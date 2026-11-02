package io.legado.app.ui.main.video

import io.ktor.http.ContentType
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
    private var server: ApplicationEngine? = null
    private var currentUrl: String = ""
    private var currentCookie: String = ""

    // 针对视频流配置专门的客户端，增加超时和长连接
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun start() {
        if (server != null) return
        server = embeddedServer(CIO, port = 8090) {
            routing {
                get("/play") {
                    if (currentUrl.isEmpty()) {
                        call.respond(HttpStatusCode.NotFound, "No URL set")
                        return@get
                    }

                    // 获取播放器传来的 Range 请求头（支持进度条拖动）
                    val range = call.request.headers[HttpHeaders.Range]
                    val reqBuilder = Request.Builder()
                        .url(currentUrl)
                        .header("Cookie", currentCookie)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch")
                        .header("Referer", "https://pan.quark.cn/")

                    if (range != null) {
                        reqBuilder.header("Range", range)
                    }

                    try {
                        client.newCall(reqBuilder.build()).execute().use { response ->
                            call.response.status(HttpStatusCode.fromValue(response.code))
                            
                            // 继承响应头，确保客户端获取到总大小、文件类型等信息
                            response.header("Content-Type")?.let { 
                                call.response.header(HttpHeaders.ContentType, it) 
                            }
                            response.header("Content-Length")?.let { 
                                call.response.header(HttpHeaders.ContentLength, it) 
                            }
                            response.header("Content-Range")?.let { 
                                call.response.header(HttpHeaders.ContentRange, it) 
                            }

                            // 流式输出
                            call.respondOutputStream {
                                response.body?.byteStream()?.copyTo(this)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        call.respond(HttpStatusCode.InternalServerError)
                    }
                }
            }
        }.start(wait = false)
    }

    fun setPlayInfo(url: String, cookie: String) {
        currentUrl = url
        currentCookie = cookie
    }
}