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
    val category: String = "其他",
    val parsedInfo: ParsedVideoName? = null
)

data class MediaSeries(
    val id: String,
    val title: String,
    val originalParsedTitle: String,
    val year: Int?,
    val category: String,
    val posterUrl: String? = null,
    val overview: String? = null,
    val rating: String? = null,
    val videos: List<QuarkFile> = emptyList()
)

data class TmdbSearchResult(
    val id: Int,
    val title: String,
    val year: String,
    val posterUrl: String?,
    val overview: String,
    val rating: String?
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
    private var hasLoadedLibrary = false

    init {
        val savedLibs = videoSettingsRepository.libraryFids.split(",").filter { it.isNotBlank() }
        _uiState.update { 
            it.copy(
                cookie = videoSettingsRepository.quarkCookie,
                tmdbApiKey = videoSettingsRepository.tmdbApiKey,
                tmdbBaseUrl = videoSettingsRepository.tmdbBaseUrl,
                selectedLibraryFids = savedLibs.toSet(),
                isLibraryMode = true // 默认影视库
            ) 
        }
        fetchQuarkFiles("0")
        if (savedLibs.isNotEmpty()) loadLibraryVideos()
    }

    fun saveSettings(cookie: String, tmdbKey: String, baseUrl: String) {
        val cleanCookie = cookie.replace("\n", "").replace("\r", "").trim()
        val cleanBaseUrl = baseUrl.removeSuffix("/").trim()
        videoSettingsRepository.quarkCookie = cleanCookie
        videoSettingsRepository.tmdbApiKey = tmdbKey
        videoSettingsRepository.tmdbBaseUrl = cleanBaseUrl
        _uiState.update { it.copy(cookie = cleanCookie, tmdbApiKey = tmdbKey, tmdbBaseUrl = cleanBaseUrl) }
        fetchQuarkFiles("0")
        hasLoadedLibrary = false
        loadLibraryVideos()
    }

    fun toggleLibrary(fid: String) {
        val current = _uiState.value.selectedLibraryFids.toMutableSet()
        if (current.contains(fid)) current.remove(fid) else current.add(fid)
        videoSettingsRepository.libraryFids = current.joinToString(",")
        _uiState.update { it.copy(selectedLibraryFids = current) }
        hasLoadedLibrary = false
        if (_uiState.value.isLibraryMode) loadLibraryVideos()
    }

    fun setMode(isLibraryMode: Boolean) {
        _uiState.update { it.copy(isLibraryMode = isLibraryMode) }
        if (isLibraryMode && !hasLoadedLibrary) loadLibraryVideos()
    }
    
    fun selectSeries(series: MediaSeries?) {
        _uiState.update { it.copy(currentSeries = series) }
    }

    fun loadLibraryVideos() {
        val cookie = _uiState.value.cookie
        val apiKey = _uiState.value.tmdbApiKey
        val libs = _uiState.value.selectedLibraryFids
        
        if (libs.isEmpty()) {
            _uiState.update { it.copy(seriesList = emptyList(), errorMsg = "请先勾选文件夹", isLoading = false) }
            return
        }

        _uiState.update { it.copy(isLoading = true, errorMsg = null) }

        viewModelScope.launch {
            try {
                val allVideos = mutableListOf<QuarkFile>()
                withContext(Dispatchers.IO) {
                    val rootFiles = getRootFolderNames(cookie)
                    for (fid in libs) {
                        val categoryName = rootFiles[fid] ?: "影视库"
                        fetchVideosRecursively(cookie, fid, categoryName, null, allVideos)
                    }
                }
                
                val groupedSeries = aggregateToSeries(allVideos)
                _uiState.update { it.copy(seriesList = groupedSeries) }
                
                if (apiKey.isNotBlank()) {
                    scrapeSeriesPosters(groupedSeries, apiKey, _uiState.value.tmdbBaseUrl)
                } else {
                    hasLoadedLibrary = true
                    _uiState.update { it.copy(isLoading = false, errorMsg = "未配置 TMDB API Key") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMsg = "扫描异常: ${e.message}") }
            }
        }
    }
    
    private fun aggregateToSeries(videos: List<QuarkFile>): List<MediaSeries> {
        val seriesMap = mutableMapOf<String, MediaSeries>()
        for (v in videos) {
            val parsed = v.parsedInfo ?: continue
            val title = parsed.title
            if (title.isBlank()) continue
            
            val key = "${title}_${parsed.year ?: "0"}"
            
            if (seriesMap.containsKey(key)) {
                val exist = seriesMap[key]!!
                seriesMap[key] = exist.copy(videos = exist.videos + v)
            } else {
                seriesMap[key] = MediaSeries(
                    id = key,
                    title = title,
                    originalParsedTitle = title,
                    year = parsed.year,
                    category = v.category,
                    videos = listOf(v)
                )
            }
        }
        return seriesMap.values.toList()
    }

    private suspend fun getRootFolderNames(cookie: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        withContext(Dispatchers.IO) {
            val url = "https://drive-pc.quark.cn/1/clouddrive/file/sort?pr=ucpro&fr=pc&uc_param_str=&pdir_fid=0&_page=1&_size=100"
            val request = Request.Builder().url(url).header("Cookie", cookie).header("User-Agent", "Mozilla/5.0").build()
            try {
                client.newCall(request).execute().use { res ->
                    val body = res.body?.string()
                    if (!body.isNullOrBlank()) {
                        val list = JSONObject(body).optJSONObject("data")?.optJSONArray("list")
                        if (list != null) {
                            for (i in 0 until list.length()) {
                                val item = list.getJSONObject(i)
                                if (item.optBoolean("dir", false)) {
                                    map[item.optString("fid")] = item.optString("file_name")
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {}
        }
        return map
    }

    // 递归时传入当前文件夹名称，用于辅助解析
    private suspend fun fetchVideosRecursively(cookie: String, parentFid: String, category: String, folderName: String?, resultList: MutableList<QuarkFile>) {
        withContext(Dispatchers.IO) {
            val url = "https://drive-pc.quark.cn/1/clouddrive/file/sort?pr=ucpro&fr=pc&uc_param_str=&pdir_fid=$parentFid&_page=1&_size=100&_sort=file_type:asc,updated_at:desc"
            val request = Request.Builder().url(url).header("Cookie", cookie).header("User-Agent", "Mozilla/5.0").build()

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
                                        // 递归，把当前的文件夹名称传下去
                                        fetchVideosRecursively(cookie, fileFid, category, fileName, resultList)
                                    } else {
                                        // 关键点：把 folderName 传进去做拼接辅助
                                        val parsed = VideoFileNameParser.parse(fileName, folderName)
                                        if (parsed.isVideo) {
                                            val qf = QuarkFile(fileFid, fileName, false, category, parsed)
                                            resultList.add(qf)
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

    private fun scrapeSeriesPosters(seriesList: List<MediaSeries>, apiKey: String, baseUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val updatedList = seriesList.map { series ->
                val match = searchTmdb(series.originalParsedTitle, series.year, apiKey, baseUrl)
                if (match != null) {
                    series.copy(
                        title = match.title,
                        posterUrl = match.posterUrl,
                        overview = match.overview,
                        rating = match.rating
                    )
                } else {
                    series
                }
            }
            withContext(Dispatchers.Main) {
                hasLoadedLibrary = true
                _uiState.update { it.copy(isLoading = false, seriesList = updatedList) }
            }
        }
    }

    private fun searchTmdb(title: String, year: Int?, apiKey: String, baseUrl: String): TmdbSearchResult? {
        val languages = listOf("zh-CN", "en-US")
        for (lang in languages) {
            try {
                val query = URLEncoder.encode(title, "UTF-8")
                val url = "$baseUrl/3/search/multi?api_key=$apiKey&query=$query&language=$lang"
                val request = Request.Builder().url(url).build()
                
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    if (!body.isNullOrBlank()) {
                        val json = JSONObject(body)
                        val results = json.optJSONArray("results")
                        if (results != null && results.length() > 0) {
                            var bestItem: JSONObject? = null
                            var topScore = -1.0
                            
                            for (i in 0 until results.length()) {
                                val item = results.getJSONObject(i)
                                val officialTitle = item.optString("title").ifBlank { item.optString("name") }
                                val releaseYear = item.optString("release_date").take(4).toIntOrNull() ?: item.optString("first_air_date").take(4).toIntOrNull()
                                
                                var score = 0.0
                                if (officialTitle.contains(title) || title.contains(officialTitle)) score += 5.0
                                if (year != null && releaseYear == year) score += 5.0
                                
                                if (score > topScore && score > 0) {
                                    topScore = score
                                    bestItem = item
                                }
                            }
                            if (bestItem == null) bestItem = results.getJSONObject(0)

                            val posterPath = bestItem.optString("poster_path")
                            val officialName = bestItem.optString("title").ifBlank { bestItem.optString("name") }
                            val voteAverage = bestItem.optDouble("vote_average", 0.0)
                            val ratingStr = if (voteAverage > 0) String.format("%.1f", voteAverage) else null
                            
                            return TmdbSearchResult(
                                id = bestItem.optInt("id"),
                                title = officialName,
                                year = (bestItem.optString("release_date").take(4).ifBlank { bestItem.optString("first_air_date").take(4) }),
                                posterUrl = if (posterPath.isNotBlank() && posterPath != "null") "https://image.tmdb.org/t/p/w500$posterPath" else null,
                                overview = bestItem.optString("overview"),
                                rating = ratingStr
                            )
                        }
                    }
                }
            } catch (e: Exception) { }
        }
        return null
    }

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
                                val voteAverage = item.optDouble("vote_average", 0.0)
                                list.add(
                                    TmdbSearchResult(
                                        id = item.optInt("id"),
                                        title = title,
                                        year = item.optString("release_date").take(4).ifBlank { item.optString("first_air_date").take(4) },
                                        posterUrl = if (posterPath.isNotBlank() && posterPath != "null") "https://image.tmdb.org/t/p/w300$posterPath" else null,
                                        overview = item.optString("overview"),
                                        rating = if (voteAverage > 0) String.format("%.1f", voteAverage) else null
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

    fun applyManualScrape(seriesId: String, result: TmdbSearchResult, newCategory: String? = null) {
        val currentList = _uiState.value.seriesList.map { 
            if (it.id == seriesId) {
                it.copy(
                    title = result.title, 
                    posterUrl = result.posterUrl, 
                    overview = result.overview, 
                    rating = result.rating,
                    category = newCategory ?: it.category 
                )
            } else it
        }
        _uiState.update { 
            it.copy(
                seriesList = currentList,
                currentSeries = currentList.find { s -> s.id == seriesId }
            ) 
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
        _uiState.update { it.copy(currentFid = fid, errorMsg = null) }

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val url = "https://drive-pc.quark.cn/1/clouddrive/file/sort?pr=ucpro&fr=pc&uc_param_str=&pdir_fid=$fid&_page=1&_size=100&_sort=file_type:asc,updated_at:desc"
                    val request = Request.Builder().url(url).header("Cookie", cookie).header("User-Agent", "Mozilla/5.0").build()
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
                                    files.add(QuarkFile(fileFid, fileName, isFolder, "", parsed))
                                }
                            }
                        }
                        _uiState.update { it.copy(fileList = files) }
                    } else {
                        _uiState.update { it.copy(errorMsg = "获取失败") }
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMsg = "请求异常") }
            }
        }
    }

    fun goBack() {
        if (pathHistory.isNotEmpty()) fetchQuarkFiles(pathHistory.removeAt(pathHistory.size - 1), isBack = true)
        else fetchQuarkFiles("0")
    }
}

data class VideoUiState(
    val cookie: String = "",
    val tmdbApiKey: String = "",
    val tmdbBaseUrl: String = "https://api.tmdb.org",
    val currentFid: String = "0",
    val isLoading: Boolean = false,
    val errorMsg: String? = null,
    val fileList: List<QuarkFile> = emptyList(),
    val isLibraryMode: Boolean = true,
    val selectedLibraryFids: Set<String> = emptySet(),
    val seriesList: List<MediaSeries> = emptyList(),
    val currentSeries: MediaSeries? = null
)