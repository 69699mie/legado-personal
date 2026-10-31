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

    private val noiseWords = listOf(
        "2160p", "1080p", "720p", "480p", "4k", "8k", "hdr", "sdr", "dv", "dolby", "vision",
        "bluray", "bdrip", "web-dl", "webrip", "hdtv", "dvdrip", "remux", "x264", "x265",
        "h264", "h265", "hevc", "avc", "aac", "ac3", "dts", "ddp", "atmos", "truehd", "flac",
        "10bit", "8bit", "60fps", "25fps", "24fps", "120fps", "hfr", "hifi", "audios", "audio",
        "video", "hq", "hd", "ma", "itunes", "quickio", "pandaqt", "臻彩", "cuva", "vivid", "web"
    )

    fun parse(fileName: String, parentFolder: String? = null): ParsedVideoName {
        val lowerName = fileName.lowercase()
        val isVideo = videoExts.any { lowerName.endsWith(it) }
        
        var name = fileName
        videoExts.forEach { if (name.lowercase().endsWith(it)) { name = name.dropLast(it.length) } }

        // 1. 提取年份
        var year: Int? = null
        val yearRegex = Regex("(19|20)\\d{2}")
        yearRegex.find(name)?.let {
            year = it.value.toIntOrNull()
            name = name.replace(it.value, " ")
        }

        // 2. 提取季数 (S01)
        var season: Int? = null
        val seRegex = Regex("(?i)S(\\d{1,2})")
        seRegex.find(name)?.let {
            season = it.groupValues[1].toIntOrNull()
            name = name.replace(it.value, " ")
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

        // 3. 提取集数 (极其严谨，防误杀)
        var episode: Int? = null
        
        // 3.1 优先匹配标准格式 S01E02 或 E02
        val eRegex = Regex("(?i)E(\\d{1,3})")
        eRegex.find(name)?.let {
            episode = it.groupValues[1].toIntOrNull()
            name = name.replace(it.value, " ")
        }
        
        // 3.2 匹配中文格式 第2集、第2话
        if (episode == null) {
            val cnEpRegex = Regex("第(\\d{1,3})[集话]")
            cnEpRegex.find(name)?.let {
                episode = it.groupValues[1].toIntOrNull()
                name = name.replace(it.value, " ")
            }
        }

        // 3.3 匹配下划线或空格边界格式 (如 _02_ 或 02 在末尾)
        if (episode == null) {
            // 匹配 [ _-]02[ _-] 或 结尾的 02
            val boundaryEpRegex = Regex("(?:^|[ _-])(\\d{1,3})(?:[ _-]|$)")
            val matches = boundaryEpRegex.findAll(name).toList()
            // 从后往前找，因为集数通常靠后
            for (match in matches.reversed()) {
                val num = match.groupValues[1].toIntOrNull()
                if (num != null) {
                    // 黑名单防御：不能是年份，不能是分辨率
                    val isYear = num in 1900..2099
                    val isResolution = num in listOf(480, 720, 1080, 2160, 264, 265, 360)
                    if (!isYear && !isResolution) {
                        episode = num
                        name = name.replace(match.value, " ")
                        break
                    }
                }
            }
        }

        // 4. 清理无意义标签
        var cleanName = name.replace(Regex("[._\\-\\[\\]()【】]"), " ")
        
        cleanName = cleanName.split(" ")
            .filter { word ->
                val w = word.trim()
                w.isNotEmpty() && 
                !noiseWords.contains(w.lowercase()) && 
                w.toIntOrNull() == null && 
                !w.matches(Regex("^[a-zA-Z]{1,2}$")) 
            }
            .joinToString(" ")

        // 5. 终极兜底：使用父文件夹名
        if (cleanName.isBlank() || cleanName.length < 2) {
            if (!parentFolder.isNullOrBlank() && parentFolder != "电影" && parentFolder != "动漫" && parentFolder != "电视剧") {
                cleanName = parentFolder
            } else {
                cleanName = "未知影视"
            }
        }

        return ParsedVideoName(cleanName, year, season, episode, isVideo)
    }
}