package io.legado.app.utils

data class ParsedVideoName(
    val title: String,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val isVideo: Boolean = false
)

object VideoFileNameParser {

    private val videoExts = listOf(".mp4", ".mkv", ".avi", ".ts", ".m3u8", ".mov", ".rmvb", ".flv")

    // 借鉴 PTN 的规则链思路，定义需要剥离的噪声模式
    private val noisePatterns = listOf(
        Regex("(?i)\\b(2160p|1080p|720p|480p|4k|8k)\\b"),
        Regex("(?i)\\b(hdr|sdr|dv|dolby\\s*vision)\\b"),
        Regex("(?i)\\b(bluray|bdrip|web-?dl|webrip|hdtv|dvdrip)\\b"),
        Regex("(?i)\\b(x264|x265|h264|h265|hevc|avc)\\b"),
        Regex("(?i)\\b(aac|ac3|dts|ddp?\\d?\\.?\\d?|atmos|truehd)\\b"),
        Regex("(?i)\\b(10bit|8bit|60fps|hfr)\\b"),
        Regex("\\[.*?\\]|\\(.*?\\)|【.*?】"), // 移除各种括号及其内容
        Regex("(?i)(电影|电视剧|动漫|动画|纪录片|综艺|剧场版|臻彩)") // 移除分类词
    )

    fun parse(fileName: String): ParsedVideoName {
        val lowerName = fileName.lowercase()
        val isVideo = videoExts.any { lowerName.endsWith(it) }
        
        var name = fileName

        // 1. 剥离后缀
        videoExts.forEach { if (lowerName.endsWith(it)) { name = name.dropLast(it.length) } }

        // 2. 提取季集 (S01E01, 第1季第1集)
        var season: Int? = null
        var episode: Int? = null
        val seRegex = Regex("(?i)s(\\d{1,2})e(\\d{1,3})")
        seRegex.find(name)?.let {
            season = it.groupValues[1].toIntOrNull()
            episode = it.groupValues[2].toIntOrNull()
            name = name.replace(it.value, " ")
        } ?: run {
            val eRegex = Regex("(?i)(?:^|[^a-z])e(\\d{1,3})(?:[^a-z]|$)")
            eRegex.find(name)?.let {
                episode = it.groupValues[1].toIntOrNull()
                name = name.replace(it.value, " ")
            }
        }

        // 3. 提取年份
        var year: Int? = null
        val yearRegex = Regex("(19|20)\\d{2}")
        yearRegex.find(name)?.let {
            year = it.value.toIntOrNull()
            name = name.replace(it.value, " ")
        }

        // 4. 应用噪声清洗规则链
        noisePatterns.forEach { pattern ->
            name = name.replace(pattern, " ")
        }

        // 5. 最终清理
        name = name.replace(Regex("\\s+"), " ").trim()
        if (name.isBlank()) name = fileName.substringBeforeLast(".")

        return ParsedVideoName(name, year, season, episode, isVideo)
    }
}