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
    val name: String, // 原始名字
    val isFolder: Boolean,
    val parsedInfo: ParsedVideoName? = null,
    val posterUrl: String? = null,
    val tmdbTitle: String? = null, // ➕ TMDB 官方名字
    val overview: String? = null   // ➕ TMDB 简介
)

// 供手动刮削选择的搜索结果
data class TmdbSearchResult(
    val id: Int,
    val title: String,
    val year: String,
    val posterUrl: String?,
    val overview: String
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
                tmdbBaseUrl = videoSettingsRepository.tmdbBaseUrl,
                selectedLibraryFids = savedLibs.toSet()
            ) 
        }
        fetchQuarkFiles("0")
    }

    fun saveSettings(cookie: String, tmdbKey: String, baseUrl: String) {
        val cleanCookie = cookie.replace("\n", "").replace("\r", "").trim()
        val cleanBaseUrl = baseUrl.removeSuffix("/").trim()
        videoSettingsRepository.quarkCookie = cleanCookie
        videoSettingsRepository.tmdbApiKey = tmdbKey
        videoSettingsRepository.tmdbBaseUrl = cleanBaseUrl
        _uiState.update { it.copy(cookie = cleanCookie, tmdbApiKey = tmdbKey, tmdbBaseUrl = cleanBaseUrl) }
        fetchQuarkFiles("0")
    }

    fun toggleLibrary(fid: String) {
        val current = _uiState.value.selectedLibraryFids.toMutableSet()
        if (current.contains(fid)) current.remove(fid) else current.add(fid)
        videoSettingsRepository.libraryFids = current.joinToString(",")
        _uiState.update { it.copy(selectedLibraryFids = current) }
        if (_uiState.value.isLibraryMode) loadLibraryVideos()
    }

    fun setMode(isLibraryMode: Boolean) {
        _uiState.update { it.copy(isLibraryMode = isLibraryMode) }
        if (isLibraryMode) loadLibraryVideos()
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
                        fetchVideosRecursively(cookie, fid, allVideos)
                    }
                }
                
                _uiState.update { it.copy(libraryVideos = allVideos) }
                
                if (apiKey.isNotBlank()) {
                    scrapePosters(allVideos, apiKey, _uiState.value.tmdbBaseUrl)
                } else {
                    _uiState.update { it.copy(isLoading = false, errorMsg = "未配置 TMDB API Key") }
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
                                        fetchVideosRecursively(cookie, fileFid, resultList)
                                    } else {
                                        val parsed = VideoFileNameParser.parse(fileName)
                                        if (parsed.isVideo) {
                                            val qf = QuarkFile(fileFid, fileName, false, parsed)
                                            resultList.add(qf)
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
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    // ➕ 核心改进：使用代理、多语言降级、官方名称替换
    private fun scrapePosters(videos: List<QuarkFile>, apiKey: String, baseUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val updatedList = videos.map { video ->
                val title = video.parsedInfo?.title ?: return@map video
                val year = video.parsedInfo.year
                
                val match = searchTmdb(title, year, apiKey, baseUrl)
                if (match != null) {
                    video.copy(
                        posterUrl = match.posterUrl,
                        tmdbTitle = match.title, // 官方名称
                        overview = match.overview
                    )
                } else {
                    video
                }
            }
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(isLoading = false, libraryVideos = updatedList) }
            }
        }
    }

    private fun searchTmdb(title: String, year: Int?, apiKey: String, baseUrl: String): TmdbSearchResult? {
        val languages = listOf("zh-CN", "en-US")
        for (lang in languages) {
            try {
                val query = URLEncoder.encode(title, "UTF-8")
                // 使用用户配置的代理地址
                val url = "$baseUrl/3/search/multi?api_key=$apiKey&query=$query&language=$lang"
                val request = Request.Builder().url(url).build()
                
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    if (!body.isNullOrBlank()) {
                        val json = JSONObject(body)
                        val results = json.optJSONArray("results")
                        if (results != null && results.length() > 0) {
                            // 简单评分机制：优先匹配年份，优先包含原名
                            var bestItem: JSONObject? = null
                            var topScore = -1.0
                            
                            for (i in 0 until results.length()) {
                                val item = results.getJSONObject(i)
                                val officialTitle = item.optString("title").ifBlank { item.optString("name") }
                                val releaseYear = item.optString("release_date").take(4).toIntOrNull() 
                                                 ?: item.optString("first_air_date").take(4).toIntOrNull()
                                
                                var score = 0.0
                                if (officialTitle.contains(title) || title.contains(officialTitle)) score += 5.0
                                if (year != null && releaseYear == year) score += 5.0
                                
                                if (score > topScore && score > 0) {
                                    topScore = score
                                    bestItem = item
                                }
                            }
                            
                            // 实在没有高分，取第一个
                            if (bestItem == null) bestItem = results.getJSONObject(0)

                            val posterPath = bestItem.optString("poster_path")
                            val officialName = bestItem.optString("title").ifBlank { bestItem.optString("name") }
                            
                            return TmdbSearchResult(
                                id = bestItem.optInt("id"),
                                title = officialName,
                                year = (bestItem.optString("release_date").take(4).ifBlank { bestItem.optString("first_air_date").take(4) }),
                                posterUrl = if (posterPath.isNotBlank() && posterPath != "null") "https://image.tmdb.org/t/p/w300$posterPath" else null,
                                overview = bestItem.optString("overview")
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                // 网络错误，尝试下一个语言或退出
            }
        }
        return null
    }

    // ➕ 手动重新刮削：搜索候选列表
    fun searchTmdbManually(query: String, onResult: (List<TmdbSearchResult>) -> Unit) {
        val apiKey = _uiState.value.tmdbApiKey
        val baseUrl = _uiState.value.tmdbBaseUrl
        if (apiKey.isBlank()) return

        viewModelScope.launch(Dispatchers.IO) {
            val list = mutableListOf<TmdbSearchResult>()
            try {
                val q = URLEncoder.encode(query, "UTF-8")
                val url = "$baseUrl/3/search/multi?api_key=$apiKey&query=$q&language=zh-CN"
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    if (!body.isNullOrBlank()) {
                        val json = JSONObject(body)
                        val results = json.optJSONArray("results")
                        if (results != null) {
                            for (i in 0 until results.length()) {
                                val item = results.getJSONObject(i)
                                val title = item.optString("title").ifBlank { item.optString("name") }
                                val posterPath = item.optString("poster_path")
                                list.add(
                                    TmdbSearchResult(
                                        id = item.optInt("id"),
                                        title = title,
                                        year = item.optString("release_date").take(4).ifBlank { item.optString("first_air_date").take(4) },
                                        posterUrl = if (posterPath.isNotBlank() && posterPath != "null") "https://image.tmdb.org/t/p/w300$posterPath" else null,
                                        overview = item.optString("overview")
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {}
            
            withContext(Dispatchers.Main) { onResult(list) }
        }
    }

    // ➕ 应用手动选定的刮削结果
    fun applyManualScrape(videoFid: String, result: TmdbSearchResult) {
        val currentList = _uiState.value.libraryVideos.map { 
            if (it.fid == videoFid) {
                it.copy(posterUrl = result.posterUrl, tmdbTitle = result.title, overview = result.overview)
            } else it
        }
        _uiState.update { it.copy(libraryVideos = currentList) }
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
                        val files = mutableListOf<QuarkFile>()
                        val list = json.optJSONObject("data")?.optJSONArray("list")
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
            fetchQuarkFiles(pathHistory.removeAt(pathHistory.size - 1), isBack = true)
        } else {
            fetchQuarkFiles("0")
        }
    }
}

data class VideoUiState(
    val cookie: String = "",
    val tmdbApiKey: String = "",
    val tmdbBaseUrl: String = "https://api.tmdb.org", // ➕
    val currentFid: String = "0",
    val isLoading: Boolean = false,
    val errorMsg: String? = null,
    val fileList: List<QuarkFile> = emptyList(),
    val isLibraryMode: Boolean = false,
    val selectedLibraryFids: Set<String> = emptySet(),
    val libraryVideos: List<QuarkFile> = emptyList()
)