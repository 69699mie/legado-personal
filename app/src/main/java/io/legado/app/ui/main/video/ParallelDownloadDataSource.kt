package io.legado.app.ui.main.video

import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ParallelDownloadDataSource(
    private val client: OkHttpClient,
    private val headers: Map<String, String>,
    private val threadCount: Int = DEFAULT_THREAD_COUNT,
    private val maxBufferedChunks: Int = DEFAULT_MAX_BUFFERED
) : BaseDataSource(false) {

    private var dataSpec: DataSpec? = null
    private var currentUrl: String = ""
    private var startPosition: Long = 0
    private var endPosition: Long = 0
    private var totalLength: Long = 0
    private var remainingBytes: Long = 0
    private var chunkCount: Int = 0
    private var nextExpectedIndex: Int = 0
    private var currentChunkStream: InputStream? = null

    private var running: AtomicBoolean = AtomicBoolean(false)
    private var executor: ExecutorService? = null
    private var readyChunks: ConcurrentHashMap<Int, PendingChunk>? = null
    private var semaphore: Semaphore? = null

    override fun open(spec: DataSpec): Long {
        close()

        dataSpec = spec
        currentUrl = spec.uri.toString()
        startPosition = spec.position

        // 直接请求第一块作为探测，同时拿到第一块数据
        val reqEnd = startPosition + INITIAL_CHUNK_SIZE - 1
        val req = buildRequest(mapOf("Range" to "bytes=$startPosition-$reqEnd")).build()
        
        var firstChunkData: ByteArray
        
        try {
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    throw IOException("探测数据失败: HTTP ${res.code}")
                }
                
                // 严谨校验：拦截非视频流格式
                val contentType = (res.header("Content-Type") ?: "").lowercase()
                if (!contentType.contains("video") && !contentType.contains("octet-stream") && contentType.isNotBlank()) {
                    val errMsg = res.body?.string()?.take(200) ?: ""
                    throw IOException("服务器返回了非视频格式(可能触发风控): $contentType\n$errMsg")
                }
                
                firstChunkData = res.body?.bytes() ?: throw IOException("返回数据为空")

                val cr = res.header("Content-Range")
                if (!cr.isNullOrBlank() && cr.contains("/")) {
                    val lenStr = cr.substringAfter("/").trim()
                    if (lenStr != "*") totalLength = lenStr.toLongOrNull() ?: -1L
                }
                
                if (totalLength <= 0) {
                    if (res.code == 200) {
                        totalLength = res.header("Content-Length")?.toLongOrNull() ?: -1L
                    } else {
                        throw IOException("无法获取文件总长度")
                    }
                }
            }
        } catch (e: Throwable) { // 捕获 Throwable 防止 OOM 或 Error 导致崩溃
            throw IOException("获取首块数据异常: ${e.message}", e)
        }

        endPosition = if (spec.length > 0 && spec.position + spec.length <= totalLength) {
            spec.position + spec.length
        } else {
            totalLength
        }
        
        remainingBytes = endPosition - startPosition
        if (remainingBytes <= 0) return 0

        // 计算分块数量
        val firstChunkActualLen = firstChunkData.size.toLong()
        val remainingAfterFirst = remainingBytes - firstChunkActualLen
        val subsequentChunks = if (remainingAfterFirst > 0) ((remainingAfterFirst + SUBSEQUENT_CHUNK_SIZE - 1) / SUBSEQUENT_CHUNK_SIZE).toInt() else 0
        chunkCount = 1 + subsequentChunks

        nextExpectedIndex = 0
        running.set(true)
        readyChunks = ConcurrentHashMap()
        semaphore = Semaphore(maxBufferedChunks)

        // 把首块直接塞进去
        readyChunks?.put(0, PendingChunk(0, firstChunkData, null))

        val ex = Executors.newFixedThreadPool(threadCount) { r ->
            Thread(r, "parallel-dl").apply { isDaemon = true }
        }
        executor = ex

        // 线程池从 index 1 开始领任务
        val nextIdx = AtomicInteger(1)
        for (i in 0 until threadCount) {
            ex.execute {
                while (running.get()) {
                    val idx = nextIdx.getAndIncrement()
                    if (idx >= chunkCount) break

                    val sem = semaphore ?: break
                    try { sem.acquire() } catch (e: InterruptedException) { break }
                    if (!running.get()) break

                    val chunkStart = startPosition + INITIAL_CHUNK_SIZE + (idx - 1) * SUBSEQUENT_CHUNK_SIZE
                    val chunkEnd = minOf(chunkStart + SUBSEQUENT_CHUNK_SIZE, endPosition) - 1
                    
                    if (chunkStart <= chunkEnd) {
                        try {
                            val data = downloadRange(chunkStart, chunkEnd)
                            readyChunks?.put(idx, PendingChunk(idx, data, null))
                        } catch (e: Throwable) {
                            readyChunks?.put(idx, PendingChunk(idx, null, e as? Exception ?: Exception(e.message)))
                        }
                    }
                }
            }
        }

        return remainingBytes
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remainingBytes <= 0) return C.RESULT_END_OF_INPUT

        while (true) {
            val stream = currentChunkStream
            if (stream != null) {
                val want = minOf(length.toLong(), remainingBytes).toInt()
                val n = try {
                    stream.read(buffer, offset, want)
                } catch (e: Throwable) {
                    if (!running.get()) return C.RESULT_END_OF_INPUT
                    throw IOException("读取数据失败", e)
                }
                if (n > 0) {
                    remainingBytes -= n
                    return n
                }
                try { stream.close() } catch (_: Exception) {}
                currentChunkStream = null
                if (remainingBytes <= 0) return C.RESULT_END_OF_INPUT
                continue
            }

            if (nextExpectedIndex >= chunkCount) return C.RESULT_END_OF_INPUT
            if (!running.get()) return C.RESULT_END_OF_INPUT

            val chunk = readyChunks?.remove(nextExpectedIndex)
            if (chunk == null) {
                try { Thread.sleep(30) } catch (e: InterruptedException) { return C.RESULT_END_OF_INPUT }
                continue
            }

            if (chunk.error != null) {
                throw IOException("分片 ${chunk.index} 下载失败", chunk.error)
            }

            nextExpectedIndex++
            semaphore?.release()

            val data = chunk.data
            if (data == null || data.isEmpty()) continue
            currentChunkStream = data.inputStream()
        }
    }

    override fun getUri(): android.net.Uri? = dataSpec?.uri

    override fun close() {
        running.set(false)
        try { currentChunkStream?.close() } catch (_: Exception) {}
        currentChunkStream = null
        try { executor?.shutdownNow() } catch (_: Exception) {}
        executor = null
        readyChunks = null
        semaphore = null
    }

    private fun buildRequest(extraHeaders: Map<String, String> = emptyMap()): Request.Builder {
        val builder = Request.Builder().url(currentUrl)
        headers.forEach { (k, v) ->
            if (!k.equals("User-Agent", ignoreCase = true)) {
                builder.header(k, v)
            }
        }
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }
        builder.header("User-Agent", QUARK_DESKTOP_UA)
        return builder
    }

    private fun downloadRange(start: Long, end: Long): ByteArray {
        val req = buildRequest(mapOf("Range" to "bytes=$start-$end")).build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) {
                throw IOException("HTTP ${res.code}")
            }
            if (res.code == 200 && start > 0) {
                throw IOException("服务器未正确响应分片请求(HTTP 200)")
            }
            val contentType = (res.header("Content-Type") ?: "").lowercase()
            if (!contentType.contains("video") && !contentType.contains("octet-stream") && contentType.isNotBlank()) {
                throw IOException("服务器返回了错误格式: $contentType")
            }
            val body = res.body ?: throw IOException("empty body")
            return body.bytes()
        }
    }

    private class PendingChunk(
        val index: Int,
        val data: ByteArray?,
        val error: Exception?
    )

    class Factory(
        private val client: OkHttpClient,
        private val headers: Map<String, String>,
        private val threadCount: Int = DEFAULT_THREAD_COUNT
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            ParallelDownloadDataSource(client, headers, threadCount)
    }

    companion object {
        private const val DEFAULT_THREAD_COUNT = 6
        private const val INITIAL_CHUNK_SIZE = 2L * 1024 * 1024 // 首块 2MB 秒开
        
        // 🌟 核心修改：将 8MB 降为 3MB，彻底解决极速网络下的瞬时并发流量过大问题
        private const val SUBSEQUENT_CHUNK_SIZE = 3L * 1024 * 1024 
        
        // 🌟 核心修改：最大缓冲块降为 2，减少内存积压，让请求节奏更平滑
        private const val DEFAULT_MAX_BUFFERED = 2 
        
        private const val QUARK_DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch"
    }
}