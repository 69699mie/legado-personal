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
    val parsedInfo: ParsedVideoName? = null
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
        val savedLibs = videoSettingsRepository.libraryFids.split(",").filter { it.isNotBlank() }
        _uiState.update { 
            it.copy(
                cookie = videoSettingsRepository.quarkCookie,
                tmdbApiKey = videoSettingsRepository.tmdbApiKey,
                selectedLibraryFids = savedLibs.toSet()
            ) 
        }
        // ➕ 初始化时自动请求根目录
        fetchQuarkFiles("0")
    }

    fun saveSettings(cookie: String, tmdbKey: String) {
        val cleanCookie = cookie.replace("\n", "").replace("\r", "").trim()
        videoSettingsRepository.quarkCookie = cleanCookie
        videoSettingsRepository.tmdbApiKey = tmdbKey
        _uiState.update { it.copy(cookie = cleanCookie, tmdbApiKey = tmdbKey) }
        fetchQuarkFiles("0")
    }

    // 切换文件夹是否加入媒体库
    fun toggleLibrary(fid: String) {
        val current = _uiState.value.selectedLibraryFids.toMutableSet()
        if (current.contains(fid)) current.remove(fid) else current.add(fid)
        
        videoSettingsRepository.libraryFids = current.joinToString(",")
        _uiState.update { it.copy(selectedLibraryFids = current) }
        
        // 如果当前在影视库页面，刷新一下
        if (_uiState.value.isLibraryMode) {
            loadLibraryVideos()
        }
    }

    // 切换 UI 模式（网盘浏览 vs 影视库）
    fun setMode(isLibraryMode: Boolean) {
        _uiState.update { it.copy(isLibraryMode = isLibraryMode) }
        if (isLibraryMode) {
            loadLibraryVideos()
        }
    }

    // 扫描所有选中的文件夹，提取视频并放入海报墙列表
    fun loadLibraryVideos() {
        val cookie = _uiState.value.cookie
        val libs = _uiState.value.selectedLibraryFids
        if (libs.isEmpty()) {
            _uiState.update { it.copy(libraryVideos = emptyList()) }
            return
        }

        _uiState.update { it.copy(isLoading = true, errorMsg = null) }

        viewModelScope.launch {
            try {
                val allVideos = mutableListOf<QuarkFile>()
                withContext(Dispatchers.IO) {
                    for (fid in libs) {
                        val url = "https://drive-pc.quark.cn/1/clouddrive/file/sort?pr=ucpro&fr=pc&uc_param_str=&pdir_fid=$fid&_page=1&_size=100&_sort=file_type:asc,updated_at:desc"
                        val request = Request.Builder()
                            .url(url)
                            .header("Cookie", cookie)
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                            .header("Referer", "https://pan.quark.cn/")
                            .build()

                        val response = client.newCall(request).execute()
                        val body = response.body?.string()

                        if (body != null) {
                            val json = JSONObject(body)
                            if (json.optInt("code", -1) == 0) {
                                val list = json.optJSONObject("data")?.optJSONArray("list")
                                if (list != null) {
                                    for (i in 0 until list.length()) {
                                        val item = list.getJSONObject(i)
                                        val fileName = item.optString("file_name")
                                        val fileFid = item.optString("fid")
                                        val isFolder = item.optBoolean("dir", false)
                                        // 这里暂时只取直接包含的视频文件，不深度递归
                                        if (!isFolder) {
                                            val parsed = VideoFileNameParser.parse(fileName)
                                            if (parsed.isVideo) {
                                                allVideos.add(QuarkFile(fileFid, fileName, false, parsed))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                _uiState.update { it.copy(isLoading = false, libraryVideos = allVideos) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMsg = "获取媒体库失败: ${e.message}") }
            }
        }
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
                                
                                val parsed = if (!isFolder) VideoFileNameParser.parse(fileName) else null
                                
                                if (isFolder || (parsed != null && parsed.isVideo)) {
                                    files.add(QuarkFile(fileFid, fileName, isFolder, parsed))
                                }
                            }
                        }
                        _uiState.update { it.copy(isLoading = false, fileList = files) }
                    } else {
                        _uiState.update { it.copy(isLoading = false, errorMsg = "获取失败 (Code: $code)") }
                    }
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
    val fileList: List<QuarkFile> = emptyList(),
    // ➕ 新增媒体库相关状态
    val isLibraryMode: Boolean = false,
    val selectedLibraryFids: Set<String> = emptySet(),
    val libraryVideos: List<QuarkFile> = emptyList()
)