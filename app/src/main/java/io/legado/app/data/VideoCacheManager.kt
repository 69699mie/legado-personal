package io.legado.app.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.legado.app.ui.main.video.MediaSeries
import java.lang.reflect.Type

object VideoCacheManager {

    @Volatile
    var isReady = false
        private set

    @Volatile
    var cachedSeries: List<MediaSeries>? = null
        private set

    private const val PREFS_NAME = "video_cache_prefs"
    private const val KEY_SNAPSHOT = "video_library_snapshot"
    private val gson = Gson()
    private val type: Type = object : TypeToken<List<MediaSeries>>() {}.type

    /**
     * 在 Application.onCreate 中调用。
     * 同步读取 SharedPreferences 中的 JSON 快照，耗时极短（毫秒级），不会阻塞首帧。
     */
    fun preload(context: Context) {
        if (isReady) return
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_SNAPSHOT, null)
            if (!json.isNullOrBlank()) {
                cachedSeries = gson.fromJson(json, type)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isReady = true
        }
    }

    /**
     * 后台异步更新内存及 SharedPreferences 快照，供下次冷启动使用
     */
    suspend fun updateCache(series: List<MediaSeries>) {
        cachedSeries = series
        try {
            val json = gson.toJson(series, type)
            // 使用 apply() 异步写入磁盘，不阻塞当前线程
            val prefs = getPrefs()
            prefs?.edit()?.putString(KEY_SNAPSHOT, json)?.apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private var appContext: Context? = null

    fun setContext(context: Context) {
        appContext = context.applicationContext
    }

    private fun getPrefs(): SharedPreferences? {
        return appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}