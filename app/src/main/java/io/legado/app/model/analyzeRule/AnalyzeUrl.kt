package io.legado.app.model.analyzeRule

import android.annotation.SuppressLint
import android.util.Base64
import androidx.annotation.Keep
import androidx.media3.common.MediaItem
import com.bumptech.glide.load.model.GlideUrl
import com.script.buildScriptBindings
import com.script.rhino.RhinoScriptEngine
import com.script.rhino.runScriptWithContext
import io.legado.app.constant.AppConst.UA_NAME
import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.domain.gateway.DownloadCacheSettingsGateway
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.CacheManager
import io.legado.app.help.ConcurrentRateLimiter
import io.legado.app.help.JsExtensions
import io.legado.app.help.crypto.toHexString
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.help.glide.GlideHeaders
import io.legado.app.help.http.BackstageWebView
import io.legado.app.help.http.CookieManager
import io.legado.app.help.http.CookieManager.mergeCookies
import io.legado.app.help.http.CookieStore
import io.legado.app.help.http.RequestMethod
import io.legado.app.help.http.StrResponse
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.get
import io.legado.app.help.http.getDynamicProxyClient // === 新增导入 ===
import io.legado.app.help.http.getDirectClient // === 新增导入 ===
import io.legado.app.help.http.getProxyClient
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.postForm
import io.legado.app.help.http.postJson
import io.legado.app.help.http.postMultipart
import io.legado.app.help.source.getShareScope
import io.legado.app.utils.EncoderUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.GSONStrict
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.get
import io.legado.app.utils.isJson
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.isXml
import io.legado.app.utils.parseIpsFromString
import io.legado.app.utils.stackTraceStr
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.koin.core.context.GlobalContext
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.max

@Suppress("unused", "MemberVisibilityCanBePrivate")
@Keep
@SuppressLint("DefaultLocale")
class AnalyzeUrl(
    private val mUrl: String,
    private val key: String? = null,
    private val page: Int? = null,
    private val speakText: String? = null,
    private val speakSpeed: Int? = null,
    private var baseUrl: String = "",
    private val source: BaseSource? = null,
    private val ruleData: RuleDataInterface? = null,
    private val chapter: BookChapter? = null,
    private val readTimeout: Long? = null,
    private val callTimeout: Long? = null,
    private var coroutineContext: CoroutineContext = EmptyCoroutineContext,
    headerMapF: Map<String, String>? = null,
    hasLoginHeader: Boolean = true,
    private val infoMap: MutableMap<String, String>? = null
) : JsExtensions {
    constructor(mUrl: String) : this(mUrl, null)

    var ruleUrl = ""
        private set
    var url: String = ""
        private set
    var type: String? = null
        private set
    val headerMap = LinkedHashMap<String, String>()
    private var body: String? = null
    var urlNoQuery: String = ""
        private set
    private var encodedForm: String? = null
    private var encodedQuery: String? = null
    private var charset: String? = null
    private var method = RequestMethod.GET
    private var proxy: String? = null
    private var retry: Int = 0
    private var useWebView: Boolean = false
    private var webJs: String? = null
    private var bodyJs: String? = null
    private var dnsIp: String? = null
    private val enabledCookieJar = source?.enabledCookieJar == true
    private val domain: String
    private var webViewDelayTime: Long = 0
    private val concurrentRateLimiter = ConcurrentRateLimiter(source)
    private val cacheSettingsGateway get() = GlobalContext.get().get<DownloadCacheSettingsGateway>()

    var serverID: Long? = null
        private set

    init {
        coroutineContext = coroutineContext.minusKey(ContinuationInterceptor)
        val urlMatch = paramPattern.find(baseUrl)
        if (urlMatch != null) baseUrl = baseUrl.substring(0, urlMatch.range.first)
        (headerMapF ?: runScriptWithContext(coroutineContext) {
            source?.getHeaderMap(cacheSettingsGateway.currentSettings.userAgent, hasLoginHeader)
        })?.let {
            headerMap.putAll(it)
            if (it.containsKey("proxy")) {
                proxy = it["proxy"]
                headerMap.remove("proxy")
            }
        }
        initUrl()
        domain = NetworkUtils.getSubDomain(source?.getKey() ?: url)
    }

    fun initUrl() {
        ruleUrl = mUrl
        analyzeJs()
        replaceKeyPageJs()
        analyzeUrl()
    }

    private fun analyzeJs() {
        var start = 0
        var result = ruleUrl
        for (m in AppPattern.JS_PATTERN.findAll(ruleUrl)) {
            if (m.range.first > start) {
                ruleUrl.substring(start, m.range.first).trim().let {
                    if (it.isNotEmpty()) {
                        result = it.replace("@result", result)
                    }
                }
            }
            result = evalJS(m.groupValues[2].ifEmpty { m.groupValues[1] }, result).toString()
            start = m.range.last + 1
        }
        if (ruleUrl.length > start) {
            ruleUrl.substring(start).trim().let {
                if (it.isNotEmpty()) {
                    result = it.replace("@result", result)
                }
            }
        }
        ruleUrl = result
    }

    private fun replaceKeyPageJs() { 
        if (ruleUrl.contains("{{") && ruleUrl.contains("}}")) {
            val analyze = RuleAnalyzer(ruleUrl)
            val url = analyze.innerRule("{{", "}}") {
                val jsEval = evalJS(it) ?: ""
                when (jsEval) {
                    is String -> jsEval
                    is Double if jsEval % 1.0 == 0.0 -> String.format("%.0f", jsEval)
                    else -> jsEval.toString()
                }
            }
            if (url.isNotEmpty()) ruleUrl = url
        }
        page?.let {
            for (m in pagePattern.findAll(ruleUrl)) {
                val pages = m.groupValues[1].split(",")
                ruleUrl = if (page < pages.size) {
                    ruleUrl.replace(m.value, pages[page - 1].trim { it <= ' ' })
                } else {
                    ruleUrl.replace(m.value, pages.last().trim { it <= ' ' })
                }
            }
        }
    }

    private fun analyzeUrl() {
        val urlMatch = paramPattern.find(ruleUrl)
        val urlNoOption =
            if (urlMatch != null) ruleUrl.substring(0, urlMatch.range.first) else ruleUrl
        url = NetworkUtils.getAbsoluteURL(baseUrl, urlNoOption)
        NetworkUtils.getBaseUrl(url)?.let {
            baseUrl = it
        }
        if (urlMatch != null) {
            val urlOptionStr = ruleUrl.substring(urlMatch.range.last + 1)
            var urlOption = GSONStrict.fromJsonObject<UrlOption>(urlOptionStr).getOrNull()
            if (urlOption == null) {
                urlOption = GSON.fromJsonObject<UrlOption>(urlOptionStr).getOrNull()
                if (urlOption != null) {
                    log("链接参数 JSON 格式不规范，请改为规范格式")
                }
            }
            urlOption?.let { option ->
                option.getMethod()?.let {
                    method = when (it.uppercase()) {
                        "POST" -> RequestMethod.POST
                        "HEAD" -> RequestMethod.HEAD
                        else -> RequestMethod.GET
                    }
                }
                option.getHeaderMap()?.forEach { entry ->
                    headerMap[entry.key.toString()] = entry.value.toString()
                }
                option.getBody()?.let {
                    body = it
                }
                type = option.getType()
                charset = option.getCharset()
                retry = option.getRetry()
                useWebView = option.useWebView()
                webJs = option.getWebJs()
                bodyJs = option.getBodyJs()
                dnsIp = option.getDnsIp()
                option.getJs()?.let { jsStr ->
                    evalJS(jsStr, url)?.toString()?.let {
                        url = it
                    }
                }
                serverID = option.getServerID()
                webViewDelayTime = max(0, option.getWebViewDelayTime() ?: 0)
            }
        }
        urlNoQuery = url
        when (method) {
            RequestMethod.GET -> {
                val pos = url.indexOf('?')
                if (pos != -1) {
                    analyzeQuery(url.substring(pos + 1))
                    urlNoQuery = url.substring(0, pos)
                }
            }

            RequestMethod.POST -> body?.let {
                if (!it.isJson() && !it.isXml() && headerMap["Content-Type"].isNullOrEmpty()) {
                    analyzeFields(it)
                }
            }

            RequestMethod.HEAD -> Unit
        }
    }

    private fun analyzeFields(fieldsTxt: String) {
        encodedForm = encodeParams(fieldsTxt, charset, false)
    }

    private fun analyzeQuery(query: String) {
        encodedQuery = encodeParams(query, charset, true)
    }

    private fun encodeParams(params: String, charset: String?, isQuery: Boolean): String {
        val checkEncoded = charset.isNullOrEmpty()
        val charset = when {
            charset.isNullOrEmpty() -> Charsets.UTF_8
            charset == "escape" -> null
            else -> charset(charset)
        }
        if (isQuery && charset != null) {
            if (NetworkUtils.encodedQuery(params)) {
                return params
            }
            return encodeQueryParams(params, charset)
        }
        val len = params.length
        val sb = StringBuilder()
        var pos = 0
        while (pos <= len) {
            if (sb.isNotEmpty()) {
                sb.append("&")
            }
            var ampOffset = params.indexOf("&", pos)
            if (ampOffset == -1) {
                ampOffset = len
            }
            val eqOffset = params.indexOf("=", pos)
            val key: String
            val value: String?
            if (eqOffset == -1 || eqOffset > ampOffset) {
                key = params.substring(pos, ampOffset)
                value = null
            } else {
                key = params.substring(pos, eqOffset)
                value = params.substring(eqOffset + 1, ampOffset)
            }
            sb.appendEncoded(key, checkEncoded, charset)
            if (value != null) {
                sb.append("=")
                sb.appendEncoded(value, checkEncoded, charset)
            }
            pos = ampOffset + 1
        }
        return sb.toString()
    }

    private fun StringBuilder.appendEncoded(
        value: String,
        checkEncoded: Boolean,
        charset: Charset?
    ) {
        if (checkEncoded && NetworkUtils.encodedForm(value)) {
            append(value)
        } else if (charset == null) {
            append(EncoderUtils.escape(value))
        } else {
            append(URLEncoder.encode(value, charset))
        }
    }

    fun evalJS(jsStr: String, result: Any? = null): Any? {
        val bindings = buildScriptBindings { bindings ->
            bindings["java"] = this
            bindings["baseUrl"] = baseUrl
            bindings["cookie"] = CookieStore
            bindings["cache"] = CacheManager
            bindings["page"] = page
            bindings["key"] = key
            bindings["speakText"] = speakText
            bindings["speakSpeed"] = speakSpeed
            bindings["book"] = ruleData as? Book
            bindings["source"] = source
            bindings["result"] = result
            bindings["infoMap"] = infoMap
        }
        val sharedScope = source?.getShareScope(coroutineContext)
        val scope = if (sharedScope == null) {
            RhinoScriptEngine.getRuntimeScope(bindings)
        } else {
            bindings.apply {
                prototype = sharedScope
            }
        }
        return RhinoScriptEngine.eval(jsStr, scope, coroutineContext)
    }

    fun put(key: String, value: String): String {
        chapter?.putVariable(key, value)
            ?: ruleData?.putVariable(key, value)
        return value
    }

    fun get(key: String): String {
        when (key) {
            "bookName" -> (ruleData as? Book)?.let {
                return it.name
            }
            "title" -> chapter?.let {
                return it.title
            }
        }
        return chapter?.getVariable(key)?.takeIf { it.isNotEmpty() }
            ?: ruleData?.getVariable(key)?.takeIf { it.isNotEmpty() }
            ?: ""
    }

    suspend fun getStrResponseAwait(
        jsStr: String? = null,
        sourceRegex: String? = null,
        useWebView: Boolean = true,
        isTest: Boolean = false,
        skipRateLimit: Boolean = false
    ): StrResponse {
        if (type != null) {
            return StrResponse(url, getByteArrayAwait().toHexString())
        }
        if (skipRateLimit) {
            return executeStrRequest(jsStr, sourceRegex, useWebView, isTest)
        }
        concurrentRateLimiter.withLimit {
            return executeStrRequest(jsStr, sourceRegex, useWebView, isTest)
        }
    }

    private suspend fun executeStrRequest(
        jsStr: String? = null,
        sourceRegex: String? = null,
        useWebView: Boolean = true,
        isTest: Boolean = false
    ): StrResponse {
        setCookie()
        val startTime = System.currentTimeMillis()
        var strResponse: StrResponse
        try {
            if (this.useWebView && useWebView) {
                strResponse = when (method) {
                    RequestMethod.POST -> {
                        val res = getClient().newCallStrResponse(retry) {
                            addHeaders(headerMap)
                            url(urlNoQuery)
                            if (!encodedForm.isNullOrEmpty() || body.isNullOrBlank()) {
                                postForm(encodedForm ?: "")
                            } else {
                                postJson(body)
                            }
                        }
                        BackstageWebView(
                            url = res.url,
                            html = res.body,
                            tag = source?.getKey(),
                            javaScript = webJs ?: jsStr,
                            sourceRegex = sourceRegex,
                            headerMap = headerMap,
                            delayTime = webViewDelayTime
                        ).getStrResponse()
                    }

                    else -> BackstageWebView(
                        url = url,
                        tag = source?.getKey(),
                        javaScript = webJs ?: jsStr,
                        sourceRegex = sourceRegex,
                        headerMap = headerMap,
                        delayTime = webViewDelayTime
                    ).getStrResponse()
                }
            } else {
                strResponse = getClient().newCallStrResponse(retry) {
                    addHeaders(headerMap)
                    when (method) {
                        RequestMethod.POST -> {
                            url(urlNoQuery)
                            val contentType = headerMap["Content-Type"]
                            val body = body
                            if (!encodedForm.isNullOrEmpty() || body.isNullOrBlank()) {
                                postForm(encodedForm ?: "")
                            } else if (!contentType.isNullOrBlank()) {
                                val requestBody = body.toRequestBody(contentType.toMediaType())
                                post(requestBody)
                            } else {
                                postJson(body)
                            }
                        }

                        RequestMethod.HEAD -> {
                            get(urlNoQuery, encodedQuery)
                            head()
                        }

                        else -> get(urlNoQuery, encodedQuery)
                    }
                }.let {
                    val isXml = it.raw.body.contentType()?.toString()
                        ?.matches(AppPattern.xmlContentTypeRegex) == true
                    if (isXml && it.body?.trim()?.startsWith("<?xml", true) == false) {
                        StrResponse(it.raw, "<?xml version=\"1.0\"?>" + it.body)
                    } else if (bodyJs != null) {
                        val body = evalJS(bodyJs!!, it.body).toString()
                        StrResponse(it.raw, body)
                    } else it
                }
            }
            val connectionTime = System.currentTimeMillis() - startTime
            strResponse.putCallTime(connectionTime.toInt())
            return strResponse
        } catch (e: Exception) {
            // === 新增：代理失败回退直连 ===
            val isProxyConfigured = io.legado.app.help.config.ProxyManager.getConfig(source?.getKey()) != null
            val isNetworkError = e is java.net.SocketTimeoutException ||
                    e is java.net.ConnectException ||
                    e is java.net.SocketException ||
                    e is java.net.UnknownHostException ||
                    e is javax.net.ssl.SSLException ||
                    (e.message?.contains("407") == true)

            if (isProxyConfigured && isNetworkError && !useWebView) {
                try {
                    // 使用直连 Client 重试
                    strResponse = getDirectClient(false).newCallStrResponse(retry) {
                        addHeaders(headerMap)
                        when (method) {
                            RequestMethod.POST -> {
                                url(urlNoQuery)
                                val contentType = headerMap["Content-Type"]
                                val body = body
                                if (!encodedForm.isNullOrEmpty() || body.isNullOrBlank()) {
                                    postForm(encodedForm ?: "")
                                } else if (!contentType.isNullOrBlank()) {
                                    val requestBody = body.toRequestBody(contentType.toMediaType())
                                    post(requestBody)
                                } else {
                                    postJson(body)
                                }
                            }

                            RequestMethod.HEAD -> {
                                get(urlNoQuery, encodedQuery)
                                head()
                            }

                            else -> get(urlNoQuery, encodedQuery)
                        }
                    }.let {
                        val isXml = it.raw.body.contentType()?.toString()
                            ?.matches(AppPattern.xmlContentTypeRegex) == true
                        if (isXml && it.body?.trim()?.startsWith("<?xml", true) == false) {
                            StrResponse(it.raw, "<?xml version=\"1.0\"?>" + it.body)
                        } else if (bodyJs != null) {
                            val body = evalJS(bodyJs!!, it.body).toString()
                            StrResponse(it.raw, body)
                        } else it
                    }
                    val connectionTime = System.currentTimeMillis() - startTime
                    strResponse.putCallTime(connectionTime.toInt())
                    return strResponse
                } catch (retryException: Exception) {
                    throw retryException // 直连也失败，抛出
                }
            }
            // =======================

            if (!isTest) {
                throw e
            }
            val errorCode = when (e) {
                is java.net.SocketTimeoutException -> -2
                is java.net.UnknownHostException -> -3
                is java.net.ConnectException -> -4
                is java.net.SocketException -> -5
                is javax.net.ssl.SSLException -> -6
                is java.io.InterruptedIOException -> {
                    if (e.message?.contains("timeout") == true) {
                        -1
                    } else -7
                }

                else -> -7
            }
            return StrResponse(url, e.message).apply {
                putCallTime(errorCode)
            }
        }
    }

    @JvmOverloads
    fun getStrResponse(
        jsStr: String? = null,
        sourceRegex: String? = null,
        useWebView: Boolean = true,
    ): StrResponse {
        return runBlocking(coroutineContext) {
            getStrResponseAwait(jsStr, sourceRegex, useWebView)
        }
    }

    suspend fun getResponseAwait(): Response {
        concurrentRateLimiter.withLimit {
            setCookie()
            val response = getClient().newCallResponse(retry) {
                addHeaders(headerMap)
                when (method) {
                    RequestMethod.POST -> {
                        url(urlNoQuery)
                        val contentType = headerMap["Content-Type"]
                        val body = body
                        if (!encodedForm.isNullOrEmpty() || body.isNullOrBlank()) {
                            postForm(encodedForm ?: "")
                        } else if (!contentType.isNullOrBlank()) {
                            val requestBody = body.toRequestBody(contentType.toMediaType())
                            post(requestBody)
                        } else {
                            postJson(body)
                        }
                    }

                    RequestMethod.HEAD -> {
                        get(urlNoQuery, encodedQuery)
                        head()
                    }

                    else -> get(urlNoQuery, encodedQuery)
                }
            }
            return response
        }
    }

    fun getErrResponse(e: Throwable): Response = Response.Builder()
        .request(Request.Builder().url(url).build())
        .protocol(okhttp3.Protocol.HTTP_1_1)
        .code(500)
        .message(e.message ?: "Error Response")
        .body(e.stackTraceStr.toResponseBody(null))
        .build()

    fun getErrStrResponse(e: Throwable): StrResponse =
        StrResponse(getErrResponse(e), e.stackTraceStr)

    private fun getClient(): OkHttpClient {
        // === 修改点：优先使用书源自带的 proxy 字段，如果没有，则查全局代理配置 ===
        val client = if (!proxy.isNullOrBlank()) {
            getProxyClient(proxy)
        } else {
            getDynamicProxyClient(source?.getKey(), false)
        }

        if (readTimeout == null && callTimeout == null && dnsIp == null) {
            return client
        }
        if (cacheSettingsGateway.currentSettings.cronetEnabled && dnsIp != null) {
            customIp[urlNoQuery] = dnsIp!!
        }
        return client.newBuilder().run {
            if (readTimeout != null) {
                readTimeout(readTimeout, TimeUnit.MILLISECONDS)
                callTimeout(max(60 * 1000L, readTimeout * 2), TimeUnit.MILLISECONDS)
            }
            if (callTimeout != null) {
                callTimeout(callTimeout, TimeUnit.MILLISECONDS)
            }
            if (dnsIp != null) {
                val inetAddress = dnsIp!!.parseIpsFromString()
                dns { hostname ->
                    inetAddress ?: Dns.SYSTEM.lookup(hostname)
                }
            }
            build()
        }
    }

    private fun extractHostFromUrl(url: String): String? {
        return AppPattern.domainRegex.find(url)?.groupValues?.getOrNull(1)
    }

    fun getResponse(): Response {
        return runBlocking(coroutineContext) {
            getResponseAwait()
        }
    }

    private fun getByteArrayIfDataUri(): ByteArray? {
        if (!urlNoQuery.startsWith("data:")) {
            return null
        }
        val dataUriFindResult = AppPattern.dataUriRegex.find(urlNoQuery)
        if (dataUriFindResult != null) {
            val dataUriBase64 = dataUriFindResult.groupValues[1]
            val byteArray = Base64.decode(dataUriBase64, Base64.DEFAULT)
            return byteArray
        }
        return null
    }

    suspend fun getByteArrayAwait(): ByteArray {
        getByteArrayIfDataUri()?.let {
            return it
        }
        return getResponseAwait().body.bytes()
    }

    fun getByteArray(): ByteArray {
        return runBlocking(coroutineContext) {
            getByteArrayAwait()
        }
    }

    suspend fun getInputStreamAwait(): InputStream {
        getByteArrayIfDataUri()?.let {
            return ByteArrayInputStream(it)
        }
        return getResponseAwait().body.byteStream()
    }

    fun getInputStream(): InputStream {
        return runBlocking(coroutineContext) {
            getInputStreamAwait()
        }
    }

    suspend fun upload(fileName: String, file: Any, contentType: String): StrResponse {
        val bodyMap = GSON.fromJsonObject<HashMap<String, Any>>(body).getOrNull()
            ?: return getErrStrResponse(NoStackTraceException("请求体不是合法的JSON格式"))
        return getProxyClient(proxy).newCallStrResponse(retry) {
            url(urlNoQuery)
            bodyMap.forEach { entry ->
                if (entry.value.toString() == "fileRequest") {
                    bodyMap[entry.key] = mapOf(
                        Pair("fileName", fileName),
                        Pair("file", file),
                        Pair("contentType", contentType)
                    )
                }
            }
            postMultipart(type, bodyMap)
        }
    }

    private fun setCookie() {
        val cookie = kotlin.run {
            CookieStore.getCookie(domain)
        }
        if (cookie.isNotEmpty()) {
            mergeCookies(cookie, headerMap["Cookie"])?.let {
                headerMap.put("Cookie", it)
            }
        }
        if (enabledCookieJar) {
            headerMap[CookieManager.cookieJarHeader] = "1"
        } else {
            headerMap.remove(CookieManager.cookieJarHeader)
        }
    }

    private fun saveCookie() {
        if (enabledCookieJar) {
            val key = "${domain}_cookieJar"
            CacheManager.getFromMemory(key)?.let {
                if (it is String) {
                    CookieStore.replaceCookie(domain, it)
                    CacheManager.deleteMemory(key)
                }
            }
        }
    }

    fun getGlideUrl(): GlideUrl {
        setCookie()
        return GlideUrl(url, GlideHeaders(headerMap))
    }

    fun getUrlAndHeaders(): Pair<String, Map<String, String>> {
        setCookie()
        return Pair(url, headerMap)
    }

    fun getUserAgent(): String {
        return headerMap.get(UA_NAME, true) ?: cacheSettingsGateway.currentSettings.userAgent
    }

    fun isPost(): Boolean {
        return method == RequestMethod.POST
    }

    override fun getSource(): BaseSource? {
        return source
    }

    override fun getTag(): String? {
        return source?.getTag()
    }

    companion object {
        val paramPattern: Regex = Regex("\\s*,\\s*(?=\\{)")
        private val pagePattern = Regex("<(.*?)>")
        private const val QUERY_HEX_UPPER = "0123456789ABCDEF"
        private val queryEncoderSafeChars: BooleanArray = BooleanArray(128).apply {
            for (c in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_.-~!$%&()*+,/:;=?@[\\]^`{|}") {
                this[c.code] = true
            }
        }

        private fun encodeQueryParams(str: String, charset: Charset): String {
            val builder = StringBuilder(str.length)
            for (c in str) {
                if (c.code < 128 && queryEncoderSafeChars[c.code]) {
                    builder.append(c)
                } else {
                    val bytes = c.toString().toByteArray(charset)
                    for (b in bytes) {
                        val v = b.toInt() and 0xFF
                        builder.append('%')
                        builder.append(QUERY_HEX_UPPER[v ushr 4])
                        builder.append(QUERY_HEX_UPPER[v and 0x0F])
                    }
                }
            }
            return builder.toString()
        }

        val customIp by lazy { ConcurrentHashMap<String, String>() }
        fun AnalyzeUrl.getMediaItem(): MediaItem {
            setCookie()
            return ExoPlayerHelper.createMediaItem(url, headerMap)
        }
    }

    @Keep
    data class UrlOption(
        private var method: String? = null,
        private var charset: String? = null,
        private var headers: Any? = null,
        private var body: Any? = null,
        private var origin: String? = null,
        private var retry: Int? = null,
        private var type: String? = null,
        private var webView: Any? = null,
        private var webJs: String? = null,
        private var dnsIp: String? = null,
        private var js: String? = null,
        private var bodyJs: String? = null,
        private var serverID: Long? = null,
        private var webViewDelayTime: Long? = null,
    ) {
        fun setMethod(value: String?) { method = if (value.isNullOrBlank()) null else value }
        fun getMethod(): String? = method
        fun setCharset(value: String?) { charset = if (value.isNullOrBlank()) null else value }
        fun getCharset(): String? = charset
        fun setOrigin(value: String?) { origin = if (value.isNullOrBlank()) null else value }
        fun getOrigin(): String? = origin
        fun setRetry(value: String?) { retry = if (value.isNullOrEmpty()) null else value.toIntOrNull() }
        fun getRetry(): Int = retry ?: 0
        fun setType(value: String?) { type = if (value.isNullOrBlank()) null else value }
        fun getType(): String? = type
        fun useWebView(): Boolean = when (webView) { null, "", false, "false" -> false else -> true }
        fun useWebView(boolean: Boolean) { webView = if (boolean) true else null }
        fun setHeaders(value: String?) {
            headers = if (value.isNullOrBlank()) null else GSON.fromJsonObject<Map<String, Any>>(value).getOrNull()
        }
        fun getHeaderMap(): Map<*, *>? {
            return when (val value = headers) {
                is Map<*, *> -> value
                is String -> GSON.fromJsonObject<Map<String, Any>>(value).getOrNull()
                else -> null
            }
        }
        fun setBody(value: String?) {
            body = when {
                value.isNullOrBlank() -> null
                value.isJsonObject() -> GSON.fromJsonObject<Map<String, Any>>(value).getOrNull()
                value.isJsonArray() -> GSON.fromJsonArray<Map<String, Any>>(value).getOrNull()
                else -> value
            }
        }
        fun getBody(): String? = body?.let { it as? String ?: GSON.toJson(it) }
        fun setWebJs(value: String?) { webJs = if (value.isNullOrBlank()) null else value }
        fun getWebJs(): String? = webJs
        fun setDnsIp(value: String?) { dnsIp = if (value.isNullOrBlank()) null else value }
        fun getDnsIp(): String? = dnsIp
        fun setJs(value: String?) { js = if (value.isNullOrBlank()) null else value }
        fun getJs(): String? = js
        fun setBodyJs(value: String?) { bodyJs = if (value.isNullOrBlank()) null else value }
        fun getBodyJs(): String? = bodyJs
        fun setServerID(value: String?) { serverID = if (value.isNullOrBlank()) null else value.toLong() }
        fun getServerID(): Long? = serverID
        fun setWebViewDelayTime(value: String?) { webViewDelayTime = if (value.isNullOrBlank()) null else value.toLong() }
        fun getWebViewDelayTime(): Long? = webViewDelayTime
    }

    data class ConcurrentRecord(
        var time: Long,
        var accessLimit: Int,
        var interval: Int,
        var frequency: Int
    )
}