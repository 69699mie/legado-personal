package io.legado.app.ui.main.video

import kotlinx.coroutines.*
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
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private var currentUrl = initialUrl
    private var totalLength = -1L
    private val downloadedBytes = AtomicLong(0)
    private var isRunning = false
    private val downloadJobs = mutableListOf<Job>()
    
    lateinit var cacheFile: File
    private var raf: RandomAccessFile? = null

    var lastError: String = ""

    private val playUserAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/100.0.4896.58 UCBrowser/16.5.8.1309 Mobile Safari/537.36"
    private val playReferer = "https://drive.quark.cn/"
    private val chunkSize = 2 * 1024 * 1024L

    fun getTotalLength(): Long = totalLength

    // 【核心突破】同步下载前2MB数据（死等），确保文件头绝对落盘
    fun prepareAndDownloadHead(): Boolean {
        return try {
            // 1. 探测文件总大小
            val headReq = Request.Builder()
                .url(currentUrl)
                .header("Cookie", cookie)
                .header("User-Agent", playUserAgent)
                .header("Referer", playReferer)
                .header("Range", "bytes=0-0")
                .build()

            client.newCall(headReq).execute().use { res ->
                if (res.code != 200 && res.code != 206) {
                    lastError = "HTTP ${res.code}: ${res.body?.string()}"
                    return false
                }
                var length = res.header("Content-Length")?.toLongOrNull() ?: -1L
                val contentRange = res.header("Content-Range")
                if (contentRange != null && contentRange.contains("/")) {
                    length = contentRange.substringAfter("/").toLongOrNull() ?: -1L
                }
                if (length <= 0) {
                    lastError = "获取文件大小失败"
                    return false
                }
                totalLength = length

                val fileName = currentUrl.hashCode().toString() + ".tmp"
                cacheFile = File(cacheDir, fileName)
                raf = RandomAccessFile(cacheFile, "rw")
                raf?.setLength(totalLength)
            }

            // 2. 同步拉取头部 2MB（解决 PARSING_CONTAINER_UNSUPPORTED 的核心）
            val headEnd = minOf(chunkSize - 1, totalLength - 1)
            val syncReq = Request.Builder()
                .url(currentUrl)
                .header("Cookie", cookie)
                .header("User-Agent", playUserAgent)
                .header("Referer", playReferer)
                .header("Range", "bytes=0-$headEnd")
                .build()

            client.newCall(syncReq).execute().use { res ->
                if (res.code == 206 || res.code == 200) {
                    val data = res.body?.bytes() ?: ByteArray(0)
                    synchronized(this) {
                        raf?.seek(0)
                        raf?.write(data)
                    }
                    downloadedBytes.addAndGet(data.size.toLong())
                    return true
                } else {
                    lastError = "下载头部失败 HTTP ${res.code}"
                    return false
                }
            }
        } catch (e: Exception) {
            lastError = "Exception: ${e.message}"
            false
        }
    }

    fun startDownload(startPos: Long, endPos: Long) {
        if (isRunning) return
        isRunning = true
        
        var currentPos = startPos
        // 如果起始位置在前 2MB 内，跳过已经下载的部分
        if (currentPos < chunkSize) {
            currentPos = chunkSize
        }

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
                    val data = res.body?.bytes() ?: return
                    synchronized(this) {
                        raf?.seek(start)
                        raf?.write(data)
                    }
                    downloadedBytes.addAndGet(data.size.toLong())
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

    suspend fun awaitDataReady(pos: Long) = withContext(Dispatchers.IO) {
        while (downloadedBytes.get() < pos && isRunning) {
            delay(100)
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