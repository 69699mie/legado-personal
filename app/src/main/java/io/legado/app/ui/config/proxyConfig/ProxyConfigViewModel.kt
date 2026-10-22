package io.legado.app.ui.config.proxyConfig

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.ProxyConfig
import io.legado.app.help.config.ProxyManager
import io.legado.app.help.http.getProxyClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class ProxyTestResult(
    val isTesting: Boolean = false,
    val success: Boolean? = null,
    val message: String = ""
)

data class SourceProxyItem(
    val source: BookSource,
    val config: ProxyConfig?
)

class ProxyConfigViewModel(app: Application) : AndroidViewModel(app) {
    private val _sources = MutableStateFlow<List<SourceProxyItem>>(emptyList())
    val sources: StateFlow<List<SourceProxyItem>> = _sources.asStateFlow()

    private val _testResult = MutableStateFlow<ProxyTestResult>(ProxyTestResult())
    val testResult: StateFlow<ProxyTestResult> = _testResult.asStateFlow()

    init {
        loadSources()
    }

    fun loadSources() {
        viewModelScope.launch(Dispatchers.IO) {
            // 获取全部启用的书源
            val allSources = appDb.bookSourceDao.all
            val configs = ProxyManager.getConfigs()
            val items = allSources.map { source ->
                SourceProxyItem(source, configs[source.bookSourceUrl])
            }.sortedBy { it.source.bookSourceName }
            _sources.value = items
        }
    }

    fun saveProxy(sourceUrl: String, config: ProxyConfig?) {
        ProxyManager.saveConfig(sourceUrl, config)
        // 更新内存中的列表状态
        _sources.value = _sources.value.map {
            if (it.source.bookSourceUrl == sourceUrl) {
                it.copy(config = config)
            } else it
        }
    }

    fun testProxy(sourceUrl: String, config: ProxyConfig) {
        _testResult.value = ProxyTestResult(isTesting = true)
        viewModelScope.launch(Dispatchers.IO) {
            val proxyStr = config.toUrlString()
            if (proxyStr.isBlank()) {
                _testResult.value = ProxyTestResult(success = false, message = "配置不完整")
                return@launch
            }

            val proxyClient = getProxyClient(proxyStr).newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            val startTime = System.currentTimeMillis()
            try {
                val request = Request.Builder()
                    .url(sourceUrl)
                    // 带上默认 UA，避免部分网站拦截
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .build()

                val response = proxyClient.newCall(request).execute()
                val timeCost = System.currentTimeMillis() - startTime

                if (response.isSuccessful || response.code == 200 || response.code in 300..399) {
                    _testResult.value = ProxyTestResult(success = true, message = "成功 (HTTP ${response.code}) - 耗时 ${timeCost}ms")
                } else {
                    _testResult.value = ProxyTestResult(success = false, message = "失败 (HTTP ${response.code}) - 耗时 ${timeCost}ms")
                }
                response.close()
            } catch (e: Exception) {
                val timeCost = System.currentTimeMillis() - startTime
                _testResult.value = ProxyTestResult(success = false, message = "异常: ${e.localizedMessage ?: "连接失败"} (${timeCost}ms)")
            }
        }
    }

    fun clearTestResult() {
        _testResult.value = ProxyTestResult()
    }
}
