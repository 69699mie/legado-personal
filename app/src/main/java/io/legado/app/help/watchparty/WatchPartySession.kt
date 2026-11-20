package io.legado.app.help.watchparty

import android.content.Context
import io.legado.app.utils.LogUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

    private val _state = MutableStateFlow(WatchPartyState())
    val state = _state.asStateFlow()

    private var channel: WatchPartyChannel? = null
    private var lastBroadcastAt = 0L

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

    /** 生成 4 位数字密码 */
    fun newPassword(): String = (1..4).map { (0..9).random() }.joinToString("")

    fun createRoom(password: String): Result<String> {
        if (!WatchPartyConfig.isReady) {
            return Result.failure(IllegalStateException("请先在设置里填写 Supabase 地址和密钥"))
        }
        if (password.isBlank()) {
            return Result.failure(IllegalArgumentException("请设置房间密码"))
        }
        val code = newRoomCode()
        start(code, password, host = true)
        return Result.success(code)
    }

    fun joinRoom(code: String, password: String): Result<String> {
        val normalized = code.trim().uppercase()
        if (normalized.length != 6) {
            return Result.failure(IllegalArgumentException("房间码是 6 位"))
        }
        if (password.isBlank()) {
            return Result.failure(IllegalArgumentException("请输入房间密码"))
        }
        if (!WatchPartyConfig.isReady) {
            return Result.failure(IllegalStateException("请先在设置里填写 Supabase 地址和密钥"))
        }
        start(normalized, password, host = false)
        return Result.success(normalized)
    }

    private fun start(code: String, password: String, host: Boolean) {
        leave()
        val uid = myId()
        val name = WatchPartyConfig.nameOrDefault()
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
        )
        val ch = WatchPartyChannel(
            baseUrl = WatchPartyConfig.url,
            apiKey = WatchPartyConfig.apiKey,
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
                    _state.update { current ->
                        val me = PartyMember(
                            userId = current.myId,
                            userName = current.myName,
                            lastSeen = System.currentTimeMillis(),
                            isHost = current.isHost,
                        )
                        current.copy(members = (listOf(me) + members).distinctBy { it.userId })
                    }
                }

                override fun onKnock(userId: String, userName: String) {
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

                override fun onRejected() {
                    leave()
                    _state.update { it.copy(error = "房主拒绝了加入请求") }
                }

                override fun onDecryptFailed() {
                    _state.update { it.copy(error = "密码不对，无法读取房间内容") }
                }
            },
        )
        channel = ch
        ch.connect()
    }

    fun approve(userId: String) {
        channel?.sendAdmit(userId)
        _state.update { current ->
            current.copy(pending = current.pending.filterNot { it.userId == userId })
        }
    }

    fun reject(userId: String) {
        channel?.sendReject(userId)
        _state.update { current ->
            current.copy(pending = current.pending.filterNot { it.userId == userId })
        }
    }

    fun leave() {
        val ch = channel
        channel = null
        runCatching { ch?.close() }
        _state.value = WatchPartyState()
    }

    /**
     * 房主把自己的播放状态推上去。
     * 1 秒内最多推一次，避免频繁 seek 时刷屏。
     */
    fun pushLocalSync(fid: String, episode: String, positionMs: Long, paused: Boolean, force: Boolean = false) {
        val ch = channel ?: return
        if (!_state.value.isHost || !_state.value.connected) return
        val now = System.currentTimeMillis()
        if (!force && now - lastBroadcastAt < 1_000L) return
        lastBroadcastAt = now
        ch.sendSync(
            PartySyncState(
                userId = _state.value.myId,
                userName = _state.value.myName,
                fid = fid,
                episode = episode,
                positionMs = positionMs,
                paused = paused,
                sentAt = now,
            )
        )
    }
}
