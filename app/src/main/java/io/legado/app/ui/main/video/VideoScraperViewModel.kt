package io.legado.app.ui.main.video

import android.app.AlertDialog
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.VideoSettingsRepository
import io.legado.app.utils.ParsedVideoName
import io.legado.app.utils.VideoFileNameParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.text.Collator
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
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

data class SeasonDetail(
    val posterUrl: String? = null,
    val overview: String? = null
)

data class MediaSeries(
    val id: String,
    val title: String,
    val originalParsedTitle: String,
    val year: Int?,
    val category: String,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val overview: String? = null,
    val rating: String? = null,
    val videos: List<QuarkFile> = emptyList(),
    val seasonDetails: Map<Int, SeasonDetail> = emptyMap(),
    val episodeTitles: Map<Int, String> = emptyMap(),
    val episodeStills: Map<Int, String> = emptyMap(),
    val seasonEpisodeTitles: Map<String, String> = emptyMap(),
    val seasonEpisodeStills: Map<String, String> = emptyMap(),
    val groupName: String? = null,
    val isManuallyEdited: Boolean = false 
)

data class TmdbSearchResult(
    val id: Int,
    val title: String,
    val year: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val overview: String,
    val rating: String?,
    val mediaType: String = "tv",
    val genreIds: List<Int> = emptyList()
)

data class ScrapeCacheItem(
    val title: String,
    val category: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val overview: String?,
    val rating: String?,
    val seasonDetails: Map<Int, SeasonDetail> = emptyMap(),
    val episodeTitles: Map<Int, String> = emptyMap(),
    val episodeStills: Map<Int, String> = emptyMap(),
    val seasonEpisodeTitles: Map<String, String> = emptyMap(),
    val seasonEpisodeStills: Map<String, String> = emptyMap(),
    val groupName: String? = null,
    val isManuallyEdited: Boolean = false,
    val fileListSignature: String = ""
)

class VideoScraperViewModel(
    private val videoSettingsRepository: VideoSettingsRepository
) : ViewModel() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val tmdbSemaphore = Semaphore(5)
    private val pinyinCollator = Collator.getInstance(Locale.CHINA)

    private val _uiState = MutableStateFlow(VideoUiState())
    val uiState = _uiState.asStateFlow()

    private val pathHistory = mutableListOf<String>()
    val browsePath = mutableListOf<String>()

    private var hasLoadedLibrary = false
    private val libraryNames = mutableMapOf<String, String>()
    
    private var lastQuarkError: String? = null
    private val scrapedCache = ConcurrentHashMap<String, ScrapeCacheItem>()
    private var isHandlingPendingRequest = false

    init {
        loadScrapedCache()
        val savedLibs = videoSettingsRepository.libraryFids.split(",").filter { it.isNotBlank() }
        val fids = mutableSetOf<String>()
        savedLibs.forEach { item ->
            val parts = item.split(":")
            if (parts.isNotEmpty()) {
                val fid = parts[0]
                fids.add(fid)
                if (parts.size > 1) libraryNames[fid] = parts[1]
            }
        }
        
        _uiState.update {
            it.copy(
                cookie = videoSettingsRepository.quarkCookie,
                tmdbApiKey = videoSettingsRepository.tmdbApiKey,
                tmdbBaseUrl = videoSettingsRepository.tmdbBaseUrl.ifBlank { "https://tmdbimg.quarkalist.xyz" },
                selectedLibraryFids = fids,
                isLibraryMode = true,
                downloadThreads = videoSettingsRepository.proxyThreads,
                bannerLogic = videoSettingsRepository.bannerLogic,
                categoryOrder = videoSettingsRepository.categoryOrder
            )
        }

        val cachedSeries = scrapedCache.map { (id, cached) ->
            MediaSeries(
                id = id,
                title = cached.title,
                originalParsedTitle = cached.title,
                year = null,
                category = cached.category,
                posterUrl = cached.posterUrl,
                backdropUrl = cached.backdropUrl,
                overview = cached.overview,
                rating = cached.rating,
                seasonDetails = cached.seasonDetails,
                episodeTitles = cached.episodeTitles,
                episodeStills = cached.episodeStills,
                seasonEpisodeTitles = cached.seasonEpisodeTitles,
                seasonEpisodeStills = cached.seasonEpisodeStills,
                groupName = cached.groupName,
                isManuallyEdited = cached.isManuallyEdited,
                videos = emptyList() 
            )
        }.filter { !it.title.matches(Regex("^\\d+$")) } // 🌟 启动防闪：过滤纯数字脏数据
         .sortedWith(compareBy(pinyinCollator) { it.title })
        
        if (cachedSeries.isNotEmpty()) {
            _uiState.update { it.copy(seriesList = cachedSeries) }
        }

        fetchQuarkFiles("0")
        if (fids.isNotEmpty()) loadLibraryVideos()
    }

    private fun loadScrapedCache() {
        try {
            val jsonStr = videoSettingsRepository.scrapedCacheData
            if (jsonStr.isNotBlank()) {
                val jsonObj = JSONObject(jsonStr)
                for (key in jsonObj.keys()) {
                    try {
                        val itemObj = jsonObj.getJSONObject(key)
                        
                        val epTitlesObj = itemObj.optJSONObject("episodeTitles")
                        val epTitles = mutableMapOf<Int, String>()
                        epTitlesObj?.keys()?.forEach { k -> 
                            k.toIntOrNull()?.let { num -> epTitles[num] = epTitlesObj.getString(k) }
                        }
                        
                        val epStillsObj = itemObj.optJSONObject("episodeStills")
                        val epStills = mutableMapOf<Int, String>()
                        epStillsObj?.keys()?.forEach { k -> 
                            k.toIntOrNull()?.let { num -> epStills[num] = epStillsObj.getString(k) }
                        }

                        val seasonDetailsObj = itemObj.optJSONObject("seasonDetails")
                        val seasonDetails = mutableMapOf<Int, SeasonDetail>()
                        seasonDetailsObj?.keys()?.forEach { k ->
                            k.toIntOrNull()?.let { num ->
                                val sObj = seasonDetailsObj.getJSONObject(k)
                                seasonDetails[num] = SeasonDetail(
                                    posterUrl = sObj.optString("posterUrl").takeIf { it.isNotBlank() && it != "null" },
                                    overview = sObj.optString("overview").takeIf { it.isNotBlank() && it != "null" }
                                )
                            }
                        }

                        val sEpTitlesObj = itemObj.optJSONObject("seasonEpisodeTitles")
                        val sEpTitles = mutableMapOf<String, String>()
                        sEpTitlesObj?.keys()?.forEach { k -> sEpTitles[k] = sEpTitlesObj.getString(k) }

                        val sEpStillsObj = itemObj.optJSONObject("seasonEpisodeStills")
                        val sEpStills = mutableMapOf<String, String>()
                        sEpStillsObj?.keys()?.forEach { k -> sEpStills[k] = sEpStillsObj.getString(k) }

                        scrapedCache[key] = ScrapeCacheItem(
                            title = itemObj.optString("title"),
                            category = itemObj.optString("category"),
                            posterUrl = itemObj.optString("posterUrl").takeIf { it.isNotBlank() && it != "null" },
                            backdropUrl = itemObj.optString("backdropUrl").takeIf { it.isNotBlank() && it != "null" },
                            overview = itemObj.optString("overview").takeIf { it.isNotBlank() && it != "null" },
                            rating = itemObj.optString("rating").takeIf { it.isNotBlank() && it != "null" },
                            seasonDetails = seasonDetails,
                            episodeTitles = epTitles,
                            episodeStills = epStills,
                            seasonEpisodeTitles = sEpTitles,
                            seasonEpisodeStills = sEpStills,
                            groupName = itemObj.optString("groupName").takeIf { it.isNotBlank() && it != "null" },
                            isManuallyEdited = itemObj.optBoolean("isManuallyEdited", false),
                            fileListSignature = itemObj.optString("fileListSignature", "")
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        } catch(e: Exception) { 
            e.printStackTrace() 
        }
    }

    private fun saveScrapedCache() {
        try {
            val jsonObj = JSONObject()
            scrapedCache.forEach { (key, item) ->
                val itemObj = JSONObject()
                itemObj.put("title", item.title)
                itemObj.put("category", item.category)
                itemObj.put("posterUrl", item.posterUrl ?: "")
                itemObj.put("backdropUrl", item.backdropUrl ?: "")
                itemObj.put("overview", item.overview ?: "")
                itemObj.put("rating", item.rating ?: "")
                itemObj.put("groupName", item.groupName ?: "")
                itemObj.put("isManuallyEdited", item.isManuallyEdited)
                itemObj.put("fileListSignature", item.fileListSignature)
                
                val epTitlesObj = JSONObject()
                item.episodeTitles.forEach { (k, v) -> epTitlesObj.put(k.toString(), v) }
                itemObj.put("episodeTitles", epTitlesObj)

                val epStillsObj = JSONObject()
                item.episodeStills.forEach { (k, v) -> epStillsObj.put(k.toString(), v) }
                itemObj.put("episodeStills", epStillsObj)

                val seasonDetailsObj = JSONObject()
                item.seasonDetails.forEach { (k, v) ->
                    seasonDetailsObj.put(k.toString(), JSONObject().apply {
                        put("posterUrl", v.posterUrl ?: "")
                        put("overview", v.overview ?: "")
                    })
                }
                itemObj.put("seasonDetails", seasonDetailsObj)

                val sEpTitlesObj = JSONObject()
                item.seasonEpisodeTitles.forEach { (k, v) -> sEpTitlesObj.put(k, v) }
                itemObj.put("seasonEpisodeTitles", sEpTitlesObj)

                val sEpStillsObj = JSONObject()
                item.seasonEpisodeStills.forEach { (k, v) -> sEpStillsObj.put(k, v) }
                itemObj.put("seasonEpisodeStills", sEpStillsObj)

                jsonObj.put(key, itemObj)
            }
            videoSettingsRepository.scrapedCacheData = jsonObj.toString()
        } catch(e: Exception) { e.printStackTrace() }
    }

    fun saveSettings(cookie: String, tmdbKey: String, baseUrl: String) {
        val cleanCookie = cookie.replace("\n", "").replace("\r", "").trim()
        val cleanBaseUrl = baseUrl.removeSuffix("/").trim().ifBlank { "https://tmdbimg.quarkalist.xyz" }
        videoSettingsRepository.quarkCookie = cleanCookie
        videoSettingsRepository.tmdbApiKey = tmdbKey
        videoSettingsRepository.tmdbBaseUrl = cleanBaseUrl
        _uiState.update { it.copy(cookie = cleanCookie, tmdbApiKey = tmdbKey, tmdbBaseUrl = cleanBaseUrl) }
        fetchQuarkFiles("0")
        hasLoadedLibrary = false
        loadLibraryVideos()
    }

    fun saveBannerLogic(logic: String) {
        videoSettingsRepository.bannerLogic = logic
        _uiState.update { it.copy(bannerLogic = logic) }
    }

    fun saveCategoryOrder(order: String) {
        videoSettingsRepository.categoryOrder = order
        _uiState.update { it.copy(categoryOrder = order) }
    }

    fun saveDownloadThreads(count: Int) {
        videoSettingsRepository.proxyThreads = count
        _uiState.update { it.copy(downloadThreads = count) }
    }

    fun loginWithCapturedCookie(capturedCookie: String) {
        if (capturedCookie.isBlank()) {
            videoSettingsRepository.quarkCookie = ""
            _uiState.update { it.copy(cookie = "", seriesList = emptyList(), fileList = emptyList()) }
            return
        }
        val cleanCookie = capturedCookie.replace("\n", "").replace("\r", "").trim()
        videoSettingsRepository.quarkCookie = cleanCookie
        _uiState.update { it.copy(cookie = cleanCookie) }
        fetchQuarkFiles("0")
        hasLoadedLibrary = false
        loadLibraryVideos()
    }

    fun toggleLibrary(fid: String, name: String) {
        val current = _uiState.value.selectedLibraryFids.toMutableSet()
        if (current.contains(fid)) {
            current.remove(fid)
            libraryNames.remove(fid)
        } else {
            current.add(fid)
            libraryNames[fid] = name
        }
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
            _uiState.update { it.copy(seriesList = emptyList(), errorMsg = "请先勾选文件夹", isLoading = false, isScraping = false) }
            return
        }

        _uiState.update { it.copy(isLoading = true, errorMsg = null, isScraping = true) }

        viewModelScope.launch {
            try {
                val allVideos = mutableListOf<QuarkFile>()
                withContext(Dispatchers.IO) {
                    for (fid in libs) {
                        val folderName = libraryNames[fid] ?: ""
                        val initialPath = if (folderName.isNotBlank()) folderName.split("/") else emptyList()
                        fetchVideosRecursively(cookie, fid, initialPath, allVideos)
                    }
                }
                val uniqueVideos = allVideos.distinctBy { it.fid }
                val groupedSeries = aggregateToSeries(uniqueVideos)

                val validIds = groupedSeries.map { it.id }.toSet()
                val invalidIds = scrapedCache.keys.filter { it !in validIds && (it.startsWith("temp_") || it.matches(Regex("^\\d+$"))) }
                invalidIds.forEach { scrapedCache.remove(it) }
                if (invalidIds.isNotEmpty()) saveScrapedCache()

                val needScrapeList = mutableListOf<MediaSeries>()
                var emptyPosterRetryCount = 0
                val maxEmptyPosterRetry = 10

                val finalSeriesList = groupedSeries.map { series ->
                    val newSignature = series.videos.map { it.fid }.sorted().joinToString("|")
                    
                    var cached = scrapedCache[series.id]
                    
                    if (cached == null || cached.fileListSignature != newSignature) {
                        val migratedCache = scrapedCache.values.find { it.fileListSignature == newSignature }
                        if (migratedCache != null) {
                            val oldId = scrapedCache.entries.find { it.value == migratedCache }?.key
                            if (oldId != null) {
                                scrapedCache.remove(oldId)
                            }
                            cached = migratedCache
                            scrapedCache[series.id] = migratedCache 
                        }
                    }

                    val isSignatureChanged = cached == null || newSignature != cached.fileListSignature
                    val isEmptyPoster = cached?.posterUrl.isNullOrBlank()
                    val shouldRetryEmptyPoster = isEmptyPoster && emptyPosterRetryCount < maxEmptyPosterRetry

                    val needScrape = isSignatureChanged || shouldRetryEmptyPoster
                    if (needScrape) {
                        needScrapeList.add(series)
                        if (isEmptyPoster) emptyPosterRetryCount++
                        if (cached != null) {
                            series.copy(
                                title = cached.title, category = cached.category, posterUrl = cached.posterUrl,
                                backdropUrl = cached.backdropUrl, overview = cached.overview, rating = cached.rating,
                                seasonDetails = cached.seasonDetails, episodeTitles = cached.episodeTitles,
                                episodeStills = cached.episodeStills, seasonEpisodeTitles = cached.seasonEpisodeTitles,
                                seasonEpisodeStills = cached.seasonEpisodeStills, groupName = cached.groupName,
                                isManuallyEdited = cached.isManuallyEdited
                            )
                        } else series
                    } else {
                        series.copy(
                            title = cached!!.title, category = cached.category, posterUrl = cached.posterUrl,
                            backdropUrl = cached.backdropUrl, overview = cached.overview, rating = cached.rating,
                            seasonDetails = cached.seasonDetails, episodeTitles = cached.episodeTitles,
                            episodeStills = cached.episodeStills, seasonEpisodeTitles = cached.seasonEpisodeTitles,
                            seasonEpisodeStills = cached.seasonEpisodeStills, groupName = cached.groupName,
                            isManuallyEdited = cached.isManuallyEdited
                        )
                    }
                }.sortedWith(compareBy(pinyinCollator) { it.title })

                _uiState.update { it.copy(seriesList = finalSeriesList) }
                
                // 🌟 核心修复：绝不在此处清理历史记录。等刮削完成后再清。避免用文件夹原名去误杀刮削后的名字！
                // cleanInvalidRecentWatched(finalSeriesList)

                if (apiKey.isNotBlank() && needScrapeList.isNotEmpty()) {
                    scrapeSeriesPosters(needScrapeList, apiKey, _uiState.value.tmdbBaseUrl)
                } else {
                    hasLoadedLibrary = true
                    _uiState.update { it.copy(isLoading = false, isScraping = false) }
                    handlePendingPlayRequest()
                }

            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, isScraping = false, errorMsg = "扫描异常: ${e.message}") }
            }
        }
    }

    // 🌟 修复历史记录误删：此处拿到的 seriesList 是已经刮削完毕或合并了正确信息的列表
    private fun cleanInvalidRecentWatched(seriesList: List<MediaSeries>) {
        try {
            val jsonArray = JSONArray(videoSettingsRepository.recentWatchedVideos)
            val newList = mutableListOf<JSONObject>()
            
            // 收集当前所有合法剧集的 id 和 title
            val validNames = mutableSetOf<String>()
            seriesList.forEach { series ->
                validNames.add(series.id.replace("\\s".toRegex(), ""))
                validNames.add(series.title.replace("\\s".toRegex(), ""))
                validNames.add(series.originalParsedTitle.replace("\\s".toRegex(), ""))
            }
            
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val sTitle = obj.optString("seriesTitle").replace("\\s".toRegex(), "")
                if (validNames.contains(sTitle)) {
                    newList.add(obj)
                }
            }
            val resultArray = JSONArray()
            newList.forEach { resultArray.put(it) }
            videoSettingsRepository.recentWatchedVideos = resultArray.toString()
        } catch (e: Exception) { e.printStackTrace() }
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
            var finalCategory = "未分类"
            for (pathNode in v.folderPath) {
                if (categoryMap.containsKey(pathNode)) {
                    finalCategory = categoryMap[pathNode]!!
                    break
                }
            }

            val meaningfulPaths = v.folderPath.filter { !excludeFolders.contains(it) }
            var baseTitle = ""
            var groupName: String? = null
            var folderSeason: Int? = null

            if (meaningfulPaths.isNotEmpty()) {
                var lastPath = meaningfulPaths.last()
                
                if (lastPath.matches(Regex("^\\d+$")) && meaningfulPaths.size >= 2) {
                    lastPath = meaningfulPaths[meaningfulPaths.size - 2]
                }

                if (VideoFileNameParser.isSeasonFolder(lastPath)) {
                    folderSeason = parseSeasonFromFolder(lastPath)
                    val seasonStr = lastPath
                    baseTitle = if (meaningfulPaths.size >= 2) meaningfulPaths[meaningfulPaths.size - 2] else meaningfulPaths.last()
                    groupName = baseTitle 
                    baseTitle = "$baseTitle $seasonStr" 
                } else {
                    baseTitle = lastPath
                    if (meaningfulPaths.size >= 2 && VideoFileNameParser.isSeasonFolder(meaningfulPaths[meaningfulPaths.size - 2])) {
                        val seasonStr = meaningfulPaths[meaningfulPaths.size - 2]
                        folderSeason = parseSeasonFromFolder(seasonStr)
                        groupName = baseTitle
                        baseTitle = "$baseTitle $seasonStr"
                    }
                }
            } else {
                baseTitle = parsed.title
            }

            if (baseTitle.isBlank() || baseTitle == "未知影视") baseTitle = parsed.title

            val key = baseTitle

            val finalParsedInfo = if (folderSeason != null) {
                parsed.copy(season = folderSeason)
            } else {
                parsed
            }

            val updatedV = v.copy(parsedInfo = finalParsedInfo)

            if (seriesMap.containsKey(key)) {
                val exist = seriesMap[key]!!
                val combinedVideos = (exist.videos + updatedV).sortedWith(
                    compareBy<QuarkFile> { it.parsedInfo?.season ?: 1 }
                        .thenBy { it.parsedInfo?.episode ?: 0 }
                        .thenBy { it.name }
                )
                seriesMap[key] = exist.copy(videos = combinedVideos)
            } else {
                seriesMap[key] = MediaSeries(
                    id = key, title = baseTitle, originalParsedTitle = baseTitle,
                    year = parsed.year, category = finalCategory, videos = listOf(updatedV),
                    groupName = groupName
                )
            }
        }
        return seriesMap.values.toList().sortedWith(compareBy(pinyinCollator) { it.title })
    }

    private fun parseSeasonFromFolder(folderName: String): Int? {
        val cnMatch = Regex("第([0-9一二三四五六七八九十]+)季").find(folderName)
        if (cnMatch != null) {
            val sStr = cnMatch.groupValues[1]
            return when(sStr) {
                "一" -> 1; "二" -> 2; "三" -> 3; "四" -> 4; "五" -> 5
                "六" -> 6; "七" -> 7; "八" -> 8; "九" -> 9; "十" -> 10
                else -> sStr.toIntOrNull()
            }
        }
        val enMatch = Regex("(?i)season\\s*(\\d+)").find(folderName) ?: Regex("(?i)^s(\\d{1,2})$").find(folderName)
        return enMatch?.groupValues?.get(1)?.toIntOrNull()
    }

    private suspend fun fetchVideosRecursively(
        cookie: String, parentFid: String, currentPath: List<String>, resultList: MutableList<QuarkFile>
    ) {
        withContext(Dispatchers.IO) {
            var page = 1
            var hasMore = true
            while (hasMore) {
                val url = "https://drive-pc.quark.cn/1/clouddrive/file/sort?pr=ucpro&fr=pc&uc_param_str=&pdir_fid=$parentFid&_page=$page&_size=100&_sort=file_type:asc,updated_at:desc"
                val request = Request.Builder().url(url).header("Cookie", cookie).header("User-Agent", "Mozilla/5.0").build()
                try {
                    client.newCall(request).execute().use { response ->
                        updateCookieFromResponse(response.headers)
                        
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
                                            val parsed = VideoFileNameParser.parse(fileName, currentPath.lastOrNull())
                                            if (parsed.isVideo) {
                                                resultList.add(QuarkFile(fileFid, fileName, false, "未分类", currentPath, parsed, size))
                                            }
                                        }
                                    }
                                    if (list.length() < 100) {
                                        hasMore = false
                                    } else {
                                        page++
                                    }
                                } else {
                                    hasMore = false
                                }
                            } else {
                                hasMore = false
                            }
                        } else {
                            hasMore = false
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    hasMore = false
                }
            }
        }
    }

    private fun scrapeSeriesPosters(needScrapeList: List<MediaSeries>, apiKey: String, baseUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val scrapedList = needScrapeList.map { series ->
                tmdbSemaphore.withPermit {
                    var searchTitle = series.originalParsedTitle
                    if (series.groupName != null) searchTitle = series.groupName

                    val match = searchTmdbWithFallback(searchTitle, series.year, apiKey, baseUrl)
                    if (match != null) {
                        var finalTitle = match.title
                        if (series.groupName != null && series.originalParsedTitle.contains("第")) {
                            val suffix = series.originalParsedTitle.substringAfter(series.groupName).trim()
                            if (suffix.isNotBlank() && !finalTitle.contains(suffix)) finalTitle = "$finalTitle $suffix"
                        }
                        if (finalTitle.matches(Regex("^[a-zA-Z0-9\\s\\p{Punct}]+$")) && searchTitle.any { it.code > 127 }) finalTitle = searchTitle
                        
                        var finalCategory = series.category
                        if (finalCategory == "未分类") {
                            finalCategory = when {
                                match.mediaType == "movie" -> "电影"
                                match.genreIds.contains(16) -> "动漫"
                                match.mediaType == "tv" -> "剧集"
                                else -> "未分类"
                            }
                        }
                        
                        var epTitles = mutableMapOf<Int, String>()
                        var epStills = mutableMapOf<Int, String>()
                        var seasonDetails = mutableMapOf<Int, SeasonDetail>()
                        var sEpTitles = mutableMapOf<String, String>()
                        var sEpStills = mutableMapOf<String, String>()

                        val oldCached = scrapedCache[series.id]
                        if (oldCached != null) {
                            epTitles.putAll(oldCached.episodeTitles)
                            epStills.putAll(oldCached.episodeStills)
                            seasonDetails.putAll(oldCached.seasonDetails)
                            sEpTitles.putAll(oldCached.seasonEpisodeTitles)
                            sEpStills.putAll(oldCached.seasonEpisodeStills)
                        }

                        if (match.mediaType != "movie") {
                            val allSeasons = series.videos.mapNotNull { it.parsedInfo?.season }.distinct().sorted()
                            val targetSeasons = if (allSeasons.isEmpty()) listOf(1) else allSeasons
                            for (season in targetSeasons) {
                                val detail = fetchSeasonDetail(match.id, season, apiKey, baseUrl)
                                if (detail != null) {
                                    seasonDetails[season] = detail.first
                                    sEpTitles.putAll(detail.second)
                                    sEpStills.putAll(detail.third)
                                    if (season == 1) {
                                        detail.second.forEach { (k, v) ->
                                            val epNum = k.substringAfter("_").toIntOrNull()
                                            if (epNum != null) epTitles[epNum] = v
                                        }
                                        detail.third.forEach { (k, v) ->
                                            val epNum = k.substringAfter("_").toIntOrNull()
                                            if (epNum != null) epStills[epNum] = v
                                        }
                                    }
                                }
                            }
                        }

                        val newSeries = series.copy(
                            title = finalTitle, category = finalCategory, posterUrl = match.posterUrl, backdropUrl = match.backdropUrl,
                            overview = match.overview, rating = match.rating, 
                            seasonDetails = seasonDetails, episodeTitles = epTitles, episodeStills = epStills,
                            seasonEpisodeTitles = sEpTitles, seasonEpisodeStills = sEpStills
                        )

                        val newSignature = newSeries.videos.map { it.fid }.sorted().joinToString("|")

                        scrapedCache[newSeries.id] = ScrapeCacheItem(
                            title = newSeries.title, category = newSeries.category, posterUrl = newSeries.posterUrl,
                            backdropUrl = newSeries.backdropUrl, overview = newSeries.overview, rating = newSeries.rating,
                            seasonDetails = newSeries.seasonDetails, episodeTitles = newSeries.episodeTitles,
                            episodeStills = newSeries.episodeStills, seasonEpisodeTitles = newSeries.seasonEpisodeTitles,
                            seasonEpisodeStills = newSeries.seasonEpisodeStills, groupName = newSeries.groupName,
                            isManuallyEdited = false, fileListSignature = newSignature 
                        )
                        newSeries
                    } else {
                        val newSignature = series.videos.map { it.fid }.sorted().joinToString("|")
                        val oldCached = scrapedCache[series.id]
                        if (oldCached == null) {
                            scrapedCache[series.id] = ScrapeCacheItem(
                                title = series.title, category = series.category, posterUrl = null, backdropUrl = null,
                                overview = null, rating = null, groupName = series.groupName,
                                isManuallyEdited = false, fileListSignature = newSignature
                            )
                        }
                        series
                    }
                }
            }

            saveScrapedCache()

            withContext(Dispatchers.Main) {
                val currentList = _uiState.value.seriesList.map { old ->
                    scrapedList.find { it.id == old.id } ?: old
                }.sortedWith(compareBy(pinyinCollator) { it.title })
                
                val oldCurrent = _uiState.value.currentSeries
                val newCurrent = if (oldCurrent != null) {
                    currentList.find { it.id == oldCurrent.id } 
                        ?: currentList.find { it.title == oldCurrent.title }
                        ?: oldCurrent
                } else null

                hasLoadedLibrary = true
                _uiState.update { it.copy(isLoading = false, isScraping = false, seriesList = currentList, currentSeries = newCurrent) }
                
                // 🌟 关键修复：刮削完成后，数据已经是最新的真实剧名，此时再清理历史记录，绝对不会误杀！
                cleanInvalidRecentWatched(currentList)
                
                handlePendingPlayRequest()
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
        val isProxy = baseUrl.contains("quarkalist") || baseUrl.contains("proxy")
        val url = try {
            val query = URLEncoder.encode(title, "UTF-8")
            val apiPath = if (isProxy) "/api/3/search/multi" else "/3/search/multi"
            "$baseUrl$apiPath?api_key=$apiKey&query=$query&language=$language"
        } catch (e: Exception) { return null }

        var lastException: Exception? = null
        for (attempt in 0..2) {
            if (attempt > 0) {
                val backoffMs = if (attempt == 1) 1000L else 2000L
                try { Thread.sleep(backoffMs) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); return null }
            }
            try {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    val code = response.code
                    if (code == 401 || code == 403) return null
                    if (code == 429 || code in 500..599) {
                        lastException = IOException("HTTP $code")
                        return@use
                    }
                    if (code != 200) return null

                    val body = response.body?.string()
                    if (body.isNullOrBlank()) return null
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
                            if (score > topScore && score > 0) { topScore = score; bestItem = item }
                        }
                        if (bestItem == null) bestItem = results.getJSONObject(0)
                        
                        val posterPath = bestItem.optString("poster_path")
                        val backdropPath = bestItem.optString("backdrop_path")
                        val officialName = bestItem.optString("title").ifBlank { bestItem.optString("name") }
                        val voteAverage = bestItem.optDouble("vote_average", 0.0)
                        val ratingStr = if (voteAverage > 0) String.format("%.1f", voteAverage) else null
                        val mediaType = bestItem.optString("media_type", "tv")
                        val genreIdsList = mutableListOf<Int>()
                        val genreIdsArray = bestItem.optJSONArray("genre_ids")
                        if (genreIdsArray != null) {
                            for (j in 0 until genreIdsArray.length()) genreIdsList.add(genreIdsArray.getInt(j))
                        }
                        
                        val posterUrl = if (posterPath.isNotBlank() && posterPath != "null") {
                            if (isProxy) "$baseUrl/img/t/p/w500$posterPath" else "https://image.tmdb.org/t/p/w500$posterPath"
                        } else null
                        
                        val backdropUrl = if (backdropPath.isNotBlank() && backdropPath != "null") {
                            if (isProxy) "$baseUrl/img/t/p/w780$backdropPath" else "https://image.tmdb.org/t/p/w780$backdropPath"
                        } else null

                        return TmdbSearchResult(
                            id = bestItem.optInt("id"),
                            title = officialName,
                            year = (bestItem.optString("release_date").take(4).ifBlank { bestItem.optString("first_air_date").take(4) }),
                            posterUrl = posterUrl, 
                            backdropUrl = backdropUrl, 
                            overview = bestItem.optString("overview"), rating = ratingStr,
                            mediaType = mediaType, genreIds = genreIdsList
                        )
                    }
                    return null
                }
            } catch (e: IOException) {
                lastException = e
            } catch (e: Exception) {
                return null
            }
        }
        return null
    }

    private fun fetchSeasonDetail(tvId: Int, season: Int, apiKey: String, baseUrl: String): Triple<SeasonDetail, Map<String, String>, Map<String, String>>? {
        val titles = mutableMapOf<String, String>()
        val stills = mutableMapOf<String, String>()
        try {
            val isProxy = baseUrl.contains("quarkalist") || baseUrl.contains("proxy")
            val apiPath = if (isProxy) "/api/3/tv/$tvId/season/$season" else "/3/tv/$tvId/season/$season"
            val url = "$baseUrl$apiPath?api_key=$apiKey&language=zh-CN"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (!body.isNullOrBlank()) {
                    val json = JSONObject(body)
                    
                    val seasonPosterPath = json.optString("poster_path")
                    val seasonOverview = json.optString("overview")
                    val seasonPosterUrl = if (seasonPosterPath.isNotBlank() && seasonPosterPath != "null") {
                        if (isProxy) "$baseUrl/img/t/p/w500$seasonPosterPath" else "https://image.tmdb.org/t/p/w500$seasonPosterPath"
                    } else null
                    
                    val episodes = json.optJSONArray("episodes")
                    if (episodes != null) {
                        for (i in 0 until episodes.length()) {
                            val ep = episodes.getJSONObject(i)
                            val epNum = ep.optInt("episode_number")
                            val epName = ep.optString("name")
                            val stillPath = ep.optString("still_path")
                            if (epNum > 0) {
                                val key = "${season}_${epNum}"
                                if (epName.isNotBlank()) titles[key] = epName
                                if (stillPath.isNotBlank() && stillPath != "null") {
                                    stills[key] = if (isProxy) "$baseUrl/img/t/p/w300$stillPath" else "https://image.tmdb.org/t/p/w300$stillPath"
                                }
                            }
                        }
                    }
                    return Triple(SeasonDetail(seasonPosterUrl, seasonOverview), titles, stills)
                }
            }
        } catch (e: Exception) {}
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
                val isProxy = baseUrl.contains("quarkalist") || baseUrl.contains("proxy")
                val apiPath = if (isProxy) "/api/3/search/multi" else "/3/search/multi"
                val url = "$baseUrl$apiPath?api_key=$apiKey&query=$q&language=zh-CN"
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
                                val backdropPath = item.optString("backdrop_path")
                                val voteAverage = item.optDouble("vote_average", 0.0)
                                
                                val posterUrl = if (posterPath.isNotBlank() && posterPath != "null") {
                                    if (isProxy) "$baseUrl/img/t/p/w300$posterPath" else "https://image.tmdb.org/t/p/w300$posterPath"
                                } else null
                                
                                val backdropUrl = if (backdropPath.isNotBlank() && backdropPath != "null") {
                                    if (isProxy) "$baseUrl/img/t/p/w780$backdropPath" else "https://image.tmdb.org/t/p/w780$backdropPath"
                                } else null

                                list.add(
                                    TmdbSearchResult(
                                        id = item.optInt("id"), title = title,
                                        year = item.optString("release_date").take(4).ifBlank { item.optString("first_air_date").take(4) },
                                        posterUrl = posterUrl, 
                                        backdropUrl = backdropUrl,
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
        val apiKey = _uiState.value.tmdbApiKey
        val baseUrl = _uiState.value.tmdbBaseUrl

        val currentList = _uiState.value.seriesList.map {
            if (it.id == seriesId) {
                it.copy(
                    title = result?.title ?: newTitle, 
                    posterUrl = result?.posterUrl ?: it.posterUrl,
                    backdropUrl = result?.backdropUrl ?: it.backdropUrl,
                    overview = result?.overview ?: it.overview, 
                    rating = result?.rating ?: it.rating, 
                    category = newCategory,
                    isManuallyEdited = true
                )
            } else it
        }.sortedWith(compareBy(pinyinCollator) { it.title })
        
        _uiState.update {
            val updatedCurrent = currentList.find { s -> s.id == seriesId }
            it.copy(seriesList = currentList, currentSeries = updatedCurrent ?: it.currentSeries)
        }

        val updatedSeries = currentList.find { it.id == seriesId }
        if (updatedSeries == null) return

        saveManualScrapeBasic(updatedSeries)

        if (result != null && apiKey.isNotBlank()) {
            viewModelScope.launch(Dispatchers.IO) {
                val seasonTitles = mutableMapOf<String, String>()
                val episodeTitles = mutableMapOf<Int, String>()
                val seasonEpisodeStills = mutableMapOf<String, String>()
                val episodeStills = mutableMapOf<Int, String>()

                val allSeasons = updatedSeries.videos
                    .mapNotNull { it.parsedInfo?.season }
                    .distinct()
                    .sorted()
                val targetSeasons = if (allSeasons.isEmpty()) listOf(1) else allSeasons

                for (season in targetSeasons) {
                    val detail = tmdbSemaphore.withPermit {
                        fetchSeasonDetail(result.id, season, apiKey, baseUrl)
                    }
                    if (detail != null) {
                        seasonTitles.putAll(detail.second)
                        seasonEpisodeStills.putAll(detail.third)
                        if (targetSeasons.size == 1) {
                            detail.second.forEach { (k, v) ->
                                val epNum = k.substringAfter("_").toIntOrNull()
                                if (epNum != null) episodeTitles[epNum] = v
                            }
                            detail.third.forEach { (k, v) ->
                                val epNum = k.substringAfter("_").toIntOrNull()
                                if (epNum != null) episodeStills[epNum] = v
                            }
                        }
                    }
                }

                val existingCache = scrapedCache[seriesId]
                val mergedSeasonTitles = if (existingCache != null) {
                    (existingCache.seasonEpisodeTitles + seasonTitles).toMutableMap()
                } else seasonTitles.toMutableMap()
                val mergedEpisodeTitles = if (existingCache != null) {
                    (existingCache.episodeTitles + episodeTitles).toMutableMap()
                } else episodeTitles.toMutableMap()
                val mergedSeasonStills = if (existingCache != null) {
                    (existingCache.seasonEpisodeStills + seasonEpisodeStills).toMutableMap()
                } else seasonEpisodeStills.toMutableMap()
                val mergedEpisodeStills = if (existingCache != null) {
                    (existingCache.episodeStills + episodeStills).toMutableMap()
                } else episodeStills.toMutableMap()

                val finalSeries = updatedSeries.copy(
                    seasonEpisodeTitles = mergedSeasonTitles,
                    episodeTitles = mergedEpisodeTitles,
                    seasonEpisodeStills = mergedSeasonStills,
                    episodeStills = mergedEpisodeStills
                )

                withContext(Dispatchers.Main) {
                    val newList = _uiState.value.seriesList.map {
                        if (it.id == seriesId) finalSeries else it
                    }.sortedWith(compareBy(pinyinCollator) { it.title })
                    
                    _uiState.update {
                        it.copy(seriesList = newList, currentSeries = finalSeries)
                    }
                }

                saveManualScrapeComplete(finalSeries)
            }
        }
    }

    private fun saveManualScrapeBasic(series: MediaSeries) {
        val newSignature = series.videos.map { it.fid }.sorted().joinToString("|")
        scrapedCache[series.id] = ScrapeCacheItem(
            title = series.title,
            category = series.category,
            posterUrl = series.posterUrl,
            backdropUrl = series.backdropUrl,
            overview = series.overview,
            rating = series.rating,
            seasonDetails = series.seasonDetails,
            episodeTitles = series.episodeTitles,
            episodeStills = series.episodeStills,
            seasonEpisodeTitles = series.seasonEpisodeTitles,
            seasonEpisodeStills = series.seasonEpisodeStills,
            groupName = series.groupName,
            isManuallyEdited = true,
            fileListSignature = newSignature
        )
        saveScrapedCache()
    }

    private fun saveManualScrapeComplete(series: MediaSeries) {
        val newSignature = series.videos.map { it.fid }.sorted().joinToString("|")
        scrapedCache[series.id] = ScrapeCacheItem(
            title = series.title,
            category = series.category,
            posterUrl = series.posterUrl,
            backdropUrl = series.backdropUrl,
            overview = series.overview,
            rating = series.rating,
            seasonDetails = series.seasonDetails,
            episodeTitles = series.episodeTitles,
            episodeStills = series.episodeStills,
            seasonEpisodeTitles = series.seasonEpisodeTitles,
            seasonEpisodeStills = series.seasonEpisodeStills,
            groupName = series.groupName,
            isManuallyEdited = true,
            fileListSignature = newSignature
        )
        saveScrapedCache()
    }

    fun playVideo(context: Context, series: MediaSeries, file: QuarkFile) {
        val realSeries = if (series.id.startsWith("temp_")) {
            _uiState.value.seriesList.find { s -> s.videos.any { it.fid == file.fid } } ?: series
        } else series

        val cookie = _uiState.value.cookie
        
        val parsedInfo = file.parsedInfo ?: VideoFileNameParser.parse(file.name)
        val epNum = parsedInfo.episode
        val season = parsedInfo.season ?: 1
        
        var titleText = realSeries.title
        if (realSeries.category != "电影") {
            val realEpNum = epNum ?: Regex("第\\s*([0-9]+)\\s*集").find(file.name)?.groupValues?.get(1)?.toIntOrNull()
            if (realEpNum != null) {
                val tmdbEpTitle = realSeries.seasonEpisodeTitles["${season}_${realEpNum}"] ?: realSeries.episodeTitles[realEpNum]
                titleText = if (!tmdbEpTitle.isNullOrBlank()) "${realSeries.title} 第${realEpNum}集 $tmdbEpTitle"
                else "${realSeries.title} 第${realEpNum}集"
            } else if (file.name.contains("第") && file.name.contains("集")) {
                titleText = file.name 
            }
        }

        try {
            val jsonArray = JSONArray(videoSettingsRepository.recentWatchedVideos)
            val newList = mutableListOf<JSONObject>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                if (obj.optString("seriesTitle") != realSeries.title) {
                    newList.add(obj)
                }
            }
            val newObj = JSONObject().apply {
                put("fid", file.fid)
                put("title", titleText) 
                put("seriesTitle", realSeries.title)
                put("coverPath", realSeries.posterUrl ?: "")
                put("timestamp", System.currentTimeMillis())
            }
            newList.add(0, newObj)
            
            val resultArray = JSONArray()
            newList.take(50).forEach { resultArray.put(it) }
            videoSettingsRepository.recentWatchedVideos = resultArray.toString()
        } catch (e: Exception) { e.printStackTrace() }

        val isSameSeriesSwitch = _uiState.value.currentSeries?.id == realSeries.id && _uiState.value.playUrl != null

        if (isSameSeriesSwitch) {
            _uiState.update { it.copy(playTitle = titleText, currentSeries = realSeries) }
        } else {
            _uiState.update { it.copy(playUrl = null, playTitle = titleText, currentSeries = realSeries, currentPlayFile = file, isDirectPlay = false) }
        }

        viewModelScope.launch {
            val url = withContext(Dispatchers.IO) {
                fetchDirectDownloadUrl(file.fid, cookie)
            }

            if (url != null) {
                _uiState.update { it.copy(playUrl = url, currentPlayFile = file) }
            } else {
                val detail = lastQuarkError ?: "未知错误"
                stopPlaying()
                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(context)
                        .setTitle("获取原画直链失败")
                        .setMessage(detail)
                        .setPositiveButton("确定", null)
                        .show()
                }
            }
        }
    }

    fun handlePendingPlayRequest() {
        if (isHandlingPendingRequest) return
        val fid = videoSettingsRepository.pendingPlayFid ?: return
        
        val series = _uiState.value.seriesList.find { it.videos.any { v -> v.fid == fid } }
        val file = series?.videos?.find { it.fid == fid }
        
        if (series != null && file != null) {
            isHandlingPendingRequest = true
            videoSettingsRepository.pendingPlayFid = null
            
            val parsedInfo = file.parsedInfo ?: VideoFileNameParser.parse(file.name)
            val epNum = parsedInfo.episode
            val season = parsedInfo.season ?: 1
            
            var titleText = series.title
            if (series.category != "电影") {
                val realEpNum = epNum ?: Regex("第\\s*([0-9]+)\\s*集").find(file.name)?.groupValues?.get(1)?.toIntOrNull()
                if (realEpNum != null) {
                    val tmdbEpTitle = series.seasonEpisodeTitles["${season}_${realEpNum}"] ?: series.episodeTitles[realEpNum]
                    titleText = if (!tmdbEpTitle.isNullOrBlank()) "${series.title} 第${realEpNum}集 $tmdbEpTitle"
                    else "${series.title} 第${realEpNum}集"
                } else if (file.name.contains("第") && file.name.contains("集")) {
                    titleText = file.name
                }
            }

            _uiState.update { it.copy(playUrl = null, playTitle = titleText, currentSeries = series, currentPlayFile = file, isDirectPlay = true) }
            
            viewModelScope.launch {
                val url = withContext(Dispatchers.IO) { fetchDirectDownloadUrl(file.fid, _uiState.value.cookie) }
                if (url != null) {
                    _uiState.update { it.copy(playUrl = url) }
                } else {
                    stopPlaying()
                }
                isHandlingPendingRequest = false
            }
        }
    }

    private fun updateCookieFromResponse(headers: okhttp3.Headers) {
        val setCookies = headers.values("Set-Cookie")
        if (setCookies.isEmpty()) return

        var currentCookie = _uiState.value.cookie.ifBlank { videoSettingsRepository.quarkCookie }
        if (currentCookie.isBlank()) return

        val cookieMap = mutableMapOf<String, String>()
        currentCookie.split(";").forEach {
            val parts = it.trim().split("=", limit = 2)
            if (parts.size == 2) cookieMap[parts[0]] = parts[1]
        }

        var changed = false
        setCookies.forEach { setCookie ->
            val firstPart = setCookie.split(";").firstOrNull() ?: return@forEach
            val parts = firstPart.trim().split("=", limit = 2)
            if (parts.size == 2) {
                val key = parts[0].trim()
                val value = parts[1].trim()
                if (key == "__puus" || key == "__pus" || key == "__uid") {
                    if (cookieMap[key] != value) {
                        cookieMap[key] = value
                        changed = true
                    }
                }
            }
        }

        if (changed) {
            val newCookie = cookieMap.entries.joinToString(";") { "${it.key}=${it.value}" }
            videoSettingsRepository.quarkCookie = newCookie
            _uiState.update { it.copy(cookie = newCookie) }
        }
    }

    private fun fetchDirectDownloadUrl(fid: String, cookie: String): String? {
        lastQuarkError = null
        return try {
            val apiUrl = "https://drive-pc.quark.cn/1/clouddrive/file/download?pr=ucpro&fr=pc"
            val jsonBody = JSONObject().apply { put("fids", JSONArray().put(fid)) }
            val body = jsonBody.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(apiUrl).post(body)
                .header("Cookie", cookie)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch")
                .header("Referer", "https://pan.quark.cn/")
                .header("Accept", "application/json, text/plain, */*")
                .build()

            client.newCall(request).execute().use { response ->
                updateCookieFromResponse(response.headers)

                val httpCode = response.code
                val resBody = response.body?.string()

                if (httpCode != 200) {
                    lastQuarkError = "HTTP $httpCode\n\n$resBody"
                    return null
                }
                if (resBody.isNullOrBlank()) {
                    lastQuarkError = "响应体为空 (HTTP $httpCode)"
                    return null
                }

                val json = JSONObject(resBody)
                val code = json.optInt("code", -1)
                val message = json.optString("message")
                if (code != 0) {
                    lastQuarkError = "code=$code\nmessage=$message\n\n$resBody"
                    return null
                }

                val data = json.optJSONArray("data")
                if (data == null || data.length() == 0) {
                    lastQuarkError = "data 为空\n\n$resBody"
                    return null
                }

                val downloadUrl = data.getJSONObject(0).optString("download_url")
                if (downloadUrl.isBlank()) {
                    lastQuarkError = "download_url 为空\n\n$resBody"
                    return null
                }
                return downloadUrl
            }
        } catch (e: Exception) {
            lastQuarkError = "异常: ${e.message}\n\n${e.stackTraceToString()}"
            null
        }
    }

    fun getVideoProgress(fid: String): Long {
        try {
            val json = JSONObject(videoSettingsRepository.videoProgressData)
            if (json.has(fid)) return json.getLong(fid)
        } catch (e: Exception) {}
        return 0L
    }

    fun saveVideoProgress(fid: String, position: Long) {
        try {
            val json = JSONObject(videoSettingsRepository.videoProgressData)
            json.put(fid, position)
            videoSettingsRepository.videoProgressData = json.toString()
        } catch (e: Exception) {}
    }

    fun getIntroOutro(seriesTitle: String, season: Int): Pair<Int, Int> {
        try {
            val json = JSONObject(videoSettingsRepository.introOutroData)
            val key = "${seriesTitle}_S$season"
            if (json.has(key)) {
                val obj = json.getJSONObject(key)
                return Pair(obj.optInt("intro", 0), obj.optInt("outro", 0))
            }
        } catch (e: Exception) {}
        return Pair(0, 0)
    }

    fun saveIntroOutro(seriesTitle: String, season: Int, intro: Int, outro: Int) {
        try {
            val json = JSONObject(videoSettingsRepository.introOutroData)
            val key = "${seriesTitle}_S$season"
            json.put(key, JSONObject().apply { put("intro", intro); put("outro", outro) })
            videoSettingsRepository.introOutroData = json.toString()
        } catch (e: Exception) {}
    }

    fun stopPlaying() {
        _uiState.update { it.copy(playUrl = null, playTitle = null, currentPlayFile = null, isDirectPlay = false) }
    }

    fun clearRecentWatched() {
        videoSettingsRepository.recentWatchedVideos = "[]"
        _uiState.update { it.copy() } 
    }

    fun removeRecentWatchedBatch(seriesTitles: Set<String>) {
        try {
            val jsonArray = JSONArray(videoSettingsRepository.recentWatchedVideos)
            val newList = mutableListOf<JSONObject>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                if (!seriesTitles.contains(obj.optString("seriesTitle"))) {
                    newList.add(obj)
                }
            }
            val resultArray = JSONArray()
            newList.forEach { resultArray.put(it) }
            videoSettingsRepository.recentWatchedVideos = resultArray.toString()
            _uiState.update { it.copy() }
        } catch (e: Exception) {}
    }

    fun fetchQuarkFiles(fid: String = "0", isBack: Boolean = false, folderName: String? = null) {
        val cookie = _uiState.value.cookie
        if (cookie.isBlank()) {
            _uiState.update { it.copy(errorMsg = "请先设置 Cookie 或扫码登录") }
            return
        }
        
        if (fid == "0") {
            pathHistory.clear()
            browsePath.clear()
        } else if (!isBack && _uiState.value.currentFid != fid) {
            pathHistory.add(_uiState.value.currentFid)
            if (folderName != null) {
                browsePath.add(folderName)
            }
        }
        
        _uiState.update { it.copy(currentFid = fid, errorMsg = null) }

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val url = "https://drive-pc.quark.cn/1/clouddrive/file/sort?pr=ucpro&fr=pc&uc_param_str=&pdir_fid=$fid&_page=1&_size=100&_sort=file_type:asc,updated_at:desc"
                    val request = Request.Builder().url(url).header("Cookie", cookie).header("User-Agent", "Mozilla/5.0").build()
                    client.newCall(request).execute().use { Triple(it.isSuccessful, it.body?.string(), it.headers) }
                }
                val (isSuccessful, body, headers) = result
                updateCookieFromResponse(headers)
                
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
                        _uiState.update { it.copy(errorMsg = "获取失败，Cookie 可能已过期") }
                    }
                }
            } catch (e: Exception) { _uiState.update { it.copy(errorMsg = "请求异常") } }
        }
    }

    fun goBack() {
        if (pathHistory.isNotEmpty()) {
            if (browsePath.isNotEmpty()) {
                browsePath.removeAt(browsePath.size - 1)
            }
            fetchQuarkFiles(pathHistory.removeAt(pathHistory.size - 1), isBack = true) 
        } else {
            fetchQuarkFiles("0")
        }
    }
}

data class VideoUiState(
    val cookie: String = "",
    val tmdbApiKey: String = "",
    val tmdbBaseUrl: String = "https://tmdbimg.quarkalist.xyz",
    val currentFid: String = "0",
    val isLoading: Boolean = false,
    val isScraping: Boolean = false, 
    val errorMsg: String? = null,
    val fileList: List<QuarkFile> = emptyList(),
    val isLibraryMode: Boolean = true,
    val selectedLibraryFids: Set<String> = emptySet(),
    val seriesList: List<MediaSeries> = emptyList(),
    val currentSeries: MediaSeries? = null,
    val playUrl: String? = null,
    val playTitle: String? = null,
    val currentPlayFile: QuarkFile? = null,
    val downloadThreads: Int = 6,
    val isDirectPlay: Boolean = false,
    val bannerLogic: String = "智能推荐",
    val categoryOrder: String = "最近观看,剧集,电影,动漫,综艺"
)