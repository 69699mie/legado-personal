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

    // 全面扩充噪音词，直接干掉 DDP2 2Audios 这种
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

        // 1. 提取季集
        var season: Int? = null
        var episode: Int? = null
        
        // 类似 S01E01
        val seRegex = Regex("(?i)S(\\d{1,2})E(\\d{1,3})")
        seRegex.find(name)?.let {
            season = it.groupValues[1].toIntOrNull()
            episode = it.groupValues[2].toIntOrNull()
            name = name.replace(it.value, " ")
        } ?: run {
            // 类似 EP01
            val eRegex = Regex("(?i)(?:^|[^a-z])E(\\d{1,3})(?:[^a-z]|$)")
            eRegex.find(name)?.let {
                episode = it.groupValues[1].toIntOrNull()
                name = name.replace(it.value, " ")
            }
            // 类似 第01集
            val cnEpRegex = Regex("第(\\d{1,3})[集话期]")
            cnEpRegex.find(name)?.let {
                episode = it.groupValues[1].toIntOrNull()
                name = name.replace(it.value, " ")
            }
            
            // 海阔视界同款：提取纯数字作为集数
            if (episode == null) {
                val allNums = Regex("\\d+").findAll(name).map { it.value }.toList()
                for (numStr in allNums) {
                    val num = numStr.toIntOrNull() ?: continue
                    if (num in listOf(720, 1080, 2160, 480, 360, 264, 265, 1440, 4320)) continue
                    if (num in 1..999 && numStr.length <= 3) {
                        episode = num
                        // 只剔除第一个找到的符合条件的数字
                        name = name.replaceFirst(numStr, " ")
                        break
                    }
                }
            }
        }
        
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

        // 2. 提取年份
        var year: Int? = null
        val yearRegex = Regex("(19|20)\\d{2}")
        yearRegex.find(name)?.let {
            year = it.value.toIntOrNull()
            name = name.replace(it.value, " ")
        }

        // 3. 清理无意义标签
        var cleanName = name.replace(Regex("[._\\-\\[\\]()【】]"), " ")
        
        cleanName = cleanName.split(" ")
            .filter { word ->
                val w = word.trim()
                w.isNotEmpty() && 
                !noiseWords.contains(w.lowercase()) && 
                !w.matches(Regex("^[a-zA-Z]{1,2}$")) // 过滤掉 DDP 这种短字母
            }
            .joinToString(" ")

        // 4. 终极兜底
        if (cleanName.isBlank() || cleanName.length < 2 || cleanName.all { it.isDigit() }) {
            if (!parentFolder.isNullOrBlank() && !isSeasonFolder(parentFolder) && !listOf("电影", "电视剧", "动漫", "未观看", "已观看").contains(parentFolder)) {
                cleanName = parentFolder
            } else {
                cleanName = "未知影视"
            }
        }

        return ParsedVideoName(cleanName, year, season, episode, isVideo)
    }
}