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
    private val threadCount: Int = DEFAULT_THREAD_COUNT, // 用户设置的线程数，仅作参考
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

    override fun open(spec: DataSpec): Long {
        close()

        dataSpec = spec
        currentUrl = spec.uri.toString()
        startPosition = spec.position

        val reqEnd = startPosition + INITIAL_CHUNK_SIZE - 1
        val req = buildRequest(mapOf("Range" to "bytes=$startPosition-$reqEnd")).build()
        
        var firstChunkData: ByteArray
        
        try {
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw IOException("探测数据失败: HTTP ${res.code}")
                
                val contentType = (res.header("Content-Type") ?: "").lowercase()
                if (contentType.contains("html") || contentType.contains("json") || contentType.contains("text/plain") || contentType.contains("xml")) {
                    val errMsg = res.body?.string()?.take(200) ?: ""
                    throw IOException("服务器返回了错误格式: $contentType\n$errMsg")
                }
                
                firstChunkData = res.body?.bytes() ?: throw IOException("返回数据为空")
                if (firstChunkData.size < 1024) throw IOException("返回数据异常截断，大小: ${firstChunkData.size} 字节")

                val cr = res.header("Content-Range")
                if (!cr.isNullOrBlank() && cr.contains("/")) {
                    val lenStr = cr.substringAfter("/").trim()
                    if (lenStr != "*") totalLength = lenStr.toLongOrNull() ?: -1L
                }
                
                if (totalLength <= 0) {
                    if (res.code == 200) totalLength = res.header("Content-Length")?.toLongOrNull() ?: -1L
                    else throw IOException("无法获取文件总长度")
                }
            }
        } catch (e: Exception) {
            throw IOException("获取首块数据异常", e)
        }

        endPosition = if (spec.length > 0 && spec.position + spec.length <= totalLength) spec.position + spec.length else totalLength
        remainingBytes = endPosition - startPosition
        if (remainingBytes <= 0) return 0

        val firstChunkActualLen = firstChunkData.size.toLong()
        val remainingAfterFirst = remainingBytes - firstChunkActualLen
        val subsequentChunks = if (remainingAfterFirst > 0) ((remainingAfterFirst + SUBSEQUENT_CHUNK_SIZE - 1) / SUBSEQUENT_CHUNK_SIZE).toInt() else 0
        chunkCount = 1 + subsequentChunks

        nextExpectedIndex = 0
        running.set(true)
        readyChunks = ConcurrentHashMap()
        readyChunks?.put(0, PendingChunk(0, firstChunkData, null))

        // 🌟 架构级修正：真正的并发数，应该由缓冲区大小（maxBufferedChunks）决定，而不是用户盲选的32。
        // 假设缓存3块，我们就只开3个线程。循环去取任务。这能彻底斩断内存碎片。
        val actualThreads = maxBufferedChunks.coerceAtMost(threadCount).coerceAtLeast(1)
        
        val ex = Executors.newFixedThreadPool(actualThreads) { r ->
            Thread(r, "parallel-dl").apply { isDaemon = true }
        }
        executor = ex

        val nextIdx = AtomicInteger(1)
        for (i in 0 until actualThreads) {
            ex.execute {
                while (running.get()) {
                    val idx = nextIdx.getAndIncrement()
                    if (idx >= chunkCount) break

                    val chunkStart = startPosition + INITIAL_CHUNK_SIZE + (idx - 1) * SUBSEQUENT_CHUNK_SIZE
                    val chunkEnd = minOf(chunkStart + SUBSEQUENT_CHUNK_SIZE, endPosition) - 1
                    
                    if (chunkStart <= chunkEnd) {
                        try {
                            val data = downloadRange(chunkStart, chunkEnd)
                            readyChunks?.put(idx, PendingChunk(idx, data, null))
                        } catch (e: Exception) {
                            readyChunks?.put(idx, PendingChunk(idx, null, e))
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
                } catch (e: Exception) {
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
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
            if (res.code == 200 && start > 0) throw IOException("服务器未正确响应分片请求(HTTP 200)")
            
            val contentType = (res.header("Content-Type") ?: "").lowercase()
            if (contentType.contains("html") || contentType.contains("json") || contentType.contains("text/plain") || contentType.contains("xml")) {
                throw IOException("服务器返回了错误格式: $contentType")
            }
            
            val body = res.body ?: throw IOException("empty body")
            val bytes = body.bytes()
            
            val expectedLen = end - start + 1
            if (bytes.size < expectedLen / 2 && expectedLen > 1024 * 1024) {
                throw IOException("分片数据异常截断，预期: $expectedLen，实际: ${bytes.size}")
            }
            return bytes
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
        private const val INITIAL_CHUNK_SIZE = 2L * 1024 * 1024 
        private const val SUBSEQUENT_CHUNK_SIZE = 8L * 1024 * 1024 
        private const val DEFAULT_MAX_BUFFERED = 3
        private const val QUARK_DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch"
    }
}