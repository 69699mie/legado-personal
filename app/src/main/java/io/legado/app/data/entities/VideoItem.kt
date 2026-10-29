package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "video_items")
data class VideoItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    // 夸克网盘唯一标识（用于后续获取播放直链）
    val fileId: String,
    
    // 原始文件名
    val fileName: String,
    
    // 解析出的核心标题（如 "流浪地球2"）
    val title: String = "",
    
    // 解析出的年份（如 "2023"）
    val year: String = "",
    
    // TMDB 刮削的数据
    val tmdbId: String = "",
    val posterUrl: String = "",
    val backdropUrl: String = "",
    val overview: String = "",
    
    // 刮削状态机：0=待刮削, 1=已匹配, 2=手动修正, 3=匹配失败
    val scrapeState: Int = 0,
    
    // 如果是剧集，记录解析出的季和集（可选，用于后续排序聚合）
    val season: Int = 0,
    val episode: Int = 0,
    
    // 播放进度（毫秒）
    val playProgress: Long = 0L,
    val totalDuration: Long = 0L
)