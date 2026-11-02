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
    private val cookie: String,
    private val threadCount: Int,
    private val onRefreshUrl: suspend () -> String? // 412 时重新获取链接的回调
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

    // 记录准备阶段的具体错误信息，供代理服务器透传
    var lastError: String = ""

    fun getTotalLength(): Long = totalLength

    suspend fun prepare(): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(currentUrl)
            .header("Cookie", cookie)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch")
            .header("Referer", "https://pan.quark.cn/")
            .header("Origin", "https://pan.quark.cn")
            .header("Range", "bytes=0-0") // 【核心修复】请求第一个字节，获取总大小
            .build()
        try {
            client.newCall(req).execute().use { res ->
                if (res.code == 200 || res.code == 206) {
                    // 优先从 Content-Range 解析大小，例如 "bytes 0-0/102400"
                    var length = -1L
                    val contentRange = res.header("Content-Range")
                    if (contentRange != null && contentRange.contains("/")) {
                        length = contentRange.substringAfter("/").toLongOrNull() ?: -1L
                    }
                    
                    // 如果 Content-Range 没拿到，尝试用 Content-Length
                    if (length <= 0) {
                        length = res.header("Content-Length")?.toLongOrNull() ?: -1L
                    }

                    if (length > 0) {
                        totalLength = length
                        val fileName = currentUrl.hashCode().toString() + ".tmp"
                        cacheFile = File(cacheDir, fileName)
                        raf = RandomAccessFile(cacheFile, "rw")
                        raf?.setLength(totalLength)
                        return@withContext true
                    } else {
                        lastError = "获取文件大小失败: 缺少 Content-Range 或 Content-Length\nHTTP ${res.code}\nHeaders: ${res.headers}"
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

    // 针对特定位置启动分片下载（支持 Seek 拖拽）
    fun startDownload(startPos: Long, endPos: Long) {
        if (isRunning) return
        isRunning = true
        val chunkSize = 2 * 1024 * 1024L // 每片 2MB
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
        if (retry > 3) return // 重试超过3次放弃
        val req = Request.Builder()
            .url(currentUrl)
            .header("Cookie", cookie)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch")
            .header("Referer", "https://pan.quark.cn/")
            .header("Origin", "https://pan.quark.cn")
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
                    // 412 鉴权失效，回调刷新链接
                    val newUrl = onRefreshUrl()
                    if (newUrl != null) {
                        currentUrl = newUrl
                        downloadChunk(start, end, retry + 1)
                    }
                }
            }
        } catch (e: Exception) {
            delay(1000)
            downloadChunk(start, end, retry + 1)
        }
    }

    // 阻塞等待数据下载就绪
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