package io.legado.app.utils

data class ParsedVideoName(
    val title: String,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val isVideo: Boolean = false
)

object VideoFileNameParser {

    // 支持的视频后缀
    private val videoExts = listOf(".mp4", ".mkv", ".avi", ".ts", ".m3u8", ".mov", ".rmvb", ".flv")

    fun parse(fileName: String): ParsedVideoName {
        val lowerName = fileName.lowercase()
        val isVideo = videoExts.any { lowerName.endsWith(it) }
        
        var name = fileName

        // 1. 剥离后缀
        videoExts.forEach {
            if (lowerName.endsWith(it)) {
                name = name.dropLast(it.length)
            }
        }

        // 2. 提取季集 (例如 S01E01, 第一季第一集)
        var season: Int? = null
        var episode: Int? = null
        val seRegex = Regex("(?i)s(\\d{1,2})e(\\d{1,3})")
        val seMatch = seRegex.find(name)
        if (seMatch != null) {
            season = seMatch.groupValues[1].toIntOrNull()
            episode = seMatch.groupValues[2].toIntOrNull()
            // 把 S01E01 这部分从名字里删掉
            name = name.replace(seMatch.value, " ")
        } else {
            // 尝试匹配单纯的 E01
            val eRegex = Regex("(?i)(?:^|[^a-z])e(\\d{1,3})(?:[^a-z]|$)")
            val eMatch = eRegex.find(name)
            if (eMatch != null) {
                episode = eMatch.groupValues[1].toIntOrNull()
                name = name.replace(eMatch.value, " ")
            }
        }

        // 3. 提取年份 (19xx 或 20xx)
        var year: Int? = null
        val yearRegex = Regex("(19|20)\\d{2}")
        val yearMatch = yearRegex.find(name)
        if (yearMatch != null) {
            year = yearMatch.value.toIntOrNull()
            name = name.replace(yearMatch.value, " ")
        }

        // 4. 剔除常见的画质、来源、编码等干扰词
        val noiseRegex = Regex("(?i)(2160p|1080p|720p|4k|8k|hdr|web-dl|bluray|bdrip|hevc|x264|x265|aac|h264|60fps|10bit)")
        name = name.replace(noiseRegex, " ")

        // 5. 清理中括号、前缀和多余的符号
        name = name.replace(Regex("[\\[\\]【】()（）]"), " ") // 括号换成空格
        name = name.replace(Regex("(?i)(电影|电视剧|动漫|动画|纪录片|综艺)"), " ") // 去掉分类词
        
        // 将多个空格合并为一个，并去掉首尾空格
        name = name.replace(Regex("\\s+"), " ").trim()
        
        // 如果清洗完变成了空字符串，就退回用原文件名
        if (name.isBlank()) {
            name = fileName.substringBeforeLast(".")
        }

        return ParsedVideoName(name, year, season, episode, isVideo)
    }
}