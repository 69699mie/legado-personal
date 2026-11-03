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
    private var currentFid: String = ""

    var onRefreshUrlNeeded: (suspend (fid: String, cookie: String) -> Pair<String, String>?)? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // 海阔视界中抓包确认的 UC 浏览器 UA 和 Referer
    private val playUserAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/100.0.4896.58 UCBrowser/16.5.8.1309 Mobile Safari/537.36"
    private val playReferer = "https://drive.quark.cn/"

    fun start(context: Context) {
        if (server != null) return

        server = embeddedServer(CIO, port = 8090) {
            routing {
                get("/play") {
                    if (currentUrl.isEmpty()) {
                        call.respond(HttpStatusCode.NotFound, "No URL set")
                        return@get
                    }

                    // 获取 ExoPlayer 发来的 Range
                    val rangeHeader = call.request.headers[HttpHeaders.Range]

                    // 发起请求到夸克真实直链
                    val reqBuilder = Request.Builder()
                        .url(currentUrl)
                        .header("Cookie", currentCookie)
                        .header("User-Agent", playUserAgent)
                        .header("Referer", playReferer)
                    
                    if (rangeHeader != null) {
                        reqBuilder.header("Range", rangeHeader)
                    }

                    var response = client.newCall(reqBuilder.build()).execute()

                    // 如果遇到风控（412/403），尝试刷新链接并重试一次
                    if (response.code == 412 || response.code == 403) {
                        val result = onRefreshUrlNeeded?.invoke(currentFid, currentCookie)
                        if (result != null) {
                            currentUrl = result.first
                            currentCookie = result.second
                            
                            val retryReq = Request.Builder()
                                .url(currentUrl)
                                .header("Cookie", currentCookie)
                                .header("User-Agent", playUserAgent)
                                .header("Referer", playReferer)
                            if (rangeHeader != null) {
                                retryReq.header("Range", rangeHeader)
                            }
                            response = client.newCall(retryReq.build()).execute()
                        }
                    }

                    try {
                        if (response.code == 200 || response.code == 206) {
                            // 原样透传状态码
                            call.response.status(HttpStatusCode.fromValue(response.code))

                            // 原样透传关键 Headers
                            response.header("Content-Type")?.let { 
                                call.response.header(HttpHeaders.ContentType, it) 
                            }
                            response.header("Content-Length")?.let { 
                                call.response.header(HttpHeaders.ContentLength, it) 
                            }
                            response.header("Content-Range")?.let { 
                                call.response.header(HttpHeaders.ContentRange, it) 
                            }
                            response.header("Accept-Ranges")?.let { 
                                call.response.header(HttpHeaders.AcceptRanges, it) 
                            }

                            // 透传流（边下边播）
                            val body = response.body
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
                            } else {
                                call.respond(HttpStatusCode.InternalServerError, "Empty body from upstream")
                            }
                        } else {
                            // 如果不是 200/206，把错误信息透传给播放器，让它弹窗
                            val errorBody = response.body?.string() ?: "Unknown error"
                            call.respond(HttpStatusCode.InternalServerError, "HTTP ${response.code}\n$errorBody")
                        }
                    } finally {
                        response.close()
                    }
                }
            }
        }.start(wait = false)
    }

    fun setPlayInfo(url: String, cookie: String, fid: String) {
        this.currentUrl = url
        this.currentCookie = cookie
        this.currentFid = fid
    }

    fun stop() {
        // 透明代理即取即用，无需清理缓存
    }
}