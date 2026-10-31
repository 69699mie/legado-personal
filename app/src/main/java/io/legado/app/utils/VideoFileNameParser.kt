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

    private val noisePatterns = listOf(
        Regex("\\[.*?\\]|\\(.*?\\)|【.*?】"), 
        Regex("(?i)\\b(2160p|1080p|720p|480p|4k|8k)\\b"),
        Regex("(?i)\\b(hdr|sdr|dv|dolby|vision)\\b"),
        Regex("(?i)\\b(bluray|bdrip|web-?dl|webrip|hdtv|dvdrip|remux)\\b"),
        Regex("(?i)\\b(x264|x265|h264|h265|hevc|avc)\\b"),
        Regex("(?i)\\b(aac|ac3|dts|ddp?\\d?|atmos|truehd|flac)\\b"),
        Regex("(?i)\\b(10bit|8bit|60fps|25fps|24fps|120fps|hfr|hifi)\\b"),
        Regex("(?i)\\b(audios|audio|video|hq|hd)\\b"),
        Regex("(?i)\\b(iTunes|QuickIO|PandaQT|臻彩)\\b")
    )

    // 支持传入父文件夹名称进行辅助解析
    fun parse(fileName: String, parentFolder: String? = null): ParsedVideoName {
        val lowerName = fileName.lowercase()
        val isVideo = videoExts.any { lowerName.endsWith(it) }
        
        // 预处理：如果文件名叫 2026.2160p... 且父文件夹名为 恶意，拼起来就是：恶意 2026.2160p...
        var rawName = fileName
        if (parentFolder != null && parentFolder.isNotBlank()) {
            // 排除掉无意义的父文件夹名
            val ignoreFolders = listOf("电影", "动漫", "电视剧", "纪录片", "综艺", "第一季", "第二季", "第三季", "Season 1", "Season 2")
            if (!ignoreFolders.contains(parentFolder)) {
                rawName = "$parentFolder $fileName"
            }
        }

        var name = rawName

        videoExts.forEach { if (name.lowercase().endsWith(it)) { name = name.dropLast(it.length) } }

        var season: Int? = null
        var episode: Int? = null
        
        // 提取季集
        val seRegex = Regex("(?i)S(\\d{1,2})E(\\d{1,3})")
        seRegex.find(name)?.let {
            season = it.groupValues[1].toIntOrNull()
            episode = it.groupValues[2].toIntOrNull()
            name = name.replace(it.value, " ")
        } ?: run {
            val eRegex = Regex("(?i)(?:^|[^a-z])E(\\d{1,3})(?:[^a-z]|$)")
            eRegex.find(name)?.let {
                episode = it.groupValues[1].toIntOrNull()
                name = name.replace(it.value, " ")
            }
            val cnEpRegex = Regex("第(\\d{1,3})[集话]")
            cnEpRegex.find(name)?.let {
                episode = it.groupValues[1].toIntOrNull()
                name = name.replace(it.value, " ")
            }
        }
        
        val cnSeasonRegex = Regex("第(\\d{1,2}|[一二三四五六七八九十])季")
        cnSeasonRegex.find(name)?.let {
            val sStr = it.groupValues[1]
            season = when(sStr) {
                "一" -> 1; "二" -> 2; "三" -> 3; "四" -> 4; "五" -> 5
                "六" -> 6; "七" -> 7; "八" -> 8; "九" -> 9; "十" -> 10
                else -> sStr.toIntOrNull()
            }
            name = name.replace(it.value, " ")
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

        name = name.replace(Regex("[._\\-]"), " ")
        name = name.replace(Regex("\\s+"), " ").trim()
        
        // 如果清洗完太短或者全是数字，保底用原文件名或父文件夹
        if (name.isBlank() || name.toIntOrNull() != null || name.length < 2) {
            name = if (!parentFolder.isNullOrBlank()) parentFolder else fileName.substringBeforeLast(".")
        }

        return ParsedVideoName(name, year, season, episode, isVideo)
    }
}