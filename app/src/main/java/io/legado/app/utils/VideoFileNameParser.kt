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
        Regex("(?i)\\b(hdr|sdr|dv|dolby|vision|hdr10\\+?)\\b"),
        Regex("(?i)\\b(bluray|bdrip|web-?dl|webrip|hdtv|dvdrip|remux)\\b"),
        Regex("(?i)\\b(x264|x265|h264|h265|hevc|avc)\\b"),
        Regex("(?i)\\b(aac|ac3|dts\\d*[\\s\\d]*|ddp?\\d*[\\s\\d]*|atmos|truehd|flac|falc\\d*[\\s\\d]*)\\b"),
        Regex("(?i)\\b(10bit|8bit|60fps|25fps|24fps|120fps|hfr|hifi)\\b"),
        Regex("(?i)\\b(audios|audio|video|hq|hd|ma|5 1|7 1|2 0)\\b"),
        Regex("(?i)\\b(iTunes|QuickIO|PandaQT|臻彩)\\b")
    )

    // ➕ 提取中文辅助函数
    private fun keepChineseOnly(text: String): String {
        val chinesePattern = Regex("[\\u4e00-\\u9fa5]+")
        val matches = chinesePattern.findAll(text).map { it.value }.toList()
        return if (matches.isNotEmpty()) {
            matches.joinToString("")
        } else {
            text // 如果没有任何中文，保留原样（比如纯英文电影）
        }
    }

    fun parse(fileName: String, parentFolder: String? = null): ParsedVideoName {
        val lowerName = fileName.lowercase()
        val isVideo = videoExts.any { lowerName.endsWith(it) }
        
        var rawName = fileName
        if (!parentFolder.isNullOrBlank()) {
            val ignoreFolders = listOf("电影", "动漫", "电视剧", "纪录片", "综艺", "第一季", "第二季", "第三季", "Season 1", "Season 2")
            
            // ➕ 拼接前，先把父文件夹名强行过一遍纯中文提取
            val cleanParent = keepChineseOnly(parentFolder)
            
            if (!ignoreFolders.contains(parentFolder) && !fileName.contains(cleanParent)) {
                rawName = "$cleanParent $fileName"
            }
        }

        var name = rawName
        videoExts.forEach { if (name.lowercase().endsWith(it)) { name = name.dropLast(it.length) } }

        var season: Int? = null
        var episode: Int? = null
        
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

        // ➕ 终极净化：如果存在中文，剔除所有英文和多余符号
        name = keepChineseOnly(name)
        
        // 去除重复
        val words = name.split(" ")
        val uniqueWords = mutableListOf<String>()
        for (w in words) {
            if (!uniqueWords.contains(w) && w.isNotBlank()) {
                uniqueWords.add(w)
            }
        }
        name = uniqueWords.joinToString("").trim() // 中文字符之间不需要空格

        if (name.isBlank() || name.toIntOrNull() != null) {
            name = if (!parentFolder.isNullOrBlank()) keepChineseOnly(parentFolder) else fileName.substringBeforeLast(".")
        }

        return ParsedVideoName(name, year, season, episode, isVideo)
    }
}