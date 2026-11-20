package io.legado.app.help.watchparty

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.utils.LogUtils
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 一条 Supabase Realtime 房间通道。
 *
 * 走 Phoenix 协议（版本 1.0.0，JSON 文本帧），不引 supabase SDK：
 * - 连接：wss://<ref>.supabase.co/realtime/v1/websocket?apikey=..&vsn=1.0.0
 * - 加入：{topic, event:"phx_join", payload:{config:{broadcast:{ack:false,self:true}}}, ref, join_ref}
 * - 发送：{topic, event:"broadcast", payload:{type:"broadcast", event:"sync", payload:{...}|{enc:"..."}, ref}
 * - 保活：每 30 秒 {topic:"phoenix", event:"heartbeat", payload:{}}
 *
 * 隐私：频道是公开的，但只要广播载荷用「房间码 + 密码」派生密钥做 AES-GCM 加密，
 * 光知道房间码的人拿到的也是密文。
 */
class WatchPartyChannel(
    baseUrl: String,
    apiKey: String,
    roomCode: String,
    password: String,
    val userId: String,
    val userName: String,
    private val amHost: Boolean,
    private val callbacks: Callbacks,
) {

    interface Callbacks {
        fun onConnected()
        fun onDisconnected(reason: String?)
        fun onRemoteSync(state: PartySyncState)
        fun onChat(message: PartyChatMessage)
        fun onMembersChanged(members: List<PartyMember>)
        /** 有人敲门（房主专用） */
        fun onKnock(userId: String, userName: String)
        /** 自己被批准进入 */
        fun onAdmitted()
        /** 自己被拒绝 */
        fun onRejected()
        /** 密码不对或密文损坏 */
        fun onDecryptFailed()
    }

    private companion object {
        const val TAG = "WatchPartyChannel"
        const val HEARTBEAT_MS = 30_000L
        const val MEMBER_TIMEOUT_MS = 90_000L
    }

    private val gson = Gson()
    private val topic = "realtime:watchparty_$roomCode"
    private val ref = AtomicInteger(0)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val url = buildUrl(baseUrl, apiKey)
    private val members = LinkedHashMap<String, PartyMember>()
    private val secretKey = SecretKeySpec(
        MessageDigest.getInstance("SHA-256")
            .digest("legado-watch-party|$roomCode|$password".toByteArray(Charsets.UTF_8))
            .copyOf(16),
        "AES",
    )
    private var socket: WebSocket? = null
    private var timer: Timer? = null
    private var retries = 0

    @Volatile
    private var closed = false

    @Volatile
    private var joined = false

    /** 房主天然已批准；客机要等房主 admit */
    @Volatile
    private var approved = amHost

    @Volatile
    private var decryptFailureReported = false

    fun connect() {
        closed = false
        retries = 0
        open()
    }

    fun close() {
        closed = true
        stopHeartbeat()
        runCatching { socket?.send(buildBroadcastJson("bye", selfJson())) }
        runCatching { socket?.close(1000, "leave") }
        socket = null
        synchronized(members) { members.clear() }
    }

    fun sendKnock() {
        socket?.send(buildBroadcastJson("knock", selfJson()))
    }

    /** 被批准后立刻报到，让房主端的成员列表马上出现自己 */
    fun announce() {
        socket?.send(buildBroadcastJson("hello", selfJson()))
    }

    fun sendAdmit(targetUserId: String) {
        socket?.send(
            buildBroadcastJson(
                "admit",
                JsonObject().apply { addProperty("userId", targetUserId) })
        )
    }

    fun sendReject(targetUserId: String) {
        socket?.send(
            buildBroadcastJson(
                "reject",
                JsonObject().apply { addProperty("userId", targetUserId) })
        )
    }

    fun sendSync(state: PartySyncState) {
        if (!approved) return
        val payload = JsonObject().apply {
            addProperty("userId", state.userId)
            addProperty("userName", state.userName)
            addProperty("fid", state.fid)
            addProperty("episode", state.episode)
            addProperty("positionMs", state.positionMs)
            addProperty("paused", state.paused)
            addProperty("sentAt", System.currentTimeMillis())
        }
        socket?.send(buildBroadcastJson("sync", payload))
    }

    fun sendChat(text: String) {
        if (!approved) return
        val payload = JsonObject().apply {
            addProperty("userId", userId)
            addProperty("userName", userName)
            addProperty("text", text)
            addProperty("sentAt", System.currentTimeMillis())
        }
        socket?.send(buildBroadcastJson("chat", payload))
    }

    // ---------------- 连接与消息 ----------------

    private fun open() {
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            retries = 0
            sendJoin(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleMessage(text)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (closed) return
            LogUtils.d(TAG, "连接失败: ${t.localizedMessage}")
            joined = false
            callbacks.onDisconnected(t.localizedMessage ?: "连接失败")
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (closed) return
            joined = false
            callbacks.onDisconnected(reason.ifBlank { "连接已关闭" })
            scheduleReconnect()
        }
    }

    private fun sendJoin(webSocket: WebSocket) {
        val joinRef = ref.incrementAndGet().toString()
        val msg = JsonObject().apply {
            addProperty("topic", topic)
            addProperty("event", "phx_join")
            add("payload", JsonObject().apply {
                add("config", JsonObject().apply {
                    add("broadcast", JsonObject().apply {
                        addProperty("ack", false)
                        addProperty("self", true)
                    })
                    add("presence", JsonObject().apply { addProperty("enabled", false) })
                    addProperty("private", false)
                })
            })
            addProperty("ref", joinRef)
            addProperty("join_ref", joinRef)
        }
        webSocket.send(gson.toJson(msg))
    }

    private fun handleMessage(text: String) {
        val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return
        when (obj.get("event")?.asString) {
            "phx_reply" -> {
                val payload = obj.getAsJsonObject("payload")
                val status = payload?.get("status")?.asString
                if (status == "ok") {
                    joined = true
                    callbacks.onConnected()
                    startHeartbeat()
                    socket?.send(buildBroadcastJson("hello", selfJson()))
                    if (!amHost) sendKnock()
                } else {
                    callbacks.onDisconnected("加入房间失败：${payload?.get("response")}")
                }
            }

            "broadcast" -> {
                val payload = obj.getAsJsonObject("payload") ?: return
                val event = payload.get("event")?.asString ?: return
                val data = decryptPayload(payload) ?: return
                when (event) {
                    "knock" -> {
                        val id = data.get("userId")?.asString ?: return
                        if (id == userId) return
                        if (amHost) {
                            callbacks.onKnock(id, data.get("userName")?.asString ?: "有人")
                        }
                    }

                    "admit" -> {
                        val id = data.get("userId")?.asString ?: return
                        if (id == userId && !amHost) {
                            approved = true
                            callbacks.onAdmitted()
                        } else if (id != userId) {
                            synchronized(members) {
                                members[id]?.let { m ->
                                    members[id] = m.copy(isHost = false, lastSeen = System.currentTimeMillis())
                                }
                            }
                            emitMembers()
                        }
                    }

                    "reject" -> {
                        val id = data.get("userId")?.asString ?: return
                        if (id == userId && !amHost) {
                            callbacks.onRejected()
                        }
                    }

                    "sync" -> {
                        if (!approved) return
                        handleSync(data)
                    }

                    "chat" -> {
                        if (!approved) return
                        handleChat(data)
                    }

                    "hello", "beat" -> {
                        if (!approved) return
                        handlePresence(data)
                    }

                    "bye" -> {
                        val id = data.get("userId")?.asString ?: return
                        synchronized(members) { members.remove(id) }
                        emitMembers()
                    }
                }
            }

            "phx_error", "phx_close" -> {
                joined = false
                callbacks.onDisconnected("房间连接已断开")
                scheduleReconnect()
            }
        }
    }

    /** 解出真正的事件载荷：优先解密密文，兼容未加密的旧格式 */
    private fun decryptPayload(payload: JsonObject): JsonObject? {
        val enc = payload.get("enc")?.asString
        if (enc != null) {
            val plain = decrypt(enc)
            if (plain == null) {
                if (!decryptFailureReported) {
                    decryptFailureReported = true
                    callbacks.onDecryptFailed()
                }
                return null
            }
            return runCatching { JsonParser.parseString(plain).asJsonObject }.getOrNull()
        }
        return payload.getAsJsonObject("payload")
    }

    private fun handleSync(data: JsonObject) {
        val state = PartySyncState(
            userId = data.get("userId")?.asString ?: "",
            userName = data.get("userName")?.asString ?: "",
            fid = data.get("fid")?.asString ?: "",
            episode = data.get("episode")?.asString ?: "",
            positionMs = data.get("positionMs")?.asLong ?: 0L,
            paused = data.get("paused")?.asBoolean ?: true,
            sentAt = data.get("sentAt")?.asLong ?: System.currentTimeMillis(),
        )
        if (state.userId.isBlank() || state.userId == userId) return
        callbacks.onRemoteSync(state)
    }

    private fun handleChat(data: JsonObject) {
        val msg = PartyChatMessage(
            userId = data.get("userId")?.asString ?: "",
            userName = data.get("userName")?.asString ?: "",
            text = data.get("text")?.asString ?: "",
            sentAt = data.get("sentAt")?.asLong ?: System.currentTimeMillis(),
            mine = (data.get("userId")?.asString ?: "") == userId,
        )
        if (msg.userId.isBlank()) return
        callbacks.onChat(msg)
    }

    private fun handlePresence(data: JsonObject) {
        val id = data.get("userId")?.asString ?: return
        if (id == userId) return
        val name = data.get("userName")?.asString ?: "有人"
        val isHost = data.get("host")?.asBoolean ?: false
        val now = System.currentTimeMillis()
        synchronized(members) {
            members[id] = PartyMember(id, name, now, isHost)
        }
        emitMembers()
    }

    private fun emitMembers() {
        val now = System.currentTimeMillis()
        val alive = synchronized(members) {
            members.entries.removeAll { now - it.value.lastSeen > MEMBER_TIMEOUT_MS }
            members.values.toList()
        }
        callbacks.onMembersChanged(alive)
    }

    private fun selfJson(): JsonObject = JsonObject().apply {
        addProperty("userId", userId)
        addProperty("userName", userName)
        addProperty("host", amHost)
    }

    private fun buildBroadcastJson(event: String, payload: JsonObject): String {
        val inner = JsonObject().apply {
            addProperty("type", "broadcast")
            addProperty("event", event)
            addProperty("enc", encrypt(payload.toString()))
        }
        val msg = JsonObject().apply {
            addProperty("topic", topic)
            addProperty("event", "broadcast")
            add("payload", inner)
            addProperty("ref", ref.incrementAndGet().toString())
        }
        return gson.toJson(msg)
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        timer = Timer("watch-party-heartbeat", true).apply {
            schedule(object : TimerTask() {
                override fun run() {
                    if (closed) return
                    val heartbeat = JsonObject().apply {
                        addProperty("topic", "phoenix")
                        addProperty("event", "heartbeat")
                        add("payload", JsonObject())
                        addProperty("ref", ref.incrementAndGet().toString())
                    }
                    runCatching { socket?.send(gson.toJson(heartbeat)) }
                    runCatching { socket?.send(buildBroadcastJson("beat", selfJson())) }
                    emitMembers()
                }
            }, HEARTBEAT_MS, HEARTBEAT_MS)
        }
    }

    private fun stopHeartbeat() {
        timer?.cancel()
        timer = null
    }

    private fun scheduleReconnect() {
        stopHeartbeat()
        if (closed) return
        if (retries >= 3) {
            callbacks.onDisconnected("重连失败，请检查网络或 Supabase 地址/密钥")
            return
        }
        val delayMs = when (retries) {
            0 -> 2_000L
            1 -> 5_000L
            else -> 10_000L
        }
        retries++
        socket = null
        Thread {
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (!closed) open()
        }.start()
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

    private fun buildUrl(base: String, key: String): String {
        var host = base.trim().removeSuffix("/")
        host = host.removeSuffix("/realtime/v1/websocket").removeSuffix("/realtime/v1")
        host = when {
            host.startsWith("wss://") || host.startsWith("ws://") -> host
            host.startsWith("https://") -> "wss://" + host.removePrefix("https://")
            host.startsWith("http://") -> "ws://" + host.removePrefix("http://")
            else -> "wss://$host"
        }
        val encodedKey = URLEncoder.encode(key, "UTF-8")
        return "$host/realtime/v1/websocket?apikey=$encodedKey&vsn=1.0.0"
    }
}
