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

    // 片头片尾
    var introOutroData: String
        get() = prefs.getString("intro_outro_data", "{}") ?: "{}"
        set(value) { prefs.edit().putString("intro_outro_data", value).apply() }

    // 播放进度记录（JSON: {"fid":"position_ms"}）
    var videoProgressData: String
        get() = prefs.getString("video_progress_data", "{}") ?: "{}"
        set(value) { prefs.edit().putString("video_progress_data", value).apply() }
}