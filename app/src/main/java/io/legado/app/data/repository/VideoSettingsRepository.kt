package io.legado.app.data.repository

import android.content.Context
import android.content.SharedPreferences

class VideoSettingsRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("video_settings", Context.MODE_PRIVATE)

    var quarkCookie: String
        get() = prefs.getString("quark_cookie", "") ?: ""
        set(value) { prefs.edit().putString("quark_cookie", value).apply() }

    var tmdbApiKey: String
        get() = prefs.getString("tmdb_api_key", "") ?: ""
        set(value) { prefs.edit().putString("tmdb_api_key", value).apply() }

    var tmdbBaseUrl: String
        get() = prefs.getString("tmdb_base_url", "https://api.tmdb.org") ?: "https://api.tmdb.org"
        set(value) { prefs.edit().putString("tmdb_base_url", value).apply() }

    var libraryFids: String
        get() = prefs.getString("library_fids", "") ?: ""
        set(value) { prefs.edit().putString("library_fids", value).apply() }

    var introOutroData: String
        get() = prefs.getString("intro_outro_data", "{}") ?: "{}"
        set(value) { prefs.edit().putString("intro_outro_data", value).apply() }

    var videoProgressData: String
        get() = prefs.getString("video_progress_data", "{}") ?: "{}"
        set(value) { prefs.edit().putString("video_progress_data", value).apply() }

    var proxyThreads: Int
        get() = prefs.getInt("proxy_threads", 6)
        set(value) { prefs.edit().putInt("proxy_threads", value).apply() }

    var scrapedCacheData: String
        get() = prefs.getString("scraped_cache_data", "") ?: ""
        set(value) { prefs.edit().putString("scraped_cache_data", value).apply() }

    // 【新增】最近观看的影视记录（JSON格式）
    var recentWatchedVideos: String
        get() = prefs.getString("recent_watched_videos", "[]") ?: "[]"
        set(value) { prefs.edit().putString("recent_watched_videos", value).apply() }

    // 【新增】跨页面播放指令：当从首页点击影视记录时，存入此字段，VideoRouteScreen 检测到后自动播放
    var pendingPlayFid: String?
        get() = prefs.getString("pending_play_fid", null)
        set(value) { prefs.edit().putString("pending_play_fid", value).apply() }
}