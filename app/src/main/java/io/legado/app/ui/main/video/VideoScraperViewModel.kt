package io.legado.app.ui.main.video

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.VideoSettingsRepository
import io.legado.app.utils.ParsedVideoName
import io.legado.app.utils.VideoFileNameParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class QuarkFile(
    val fid: String,
    val name: String,
    val isFolder: Boolean,
    val parsedInfo: ParsedVideoName? = null // ➕ 新增解析信息
)

class VideoScraperViewModel(
    private val videoSettingsRepository: VideoSettingsRepository
) : ViewModel() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val _uiState = MutableStateFlow(VideoUiState())
    val uiState = _uiState.asStateFlow()
    
    private val pathHistory = mutableListOf<String>()

    init {
        _uiState.update { 
            it.copy(
                cookie = videoSettingsRepository.quarkCookie,
                tmdbApiKey = videoSettingsRepository.tmdbApiKey
            ) 
        }
    }

    fun saveSettings(cookie: String, tmdbKey: String) {
        val cleanCookie = cookie.replace("\n", "").replace("\r", "").trim()
        videoSettingsRepository.quarkCookie = cleanCookie
        videoSettingsRepository.tmdbApiKey = tmdbKey
        _uiState.update { it.copy(cookie = cleanCookie, tmdbApiKey = tmdbKey) }
    }

    fun fetchQuarkFiles(fid: String = "0", isBack: Boolean = false) {
        val cookie = _uiState.value.cookie
        if (cookie.isBlank()) {
            _uiState.update { it.copy(errorMsg = "请先设置 Cookie") }
            return
        }

        if (fid == "0") {
            pathHistory.clear()
        } else if (!isBack && _uiState.value.currentFid != fid) {
            pathHistory.add(_uiState.value.currentFid)
        }

        _uiState.update { 
            it.copy(
                currentFid = fid,
                isLoading = true, 
                errorMsg = null, 
                fileList = emptyList()
            ) 
        }

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val url = "https://drive-pc.quark.cn/1/clouddrive/file/sort?pr=ucpro&fr=pc&uc_param_str=&pdir_fid=$fid&_page=1&_size=100&_sort=file_type:asc,updated_at:desc"
                    val request = Request.Builder()
                        .url(url)
                        .header("Cookie", cookie)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .header("Referer", "https://pan.quark.cn/")
                        .build()

                    val response = client.newCall(request).execute()
                    val body = response.body?.string()
                    Pair(response.isSuccessful, body)
                }

                val (isSuccessful, body) = result

                if (isSuccessful && !body.isNullOrBlank()) {
                    val json = JSONObject(body)
                    val code = json.optInt("code", -1)
                    if (code == 0) {
                        val data = json.optJSONObject("data")
                        val list = data?.optJSONArray("list")
                        val files = mutableListOf<QuarkFile>()
                        if (list != null) {
                            for (i in 0 until list.length()) {
                                val item = list.getJSONObject(i)
                                val fileName = item.optString("file_name")
                                val fileFid = item.optString("fid")
                                val isFolder = item.optBoolean("dir", false)
                                
                                // ➕ 如果是文件，直接在这里做宽松解析
                                val parsed = if (!isFolder) VideoFileNameParser.parse(fileName) else null
                                
                                // 如果是视频文件或文件夹才加入列表，过滤掉 apk、txt 等
                                if (isFolder || (parsed != null && parsed.isVideo)) {
                                    files.add(QuarkFile(fileFid, fileName, isFolder, parsed))
                                }
                            }
                        }
                        _uiState.update { it.copy(isLoading = false, fileList = files) }
                    } else {
                        _uiState.update { it.copy(isLoading = false, errorMsg = "获取失败，Cookie 可能已过期 (Code: $code)") }
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false, errorMsg = "网络请求失败") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMsg = "请求异常: ${e.message}") }
            }
        }
    }

    fun goBack() {
        if (pathHistory.isNotEmpty()) {
            val parentFid = pathHistory.removeAt(pathHistory.size - 1)
            fetchQuarkFiles(parentFid, isBack = true)
        } else {
            fetchQuarkFiles("0")
        }
    }
}

data class VideoUiState(
    val cookie: String = "",
    val tmdbApiKey: String = "",
    val currentFid: String = "0",
    val isLoading: Boolean = false,
    val errorMsg: String? = null,
    val fileList: List<QuarkFile> = emptyList()
)