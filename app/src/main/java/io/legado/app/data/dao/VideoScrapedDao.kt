package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.legado.app.data.entities.VideoScrapedEntity

@Dao
interface VideoScrapedDao {
    @Query("SELECT * FROM video_scraped_cache")
    suspend fun getAll(): List<VideoScrapedEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<VideoScrapedEntity>)

    @Query("DELETE FROM video_scraped_cache")
    suspend fun deleteAll()

    // ➕ 新增：事务级全量覆盖，确保绝不会因中途退出导致数据丢失
    @Transaction
    suspend fun replaceAll(entities: List<VideoScrapedEntity>) {
        deleteAll()
        upsertAll(entities)
    }
}