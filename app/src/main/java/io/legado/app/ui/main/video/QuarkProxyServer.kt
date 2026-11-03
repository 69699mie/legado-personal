package io.legado.app.ui.main.video

import android.content.Context
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File
import java.io.RandomAccessFile

object QuarkProxyServer {
    private var server: EmbeddedServer<*, *>? = null
    private var currentUrl: String = ""
    private var currentCookie: String = ""
    private var currentFid: String = ""
    private var threadCount = 4
    private var downloader: MultiThreadDownloader? = null

    // 【修改】返回新链接和新 Cookie
    var onRefreshUrlNeeded: (suspend (fid: String, cookie: String) -> Pair<String, String>?)? = null

    fun start(context: Context) {
        if (server != null) return
        val cacheDir = File(context.cacheDir, "QuarkProxyCache").apply { mkdirs() }

        server = embeddedServer(CIO, port = 8090) {
            routing {
                get("/play") {
                    if (currentUrl.isEmpty()) {
                        call.respond(HttpStatusCode.NotFound, "No URL set")
                        return@get
                    }

                    val rangeHeader = call.request.headers[HttpHeaders.Range]
                    
                    if (downloader == null) {
                        downloader = MultiThreadDownloader(
                            context = context,
                            cacheDir = cacheDir,
                            initialUrl = currentUrl,
                            cookie = currentCookie,
                            threadCount = threadCount,
                            onRefreshUrl = {
                                val result = onRefreshUrlNeeded?.invoke(currentFid, currentCookie)
                                if (result != null) {
                                    currentCookie = result.second
                                }
                                result
                            }
                        )
                    }
                    
                    val success = downloader!!.prepare()
                    if (!success) {
                        call.respond(HttpStatusCode.InternalServerError, downloader!!.lastError.ifBlank { "Prepare failed" })
                        return@get
                    }

                    val totalLength = downloader!!.getTotalLength()
                    if (totalLength <= 0) {
                        call.respond(HttpStatusCode.InternalServerError, "Unknown length")
                        return@get
                    }

                    var start = 0L
                    var end = totalLength - 1

                    if (rangeHeader != null) {
                        val range = rangeHeader.replace("bytes=", "").split("-")
                        start = range.getOrNull(0)?.toLongOrNull() ?: 0L
                        if (range.size > 1 && range[1].isNotEmpty()) {
                            end = range[1].toLongOrNull() ?: end
                        }
                    }

                    downloader!!.startDownload(start, end)

                    call.response.status(HttpStatusCode.PartialContent)
                    call.response.header(HttpHeaders.AcceptRanges, "bytes")
                    call.response.header(HttpHeaders.ContentLength, (end - start + 1).toString())
                    call.response.header(HttpHeaders.ContentRange, "bytes $start-$end/$totalLength")
                    call.response.header(HttpHeaders.ContentType, "video/mp4")

                    call.respondOutputStream {
                        val raf = RandomAccessFile(downloader!!.cacheFile, "r")
                        raf.seek(start)
                        val buffer = ByteArray(64 * 1024)
                        var remaining = end - start + 1
                        
                        while (remaining > 0) {
                            downloader!!.awaitDataReady(start + (end - start + 1) - remaining)
                            
                            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                            val read = raf.read(buffer, 0, toRead)
                            if (read == -1) break
                            
                            this.write(buffer, 0, read)
                            remaining -= read
                        }
                        raf.close()
                    }
                }
            }
        }.start(wait = false)
    }

    fun setPlayInfo(url: String, cookie: String, fid: String, threads: Int = 4) {
        this.currentUrl = url
        this.currentCookie = cookie
        this.currentFid = fid
        this.threadCount = threads
        downloader?.stop()
        downloader = null
    }

    fun stop() {
        downloader?.stop()
        downloader = null
    }
}