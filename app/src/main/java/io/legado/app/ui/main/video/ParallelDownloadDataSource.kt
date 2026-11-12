package io.legado.app.ui.main.video

import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
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

    private var running = AtomicBoolean(false)
    
    private var pipedInputStream: PipedInputStream? = null
    private var pipedOutputStream: PipedOutputStream? = null

    private var downloadExecutor: ExecutorService? = null
    private var writerExecutor: ExecutorService? = null
    private var semaphore: Semaphore? = null
    
    private var readyStreams: ConcurrentHashMap<Int, PendingStream>? = null
    private var nextExpectedIndex = 0

    override fun open(spec: DataSpec): Long {
        close()

        dataSpec = spec
        currentUrl = spec.uri.toString()
        startPosition = spec.position

        val reqEnd = startPosition + INITIAL_CHUNK_SIZE - 1
        val req = buildRequest(mapOf("Range" to "bytes=$startPosition-$reqEnd")).build()
        
        var firstChunkStream: InputStream? = null
        
        try {
            val res = client.newCall(req).execute()
            if (!res.isSuccessful) {
                res.close()
                throw IOException("探测数据失败: HTTP ${res.code}")
            }
            
            val contentType = (res.header("Content-Type") ?: "").lowercase()
            if (contentType.contains("html") || contentType.contains("json") || contentType.contains("text/plain") || contentType.contains("xml")) {
                res.close()
                throw IOException("服务器返回了错误格式(可能触发风控): $contentType")
            }
            
            val body = res.body ?: throw IOException("返回数据为空")
            firstChunkStream = body.byteStream()

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
        } catch (e: Exception) {
            try { firstChunkStream?.close() } catch (_: Exception) {}
            throw IOException("获取首块数据异常", e)
        }

        endPosition = if (spec.length > 0 && spec.position + spec.length <= totalLength) spec.position + spec.length else totalLength
        remainingBytes = endPosition - startPosition
        if (remainingBytes <= 0) return 0

        pipedInputStream = PipedInputStream(2 * 1024 * 1024)
        pipedOutputStream = PipedOutputStream(pipedInputStream)

        val firstChunkActualLen = minOf(remainingBytes, INITIAL_CHUNK_SIZE)
        val remainingAfterFirst = remainingBytes - firstChunkActualLen
        val subsequentChunks = if (remainingAfterFirst > 0) ((remainingAfterFirst + SUBSEQUENT_CHUNK_SIZE - 1) / SUBSEQUENT_CHUNK_SIZE).toInt() else 0
        chunkCount = 1 + subsequentChunks

        running.set(true)
        readyStreams = ConcurrentHashMap()
        semaphore = Semaphore(maxBufferedChunks)
        
        readyStreams?.put(0, PendingStream(firstChunkStream, firstChunkActualLen, null))

        val actualThreads = maxBufferedChunks.coerceAtMost(threadCount).coerceAtLeast(1) + 2 
        downloadExecutor = Executors.newFixedThreadPool(actualThreads) { r ->
            Thread(r, "parallel-dl").apply { isDaemon = true }
        }

        val nextIdx = AtomicInteger(1)
        for (i in 0 until actualThreads) {
            downloadExecutor.execute {
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
                            val stream = downloadChunkStream(chunkStart, chunkEnd)
                            // 🌟 修复1：如果流获取成功但组件已关闭，立即释放流，防止连接泄漏
                            if (running.get()) {
                                readyStreams?.put(idx, PendingStream(stream, (chunkEnd - chunkStart + 1), null))
                            } else {
                                try { stream.close() } catch (_: Exception) {}
                            }
                        } catch (e: Exception) {
                            if (running.get()) {
                                readyStreams?.put(idx, PendingStream(null, 0, e))
                            }
                        }
                    }
                }
            }
        }

        writerExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "pipe-writer").apply { isDaemon = true }
        }
        writerExecutor?.execute {
            val out = pipedOutputStream ?: return@execute
            try {
                while (running.get() && nextExpectedIndex < chunkCount) {
                    val pending = readyStreams?.remove(nextExpectedIndex)
                    if (pending == null) {
                        Thread.sleep(30)
                        continue
                    }
                    
                    if (pending.error != null) throw pending.error
                    
                    val input = pending.stream ?: continue
                    input.use { ins ->
                        val buffer = ByteArray(64 * 1024)
                        var totalRead = 0L
                        while (running.get() && totalRead < pending.length) {
                            val bytesToRead = minOf(buffer.size.toLong(), pending.length - totalRead).toInt()
                            val read = ins.read(buffer, 0, bytesToRead)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                            totalRead += read
                        }
                    }
                    
                    nextExpectedIndex++
                    semaphore?.release()
                }
            } catch (e: Exception) {
                if (running.get()) {
                    try { out.close() } catch (_: Exception) {}
                }
            }
        }

        return remainingBytes
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remainingBytes <= 0) return C.RESULT_END_OF_INPUT
        
        val stream = pipedInputStream ?: return C.RESULT_END_OF_INPUT
        
        try {
            val n = stream.read(buffer, offset, length)
            if (n > 0) {
                remainingBytes -= n
            }
            return if (n == -1) C.RESULT_END_OF_INPUT else n
        } catch (e: InterruptedIOException) {
            return C.RESULT_END_OF_INPUT
        } catch (e: Exception) {
            if (!running.get()) return C.RESULT_END_OF_INPUT
            throw IOException("读取数据流失败", e)
        }
    }

    override fun getUri(): android.net.Uri? = dataSpec?.uri

    override fun close() {
        running.set(false)
        
        val sem = semaphore
        if (sem != null) {
            for (i in 0 until (threadCount + maxBufferedChunks + 2)) {
                sem.release()
            }
        }
        semaphore = null

        readyStreams?.values?.forEach { pending ->
            try { pending.stream?.close() } catch (_: Exception) {}
        }
        readyStreams?.clear()
        readyStreams = null

        try { pipedOutputStream?.close() } catch (_: Exception) {}
        pipedOutputStream = null
        
        try { pipedInputStream?.close() } catch (_: Exception) {}
        pipedInputStream = null

        try { downloadExecutor?.shutdownNow() } catch (_: Exception) {}
        downloadExecutor = null
        
        try { writerExecutor?.shutdownNow() } catch (_: Exception) {}
        writerExecutor = null
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

    private fun downloadChunkStream(start: Long, end: Long): InputStream {
        val req = buildRequest(mapOf("Range" to "bytes=$start-$end")).build()
        val res = client.newCall(req).execute()
        
        if (!res.isSuccessful) {
            res.close()
            throw IOException("HTTP ${res.code}")
        }
        if (res.code == 200 && start > 0) {
            res.close()
            throw IOException("服务器未正确响应分片请求(HTTP 200)")
        }
        
        val contentType = (res.header("Content-Type") ?: "").lowercase()
        if (contentType.contains("html") || contentType.contains("json") || contentType.contains("text/plain") || contentType.contains("xml")) {
            res.close()
            throw IOException("服务器返回了错误格式: $contentType")
        }
        
        val body = res.body ?: throw IOException("empty body")
        return body.byteStream()
    }

    private class PendingStream(
        val stream: InputStream?,
        val length: Long,
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
        private const val SUBSEQUENT_CHUNK_SIZE = 2L * 1024 * 1024
        private const val DEFAULT_MAX_BUFFERED = 3
        private const val QUARK_DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch"
    }
}