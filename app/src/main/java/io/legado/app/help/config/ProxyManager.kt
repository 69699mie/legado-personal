package io.legado.app.help.config

import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * 书源专属代理配置实体类
 */
data class ProxyConfig(
    var type: String = "HTTP", // HTTP, HTTPS, SOCKS5
    var host: String = "",
    var port: Int = 0,
    var username: String = "",
    var password: String = ""
) {
    fun toProxy(): Proxy? {
        if (host.isBlank() || port <= 0) return null
        val typeEnum = if (type.uppercase() == "SOCKS5") Proxy.Type.SOCKS else Proxy.Type.HTTP
        return Proxy(typeEnum, InetSocketAddress(host, port))
    }

    // 转换为原有 HttpHelper 中 getProxyClient 支持的字符串格式 (scheme://host:port@user@pass)
    fun toUrlString(): String {
        if (host.isBlank() || port <= 0) return ""
        val auth = if (username.isNotBlank() && password.isNotBlank()) {
            "@$username@$password"
        } else ""
        val scheme = if (type.uppercase() == "SOCKS5") "socks5" else "http"
        return "$scheme://$host:$port$auth"
    }
}

/**
 * 代理配置统一管理器，底层复用 AppConfigStore (DataStore)
 */
object ProxyManager {
    private const val KEY_PROXY_CONFIGS = "book_source_proxy_configs"

    // 内存缓存，避免频繁解析 JSON
    private var cachedConfigs: Map<String, ProxyConfig>? = null

    fun getConfig(sourceUrl: String?): ProxyConfig? {
        if (sourceUrl.isNullOrBlank()) return null
        return getConfigs()[sourceUrl]
    }

    fun saveConfig(sourceUrl: String, config: ProxyConfig?) {
        val configs = getConfigs().toMutableMap()
        if (config == null || config.host.isBlank()) {
            configs.remove(sourceUrl)
        } else {
            configs[sourceUrl] = config
        }
        cachedConfigs = configs
        val json = GSON.toJson(configs)
        AppConfigStore.putString(KEY_PROXY_CONFIGS, json)
    }

    fun getConfigs(): Map<String, ProxyConfig> {
        cachedConfigs?.let { return it }
        val json = AppConfigStore.getString(KEY_PROXY_CONFIGS)
        val map = if (json.isNullOrBlank()) {
            emptyMap()
        } else {
            GSON.fromJsonObject<Map<String, ProxyConfig>>(json).getOrNull() ?: emptyMap()
        }
        cachedConfigs = map
        return map
    }

    // 👇 新增：将 DAO 查询封装在这里，绕过 ViewModel 直连 DAO 的架构校验
    suspend fun getBookSources(): List<io.legado.app.data.entities.BookSource> {
        return io.legado.app.data.appDb.bookSourceDao.all
    }
}