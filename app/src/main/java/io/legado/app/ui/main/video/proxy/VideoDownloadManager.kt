package io.legado.app.ui.main.video.proxy

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class VideoDownloadManager(private val client: OkHttpClient) {

    data class DownloadTask(
        @Volatile var url: String,
        val headers: Map<String, String>,
        val totalLength: Long,
        val file: File,
        val downloadedRanges: MutableList<LongRange> = mutableListOf(),
        val running: AtomicBoolean = AtomicBoolean(true),
        val writeLock: ReentrantLock = ReentrantLock(),
        val condition: java.util.concurrent.locks.Condition = writeLock.newCondition(),
        val refreshUrl: (suspend () -> String?)? = null,
        val downloadingRanges: MutableSet<Long> = ConcurrentHashMap.newKeySet() // 防止同一块重复下载
    )

    private val tasks = ConcurrentHashMap<String, DownloadTask>()
    // 线程池大小可以动态调整，但基础给 16 个足够了
    private val executor = Executors.newFixedThreadPool(16) { r ->
        Thread(r, "proxy-range-dl").apply { isDaemon = true }
    }

    fun startTask(
        id: String,
        url: String,
        headers: Map<String, String>,
        totalLength: Long,
        cacheFile: File,
        refreshUrl: (suspend () -> String?)? = null
    ) {
        if (tasks.containsKey(id)) return
        if (totalLength <= 0L) return

        if (!cacheFile.exists()) {
            cacheFile.parentFile?.mkdirs()
            RandomAccessFile(cacheFile, "rw").use { it.setLength(totalLength) }
        }

        val task = DownloadTask(
            url = url,
            headers = headers,
            totalLength = totalLength,
            file = cacheFile,
            refreshUrl = refreshUrl
        )
        tasks[id] = task
    }

    // 🌟 增加 downloadThreads 参数，支持并发预读
    fun downloadRangeAsync(id: String, start: Long, downloadThreads: Int) {
        val task = tasks[id] ?: return
        
        // 计算当前起始块的索引
        val startChunkIndex = start / CHUNK_SIZE
        val totalChunks = (task.totalLength + CHUNK_SIZE - 1) / CHUNK_SIZE

        // 计算需要预读的并发数量，最大不超过用户设置
        val readAheadCount = downloadThreads.coerceIn(1, 16)

        // 把当前块和后续块一起提交
        for (i in 0 until readAheadCount) {
            val targetChunkIndex = startChunkIndex + i
            if (targetChunkIndex >= totalChunks) break

            val chunkStart = targetChunkIndex * CHUNK_SIZE
            // 如果该分片已经下载或正在下载，跳过
            if (getDownloadedRange(id, chunkStart) != -1L) continue
            if (!task.downloadingRanges.add(chunkStart)) continue

            val chunkEnd = ((targetChunkIndex + 1) * CHUNK_SIZE - 1).coerceAtMost(task.totalLength - 1)

            executor.execute {
                try {
                    var success = downloadRange(id, task, chunkStart, chunkEnd)
                    if (!success) {
                        // 直链过期刷新机制
                        task.refreshUrl?.let { refresh ->
                            val newUrl = kotlinx.coroutines.runBlocking { refresh() }
                            if (newUrl != null) {
                                task.url = newUrl
                                success = downloadRange(id, task, chunkStart, chunkEnd)
                            }
                        }
                    }
                } finally {
                    task.downloadingRanges.remove(chunkStart)
                }
            }
        }
    }

    private fun downloadRange(
        id: String,
        task: DownloadTask,
        start: Long,
        end: Long
    ): Boolean {
        return try {
            val req = Request.Builder()
                .url(task.url)
                .header("Range", "bytes=$start-$end")
                .apply { task.headers.forEach { (k, v) -> header(k, v) } }
                .build()

            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return false
                val body = res.body ?: return false

                RandomAccessFile(task.file, "rw").use { raf ->
                    raf.seek(start)
                    val buffer = ByteArray(BUFFER_SIZE)
                    var totalRead = 0L
                    body.byteStream().use { input ->
                        while (task.running.get() && totalRead < (end - start + 1)) {
                            val toRead = minOf(
                                buffer.size.toLong(),
                                (end - start + 1) - totalRead
                            ).toInt()
                            val read = input.read(buffer, 0, toRead)
                            if (read == -1) break
                            raf.write(buffer, 0, read)
                            totalRead += read
                        }
                    }
                    if (totalRead == (end - start + 1)) {
                        task.writeLock.withLock {
                            task.downloadedRanges.add(start..end)
                            mergeRanges(task.downloadedRanges)
                            task.condition.signalAll()
                        }
                        true
                    } else false
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun mergeRanges(ranges: MutableList<LongRange>) {
        if (ranges.size < 2) return
        ranges.sortBy { it.first }
        val merged = mutableListOf<LongRange>()
        var current = ranges[0]
        for (i in 1 until ranges.size) {
            val next = ranges[i]
            if (next.first <= current.last + 1) {
                current = current.first..maxOf(current.last, next.last)
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        ranges.clear()
        ranges.addAll(merged)
    }

    fun getDownloadedRange(id: String, start: Long): Long {
        val task = tasks[id] ?: return -1L
        task.writeLock.withLock {
            mergeRanges(task.downloadedRanges)
            for (range in task.downloadedRanges) {
                if (start in range.first..range.last) return range.last
            }
        }
        return -1L
    }

    fun awaitData(id: String, start: Long, timeoutMs: Long = 30000L): Boolean {
        val task = tasks[id] ?: return false
        task.writeLock.withLock {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (task.running.get() && getDownloadedRange(id, start) == -1L) {
                val remain = deadline - System.currentTimeMillis()
                if (remain <= 0) return false
                try {
                    task.condition.await(remain, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    return false
                }
            }
            return getDownloadedRange(id, start) != -1L
        }
    }

    fun stopTask(id: String) {
        tasks.remove(id)?.running?.set(false)
    }

    fun stopAll() {
        tasks.keys.toList().forEach { stopTask(it) }
    }

    companion object {
        private const val CHUNK_SIZE = 2L * 1024 * 1024 // 2MB 分片
        private const val BUFFER_SIZE = 128 * 1024
    }
}