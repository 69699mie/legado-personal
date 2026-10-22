package io.legado.app.help.config

import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.net.InetSocketAddress
import java.net.Proxy

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

    fun toUrlString(): String {
        if (host.isBlank() || port <= 0) return ""
        val auth = if (username.isNotBlank() && password.isNotBlank()) {
            "@$username@$password"
        } else ""
        val scheme = if (type.uppercase() == "SOCKS5") "socks5" else "http"
        return "$scheme://$host:$port$auth"
    }
}

object ProxyManager {
    private const val KEY_PROXY_CONFIGS = "book_source_proxy_configs"
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

    // 返回基础类型，彻底避开 ViewModel 层的数据实体依赖与文本校验
    suspend fun getBookSourceList(): List<Pair<String, String>> {
        return io.legado.app.data.appDb.bookSourceDao.all.map { it.bookSourceName to it.bookSourceUrl }
    }
}