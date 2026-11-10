package io.legado.app.data

import android.content.Context
import androidx.room.Room
import com.tencent.mmkv.MMKV
import io.legado.app.data.entities.VideoScrapedEntity
import io.legado.app.ui.main.video.MediaSeries
import io.legado.app.ui.main.video.QuarkFile
import io.legado.app.ui.main.video.SeasonDetail
import io.legado.app.utils.ParsedVideoName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object VideoCacheManager {

    @Volatile
    var isReady = false
        private set

    @Volatile
    var cachedSeries: List<MediaSeries>? = null
        private set

    private const val MMKV_KEY = "video_library_snapshot"

    /**
     * 在 Application.onCreate 中调用。
     * 同步读取 MMKV 内存映射快照，耗时在毫秒级，不阻塞首帧。
     */
    fun preload(context: Context) {
        if (isReady) return
        try {
            // MMKV 基于 mmap，初次读取极快，适合在 Application 启动阶段获取首屏快照
            MMKV.initialize(context)
            val kv = MMKV.defaultMMKV()
            val jsonStr = kv.decodeString(MMKV_KEY)
            if (!jsonStr.isNullOrBlank()) {
                cachedSeries = parseSnapshot(jsonStr)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isReady = true
        }
    }

    /**
     * 后台异步更新内存及 MMKV 快照，供下次冷启动使用
     */
    suspend fun updateCache(series: List<MediaSeries>) {
        withContext(Dispatchers.IO) {
            cachedSeries = series
            try {
                val kv = MMKV.defaultMMKV()
                kv.encode(MMKV_KEY, buildSnapshot(series))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun buildSnapshot(seriesList: List<MediaSeries>): String {
        val root = JSONArray()
        seriesList.forEach { series ->
            val obj = JSONObject().apply {
                put("id", series.id)
                put("title", series.title)
                put("originalParsedTitle", series.originalParsedTitle)
                put("year", series.year ?: JSONObject.NULL)
                put("category", series.category)
                put("posterUrl", series.posterUrl ?: JSONObject.NULL)
                put("backdropUrl", series.backdropUrl ?: JSONObject.NULL)
                put("overview", series.overview ?: JSONObject.NULL)
                put("rating", series.rating ?: JSONObject.NULL)
                put("groupName", series.groupName ?: JSONObject.NULL)
                put("isManuallyEdited", series.isManuallyEdited)

                val videosArr = JSONArray()
                series.videos.forEach { file ->
                    val vObj = JSONObject().apply {
                        put("fid", file.fid)
                        put("name", file.name)
                        put("isFolder", file.isFolder)
                        put("category", file.category)
                        put("size", file.size)
                        put("folderPath", JSONArray(file.folderPath))
                        file.parsedInfo?.let { info ->
                            put("parsedInfo", JSONObject().apply {
                                put("title", info.title)
                                put("season", info.season)
                                put("episode", info.episode)
                                put("year", info.year ?: 0)
                                put("isVideo", info.isVideo)
                            })
                        }
                    }
                    videosArr.put(vObj)
                }
                put("videos", videosArr)

                val sdObj = JSONObject()
                series.seasonDetails.forEach { (k, v) ->
                    sdObj.put(k.toString(), JSONObject().apply {
                        put("posterUrl", v.posterUrl ?: JSONObject.NULL)
                        put("overview", v.overview ?: JSONObject.NULL)
                    })
                }
                put("seasonDetails", sdObj)

                val epTitlesObj = JSONObject()
                series.episodeTitles.forEach { (k, v) -> epTitlesObj.put(k.toString(), v) }
                put("episodeTitles", epTitlesObj)

                val epStillsObj = JSONObject()
                series.episodeStills.forEach { (k, v) -> epStillsObj.put(k.toString(), v) }
                put("episodeStills", epStillsObj)

                val sEpTitlesObj = JSONObject()
                series.seasonEpisodeTitles.forEach { (k, v) -> sEpTitlesObj.put(k, v) }
                put("seasonEpisodeTitles", sEpTitlesObj)

                val sEpStillsObj = JSONObject()
                series.seasonEpisodeStills.forEach { (k, v) -> sEpStillsObj.put(k, v) }
                put("seasonEpisodeStills", sEpStillsObj)
            }
            root.put(obj)
        }
        return root.toString()
    }

    private fun parseSnapshot(jsonStr: String): List<MediaSeries> {
        val list = mutableListOf<MediaSeries>()
        try {
            val root = JSONArray(jsonStr)
            for (i in 0 until root.length()) {
                val obj = root.getJSONObject(i)
                val videosArr = obj.optJSONArray("videos")
                val videos = mutableListOf<QuarkFile>()
                if (videosArr != null) {
                    for (j in 0 until videosArr.length()) {
                        val vObj = videosArr.getJSONObject(j)
                        val folderPathArr = vObj.optJSONArray("folderPath")
                        val folderPath = mutableListOf<String>()
                        if (folderPathArr != null) {
                            for (k in 0 until folderPathArr.length()) {
                                folderPath.add(folderPathArr.getString(k))
                            }
                        }
                        var parsedInfo: ParsedVideoName? = null
                        val pObj = vObj.optJSONObject("parsedInfo")
                        if (pObj != null) {
                            parsedInfo = ParsedVideoName(
                                title = pObj.optString("title"),
                                season = pObj.optInt("season", 1),
                                episode = pObj.optInt("episode", 0),
                                year = pObj.optInt("year", 0).takeIf { it > 0 },
                                isVideo = pObj.optBoolean("isVideo", true)
                            )
                        }
                        videos.add(
                            QuarkFile(
                                fid = vObj.optString("fid"),
                                name = vObj.optString("name"),
                                isFolder = vObj.optBoolean("isFolder", false),
                                category = vObj.optString("category"),
                                size = vObj.optLong("size", 0L),
                                folderPath = folderPath,
                                parsedInfo = parsedInfo
                            )
                        )
                    }
                }

                val sdObj = obj.optJSONObject("seasonDetails")
                val seasonDetails = mutableMapOf<Int, SeasonDetail>()
                sdObj?.keys()?.forEach { k ->
                    k.toIntOrNull()?.let { keyInt ->
                        val item = sdObj.getJSONObject(k)
                        seasonDetails[keyInt] = SeasonDetail(
                            posterUrl = item.optString("posterUrl").takeIf { it.isNotBlank() && it != "null" },
                            overview = item.optString("overview").takeIf { it.isNotBlank() && it != "null" }
                        )
                    }
                }

                val epTitlesMap = mutableMapOf<Int, String>()
                obj.optJSONObject("episodeTitles")?.let { mapObj ->
                    mapObj.keys().forEach { k ->
                        k.toIntOrNull()?.let { epTitlesMap[it] = mapObj.optString(k) }
                    }
                }

                val epStillsMap = mutableMapOf<Int, String>()
                obj.optJSONObject("episodeStills")?.let { mapObj ->
                    mapObj.keys().forEach { k ->
                        k.toIntOrNull()?.let { epStillsMap[it] = mapObj.optString(k) }
                    }
                }

                val sEpTitlesMap = mutableMapOf<String, String>()
                obj.optJSONObject("seasonEpisodeTitles")?.let { mapObj ->
                    mapObj.keys().forEach { k -> sEpTitlesMap[k] = mapObj.optString(k) }
                }

                val sEpStillsMap = mutableMapOf<String, String>()
                obj.optJSONObject("seasonEpisodeStills")?.let { mapObj ->
                    mapObj.keys().forEach { k -> sEpStillsMap[k] = mapObj.optString(k) }
                }

                list.add(
                    MediaSeries(
                        id = obj.optString("id"),
                        title = obj.optString("title"),
                        originalParsedTitle = obj.optString("originalParsedTitle"),
                        year = obj.optInt("year", 0).takeIf { it > 0 },
                        category = obj.optString("category"),
                        posterUrl = obj.optString("posterUrl").takeIf { it.isNotBlank() && it != "null" },
                        backdropUrl = obj.optString("backdropUrl").takeIf { it.isNotBlank() && it != "null" },
                        overview = obj.optString("overview").takeIf { it.isNotBlank() && it != "null" },
                        rating = obj.optString("rating").takeIf { it.isNotBlank() && it != "null" },
                        groupName = obj.optString("groupName").takeIf { it.isNotBlank() && it != "null" },
                        isManuallyEdited = obj.optBoolean("isManuallyEdited", false),
                        videos = videos,
                        seasonDetails = seasonDetails,
                        episodeTitles = epTitlesMap,
                        episodeStills = epStillsMap,
                        seasonEpisodeTitles = sEpTitlesMap,
                        seasonEpisodeStills = sEpStillsMap
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }
}