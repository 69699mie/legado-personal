package io.legado.app.data

import android.content.Context
import io.legado.app.data.entities.VideoScrapedEntity
import io.legado.app.ui.main.video.MediaSeries
import io.legado.app.ui.main.video.QuarkFile
import io.legado.app.ui.main.video.SeasonDetail
import io.legado.app.utils.ParsedVideoName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

object VideoCacheManager {
    @Volatile
    var cachedSeries: List<MediaSeries>? = null
        private set

    @Volatile
    var isReady: Boolean = false
        private set

    suspend fun preload(context: Context) {
        if (isReady) return
        withContext(Dispatchers.IO) {
            try {
                // 🌟 加入 3 秒超时兜底机制，防卡死
                val result = withTimeoutOrNull(3000L) {
                    val dao = appDb.videoScrapedDao
                    val entities = dao.getAll()
                    val list = mutableListOf<MediaSeries>()
                    for (entity in entities) {
                        val series = entity.toMediaSeries()
                        list.add(series)
                    }
                    list.sortedBy { it.title }
                }
                cachedSeries = result ?: emptyList()
            } catch (e: Exception) {
                e.printStackTrace()
                cachedSeries = emptyList()
            } finally {
                isReady = true
            }
        }
    }

    // 🌟 数据发生变化时（如刮削完成、手动修改），更新内存缓存
    fun updateCache(newList: List<MediaSeries>) {
        cachedSeries = newList
    }

    // 辅助解析方法（从 Entity 解析为 MediaSeries）
    private fun VideoScrapedEntity.toMediaSeries(): MediaSeries {
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

        return MediaSeries(
            id = id,
            title = title,
            originalParsedTitle = title,
            year = null,
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
            videos = videos
        )
    }
}