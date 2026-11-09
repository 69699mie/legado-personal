package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.VideoScrapedEntity

@Dao
interface VideoScrapedDao {
    @Query("SELECT * FROM video_scraped_cache")
    suspend fun getAll(): List<VideoScrapedEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<VideoScrapedEntity>)

    @Query("DELETE FROM video_scraped_cache")
    suspend fun deleteAll()

    @Query("DELETE FROM video_scraped_cache WHERE id = :id")
    suspend fun deleteById(id: String)
}
