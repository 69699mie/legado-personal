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
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class QuarkFile(
    val fid: String,
    val name: String,
    val isFolder: Boolean,
    val category: String = "未分类",
    val folderPath: List<String> = emptyList(),
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
    val rating: String?,
    val mediaType: String = "tv",
    val genreIds: List<Int> = emptyList()
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

    // 【核心修复】用于记录勾选的文件夹 fid 对应的文件夹名字
    private val libraryNames = mutableMapOf<String, String>()

    init {
        val savedLibs = videoSettingsRepository.libraryFids.split(",").filter { it.isNotBlank() }
        val fids = mutableSetOf<String>()
        
        savedLibs.forEach { item ->
            val parts = item.split(":")
            if (parts.isNotEmpty()) {
                val fid = parts[0]
                fids.add(fid)
                if (parts.size > 1) {
                    libraryNames[fid] = parts[1] // 恢复名字
                }
            }
        }

        _uiState.update {
            it.copy(
                cookie = videoSettingsRepository.quarkCookie,
                tmdbApiKey = videoSettingsRepository.tmdbApiKey,
                tmdbBaseUrl = videoSettingsRepository.tmdbBaseUrl,
                selectedLibraryFids = fids,
                isLibraryMode = true
            )
        }
        fetchQuarkFiles("0")
        if (fids.isNotEmpty()) loadLibraryVideos()
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

    // 【核心修复】接收 file.name 并保存
    fun toggleLibrary(fid: String, name: String) {
        val current = _uiState.value.selectedLibraryFids.toMutableSet()
        if (current.contains(fid)) {
            current.remove(fid)
            libraryNames.remove(fid)
        } else {
            current.add(fid)
            libraryNames[fid] = name
        }
        
        // 保存为 fid:name 格式
        val saveString = current.joinToString(",") { f -> "$f:${libraryNames[f] ?: ""}" }
        videoSettingsRepository.libraryFids = saveString
        
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
                    for (fid in libs) {
                        // 【核心修复】从 map 里拿到对应的文件夹名字，作为初始路径
                        val folderName = libraryNames[fid] ?: ""
                        val initialPath = if (folderName.isNotBlank()) listOf(folderName) else emptyList()
                        fetchVideosRecursively(cookie, fid, initialPath, allVideos)
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
        
        val categoryMap = mapOf(
            "电视剧" to "剧集", "剧集" to "剧集",
            "动漫" to "动漫", "动画" to "动漫",
            "电影" to "电影", "纪录片" to "纪录片", "综艺" to "综艺"
        )
        val excludeFolders = categoryMap.keys + setOf("未观看", "已观看", "其他", "未分类")

        for (v in videos) {
            val parsed = v.parsedInfo ?: continue
            
            // 1. 判定分类（路径绝对优先）
            var finalCategory = "未分类"
            for (pathNode in v.folderPath) {
                if (categoryMap.containsKey(pathNode)) {
                    finalCategory = categoryMap[pathNode]!!
                    break 
                }
            }

            // 2. 提取有效路径，用于确定剧名
            val meaningfulPaths = v.folderPath.filter { !excludeFolders.contains(it) }
            var baseTitle = ""
            
            if (meaningfulPaths.isNotEmpty()) {
                if (VideoFileNameParser.isSeasonFolder(meaningfulPaths.last())) {
                    baseTitle = if (meaningfulPaths.size >= 2) meaningfulPaths[meaningfulPaths.size - 2] else meaningfulPaths.last()
                } else {
                    baseTitle = meaningfulPaths.last()
                }
            } else {
                baseTitle = parsed.title
            }

            if (baseTitle.isBlank() || baseTitle == "未知影视") {
                baseTitle = parsed.title
            }

            // 3. 强行合并
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
                    category = finalCategory, 
                    videos = listOf(v)
                )
            }
        }
        return seriesMap.values.toList()
    }

    private suspend fun fetchVideosRecursively(
        cookie: String,
        parentFid: String,
        currentPath: List<String>,
        resultList: MutableList<QuarkFile>
    ) {
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
                                        fetchVideosRecursively(cookie, fileFid, currentPath + fileName, resultList)
                                    } else {
                                        val parentFolder = currentPath.lastOrNull()
                                        val parsed = VideoFileNameParser.parse(fileName, parentFolder)
                                        if (parsed.isVideo) {
                                            // 这里的分类填“未分类”，真正的分类在 aggregateToSeries 里算
                                            val qf = QuarkFile(fileFid, fileName, false, "未分类", currentPath, parsed, size)
                                            resultList.add(qf)
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

    private fun scrapeSeriesPosters(seriesList: List<MediaSeries>, apiKey: String, baseUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val updatedList = seriesList.map { series ->
                val match = searchTmdbWithFallback(series.originalParsedTitle, series.year, apiKey, baseUrl)
                if (match != null) {
                    var finalTitle = match.title
                    if (finalTitle.matches(Regex("^[a-zA-Z0-9\\s\\p{Punct}]+$")) && series.originalParsedTitle.any { it.code > 127 }) {
                        finalTitle = series.originalParsedTitle
                    }

                    var finalCategory = series.category
                    if (finalCategory == "未分类") {
                        finalCategory = when {
                            match.mediaType == "movie" -> "电影"
                            match.genreIds.contains(16) -> "动漫"
                            match.mediaType == "tv" -> "剧集"
                            else -> "未分类"
                        }
                    }

                    var epTitles: Map<Int, String> = emptyMap()
                    val season = series.videos.firstOrNull()?.parsedInfo?.season ?: 1
                    if (series.videos.size > 1 || series.originalParsedTitle.contains("季")) {
                        epTitles = fetchEpisodeTitles(match.id, season, apiKey, baseUrl)
                    }
                    series.copy(
                        title = finalTitle,
                        category = finalCategory,
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

    private fun searchTmdbWithFallback(title: String, year: Int?, apiKey: String, baseUrl: String): TmdbSearchResult? {
        val languages = listOf("zh-CN", "zh-SG", "en-US")
        for (lang in languages) {
            val result = searchTmdb(title, year, apiKey, baseUrl, lang)
            if (result != null) return result
        }
        return null
    }

    private fun searchTmdb(title: String, year: Int?, apiKey: String, baseUrl: String, language: String): TmdbSearchResult? {
        try {
            val query = URLEncoder.encode(title, "UTF-8")
            val url = "$baseUrl/3/search/multi?api_key=$apiKey&query=$query&language=$language"
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
                        val mediaType = bestItem.optString("media_type", "tv")

                        val genreIdsList = mutableListOf<Int>()
                        val genreIdsArray = bestItem.optJSONArray("genre_ids")
                        if (genreIdsArray != null) {
                            for (j in 0 until genreIdsArray.length()) {
                                genreIdsList.add(genreIdsArray.getInt(j))
                            }
                        }

                        return TmdbSearchResult(
                            id = bestItem.optInt("id"),
                            title = officialName,
                            year = (bestItem.optString("release_date").take(4).ifBlank { bestItem.optString("first_air_date").take(4) }),
                            posterUrl = if (posterPath.isNotBlank() && posterPath != "null") "https://image.tmdb.org/t/p/w500$posterPath" else null,
                            overview = bestItem.optString("overview"),
                            rating = ratingStr,
                            mediaType = mediaType,
                            genreIds = genreIdsList
                        )
                    }
                }
            }
        } catch (e: Exception) {}
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
        } catch (e: Exception) {}
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
            withContext(Dispatchers.Main) {
                onResult(list)
            }
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
            it.copy(seriesList = currentList, currentSeries = updatedCurrent ?: it.currentSeries)
        }
    }

    fun playVideo(context: Context, file: QuarkFile) {
        val cookie = _uiState.value.cookie
        _uiState.update { it.copy(playUrl = null, playTitle = file.name) }
        Toast.makeText(context, "正在获取播放直链...", Toast.LENGTH_SHORT).show()

        viewModelScope.launch {
            val url = withContext(Dispatchers.IO) {
                try {
                    val apiUrl = "https://drive.quark.cn/1/clouddrive/file/v2/play?pr=ucpro&fr=pc"
                    val jsonBody = JSONObject().apply {
                        put("fid", file.fid)
                        put("resolutions", "normal,low,high,super,2k,4k")
                        put("supports", "fmp4,m3u8")
                    }
                    val body = jsonBody.toString().toRequestBody("application/json".toMediaType())
                    val request = Request.Builder()
                        .url(apiUrl)
                        .post(body)
                        .header("Cookie", cookie)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch")
                        .header("Referer", "https://drive.quark.cn/") 
                        .header("Accept", "application/json, text/plain, */*")
                        .build()

                    client.newCall(request).execute().use { response ->
                        val resBody = response.body?.string()
                        if (!resBody.isNullOrBlank()) {
                            val json = JSONObject(resBody)
                            if (json.optInt("code", -1) == 0) {
                                val videoList = json.optJSONObject("data")?.optJSONArray("video_list")
                                if (videoList != null && videoList.length() > 0) {
                                    var bestUrl: String? = null
                                    for (i in 0 until videoList.length()) {
                                        val video = videoList.getJSONObject(i)
                                        if (video.optBoolean("accessable", false)) {
                                            val videoInfo = video.optJSONObject("video_info")
                                            val videoUrl = videoInfo?.optString("url")
                                            if (!videoUrl.isNullOrBlank()) {
                                                bestUrl = videoUrl
                                                break
                                            }
                                        }
                                    }
                                    if (bestUrl != null) return@withContext bestUrl
                                }
                            } else {
                                val msg = json.optString("message")
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "接口报错: $msg", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(context, "获取播放流失败，请检查Cookie或文件状态", Toast.LENGTH_LONG).show()
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
        if (fid == "0") pathHistory.clear() else if (!isBack && _uiState.value.currentFid != fid) pathHistory.add(_uiState.value.currentFid)
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
        if (pathHistory.isNotEmpty()) fetchQuarkFiles(pathHistory.removeAt(pathHistory.size - 1), isBack = true) else fetchQuarkFiles("0")
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