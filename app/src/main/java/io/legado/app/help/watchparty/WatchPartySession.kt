package io.legado.app.help.watchparty

import android.content.Context
import io.legado.app.service.WatchPartyService
import io.legado.app.utils.LogUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.util.UUID

/**
 * 一起看的会话管理。
 *
 * 角色约定（不做抢麦）：房主广播状态，其他人跟随。
 * 加入流程：
 *   客机连接 → 发 knock（敲门）→ 房主收到「XX 请求加入」→ 房主同意/admit 或拒绝/reject
 * 广播载荷用「房间码 + 密码」派生的 AES 密钥加密，光有房间码看不到内容。
 */
object WatchPartySession {

    private const val TAG = "WatchPartySession"
    private const val PREF = "watch_party"
    /** 邀请码有效期：5 分钟（之前 2 分钟，房主截图发码经常来不及） */
    const val INVITE_WINDOW_MS = 300_000L
    /** 后台心跳步长（房主 10 秒一次同步，加入端 3 步报一次在线） */
    private const val HEARTBEAT_STEP_MS = 10_000L

    private val _state = MutableStateFlow(WatchPartyState())
    val state = _state.asStateFlow()

    private var channel: WatchPartyChannel? = null
    private var lastBroadcastAt = 0L
    /** 本房间内已被房主同意过的设备，退出再进免确认、不受 2 分钟限制 */
    private val approvedIds = mutableSetOf<String>()

    // ---- 短时直链（本地缺这一集时的兜底）防泄露参数 ----
    /** 同一集 60 秒内只允许请求一次，避免有人反复刷着要链接 */
    private const val LINK_REQ_COOLDOWN_MS = 60_000L
    /** 每个房间最多给 3 条直链，防止被当成「多集批量抓链」的工具 */
    private const val LINK_MAX_PER_ROOM = 3
    /** 直链有效期：2 分钟（夸克直链本身也是短时的，过期就别用了） */
    private const val LINK_TTL_MS = 120_000L

    /** fid -> 上次请求时间，做冷却 */
    private val linkReqAt = mutableMapOf<String, Long>()
    /** 房主已经给出过几条直链 */
    private var linkSentCount = 0
    /** 本机正在等哪一集（fid）的直链，收到 link 时用它校验「是不是我请求的那一集」 */
    @Volatile
    private var pendingLinkFid: String? = null

    /** 心跳跑在自己的后台线程上：熄屏后 Compose 的帧调度会停，不能把心跳挂在 UI 上 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var heartbeatJob: Job? = null

    /** 最近一次本机播放状态快照，熄屏期间靠它继续上报 */
    @Volatile
    private var localSnapshot: PartySyncState? = null

    @Volatile
    private var snapshotAt = 0L

    /** 房主最近一次广播的「当前节目」；有新成员进房时按它补发一次，新来的人不用等 10 秒心跳 */
    @Volatile
    private var lastShow: PartyShowState? = null
    /** 房主端：各加入端回报「已就位」的时间戳（userId -> ackAt） */
    private val readyAcks = mutableMapOf<String, Long>()

    private fun pref() = appCtx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun myId(): String {
        val cached = pref().getString("user_id", null)
        if (!cached.isNullOrBlank()) return cached
        val id = UUID.randomUUID().toString()
        pref().edit().putString("user_id", id).apply()
        return id
    }

    /** 生成房间码：6 位，去掉容易看错的 O/0/I/1 */
    fun newRoomCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { chars.random() }.joinToString("")
    }

    /** 生成 8 位密码：大小写字母 + 数字，去掉容易看错的 0/O/o/1/l/I */
    fun newPassword(): String {
        val chars = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..8).map { chars.random() }.joinToString("")
    }

    /** 邀请码 = 房间码-密码，例如 XN3GZ9-K7m3Qp2a */
    fun inviteOf(code: String, password: String): String = "$code-$password"

    /** 解析邀请码：容忍空格、连字符、中文前缀；6 位码 + 8 位密码（也兼容旧的 6+4） */
    fun parseInvite(raw: String): Pair<String, String>? {
        val cleaned = raw.replace(Regex("[^A-Za-z0-9]"), "")
        return when {
            cleaned.length >= 14 -> cleaned.take(6).uppercase() to cleaned.substring(6, 14)
            cleaned.length == 10 -> cleaned.take(6).uppercase() to cleaned.substring(6)
            else -> null
        }
    }

    /** 建房：房间码、密码全自动生成，返回邀请码 */
    fun createRoom(): Result<String> {
        val code = newRoomCode()
        val password = newPassword()
        start(code, password, host = true)
        return Result.success(inviteOf(code, password))
    }

    /** 加入：粘贴整串邀请码即可 */
    fun joinRoom(invite: String): Result<String> {
        val parsed = parseInvite(invite)
            ?: return Result.failure(IllegalArgumentException("邀请码不对，格式是 6 位房间码-8 位密码"))
        val (code, password) = parsed
        if (password.isBlank()) {
            return Result.failure(IllegalArgumentException("邀请码里缺少密码"))
        }
        start(code, password, host = false)
        return Result.success(inviteOf(code, password))
    }

    private fun start(code: String, password: String, host: Boolean) {
        leave()
        approvedIds.clear()
        // 直链相关状态按房间重置，别把上个房间的冷却/计数带过来
        linkReqAt.clear()
        linkSentCount = 0
        pendingLinkFid = null
        if (host) approvedIds.addAll(loadApproved(code))
        val uid = myId()
        val name = WatchPartyConfig.nameOrDefault()
        val now = System.currentTimeMillis()
        _state.value = WatchPartyState(
            active = true,
            code = code,
            password = password,
            isHost = host,
            approved = host,
            connecting = true,
            connected = false,
            myId = uid,
            myName = name,
            inviteExpiresAt = if (host) now + INVITE_WINDOW_MS else 0L,
        )
        val ch = WatchPartyChannel(
            roomCode = code,
            password = password,
            userId = uid,
            userName = name,
            amHost = host,
            callbacks = object : WatchPartyChannel.Callbacks {
                override fun onConnected() {
                    LogUtils.d(TAG, "房间已连接：$code")
                    _state.update { it.copy(connecting = false, connected = true, error = null) }
                }

                override fun onDisconnected(reason: String?) {
                    _state.update {
                        it.copy(connecting = false, connected = false, error = reason)
                    }
                }

                override fun onRemoteSync(state: PartySyncState) {
                    _state.update { it.copy(remoteSync = state) }
                }

                override fun onMembersChanged(members: List<PartyMember>) {
                    val before = _state.value.members.map { it.userId }.toSet()
                    _state.update { current ->
                        val me = PartyMember(
                            userId = current.myId,
                            userName = current.myName,
                            lastSeen = System.currentTimeMillis(),
                            isHost = current.isHost,
                        )
                        // 被同意的人真正开始报在线（hello/beat）后才把申请条目移除：
                        // 之前点完「同意」立刻删，admit 丢了房间里就只剩房主一个人还浑然不知
                        val joinedIds = members.map { it.userId }.toSet()
                        current.copy(
                            members = (listOf(me) + members).distinctBy { it.userId },
                            pending = current.pending.filterNot { it.userId in joinedIds },
                            approving = current.approving - joinedIds,
                        )
                    }
                    // 有新成员刚进房：房主补发一次「当前节目」，新来的人不用干等 10 秒心跳
                    // （否则加入端只能拿到不含剧名/集标题的 sync，标题会回退成「一起看」）
                    val cur = _state.value
                    val after = cur.members.map { it.userId }.toSet()
                    val newlyJoined = after - before - cur.myId
                    if (cur.isHost && cur.approved && newlyJoined.isNotEmpty()) {
                        lastShow?.let { s ->
                            channel?.sendShow(s.fid, s.episode, s.title, s.episodeTitle)
                        }
                    }
                }

                override fun onKnock(userId: String, userName: String) {
                    // 房主侧准入控制：老熟人直接放行；邀请码过期直接拒；否则排队等房主点同意
                    if (_state.value.isHost) {
                        if (approvedIds.contains(userId)) {
                            channel?.sendAdmit(userId)
                            return
                        }
                        if (System.currentTimeMillis() > _state.value.inviteExpiresAt) {
                            channel?.sendReject(userId, "邀请码已过期，请房主重新开启邀请")
                            return
                        }
                    }
                    _state.update { current ->
                        if (current.pending.any { it.userId == userId }) {
                            current
                        } else {
                            current.copy(
                                pending = current.pending + PartyMember(userId, userName)
                            )
                        }
                    }
                }

                override fun onAdmitted() {
                    channel?.announce()
                    _state.update {
                        it.copy(approved = true, error = null, pending = emptyList())
                    }
                }

                override fun onRemoteShow(show: PartyShowState) {
                    _state.update { it.copy(show = show, hostLeft = false) }
                }

                override fun onHostLeftPlayer() {
                    _state.update { it.copy(hostLeft = true) }
                }

                override fun onReadyAck(userId: String) {
                    readyAcks[userId] = System.currentTimeMillis()
                }

                override fun onLinkRequest(userId: String, fid: String) {
                    // 只服务已经同意的成员；陌生人即使知道邀请码也拿不到直链
                    if (!_state.value.isHost || !approvedIds.contains(userId)) return
                    _state.update { it.copy(linkRequest = PartyLinkRequest(userId, fid)) }
                }

                override fun onLinkReceived(toId: String, fid: String, url: String, expiresAt: Long, size: Long) {
                    val cur = _state.value
                    if (cur.isHost) return
                    // 只认「我请求过的那一集」，防止同房间别人拿到的链接被复用/串号
                    if (pendingLinkFid == null || pendingLinkFid != fid) return
                    if (expiresAt > 0 && System.currentTimeMillis() > expiresAt) return
                    _state.update { it.copy(incomingLink = PartyLink(toId, fid, url, expiresAt, size)) }
                }

                override fun onRejected(reason: String?) {
                    leave()
                    _state.update { it.copy(error = reason ?: "房主拒绝了加入请求") }
                }

                override fun onDecryptFailed() {
                    _state.update { it.copy(error = "密码不对，无法读取房间内容") }
                }

                override fun onPublishResult(event: String, code: Int, error: String?) {
                    // 之前发布失败是静默的，真机上只能看到「房主收得到、发不出」，这里把结果摊到界面上
                    val msg = when {
                        code in 200..299 -> null
                        code < 0 -> "发不出去（$event）：${error ?: "网络异常"}"
                        else -> "中继拒收 $code（$event）"
                    }
                    _state.update { it.copy(publishError = msg) }
                }
            },
        )
        channel = ch
        ch.connect()
        // 前台服务把进程钉住，熄屏（小米会冻结缓存进程）时心跳和长流才不会被掐断
        WatchPartyService.start(appCtx)
        startHeartbeat()
    }

    /**
     * 后台心跳：房主每 10 秒补发一次 sync（与原播放页心跳一致），加入端每 30 秒报一次在线。
     * 播放位置按流逝时间外推（暂停时不外推），熄屏期间另一端看到的进度不会突然冻结在旧值。
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            var tick = 0
            while (isActive) {
                delay(HEARTBEAT_STEP_MS)
                tick++
                val ch = channel ?: continue
                val current = _state.value
                // dataSync 前台服务在 Android 15 上有 6 小时上限（实测 08:18 被系统
                // "FGS (dataSync) timed out" 掐掉，随后进程被冻结、长流断掉），
                // 这里每个心跳周期兜一次：服务没了就重新拉起。
                if (!WatchPartyService.isRunning) {
                    LogUtils.d(TAG, "保活服务不在了，重新拉起")
                    WatchPartyService.start(appCtx)
                }
                if (!current.active || !current.connected) continue
                if (!current.approved) {
                    // 还没被放行：每 10 秒补敲一次门。房主那边 approvedIds 是白名单，
                    // 收到会再发一次 admit，所以 admit 丢包/断流也能自愈，不会永远卡在「等待房主同意」
                    ch.sendKnock()
                    continue
                }
                if (current.isHost) {
                    publishSnapshot(ch)
                } else if (tick % 3 == 0) {
                    ch.sendBeat()
                }
            }
        }
    }

    private fun publishSnapshot(ch: WatchPartyChannel) {
        val snap = localSnapshot ?: return
        val now = System.currentTimeMillis()
        val elapsed = now - snapshotAt
        val position = if (snap.paused) snap.positionMs
        else snap.positionMs + (elapsed * snap.speed).toLong()
        lastBroadcastAt = now
        ch.sendSync(snap.copy(positionMs = position, sentAt = now))
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    fun approve(userId: String) {
        approvedIds.add(userId)
        saveApproved(_state.value.code, approvedIds)
        channel?.sendAdmit(userId)
        // 不立刻从 pending 里删：等对方真的报在线（onMembersChanged）再删，
        // 否则 admit 丢了房主会以为成功、对方却还卡在等待
        _state.update { it.copy(approving = it.approving + userId) }
        LogUtils.d(TAG, "同意加入：$userId")
    }

    /** 房主换集 / 换片：把「当前节目」广播出去，加入端据此提前跟随 */
    fun notifyShow(fid: String, episode: String, title: String, episodeTitle: String = "") {
        if (!_state.value.isHost) return
        val ch = channel ?: return
        val show = PartyShowState(
            fid = fid,
            episode = episode,
            title = title,
            episodeTitle = episodeTitle,
            sentAt = System.currentTimeMillis(),
        )
        lastShow = show
        _state.update { it.copy(show = show, hostLeft = false) }
        ch.sendShow(fid, episode, title, episodeTitle)
    }

    /** 房主端：还要等哪些成员回报「已就位」。
     *  只算「刚还在报在线」的已同意成员：已经退出的人不能每次拖动都拖住房主 8 秒。 */
    fun peersNeedReady(): Set<String> {
        val cur = _state.value
        if (!cur.isHost) return emptySet()
        val now = System.currentTimeMillis()
        return cur.members.asSequence()
            .filter { it.userId != cur.myId && now - it.lastSeen < 25_000L }
            .map { it.userId }
            .filter { it in approvedIds }
            .toSet()
    }

    /** 房主端：开始一轮就位等待前先清空旧回执，避免上一轮的 ack 被误当成本轮 */
    fun clearReadyAcks() = readyAcks.clear()

    fun readyAckAt(userId: String): Long = readyAcks[userId] ?: 0L

    /** 加入端：本机缓冲就绪、进度也对上了，回报一声 */
    fun notifyReady(fid: String, positionMs: Long) {
        val cur = _state.value
        if (cur.isHost || !cur.approved || fid.isBlank()) return
        channel?.sendReady(fid, positionMs)
    }

    /** 房主退出了播放页：加入端暂停并等待，别再按旧进度外推 */
    fun notifyHostLeftPlayer() {
        if (!_state.value.isHost) return
        _state.update { it.copy(hostLeft = true) }
        channel?.sendLeavePlayer()
    }

    fun reject(userId: String) {
        channel?.sendReject(userId, "房主拒绝了加入请求")
        approvedIds.remove(userId)
        saveApproved(_state.value.code, approvedIds)
        _state.update { current ->
            current.copy(
                pending = current.pending.filterNot { it.userId == userId },
                approving = current.approving - userId,
            )
        }
    }

    /** 加入端手动点「跟随房主当前节目」：把请求交给主页面，走同一套 3 秒倒计时 */
    fun requestFollow() {
        val show = _state.value.show ?: return
        if (_state.value.isHost || !_state.value.approved) return
        _state.update { it.copy(followRequest = show) }
    }

    fun consumeFollowRequest() {
        _state.update { it.copy(followRequest = null) }
    }

    /** 加入端：本地没有房主正在播的这一集时，向他索要一次短时直链（带冷却，不会刷屏） */
    fun requestLink(fid: String) {
        val cur = _state.value
        if (!cur.active || cur.isHost || !cur.approved || fid.isBlank()) return
        val now = System.currentTimeMillis()
        val last = linkReqAt[fid]
        if (last != null && now - last < LINK_REQ_COOLDOWN_MS) return
        linkReqAt[fid] = now
        pendingLinkFid = fid
        channel?.sendLinkRequest(fid)
    }

    /**
     * 房主：把当前集的直链点对点发给申请的成员。
     * 只发给已同意的成员、每个房间最多 3 条、有效期 120 秒，且不落盘、不打印日志。
     */
    fun respondLink(userId: String, fid: String, url: String, size: Long) {
        val cur = _state.value
        if (!cur.isHost || !cur.active) return
        if (!approvedIds.contains(userId)) return
        if (url.isBlank()) return
        if (linkSentCount >= LINK_MAX_PER_ROOM) return
        linkSentCount++
        // size 一起给：加入端播放前要校验文件大小，缺了会直接弹「无法获取视频文件大小」
        channel?.sendLink(userId, fid, url, System.currentTimeMillis() + LINK_TTL_MS, size)
        _state.update { it.copy(linkRequest = null) }
    }

    fun consumeLinkRequest() {
        _state.update { it.copy(linkRequest = null) }
    }

    fun consumeIncomingLink() {
        pendingLinkFid = null
        _state.update { it.copy(incomingLink = null) }
    }

    /** 放弃等待直链（比如 15 秒没等到、或用户取消了跟随） */
    fun abandonPendingLink() {
        pendingLinkFid = null
    }

    private fun approvedPrefKey(code: String) = "approved_$code"

    private fun loadApproved(code: String): Set<String> =
        pref().getStringSet(approvedPrefKey(code), emptySet()) ?: emptySet()

    /** 房主进程被杀重启后，老成员回来仍免审批 */
    private fun saveApproved(code: String, ids: Set<String>) {
        if (code.isBlank()) return
        runCatching {
            pref().edit().putStringSet(approvedPrefKey(code), ids.toSet()).apply()
        }
    }

    /** 房主重新开启邀请：只重置倒计时，不换码（换码会把已进房的人踢掉） */
    fun reopenInvite() {
        if (!_state.value.isHost) return
        val until = System.currentTimeMillis() + INVITE_WINDOW_MS
        _state.update {
            it.copy(inviteExpiresAt = until, publishError = null)
        }
        LogUtils.d(TAG, "重新开启邀请，截止 $until")
    }

    fun leave() {
        stopHeartbeat()
        WatchPartyService.stop(appCtx)
        localSnapshot = null
        val ch = channel
        channel = null
        runCatching { ch?.close() }
        _state.value = WatchPartyState()
    }

    /**
     * 任意一端把自己的播放状态推上去（房主/加入端都能操作，按「最后操作生效」）。
     * 最少 2.5 秒一次：ntfy 公共中继按 IP 限流 60 次请求/5 分钟，推得太勤会被 429 丢掉。
     */
    fun pushLocalSync(
        fid: String,
        episode: String,
        positionMs: Long,
        paused: Boolean,
        speed: Float = 1.0f,
        force: Boolean = false,
        stage: Boolean = false,
    ) {
        val ch = channel ?: return
        if (!_state.value.connected || !_state.value.approved) return
        // 不管这次推不推得出去，都先把状态存下来：熄屏后后台心跳要靠它
        val snap = PartySyncState(
            userId = _state.value.myId,
            userName = _state.value.myName,
            fid = fid,
            episode = episode,
            positionMs = positionMs,
            paused = paused,
            speed = speed,
            stage = stage,
            sentAt = System.currentTimeMillis(),
        )
        localSnapshot = snap
        snapshotAt = snap.sentAt
        val now = System.currentTimeMillis()
        if (!force && now - lastBroadcastAt < 2_500L) return
        lastBroadcastAt = now
        ch.sendSync(snap)
    }
}
