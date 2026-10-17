package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.constant.BookType
import io.legado.app.data.entities.readRecord.HomeRecentBookRow
import io.legado.app.data.entities.readRecord.ReadRecord
import io.legado.app.data.entities.readRecord.ReadRecordDetail
import io.legado.app.data.entities.readRecord.ReadRecordSession
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadRecordDao {

    @get:Query("select * from readRecord")
    val all: List<ReadRecord>

    @get:Query("select * from readRecordDetail")
    val allDetail: List<ReadRecordDetail>

    @get:Query("select * from readRecordSession")
    val allSession: List<ReadRecordSession>

    // 💡 漫画专用 App：所有全局统计/列表查询都通过 EXISTS 关联 books 表，仅统计漫画书（type & image > 0）。
    // 老数据若匹配不到 books 表（书已删除），则不计入，符合"只看漫画"的预期。
    //
    // 💡 作者匹配：readRecord 存的是 books.author 的「第一行」（书源常用换行堆叠作者/出品方/绘制）。
    //   这里除了直接相等，还要匹配「books.author 以 readRecord.bookAuthor 开头，且下一个字符是换行」
    //   的多行情况。用 substr 而非 LIKE，避免作者名里的 % / _ 被误当通配符。

    @Query(
        """
        SELECT sum(r.readTime) FROM readRecord r
        WHERE EXISTS (
            SELECT 1 FROM books b
            WHERE b.name = r.bookName
              AND (
                b.author = r.bookAuthor
                OR (
                  r.bookAuthor != ''
                  AND length(b.author) > length(r.bookAuthor)
                  AND substr(b.author, 1, length(r.bookAuthor)) = r.bookAuthor
                  AND substr(b.author, length(r.bookAuthor) + 1, 1) IN (char(10), char(13))
                )
              )
              AND b.type & ${BookType.image} > 0
        )
        """
    )
    fun getTotalReadTime(): Flow<Long?>

    @Query(
        """
        SELECT COUNT(*) FROM (
            SELECT 1
            FROM readRecord r
            WHERE EXISTS (
                SELECT 1 FROM books b
                WHERE b.name = r.bookName
                  AND (
                    b.author = r.bookAuthor
                    OR (
                      r.bookAuthor != ''
                      AND length(b.author) > length(r.bookAuthor)
                      AND substr(b.author, 1, length(r.bookAuthor)) = r.bookAuthor
                      AND substr(b.author, length(r.bookAuthor) + 1, 1) IN (char(10), char(13))
                    )
                  )
                  AND b.type & ${BookType.image} > 0
            )
            GROUP BY r.bookName, r.bookAuthor
        )
        """
    )
    fun observeTotalReadBookCount(): Flow<Int>

    @Query(
        """
        SELECT SUM(r.readTime) FROM readRecordDetail r
        WHERE r.date = :date
          AND EXISTS (
              SELECT 1 FROM books b
              WHERE b.name = r.bookName
                AND (
                  b.author = r.bookAuthor
                  OR (
                    r.bookAuthor != ''
                    AND length(b.author) > length(r.bookAuthor)
                    AND substr(b.author, 1, length(r.bookAuthor)) = r.bookAuthor
                    AND substr(b.author, length(r.bookAuthor) + 1, 1) IN (char(10), char(13))
                  )
                )
                AND b.type & ${BookType.image} > 0
          )
        """
    )
    fun observeReadTimeByDate(date: String): Flow<Long?>

    @Query(
        """
        WITH recent AS (
            SELECT
                bookName,
                bookAuthor,
                MAX(lastRead) AS lastRead
            FROM readRecord r
            WHERE EXISTS (
                SELECT 1 FROM books b
                WHERE b.name = r.bookName
                  AND (
                    b.author = r.bookAuthor
                    OR (
                      r.bookAuthor != ''
                      AND length(b.author) > length(r.bookAuthor)
                      AND substr(b.author, 1, length(r.bookAuthor)) = r.bookAuthor
                      AND substr(b.author, length(r.bookAuthor) + 1, 1) IN (char(10), char(13))
                    )
                  )
                  AND b.type & ${BookType.image} > 0
            )
            GROUP BY bookName, bookAuthor
            ORDER BY lastRead DESC
            LIMIT :limit
        )
        SELECT
            recent.bookName AS recordName,
            recent.bookAuthor AS recordAuthor,
            book.bookUrl AS bookUrl,
            book.origin AS origin,
            book.coverUrl AS coverUrl,
            book.customCoverUrl AS customCoverUrl,
            book.durChapterTitle AS chapterTitle,
            book.totalChapterNum AS totalChapterNum,
            book.durChapterIndex AS chapterIndex
        FROM recent
        LEFT JOIN books AS book ON book.bookUrl = (
            SELECT candidate.bookUrl
            FROM books AS candidate
            WHERE candidate.name = recent.bookName
                AND (
                  candidate.author = recent.bookAuthor
                  OR (
                    recent.bookAuthor != ''
                    AND length(candidate.author) > length(recent.bookAuthor)
                    AND substr(candidate.author, 1, length(recent.bookAuthor)) = recent.bookAuthor
                    AND substr(candidate.author, length(recent.bookAuthor) + 1, 1) IN (char(10), char(13))
                  )
                )
            ORDER BY candidate.durChapterTime DESC, candidate.bookUrl ASC
            LIMIT 1
        )
        ORDER BY recent.lastRead DESC
        """
    )
    fun observeRecentHomeBooks(limit: Int): Flow<List<HomeRecentBookRow>>

    @Query("select sum(readTime) from readRecord where bookName = :bookName")
    fun getReadTime(bookName: String): Long?

    @Query("select readTime from readRecord where deviceId = :deviceId and bookName = :bookName and bookAuthor = :bookAuthor")
    fun getReadTime(deviceId: String, bookName: String, bookAuthor: String): Long?

    @Query("SELECT * FROM readRecord WHERE deviceId = :deviceId AND bookName = :bookName AND bookAuthor = :bookAuthor")
    suspend fun getReadRecord(deviceId: String, bookName: String, bookAuthor: String): ReadRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vararg readRecord: ReadRecord)

    @Update
    suspend fun update(vararg record: ReadRecord)

    @Delete
    fun delete(vararg record: ReadRecord)

    @Query("delete from readRecord")
    fun clear()

    @Query("delete from readRecord where bookName = :bookName and bookAuthor = :bookAuthor")
    fun deleteByName(bookName: String, bookAuthor: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDetail(detail: ReadRecordDetail)

    @Query("SELECT * FROM readRecordDetail WHERE deviceId = :deviceId AND bookName = :bookName AND bookAuthor = :bookAuthor AND date = :date")
    suspend fun getDetail(deviceId: String, bookName: String, bookAuthor: String, date: String): ReadRecordDetail?

    @Query("SELECT DISTINCT date FROM readRecordDetail WHERE deviceId = :deviceId ORDER BY date DESC")
    fun getAllReadDates(deviceId: String): List<String>

    @Query("SELECT * FROM readRecordDetail WHERE deviceId = :deviceId AND date = :date")
    suspend fun getDetailsByDate(deviceId: String, date: String): List<ReadRecordDetail>

    @Query("DELETE FROM readRecordDetail WHERE bookName = :bookName AND bookAuthor = :bookAuthor")
    fun deleteDetailByName(bookName: String, bookAuthor: String)

    @Query("DELETE FROM readRecordDetail WHERE bookName = :bookName AND bookAuthor = :bookAuthor AND date = :date")
    suspend fun deleteDetailByNameAndDate(bookName: String, bookAuthor: String, date: String)

    @Query("SELECT * FROM readRecordSession WHERE bookName = :bookName AND bookAuthor = :bookAuthor ORDER BY endTime DESC LIMIT 1")
    suspend fun getLatestSessionByBook(bookName: String, bookAuthor: String): ReadRecordSession?

    @Update
    suspend fun updateSession(session: ReadRecordSession)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSession(session: ReadRecordSession)

    @Query(
        """
        SELECT r.* FROM readRecord r
        WHERE EXISTS (
            SELECT 1 FROM books b
            WHERE b.name = r.bookName
              AND (
                b.author = r.bookAuthor
                OR (
                  r.bookAuthor != ''
                  AND length(b.author) > length(r.bookAuthor)
                  AND substr(b.author, 1, length(r.bookAuthor)) = r.bookAuthor
                  AND substr(b.author, length(r.bookAuthor) + 1, 1) IN (char(10), char(13))
                )
              )
              AND b.type & ${BookType.image} > 0
        )
        ORDER BY r.lastRead DESC
        """
    )
    fun getAllReadRecordsSortedByLastRead(): Flow<List<ReadRecord>>

    @Query(
        """
        SELECT r.* FROM readRecord r
        WHERE (r.bookName LIKE '%' || :query || '%' OR r.bookAuthor LIKE '%' || :query || '%')
          AND EXISTS (
              SELECT 1 FROM books b
              WHERE b.name = r.bookName
                AND (
                  b.author = r.bookAuthor
                  OR (
                    r.bookAuthor != ''
                    AND length(b.author) > length(r.bookAuthor)
                    AND substr(b.author, 1, length(r.bookAuthor)) = r.bookAuthor
                    AND substr(b.author, length(r.bookAuthor) + 1, 1) IN (char(10), char(13))
                  )
                )
                AND b.type & ${BookType.image} > 0
          )
        ORDER BY r.lastRead DESC
        """
    )
    fun searchReadRecordsByLastRead(query: String): Flow<List<ReadRecord>>

    @Query("SELECT * FROM readRecord WHERE deviceId = :deviceId AND bookName = :bookName AND bookAuthor != :excludeAuthor ORDER BY lastRead DESC")
    suspend fun getReadRecordsByNameExcludingAuthor(
        deviceId: String,
        bookName: String,
        excludeAuthor: String
    ): List<ReadRecord>

    @Query(
        """
        SELECT * FROM readRecord
        WHERE deviceId = :deviceId
        AND NOT (bookName = :bookName AND bookAuthor = :bookAuthor)
        ORDER BY
            CASE WHEN bookName = :bookName THEN 0 ELSE 1 END,
            lastRead DESC
        """
    )
    suspend fun getMergeCandidates(
        deviceId: String,
        bookName: String,
        bookAuthor: String
    ): List<ReadRecord>

    @Query(
        """
        SELECT * FROM readRecord
        WHERE NOT (bookName = :bookName AND bookAuthor = :bookAuthor)
        ORDER BY CASE WHEN bookName = :bookName THEN 0 ELSE 1 END, lastRead DESC
        """
    )
    suspend fun getMergeCandidatesAcrossDevices(
        bookName: String,
        bookAuthor: String,
    ): List<ReadRecord>

    @Query("""
        SELECT * FROM readRecordSession 
        WHERE deviceId = :deviceId 
        AND bookName = :bookName 
        AND bookAuthor = :bookAuthor 
        AND STRFTIME('%Y-%m-%d', datetime(startTime/1000, 'unixepoch', 'localtime')) = :date 
        ORDER BY startTime ASC
    """)
    suspend fun getSessionsByBookAndDate(
        deviceId: String,
        bookName: String,
        bookAuthor: String,
        date: String
    ): List<ReadRecordSession>

    @Query("""
    SELECT * FROM readRecordSession 
    WHERE deviceId = :deviceId 
    AND STRFTIME('%Y-%m-%d', datetime(startTime/1000, 'unixepoch', 'localtime')) = :date 
    ORDER BY startTime ASC
    """)
    suspend fun getSessionsByDate(deviceId: String, date: String): List<ReadRecordSession>

    @Query("SELECT * FROM readRecordDetail WHERE deviceId = :deviceId AND date = :date AND (bookName LIKE '%' || :query || '%' OR bookAuthor LIKE '%' || :query || '%')")
    suspend fun searchDetailsByDate(deviceId: String, date: String, query: String): List<ReadRecordDetail>

    @Query("DELETE FROM readRecordSession WHERE bookName = :bookName AND bookAuthor = :bookAuthor")
    fun deleteSessionByName(bookName: String, bookAuthor: String)

    @Query("DELETE FROM readRecordSession WHERE bookName = :bookName AND bookAuthor = :bookAuthor AND startTime = :startTime AND endTime = :endTime AND words = :words")
    suspend fun deleteSessionByIdentity(
        bookName: String,
        bookAuthor: String,
        startTime: Long,
        endTime: Long,
        words: Long,
    )

    @Query("SELECT * FROM readRecord WHERE bookName = :bookName AND bookAuthor = :bookAuthor")
    suspend fun getReadRecordsByName(bookName: String, bookAuthor: String): List<ReadRecord>

    @Query("SELECT * FROM readRecord WHERE bookName = :bookName AND bookAuthor = ''")
    suspend fun getUnknownAuthorRecords(bookName: String): List<ReadRecord>

    @Query(
        """
        SELECT r.* FROM readRecordDetail r
        WHERE EXISTS (
            SELECT 1 FROM books b
            WHERE b.name = r.bookName
              AND (
                b.author = r.bookAuthor
                OR (
                  r.bookAuthor != ''
                  AND length(b.author) > length(r.bookAuthor)
                  AND substr(b.author, 1, length(r.bookAuthor)) = r.bookAuthor
                  AND substr(b.author, length(r.bookAuthor) + 1, 1) IN (char(10), char(13))
                )
              )
              AND b.type & ${BookType.image} > 0
        )
        ORDER BY r.date DESC, r.lastReadTime DESC
        """
    )
    fun getAllDetails(): Flow<List<ReadRecordDetail>>

    @Query(
        """
        SELECT r.* FROM readRecordDetail r
        WHERE (r.bookName LIKE '%' || :query || '%' OR r.bookAuthor LIKE '%' || :query || '%')
          AND EXISTS (
              SELECT 1 FROM books b
              WHERE b.name = r.bookName
                AND (
                  b.author = r.bookAuthor
                  OR (
                    r.bookAuthor != ''
                    AND length(b.author) > length(r.bookAuthor)
                    AND substr(b.author, 1, length(r.bookAuthor)) = r.bookAuthor
                    AND substr(b.author, length(r.bookAuthor) + 1, 1) IN (char(10), char(13))
                  )
                )
                AND b.type & ${BookType.image} > 0
          )
        ORDER BY r.date DESC, r.lastReadTime DESC
        """
    )
    fun searchDetails(query: String): Flow<List<ReadRecordDetail>>

    @Query("SELECT * FROM readRecordSession WHERE deviceId = :deviceId ORDER BY startTime ASC")
    fun getAllSessions(deviceId: String): Flow<List<ReadRecordSession>>

    @Query(
        """
        SELECT r.* FROM readRecordSession r
        WHERE EXISTS (
            SELECT 1 FROM books b
            WHERE b.name = r.bookName
              AND (
                b.author = r.bookAuthor
                OR (
                  r.bookAuthor != ''
                  AND length(b.author) > length(r.bookAuthor)
                  AND substr(b.author, 1, length(r.bookAuthor)) = r.bookAuthor
                  AND substr(b.author, length(r.bookAuthor) + 1, 1) IN (char(10), char(13))
                )
              )
              AND b.type & ${BookType.image} > 0
        )
        ORDER BY r.startTime ASC
        """
    )
    fun getAllSessions(): Flow<List<ReadRecordSession>>

    @Query("SELECT * FROM readRecordSession WHERE deviceId = :deviceId AND bookName = :bookName AND bookAuthor = :bookAuthor")
    suspend fun getSessionsByBook(deviceId: String, bookName: String, bookAuthor: String): List<ReadRecordSession>

    @Query(
        """
        DELETE FROM readRecordSession
        WHERE id NOT IN (
            SELECT MIN(id) FROM readRecordSession
            GROUP BY bookName, bookAuthor, bookUrl, startTime, endTime, words
        )
        """
    )
    suspend fun deleteDuplicateSessions()

    @Query(
        """
        DELETE FROM readRecordSession
        WHERE id NOT IN (
            SELECT MIN(id) FROM readRecordSession
            WHERE deviceId = :deviceId AND bookName = :bookName AND bookAuthor = :bookAuthor
            GROUP BY bookName, bookAuthor, bookUrl, startTime, endTime, words
        )
        AND deviceId = :deviceId AND bookName = :bookName AND bookAuthor = :bookAuthor
        """
    )
    suspend fun deleteDuplicateSessionsByBook(deviceId: String, bookName: String, bookAuthor: String)

    @Query(
        """
        SELECT * FROM readRecordSession
        WHERE deviceId = :deviceId
        AND bookName = :bookName
        AND bookAuthor = :bookAuthor
        AND bookUrl = :bookUrl
        AND startTime = :startTime
        AND endTime = :endTime
        AND words = :words
        LIMIT 1
        """
    )
    suspend fun getSession(
        deviceId: String,
        bookName: String,
        bookAuthor: String,
        bookUrl: String,
        startTime: Long,
        endTime: Long,
        words: Long
    ): ReadRecordSession?

    @Query("SELECT * FROM readRecordSession WHERE bookUrl = :bookUrl ORDER BY startTime ASC")
    suspend fun getSessionsByBookUrl(bookUrl: String): List<ReadRecordSession>

    @Query(
        """
        UPDATE readRecordSession
        SET bookUrl = :newBookUrl, bookName = :newBookName, bookAuthor = :newBookAuthor
        WHERE bookUrl = :oldBookUrl
        """
    )
    suspend fun reassignSessionOwnership(
        oldBookUrl: String,
        newBookUrl: String,
        newBookName: String,
        newBookAuthor: String,
    )

    @Query("SELECT * FROM readRecordSession WHERE deviceId = :deviceId AND bookName = :bookName AND bookAuthor = :bookAuthor ORDER BY startTime DESC")
    fun getSessionsByBookFlow(deviceId: String, bookName: String, bookAuthor: String): Flow<List<ReadRecordSession>>

    @Query("SELECT SUM(readTime) FROM readRecord WHERE bookName = :bookName AND bookAuthor = :bookAuthor")
    fun getReadTimeFlow(bookName: String, bookAuthor: String): Flow<Long?>

    @Delete
    suspend fun deleteDetail(detail: ReadRecordDetail)

    @Query(
        """
        DELETE FROM readRecordSession 
        WHERE deviceId = :deviceId 
        AND bookName = :bookName 
        AND bookAuthor = :bookAuthor 
        AND STRFTIME('%Y-%m-%d', datetime(startTime/1000, 'unixepoch', 'localtime')) = :date
    """
    )
    suspend fun deleteSessionsByBookAndDate(
        deviceId: String,
        bookName: String,
        bookAuthor: String,
        date: String
    )

    @Delete
    suspend fun deleteSession(session: ReadRecordSession)

    @Delete
    suspend fun deleteReadRecord(record: ReadRecord)

    @Query("DELETE FROM readRecord")
    suspend fun clearReadRecords()

    @Query("DELETE FROM readRecordDetail")
    suspend fun clearReadRecordDetails()

    @Query("DELETE FROM readRecordSession")
    suspend fun clearReadRecordSessions()

    @Query("DELETE FROM readRecordDetail WHERE deviceId = :deviceId AND bookName = :bookName AND bookAuthor = :bookAuthor")
    suspend fun deleteDetailsByBook(deviceId: String, bookName: String, bookAuthor: String)

    @Query("DELETE FROM readRecordSession WHERE deviceId = :deviceId AND bookName = :bookName AND bookAuthor = :bookAuthor")
    suspend fun deleteSessionsByBook(deviceId: String, bookName: String, bookAuthor: String)

    @Query("SELECT * FROM readRecordDetail WHERE deviceId = :deviceId AND bookName = :bookName AND bookAuthor = :bookAuthor")
    suspend fun getDetailsByBook(deviceId: String, bookName: String, bookAuthor: String): List<ReadRecordDetail>
}