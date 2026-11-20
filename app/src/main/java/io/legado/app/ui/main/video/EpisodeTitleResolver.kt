package io.legado.app.ui.main.video

/**
 * 剧集标题/剧照解析的唯一入口，所有界面与播放器都走这里，避免多处逻辑不一致。
 *
 * 规则（用户明确要求）：
 * 1) "季_集" 有值就直接用 —— 各季完全独立；
 * 2) 只有"整部剧只有一个季"时，才回退到旧的按集号表（兼容旧缓存）；
 * 3) 多季且没有该季标题 → 返回 null，界面显示"第N集"，绝不跨季借标题。
 */
internal fun resolveEpisodeTitleOf(series: MediaSeries, season: Int, ep: Int): String? {
    if (ep <= 0) return null
    series.seasonEpisodeTitles["${season}_${ep}"]?.takeIf { it.isNotBlank() }?.let { return it }
    val seasons = series.videos.map { it.parsedInfo?.season ?: 1 }.distinct()
    if (seasons.size <= 1) return series.episodeTitles[ep]?.takeIf { it.isNotBlank() }
    return null
}

internal fun resolveEpisodeStillOf(series: MediaSeries, season: Int, ep: Int): String? {
    if (ep <= 0) return null
    series.seasonEpisodeStills["${season}_${ep}"]?.takeIf { it.isNotBlank() }?.let { return it }
    val seasons = series.videos.map { it.parsedInfo?.season ?: 1 }.distinct()
    if (seasons.size <= 1) return series.episodeStills[ep]?.takeIf { it.isNotBlank() }
    return null
}
