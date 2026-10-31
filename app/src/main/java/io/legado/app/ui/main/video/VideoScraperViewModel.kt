package io.legado.app.ui.main.video

import android.content.Context
import android.widget.Toast
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class QuarkFile(
    val fid: String,
    val name: String,
    val isFolder: Boolean,
    val category: String = "其他",
    val folderPath: List<String> = emptyList(), // 只包含父级文件夹路径
    val parsedInfo: ParsedVideoName? = null,
    val size: Long = 0L
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
    val videos: List<QuarkFile> = emptyList(),
    val episodeTitles: Map<Int, String> = emptyMap()
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
                isLibraryMode = true 
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
                        // 取根目录名字，比如"电影"、"动漫"，作为分类
                        val categoryName = rootFiles[fid] ?: "影视库"
                        fetchVideosRecursively(cookie, fid, categoryName, listOf(categoryName), allVideos)
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
    
    // ➕ 严格按照你的规则重写归类：父级优先，季级追溯
    private fun aggregateToSeries(videos: List<QuarkFile>): List<MediaSeries> {
        val seriesMap = mutableMapOf<String, MediaSeries>()
        
        // 忽略的顶级根目录名
        val ignoreFolders = setOf("电影", "动漫", "电视剧", "纪录片", "综艺", "影视库", "其他", "未分类")

        for (v in videos) {
            val parsed = v.parsedInfo ?: continue
            
            var baseTitle = ""
            
            if (v.folderPath.size >= 2) {
                val parentFolder = v.folderPath.last() // 直接父目录
                val grandParentFolder = v.folderPath[v.folderPath.size - 2] // 爷爷目录

                if (VideoFileNameParser.isSeasonFolder(parentFolder)) {
                    // 如果父目录是“第1季”，往上找一级作为剧名
                    baseTitle = grandParentFolder
                } else {
                    // 如果父目录不是季，直接用它当剧名
                    baseTitle = parentFolder
                }
            } else if (v.folderPath.isNotEmpty()) {
                // 如果只有一级目录（直接放在“动漫”下面），说明是散件，用文件名解析出来的标题
                baseTitle = v.folderPath.last()
                if (ignoreFolders.contains(baseTitle)) {
                    baseTitle = parsed.title
                }
            } else {
                baseTitle = parsed.title
            }
            
            // 极端兜底：如果剧名是无效的根目录名，或者为空，抛弃它
            if (baseTitle.isBlank() || ignoreFolders.contains(baseTitle)) {
                baseTitle = parsed.title
                if (baseTitle.isBlank() || baseTitle == "未知影视") continue
            }

            val key = baseTitle
            
            if (seriesMap.containsKey(key)) {
                val exist = seriesMap[key]!!
                val combinedVideos = (exist.videos + v).sortedBy { it.parsedInfo?.episode ?: 0 }
                seriesMap[key] = exist.copy(videos = combinedVideos)
            } else {
                seriesMap[key] = MediaSeries(
                    id = key,
                    title = baseTitle,
                    originalParsedTitle = baseTitle,
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

    private suspend fun fetchVideosRecursively(cookie: String, parentFid: String, category: String, currentPath: List<String>, resultList: MutableList<QuarkFile>) {
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
                                    val size = item.optLong("size", 0L)
                                    
                                    if (isFolder) {
                                        // 递归时路径增加当前文件夹
                                        fetchVideosRecursively(cookie, fileFid, category, currentPath + fileName, resultList)
                                    } else {
                                        val parentFolder = currentPath.lastOrNull()
                                        val parsed = VideoFileNameParser.parse(fileName, parentFolder)
                                        if (parsed.isVideo) {
                                            // 记录当前路径（不包括文件本身）
                                            val qf = QuarkFile(fileFid, fileName, false, category, currentPath, parsed, size)
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
                    var epTitles: Map<Int, String> = emptyMap()
                    val season = series.videos.firstOrNull()?.parsedInfo?.season ?: 1
                    if (series.videos.size > 1 || series.originalParsedTitle.contains("季")) {
                        epTitles = fetchEpisodeTitles(match.id, season, apiKey, baseUrl)
                    }

                    series.copy(
                        title = match.title,
                        posterUrl = match.posterUrl,
                        overview = match.overview,
                        rating = match.rating,
                        episodeTitles = epTitles
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

    private fun fetchEpisodeTitles(tvId: Int, season: Int, apiKey: String, baseUrl: String): Map<Int, String> {
        val map = mutableMapOf<Int, String>()
        try {
            val url = "$baseUrl/3/tv/$tvId/season/$season?api_key=$apiKey&language=zh-CN"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (!body.isNullOrBlank()) {
                    val json = JSONObject(body)
                    val episodes = json.optJSONArray("episodes")
                    if (episodes != null) {
                        for (i in 0 until episodes.length()) {
                            val ep = episodes.getJSONObject(i)
                            val epNum = ep.optInt("episode_number")
                            val epName = ep.optString("name")
                            if (epNum > 0 && epName.isNotBlank()) {
                                map[epNum] = epName
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) { }
        return map
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

    fun applyManualScrape(seriesId: String, result: TmdbSearchResult?, newCategory: String, newTitle: String) {
        val currentList = _uiState.value.seriesList.map { 
            if (it.id == seriesId) {
                it.copy(
                    title = result?.title ?: newTitle,
                    posterUrl = result?.posterUrl ?: it.posterUrl, 
                    overview = result?.overview ?: it.overview, 
                    rating = result?.rating ?: it.rating,
                    category = newCategory
                )
            } else it
        }
        _uiState.update { 
            val updatedCurrent = currentList.find { s -> s.id == seriesId }
            it.copy(
                seriesList = currentList,
                currentSeries = updatedCurrent ?: it.currentSeries
            ) 
        }
    }

    fun playVideo(context: Context, file: QuarkFile) {
        val cookie = _uiState.value.cookie
        _uiState.update { it.copy(playUrl = null, playTitle = file.name) }
        Toast.makeText(context, "正在获取播放直链...", Toast.LENGTH_SHORT).show()
        
        viewModelScope.launch {
            val url = withContext(Dispatchers.IO) {
                try {
                    val apiUrl = "https://drive.quark.cn/1/clouddrive/file/download?pr=ucpro&fr=pc"
                    val jsonBody = JSONObject().apply {
                        put("fids", JSONArray().put(file.fid))
                    }
                    val body = jsonBody.toString().toRequestBody("application/json".toMediaType())
                    
                    val request = Request.Builder()
                        .url(apiUrl)
                        .post(body)
                        .header("Cookie", cookie)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch")
                        .header("Referer", "https://pan.quark.cn/")
                        .header("Accept", "application/json, text/plain, */*")
                        .build()
                        
                    client.newCall(request).execute().use { response ->
                        val resBody = response.body?.string()
                        if (!resBody.isNullOrBlank()) {
                            val json = JSONObject(resBody)
                            if (json.optInt("code", -1) == 0) {
                                val data = json.optJSONArray("data")
                                if (data != null && data.length() > 0) {
                                    return@withContext data.getJSONObject(0).optString("download_url")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                null
            }
            
            if (url != null) {
                _uiState.update { it.copy(playUrl = url) }
            } else {
                Toast.makeText(context, "获取直链失败，请检查 Cookie", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun stopPlaying() {
        _uiState.update { it.copy(playUrl = null, playTitle = null) }
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
                                    files.add(QuarkFile(fileFid, fileName, isFolder, "", emptyList(), parsed))
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
    val currentSeries: MediaSeries? = null,
    val playUrl: String? = null,
    val playTitle: String? = null
)