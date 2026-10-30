package io.legado.app.data.repository

import android.content.Context
import android.content.SharedPreferences

class VideoSettingsRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("video_settings", Context.MODE_PRIVATE)

    var quarkCookie: String
        get() = prefs.getString("quark_cookie", "") ?: ""
        set(value) {
            prefs.edit().putString("quark_cookie", value).apply()
        }

    // ➕ 新增：TMDB API Key 存储
    var tmdbApiKey: String
        get() = prefs.getString("tmdb_api_key", "") ?: ""
        set(value) {
            prefs.edit().putString("tmdb_api_key", value).apply()
        }
}