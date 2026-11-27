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

    // 🌟 新增：被单独排除的子目录列表
    var excludedLibraryFids: String
        get() = prefs.getString("excluded_library_fids", "") ?: ""
        set(value) { prefs.edit().putString("excluded_library_fids", value).apply() }

    var introOutroData: String
        get() = prefs.getString("intro_outro_data", "{}") ?: "{}"
        set(value) { prefs.edit().putString("intro_outro_data", value).apply() }

    var videoProgressData: String
        get() = prefs.getString("video_progress_data", "{}") ?: "{}"
        set(value) { prefs.edit().putString("video_progress_data", value).apply() }

    /**
     * 每集片头片尾（绝对毫秒），键为 fid：
     * {"fid": {"introEndMs":150000,"outroStartMs":2400000,"ep":"标题|S1|E3","src":"manual|auto","conf":0.9,"t":123}}
     * 老数据仍留在 introOutroData（按“标题_S季”），作为没有集级记录时的回退。
     */
    var introOutroEpisodeData: String
        get() = prefs.getString("intro_outro_episode", "{}") ?: "{}"
        set(value) { prefs.edit().putString("intro_outro_episode", value).apply() }

    /** 自动识别片头片尾（默认关）。关掉时完全走手动/旧季级设置。 */
    var autoIntroOutroEnabled: Boolean
        get() = prefs.getBoolean("auto_intro_outro", false)
        set(value) { prefs.edit().putBoolean("auto_intro_outro", value).apply() }

    var proxyThreads: Int
        get() = prefs.getInt("proxy_threads", 6)
        set(value) { prefs.edit().putInt("proxy_threads", value).apply() }

    var scrapedCacheData: String
        get() = prefs.getString("scraped_cache_data", "") ?: ""
        set(value) { prefs.edit().putString("scraped_cache_data", value).apply() }

    var recentWatchedVideos: String
        get() = prefs.getString("recent_watched_videos", "[]") ?: "[]"
        set(value) { prefs.edit().putString("recent_watched_videos", value).apply() }

    var totalVideoWatchTime: Long
        get() = prefs.getLong("total_video_watch_time", 0L)
        set(value) { prefs.edit().putLong("total_video_watch_time", value).apply() }

    var pendingPlayFid: String?
        get() = prefs.getString("pending_play_fid", null)
        set(value) { prefs.edit().putString("pending_play_fid", value).apply() }

    var bannerLogic: String
        get() = prefs.getString("banner_logic", "智能推荐") ?: "智能推荐"
        set(value) { prefs.edit().putString("banner_logic", value).apply() }

    var categoryOrder: String
        get() = prefs.getString("category_order", "最近观看,剧集,电影,动漫") ?: "最近观看,剧集,电影,动漫"
        set(value) { prefs.edit().putString("category_order", value).apply() }

    // 🌟 新增：记住用户上次选择的播放倍速（切集/重进播放页不再回到 1.0x）
    var playbackSpeed: Float
        get() = prefs.getFloat("playback_speed", 1.0f).coerceIn(0.5f, 3.0f)
        set(value) { prefs.edit().putFloat("playback_speed", value.coerceIn(0.5f, 3.0f)).apply() }

    // 🌟 新增：刮削缓存的"连续缺失次数"，用于软删除（避免网盘列表抖动导致重刮）
    var scrapeMissData: String
        get() = prefs.getString("scrape_miss_data", "{}") ?: "{}"
        set(value) { prefs.edit().putString("scrape_miss_data", value).apply() }

    // 🌟 新增：空海报重试的退避记录（id -> "次数:下次时间"）
    var scrapeRetryData: String
        get() = prefs.getString("scrape_retry_data", "{}") ?: "{}"
        set(value) { prefs.edit().putString("scrape_retry_data", value).apply() }
}