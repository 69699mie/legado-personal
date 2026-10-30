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

    var tmdbApiKey: String
        get() = prefs.getString("tmdb_api_key", "") ?: ""
        set(value) {
            prefs.edit().putString("tmdb_api_key", value).apply()
        }

    // ➕ 新增：保存选中作为媒体库的文件夹 FID 列表 (逗号分隔)
    var libraryFids: String
        get() = prefs.getString("library_fids", "") ?: ""
        set(value) {
            prefs.edit().putString("library_fids", value).apply()
        }
}