package io.legado.app.ui.main.video

import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
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
    
    // 存储已经下载好的数据块，等待按顺序写入管道
    private var readyChunks: ConcurrentHashMap<Int, PendingChunk>? = null
    private var nextWriteIndex = 0

    override fun open(spec: DataSpec): Long {
        close()

        dataSpec = spec
        currentUrl = spec.uri.toString()
        startPosition = spec.position

        // 1. 发起首块探测，获取总长度
        val reqEnd = startPosition + INITIAL_CHUNK_SIZE - 1
        val req = buildRequest(mapOf("Range" to "bytes=$startPosition-$reqEnd")).build()
        
        var firstChunkData: ByteArray
        
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
            firstChunkData = body.bytes() // 首块直接读，用于秒开

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
            throw IOException("获取首块数据异常", e)
        }

        endPosition = if (spec.length > 0 && spec.position + spec.length <= totalLength) spec.position + spec.length else totalLength
        remainingBytes = endPosition - startPosition
        if (remainingBytes <= 0) return 0

        // 初始化管道
        pipedInputStream = PipedInputStream(2 * 1024 * 1024) // 2MB 管道缓冲
        pipedOutputStream = PipedOutputStream(pipedInputStream)

        val firstChunkActualLen = firstChunkData.size.toLong()
        val remainingAfterFirst = remainingBytes - firstChunkActualLen
        // 🌟 分块大小改为 2MB，配合多线程高并发，既能满速，又防 OOM
        val subsequentChunks = if (remainingAfterFirst > 0) ((remainingAfterFirst + SUBSEQUENT_CHUNK_SIZE - 1) / SUBSEQUENT_CHUNK_SIZE).toInt() else 0
        chunkCount = 1 + subsequentChunks

        running.set(true)
        readyChunks = ConcurrentHashMap()
        semaphore = Semaphore(maxBufferedChunks) // 内存里最多允许存几个块（默认 3 个）
        
        // 首块直接放进去
        readyChunks?.put(0, PendingChunk(0, firstChunkData, null))

        // 2. 启动多线程下载池
        // 恢复尊重用户的线程设置，保证《狂飙》满速
        downloadExecutor = Executors.newFixedThreadPool(threadCount) { r ->
            Thread(r, "parallel-dl").apply { isDaemon = true }
        }

        val nextIdx = AtomicInteger(1)
        for (i in 0 until threadCount) {
            downloadExecutor?.execute {
                while (running.get()) {
                    val idx = nextIdx.getAndIncrement()
                    if (idx >= chunkCount) break

                    // 如果内存里积压的块过多（比如已经下了 3 个还没被消费），这里会阻塞等待
                    val sem = semaphore ?: break
                    try { sem.acquire() } catch (e: InterruptedException) { break }
                    if (!running.get()) break

                    val chunkStart = startPosition + INITIAL_CHUNK_SIZE + (idx - 1) * SUBSEQUENT_CHUNK_SIZE
                    val chunkEnd = minOf(chunkStart + SUBSEQUENT_CHUNK_SIZE, endPosition) - 1
                    
                    if (chunkStart <= chunkEnd) {
                        try {
                            // 🌟 核心：下载返回 ByteArray，彻底释放 OkHttp 连接池
                            val data = downloadChunkData(chunkStart, chunkEnd)
                            val map = readyChunks
                            if (running.get() && map != null) {
                                map[idx] = PendingChunk(idx, data, null)
                            }
                        } catch (e: Exception) {
                            val map = readyChunks
                            if (running.get() && map != null) {
                                map[idx] = PendingChunk(idx, null, e)
                            }
                        }
                    }
                }
            }
        }

        // 3. 启动唯一的顺序写入线程
        writerExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "pipe-writer").apply { isDaemon = true }
        }
        writerExecutor?.execute {
            val out = pipedOutputStream ?: return@execute
            try {
                while (running.get() && nextWriteIndex < chunkCount) {
                    val map = readyChunks
                    // 🌟 核心：严格按顺序死等。如果 0 号还没下完，1 号下完了也不能写。
                    val pending = map?.remove(nextWriteIndex)
                    if (pending == null) {
                        Thread.sleep(20)
                        continue
                    }
                    
                    if (pending.error != null) throw pending.error
                    
                    val data = pending.data
                    if (data != null && data.isNotEmpty()) {
                        out.write(data, 0, data.size)
                        out.flush()
                    }
                    
                    nextWriteIndex++
                    // 消费掉一个块，释放一个信号量，允许下载线程继续下新的块
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
        
        // 唤醒所有卡在 sem.acquire() 的线程
        val sem = semaphore
        if (sem != null) {
            for (i in 0 until threadCount) {
                sem.release()
            }
        }
        semaphore = null

        readyChunks?.clear()
        readyChunks = null

        // 关闭管道，唤醒读/写线程
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

    private fun downloadChunkData(start: Long, end: Long): ByteArray {
        val req = buildRequest(mapOf("Range" to "bytes=$start-$end")).build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
            if (res.code == 200 && start > 0) throw IOException("服务器未正确响应分片请求(HTTP 200)")
            
            val contentType = (res.header("Content-Type") ?: "").lowercase()
            if (contentType.contains("html") || contentType.contains("json") || contentType.contains("text/plain") || contentType.contains("xml")) {
                throw IOException("服务器返回了错误格式: $contentType")
            }
            
            val body = res.body ?: throw IOException("empty body")
            return body.bytes() // 完整读入内存，随后连接自动关闭
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
        // 🌟 分块设为 2MB。无论 32 线程还是 64 线程，因为 Semaphore=3，瞬间内存最多 6MB。
        private const val SUBSEQUENT_CHUNK_SIZE = 2L * 1024 * 1024 
        private const val DEFAULT_MAX_BUFFERED = 3
        private const val QUARK_DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch"
    }
}
