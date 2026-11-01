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
        
        if (Regex("(?i)sample|trailer|预告|片头|片尾|花絮").containsMatchIn(name)) {
            return ParsedVideoName(name, null, null, 0, isVideo)
        }

        var cleanNameForEpisode = name
            .replace(Regex("(?i)\\d{3,4}p"), "")
            .replace(Regex("(?i)\\d+k\\b"), "")
            .replace(Regex("(?i)h\\d{3}"), "")
            .replace(Regex("(19|20)\\d{2}"), "")
            .replace(Regex("(?i)x\\d{3,4}"), "")
            .replace(Regex("(?i)hevc|avc|bluray|web-dl|dvd|bdrip|hdrip|remux"), "")

        val cnEpRegex = Regex("第\\s*(\\d+)\\s*(集|话|回|期|卷)")
        cnEpRegex.find(cleanNameForEpisode)?.let {
            episode = it.groupValues[1].toIntOrNull()
        }

        if (episode == null) {
            val epRegex = Regex("(?i)EP\\s*(\\d+)")
            epRegex.find(cleanNameForEpisode)?.let {
                episode = it.groupValues[1].toIntOrNull()
            }
        }

        if (episode == null) {
            val seRegex = Regex("(?i)S\\d+E(\\d+)")
            seRegex.find(cleanNameForEpisode)?.let {
                season = it.value.substringAfter("S").substringBefore("E").toIntOrNull()
                episode = it.groupValues[1].toIntOrNull()
            }
        }

        if (episode == null) {
            val allNums = Regex("\\d+").findAll(cleanNameForEpisode).map { it.value }.toList()
            val excludedNums = setOf("720", "1080", "2160", "480", "360", "264", "265", "1440", "4320")
            for (numStr in allNums) {
                val num = numStr.toIntOrNull() ?: continue
                if (excludedNums.contains(numStr)) continue
                if (num in 1..999 && numStr.length <= 3) {
                    episode = num
                    break
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
        }

        var year: Int? = null
        Regex("(19|20)\\d{2}").find(name)?.let {
            year = it.value.toIntOrNull()
        }

        var cleanName = name.replace(Regex("[._\\-\\[\\]()【】]"), " ")
        cleanName = cleanName.split(" ")
            .filter { word ->
                val w = word.trim()
                w.isNotEmpty() && 
                !Regex("(?i)\\d{3,4}p|\\d+k|h\\d{3}|x\\d{3,4}|hevc|avc|bluray|web-dl|dvd|bdrip|hdrip|remux|\\d{4}").matches(w)
            }
            .joinToString(" ")

        if (cleanName.isBlank() || cleanName.length < 2 || cleanName.matches(Regex("^\\d+$"))) {
            if (!parentFolder.isNullOrBlank() && !isSeasonFolder(parentFolder)) {
                cleanName = parentFolder
            } else {
                cleanName = "未知影视"
            }
        }

        return ParsedVideoName(cleanName, year, season, episode, isVideo)
    }
}