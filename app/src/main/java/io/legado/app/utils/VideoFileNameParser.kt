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

    // 增强的噪声清洗规则
    private val noisePatterns = listOf(
        Regex("(?i)\\b(2160p|1080p|720p|480p|4k|8k)\\b"),
        Regex("(?i)\\b(hdr|sdr|dv|dolby\\s*vision)\\b"),
        Regex("(?i)\\b(bluray|bdrip|web-?dl|webrip|hdtv|dvdrip|remux)\\b"),
        Regex("(?i)\\b(x264|x265|h264|h265|hevc|avc)\\b"),
        Regex("(?i)\\b(aac|ac3|dts|ddp?\\d?\\.?\\d?|atmos|truehd|flac)\\b"),
        Regex("(?i)\\b(10bit|8bit|60fps|hfr|hifi)\\b"),
        Regex("(?i)\\b(iTunes|QuickIO|PandaQT|臻彩)\\b"), // 常见压制组或平台
        Regex("\\[.*?\\]|\\(.*?\\)|【.*?】"), // 移除各种括号及其内容
        Regex("(?i)(电影|电视剧|动漫|动画|纪录片|综艺|剧场版)") 
    )

    fun parse(fileName: String): ParsedVideoName {
        val lowerName = fileName.lowercase()
        val isVideo = videoExts.any { lowerName.endsWith(it) }
        
        var name = fileName

        videoExts.forEach { if (lowerName.endsWith(it)) { name = name.dropLast(it.length) } }

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

        var year: Int? = null
        val yearRegex = Regex("(19|20)\\d{2}")
        yearRegex.find(name)?.let {
            year = it.value.toIntOrNull()
            name = name.replace(it.value, " ")
        }

        noisePatterns.forEach { pattern ->
            name = name.replace(pattern, " ")
        }

        // 处理诸如 "The.Legend.of.Hei." 这种点号分割的情况
        name = name.replace(".", " ")
        // 处理多余的下划线和横杠
        name = name.replace(Regex("[_\\-]"), " ")

        name = name.replace(Regex("\\s+"), " ").trim()
        if (name.isBlank()) name = fileName.substringBeforeLast(".")

        return ParsedVideoName(name, year, season, episode, isVideo)
    }
}