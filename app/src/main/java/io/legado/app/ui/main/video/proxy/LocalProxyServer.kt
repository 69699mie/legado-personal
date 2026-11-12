package io.legado.app.ui.main.video.proxy

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.RandomAccessFile

class LocalProxyServer(
    private val downloadManager: VideoDownloadManager
) : NanoHTTPD(0) {

    private val registry = mutableMapOf<String, VideoInfo>()

    data class VideoInfo(
        val url: String,
        val headers: Map<String, String>,
        val file: File,
        val totalLength: Long,
        val refreshUrl: (suspend () -> String?)?
    )

    fun registerVideo(
        id: String,
        url: String,
        headers: Map<String, String>,
        file: File,
        totalLength: Long,
        refreshUrl: (suspend () -> String?)? = null
    ) {
        registry[id] = VideoInfo(url, headers, file, totalLength, refreshUrl)
    }

    fun proxyUrlFor(id: String): String =
        "http://127.0.0.1:$listeningPort/$id"

    override fun serve(session: IHTTPSession): Response {
        val id = session.uri.removePrefix("/").substringBefore("?")
        val info = registry[id]
            ?: return newFixedLengthResponse(
                Response.Status.NOT_FOUND, "text/plain", "Not Found"
            )

        val rangeHeader = session.headers["range"]
        var start = 0L
        var end = info.totalLength - 1

        if (rangeHeader != null) {
            val match = Regex("bytes=(\\d+)-(\\d*)").find(rangeHeader)
            if (match != null) {
                start = match.groupValues[1].toLong()
                if (match.groupValues[2].isNotEmpty()) {
                    end = match.groupValues[2].toLong()
                }
            }
        }

        downloadManager.startTask(
            id, info.url, info.headers, info.totalLength,
            info.file, info.refreshUrl
        )

        if (!downloadManager.awaitData(id, start)) {
            return newFixedLengthResponse(
                Response.Status.RANGE_NOT_SATISFIABLE,
                "text/plain",
                "Range Not Ready"
            )
        }

        val availableEnd = downloadManager.getDownloadedRange(id, start)
        if (availableEnd == -1L || availableEnd < start) {
            return newFixedLengthResponse(
                Response.Status.RANGE_NOT_SATISFIABLE,
                "text/plain",
                "Range Not Ready"
            )
        }
        if (end > availableEnd) end = availableEnd

        val length = end - start + 1
        if (length <= 0) {
            return newFixedLengthResponse(
                Response.Status.RANGE_NOT_SATISFIABLE,
                "text/plain",
                "Invalid Range"
            )
        }

        return try {
            val raf = RandomAccessFile(info.file, "r")
            raf.seek(start)

            val inputStream = object : java.io.InputStream() {
                private var remaining = length

                override fun read(): Int {
                    if (remaining <= 0) return -1
                    val b = raf.read()
                    if (b != -1) remaining--
                    return b
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (remaining <= 0) return -1
                    val toRead = minOf(len.toLong(), remaining).toInt()
                    val read = raf.read(b, off, toRead)
                    if (read > 0) remaining -= read
                    return read
                }

                override fun close() {
                    super.close()
                    raf.close()
                }
            }

            val response = newFixedLengthResponse(
                Response.Status.PARTIAL_CONTENT,
                "video/mp4",
                inputStream,
                length
            )
            response.addHeader("Accept-Ranges", "bytes")
            response.addHeader(
                "Content-Range",
                "bytes $start-$end/${info.totalLength}"
            )
            response.addHeader("Content-Length", length.toString())
            response
        } catch (e: Exception) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                e.message ?: "Internal Error"
            )
        }
    }

    fun unregisterAll() {
        registry.keys.toList().forEach { downloadManager.stopTask(it) }
        registry.values.forEach { info ->
            runCatching {
                if (info.file.exists()) info.file.delete()
            }
        }
        registry.clear()
    }
}