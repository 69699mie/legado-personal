package io.legado.app.data.repository

import io.legado.app.data.dao.ReadRecordDao
import io.legado.app.data.local.preferences.LocalPreferencesKeys
import io.legado.app.domain.gateway.HomeDashboardGateway
import io.legado.app.domain.model.DEFAULT_HOME_DASHBOARD_SECTIONS
import io.legado.app.domain.model.HomeDashboardSection
import io.legado.app.domain.model.HomeReadingBook
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class HomeDashboardRepository(
    private val readRecordDao: ReadRecordDao,
    private val localPreferencesRepository: SettingsRepository,
    private val bookRepository: BookRepository, // 💡 新增：注入 BookRepository 用于兜底查询（回退时删除此行及下方的兜底逻辑）
) : HomeDashboardGateway {

    override fun observeTotalReadBooks(): Flow<Int> =
        readRecordDao.observeTotalReadBookCount()

    override fun observeTotalReadTime(): Flow<Long> =
        readRecordDao.getTotalReadTime().map { it ?: 0L }

    override fun observeReadTime(date: String): Flow<Long> =
        readRecordDao.observeReadTimeByDate(date).map { it ?: 0L }

    override fun observeRecentBooks(limit: Int): Flow<List<HomeReadingBook>> =
        readRecordDao.observeRecentHomeBooks(limit).map { rows ->
            rows.map { row ->
                // 💡 修复：SQL LEFT JOIN 依赖 bookName 和 bookAuthor，如果换源/改名会导致关联失败。
                // 此时 bookUrl、coverUrl、customCoverUrl 会全为 null，使用 BookRepository 按名字作者兜底查询。
                val needsFallback = row.bookUrl.isNullOrEmpty() && 
                                   row.customCoverUrl.isNullOrEmpty() && 
                                   row.coverUrl.isNullOrEmpty()
                                   
                val fallbackBook = if (needsFallback) {
                    bookRepository.getBook(row.recordName, row.recordAuthor)
                } else {
                    null
                }

                // 优先使用 SQL 查出的封面，如果为空则使用兜底查出的本地封面或网络封面
                val finalCoverPath = when {
                    !row.customCoverUrl.isNullOrEmpty() -> row.customCoverUrl
                    !row.coverUrl.isNullOrEmpty() -> row.coverUrl
                    fallbackBook != null -> fallbackBook.getDisplayCover()
                    else -> null
                }

                val finalBookUrl = row.bookUrl ?: fallbackBook?.bookUrl

                HomeReadingBook(
                    bookUrl = finalBookUrl,
                    name = row.recordName,
                    author = row.recordAuthor,
                    origin = row.origin ?: fallbackBook?.origin,
                    coverPath = finalCoverPath,
                    chapterTitle = row.chapterTitle,
                    chapterProgress = if (
                        row.totalChapterNum != null &&
                        row.totalChapterNum > 0 &&
                        row.chapterIndex != null
                    ) {
                        (row.chapterIndex + 1)
                            .coerceIn(0, row.totalChapterNum)
                            .toFloat() / row.totalChapterNum
                    } else {
                        null
                    },
                )
            }
        }

    override fun observeDailyGoal(defaultValue: Int): Flow<Int> =
        localPreferencesRepository.getPreference(
            LocalPreferencesKeys.DAILY_READING_GOAL_MINUTES,
            defaultValue,
        )

    override fun observeSelectedSourceSetUrl(): Flow<String?> =
        localPreferencesRepository.getPreference(
            LocalPreferencesKeys.HOME_SOURCE_SET_URL,
            "",
        ).map { it.takeIf(String::isNotBlank) }

    override fun observeVisibleSections(): Flow<Set<HomeDashboardSection>> =
        localPreferencesRepository.getPreference(
            LocalPreferencesKeys.HOME_DASHBOARD_SECTIONS,
            DEFAULT_HOME_DASHBOARD_SECTIONS.joinToString(",") { it.storageValue },
        ).map(HomeDashboardSection::fromStorage)

    override suspend fun updateDailyGoal(minutes: Int) {
        localPreferencesRepository.updatePreference(
            LocalPreferencesKeys.DAILY_READING_GOAL_MINUTES,
            minutes,
        )
    }

    override suspend fun updateSelectedSourceSetUrl(sourceUrl: String) {
        localPreferencesRepository.updatePreference(
            LocalPreferencesKeys.HOME_SOURCE_SET_URL,
            sourceUrl,
        )
    }

    override suspend fun updateVisibleSections(sections: Set<HomeDashboardSection>) {
        localPreferencesRepository.updatePreference(
            LocalPreferencesKeys.HOME_DASHBOARD_SECTIONS,
            HomeDashboardSection.entries
                .filter(sections::contains)
                .joinToString(",") { it.storageValue },
        )
    }
}