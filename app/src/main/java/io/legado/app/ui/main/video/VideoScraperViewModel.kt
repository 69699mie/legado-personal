package io.legado.app.ui.main.video

import android.app.AlertDialog
import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.dao.VideoScrapedDao
import io.legado.app.data.entities.VideoScrapedEntity
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

@Immutable
data class QuarkFile(
    val fid: String,
    val name: String,
    val isFolder: Boolean,
    val category: String = "未分类",
    val folderPath: List<String> = emptyList(),
    val parsedInfo: ParsedVideoName? = null,
    val size: Long = 0L
)

@Immutable
data class SeasonDetail(
    val posterUrl: String? = null,
    val overview: String? = null
)

@Immutable
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

@Immutable
data class TmdbSearchResult(
    val id: Int,
    val title: String,
    val year: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val overview: String,
    val rating: String?,
    val mediaType: String = "tv",
    val genreIds: List<Int> = emptyList(),
    val seasons: List<Int> = emptyList()
)

@Immutable
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
    val fileListSignature: String = "",
    val cachedVideos: List<QuarkFile> = emptyList(),
    // ===== 新增字段（全部有默认值，旧数据反序列化时为默认值，完全兼容）=====
    /** 剧目录相对路径，作为稳定身份主键；旧条目为空，靠 cachedVideos 反推迁移 */
    val seriesPath: String = "",
    /** 手动季号：fid -> 季。fid 变了也能靠文件名兜底 */
    val seasonOverrideByFid: Map<String, Int> = emptyMap(),
    /** 手动季号：规范化文件名 -> 季 */
    val seasonOverrideByName: Map<String, Int> = emptyMap()
)

/** 刮削任务：全量 or 只补新增集 */
private data class ScrapeTask(
    val series: MediaSeries,
    val full: Boolean,
    val newEpisodeKeys: Set<String> = emptySet()
)

class VideoScraperViewModel(
    private val videoSettingsRepository: VideoSettingsRepository,
    private val videoScrapedDao: VideoScrapedDao
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
    private val excludedFids = mutableSetOf<String>()
    
    private var lastQuarkError: String? = null
    private val scrapedCache = ConcurrentHashMap<String, ScrapeCacheItem>()
    private var isHandlingPendingRequest = false

    init {
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
        
        videoSettingsRepository.excludedLibraryFids.split(",").filter { it.isNotBlank() }.forEach {
            excludedFids.add(it)
        }
        
        _uiState.update {
            it.copy(
                cookie = videoSettingsRepository.quarkCookie,
                tmdbApiKey = videoSettingsRepository.tmdbApiKey,
                tmdbBaseUrl = videoSettingsRepository.tmdbBaseUrl.ifBlank { "https://tmdbimg.quarkalist.xyz" },
                selectedLibraryFids = fids,
                excludedLibraryFids = excludedFids.toSet(),
                isLibraryMode = true,
                downloadThreads = videoSettingsRepository.proxyThreads,
                bannerLogic = videoSettingsRepository.bannerLogic,
                categoryOrder = videoSettingsRepository.categoryOrder
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            if (scrapedCache.isEmpty()) {
                try {
                    val entities = videoScrapedDao.getAll()
                    if (entities.isNotEmpty()) {
                        entities.forEach { entity ->
                            val item = entity.toScrapeCacheItem()
                            scrapedCache[entity.id] = item
                        }
                        val cachedSeries = scrapedCache.map { (id, item) -> item.toMediaSeries(id) }
                            .sortedWith(compareBy(pinyinCollator) { it.title })
                        _uiState.update { it.copy(seriesList = cachedSeries, isLoading = false) }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            fetchQuarkFiles("0")
            if (fids.isNotEmpty()) loadLibraryVideos()
        }
    }

    // 🌟 新增：强力提取集数的兜底方法，解决 264.mp4 无法识别的问题
    fun extractEpisodeNumber(fileName: String, parsedEpisode: Int?): Int {
        if (parsedEpisode != null && parsedEpisode > 0) return parsedEpisode
        val seasonEpisodeMatch = Regex("(?i)S\\d+E(\\d+)").find(fileName)
        if (seasonEpisodeMatch != null) return seasonEpisodeMatch.groupValues[1].toIntOrNull() ?: 0
        val cnMatch = Regex("(?:第|EP|E)\\s*(\\d+)", RegexOption.IGNORE_CASE).find(fileName)
        if (cnMatch != null) return cnMatch.groupValues[1].toIntOrNull() ?: 0
        val cleanName = fileName.replace(Regex("\\d{3,4}[pP]", RegexOption.IGNORE_CASE), "").replace(Regex("\\d{4}"), "")
        val numMatch = Regex("(?<!\\d)(\\d{1,4})(?!\\d)").find(cleanName)
        return numMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun saveScrapedCache() {
        val entities = scrapedCache.map { (id, item) -> item.toEntity(id) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                videoScrapedDao.replaceAll(entities)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ================= 身份 / 集指纹 / 手改保护 / 删除清理（V3.6）=================
    // 设计原则：
    // 1) 身份 = 剧目录相对路径 + 原分组名（默认值兜底，旧数据可迁移）
    // 2) 变化判定只看"集键集合"（季_集）+ 文件大小，fid 只做快速命中，fid 单独变化不触发刮削
    // 3) 只补新增集，已刮过的集标题一律不重写
    // 4) 手动修改（含季号）永不被覆盖

    /** 规范化文件名：去扩展名/清晰度/编码/字幕组噪声，用于跨 fid 稳定识别 */
    private fun normalizeVideoName(name: String): String {
        var n = name.substringBeforeLast('.')
        n = n.replace(Regex("(?i)\\[[^\\]]*]"), " ")
        n = n.replace(Regex("(?i)\\([^)]*\\)"), " ")
        n = n.replace(
            Regex(
                "(?i)\\b(2160p|1080p|720p|480p|4k|8k|hdr|hdr10|dolby|dv|web-?dl|webrip|blu-?ray|bdrip|remux|hevc|h\\.?265|x265|h\\.?264|x264|aac|ac3|eac3|dts|flac|truehd|atmos|10bit|8bit|chs|cht|gb|big5)\\b"
            ), " "
        )
        n = n.replace(Regex("(?i)\\b(s\\d{1,2}e\\d{1,3}|ep?\\d{1,4}|第\\s*\\d+\\s*[集话期])\\b"), " ")
        n = n.replace(Regex("[\\s._\\-]+"), " ").trim()
        return n.ifBlank { name.substringBeforeLast('.') }
    }

    private fun seasonOf(file: QuarkFile): Int = file.parsedInfo?.season ?: 1

    private fun episodeOf(file: QuarkFile): Int =
        extractEpisodeNumber(file.name, file.parsedInfo?.episode)

    /** 集键：季_集；集号识别不出来时用规范化文件名兜底，避免多集塌成一个键 */
    private fun episodeKeyOf(file: QuarkFile): String {
        val s = seasonOf(file)
        val e = episodeOf(file)
        return if (e > 0) "${s}_${e}" else "${s}_n:${normalizeVideoName(file.name)}"
    }

    /** 剧目录路径键：取该剧所有文件 folderPath 的最长公共目录 */
    private fun seriesPathKeyOf(videos: List<QuarkFile>): String {
        val paths = videos.map { it.folderPath.filter { p -> p.isNotBlank() } }.filter { it.isNotEmpty() }
        if (paths.isEmpty()) return ""
        var common = paths.first().toList()
        for (p in paths) {
            var i = 0
            while (i < common.size && i < p.size && common[i] == p[i]) i++
            common = common.take(i)
        }
        return common.joinToString("/")
    }

    /** 缓存主键：路径 + 原分组名，二者都稳定；路径为空时退回原分组名（兼容旧数据） */
    private fun cacheKeyOf(seriesPath: String, groupTitle: String): String =
        if (seriesPath.isBlank()) groupTitle else "$seriesPath#$groupTitle"

    /** 集指纹（用于"搬家/改名"识别）：季_集 | 规范化名 | 大小 */
    private fun buildSeriesFingerprint(videos: List<QuarkFile>): String {
        if (videos.isEmpty()) return "fp:0"
        val raw = videos.map { "${episodeKeyOf(it)}|${normalizeVideoName(it.name)}|${it.size}" }
            .sorted().joinToString("\n")
        val digest = java.security.MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
        return "fp:" + digest.joinToString("") { String.format("%02x", it) }
    }

    private fun cachedEpisodeKeys(item: ScrapeCacheItem): Map<String, Long> =
        item.cachedVideos.associate { episodeKeyOf(it) to it.size }

    private fun newEpisodeKeys(videos: List<QuarkFile>): Map<String, Long> =
        videos.associate { episodeKeyOf(it) to it.size }

    /** 判定结果：不做 / 只补这些集 / 整部重刮 / 只删这些集缓存 */
    private data class ScrapeDecision(
        val mode: Int,               // 0=不刮 1=增量 2=全量
        val addedKeys: Set<String> = emptySet(),
        val removedKeys: Set<String> = emptySet()
    )

    private fun decideScrape(cached: ScrapeCacheItem?, videos: List<QuarkFile>): ScrapeDecision {
        if (cached == null) return ScrapeDecision(2)
        val oldMap = cachedEpisodeKeys(cached)
        val newMap = newEpisodeKeys(videos)
        if (oldMap.isEmpty() || newMap.isEmpty()) return ScrapeDecision(2)

        val oldKeys = oldMap.keys
        val newKeys = newMap.keys
        val removed = oldKeys - newKeys
        // 同集号但文件被替换（大小变）也算"这集需要重新刮"，但只补这些集
        val added = newKeys.filter { k ->
            val oldSize = oldMap[k]
            oldSize == null || (oldSize > 0L && newMap[k]!! > 0L && oldSize != newMap[k])
        }.toSet()

        // 大面积变化（重合度 < 50%）才视为换了片源，整部重刮
        val overlap = (oldKeys intersect newKeys).size.toDouble() / maxOf(oldKeys.size, newKeys.size)
        if (overlap < 0.5) return ScrapeDecision(2, added, removed)

        return when {
            added.isNotEmpty() -> ScrapeDecision(1, added, removed)
            removed.isNotEmpty() -> ScrapeDecision(0, emptySet(), removed)
            else -> ScrapeDecision(0)
        }
    }

    /** 应用手动季号（fid 与文件名双键，任一命中即用），旧缓存无此映射时原样返回 */
    private fun applySeasonOverrides(videos: List<QuarkFile>, cached: ScrapeCacheItem?): List<QuarkFile> {
        if (cached == null) return videos
        if (cached.seasonOverrideByFid.isEmpty() && cached.seasonOverrideByName.isEmpty()) return videos
        return videos.map { v ->
            val byName = cached.seasonOverrideByName[normalizeVideoName(v.name)]
            val byFid = cached.seasonOverrideByFid[v.fid]
            val target = byFid ?: byName
            if (target == null) v
            else v.copy(parsedInfo = (v.parsedInfo ?: ParsedVideoName(v.name)).copy(season = target))
        }
    }

    /**
     * 剧集标题解析（唯一入口，所有界面都走这里）：
     * 1) "季_集" 有值就用 —— 各季完全独立；
     * 2) 仅当整部剧只有一个季时，才回退到按集号存的旧表（兼容旧缓存）；
     * 3) 多季且没有该季标题 → 返回 null，界面显示"第N集"，绝不跨季借标题。
     */
    fun resolveEpisodeTitle(series: MediaSeries, season: Int, ep: Int): String? =
        resolveEpisodeTitleOf(series, season, ep)

    fun resolveEpisodeStill(series: MediaSeries, season: Int, ep: Int): String? =
        resolveEpisodeStillOf(series, season, ep)

    /** 删除该剧的全部本地数据：进度（按 fid）、片头片尾（按标题+季）、最近观看（按标题） */
    private fun purgeSeriesData(seriesTitle: String, fids: Collection<String>) {
        // 观看进度
        runCatching {
            val json = JSONObject(videoSettingsRepository.videoProgressData)
            fids.forEach { if (json.has(it)) json.remove(it) }
            videoSettingsRepository.videoProgressData = json.toString()
        }
        // 片头片尾：key 形如 "标题_S季"
        runCatching {
            val json = JSONObject(videoSettingsRepository.introOutroData)
            val removeKeys = json.keys().asSequence().filter { it.startsWith("${seriesTitle}_S") }.toList()
            removeKeys.forEach { json.remove(it) }
            videoSettingsRepository.introOutroData = json.toString()
        }
        // 最近观看
        runCatching {
            val arr = JSONArray(videoSettingsRepository.recentWatchedVideos)
            val keep = JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.optString("seriesTitle") != seriesTitle) keep.put(obj)
            }
            videoSettingsRepository.recentWatchedVideos = keep.toString()
        }
    }

    /** 缺失计数（连续 2 次才判删，避免网盘抖动误删） */
    private fun readMissMap(): MutableMap<String, Int> {
        val map = mutableMapOf<String, Int>()
        runCatching {
            val obj = JSONObject(videoSettingsRepository.scrapeMissData)
            obj.keys().forEach { k -> map[k] = obj.optInt(k, 0) }
        }
        return map
    }

    private fun writeMissMap(map: Map<String, Int>) {
        runCatching {
            val obj = JSONObject()
            map.forEach { (k, v) -> obj.put(k, v) }
            videoSettingsRepository.scrapeMissData = obj.toString()
        }
    }

    fun getPlaybackSpeed(): Float = videoSettingsRepository.playbackSpeed

    fun savePlaybackSpeed(speed: Float) {
        videoSettingsRepository.playbackSpeed = speed
    }

    private fun ScrapeCacheItem.toMediaSeries(id: String): MediaSeries {
        val year = cachedVideos.firstOrNull()?.parsedInfo?.year
        return MediaSeries(
            id = id,
            title = title,
            originalParsedTitle = title,
            year = year,
            category = category,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            overview = overview,
            rating = rating,
            videos = cachedVideos,
            seasonDetails = seasonDetails,
            episodeTitles = episodeTitles,
            episodeStills = episodeStills,
            seasonEpisodeTitles = seasonEpisodeTitles,
            seasonEpisodeStills = seasonEpisodeStills,
            groupName = groupName,
            isManuallyEdited = isManuallyEdited
        )
    }

    private fun VideoScrapedEntity.toScrapeCacheItem(): ScrapeCacheItem {
        var epTitles = mapOf<Int, String>()
        var epStills = mapOf<Int, String>()
        var seasonDetails = mapOf<Int, SeasonDetail>()
        var sEpTitles = mapOf<String, String>()
        var sEpStills = mapOf<String, String>()
        var videos = listOf<QuarkFile>()

        try {
            if (extraData.isNotBlank()) {
                val json = JSONObject(extraData)
                
                json.optJSONObject("episodeTitles")?.let { obj ->
                    val map = mutableMapOf<Int, String>()
                    obj.keys().forEach { k -> k.toIntOrNull()?.let { num -> map[num] = obj.getString(k) } }
                    epTitles = map
                }
                json.optJSONObject("episodeStills")?.let { obj ->
                    val map = mutableMapOf<Int, String>()
                    obj.keys().forEach { k -> k.toIntOrNull()?.let { num -> map[num] = obj.getString(k) } }
                    epStills = map
                }
                json.optJSONObject("seasonDetails")?.let { obj ->
                    val map = mutableMapOf<Int, SeasonDetail>()
                    obj.keys().forEach { k ->
                        k.toIntOrNull()?.let { num ->
                            val sObj = obj.getJSONObject(k)
                            map[num] = SeasonDetail(
                                posterUrl = sObj.optString("posterUrl").takeIf { it.isNotBlank() && it != "null" },
                                overview = sObj.optString("overview").takeIf { it.isNotBlank() && it != "null" }
                            )
                        }
                    }
                    seasonDetails = map
                }
                json.optJSONObject("seasonEpisodeTitles")?.let { obj ->
                    val map = mutableMapOf<String, String>()
                    obj.keys().forEach { k -> map[k] = obj.getString(k) }
                    sEpTitles = map
                }
                json.optJSONObject("seasonEpisodeStills")?.let { obj ->
                    val map = mutableMapOf<String, String>()
                    obj.keys().forEach { k -> map[k] = obj.getString(k) }
                    sEpStills = map
                }
                
                json.optJSONArray("cachedVideos")?.let { arr ->
                    val list = mutableListOf<QuarkFile>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val parsedInfoObj = obj.optJSONObject("parsedInfo")
                        val parsedInfo = if (parsedInfoObj != null) {
                            ParsedVideoName(
                                title = parsedInfoObj.optString("title"),
                                season = parsedInfoObj.optInt("season", 1),
                                episode = parsedInfoObj.optInt("episode", 0),
                                year = parsedInfoObj.optInt("year", 0).takeIf { it > 0 },
                                isVideo = true
                            )
                        } else null
                        val pathsArray = obj.optJSONArray("folderPath")
                        val paths = mutableListOf<String>()
                        if (pathsArray != null) {
                            for (j in 0 until pathsArray.length()) paths.add(pathsArray.getString(j))
                        }
                        list.add(QuarkFile(
                            fid = obj.optString("fid"),
                            name = obj.optString("name"),
                            isFolder = false,
                            category = obj.optString("category"),
                            folderPath = paths,
                            parsedInfo = parsedInfo,
                            size = obj.optLong("size", 0L)
                        ))
                    }
                    videos = list
                }
            }
        } catch (e: Exception) { e.printStackTrace() }

        // 新增字段（旧数据没有则为默认值，不报错、不丢旧字段）
        var seriesPath = ""
        var seasonByFid = mapOf<String, Int>()
        var seasonByName = mapOf<String, Int>()
        try {
            if (extraData.isNotBlank()) {
                val json = JSONObject(extraData)
                seriesPath = json.optString("seriesPath", "")
                json.optJSONObject("seasonOverrideByFid")?.let { obj ->
                    val map = mutableMapOf<String, Int>()
                    obj.keys().forEach { k -> map[k] = obj.optInt(k, 0) }
                    seasonByFid = map
                }
                json.optJSONObject("seasonOverrideByName")?.let { obj ->
                    val map = mutableMapOf<String, Int>()
                    obj.keys().forEach { k -> map[k] = obj.optInt(k, 0) }
                    seasonByName = map
                }
            }
        } catch (e: Exception) { e.printStackTrace() }

        return ScrapeCacheItem(
            title = title,
            category = category,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            overview = overview,
            rating = rating,
            seasonDetails = seasonDetails,
            episodeTitles = epTitles,
            episodeStills = epStills,
            seasonEpisodeTitles = sEpTitles,
            seasonEpisodeStills = sEpStills,
            groupName = groupName,
            isManuallyEdited = isManuallyEdited,
            fileListSignature = fileListSignature,
            cachedVideos = videos,
            seriesPath = seriesPath,
            seasonOverrideByFid = seasonByFid,
            seasonOverrideByName = seasonByName
        )
    }

    private fun ScrapeCacheItem.toEntity(id: String): VideoScrapedEntity {
        val extraObj = JSONObject()
        try {
            val epTitlesObj = JSONObject()
            episodeTitles.forEach { (k, v) -> epTitlesObj.put(k.toString(), v) }
            extraObj.put("episodeTitles", epTitlesObj)

            val epStillsObj = JSONObject()
            episodeStills.forEach { (k, v) -> epStillsObj.put(k.toString(), v) }
            extraObj.put("episodeStills", epStillsObj)

            val seasonDetailsObj = JSONObject()
            seasonDetails.forEach { (k, v) ->
                seasonDetailsObj.put(k.toString(), JSONObject().apply {
                    put("posterUrl", v.posterUrl ?: "")
                    put("overview", v.overview ?: "")
                })
            }
            extraObj.put("seasonDetails", seasonDetailsObj)

            val sEpTitlesObj = JSONObject()
            seasonEpisodeTitles.forEach { (k, v) -> sEpTitlesObj.put(k, v) }
            extraObj.put("seasonEpisodeTitles", sEpTitlesObj)

            val sEpStillsObj = JSONObject()
            seasonEpisodeStills.forEach { (k, v) -> sEpStillsObj.put(k, v) }
            extraObj.put("seasonEpisodeStills", sEpStillsObj)

            val videosArr = JSONArray()
            cachedVideos.forEach { file ->
                val fileObj = JSONObject().apply {
                    put("fid", file.fid)
                    put("name", file.name)
                    put("size", file.size)
                    put("category", file.category)
                    val pathsArr = JSONArray().apply { file.folderPath.forEach { put(it) } }
                    put("folderPath", pathsArr)
                    file.parsedInfo?.let { info ->
                        put("parsedInfo", JSONObject().apply {
                            put("title", info.title)
                            put("season", info.season)
                            put("episode", info.episode)
                            put("year", info.year ?: 0)
                        })
                    }
                }
                videosArr.put(fileObj)
            }
            extraObj.put("cachedVideos", videosArr)

            // 新增字段（与旧字段并存，旧的读取方仍可用）
            extraObj.put("seriesPath", seriesPath)
            val byFidObj = JSONObject()
            seasonOverrideByFid.forEach { (k, v) -> byFidObj.put(k, v) }
            extraObj.put("seasonOverrideByFid", byFidObj)
            val byNameObj = JSONObject()
            seasonOverrideByName.forEach { (k, v) -> byNameObj.put(k, v) }
            extraObj.put("seasonOverrideByName", byNameObj)
        } catch (e: Exception) { e.printStackTrace() }

        return VideoScrapedEntity(
            id = id,
            title = title,
            category = category,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            overview = overview,
            rating = rating,
            groupName = groupName,
            isManuallyEdited = isManuallyEdited,
            fileListSignature = fileListSignature,
            extraData = extraObj.toString()
        )
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
        val currentParentFid = _uiState.value.currentFid

        if (current.contains(fid)) {
            current.remove(fid)
            libraryNames.remove(fid)
            excludedFids.remove(fid)
        } else if (excludedFids.contains(fid)) {
            excludedFids.remove(fid)
        } else {
            if (current.contains(currentParentFid)) {
                excludedFids.add(fid)
            } else {
                current.add(fid)
                libraryNames[fid] = name
            }
        }

        val saveString = current.joinToString(",") { f -> "$f:${libraryNames[f] ?: ""}" }
        videoSettingsRepository.libraryFids = saveString
        videoSettingsRepository.excludedLibraryFids = excludedFids.joinToString(",")

        _uiState.update { it.copy(selectedLibraryFids = current, excludedLibraryFids = excludedFids.toSet()) }
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

        _uiState.update { it.copy(errorMsg = null) }

        viewModelScope.launch {
            try {
                val allVideos = mutableListOf<QuarkFile>()
                val loadedOkFids = mutableSetOf<String>()
                withContext(Dispatchers.IO) {
                    for (fid in libs) {
                        val folderName = libraryNames[fid] ?: ""
                        val initialPath = if (folderName.isNotBlank()) folderName.split("/") else emptyList()
                        val sizeBefore = allVideos.size
                        val ok = fetchVideosRecursively(cookie, fid, initialPath, allVideos)
                        if (ok) loadedOkFids.add(fid)
                        else if (allVideos.size > sizeBefore) {
                            // 部分成功：仍算不可信，本轮不做删除判定
                        }
                    }
                }
                val uniqueVideos = allVideos.distinctBy { it.fid }

                // 🌟 核心修复：防空结果误清空缓存
                if (uniqueVideos.isEmpty() && scrapedCache.isNotEmpty()) {
                    _uiState.update { it.copy(isLoading = false, isScraping = false, errorMsg = "网络异常：未获取到文件，已保留本地缓存") }
                    return@launch
                }

                val groupedSeries = aggregateToSeries(uniqueVideos)

                // ===== 旧缓存迁移：旧条目没有 seriesPath 时，用 cachedVideos 反推路径或按标题认领，
                // 认领后只补 seriesPath 字段，已刮的标题/海报/简介/手改标记全部保留，不触发重刮 =====
                if (scrapedCache.isNotEmpty()) {
                    val newIdSet = groupedSeries.map { it.id }.toSet()
                    scrapedCache.keys.filter { it !in newIdSet }.forEach { oldKey ->
                        val item = scrapedCache[oldKey] ?: return@forEach
                        val oldPath = if (item.seriesPath.isNotBlank()) item.seriesPath else seriesPathKeyOf(item.cachedVideos)
                        val target = groupedSeries.firstOrNull { s ->
                            val p = seriesPathKeyOf(s.videos)
                            (oldPath.isNotBlank() && p == oldPath) || s.title == item.title || s.title == oldKey
                        }
                        if (target != null && scrapedCache[target.id] == null) {
                            scrapedCache.remove(oldKey)
                            scrapedCache[target.id] = item.copy(
                                seriesPath = oldPath.ifBlank { seriesPathKeyOf(target.videos) }
                            )
                        }
                    }
                }

                val validIds = groupedSeries.map { it.id }.toSet()

                // ===== 删除判定：目录读取失败时整轮跳过（宁可晚删，不可误删）=====
                val anyLibFailed = libs.any { it !in loadedOkFids }
                if (!anyLibFailed) {
                    val missMap = readMissMap()
                    var missChanged = false
                    var cacheChanged = false
                    val invalidIds = scrapedCache.keys.filter { it !in validIds }
                    invalidIds.forEach { id ->
                        val item = scrapedCache[id]
                        val miss = (missMap[id] ?: 0) + 1
                        missChanged = true
                        if (miss >= 2) {
                            missMap.remove(id)
                            item?.let { purgeSeriesData(it.title, it.cachedVideos.map { v -> v.fid }) }
                            scrapedCache.remove(id)
                            cacheChanged = true
                        } else {
                            missMap[id] = miss
                        }
                    }
                    validIds.forEach { id -> if (missMap.remove(id) != null) missChanged = true }
                    if (missChanged) writeMissMap(missMap)
                    if (cacheChanged) saveScrapedCache()
                }

                val tasks = mutableListOf<ScrapeTask>()

                val finalSeriesList = groupedSeries.map { baseSeries ->
                    val pathKey = if (baseSeries.videos.isEmpty()) "" else seriesPathKeyOf(baseSeries.videos)
                    var cached = scrapedCache[baseSeries.id]

                    // 兼容老键：迁移后仍可能残留旧键，用路径/标题兜底认领
                    if (cached == null) {
                        val legacy = scrapedCache.entries.firstOrNull { (k, v) ->
                            val p = if (v.seriesPath.isNotBlank()) v.seriesPath else seriesPathKeyOf(v.cachedVideos)
                            (p.isNotBlank() && p == pathKey) || k == baseSeries.title || v.title == baseSeries.title
                        }
                        if (legacy != null) {
                            cached = legacy.value
                            scrapedCache.remove(legacy.key)
                            scrapedCache[baseSeries.id] = legacy.value
                        }
                    }

                    // 手动季号在扫描结果之上生效，且缓存里的 cachedVideos 也用它 → 不会被下次扫描覆盖
                    val videosFixed = applySeasonOverrides(baseSeries.videos, cached)
                    val series = baseSeries.copy(videos = videosFixed)

                    val decision = decideScrape(cached, videosFixed)

                    // 集被删掉时，同步清掉对应的标题/剧照键，避免残留脏数据
                    var merged = cached
                    if (cached != null && decision.removedKeys.isNotEmpty()) {
                        val seasonTitles = cached.seasonEpisodeTitles.toMutableMap()
                        val seasonStills = cached.seasonEpisodeStills.toMutableMap()
                        val epTitles = cached.episodeTitles.toMutableMap()
                        val epStills = cached.episodeStills.toMutableMap()
                        decision.removedKeys.forEach { key ->
                            seasonTitles.remove(key)
                            seasonStills.remove(key)
                            val epNum = key.substringAfter("_").toIntOrNull()
                            if (epNum != null) {
                                epTitles.remove(epNum)
                                epStills.remove(epNum)
                            }
                        }
                        merged = cached.copy(
                            seasonEpisodeTitles = seasonTitles,
                            seasonEpisodeStills = seasonStills,
                            episodeTitles = epTitles,
                            episodeStills = epStills
                        )
                    }

                    val resultSeries = if (merged != null) {
                        series.copy(
                            title = merged.title, category = merged.category, posterUrl = merged.posterUrl,
                            backdropUrl = merged.backdropUrl, overview = merged.overview, rating = merged.rating,
                            seasonDetails = merged.seasonDetails, episodeTitles = merged.episodeTitles,
                            episodeStills = merged.episodeStills, seasonEpisodeTitles = merged.seasonEpisodeTitles,
                            seasonEpisodeStills = merged.seasonEpisodeStills, groupName = merged.groupName,
                            isManuallyEdited = merged.isManuallyEdited
                        )
                    } else series

                    when (decision.mode) {
                        2 -> tasks.add(ScrapeTask(series, true))
                        1 -> tasks.add(ScrapeTask(series, false, decision.addedKeys))
                        else -> Unit
                    }

                    if (decision.mode == 0) {
                        // 本轮不需要刮削：直接把当前文件集落盘（下次只按增量判断，不再全量比对）
                        val carry = merged ?: ScrapeCacheItem(
                            title = resultSeries.title, category = resultSeries.category,
                            posterUrl = null, backdropUrl = null, overview = null, rating = null,
                            groupName = resultSeries.groupName, isManuallyEdited = false
                        )
                        scrapedCache[baseSeries.id] = carry.copy(
                            seriesPath = pathKey,
                            fileListSignature = buildSeriesFingerprint(videosFixed),
                            cachedVideos = videosFixed
                        )
                    } else if (merged != null) {
                        // 有刮削任务在跑：只先更新路径，集列表等刮削成功后再落盘，失败则下一轮重试
                        scrapedCache[baseSeries.id] = merged.copy(seriesPath = pathKey)
                    }

                    resultSeries
                }.sortedWith(compareBy(pinyinCollator) { it.title })

                _uiState.update { it.copy(seriesList = finalSeriesList) }
                
                if (apiKey.isNotBlank() && tasks.isNotEmpty()) {
                    _uiState.update { it.copy(isScraping = true) }
                    scrapeSeriesPosters(tasks, apiKey, _uiState.value.tmdbBaseUrl)
                } else {
                    hasLoadedLibrary = true
                    _uiState.update { it.copy(isLoading = false, isScraping = false) }
                    handlePendingPlayRequest()
                    saveScrapedCache() 
                }

            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, isScraping = false, errorMsg = "扫描异常: ${e.message}") }
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
            val finalParsedInfo = parsed.copy(season = folderSeason ?: 1)
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
        // 身份主键：剧目录相对路径 + 原分组名（路径稳定、不受网盘命名随意影响；改分组逻辑与以前完全一致）
        return seriesMap.values.map { s ->
            val path = seriesPathKeyOf(s.videos)
            s.copy(id = cacheKeyOf(path, s.title))
        }.sortedWith(compareBy(pinyinCollator) { it.title })
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

    /** 返回是否"完整读取成功"；失败会被上层用来跳过本轮删除判定，避免网盘抖动误删 */
    private suspend fun fetchVideosRecursively(
        cookie: String, parentFid: String, currentPath: List<String>, resultList: MutableList<QuarkFile>
    ): Boolean = withContext(Dispatchers.IO) {
        var allOk = true
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
                                        if (!excludedFids.contains(fileFid)) {
                                            val childOk = fetchVideosRecursively(cookie, fileFid, currentPath + fileName, resultList)
                                            if (!childOk) allOk = false
                                        }
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
                            allOk = false
                            hasMore = false
                        }
                    } else {
                        allOk = false
                        hasMore = false
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                allOk = false
                hasMore = false
            }
        }
        allOk
    }

    private fun scrapeSeriesPosters(tasks: List<ScrapeTask>, apiKey: String, baseUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val scrapedList = tasks.map { task ->
                tmdbSemaphore.withPermit {
                    val series = task.series
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
                        
                        val epTitles = mutableMapOf<Int, String>()
                        val epStills = mutableMapOf<Int, String>()
                        val seasonDetails = mutableMapOf<Int, SeasonDetail>()
                        val sEpTitles = mutableMapOf<String, String>()
                        val sEpStills = mutableMapOf<String, String>()

                        val oldCached = scrapedCache[series.id]
                        if (oldCached != null) {
                            epTitles.putAll(oldCached.episodeTitles)
                            epStills.putAll(oldCached.episodeStills)
                            seasonDetails.putAll(oldCached.seasonDetails)
                            sEpTitles.putAll(oldCached.seasonEpisodeTitles)
                            sEpStills.putAll(oldCached.seasonEpisodeStills)
                        }

                        if (match.mediaType != "movie") {
                            val allSeasons = series.videos.map { seasonOf(it) }.distinct().sorted()
                            val targetSeasons = if (task.full) {
                                if (allSeasons.isEmpty()) listOf(1) else allSeasons
                            } else {
                                task.newEpisodeKeys.mapNotNull { it.substringBefore("_").toIntOrNull() }
                                    .distinct().sorted().ifEmpty { allSeasons }
                            }
                            for (season in targetSeasons) {
                                val detail = fetchSeasonDetail(match.id, season, apiKey, baseUrl)
                                if (detail != null) {
                                    seasonDetails[season] = detail.first
                                    if (task.full) {
                                        sEpTitles.putAll(detail.second)
                                        sEpStills.putAll(detail.third)
                                    } else {
                                        // 增量：只补新增集，已刮过的集标题一个都不重写
                                        detail.second.forEach { (k, v) -> if (task.newEpisodeKeys.contains(k)) sEpTitles[k] = v }
                                        detail.third.forEach { (k, v) -> if (task.newEpisodeKeys.contains(k)) sEpStills[k] = v }
                                    }
                                    // 旧字段（按集号）只在"整部剧只有一个季"时才写，避免多季串标题
                                    if (allSeasons.size <= 1) {
                                        detail.second.forEach { (k, v) ->
                                            val epNum = k.substringAfter("_").toIntOrNull()
                                            if (epNum != null && (task.full || task.newEpisodeKeys.contains(k))) epTitles[epNum] = v
                                        }
                                        detail.third.forEach { (k, v) ->
                                            val epNum = k.substringAfter("_").toIntOrNull()
                                            if (epNum != null && (task.full || task.newEpisodeKeys.contains(k))) epStills[epNum] = v
                                        }
                                    }
                                }
                            }
                        }

                        val isEdited = oldCached?.isManuallyEdited ?: false

                        val newSeries = if (task.full) {
                            series.copy(
                                title = if (isEdited) oldCached!!.title else finalTitle,
                                category = if (isEdited) oldCached!!.category else finalCategory,
                                posterUrl = if (isEdited) oldCached!!.posterUrl else match.posterUrl,
                                backdropUrl = if (isEdited) oldCached!!.backdropUrl else match.backdropUrl,
                                overview = if (isEdited) oldCached!!.overview else match.overview,
                                rating = if (isEdited) oldCached!!.rating else match.rating,
                                seasonDetails = seasonDetails, episodeTitles = epTitles, episodeStills = epStills,
                                seasonEpisodeTitles = sEpTitles, seasonEpisodeStills = sEpStills
                            )
                        } else {
                            // 增量：剧级字段（标题/分类/海报/背景/简介/评分）一律不动，只把新集补上
                            series.copy(
                                title = oldCached?.title ?: series.title,
                                category = oldCached?.category ?: series.category,
                                posterUrl = oldCached?.posterUrl ?: series.posterUrl,
                                backdropUrl = oldCached?.backdropUrl ?: series.backdropUrl,
                                overview = oldCached?.overview ?: series.overview,
                                rating = oldCached?.rating ?: series.rating,
                                seasonDetails = seasonDetails, episodeTitles = epTitles, episodeStills = epStills,
                                seasonEpisodeTitles = sEpTitles, seasonEpisodeStills = sEpStills
                            )
                        }

                        scrapedCache[series.id] = ScrapeCacheItem(
                            title = newSeries.title, category = newSeries.category, posterUrl = newSeries.posterUrl,
                            backdropUrl = newSeries.backdropUrl, overview = newSeries.overview, rating = newSeries.rating,
                            seasonDetails = newSeries.seasonDetails, episodeTitles = newSeries.episodeTitles,
                            episodeStills = newSeries.episodeStills, seasonEpisodeTitles = newSeries.seasonEpisodeTitles,
                            seasonEpisodeStills = newSeries.seasonEpisodeStills, groupName = newSeries.groupName,
                            isManuallyEdited = isEdited,
                            fileListSignature = buildSeriesFingerprint(newSeries.videos),
                            cachedVideos = newSeries.videos,
                            seriesPath = oldCached?.seriesPath ?: series.id.substringBeforeLast("#", ""),
                            seasonOverrideByFid = oldCached?.seasonOverrideByFid ?: emptyMap(),
                            seasonOverrideByName = oldCached?.seasonOverrideByName ?: emptyMap()
                        )
                        newSeries
                    } else {
                        val oldCached = scrapedCache[series.id]
                        if (oldCached == null) {
                            scrapedCache[series.id] = ScrapeCacheItem(
                                title = series.title, category = series.category, posterUrl = null, backdropUrl = null,
                                overview = null, rating = null, groupName = series.groupName,
                                isManuallyEdited = false,
                                fileListSignature = buildSeriesFingerprint(series.videos),
                                cachedVideos = series.videos,
                                seriesPath = series.id.substringBeforeLast("#", "")
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
                        
                        val seasonsList = mutableListOf<Int>()
                        val seasonsArray = bestItem.optJSONArray("seasons")
                        if (seasonsArray != null) {
                            for (k in 0 until seasonsArray.length()) {
                                val seasonNum = seasonsArray.getJSONObject(k).optInt("season_number", -1)
                                if (seasonNum > 0) seasonsList.add(seasonNum)
                            }
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
                            mediaType = mediaType, genreIds = genreIdsList,
                            seasons = seasonsList
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

    fun fetchSeriesSeasons(tvId: Int, onResult: (List<Int>) -> Unit) {
        val apiKey = _uiState.value.tmdbApiKey
        val baseUrl = _uiState.value.tmdbBaseUrl
        if (apiKey.isBlank()) { onResult(emptyList()); return }
        viewModelScope.launch(Dispatchers.IO) {
            val list = mutableListOf<Int>()
            try {
                val isProxy = baseUrl.contains("quarkalist") || baseUrl.contains("proxy")
                val apiPath = if (isProxy) "/api/3/tv/$tvId" else "/3/tv/$tvId"
                val url = "$baseUrl$apiPath?api_key=$apiKey&language=zh-CN"
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    if (!body.isNullOrBlank()) {
                        val json = JSONObject(body)
                        val seasonsArray = json.optJSONArray("seasons")
                        if (seasonsArray != null) {
                            for (k in 0 until seasonsArray.length()) {
                                val seasonNum = seasonsArray.getJSONObject(k).optInt("season_number", -1)
                                if (seasonNum > 0) list.add(seasonNum)
                            }
                        }
                    }
                }
            } catch (e: Exception) {}
            withContext(Dispatchers.Main) { onResult(list) }
        }
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
                                val mediaType = item.optString("media_type", "tv")
                                
                                val seasonsList = mutableListOf<Int>()
                                val seasonsArray = item.optJSONArray("seasons")
                                if (seasonsArray != null) {
                                    for (k in 0 until seasonsArray.length()) {
                                        val seasonNum = seasonsArray.getJSONObject(k).optInt("season_number", -1)
                                        if (seasonNum > 0) seasonsList.add(seasonNum)
                                    }
                                }
                                
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
                                        rating = if (voteAverage > 0) String.format("%.1f", voteAverage) else null,
                                        mediaType = mediaType,
                                        seasons = seasonsList
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

    fun fetchTmdbImages(tvId: Int, mediaType: String, onResult: (List<String>, List<String>) -> Unit) {
        val apiKey = _uiState.value.tmdbApiKey
        val baseUrl = _uiState.value.tmdbBaseUrl
        if (apiKey.isBlank()) return
        
        viewModelScope.launch(Dispatchers.IO) {
            val posters = mutableListOf<String>()
            val backdrops = mutableListOf<String>()
            try {
                val isProxy = baseUrl.contains("quarkalist") || baseUrl.contains("proxy")
                val apiPath = if (isProxy) "/api/3/$mediaType/$tvId/images" else "/3/$mediaType/$tvId/images"
                val url = "$baseUrl$apiPath?api_key=$apiKey&language=zh-CN"
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    if (!body.isNullOrBlank()) {
                        val json = JSONObject(body)
                        
                        val postersArray = json.optJSONArray("posters")
                        if (postersArray != null) {
                            for (i in 0 until postersArray.length()) {
                                val path = postersArray.getJSONObject(i).optString("file_path")
                                if (path.isNotBlank() && path != "null") {
                                    posters.add(if (isProxy) "$baseUrl/img/t/p/w500$path" else "https://image.tmdb.org/t/p/w500$path")
                                }
                            }
                        }
                        
                        val backdropsArray = json.optJSONArray("backdrops")
                        if (backdropsArray != null) {
                            for (i in 0 until backdropsArray.length()) {
                                val path = backdropsArray.getJSONObject(i).optString("file_path")
                                if (path.isNotBlank() && path != "null") {
                                    backdrops.add(if (isProxy) "$baseUrl/img/t/p/w780$path" else "https://image.tmdb.org/t/p/w780$path")
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {}
            
            withContext(Dispatchers.Main) {
                onResult(posters, backdrops)
            }
        }
    }

    fun applyManualScrape(
        seriesId: String, 
        result: TmdbSearchResult?, 
        newCategory: String, 
        newTitle: String, 
        customPoster: String? = null, 
        customBackdrop: String? = null,
        targetSeason: Int? = null
    ) {
        val apiKey = _uiState.value.tmdbApiKey
        val baseUrl = _uiState.value.tmdbBaseUrl

        val currentList = _uiState.value.seriesList.map {
            if (it.id == seriesId) {
                val updatedVideos = if (targetSeason != null) {
                    it.videos.map { video ->
                        video.copy(parsedInfo = (video.parsedInfo ?: ParsedVideoName(video.name)).copy(season = targetSeason))
                    }
                } else it.videos

                it.copy(
                    title = result?.title ?: newTitle, 
                    posterUrl = customPoster ?: result?.posterUrl ?: it.posterUrl,
                    backdropUrl = customBackdrop ?: result?.backdropUrl ?: it.backdropUrl,
                    overview = result?.overview ?: it.overview, 
                    rating = result?.rating ?: it.rating, 
                    category = newCategory,
                    isManuallyEdited = true,
                    videos = updatedVideos
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
                val cachedBefore = scrapedCache[seriesId]
                // 先继承旧表，再只覆盖本次操作的那一季 → 各季独立、互不影响
                val seasonTitles = cachedBefore?.seasonEpisodeTitles?.toMutableMap() ?: mutableMapOf()
                val seasonEpisodeStills = cachedBefore?.seasonEpisodeStills?.toMutableMap() ?: mutableMapOf()
                val episodeTitles = cachedBefore?.episodeTitles?.toMutableMap() ?: mutableMapOf()
                val episodeStills = cachedBefore?.episodeStills?.toMutableMap() ?: mutableMapOf()
                val seasonDetails = cachedBefore?.seasonDetails?.toMutableMap() ?: mutableMapOf()

                val allSeasons = updatedSeries.videos.map { seasonOf(it) }.distinct().sorted()
                val targetSeasons = when {
                    targetSeason != null -> listOf(targetSeason)
                    allSeasons.isEmpty() -> listOf(1)
                    else -> allSeasons
                }

                // 本季要覆盖的键集合（只覆盖本季，其它季一个都不碰）
                val seasonKeys = mutableSetOf<String>()
                val seasonEpNums = mutableSetOf<Int>()

                for (season in targetSeasons) {
                    val detail = tmdbSemaphore.withPermit {
                        fetchSeasonDetail(result.id, season, apiKey, baseUrl)
                    }
                    if (detail != null) {
                        seasonDetails[season] = detail.first
                        detail.second.forEach { (k, v) ->
                            if (k.substringBefore("_").toIntOrNull() == season) {
                                seasonTitles[k] = v
                                seasonKeys.add(k)
                                k.substringAfter("_").toIntOrNull()?.let { seasonEpNums.add(it) }
                            }
                        }
                        detail.third.forEach { (k, v) ->
                            if (k.substringBefore("_").toIntOrNull() == season) seasonEpisodeStills[k] = v
                        }
                    }
                }

                // 旧字段（按集号）只在"整部剧只有一个季"时写，避免多季串标题
                if (allSeasons.size <= 1) {
                    seasonKeys.forEach { k ->
                        val epNum = k.substringAfter("_").toIntOrNull()
                        val title = seasonTitles[k]
                        if (epNum != null && title != null) episodeTitles[epNum] = title
                        val still = seasonEpisodeStills[k]
                        if (epNum != null && still != null) episodeStills[epNum] = still
                    }
                }

                val finalSeries = updatedSeries.copy(
                    seasonDetails = seasonDetails,
                    seasonEpisodeTitles = seasonTitles,
                    episodeTitles = episodeTitles,
                    seasonEpisodeStills = seasonEpisodeStills,
                    episodeStills = episodeStills
                )

                withContext(Dispatchers.Main) {
                    val newList = _uiState.value.seriesList.map {
                        if (it.id == seriesId) finalSeries else it
                    }.sortedWith(compareBy(pinyinCollator) { it.title })
                    
                    _uiState.update {
                        it.copy(seriesList = newList, currentSeries = finalSeries)
                    }
                }

                saveManualScrapeComplete(finalSeries, targetSeason)
            }
        } else {
            saveManualScrapeBasic(updatedSeries, targetSeason)
        }
    }

    /** 手动刮削落盘：保留旧的全表数据，写入手改季号双键（fid + 文件名），供后续扫描时套用 */
    private fun buildManualCacheItem(
        series: MediaSeries,
        targetSeason: Int?
    ): ScrapeCacheItem {
        val old = scrapedCache[series.id]
        val byFid = (old?.seasonOverrideByFid ?: emptyMap()).toMutableMap()
        val byName = (old?.seasonOverrideByName ?: emptyMap()).toMutableMap()
        if (targetSeason != null) {
            series.videos.forEach { v ->
                byFid[v.fid] = targetSeason
                byName[normalizeVideoName(v.name)] = targetSeason
            }
        }
        return ScrapeCacheItem(
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
            fileListSignature = buildSeriesFingerprint(series.videos),
            cachedVideos = series.videos,
            seriesPath = old?.seriesPath ?: series.id.substringBeforeLast("#", ""),
            seasonOverrideByFid = byFid,
            seasonOverrideByName = byName
        )
    }

    private fun saveManualScrapeBasic(series: MediaSeries, targetSeason: Int? = null) {
        scrapedCache[series.id] = buildManualCacheItem(series, targetSeason)
        saveScrapedCache()
    }

    private fun saveManualScrapeComplete(series: MediaSeries, targetSeason: Int? = null) {
        scrapedCache[series.id] = buildManualCacheItem(series, targetSeason)
        saveScrapedCache()
    }

    fun playVideo(context: Context, series: MediaSeries, file: QuarkFile) {
        val realSeries = if (series.id.startsWith("temp_")) {
            _uiState.value.seriesList.find { s -> s.videos.any { it.fid == file.fid } } ?: series
        } else series

        val cookie = _uiState.value.cookie
        
        val parsedInfo = file.parsedInfo ?: VideoFileNameParser.parse(file.name)
        
        // 🌟 核心修复：使用兜底方法提取集数，解决 264.mp4 等命名未识别问题
        val epNum = extractEpisodeNumber(file.name, parsedInfo.episode).takeIf { it > 0 }
        val season = parsedInfo.season ?: 1
        
        var titleText = realSeries.title
        if (realSeries.category != "电影") {
            if (epNum != null) {
                val tmdbEpTitle = resolveEpisodeTitle(realSeries, season, epNum)
                titleText = if (!tmdbEpTitle.isNullOrBlank()) "${realSeries.title} 第${epNum}集 $tmdbEpTitle"
                else "${realSeries.title} 第${epNum}集"
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
            val epNum = extractEpisodeNumber(file.name, parsedInfo.episode).takeIf { it > 0 }
            val season = parsedInfo.season ?: 1
            
            var titleText = series.title
            if (series.category != "电影") {
                if (epNum != null) {
                    val tmdbEpTitle = resolveEpisodeTitle(series, season, epNum)
                    titleText = if (!tmdbEpTitle.isNullOrBlank()) "${series.title} 第${epNum}集 $tmdbEpTitle"
                    else "${series.title} 第${epNum}集"
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

    fun getPlayUrl(fid: String): String? {
        val cookie = _uiState.value.cookie
        return fetchDirectDownloadUrl(fid, cookie)
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
    val isLoading: Boolean = true,
    val isScraping: Boolean = false, 
    val errorMsg: String? = null,
    val fileList: List<QuarkFile> = emptyList(),
    val isLibraryMode: Boolean = true,
    val selectedLibraryFids: Set<String> = emptySet(),
    val excludedLibraryFids: Set<String> = emptySet(),
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