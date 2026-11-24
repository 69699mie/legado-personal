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
        subscribe()
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
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    fun close() {
        closed = true
        connected = false
        publish("bye", selfJson())
        runCatching { readerCall?.cancel() }
        readerCall = null
        synchronized(members) { members.clear() }
    }

    fun sendKnock() = publish("knock", selfJson())

    fun announce() = publish("hello", selfJson())

    /** 心跳：只报「我还在」，用来维持在线列表，不参与同步 */
    fun sendBeat() = publish("beat", selfJson())

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
            addProperty("sentAt", System.currentTimeMillis())
        })
    }

    // ---------------- 收 ----------------

    private fun subscribe() {
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
                connected = true
                retries = 0
                lastRxAt = System.currentTimeMillis()
                callbacks.onConnected()
                // 房主直接打招呼；加入端先敲门，等房主同意后才 announce
                if (amHost || approved) publish("hello", selfJson()) else publish("knock", selfJson())
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

    private fun handleSync(data: JsonObject) {
        val state = PartySyncState(
            userId = data.get("userId")?.asString ?: "",
            userName = data.get("userName")?.asString ?: "",
            fid = data.get("fid")?.asString ?: "",
            episode = data.get("episode")?.asString ?: "",
            positionMs = data.get("positionMs")?.asLong ?: 0L,
            paused = data.get("paused")?.asBoolean ?: true,
            speed = data.get("speed")?.asFloat ?: 1.0f,
            sentAt = data.get("sentAt")?.asLong ?: System.currentTimeMillis(),
        )
        if (state.userId.isBlank() || state.userId == userId) return
        callbacks.onRemoteSync(state)
    }

    private fun handlePresence(data: JsonObject) {
        val id = data.get("userId")?.asString ?: return
        if (id == userId) return
        val now = System.currentTimeMillis()
        synchronized(members) {
            members[id] = PartyMember(id, data.get("userName")?.asString ?: "有人", now, data.get("host")?.asBoolean ?: false)
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


