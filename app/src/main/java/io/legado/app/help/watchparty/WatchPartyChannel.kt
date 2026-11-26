package io.legado.app.help.watchparty

import android.util.Base64
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.utils.LogUtils
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 记住「这个中继地址的 WebSocket 用不了」，进程内共享：
 *  否则每次断线重连都先白等 6 秒握手超时再回落 HTTP，比不用 WS 还慢。 */
private object WsProbe {
    val unsupported = java.util.concurrent.atomic.AtomicReference<String?>(null)
}

/**
 * 一条「一起看」房间通道：走 ntfy 公共中继（纯 HTTP，不需要长连接握手）。
 *
 * - 频道名 = legado- + sha256(房间码|密码) 前 24 位：不知道密码就算知道房间码也订不到；
 * - 发送：POST https://ntfy.sh/<频道>，body 是 {event, enc}，enc 为 AES-GCM 密文；
 * - 接收：流式 GET https://ntfy.sh/<频道>/raw，一行一条消息。
 *
 * 之前试过 Supabase Realtime（项目已失效）和公共 MQTT（这种网络下连接老被掐），
 * HTTP 长流最稳。
 */
class WatchPartyChannel(
    private val roomCode: String,
    private val password: String,
    val userId: String,
    val userName: String,
    private val amHost: Boolean,
    private val callbacks: Callbacks,
) {

    interface Callbacks {
        fun onConnected()
        fun onDisconnected(reason: String?)
        fun onRemoteSync(state: PartySyncState)
        fun onMembersChanged(members: List<PartyMember>)
        fun onKnock(userId: String, userName: String)
        fun onAdmitted()
        fun onRejected(reason: String?)
        fun onDecryptFailed()
        /** 房主换集 / 换片：加入端据此提前跟随 */
        fun onRemoteShow(show: PartyShowState)
        /** 房主退出了播放页（旧事件，现在等价于「房间结束」） */
        fun onHostLeftPlayer()
        /** 加入端：房主退出播放页 = 房间结束。只解除房间，不动播放器，当前集继续播 */
        fun onRoomEnded()
        /** 房主端：某个成员退出了房间（收到 bye，或心跳超时被清出） */
        fun onMemberLeft(userId: String, userName: String)
        /** 房主端：某个加入端索要当前集的短时直链 */
        fun onLinkRequest(userId: String, fid: String)
        /** 加入端：收到房主返回的短时直链 */
        fun onLinkReceived(toId: String, fid: String, url: String, expiresAt: Long, size: Long)
        /** 房主端：某个加入端回报「已就位」（缓冲好、进度对上了） */
        fun onReadyAck(userId: String)
        /** 发送结果：code<400 成功；code>=400 是中继拒收（429 限流 / 403 等）；error 为网络异常文案 */
        fun onPublishResult(event: String, code: Int, error: String?)
    }

    private companion object {
        const val TAG = "WatchPartyChannel"
        const val MEMBER_TIMEOUT_MS = 90_000L
        /** 45 秒收不到任何消息（含房主 10 秒一次的同步包）就认为长流已死，主动重连 */
        const val IDLE_RECONNECT_MS = 45_000L
    }

    /** 中继地址在会话创建时定下来，避免中途切换导致收发不同源 */
    private val baseUrl = WatchPartyConfig.relayUrl

    private val channel = "legado-" + MessageDigest.getInstance("SHA-256")
        .digest("wp|$roomCode|$password".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(24)

    private val members = LinkedHashMap<String, PartyMember>()
    private val secretKey = SecretKeySpec(
        MessageDigest.getInstance("SHA-256")
            .digest("legado-watch-party|$roomCode|$password".toByteArray(Charsets.UTF_8))
            .copyOf(16),
        "AES",
    )
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private var readerCall: okhttp3.Call? = null

    /** WebSocket 订阅句柄：优先用 WS 收，Cloudflare 不会缓冲 WS 隧道；失败自动回落到 HTTP 流 */
    private var ws: okhttp3.WebSocket? = null

    @Volatile
    private var wsReconnectPending = false

    /** WS 连续握手失败的次数：两次都失败就认定这个中继不支持 WS，本进程内不再尝试 */
    private var wsFailStreak = 0

    @Volatile
    private var closed = false

    @Volatile
    private var connected = false

    @Volatile
    private var approved = amHost

    @Volatile
    private var decryptFailureReported = false

    private var retries = 0

    /** 最近一次收到中继数据的时间，看门狗用它判断长流是否还活着 */
    @Volatile
    private var lastRxAt = 0L

    fun connect() {
        closed = false
        retries = 0
        // 订阅必须在后台线程：subscribeViaWs 里要 await 握手，之前是在 UI 线程调 connect()，
        // 中继不支持 WS 时会把「创建房间/加入房间」的界面卡住最多 6 秒
        Thread { subscribe() }.also { it.isDaemon = true }.start()
        startWatchdog()
    }

    /**
     * 长流看门狗：ntfy 的流是「一直挂着的 HTTP 响应」，网络切换或隧道抖动时读线程可能永远阻塞；
     * 这里的 readTimeout 是 0（无限），所以必须靠「多久没收到数据」来判定并主动断开重连。
     */
    private fun startWatchdog() {
        Thread {
            while (!closed) {
                try {
                    Thread.sleep(5_000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (closed) return@Thread
                val now = System.currentTimeMillis()
                if (connected && lastRxAt > 0 && now - lastRxAt > IDLE_RECONNECT_MS) {
                    LogUtils.d(TAG, "长流 ${IDLE_RECONNECT_MS}ms 无数据，主动重连")
                    runCatching { readerCall?.cancel() }
                    runCatching { ws?.cancel() }
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    fun close() {
        if (closed) return
        // 先发 bye 再置 closed：publish 里开头就有 if (closed) return，
        // 顺序反了 bye 永远发不出去，对方要等 90 秒成员超时才知道你走了
        publish("bye", selfJson())
        closed = true
        connected = false
        runCatching { readerCall?.cancel() }
        readerCall = null
        runCatching { ws?.close(1000, "bye") }
        ws = null
        synchronized(members) { members.clear() }
    }

    fun sendKnock() = publish("knock", selfJson())

    fun announce() = publish("hello", selfJson())

    /** 心跳：报「我还在」+ 我正在看哪一集，用来维持在线列表，不参与进度同步。
     *  带上 fid 是因为房主「等对方就位」时只等看同一集的人，不然没跟随的人在房间里会让房主白等。 */
    fun sendBeat(fid: String = "") = publish("beat", selfJson().apply {
        if (fid.isNotBlank()) addProperty("fid", fid)
    })

    fun sendAdmit(targetUserId: String) =
        publish("admit", JsonObject().apply { addProperty("userId", targetUserId) })

    fun sendReject(targetUserId: String, reason: String? = null) =
        publish("reject", JsonObject().apply {
            addProperty("userId", targetUserId)
            if (!reason.isNullOrBlank()) addProperty("reason", reason)
        })

    fun sendSync(state: PartySyncState) {
        if (!approved) return
        publish("sync", JsonObject().apply {
            addProperty("userId", state.userId)
            addProperty("userName", state.userName)
            addProperty("fid", state.fid)
            addProperty("episode", state.episode)
            addProperty("positionMs", state.positionMs)
            addProperty("paused", state.paused)
            addProperty("speed", state.speed)
            addProperty("stage", state.stage)
            addProperty("sentAt", System.currentTimeMillis())
        })
    }

    /** 房主换集 / 换片：只报「当前节目」，不等真正开播，加入端收到就能先跳过去 */
    fun sendShow(fid: String, episode: String, title: String, episodeTitle: String = "") {
        publish("show", JsonObject().apply {
            addProperty("userId", userId)
            addProperty("userName", userName)
            addProperty("fid", fid)
            addProperty("episode", episode)
            addProperty("title", title)
            addProperty("episodeTitle", episodeTitle)
            addProperty("sentAt", System.currentTimeMillis())
        })
    }

    /**
     * 加入端回报「我这一集已经缓冲好了、进度也对上了」。
     * 房主主动拖动/换集时靠它决定什么时候真正开播（最多等 8 秒兜底）。
     */
    fun sendReady(fid: String, positionMs: Long) {
        if (amHost || !approved) return
        publish("ready", JsonObject().apply {
            addProperty("userId", userId)
            addProperty("fid", fid)
            addProperty("positionMs", positionMs)
            addProperty("sentAt", System.currentTimeMillis())
        })
    }

    /** 房主退出播放页 */
    fun sendLeavePlayer() = publish("leaveplayer", JsonObject().apply {
        addProperty("userId", userId)
    })

    /** 房主退出播放页 = 房间结束：加入端解除房间、继续播完当前这一集，只是不能再切集 */
    fun sendRoomEnd() = publish("roomend", selfJson())

    /**
     * 加入端索要当前集直链。只带 fid 和 userId，不带任何本地路径/文件名，
     * 房主据此判断「是不是我正在播的那一集」再决定给不给。
     */
    fun sendLinkRequest(fid: String) {
        if (amHost || !approved) return
        publish("linkreq", JsonObject().apply {
            addProperty("userId", userId)
            addProperty("fid", fid)
        })
    }

    /** 房主点对点回一条短时直链：只发给申请的 userId，带有效期戳 */
    fun sendLink(toId: String, fid: String, url: String, expiresAt: Long, size: Long) {
        if (!amHost || !approved) return
        publish("link", JsonObject().apply {
            addProperty("to", toId)
            addProperty("fid", fid)
            addProperty("url", url)
            addProperty("expiresAt", expiresAt)
            addProperty("size", size)
            addProperty("sentAt", System.currentTimeMillis())
        })
    }

    // ---------------- 收 ----------------

    private fun subscribe() {
        if (closed) return
        // 先试 WebSocket：HTTP 流经 Cloudflare 会被整段缓冲（实测连 open 事件都要 4 秒），
        // WS 是 upgrade 后的隧道，不会被攒包，延迟基本等于网络往返
        if (subscribeViaWs()) return
        subscribeViaHttp()
    }

    private fun onChannelReady() {
        connected = true
        retries = 0
        wsReconnectPending = false
        lastRxAt = System.currentTimeMillis()
        callbacks.onConnected()
        // 房主直接打招呼；加入端先敲门，等房主同意后才 announce
        if (amHost || approved) publish("hello", selfJson()) else publish("knock", selfJson())
    }

    private fun subscribeViaWs(): Boolean {
        // 这个中继已确认不支持 WS：直接走 HTTP，不再每次白等 6 秒
        if (WsProbe.unsupported.get() == baseUrl) return false
        val raw = baseUrl.trimEnd('/')
        val wsUrl = when {
            raw.startsWith("https://") -> "wss://" + raw.removePrefix("https://")
            raw.startsWith("http://") -> "ws://" + raw.removePrefix("http://")
            else -> "wss://$raw"
        } + "/$channel/ws"
        val latch = java.util.concurrent.CountDownLatch(1)
        val opened = java.util.concurrent.atomic.AtomicBoolean(false)
        val socket = runCatching {
            client.newWebSocket(
                Request.Builder().url(wsUrl).build(),
                object : okhttp3.WebSocketListener() {
                    override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                        opened.set(true)
                        wsReconnectPending = false
                        wsFailStreak = 0
                        WsProbe.unsupported.set(null)
                        onChannelReady()
                        latch.countDown()
                    }

                    override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                        lastRxAt = System.currentTimeMillis()
                        val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
                            ?: return
                        // ntfy 的 WS 帧：event=open/keepalive 忽略；event=message 的业务包在 message 字段里
                        if (obj.get("event")?.asString == "message") {
                            obj.get("message")?.asString?.let { dispatchPayload(it) }
                        }
                    }

                    override fun onFailure(
                        webSocket: okhttp3.WebSocket,
                        t: Throwable,
                        response: okhttp3.Response?,
                    ) {
                        if (!closed) LogUtils.d(TAG, "WS 断开: ${t.localizedMessage}")
                        // 4xx 说明这个地址根本没有 WS（404/405/401），直接记下来别再试
                        val code = response?.code ?: 0
                        if (code in 400..499) WsProbe.unsupported.set(baseUrl)
                        connected = false
                        latch.countDown()
                        // 只有「握手成功之后掉线」才需要自己重连；握手阶段就失败，交给 subscribe() 回落 HTTP，
                        // 否则会同时挂两条 HTTP 流，同样的消息收两遍
                        if (opened.get()) wsLost()
                    }

                    override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                        connected = false
                        latch.countDown()
                        if (opened.get()) wsLost()
                    }
                },
            )
        }.getOrNull() ?: return false
        ws = socket
        val ok = try {
            latch.await(6, TimeUnit.SECONDS) && opened.get()
        } catch (_: InterruptedException) {
            false
        }
        if (!ok) {
            runCatching { socket.cancel() }
            ws = null
            wsFailStreak++
            if (wsFailStreak >= 2) WsProbe.unsupported.set(baseUrl)
        }
        return ok
    }

    private fun wsLost() {
        if (closed) return
        synchronized(this) {
            if (wsReconnectPending) return
            wsReconnectPending = true
        }
        ws = null
        callbacks.onDisconnected("通道已断开")
        scheduleReconnect()
    }

    private fun subscribeViaHttp() {
        val request = Request.Builder()
            .url("$baseUrl/$channel/raw")
            .header("Accept", "text/plain")
            .build()
        readerCall = client.newCall(request)
        Thread {
            var response: okhttp3.Response? = null
            try {
                val resp = readerCall?.execute() ?: return@Thread
                response = resp
                if (!resp.isSuccessful) {
                    callbacks.onDisconnected("中继返回 ${resp.code}")
                    scheduleReconnect()
                    return@Thread
                }
                onChannelReady()
                val source = resp.body?.source()
                while (!closed && source != null) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    lastRxAt = System.currentTimeMillis()
                    dispatchPayload(line)
                }
            } catch (e: Exception) {
                if (!closed) {
                    LogUtils.d(TAG, "订阅中断: ${e.localizedMessage}")
                }
            } finally {
                runCatching { response?.close() }
            }
            if (!closed) {
                connected = false
                callbacks.onDisconnected("通道已断开")
                scheduleReconnect()
            }
        }.also { it.isDaemon = true }.start()
    }

    // ---------------- 发 ----------------

    private fun publish(event: String, payload: JsonObject, attempt: Int = 0) {
        if (closed) return
        val inner = JsonObject().apply {
            addProperty("event", event)
            addProperty("enc", encrypt(payload.toString()))
        }
        val request = Request.Builder()
            .url("$baseUrl/$channel")
            .post(inner.toString().toRequestBody("text/plain".toMediaType()))
            .build()
        runCatching {
            client.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    // 之前这里只写日志，真机上是「静默失败」：界面看不到、logcat 也抓不到
                    LogUtils.d(TAG, "发送失败($event): ${e.localizedMessage}")
                    callbacks.onPublishResult(event, -1, e.localizedMessage)
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val code = response.code
                    response.close()
                    callbacks.onPublishResult(event, code, null)
                    // 429 = 中继限流，隔 20 秒补发一次，避免关键操作（暂停/切集）被吞
                    if (code == 429 && attempt < 1 && !closed) {
                        Thread {
                            runCatching { Thread.sleep(20_000) }
                            if (!closed) publish(event, payload, attempt + 1)
                        }.also { it.isDaemon = true }.start()
                    }
                }
            })
        }
    }

    private fun scheduleReconnect() {
        if (closed) return
        // 中继限流（429）或网络抖动时一直重试，不要放弃：放弃了房间就彻底没声音了
        val delayMs = when {
            retries <= 0 -> 3_000L
            retries <= 2 -> 8_000L
            retries <= 4 -> 15_000L
            else -> 30_000L
        }
        retries++
        Thread {
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (!closed) subscribe()
        }.start()
    }

    // ---------------- 事件 ----------------

    private fun dispatchPayload(text: String) {
        val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return
        val event = obj.get("event")?.asString ?: return
        val enc = obj.get("enc")?.asString ?: return
        val plain = decrypt(enc)
        if (plain == null) {
            if (!decryptFailureReported) {
                decryptFailureReported = true
                callbacks.onDecryptFailed()
            }
            return
        }
        val data = runCatching { JsonParser.parseString(plain).asJsonObject }.getOrNull() ?: return
        when (event) {
            "knock" -> {
                val id = data.get("userId")?.asString ?: return
                if (id == userId) return
                if (amHost) callbacks.onKnock(id, data.get("userName")?.asString ?: "有人")
            }

            "admit" -> {
                val id = data.get("userId")?.asString ?: return
                if (id == userId && !amHost) {
                    approved = true
                    callbacks.onAdmitted()
                }
            }

            "reject" -> {
                val id = data.get("userId")?.asString ?: return
                if (id == userId && !amHost) callbacks.onRejected(data.get("reason")?.asString)
            }

            "sync" -> {
                if (!approved) return
                handleSync(data)
            }

            "show" -> {
                if (!approved || amHost) return
                val id = data.get("userId")?.asString ?: return
                if (id == userId) return
                callbacks.onRemoteShow(
                    PartyShowState(
                        fid = data.get("fid")?.asString ?: "",
                        episode = data.get("episode")?.asString ?: "",
                        title = data.get("title")?.asString ?: "",
                        episodeTitle = data.get("episodeTitle")?.asString ?: "",
                        sentAt = data.get("sentAt")?.asLong ?: System.currentTimeMillis(),
                    )
                )
            }

            "ready" -> {
                val id = data.get("userId")?.asString ?: return
                if (id == userId) return
                notePeerFid(id, data.get("userName")?.asString ?: "", data.get("fid")?.asString ?: "")
                if (amHost) callbacks.onReadyAck(id)
            }

            "leaveplayer" -> {
                if (!approved || amHost) return
                val id = data.get("userId")?.asString ?: return
                if (id == userId) return
                callbacks.onHostLeftPlayer()
            }

            "roomend" -> {
                if (amHost || !approved) return
                val id = data.get("userId")?.asString ?: return
                if (id == userId) return
                callbacks.onRoomEnded()
            }

            "linkreq" -> {
                // 只有房主处理；且不处理自己发的
                if (!amHost) return
                val id = data.get("userId")?.asString ?: return
                val fid = data.get("fid")?.asString ?: return
                if (id == userId || fid.isBlank()) return
                callbacks.onLinkRequest(id, fid)
            }

            "link" -> {
                // 加入端只认发给自己的那一条，别人的直链一律忽略（防串号/防被动收集）
                if (amHost || !approved) return
                val to = data.get("to")?.asString ?: return
                if (to != userId) return
                val fid = data.get("fid")?.asString ?: return
                val url = data.get("url")?.asString ?: return
                if (url.isBlank()) return
                callbacks.onLinkReceived(to, fid, url, data.get("expiresAt")?.asLong ?: 0L, data.get("size")?.asLong ?: 0L)
            }

            "hello", "beat" -> {
                if (!approved) return
                handlePresence(data)
            }

            "bye" -> {
                val id = data.get("userId")?.asString ?: return
                val left = synchronized(members) { members.remove(id) }
                emitMembers()
                val name = left?.userName?.takeIf { it.isNotBlank() }
                    ?: data.get("userName")?.asString?.takeIf { it.isNotBlank() }
                    ?: "有人"
                if (amHost) {
                    // 房主端：告诉房主「谁退出了房间」（Session 里还有 5 秒去重 + 1.5 秒重连宽限）
                    callbacks.onMemberLeft(id, name)
                } else if (left?.isHost == true) {
                    // 加入端：bye 里走的是房主本人 → 房间结束（roomend 丢包时的兜底）
                    callbacks.onRoomEnded()
                }
            }
        }
    }

    private fun handleSync(data: JsonObject) {
        val state = PartySyncState(
            userId = data.get("userId")?.asString ?: "",
            userName = data.get("userName")?.asString ?: "",
            fid = data.get("fid")?.asString ?: "",
            episode = data.get("episode")?.asString ?: "",
            positionMs = data.get("positionMs")?.asLong ?: 0L,
            paused = data.get("paused")?.asBoolean ?: true,
            speed = data.get("speed")?.asFloat ?: 1.0f,
            stage = data.get("stage")?.asBoolean ?: false,
            sentAt = data.get("sentAt")?.asLong ?: System.currentTimeMillis(),
        )
        if (state.userId.isBlank() || state.userId == userId) return
        notePeerFid(state.userId, state.userName, state.fid)
        callbacks.onRemoteSync(state)
    }

    private fun handlePresence(data: JsonObject) {
        val id = data.get("userId")?.asString ?: return
        if (id == userId) return
        val now = System.currentTimeMillis()
        synchronized(members) {
            members[id] = PartyMember(
                userId = id,
                userName = data.get("userName")?.asString ?: "有人",
                lastSeen = now,
                isHost = data.get("host")?.asBoolean ?: false,
                fid = data.get("fid")?.asString ?: "",
            )
        }
        emitMembers()
    }

    /** 从 sync / ready 里顺手记下对方正在看哪一集，不发成员变更（避免刷界面） */
    private fun notePeerFid(id: String, userName: String, fid: String) {
        if (id == userId || id.isBlank() || fid.isBlank()) return
        val now = System.currentTimeMillis()
        synchronized(members) {
            val old = members[id]
            members[id] = if (old == null) {
                PartyMember(id, userName.ifBlank { "有人" }, now, false, fid)
            } else {
                old.copy(lastSeen = now, fid = fid)
            }
        }
    }

    private fun emitMembers() {
        val now = System.currentTimeMillis()
        val gone = mutableListOf<PartyMember>()
        val alive = synchronized(members) {
            members.values.filter { now - it.lastSeen > MEMBER_TIMEOUT_MS }.forEach { gone += it }
            members.entries.removeAll { now - it.value.lastSeen > MEMBER_TIMEOUT_MS }
            members.values.toList()
        }
        callbacks.onMembersChanged(alive)
        // 心跳超时被清出的人也要提示一次（cold 退出的客户端不会发 bye）
        if (amHost) gone.forEach { callbacks.onMemberLeft(it.userId, it.userName) }
    }

    private fun selfJson(): JsonObject = JsonObject().apply {
        addProperty("userId", userId)
        addProperty("userName", userName)
        addProperty("host", amHost)
    }

    // ---------------- 加密 ----------------

    private fun encrypt(plain: String): String {
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val out = ByteArray(iv.size + cipherText.size)
        iv.copyInto(out, 0)
        cipherText.copyInto(out, iv.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String? = runCatching {
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        if (data.size <= 12) return@runCatching null
        val iv = data.copyOfRange(0, 12)
        val cipherText = data.copyOfRange(12, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }.getOrNull()
}


