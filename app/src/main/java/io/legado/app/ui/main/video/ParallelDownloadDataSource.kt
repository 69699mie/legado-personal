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
    private val chunkSize: Long = DEFAULT_CHUNK_SIZE,
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

        totalLength = probeTotalSize()
        if (totalLength <= 0) {
            throw IOException("无法获取文件总大小: $currentUrl")
        }

        endPosition = if (spec.length > 0 && spec.position + spec.length <= totalLength) {
            spec.position + spec.length
        } else {
            totalLength
        }
        remainingBytes = endPosition - startPosition
        if (remainingBytes <= 0) return 0

        chunkCount = ((endPosition - startPosition + chunkSize - 1) / chunkSize).toInt()
        nextExpectedIndex = 0
        running.set(true)
        readyChunks = ConcurrentHashMap()
        semaphore = Semaphore(maxBufferedChunks)

        val ex = Executors.newFixedThreadPool(threadCount) { r ->
            Thread(r, "parallel-dl").apply { isDaemon = true }
        }
        executor = ex

        val nextIdx = AtomicInteger(0)
        for (i in 0 until threadCount) {
            ex.execute {
                while (running.get()) {
                    val idx = nextIdx.getAndIncrement()
                    if (idx >= chunkCount) break

                    val sem = semaphore ?: break
                    try { sem.acquire() } catch (e: InterruptedException) { break }
                    if (!running.get()) break

                    val chunkStart = startPosition + idx.toLong() * chunkSize
                    val chunkEnd = minOf(chunkStart + chunkSize, endPosition) - 1
                    try {
                        val data = downloadRange(chunkStart, chunkEnd)
                        readyChunks?.put(idx, PendingChunk(idx, data, null))
                    } catch (e: Exception) {
                        readyChunks?.put(idx, PendingChunk(idx, null, e))
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

    private fun probeTotalSize(): Long {
        val req = buildRequest(mapOf("Range" to "bytes=0-0")).build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) {
                throw IOException("探测大小失败: HTTP ${res.code}")
            }
            val cr = res.header("Content-Range")
            if (!cr.isNullOrBlank() && cr.contains("/")) {
                val len = cr.substringAfter("/").trim().toLongOrNull()
                if (len != null && len > 0) return len
            }
            if (res.code == 200) {
                return res.header("Content-Length")?.toLongOrNull() ?: -1
            }
        }
        return -1
    }

    private fun downloadRange(start: Long, end: Long): ByteArray {
        val req = buildRequest(mapOf("Range" to "bytes=$start-$end")).build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) {
                throw IOException("HTTP ${res.code}")
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
        private const val DEFAULT_THREAD_COUNT = 4
        private const val DEFAULT_CHUNK_SIZE = 16L * 1024 * 1024
        private const val DEFAULT_MAX_BUFFERED = 3
        private const val QUARK_DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch"
    }
}