package io.legado.app.ui.main.video

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.VideoSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class VideoScraperViewModel(
    private val videoSettingsRepository: VideoSettingsRepository
) : ViewModel() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val _uiState = MutableStateFlow(VideoUiState())
    val uiState = _uiState.asStateFlow()

    init {
        _uiState.update { it.copy(cookie = videoSettingsRepository.quarkCookie) }
    }

    fun saveCookie(cookie: String) {
        // 💡 保存时去掉首尾可能存在的换行和空格
        val cleanCookie = cookie.replace("\n", "").replace("\r", "").trim()
        videoSettingsRepository.quarkCookie = cleanCookie
        _uiState.update { it.copy(cookie = cleanCookie) }
    }

    fun fetchQuarkFiles() {
        val cookie = _uiState.value.cookie
        if (cookie.isBlank()) {
            _uiState.update { it.copy(errorMsg = "请先设置 Cookie") }
            return
        }

        _uiState.update { it.copy(isLoading = true, errorMsg = null, fileList = emptyList()) }

        viewModelScope.launch {
            try {
                // 💡 修复：使用 PC 端 API 地址
                val url = "https://drive-pc.quark.cn/1/clouddrive/file/sort?pr=ucpro&fr=pc&uc_param_str=&pdir_fid=0&_page=1&_size=50&_sort=file_type:asc,updated_at:desc"
                val request = Request.Builder()
                    .url(url)
                    .header("Cookie", cookie)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Referer", "https://pan.quark.cn/")
                    .header("Accept", "application/json, text/plain, */*")
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string()

                if (response.isSuccessful && !body.isNullOrBlank()) {
                    val json = JSONObject(body)
                    val code = json.optInt("code", -1)
                    if (code == 0) {
                        val data = json.optJSONObject("data")
                        val list = data?.optJSONArray("list")
                        val names = mutableListOf<String>()
                        if (list != null) {
                            for (i in 0 until list.length()) {
                                val item = list.getJSONObject(i)
                                val fileName = item.optString("file_name")
                                names.add(fileName)
                            }
                        }
                        _uiState.update { it.copy(isLoading = false, fileList = names) }
                    } else {
                        _uiState.update { it.copy(isLoading = false, errorMsg = "获取失败，Cookie 可能已过期 (Code: $code)") }
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false, errorMsg = "网络请求失败: ${response.code}") }
                }
            } catch (e: Exception) {
                // 💡 修复：使用 e.toString() 能显示具体的异常类型，比如是因为换行符还是网络不通
                val errDetail = e.toString()
                _uiState.update { it.copy(isLoading = false, errorMsg = "请求异常: $errDetail") }
            }
        }
    }
}

data class VideoUiState(
    val cookie: String = "",
    val isLoading: Boolean = false,
    val errorMsg: String? = null,
    val fileList: List<String> = emptyList()
)