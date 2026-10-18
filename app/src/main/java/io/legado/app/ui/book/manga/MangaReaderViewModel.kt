package io.legado.app.ui.book.manga

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.ImageLoader
import io.legado.app.R
import io.legado.app.data.model.MangaFooterConfig
import io.legado.app.data.repository.ReadRecordRepository
import io.legado.app.data.repository.manga.MangaReaderActionPaymentResult
import io.legado.app.data.repository.manga.MangaReaderActionRepository
import io.legado.app.domain.gateway.MangaReaderSessionFactory
import io.legado.app.domain.gateway.MangaSettingsGateway
import io.legado.app.domain.gateway.OtherSettingsGateway
import io.legado.app.domain.gateway.ReadSettingsGateway
import io.legado.app.domain.model.manga.MangaChapterState
import io.legado.app.domain.model.manga.MangaProgressState
import io.legado.app.domain.model.manga.MangaSessionCommand
import io.legado.app.domain.model.manga.MangaSessionEvent
import io.legado.app.domain.model.manga.MangaSessionState
import io.legado.app.domain.model.settings.MangaSettings
import io.legado.app.domain.usecase.CacheBookChaptersUseCase
import io.legado.app.help.coil.CoverFetcher
import io.legado.app.help.glide.progress.ProgressManager
import io.legado.app.model.SourceCallBack
import io.legado.app.ui.book.manga.config.MangaColorFilterConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splitties.init.appCtx

/** 会把下载百分比写进 UiState 的页窗口：当前页前后各若干项。 */
private const val PAGE_PROGRESS_WINDOW = 3

/** 💡 新增：阅读时长的落盘间隔（毫秒）。越大越省 IO，被杀时丢的时长越多。 */
private const val READ_RECORD_FLUSH_INTERVAL_MS = 30_000L

class MangaReaderViewModel(
    private val mangaSettingsGateway: MangaSettingsGateway,
    private val otherSettingsGateway: OtherSettingsGateway,
    private val readSettingsGateway: ReadSettingsGateway,
    private val actionRepository: MangaReaderActionRepository,
    private val cacheBookChaptersUseCase: CacheBookChaptersUseCase,
    private val readRecordRepository: ReadRecordRepository,
    private val imageLoader: ImageLoader, // 💡 新增：用于清理缓存
    sessionFactory: MangaReaderSessionFactory,
) : ViewModel() {

    private val readerSession = sessionFactory.create()

    private var refreshContentJob: Job? = null
    private var latestMangaSettings = mangaSettingsGateway.currentSettings
    private val bookConfigWriteMutex = Mutex()
    private var pendingExplicitChapterIndex: Int? = null
    private var pagerScrollInProgress = false
    private var deferredReadySession: MangaSessionState? = null
    private var initializationRequest: MangaReaderIntent.Initialize? = null

    // 💡 新增：阅读记录追踪
    private var readRecordBookName: String? = null
    private var readRecordBookAuthor: String? = null
    private var readSessionStartedAt: Long = 0L
    private var readSessionFlushJob: Job? = null

    // 💡 新增：阅读缓存记录与清理
    private var maxReadChapterIndex = -1
    private val chapterImageUrls = mutableMapOf<Int, List<String>>()

    private val _uiState = MutableStateFlow(MangaReaderUiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<MangaReaderEffect>(extraBufferCapacity = 16)
    val effects = _effects.asSharedFlow()

    init {
        viewModelScope.launch {
            readerSession.events.collect { event ->
                when (event) {
                    is MangaSessionEvent.ConfirmProgress -> _uiState.update {
                        it.copy(activeDialog = MangaReaderDialog.ConfirmProgress(event.progress.toBookProgress()))
                    }
                    is MangaSessionEvent.Message -> enqueueMessage(text = event.message)
                    is MangaSessionEvent.OpenPaymentUrl -> _effects.tryEmit(
                        MangaReaderEffect.OpenPaymentUrl(
                            event.url, event.sourceOrigin, event.sourceName, event.sourceType,
                        )
                    )
                }
            }
        }
        viewModelScope.launch {
            readerSession.state.collect(::refreshContent)
        }
        viewModelScope.launch {
            mangaSettingsGateway.settings.collect { settings ->
                latestMangaSettings = settings
                readerSession.execute(
                    MangaSessionCommand.PrefetchCountChanged(
                        if (settings.autoOfflineCache) settings.chapterPrefetchCount else 0,
                    )
                )
                _uiState.update { state ->
                    state.copy(settings = readSettings(settings))
                }
            }
        }
        viewModelScope.launch {
            otherSettingsGateway.settings.collect { settings ->
                _uiState.update { it.copy(confirmAddToShelf = settings.showAddToShelfAlert) }
            }
        }
        viewModelScope.launch {
            ProgressManager.progress.collect { event ->
                applyPageProgress(event.url, event.percentage)
            }
        }
    }

    fun onIntent(intent: MangaReaderIntent) {
        when (intent) {
            is MangaReaderIntent.Initialize -> initialize(intent)
            MangaReaderIntent.ResumeSession -> executeSession(MangaSessionCommand.Resume)
            MangaReaderIntent.PauseSession -> executeSession(MangaSessionCommand.Pause)
            MangaReaderIntent.NetworkAvailable -> executeSession(MangaSessionCommand.NetworkAvailable)
            MangaReaderIntent.RefreshBookSource -> launchAction {
                actionRepository.refreshSource(readerSession.state.value.book?.sourceOrigin)
                refreshContent()
            }
            MangaReaderIntent.ReloadContent -> executeSession(
                MangaSessionCommand.RetryChapter(readerSession.state.value.chapterIndex)
            )
            is MangaReaderIntent.ApplyReadingProgress -> {
                pendingExplicitChapterIndex = intent.progress.durChapterIndex
                executeSession(MangaSessionCommand.ApplyProgress(intent.progress.toMangaProgress()))
            }
            is MangaReaderIntent.OpenChapter -> openChapter(intent.chapterIndex, intent.pageIndex)
            is MangaReaderIntent.ChangeSourceBook -> launchAction {
                showLoading()
                val currentUrl = requireNotNull(readerSession.state.value.book?.bookUrl)
                actionRepository.changeSource(currentUrl, intent.book, intent.toc)
                readerSession.execute(MangaSessionCommand.Open(intent.book.bookUrl, true, true))
            }
            is MangaReaderIntent.AddExternalBookToShelf -> launchAction(
                successMessageRes = R.string.manga_reader_added_to_shelf,
            ) { actionRepository.addToShelf(intent.book, intent.toc) }
            MangaReaderIntent.AddCurrentBookToShelf -> launchAction {
                _uiState.update { it.copy(activeDialog = null) }
                actionRepository.addCurrentBookToShelf(
                    requireNotNull(readerSession.state.value.book?.bookUrl)
                )
                _effects.tryEmit(MangaReaderEffect.Finish(bookshelfChanged = true))
            }
            MangaReaderIntent.DiscardCurrentBookAndExit -> launchAction {
                _uiState.update { it.copy(activeDialog = null) }
                actionRepository.removeTemporaryBook(
                    requireNotNull(readerSession.state.value.book?.bookUrl)
                )
                _effects.tryEmit(MangaReaderEffect.Finish())
            }
            MangaReaderIntent.DismissDialog -> _uiState.update { it.copy(activeDialog = null) }
            MangaReaderIntent.DisableCurrentSource -> launchAction {
                actionRepository.disableSource(readerSession.state.value.book?.sourceOrigin)
            }
            MangaReaderIntent.RequestPayCurrentChapter -> _uiState.update {
                it.copy(
                    activeSheet = null,
                    activeDialog = MangaReaderDialog.ConfirmPay(it.chapterName),
                )
            }
            MangaReaderIntent.PayCurrentChapter -> {
                _uiState.update { it.copy(activeDialog = null) }
                payCurrentChapter()
            }
            MangaReaderIntent.OpenSourceLogin -> {
                _uiState.value.sourceUrl?.let {
                    _effects.tryEmit(MangaReaderEffect.OpenSourceLogin(it))
                }
            }
            MangaReaderIntent.OpenSourceEdit -> {
                _uiState.value.sourceUrl?.let {
                    _effects.tryEmit(MangaReaderEffect.OpenSourceEdit(it))
                }
            }
            is MangaReaderIntent.SourceCustomButton -> launchAction {
                val state = _uiState.value
                val payload = actionRepository.getSourceCustomButtonPayload(
                    state.bookUrl,
                    state.chapterIndex,
                ) ?: return@launchAction
                _effects.tryEmit(
                    MangaReaderEffect.RunSourceCustomButton(
                        event = if (intent.longClick) {
                            SourceCallBack.LONG_CLICK_CUSTOM_BUTTON
                        } else {
                            SourceCallBack.CLICK_CUSTOM_BUTTON
                        },
                        source = payload.source,
                        book = payload.book,
                        chapter = payload.chapter,
                    )
                )
            }
            MangaReaderIntent.BackPressed -> {
                when (
                    resolveMangaBackAction(
                        hasActiveDialog = _uiState.value.activeDialog != null,
                        hasActiveSheet = _uiState.value.activeSheet != null,
                        hasSettingsCategory = _uiState.value.settingsCategory != null,
                        menuVisible = _uiState.value.menuVisible,
                    )
                ) {
                    MangaBackAction.DISMISS_DIALOG -> {
                        _uiState.update { it.copy(activeDialog = null) }
                    }

                    MangaBackAction.DISMISS_SHEET -> {
                        _uiState.update { it.copy(activeSheet = null) }
                    }

                    MangaBackAction.CLOSE_SETTINGS -> closeSettings()
                    MangaBackAction.HIDE_MENU -> setMenuVisible(false)
                    MangaBackAction.CLOSE_READER -> closeReader()
                }
            }

            MangaReaderIntent.CloseReader -> closeReader()
            MangaReaderIntent.ToggleMenu -> setMenuVisible(!_uiState.value.menuVisible)
            MangaReaderIntent.HideMenu -> setMenuVisible(false)
            MangaReaderIntent.Retry -> launchAction {
                showLoading()
                invalidateCurrentChapter()
                executeSession(MangaSessionCommand.RetryChapter(readerSession.state.value.chapterIndex))
            }
            MangaReaderIntent.PreviousChapter -> openRelativeChapter(-1)
            MangaReaderIntent.NextChapter -> openRelativeChapter(1)
            MangaReaderIntent.OpenCatalog -> showSheet(MangaReaderSheet.Catalog)
            MangaReaderIntent.OpenBookInfo -> emitAndHide(MangaReaderEffect.OpenBookInfo)
            MangaReaderIntent.OpenChapterUrl -> emitAndHide(
                MangaReaderEffect.OpenChapterUrl(readSettingsGateway.currentSettings.readUrlInBrowser)
            )
            MangaReaderIntent.ChangeSource -> {
                setMenuVisible(false)
                _uiState.update { it.copy(activeSheet = MangaReaderSheet.ChangeSource) }
                launchAction {
                    val url = requireNotNull(readerSession.state.value.book?.bookUrl)
                    val snapshot = actionRepository.getBook(url)?.let(MangaBookSnapshot::from)
                    _uiState.update { it.copy(changeSourceBook = snapshot) }
                }
            }
            MangaReaderIntent.RefreshChapter -> {
                setMenuVisible(false)
                launchAction {
                    showLoading()
                    invalidateCurrentChapter()
                    readerSession.execute(
                        MangaSessionCommand.RetryChapter(readerSession.state.value.chapterIndex)
                    )
                }
            }
            is MangaReaderIntent.OpenSettings -> openSettings(intent.category)
            MangaReaderIntent.CloseSettings -> closeSettings()
            MangaReaderIntent.OpenSourceActions -> showSheet(MangaReaderSheet.SourceActions)
            MangaReaderIntent.OpenCacheActions -> showSheet(MangaReaderSheet.CacheActions)
            is MangaReaderIntent.CacheChapters -> cacheChapters(intent.selection)
            MangaReaderIntent.ToggleAutoRead -> _uiState.update {
                it.copy(
                    autoReadEnabled = !it.autoReadEnabled,
                    menuVisible = it.settingsCategory == MangaReaderSettingsCategory.AUTO_READ,
                )
            }
            MangaReaderIntent.DismissSheet -> _uiState.update {
                it.copy(activeSheet = null, changeSourceBook = null, settingsCategory = null)
            }
            is MangaReaderIntent.UpdateSetting -> updateSetting(intent.key, intent.value)
            is MangaReaderIntent.UpdateMenuPaletteStyle -> updateMangaPreference {
                it.copy(menuPaletteStyle = intent.value)
            }
            is MangaReaderIntent.UpdateClickAction -> updateClickAction(intent.index, intent.action)
            is MangaReaderIntent.RetryChapter -> executeSession(
                MangaSessionCommand.RetryChapter(intent.chapterIndex)
            )
            is MangaReaderIntent.PageLoadStarted -> markPageLoading(intent.requestId, intent.force)

            is MangaReaderIntent.PageLoadSucceeded -> updatePageLoadState(
                intent.requestId,
                MangaPageLoadState.Ready,
            )

            is MangaReaderIntent.PageLoadFailed -> updatePageLoadState(
                intent.requestId,
                MangaPageLoadState.Failed(intent.message),
            )

            is MangaReaderIntent.RetryPage -> retryPage(intent.key)
            is MangaReaderIntent.RetryFailedPagesInChapter -> retryFailedPages(intent.chapterIndex)
            is MangaReaderIntent.PageStep -> requestPageStep(intent.direction)
            is MangaReaderIntent.SeekToPage -> seekToPage(intent.pageIndex)
            is MangaReaderIntent.VisibleItemChanged -> updateVisibleItem(
                intent.itemIndex,
                intent.firstItemIndex,
                intent.lastItemIndex,
                intent.currentChapterVisible,
                intent.navigationId,
            )
            is MangaReaderIntent.FooterItemChanged -> updateFooterItem(
                intent.itemIndex,
                intent.navigationId,
            )
            is MangaReaderIntent.PagerScrollChanged -> {
                pagerScrollInProgress = intent.inProgress
                if (!intent.inProgress) {
                    deferredReadySession?.let(::refreshContent)
                    deferredReadySession = null
                }
            }
            is MangaReaderIntent.LongPressPage -> {
                if (_uiState.value.settings.longPressEnabled) {
                    val fallback = findSpreadCompanion(intent.pageKey)
                    showSheet(
                        MangaReaderSheet.PageActions(
                            intent.pageKey,
                            intent.companionPageKey ?: fallback?.first,
                            if (intent.companionPageKey != null) intent.companionBeforePage
                            else fallback?.second == true,
                        )
                    )
                }
            }

            is MangaReaderIntent.ExecutePageAction -> executePageAction(intent.action)
            is MangaReaderIntent.MessageShown -> _uiState.update { state ->
                state.copy(
                    pendingMessages = state.pendingMessages
                        .filterNot { it.id == intent.id }
                        .toImmutableList()
                )
            }
        }
    }

    private fun initialize(intent: MangaReaderIntent.Initialize) {
        if (initializationRequest == intent) return
        initializationRequest = intent
        showLoading()
        viewModelScope.launch {
            // 💡 新增：只在书架书中写入阅读记录（搜索页未上架的书不写）
            if (intent.inBookshelf && !intent.bookUrl.isNullOrBlank()) {
                val book = actionRepository.getBook(intent.bookUrl)
                if (book != null) {
                    runCatching {
                        readRecordRepository.recordBookOpened(book.name, book.author)
                    }
                    startReadTimeTracking(book.name, book.author)
                }
            }
            readerSession.execute(
                MangaSessionCommand.Open(
                    bookUrl = intent.bookUrl,
                    inBookshelf = intent.inBookshelf,
                    chapterChanged = intent.chapterChanged,
                )
            )
        }
    }

    private fun payCurrentChapter() {
        launchAction {
            val state = readerSession.state.value
            when (val result = actionRepository.payCurrentChapter(
                requireNotNull(state.book?.bookUrl), state.chapterIndex,
            )) {
                is MangaReaderActionPaymentResult.OpenUrl -> _effects.tryEmit(
                    MangaReaderEffect.OpenPaymentUrl(
                        result.url,
                        result.sourceOrigin,
                        result.sourceName,
                        result.sourceType,
                    )
                )
                MangaReaderActionPaymentResult.Refreshed -> executeSession(
                    MangaSessionCommand.RetryChapter(state.chapterIndex)
                )
            }
        }
    }

    private fun saveImage(url: String) {
        launchAction(
            successMessageRes = R.string.manga_reader_image_saved,
            failureMessageRes = R.string.manga_reader_save_failed,
        ) {
            val book = requireNotNull(readerSession.state.value.book)
            check(actionRepository.saveImage(url, book.bookUrl, book.sourceOrigin)) {
                ""
            }
        }
    }

    private fun launchAction(
        @androidx.annotation.StringRes successMessageRes: Int? = null,
        @androidx.annotation.StringRes failureMessageRes: Int = R.string.manga_reader_action_failed,
        action: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            runCatching { action() }
                .onSuccess { successMessageRes?.let { resId ->
                    enqueueMessage(resId = resId)
                } }
                .onFailure { error ->
                    val message = error.localizedMessage?.takeIf(String::isNotBlank)
                    if (_uiState.value.isLoading) {
                        showError(message.orEmpty(), failureMessageRes)
                    } else if (message != null) {
                        enqueueMessage(text = message)
                    } else {
                        enqueueMessage(resId = failureMessageRes)
                    }
                }
        }
    }

    fun refreshContent() = refreshContent(readerSession.state.value)

    private fun refreshContent(session: MangaSessionState) {
        if (!acceptsMangaSessionForExplicitNavigation(
                pendingExplicitChapterIndex = pendingExplicitChapterIndex,
                sessionChapterIndex = session.chapterIndex,
            )
        ) return
        val book = session.book
        if (book == null) {
            session.openError?.let {
                showError(it, fallbackRes = R.string.manga_reader_init_failed)
            }
            return
        }
        val current = session.currentChapter
        if (current !is MangaChapterState.Ready) {
            if (pendingExplicitChapterIndex == session.chapterIndex) {
                if (current is MangaChapterState.Failed) pendingExplicitChapterIndex = null
                showExplicitChapterPlaceholder(
                    chapterIndex = session.chapterIndex,
                    pageIndex = session.pageIndex,
                    errorMessage = (current as? MangaChapterState.Failed)?.message,
                )
            } else if (current is MangaChapterState.Failed) showError(current.message)
            else showLoading()
            return
        }
        
        // 💡 新增：记录阅读进度并清理已读章节缓存
        recordAndCleanChapterCache(session.chapterIndex, current.chapter)

        if (pagerScrollInProgress &&
            (_uiState.value.chapterIndex != session.chapterIndex ||
                pendingExplicitChapterIndex == session.chapterIndex)
        ) {
            deferredReadySession = session
            _uiState.update { it.copy(isChapterLoading = true, errorMessage = null) }
            return
        }
        deferredReadySession = null
        val existingPages = _uiState.value.pages
            .filterIsInstance<MangaReaderItemUi.Page>()
            .associateBy(MangaReaderItemUi.Page::key)
        fun chapterItems(chapter: io.legado.app.domain.model.manga.MangaChapterContent) =
            if (chapter.isVolume && chapter.pages.isEmpty()) {
                listOf(
                    MangaReaderItemUi.ChapterEdge(
                        key = "volume:${chapter.chapterIndex}",
                        message = chapter.chapterTitle,
                    )
                )
            } else {
                chapter.pages.map { page ->
                    val key = "page:${chapter.chapterIndex}:${page.pageIndex}:${page.imageUrl}"
                    val existing = existingPages[key]
                    MangaReaderItemUi.Page(
                        key = key,
                        imageUrl = page.imageUrl,
                        bookUrl = book.bookUrl,
                        chapterIndex = chapter.chapterIndex,
                        chapterCount = session.chapterCount,
                        pageIndex = page.pageIndex,
                        pageCount = page.pageCount,
                        chapterName = chapter.chapterTitle,
                        loadState = existing?.loadState ?: MangaPageLoadState.Queued,
                        retryRevision = existing?.retryRevision ?: 0,
                    )
                }
            }
        fun chapterTransition(
            chapter: MangaChapterState,
            edgePrefix: String,
            targetChapterIndex: Int,
            loadingMessage: String,
        ): List<MangaReaderItemUi> {
            val direction = if (edgePrefix == "prev") {
                MangaChapterTransitionDirection.PREVIOUS
            } else {
                MangaChapterTransitionDirection.NEXT
            }
            val targetExists = targetChapterIndex in 0 until session.chapterCount
            val targetName = session.book.chapterTitles.getOrNull(targetChapterIndex)
                ?: appCtx.getString(
                    R.string.manga_reader_transition_chapter_number,
                    targetChapterIndex + 1,
                ).takeIf { targetExists }
            return when (chapter) {
            is MangaChapterState.Ready -> listOf(
                MangaReaderItemUi.ChapterTransition(
                    key = "transition:$edgePrefix:${session.chapterIndex}:ready",
                    direction = direction,
                    targetChapterIndex = targetChapterIndex,
                    currentChapterName = current.chapter.chapterTitle,
                    targetChapterName = chapter.chapter.chapterTitle,
                    targetStatus = MangaChapterTransitionStatus.READY,
                )
            )
            is MangaChapterState.Failed -> listOf(
                MangaReaderItemUi.ChapterTransition(
                    key = "edge:$edgePrefix:${session.chapterIndex}:failed",
                    direction = direction,
                    targetChapterIndex = targetChapterIndex,
                    currentChapterName = current.chapter.chapterTitle,
                    targetChapterName = targetName,
                    targetStatus = MangaChapterTransitionStatus.FAILED,
                    statusMessage = appCtx.getString(
                        R.string.manga_reader_adjacent_failed,
                        chapter.message.ifBlank {
                            appCtx.getString(R.string.manga_reader_unknown_error)
                        },
                    ),
                    retryChapterIndex = targetChapterIndex,
                )
            )
            MangaChapterState.Empty -> listOf(
                MangaReaderItemUi.ChapterTransition(
                    key = "edge:$edgePrefix:${session.chapterIndex}:empty",
                    direction = direction,
                    targetChapterIndex = targetChapterIndex.takeIf { targetExists },
                    currentChapterName = current.chapter.chapterTitle,
                    targetChapterName = targetName,
                    targetStatus = if (targetExists) {
                        MangaChapterTransitionStatus.WAITING
                    } else {
                        MangaChapterTransitionStatus.UNAVAILABLE
                    },
                )
            )
            is MangaChapterState.Loading -> listOf(
                MangaReaderItemUi.ChapterTransition(
                    key = "edge:$edgePrefix:${session.chapterIndex}:loading",
                    direction = direction,
                    targetChapterIndex = targetChapterIndex,
                    currentChapterName = current.chapter.chapterTitle,
                    targetChapterName = targetName,
                    targetStatus = MangaChapterTransitionStatus.LOADING,
                    statusMessage = loadingMessage,
                )
            )
            }
        }
        val hideEdgePrompts = latestMangaSettings.hideTitle
        fun makePreviousItems(chapter: MangaChapterState): List<MangaReaderItemUi> {
            val prevItems = if (chapter is MangaChapterState.Ready) {
                chapterItems(chapter.chapter)
            } else emptyList()
            val transition = if (hideEdgePrompts) emptyList() else chapterTransition(
                chapter, "prev", session.chapterIndex - 1,
                appCtx.getString(R.string.manga_reader_loading_prev),
            )
            return prevItems + transition
        }

        fun makeNextItems(chapter: MangaChapterState): List<MangaReaderItemUi> {
            val transition = if (hideEdgePrompts) emptyList() else chapterTransition(
                chapter, "next", session.chapterIndex + 1,
                appCtx.getString(R.string.manga_reader_loading_next),
            )
            return transition + if (chapter is MangaChapterState.Ready) {
                chapterItems(chapter.chapter)
            } else emptyList()
        }

        refreshContentJob?.cancel()
        refreshContentJob = viewModelScope.launch {
            val sourceCustomButtonAvailable = actionRepository
                .refreshSource(book.sourceOrigin)
                ?.customButton == true
            val previousItems = makePreviousItems(session.previousChapter)
            val nextItems = makeNextItems(session.nextChapter)
            val items = (previousItems + chapterItems(current.chapter) + nextItems).toImmutableList()
            val safePosition = (previousItems.size + session.pageIndex)
                .coerceIn(0, (items.size - 1).coerceAtLeast(0))
            val oldState = _uiState.value
            val shouldPosition = shouldForceMangaChapterPosition(
                hasPages = oldState.pages.isNotEmpty(),
                isLoading = oldState.isLoading,
                currentBookUrl = oldState.bookUrl,
                targetBookUrl = book.bookUrl,
                pendingExplicitChapterIndex = pendingExplicitChapterIndex,
                targetChapterIndex = session.chapterIndex,
            )
            val oldCurrentItem = oldState.pages.getOrNull(oldState.currentItemIndex)
            val anchoredIndex = if (shouldPosition) null else oldCurrentItem?.let { anchor ->
                if (anchor is MangaReaderItemUi.Page) {
                    items.indexOfFirst {
                        it is MangaReaderItemUi.Page &&
                                it.chapterIndex == anchor.chapterIndex &&
                                it.pageIndex == anchor.pageIndex
                    }.takeIf { it >= 0 }
                } else null
            }
            val keyPreserved =
                oldCurrentItem?.let { old -> items.any { it.key == old.key } } == true
            val targetIndex = anchoredIndex ?: safePosition
            val positionChanged = shouldPosition || anchoredIndex == null || !keyPreserved ||
                    oldState.currentItemIndex != targetIndex
            _uiState.update { old ->
                old.copy(
                    bookName = book.name,
                    bookAuthor = book.author,
                    bookUrl = book.bookUrl,
                    coverUrl = book.coverUrl,
                    customCoverUrl = book.customCoverUrl,
                    chapterName = current.chapter.chapterTitle,
                    chapterUrl = current.chapter.chapterUrl,
                    sourceName = book.sourceName,
                    sourceUrl = book.sourceOrigin,
                    sourceType = book.sourceType,
                    sourceCustomButtonAvailable = sourceCustomButtonAvailable,
                    inBookshelf = book.inBookshelf,
                    pages = items,
                    navigationId = if (old.pages.map { it.key } != items.map { it.key }) {
                        System.nanoTime()
                    } else old.navigationId,
                    currentItemIndex = targetIndex,
                    footerItemIndex = null,
                    currentPage = if (positionChanged) session.pageIndex else old.currentPage,
                    pageCount = if (positionChanged) current.chapter.pages.size else old.pageCount,
                    chapterIndex = session.chapterIndex,
                    chapterCount = session.chapterCount,
                    cacheAvailable = !book.isLocal,
                    isLoading = false,
                    isChapterLoading = false,
                    pendingChapterIndex = null,
                    errorMessage = null,
                    settings = readSettings(latestMangaSettings),
                    scrollRequest = if (positionChanged) {
                        MangaScrollRequest(
                            id = System.nanoTime(),
                            itemIndex = targetIndex,
                            animated = false,
                        )
                    } else old.scrollRequest,
                )
            }
            if (positionChanged && pendingExplicitChapterIndex != session.chapterIndex) {
                executeSession(
                    MangaSessionCommand.VisiblePageChanged(
                        session.chapterIndex,
                        session.pageIndex,
                    )
                )
            }
        }
    }

    private fun recordAndCleanChapterCache(chapterIndex: Int, chapterContent: io.legado.app.domain.model.manga.MangaChapterContent) {
        // 记录当前章节的图片地址，供后续清理使用
        chapterImageUrls[chapterIndex] = chapterContent.pages.map { it.imageUrl }

        // 如果当前章节大于历史最高阅读章节，说明在往后看，触发旧章节清理
        if (chapterIndex > maxReadChapterIndex) {
            if (maxReadChapterIndex >= 0) {
                val bookUrl = _uiState.value.bookUrl
                if (bookUrl != null) {
                    for (i in 0 until chapterIndex) {
                        chapterImageUrls[i]?.forEach { url ->
                            val key = "manga-page:$bookUrl:$i:$url"
                            imageLoader.diskCache?.remove(key)
                        }
                        // 清理完后从记录表中移除，防止内存占用和重复查询
                        chapterImageUrls.remove(i)
                    }
                }
            }
            maxReadChapterIndex = chapterIndex
        }
    }

    fun showLoading() {
        _uiState.update {
            it.copy(
                isLoading = true,
                errorMessage = null,
                pages = persistentListOf(),
                currentItemIndex = 0,
                scrollRequest = null,
            )
        }
    }

    fun showError(message: String) {
        _uiState.update {
            it.copy(isLoading = false, errorMessage = MangaReaderText.Dynamic(message))
        }
    }

    private fun showError(message: String, @androidx.annotation.StringRes fallbackRes: Int) {
        if (message.isNotBlank()) showError(message)
        else {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    errorMessage = MangaReaderText.Resource(
                        fallbackRes,
                        persistentListOf(""),
                    ),
                )
            }
        }
    }

    private fun enqueueMessage(
        @androidx.annotation.StringRes resId: Int? = null,
        text: String? = null,
        args: kotlinx.collections.immutable.ImmutableList<String> = persistentListOf(),
    ) {
        _uiState.update { state ->
            state.copy(
                pendingMessages = (state.pendingMessages +
                    MangaReaderMessage(
                        id = System.nanoTime(),
                        content = if (resId != null) {
                            MangaReaderText.Resource(resId, args)
                        } else {
                            MangaReaderText.Dynamic(requireNotNull(text))
                        },
                    )
                ).toImmutableList()
            )
        }
    }

    override fun onCleared() {
        // 💡 新增：退出前把最后一段阅读时长落盘（onCleared 不能 suspend，用独立 IO 协程）
        val name = readRecordBookName
        val author = readRecordBookAuthor
        val delta = System.currentTimeMillis() - readSessionStartedAt
        readSessionFlushJob?.cancel()
        if (name != null && author != null && delta > 0L) {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { readRecordRepository.addReadTime(name, author, delta) }
            }
        }
        readerSession.close()
        super.onCleared()
    }

    private fun executeSession(command: MangaSessionCommand) {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { readerSession.execute(command) }
    }

    private suspend fun invalidateCurrentChapter() {
        val state = readerSession.state.value
        actionRepository.invalidateChapter(
            requireNotNull(state.book?.bookUrl),
            state.chapterIndex,
        )
    }

    // 💡 新增：开始追踪阅读时长
    private fun startReadTimeTracking(bookName: String, bookAuthor: String) {
        readRecordBookName = bookName
        readRecordBookAuthor = bookAuthor
        readSessionStartedAt = System.currentTimeMillis()
        readSessionFlushJob?.cancel()
        readSessionFlushJob = viewModelScope.launch {
            while (true) {
                delay(READ_RECORD_FLUSH_INTERVAL_MS)
                flushReadTime()
            }
        }
    }

    // 💡 新增：把自上次落盘以来的时长写入数据库
    private suspend fun flushReadTime() {
        val name = readRecordBookName ?: return
        val author = readRecordBookAuthor ?: return
        val now = System.currentTimeMillis()
        val delta = now - readSessionStartedAt
        if (delta <= 0L) return
        readSessionStartedAt = now
        runCatching { readRecordRepository.addReadTime(name, author, delta) }
    }

    private fun setMenuVisible(visible: Boolean) {
        _uiState.update {
            it.copy(
                menuVisible = visible,
                settingsCategory = if (visible) it.settingsCategory else null,
            )
        }
        _effects.tryEmit(MangaReaderEffect.SetSystemBarsVisible(visible))
    }

    private fun openSettings(category: MangaReaderSettingsCategory) {
        setMenuVisible(true)
        _uiState.update { it.copy(settingsCategory = category, activeSheet = null) }
    }

    private fun closeSettings() {
        _uiState.update { it.copy(settingsCategory = null) }
    }

    private fun emitAndHide(effect: MangaReaderEffect) {
        setMenuVisible(false)
        _effects.tryEmit(effect)
    }

    private fun closeReader() {
        when {
            readerSession.state.value.book != null &&
                    readerSession.state.value.book?.inBookshelf == false &&
                    _uiState.value.confirmAddToShelf -> {
                setMenuVisible(false)
                _uiState.update {
                    it.copy(
                        activeDialog = MangaReaderDialog.AddToShelf,
                        activeSheet = null,
                    )
                }
            }

            readerSession.state.value.book != null &&
                    readerSession.state.value.book?.inBookshelf == false -> {
                setMenuVisible(false)
                onIntent(MangaReaderIntent.DiscardCurrentBookAndExit)
            }

            else -> {
                setMenuVisible(false)
                _effects.tryEmit(MangaReaderEffect.Finish())
            }
        }
    }

    private fun showSheet(sheet: MangaReaderSheet) {
        setMenuVisible(false)
        _uiState.update { it.copy(activeSheet = sheet) }
    }

    private fun updateClickAction(index: Int, action: Int) {
        if (index !in 0..8) return
        updateMangaPreference { settings ->
            when (index) {
                0 -> settings.copy(clickActionTL = action)
                1 -> settings.copy(clickActionTC = action)
                2 -> settings.copy(clickActionTR = action)
                3 -> settings.copy(clickActionML = action)
                4 -> settings.copy(clickActionMC = action)
                5 -> settings.copy(clickActionMR = action)
                6 -> settings.copy(clickActionBL = action)
                7 -> settings.copy(clickActionBC = action)
                8 -> settings.copy(clickActionBR = action)
                else -> settings
            }
        }
    }

    private fun updateSetting(key: MangaReaderSettingKey, value: Int) {
        val enabled = value != 0
        when (key) {
            MangaReaderSettingKey.SCROLL_MODE -> {
                persistBookConfig {
                    readerSession.state.value.book?.bookUrl?.let { bookUrl ->
                        actionRepository.updateReadConfig(bookUrl) { mangaScrollMode = value }
                    }
                }
            }
            MangaReaderSettingKey.SIDE_PADDING -> {
                persistBookConfig {
                    readerSession.state.value.book?.bookUrl?.let { bookUrl ->
                        actionRepository.updateReadConfig(bookUrl) { webtoonSidePaddingDp = value }
                    }
                }
            }
            MangaReaderSettingKey.BACKGROUND_RED,
            MangaReaderSettingKey.BACKGROUND_GREEN,
            MangaReaderSettingKey.BACKGROUND_BLUE -> {
                updateMangaPreference { settings ->
                    val old = Color(settings.background)
                    val red = if (key == MangaReaderSettingKey.BACKGROUND_RED) value else (old.red * 255).toInt()
                    val green = if (key == MangaReaderSettingKey.BACKGROUND_GREEN) value else (old.green * 255).toInt()
                    val blue = if (key == MangaReaderSettingKey.BACKGROUND_BLUE) value else (old.blue * 255).toInt()
                    settings.copy(background = Color(red, green, blue).toArgb())
                }
            }
            MangaReaderSettingKey.AUTO_BACKGROUND -> updateMangaPreference { it.copy(autoBackground = enabled) }
            MangaReaderSettingKey.PAGE_SCALE_TYPE -> updateMangaPreference { it.copy(pageScaleType = value) }
            MangaReaderSettingKey.ZOOM_START_POSITION -> updateMangaPreference { it.copy(zoomStartPosition = value) }
            MangaReaderSettingKey.WIDE_PAGE_MODE -> updateMangaPreference { it.copy(widePageMode = value) }
            MangaReaderSettingKey.DOUBLE_PAGE_MODE -> updateMangaPreference { it.copy(doublePageMode = value) }
            MangaReaderSettingKey.DOUBLE_PAGE_COVER_SINGLE -> updateMangaPreference {
                it.copy(doublePageCoverSingle = value != 0)
            }

            MangaReaderSettingKey.DOUBLE_PAGE_INVERT -> updateMangaPreference {
                it.copy(doublePageInvert = value != 0)
            }

            MangaReaderSettingKey.DOUBLE_PAGE_SHIFT -> updateMangaPreference {
                it.copy(doublePageShift = value != 0)
            }
            MangaReaderSettingKey.DISABLE_SCALE -> updateMangaPreference { it.copy(disableMangaScale = enabled) }
            MangaReaderSettingKey.DISABLE_SCROLL_ANIMATION -> updateMangaPreference {
                it.copy(disableMangaScrollAnimation = enabled)
            }
            MangaReaderSettingKey.DISABLE_CROSS_FADE -> updateMangaPreference {
                it.copy(disableMangaCrossFade = enabled)
            }
            MangaReaderSettingKey.DISABLE_CLICK_SCROLL -> updateMangaPreference { it.copy(disableClickScroll = enabled) }
            MangaReaderSettingKey.LONG_PRESS -> updateMangaPreference { it.copy(longClick = enabled) }
            MangaReaderSettingKey.PRE_DOWNLOAD -> updateMangaPreference { it.copy(preDownloadNum = value) }
            MangaReaderSettingKey.CHAPTER_PREFETCH -> updateMangaPreference {
                it.copy(chapterPrefetchCount = value)
            }
            MangaReaderSettingKey.AUTO_OFFLINE_CACHE -> updateMangaPreference {
                it.copy(autoOfflineCache = enabled)
            }
            MangaReaderSettingKey.AUTO_READ_SPEED -> updateMangaPreference { it.copy(autoPageSpeed = value) }
            MangaReaderSettingKey.VOLUME_KEY_PAGE -> updateMangaPreference { it.copy(volumeKeyPage = enabled) }
            MangaReaderSettingKey.REVERSE_VOLUME_KEY_PAGE -> updateMangaPreference {
                it.copy(reverseVolumeKeyPage = enabled)
            }
            MangaReaderSettingKey.HIDE_MANGA_TITLE -> {
                viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    mangaSettingsGateway.update { it.copy(hideTitle = enabled) }
                    latestMangaSettings = mangaSettingsGateway.currentSettings
                    refreshContent()
                }
            }
            MangaReaderSettingKey.ENABLE_GRAY -> {
                updateMangaPreference {
                    it.copy(enableGray = enabled, enableEInk = if (enabled) false else it.enableEInk)
                }
            }
            MangaReaderSettingKey.ENABLE_EINK -> {
                updateMangaPreference {
                    it.copy(enableEInk = enabled, enableGray = if (enabled) false else it.enableGray)
                }
            }
            MangaReaderSettingKey.EINK_THRESHOLD -> updateMangaPreference { it.copy(eInkThreshold = value) }
            MangaReaderSettingKey.FILTER_RED,
            MangaReaderSettingKey.FILTER_GREEN,
            MangaReaderSettingKey.FILTER_BLUE,
            MangaReaderSettingKey.FILTER_ALPHA,
            // 💡 画质与性能：对比度归入颜色分支
            MangaReaderSettingKey.FILTER_CONTRAST,
            MangaReaderSettingKey.AUTO_BRIGHTNESS,
            MangaReaderSettingKey.BRIGHTNESS -> updateColorSetting(key, value)
            MangaReaderSettingKey.HIDE_FOOTER,
            MangaReaderSettingKey.HIDE_CHAPTER_NAME,
            MangaReaderSettingKey.HIDE_PAGE_NUMBER,
            MangaReaderSettingKey.HIDE_PAGE_NUMBER_LABEL,
            MangaReaderSettingKey.HIDE_CHAPTER,
            MangaReaderSettingKey.HIDE_CHAPTER_LABEL,
            MangaReaderSettingKey.HIDE_PROGRESS,
            MangaReaderSettingKey.HIDE_PROGRESS_LABEL,
            // 💡 时间电量开关也归入页脚设置分组
            MangaReaderSettingKey.HIDE_TIME_BATTERY,
            MangaReaderSettingKey.FOOTER_ALIGNMENT,
            // 💡 页脚（章节信息部分）偏移
            MangaReaderSettingKey.FOOTER_OFFSET_X,
            MangaReaderSettingKey.FOOTER_OFFSET_Y,
            // 💡 时间电量独立偏移
            MangaReaderSettingKey.TIME_BATTERY_OFFSET_X,
            MangaReaderSettingKey.TIME_BATTERY_OFFSET_Y -> updateFooterSetting(key, value)
            MangaReaderSettingKey.MENU_TOP_BAR_LIQUID_GLASS -> updateMangaPreference {
                it.copy(menuTopBarLiquidGlass = enabled)
            }
            MangaReaderSettingKey.MENU_BOTTOM_BAR_LIQUID_GLASS -> updateMangaPreference {
                it.copy(menuBottomBarLiquidGlass = enabled)
            }
            MangaReaderSettingKey.MENU_BOTTOM_BAR_FLOATING -> updateMangaPreference {
                it.copy(menuBottomBarFloating = enabled)
            }
            MangaReaderSettingKey.MENU_BOTTOM_BAR_BLUR -> updateMangaPreference {
                it.copy(menuBottomBarBlur = enabled)
            }
            MangaReaderSettingKey.MENU_TOP_BAR_COMPACT -> updateMangaPreference {
                it.copy(menuTopBarCompact = enabled)
            }
            MangaReaderSettingKey.MENU_COLOR_SOURCE -> updateMangaPreference {
                it.copy(menuColorSource = value)
            }
            MangaReaderSettingKey.MENU_SEED_COLOR -> updateMangaPreference {
                it.copy(menuSeedColor = value)
            }
            // 💡 画质与性能：5 个独立分支
            MangaReaderSettingKey.MANGA_HIGH_QUALITY_DECODE -> updateMangaPreference {
                it.copy(highQualityDecode = enabled)
            }
            MangaReaderSettingKey.MANGA_SHARPEN_ENABLED -> updateMangaPreference {
                it.copy(sharpenEnabled = enabled)
            }
            MangaReaderSettingKey.MANGA_SHARPEN_STRENGTH -> updateMangaPreference {
                it.copy(sharpenStrength = value)
            }
            MangaReaderSettingKey.MANGA_PREFETCH_ADAPTIVE -> updateMangaPreference {
                it.copy(prefetchAdaptive = enabled)
            }
            MangaReaderSettingKey.MANGA_PREFETCH_PRIORITIZE_CURRENT -> updateMangaPreference {
                it.copy(prefetchPrioritizeCurrent = enabled)
            }
        }
    }

    private fun updateMangaPreference(transform: (MangaSettings) -> MangaSettings) {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            mangaSettingsGateway.update(transform)
        }
    }

    private fun persistBookConfig(block: suspend () -> Unit) {
        viewModelScope.launch {
            bookConfigWriteMutex.withLock { block() }
        }
    }

    private fun updateColorSetting(key: MangaReaderSettingKey, value: Int) {
        val current = _uiState.value.settings
        updateMangaPreference { settings ->
            val config = GSON.fromJsonObject<MangaColorFilterConfig>(settings.colorFilter)
                .getOrNull() ?: MangaColorFilterConfig()
            when (key) {
                MangaReaderSettingKey.FILTER_RED -> config.r = value
                MangaReaderSettingKey.FILTER_GREEN -> config.g = value
                MangaReaderSettingKey.FILTER_BLUE -> config.b = value
                MangaReaderSettingKey.FILTER_ALPHA -> config.a = value
                // 💡 画质与性能：对比度
                MangaReaderSettingKey.FILTER_CONTRAST -> config.contrast = value
                MangaReaderSettingKey.AUTO_BRIGHTNESS -> config.autoBrightness = value != 0
                MangaReaderSettingKey.BRIGHTNESS -> config.l = value
                else -> Unit
            }
            settings.copy(colorFilter = config.toJson())
        }
        _effects.tryEmit(
            MangaReaderEffect.SetWindowBrightness(
                auto = if (key == MangaReaderSettingKey.AUTO_BRIGHTNESS) value != 0 else current.autoBrightness,
                brightness = if (key == MangaReaderSettingKey.BRIGHTNESS) value else current.brightness,
            )
        )
    }

    private fun updateFooterSetting(key: MangaReaderSettingKey, value: Int) {
        val enabled = value != 0
        updateMangaPreference { settings ->
            val config = GSON.fromJsonObject<MangaFooterConfig>(settings.footerConfig)
                .getOrNull() ?: MangaFooterConfig()
            when (key) {
                MangaReaderSettingKey.HIDE_FOOTER -> config.hideFooter = enabled
                MangaReaderSettingKey.HIDE_CHAPTER_NAME -> config.hideChapterName = enabled
                MangaReaderSettingKey.HIDE_PAGE_NUMBER -> config.hidePageNumber = enabled
                MangaReaderSettingKey.HIDE_PAGE_NUMBER_LABEL -> config.hidePageNumberLabel = enabled
                MangaReaderSettingKey.HIDE_CHAPTER -> config.hideChapter = enabled
                MangaReaderSettingKey.HIDE_CHAPTER_LABEL -> config.hideChapterLabel = enabled
                MangaReaderSettingKey.HIDE_PROGRESS -> config.hideProgressRatio = enabled
                MangaReaderSettingKey.HIDE_PROGRESS_LABEL -> config.hideProgressRatioLabel = enabled
                MangaReaderSettingKey.HIDE_TIME_BATTERY -> config.hideTimeBattery = enabled
                MangaReaderSettingKey.FOOTER_ALIGNMENT -> config.footerOrientation = value
                // 💡 页脚（章节信息部分）偏移
                MangaReaderSettingKey.FOOTER_OFFSET_X -> config.footerOffsetXDp = value
                MangaReaderSettingKey.FOOTER_OFFSET_Y -> config.footerOffsetYDp = value
                // 💡 时间电量独立偏移
                MangaReaderSettingKey.TIME_BATTERY_OFFSET_X -> config.timeBatteryOffsetXDp = value
                MangaReaderSettingKey.TIME_BATTERY_OFFSET_Y -> config.timeBatteryOffsetYDp = value
                else -> Unit
            }
            settings.copy(footerConfig = GSON.toJson(config))
        }
    }

    private var visibleItemRange: IntRange? = null

    private fun openChapter(chapterIndex: Int, pageIndex: Int) {
        if (chapterIndex !in 0 until readerSession.state.value.chapterCount ||
            pendingExplicitChapterIndex == chapterIndex
        ) return
        pendingExplicitChapterIndex = chapterIndex
        pagerScrollInProgress = false
        deferredReadySession = null
        refreshContentJob?.cancel()
        showExplicitChapterPlaceholder(chapterIndex, pageIndex, errorMessage = null)
        executeSession(MangaSessionCommand.OpenChapter(chapterIndex, pageIndex))
    }

    private fun cacheChapters(selection: MangaCacheSelection) {
        val state = _uiState.value
        if (!state.cacheAvailable || state.chapterCount <= 0) return
        if (selection == MangaCacheSelection.FOLLOWING && state.chapterIndex >= state.chapterCount - 1) {
            enqueueMessage(resId = R.string.manga_reader_no_next_chapter)
            _uiState.update { it.copy(activeSheet = null) }
            return
        }
        _uiState.update { it.copy(activeSheet = null) }
        launchAction(successMessageRes = R.string.manga_reader_cache_enqueued) {
            when (selection) {
                MangaCacheSelection.CURRENT -> cacheBookChaptersUseCase.execute(
                    state.bookUrl,
                    listOf(state.chapterIndex),
                )

                MangaCacheSelection.FOLLOWING -> cacheBookChaptersUseCase.executeRange(
                    state.bookUrl,
                    state.chapterIndex + 1,
                    state.chapterCount - 1,
                )

                MangaCacheSelection.ALL -> cacheBookChaptersUseCase.executeRange(
                    state.bookUrl,
                    0,
                    state.chapterCount - 1,
                )
            }
        }
    }

    private fun executePageAction(action: MangaPageAction) {
        val sheet = _uiState.value.activeSheet as? MangaReaderSheet.PageActions ?: return
        val selected = pageForKey(sheet.pageKey) ?: return
        val companion = sheet.companionPageKey?.let(::pageForKey)
        val pages = if (sheet.companionBeforePage) listOfNotNull(companion, selected)
        else listOfNotNull(selected, companion)
        val urls = when (action) {
            MangaPageAction.SAVE_SPREAD, MangaPageAction.SHARE_SPREAD, MangaPageAction.COPY_SPREAD -> pages.map { it.imageUrl }
            else -> listOf(selected.imageUrl)
        }
        _uiState.update { it.copy(activeSheet = null) }
        launchAction(
            successMessageRes = when (action) {
                MangaPageAction.SAVE, MangaPageAction.SAVE_SPREAD -> R.string.manga_reader_image_saved
                MangaPageAction.SET_COVER -> R.string.manga_reader_cover_set
                else -> null
            },
            failureMessageRes = R.string.manga_reader_action_failed,
        ) {
            val book = requireNotNull(readerSession.state.value.book)
            when (action) {
                MangaPageAction.SAVE, MangaPageAction.SAVE_SPREAD -> check(
                    actionRepository.saveImages(urls, book.bookUrl, book.sourceOrigin)
                )

                MangaPageAction.SHARE, MangaPageAction.SHARE_SPREAD -> _effects.tryEmit(
                    MangaReaderEffect.ShareImage(
                        actionRepository.prepareImageFile(
                            urls,
                            book.bookUrl,
                            book.sourceOrigin
                        ).path
                    )
                )

                MangaPageAction.COPY, MangaPageAction.COPY_SPREAD -> _effects.tryEmit(
                    MangaReaderEffect.CopyImage(
                        actionRepository.prepareImageFile(
                            urls,
                            book.bookUrl,
                            book.sourceOrigin
                        ).path
                    )
                )

                MangaPageAction.SET_COVER -> actionRepository.setBookCover(
                    book.bookUrl,
                    selected.imageUrl
                )
            }
        }
    }

    private fun pageForKey(key: String) =
        _uiState.value.pages.firstOrNull { it.key == key } as? MangaReaderItemUi.Page

    private fun findSpreadCompanion(key: String): Pair<String, Boolean>? {
        val state = _uiState.value
        if (state.settings.doublePageMode == 0) return null
        val pages = state.pages.filterIsInstance<MangaReaderItemUi.Page>()
        val index = pages.indexOfFirst { it.key == key }
        val selected = pages.getOrNull(index) ?: return null
        pages.getOrNull(index + 1)?.takeIf { it.chapterIndex == selected.chapterIndex }
            ?.let { return it.key to false }
        return pages.getOrNull(index - 1)?.takeIf { it.chapterIndex == selected.chapterIndex }
            ?.let { it.key to true }
    }

    private fun showExplicitChapterPlaceholder(
        chapterIndex: Int,
        pageIndex: Int,
        errorMessage: String?,
    ) {
        val title = readerSession.state.value.book?.chapterTitles?.getOrNull(chapterIndex)
            ?: appCtx.getString(R.string.manga_reader_transition_chapter_number, chapterIndex + 1)
        val failed = errorMessage != null
        val message = if (failed) {
            errorMessage.takeUnless(String::isBlank)
                ?: appCtx.getString(R.string.manga_reader_unknown_error)
        } else {
            appCtx.getString(R.string.manga_reader_loading_selected_chapter, title)
        }
        _uiState.update { state ->
            state.copy(
                chapterName = title,
                chapterIndex = chapterIndex,
                currentPage = pageIndex.coerceAtLeast(0),
                pageCount = 0,
                pages = persistentListOf(
                    mangaChapterLoadingItem(
                        chapterIndex = chapterIndex,
                        message = message,
                        failed = failed,
                    )
                ),
                currentItemIndex = 0,
                isLoading = false,
                isChapterLoading = !failed,
                pendingChapterIndex = chapterIndex,
                errorMessage = null,
                scrollRequest = null,
                navigationId = System.nanoTime(),
            )
        }
    }

    private fun openRelativeChapter(direction: Int) {
        openChapter(readerSession.state.value.chapterIndex + direction, pageIndex = 0)
    }

    private fun requestPageStep(direction: Int) {
        val state = _uiState.value
        val range = visibleItemRange?.takeIf { state.currentItemIndex in it }
            ?: (state.currentItemIndex..state.currentItemIndex)
        val target = nextPageItemIndex(
            items = state.pages,
            currentIndex = if (direction > 0) range.last else range.first,
            direction = direction,
        )
        if (target == null) {
            openRelativeChapter(direction)
            return
        }
        _uiState.update {
            it.copy(scrollRequest = MangaScrollRequest(System.nanoTime(), target, !it.settings.disableScrollAnimation))
        }
    }

    private fun seekToPage(pageIndex: Int) {
        val state = _uiState.value
        val target = state.pages.indexOfFirst {
            it is MangaReaderItemUi.Page &&
                    it.chapterIndex == readerSession.state.value.chapterIndex && it.pageIndex == pageIndex
        }
        if (target >= 0) {
            _uiState.update { it.copy(scrollRequest = MangaScrollRequest(System.nanoTime(), target, false)) }
        }
    }

    private fun updateVisibleItem(
        itemIndex: Int,
        firstItemIndex: Int,
        lastItemIndex: Int,
        currentChapterVisible: Boolean,
        navigationId: Long,
    ) {
        val state = _uiState.value
        if (navigationId != state.navigationId) return
        if (state.isLoading) return
        val item = state.pages.getOrNull(itemIndex)
        if (item !is MangaReaderItemUi.Page) {
            visibleItemRange = firstItemIndex.coerceAtMost(lastItemIndex)..
                    lastItemIndex.coerceAtLeast(firstItemIndex)
            if (state.scrollRequest?.itemIndex == itemIndex) {
                _uiState.update { it.copy(scrollRequest = null) }
            }
            return
        }
        val requestedItemIndex = state.scrollRequest?.itemIndex
        if (!acceptsMangaVisibleItem(requestedItemIndex, itemIndex)) return
        if (pendingExplicitChapterIndex != null &&
            item.chapterIndex != readerSession.state.value.chapterIndex
        ) return
        visibleItemRange = firstItemIndex.coerceAtMost(lastItemIndex)..
            lastItemIndex.coerceAtLeast(firstItemIndex)
        when (mangaChapterSwitchDecision(
            currentChapterIndex = readerSession.state.value.chapterIndex,
            visibleChapterIndex = item.chapterIndex,
            currentChapterVisible = currentChapterVisible,
        )) {
            MangaChapterSwitch.NEXT,
            MangaChapterSwitch.PREVIOUS -> executeSession(
                MangaSessionCommand.PromoteVisibleChapter(item.chapterIndex, item.pageIndex)
            )
            MangaChapterSwitch.NONE -> Unit
        }
        executeSession(MangaSessionCommand.VisiblePageChanged(item.chapterIndex, item.pageIndex))
        val completedExplicitNavigation = pendingExplicitChapterIndex == item.chapterIndex
        _uiState.update {
            it.copy(
                currentItemIndex = itemIndex,
                footerItemIndex = null,
                currentPage = item.pageIndex,
                pageCount = item.pageCount,
                scrollRequest = it.scrollRequest?.takeUnless { request ->
                    request.itemIndex == itemIndex
                },
                navigationId = if (completedExplicitNavigation) System.nanoTime() else it.navigationId,
            )
        }
        if (completedExplicitNavigation) {
            pendingExplicitChapterIndex = null
        }
    }

    private fun updateFooterItem(itemIndex: Int, navigationId: Long) {
        _uiState.update { state ->
            if (navigationId != state.navigationId ||
                state.pages.getOrNull(itemIndex) !is MangaReaderItemUi.Page
            ) {
                state
            } else {
                state.copy(footerItemIndex = itemIndex)
            }
        }
    }

    private fun markPageLoading(requestId: MangaPageRequestId, force: Boolean = false) {
        updatePageLoadState(requestId, MangaPageLoadState.Loading(), force)
    }

    private fun applyPageProgress(imageUrl: String, percentage: Int) {
        _uiState.update { state ->
            val anchor = mangaImagePrefetchIndex(
                state.settings.scrollMode,
                state.currentItemIndex,
                state.footerItemIndex
            )
            val from = anchor - PAGE_PROGRESS_WINDOW
            val to = anchor + PAGE_PROGRESS_WINDOW
            var changed = false
            val pages = state.pages.mapIndexed { itemIndex, item ->
                if (itemIndex < from || itemIndex > to) {
                    item
                } else if (item is MangaReaderItemUi.Page &&
                    item.imageUrl == imageUrl &&
                    item.loadState is MangaPageLoadState.Loading &&
                    item.loadState.progress != percentage
                ) {
                    changed = true
                    item.copy(loadState = MangaPageLoadState.Loading(percentage))
                } else {
                    item
                }
            }
            if (changed) state.copy(pages = pages.toImmutableList()) else state
        }
    }

    private fun updatePageLoadState(
        requestId: MangaPageRequestId,
        loadState: MangaPageLoadState,
        force: Boolean = false,
    ) {
        _uiState.update { state ->
            val index = state.pages.indexOfFirst { it.key == requestId.key }
            val page =
                state.pages.getOrNull(index) as? MangaReaderItemUi.Page ?: return@update state
            val next = page.reduceImageLoad(requestId, loadState, force)
            if (next === page) return@update state
            state.copy(pages = state.pages.mapIndexed { itemIndex, item ->
                if (itemIndex == index) next else item
            }.toImmutableList())
        }
    }

    private fun retryPage(key: String) {
        CoverFetcher.clearFailure(
            (_uiState.value.pages.firstOrNull { it.key == key } as? MangaReaderItemUi.Page)
                ?.imageUrl ?: return
        )
        _uiState.update { state ->
            val index = state.pages.indexOfFirst { it.key == key }
            val page =
                state.pages.getOrNull(index) as? MangaReaderItemUi.Page ?: return@update state
            state.copy(
                pages = state.pages.mapIndexed { itemIndex, item ->
                    if (itemIndex == index) page.copy(
                        loadState = MangaPageLoadState.Queued,
                        retryRevision = page.retryRevision + 1,
                    ) else item
                }.toImmutableList(),
            )
        }
    }

    private fun retryFailedPages(chapterIndex: Int) {
        _uiState.value.pages.filterIsInstance<MangaReaderItemUi.Page>()
            .filter { it.chapterIndex == chapterIndex && it.loadState is MangaPageLoadState.Failed }
            .forEach { CoverFetcher.clearFailure(it.imageUrl) }
        _uiState.update { state ->
            state.copy(pages = state.pages.map { item ->
                if (item is MangaReaderItemUi.Page && item.chapterIndex == chapterIndex &&
                    item.loadState is MangaPageLoadState.Failed
                ) item.copy(
                    loadState = MangaPageLoadState.Queued,
                    retryRevision = item.retryRevision + 1
                )
                else item
            }.toImmutableList())
        }
    }

    private fun readSettings(settings: MangaSettings): MangaReaderSettings {
        val colorFilter = GSON.fromJsonObject<MangaColorFilterConfig>(settings.colorFilter)
            .getOrNull() ?: MangaColorFilterConfig()
        val footer = GSON.fromJsonObject<MangaFooterConfig>(settings.footerConfig)
            .getOrNull() ?: MangaFooterConfig()
        return MangaReaderSettings(
        scrollMode = readerSession.state.value.book?.scrollMode ?: settings.scrollMode,
        sidePaddingPercent = readerSession.state.value.book?.sidePaddingDp
            ?: settings.webtoonSidePaddingDp,
        backgroundColor = Color(settings.background),
        autoBackground = settings.autoBackground,
        pageScaleType = settings.pageScaleType,
        zoomStartPosition = settings.zoomStartPosition,
        widePageMode = settings.widePageMode,
        doublePageMode = settings.doublePageMode,
            doublePageCoverSingle = settings.doublePageCoverSingle,
            doublePageInvert = settings.doublePageInvert,
            doublePageShift = settings.doublePageShift,
        disableScale = settings.disableMangaScale,
        disableScrollAnimation = settings.disableMangaScrollAnimation,
        disableCrossFade = settings.disableMangaCrossFade,
        disableClickScroll = settings.disableClickScroll,
            longPressEnabled = settings.longClick,
            preDownloadCount = settings.preDownloadNum,
            chapterPrefetchCount = settings.chapterPrefetchCount,
            autoOfflineCache = settings.autoOfflineCache,
            autoReadSpeed = settings.autoPageSpeed,
        volumeKeyPage = settings.volumeKeyPage,
        reverseVolumeKeyPage = settings.reverseVolumeKeyPage,
        hideMangaTitle = settings.hideTitle,
        autoBrightness = colorFilter.autoBrightness,
        brightness = colorFilter.l,
        enableGray = settings.enableGray,
        enableEInk = settings.enableEInk,
        eInkThreshold = settings.eInkThreshold,
        filterRed = colorFilter.r,
        filterGreen = colorFilter.g,
        filterBlue = colorFilter.b,
        filterAlpha = colorFilter.a,
        hideFooter = footer.hideFooter,
        hideChapterName = footer.hideChapterName,
        hidePageNumber = footer.hidePageNumber,
        hidePageNumberLabel = footer.hidePageNumberLabel,
        hideChapter = footer.hideChapter,
        hideChapterLabel = footer.hideChapterLabel,
        hideProgress = footer.hideProgressRatio,
        hideProgressLabel = footer.hideProgressRatioLabel,
        hideTimeBattery = footer.hideTimeBattery,
        footerAlignment = footer.footerOrientation,
        // 💡 页脚（章节信息部分）偏移
        footerOffsetX = footer.footerOffsetXDp,
        footerOffsetY = footer.footerOffsetYDp,
        // 💡 时间电量独立偏移
        timeBatteryOffsetX = footer.timeBatteryOffsetXDp,
        timeBatteryOffsetY = footer.timeBatteryOffsetYDp,
        // 💡 画质与性能
        contrast = colorFilter.contrast,
        highQualityDecode = settings.highQualityDecode,
        sharpenEnabled = settings.sharpenEnabled,
        sharpenStrength = settings.sharpenStrength,
        prefetchAdaptive = settings.prefetchAdaptive,
        prefetchPrioritizeCurrent = settings.prefetchPrioritizeCurrent,
        menuTopBarLiquidGlass = settings.menuTopBarLiquidGlass,
        menuBottomBarLiquidGlass = settings.menuBottomBarLiquidGlass,
        menuBottomBarFloating = settings.menuBottomBarFloating,
        menuBottomBarBlur = settings.menuBottomBarBlur,
        menuTopBarCompact = settings.menuTopBarCompact,
        menuColorSource = settings.menuColorSource,
        menuSeedColor = Color(settings.menuSeedColor),
        menuPaletteStyle = settings.menuPaletteStyle,
        sourceOrigin = readerSession.state.value.book?.sourceOrigin,
        clickActions = listOf(
            settings.clickActionTL,
            settings.clickActionTC,
            settings.clickActionTR,
            settings.clickActionML,
            settings.clickActionMC,
            settings.clickActionMR,
            settings.clickActionBL,
            settings.clickActionBC,
            settings.clickActionBR,
        ).toImmutableList(),
    )
    }

    private fun io.legado.app.data.entities.BookProgress.toMangaProgress() = MangaProgressState(
        bookName = name,
        bookAuthor = author,
        chapterIndex = durChapterIndex,
        pageIndex = durChapterPos,
        chapterTitle = durChapterTitle,
        updatedAt = durChapterTime,
    )

    private fun MangaProgressState.toBookProgress() = io.legado.app.data.entities.BookProgress(
        name = bookName,
        author = bookAuthor,
        durChapterIndex = chapterIndex,
        durChapterPos = pageIndex,
        durChapterTime = updatedAt,
        durChapterTitle = chapterTitle,
    )
}