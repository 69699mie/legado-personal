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

    // 全面扩充噪音词
    private val noiseWords = listOf(
        "2160p", "1080p", "720p", "480p", "4k", "8k", "hdr", "sdr", "dv", "dolby", "vision",
        "bluray", "bdrip", "web-dl", "webrip", "hdtv", "dvdrip", "remux", "x264", "x265",
        "h264", "h265", "hevc", "avc", "aac", "ac3", "dts", "ddp", "atmos", "truehd", "flac",
        "10bit", "8bit", "60fps", "25fps", "24fps", "120fps", "hfr", "hifi", "audios", "audio",
        "video", "hq", "hd", "ma", "itunes", "quickio", "pandaqt", "臻彩", "cuva", "vivid", "web",
        "edr", "3audios", "4audios", "ddp2", "ddp5", "2audios", "1blacktv", "blacktv", "hq"
    )

    fun isSeasonFolder(folderName: String): Boolean {
        val lower = folderName.lowercase()
        return lower.matches(Regex("第([0-9一二三四五六七八九十]+)季")) || 
               lower.matches(Regex("season\\s*\\d+")) || 
               lower.matches(Regex("s\\d{1,2}"))
    }

    fun parse(fileName: String, parentFolder: String? = null): ParsedVideoName {
        val lowerName = fileName.lowercase()
        val isVideo = videoExts.any { lowerName.endsWith(it) }
        
        var name = fileName
        videoExts.forEach { if (name.lowercase().endsWith(it)) { name = name.dropLast(it.length) } }

        var season: Int? = null
        var episode: Int? = null
        
        // 1. 优先匹配 SxxExx 格式
        val seRegex = Regex("(?i)S(\\d{1,2})E(\\d{1,3})")
        seRegex.find(name)?.let {
            season = it.groupValues[1].toIntOrNull()
            episode = it.groupValues[2].toIntOrNull()
            name = name.replace(it.value, " ")
        } ?: run {
            // 2. 匹配 EPxx 格式
            val eRegex = Regex("(?i)(?:^|[^a-z])EP?(\\d{1,3})(?:[^a-z]|$)")
            eRegex.find(name)?.let {
                episode = it.groupValues[1].toIntOrNull()
                name = name.replace(it.value, " ")
            }
            
            // 3. 匹配 第xx集/话/期
            val cnEpRegex = Regex("第(\\d{1,3})[集话期]")
            cnEpRegex.find(name)?.let {
                episode = it.groupValues[1].toIntOrNull()
                name = name.replace(it.value, " ")
            }
            
            // 4. 终极海阔视界同款：剔除干扰后，找纯数字作为集数
            if (episode == null) {
                // 预清理：去掉年份、分辨率后缀，防止它们被误认为集数
                var cleanForNum = name
                    .replace(Regex("(19|20)\\d{2}"), " ") // 剔除年份
                    .replace(Regex("(?i)\\d{3,4}p"), " ") // 剔除 1080p 等
                    .replace(Regex("(?i)\\d{3,4}k"), " ") // 剔除 4k 等
                    .replace(Regex("(?i)[xh]26[45]"), " ") // 剔除 x264, h265
                    .replace(Regex("(?i)ddp\\d(?:\\.\\d)?"), " ") // 剔除 ddp5.1
                    .replace(Regex("(?i)\\d+audios?"), " ") // 剔除 2audios

                // 找剩下的所有数字
                val allNums = Regex("\\d+").findAll(cleanForNum).map { it.value }.toList()
                val excludeNums = setOf("720", "1080", "2160", "480", "360", "264", "265", "1440", "4320", "60", "25", "24", "120", "10", "8")
                
                for (numStr in allNums) {
                    val num = numStr.toIntOrNull() ?: continue
                    if (excludeNums.contains(numStr)) continue
                    // 正常的集数通常在 1 到 999 之间
                    if (num in 1..999) {
                        episode = num
                        name = name.replaceFirst(numStr, " ")
                        break
                    }
                }
            }
        }
        
        // 提取季数
        val cnSeasonRegex = Regex("第([0-9一二三四五六七八九十]+)季")
        cnSeasonRegex.find(name)?.let {
            val sStr = it.groupValues[1]
            season = when(sStr) {
                "一" -> 1; "二" -> 2; "三" -> 3; "四" -> 4; "五" -> 5
                "六" -> 6; "七" -> 7; "八" -> 8; "九" -> 9; "十" -> 10
                else -> sStr.toIntOrNull()
            }
            name = name.replace(it.value, " ")
        }

        if (season == null && parentFolder != null) {
            cnSeasonRegex.find(parentFolder)?.let {
                val sStr = it.groupValues[1]
                season = when(sStr) {
                    "一" -> 1; "二" -> 2; "三" -> 3; "四" -> 4; "五" -> 5
                    "六" -> 6; "七" -> 7; "八" -> 8; "九" -> 9; "十" -> 10
                    else -> sStr.toIntOrNull()
                }
            }
        }

        // 提取年份
        var year: Int? = null
        val yearRegex = Regex("(19|20)\\d{2}")
        yearRegex.find(name)?.let {
            year = it.value.toIntOrNull()
            name = name.replace(it.value, " ")
        }

        // 清理无意义标签
        var cleanName = name.replace(Regex("[._\\-\\[\\]()【】]"), " ")
        
        cleanName = cleanName.split(" ")
            .filter { word ->
                val w = word.trim()
                w.isNotEmpty() && 
                !noiseWords.contains(w.lowercase()) && 
                !w.matches(Regex("^[a-zA-Z]{1,2}$"))
            }
            .joinToString(" ")

        // 终极兜底
        if (cleanName.isBlank() || cleanName.length < 2 || cleanName.matches(Regex("^\\d+$"))) {
            if (!parentFolder.isNullOrBlank() && !isSeasonFolder(parentFolder) && !listOf("电影", "电视剧", "动漫", "未观看", "已观看").contains(parentFolder)) {
                cleanName = parentFolder
            } else {
                cleanName = "未知影视"
            }
        }

        return ParsedVideoName(cleanName, year, season, episode, isVideo)
    }
}