package io.legado.app.ui.main.video

import android.content.Context
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object QuarkProxyServer {
    private var server: EmbeddedServer<*, *>? = null
    private var currentUrl: String = ""
    private var currentCookie: String = ""

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS) // 拉长超时，防止断连
        .build()

    fun start(context: Context) {
        if (server != null) return

        server = embeddedServer(CIO, port = 8090) {
            routing {
                get("/play") {
                    if (currentUrl.isEmpty()) {
                        call.respond(HttpStatusCode.NotFound, "No URL set")
                        return@get
                    }

                    val rangeHeader = call.request.headers[HttpHeaders.Range]
                    val reqBuilder = Request.Builder()
                        .url(currentUrl)
                        .header("Cookie", currentCookie)
                        .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/100.0.4896.58 UCBrowser/16.5.8.1309 Mobile Safari/537.36")
                        .header("Referer", "https://drive.quark.cn/")

                    if (rangeHeader != null) {
                        reqBuilder.header("Range", rangeHeader)
                    }

                    try {
                        val response = client.newCall(reqBuilder.build()).execute()
                        val body = response.body

                        if (response.code == 200 || response.code == 206) {
                            call.response.status(HttpStatusCode.fromValue(response.code))
                            response.header("Content-Range")?.let { call.response.header(HttpHeaders.ContentRange, it) }
                            response.header("Content-Length")?.let { call.response.header(HttpHeaders.ContentLength, it) }
                            response.header("Accept-Ranges")?.let { call.response.header(HttpHeaders.AcceptRanges, it) }
                            
                            // 透传流
                            if (body != null) {
                                call.respondOutputStream {
                                    val inputStream = body.byteStream()
                                    val buffer = ByteArray(64 * 1024)
                                    var read = inputStream.read(buffer)
                                    while (read != -1) {
                                        this.write(buffer, 0, read)
                                        read = inputStream.read(buffer)
                                    }
                                }
                            }
                        } else {
                            call.respond(HttpStatusCode.InternalServerError, "HTTP ${response.code}\n${body?.string()}")
                        }
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.InternalServerError, "Exception: ${e.message}")
                    }
                }
            }
        }.start(wait = false)
    }

    fun setPlayInfo(url: String, cookie: String) {
        this.currentUrl = url
        this.currentCookie = cookie
    }

    fun stop() {
        // 纯透传，无需清理缓存
    }
}