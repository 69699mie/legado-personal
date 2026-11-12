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
        val url: String,
        val headers: Map<String, String>,
        val totalLength: Long,
        val file: File,
        val downloadedRanges: MutableList<LongRange> = mutableListOf(),
        val running: AtomicBoolean = AtomicBoolean(true),
        val writeLock: ReentrantLock = ReentrantLock(),
        val condition: java.util.concurrent.locks.Condition = writeLock.newCondition()
    )

    private val tasks = ConcurrentHashMap<String, DownloadTask>()
    private val executor = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "proxy-range-dl").apply { isDaemon = true }
    }

    fun startTask(
        id: String,
        url: String,
        headers: Map<String, String>,
        totalLength: Long,
        cacheFile: File
    ) {
        if (tasks.containsKey(id)) return
        if (totalLength <= 0L) return

        if (!cacheFile.exists()) {
            cacheFile.parentFile?.mkdirs()
            RandomAccessFile(cacheFile, "rw").use { it.setLength(totalLength) }
        }

        val task = DownloadTask(url, headers, totalLength, cacheFile)
        tasks[id] = task

        executor.execute { downloadLoop(id, task) }
    }

    private fun downloadLoop(id: String, task: DownloadTask) {
        var nextStart = findFirstGap(task)
        while (task.running.get() && nextStart < task.totalLength) {
            val rangeEnd = (nextStart + CHUNK_SIZE - 1).coerceAtMost(task.totalLength - 1)
            val success = downloadRange(id, task, nextStart, rangeEnd)
            if (!success) {
                Thread.sleep(500)
                continue
            }
            nextStart = findFirstGap(task)
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

    private fun findFirstGap(task: DownloadTask): Long {
        task.writeLock.withLock {
            if (task.downloadedRanges.isEmpty()) return 0L
            mergeRanges(task.downloadedRanges)
            val first = task.downloadedRanges.first()
            return if (first.first > 0) 0L else first.last + 1
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

    fun awaitData(id: String, start: Long, timeoutMs: Long = 3000L): Boolean {
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
        private const val CHUNK_SIZE = 2L * 1024 * 1024
        private const val BUFFER_SIZE = 128 * 1024
    }
}
