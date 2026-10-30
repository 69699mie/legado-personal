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
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class QuarkFile(
    val fid: String,
    val name: String,
    val isFolder: Boolean,
    val parsedInfo: ParsedVideoName? = null,
    val posterUrl: String? = null
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
        fetchQuarkFiles("0")
    }

    fun saveSettings(cookie: String, tmdbKey: String) {
        val cleanCookie = cookie.replace("\n", "").replace("\r", "").trim()
        videoSettingsRepository.quarkCookie = cleanCookie
        videoSettingsRepository.tmdbApiKey = tmdbKey
        _uiState.update { it.copy(cookie = cleanCookie, tmdbApiKey = tmdbKey) }
        fetchQuarkFiles("0")
    }

    fun toggleLibrary(fid: String) {
        val current = _uiState.value.selectedLibraryFids.toMutableSet()
        if (current.contains(fid)) current.remove(fid) else current.add(fid)
        
        videoSettingsRepository.libraryFids = current.joinToString(",")
        _uiState.update { it.copy(selectedLibraryFids = current) }
        
        if (_uiState.value.isLibraryMode) {
            loadLibraryVideos()
        }
    }

    fun setMode(isLibraryMode: Boolean) {
        _uiState.update { it.copy(isLibraryMode = isLibraryMode) }
        if (isLibraryMode) {
            loadLibraryVideos()
        }
    }

    fun loadLibraryVideos() {
        val cookie = _uiState.value.cookie
        val apiKey = _uiState.value.tmdbApiKey
        val libs = _uiState.value.selectedLibraryFids
        
        if (libs.isEmpty()) {
            _uiState.update { it.copy(libraryVideos = emptyList(), errorMsg = "请先勾选文件夹") }
            return
        }

        _uiState.update { it.copy(isLoading = true, errorMsg = null, libraryVideos = emptyList()) }

        viewModelScope.launch {
            try {
                val allVideos = mutableListOf<QuarkFile>()
                withContext(Dispatchers.IO) {
                    for (fid in libs) {
                        // 递归遍历，有发现立刻更新 UI
                        fetchVideosRecursively(cookie, fid, allVideos)
                    }
                }
                
                _uiState.update { it.copy(libraryVideos = allVideos) }
                
                if (apiKey.isNotBlank()) {
                    scrapePosters(allVideos, apiKey)
                } else {
                    _uiState.update { it.copy(isLoading = false, errorMsg = "未配置 TMDB API Key，无法获取海报") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMsg = "扫描异常: ${e.message}") }
            }
        }
    }

    private suspend fun fetchVideosRecursively(cookie: String, parentFid: String, resultList: MutableList<QuarkFile>) {
        withContext(Dispatchers.IO) {
            val url = "https://drive-pc.quark.cn/1/clouddrive/file/sort?pr=ucpro&fr=pc&uc_param_str=&pdir_fid=$parentFid&_page=1&_size=100&_sort=file_type:asc,updated_at:desc"
            val request = Request.Builder()
                .url(url)
                .header("Cookie", cookie)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Referer", "https://pan.quark.cn/")
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    if (!body.isNullOrBlank()) {
                        val json = JSONObject(body)
                        if (json.optInt("code", -1) == 0) {
                            val list = json.optJSONObject("data")?.optJSONArray("list")
                            if (list != null) {
                                for (i in 0 until list.length()) {
                                    val item = list.getJSONObject(i)
                                    val fileName = item.optString("file_name")
                                    val fileFid = item.optString("fid")
                                    val isFolder = item.optBoolean("dir", false)
                                    
                                    if (isFolder) {
                                        // 如果文件夹太深，可以限制层数，这里暂不限制
                                        fetchVideosRecursively(cookie, fileFid, resultList)
                                    } else {
                                        val parsed = VideoFileNameParser.parse(fileName)
                                        if (parsed.isVideo) {
                                            val qf = QuarkFile(fileFid, fileName, false, parsed)
                                            resultList.add(qf)
                                            // 实时推送到 UI，让用户看到进度
                                            withContext(Dispatchers.Main) {
                                                _uiState.update { it.copy(libraryVideos = resultList.toList()) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun scrapePosters(videos: List<QuarkFile>, apiKey: String) {
        viewModelScope.launch(Dispatchers.IO) {
            var failedCount = 0
            val updatedList = videos.map { video ->
                val title = video.parsedInfo?.title ?: return@map video
                try {
                    val query = URLEncoder.encode(title, "UTF-8")
                    val url = "https://api.themoviedb.org/3/search/multi?api_key=$apiKey&query=$query&language=zh-CN"
                    val request = Request.Builder().url(url).build()
                    
                    client.newCall(request).execute().use { response ->
                        val body = response.body?.string()
                        if (!body.isNullOrBlank()) {
                            val json = JSONObject(body)
                            // 如果 TMDB 返回了错误状态码
                            if (json.has("status_code")) {
                                failedCount++
                            } else {
                                val results = json.optJSONArray("results")
                                if (results != null && results.length() > 0) {
                                    val first = results.getJSONObject(0)
                                    val posterPath = first.optString("poster_path")
                                    if (posterPath != null && posterPath != "null" && posterPath != "") {
                                        val posterUrl = "https://image.tmdb.org/t/p/w300$posterPath"
                                        return@map video.copy(posterUrl = posterUrl)
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    failedCount++
                }
                video
            }
            
            withContext(Dispatchers.Main) {
                val error = if (failedCount > 0) "有 $failedCount 个视频 TMDB 请求失败（请检查网络/API Key）" else null
                _uiState.update { it.copy(isLoading = false, libraryVideos = updatedList, errorMsg = error) }
            }
        }
    }

    fun fetchQuarkFiles(fid: String = "0", isBack: Boolean = false) {
        val cookie = _uiState.value.cookie
        if (cookie.isBlank()) {
            _uiState.update { it.copy(errorMsg = "请先设置 Cookie") }
            return
        }

        if (fid == "0") pathHistory.clear()
        else if (!isBack && _uiState.value.currentFid != fid) pathHistory.add(_uiState.value.currentFid)

        _uiState.update { it.copy(currentFid = fid, isLoading = true, errorMsg = null, fileList = emptyList()) }

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

                    client.newCall(request).execute().use { response ->
                        Pair(response.isSuccessful, response.body?.string())
                    }
                }

                val (isSuccessful, body) = result
                if (isSuccessful && !body.isNullOrBlank()) {
                    val json = JSONObject(body)
                    if (json.optInt("code", -1) == 0) {
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
                        _uiState.update { it.copy(isLoading = false, errorMsg = "获取失败 (Code: ${json.optInt("code")})") }
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
    val isLibraryMode: Boolean = false,
    val selectedLibraryFids: Set<String> = emptySet(),
    val libraryVideos: List<QuarkFile> = emptyList()
)