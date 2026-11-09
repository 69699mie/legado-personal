package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "video_scraped_cache")
data class VideoScrapedEntity(
    @PrimaryKey val id: String,
    val title: String,
    val category: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val overview: String?,
    val rating: String?,
    val groupName: String?,
    val isManuallyEdited: Boolean,
    val fileListSignature: String,
    val extraData: String // 用于存放复杂的 Map (episodes, stills 等) 的 JSON 字符串
)