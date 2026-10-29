package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.VideoItem
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoDao {

    @Query("SELECT * FROM video_items ORDER BY id DESC")
    fun getAll(): Flow<List<VideoItem>>

    @Query("SELECT * FROM video_items WHERE id = :id")
    fun getById(id: Long): VideoItem?

    @Query("SELECT * FROM video_items WHERE fileId = :fileId LIMIT 1")
    fun getByFileId(fileId: String): VideoItem?

    @Query("SELECT * FROM video_items WHERE scrapeState = 0 LIMIT :limit")
    fun getPendingScrapeItems(limit: Int): List<VideoItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(items: List<VideoItem>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(item: VideoItem): Long

    @Update
    fun update(item: VideoItem)

    @Query("UPDATE video_items SET scrapeState = :state, tmdbId = :tmdbId, posterUrl = :poster, backdropUrl = :backdrop, overview = :overview WHERE id = :id")
    fun updateScrapeResult(id: Long, state: Int, tmdbId: String, poster: String, backdrop: String, overview: String)
    
    @Query("DELETE FROM video_items WHERE id = :id")
    fun deleteById(id: Long)
}