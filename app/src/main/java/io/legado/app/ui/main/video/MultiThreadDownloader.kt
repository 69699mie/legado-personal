package io.legado.app.ui.main.video

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class MultiThreadDownloader(
    private val context: android.content.Context,
    private val cacheDir: File,
    private val initialUrl: String,
    private var cookie: String,
    private val threadCount: Int,
    private val onRefreshUrl: suspend () -> Pair<String, String>?
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private var currentUrl = initialUrl
    private var totalLength = -1L
    private val downloadedBytes = AtomicLong(0)
    private var isRunning = false
    private val downloadJobs = mutableListOf<Job>()
    
    lateinit var cacheFile: File
    private var raf: RandomAccessFile? = null

    var lastError: String = ""
    private var isPrepared = false
    private val prepareMutex = Mutex()

    private val playUserAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/100.0.4896.58 UCBrowser/16.5.8.1309 Mobile Safari/537.36"
    private val playReferer = "https://drive.quark.cn/"

    fun getTotalLength(): Long = totalLength

    suspend fun prepare(): Boolean = prepareMutex.withLock {
        if (isPrepared) return@withLock true
        return@withLock withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(currentUrl)
                .header("Cookie", cookie)
                .header("User-Agent", playUserAgent)
                .header("Referer", playReferer)
                .header("Range", "bytes=0-511") // 只请求前 512 字节做校验
                .build()
            try {
                client.newCall(req).execute().use { res ->
                    if (res.code == 200 || res.code == 206) {
                        var length = res.header("Content-Length")?.toLongOrNull() ?: -1L
                        if (length <= 0) {
                            val contentRange = res.header("Content-Range")
                            if (contentRange != null && contentRange.contains("/")) {
                                length = contentRange.substringAfter("/").toLongOrNull() ?: -1L
                            }
                        }

                        if (length > 0) {
                            totalLength = length
                            
                            // 检查前 512 字节
                            val stream = res.body?.byteStream()
                            val buffer = ByteArray(512)
                            val read = stream?.read(buffer) ?: 0
                            val headString = if (read > 0) String(buffer, 0, read) else ""
                            
                            if (headString.contains("<Error>") || headString.contains("<!DOCTYPE") || headString.contains("<html")) {
                                lastError = "夸克返回了错误页面:\n$headString"
                                return@withContext false
                            }

                            val fileName = currentUrl.hashCode().toString() + ".tmp"
                            cacheFile = File(cacheDir, fileName)
                            raf = RandomAccessFile(cacheFile, "rw")
                            raf?.setLength(totalLength)
                            isPrepared = true
                            return@withContext true
                        } else {
                            lastError = "获取文件大小失败: 缺少 Content-Length\nHTTP ${res.code}\nHeaders: ${res.headers}"
                        }
                    } else {
                        val errBody = res.body?.string() ?: "Empty body"
                        lastError = "HTTP ${res.code}: $errBody"
                    }
                }
            } catch (e: Exception) {
                lastError = "Exception: ${e.message}"
                e.printStackTrace()
            }
            return@withContext false
        }
    }

    fun startDownload(startPos: Long, endPos: Long) {
        if (isRunning) return
        isRunning = true
        val chunkSize = 2 * 1024 * 1024L
        var currentPos = startPos

        for (i in 0 until threadCount) {
            if (currentPos > endPos) break
            val rangeStart = currentPos
            val rangeEnd = minOf(currentPos + chunkSize - 1, endPos)
            currentPos = rangeEnd + 1

            val job = CoroutineScope(Dispatchers.IO).launch {
                downloadChunk(rangeStart, rangeEnd)
            }
            downloadJobs.add(job)
        }
    }

    private suspend fun downloadChunk(start: Long, end: Long, retry: Int = 0) {
        if (retry > 5) return
        val req = Request.Builder()
            .url(currentUrl)
            .header("Cookie", cookie)
            .header("User-Agent", playUserAgent)
            .header("Referer", playReferer)
            .header("Range", "bytes=$start-$end")
            .build()

        try {
            client.newCall(req).execute().use { res ->
                if (res.code == 206 || res.code == 200) {
                    val body = res.body ?: return
                    val data = body.bytes()
                    synchronized(this) {
                        raf?.seek(start)
                        raf?.write(data)
                        downloadedBytes.addAndGet(data.size.toLong())
                    }
                } else if (res.code == 412 || res.code == 403) {
                    val result = onRefreshUrl()
                    if (result != null) {
                        currentUrl = result.first
                        cookie = result.second
                        downloadChunk(start, end, retry + 1)
                    }
                }
            }
        } catch (e: Exception) {
            delay(1000)
            downloadChunk(start, end, retry + 1)
        }
    }

    // 阻塞等待数据就绪（供代理服务器调用）
    suspend fun awaitDataReady(pos: Long) = withContext(Dispatchers.IO) {
        var waitCount = 0
        while (downloadedBytes.get() < pos && isRunning) {
            delay(100)
            waitCount++
            if (waitCount > 200) { // 等待 20 秒超时
                throw Exception("MultiThreadDownloader timeout waiting for data")
            }
        }
    }

    fun stop() {
        isRunning = false
        downloadJobs.forEach { it.cancel() }
        downloadJobs.clear()
        try {
            raf?.close()
            if (::cacheFile.isInitialized && cacheFile.exists()) cacheFile.delete()
        } catch (e: Exception) {}
    }
}