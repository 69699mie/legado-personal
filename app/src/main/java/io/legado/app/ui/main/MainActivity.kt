package io.legado.app.ui.main

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.graphics.scale
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.ui.NavDisplay
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.core.ui.player.playerUnderlaySemantics
import io.legado.app.data.repository.ReadAloudSettingsRepository
import io.legado.app.domain.gateway.BackupSettingsGateway
import io.legado.app.domain.gateway.MangaSettingsGateway
import io.legado.app.domain.gateway.OtherSettingsGateway
import io.legado.app.domain.gateway.PlaybackCapsuleGateway
import io.legado.app.domain.gateway.ThemeSettingsGateway
import io.legado.app.domain.model.PlaybackCapsuleSource
import io.legado.app.domain.model.PlaybackCapsuleState
import io.legado.app.help.LocalNetworkAccess
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.LocalNetworkBlockedNotifier
import io.legado.app.help.storage.Backup
import io.legado.app.help.update.AppUpdateGitHub
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.AudioPlay
import io.legado.app.service.WebService
import io.legado.app.ui.about.MarkdownSheet
import io.legado.app.ui.about.UpdateDialog
import io.legado.app.ui.book.audio.AudioPlayViewModel
import io.legado.app.ui.book.read.ReadAloudControlsRequestBus
import io.legado.app.ui.book.read.ReadBookInputHandler
import io.legado.app.ui.book.read.ReadBookRouteHost
import io.legado.app.ui.book.read.page.entities.PageDirection
import io.legado.app.ui.book.readaloud.ReadAloudPlayerMorphHost
import io.legado.app.ui.book.readaloud.ReadAloudPlayerOverlayBus
import io.legado.app.ui.book.readaloud.ReadAloudShellHost
import io.legado.app.ui.book.readaloud.morph.CapsuleAnchorKind
import io.legado.app.ui.book.readaloud.morph.LocalReadAloudMorph
import io.legado.app.ui.book.readaloud.morph.rememberReadAloudMorphState
import io.legado.app.ui.book.readaloud.player.ReadAloudPlayerViewModel
import io.legado.app.ui.main.bookshelf.BookshelfCoverPreloader
import io.legado.app.ui.theme.LocalAppUiConfiguration
import io.legado.app.ui.welcome.WelcomeActivity
import io.legado.app.ui.widget.components.privacy.PrivateAppStartGate
import io.legado.app.utils.LogUtils
import io.legado.app.utils.eventBus.FlowEventBus
import io.legado.app.utils.getPrefString
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * 主界面
 */
open class MainActivity : BaseComposeActivity(), AudioPlay.CallBack {

    private data class RouteEvent(
        val route: NavKey,
        val resetToHome: Boolean,
    )

    /** 当前激活的有声书播放器 ViewModel（由有声书路由在生命周期内设置/清理） */
    internal var activeAudioPlayViewModel: AudioPlayViewModel? = null

    /** 全局 Compose 文本弹层状态，供遗留命令式路径展示 Markdown/文本内容 */
    private val textSheetFlow = MutableStateFlow<TextSheetData?>(null)

    fun showTextSheet(title: String, content: String, onDismiss: (() -> Unit)? = null) {
        textSheetFlow.value = TextSheetData(title, content, onDismiss)
    }

    companion object {
        private const val KEY_RESTORE_READ_ROUTE = "restoreReadRoute"
        private const val KEY_RESTORE_READ_BOOK_URL = "restoreReadBookUrl"
        private const val KEY_RESTORE_READ_ALOUD = "restoreReadAloud"
        private const val KEY_RESTORE_READ_IN_BOOKSHELF = "restoreReadInBookshelf"
        private const val KEY_RESTORE_READ_CHAPTER_CHANGED = "restoreReadChapterChanged"
        private val startupUpdateCheckGate = ProcessStartupUpdateCheckGate()

        @Volatile
        var hasActiveReadBookRoute: Boolean = false

        @Volatile
        var hasActiveAudioPlayRoute: Boolean = false

        @Volatile
        var hasActiveSourceLoginRoute: Boolean = false

        fun createLauncherIntent(context: Context): Intent =
            MainIntent.createLauncherIntent(context)

        fun createHomeIntent(context: Context): Intent = MainIntent.createHomeIntent(context)
        fun createSourceLoginIntent(
            context: Context,
            type: io.legado.app.ui.login.SourceLoginType,
            sourceKey: String? = null,
            bookUrl: String? = null,
        ): Intent = MainIntent.createSourceLoginIntent(context, type, sourceKey, bookUrl)

        fun createWebViewIntent(
            context: Context,
            title: String? = null,
            url: String,
            sourceOrigin: String? = null,
            sourceName: String? = null,
            sourceType: Int? = null,
            sourceVerificationEnable: Boolean = false,
            refetchAfterSuccess: Boolean = true,
            html: String? = null,
        ): Intent = MainIntent.createWebViewIntent(
            context, title, url, sourceOrigin, sourceName, sourceType,
            sourceVerificationEnable, refetchAfterSuccess, html,
        )

        fun createBookSourceManageIntent(context: Context, importSource: String? = null) =
            MainIntent.createBookSourceManageIntent(context, importSource)

        fun createBookSourceEditIntent(context: Context, sourceUrl: String? = null) =
            MainIntent.createBookSourceEditIntent(context, sourceUrl)

        fun createRssSourceManageIntent(context: Context) =
            MainIntent.createRssSourceManageIntent(context)

        fun createRssSourceEditIntent(context: Context, sourceUrl: String? = null) =
            MainIntent.createRssSourceEditIntent(context, sourceUrl)

        fun createBookSourceDebugIntent(context: Context, sourceUrl: String?) =
            MainIntent.createBookSourceDebugIntent(context, sourceUrl)

        fun createRssSourceDebugIntent(context: Context, sourceUrl: String?) =
            MainIntent.createRssSourceDebugIntent(context, sourceUrl)
        fun createIntent(context: Context, configTag: String? = null): Intent =
            MainIntent.createIntent(context, configTag)

        fun createRssSortIntent(
            context: Context,
            sourceUrl: String,
            sortUrl: String? = null,
            key: String? = null
        ): Intent = MainIntent.createRssSortIntent(context, sourceUrl, sortUrl, key)

        fun createRssReadIntent(
            context: Context,
            title: String? = null,
            origin: String,
            link: String? = null,
            openUrl: String? = null
        ): Intent = MainIntent.createRssReadIntent(context, title, origin, link, openUrl)

        fun createBookshelfManageScreenIntent(context: Context, groupId: Long = -1L): Intent =
            MainIntent.createBookshelfManageScreenIntent(context, groupId)

        fun createCacheIntent(context: Context, groupId: Long = -1L): Intent =
            MainIntent.createCacheIntent(context, groupId)

        fun createBookCacheManageIntent(context: Context): Intent =
            MainIntent.createBookCacheManageIntent(context)

        fun createReadBookIntent(
            context: Context,
            bookUrl: String? = null,
            readAloud: Boolean = false,
            inBookshelf: Boolean = true,
            chapterChanged: Boolean = false,
        ): Intent = MainIntent.createReadBookIntent(
            context = context,
            bookUrl = bookUrl,
            readAloud = readAloud,
            inBookshelf = inBookshelf,
            chapterChanged = chapterChanged,
        )

        fun createReadBookMediaControlIntent(context: Context): Intent =
            MainIntent.createReadBookMediaControlIntent(context)

        fun createReadMangaIntent(
            context: Context,
            bookUrl: String? = null,
            inBookshelf: Boolean = true,
            chapterChanged: Boolean = false,
        ): Intent = MainIntent.createReadMangaIntent(
            context = context,
            bookUrl = bookUrl,
            inBookshelf = inBookshelf,
            chapterChanged = chapterChanged,
        )

        fun createAudioPlayIntent(
            context: Context,
            bookUrl: String? = null,
            inBookshelf: Boolean = true,
        ): Intent = MainIntent.createAudioPlayIntent(
            context = context,
            bookUrl = bookUrl,
            inBookshelf = inBookshelf,
        )

        fun createSearchIntent(
            context: Context,
            key: String? = null,
            scopeRaw: String? = null
        ): Intent = MainIntent.createSearchIntent(context, key, scopeRaw)

        fun createBookInfoIntent(
            context: Context,
            name: String? = null,
            author: String? = null,
            bookUrl: String,
            origin: String? = null,
            coverPath: String? = null
        ): Intent =
            MainIntent.createBookInfoIntent(context, name, author, bookUrl, origin, coverPath)

        fun createBookCharacterDetailIntent(
            context: Context,
            bookUrl: String,
            characterId: String? = null,
        ): Intent = MainIntent.createBookCharacterDetailIntent(context, bookUrl, characterId)

        fun createBookCharacterNetworkIntent(
            context: Context,
            bookUrl: String,
        ): Intent = MainIntent.createBookCharacterNetworkIntent(context, bookUrl)

        fun createBookKnowledgeListIntent(
            context: Context,
            bookUrl: String,
        ): Intent = MainIntent.createBookKnowledgeListIntent(context, bookUrl)

        fun createBookCharacterListIntent(
            context: Context,
            bookUrl: String,
        ): Intent = MainIntent.createBookCharacterListIntent(context, bookUrl)

        fun createBookKnowledgeDetailIntent(
            context: Context,
            bookUrl: String,
            entryId: String? = null,
        ): Intent = MainIntent.createBookKnowledgeDetailIntent(context, bookUrl, entryId)

        fun createBookEventListIntent(
            context: Context,
            bookUrl: String,
        ): Intent = MainIntent.createBookEventListIntent(context, bookUrl)

        fun createBookEventDetailIntent(
            context: Context,
            bookUrl: String,
            eventId: String? = null,
        ): Intent = MainIntent.createBookEventDetailIntent(context, bookUrl, eventId)

        fun createExploreShowIntent(
            context: Context,
            exploreName: String? = null,
            sourceUrl: String,
            exploreUrl: String? = null,
        ): Intent = MainIntent.createExploreShowIntent(context, exploreName, sourceUrl, exploreUrl)
    }

    private val viewModel by viewModel<MainViewModel>()
    private val bookshelfCoverPreloader by inject<BookshelfCoverPreloader>()
    private val otherSettingsGateway by inject<OtherSettingsGateway>()
    private val mangaSettingsGateway by inject<MangaSettingsGateway>()
    private val backupSettingsGateway by inject<BackupSettingsGateway>()
    private val readAloudSettingsRepository by inject<ReadAloudSettingsRepository>()
    private val themeGateway by inject<ThemeSettingsGateway>()
    internal val navRouteTracker by inject<MainNavRouteTracker>()
    private val routeEvents = MutableSharedFlow<RouteEvent>(extraBufferCapacity = 1)
    
    private val webServiceLocalNetworkPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            WebService.startForeground(this)
        } else {
            toastOnUi(R.string.web_service_local_network_permission_denied)
        }
    }

    private val localNetworkRequestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            toastOnUi(R.string.local_network_permission_required)
        }
    }
    private var shouldApplyDefaultToRead = true
    private var restoredReadBookRoute: MainRouteReadBook? = null
    internal var activeReadBookInputHandler: ReadBookInputHandler? = null
    internal var activeReadBookRoute: MainRouteReadBook? = null
    internal var activeMangaKeyHandler: ((Int) -> Boolean)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        shouldApplyDefaultToRead = savedInstanceState == null
        restoredReadBookRoute = savedInstanceState?.restoreReadBookRoute()
            ?: navRouteTracker.lastReadBookRoute()
        super.onCreate(savedInstanceState)

        // 🌟 新增：恢复动态取色（必须在 installSplashScreen 和 super.onCreate 之后）
        if (getPrefString("app_theme", "0") == "12") {
            if (themeGateway.currentSettings.customMode == "accent")
                setTheme(R.style.ThemeOverlay_WhiteBackground)

            val colorImagePath = getPrefString(PreferKey.colorImage)
            if (!colorImagePath.isNullOrBlank()) {
                val file = File(colorImagePath)
                if (file.exists()) {
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    if (bitmap != null) {
                        val colorAccuracy = true
                        val targetWidth = if (colorAccuracy) (bitmap.width / 4).coerceAtMost(256) else 16
                        val targetHeight = if (colorAccuracy) (bitmap.height / 4).coerceAtMost(256) else 16
                        val scaledBitmap = bitmap.scale(targetWidth, targetHeight, false)

                        val options = DynamicColorsOptions.Builder()
                            .setContentBasedSource(scaledBitmap)
                            .build()

                        DynamicColors.applyToActivitiesIfAvailable(application, options)
                        bitmap.recycle()
                    }
                }
            } else {
                DynamicColors.applyToActivitiesIfAvailable(
                    application,
                    DynamicColorsOptions.Builder()
                        .setContentBasedSource(this.primaryColor)
                        .build()
                )
            }
        }

        if (checkStartupRoute()) return
        val shouldAutoCheckUpdate = startupUpdateCheckGate.consume(
            otherSettingsGateway.currentSettings.autoCheckUpdateOnStart
        )

        val requestWebService = otherSettingsGateway.currentSettings.webServiceAutoStart ||
                intent?.getBooleanExtra(MainIntent.EXTRA_WEB_SERVICE_LOCAL_NETWORK, false) == true
        if (requestWebService) {
            startWebServiceWithLocalNetworkPermission()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                FlowEventBus.with<Unit>(EventBus.LOCAL_NETWORK_PERMISSION_REQUIRED).collect {
                    requestLocalNetworkPermissionIfBlocked()
                }
            }
        }

        lifecycleScope.launch {
            upVersion()
            backupSync()
            val isAutoRefreshedBook = savedInstanceState?.getBoolean("isAutoRefreshedBook") ?: false
            if (otherSettingsGateway.currentSettings.autoRefresh && !isAutoRefreshedBook) {
                viewModel.upAllBookToc()
            }
            if (shouldAutoCheckUpdate) {
                checkUpdateOnStart()
            }
        }

        lifecycleScope.launch {
            runCatching { bookshelfCoverPreloader.preloadCurrentGroupFirstScreen() }
        }
    }

    private fun startWebServiceWithLocalNetworkPermission() {
        if (LocalNetworkAccess.isGranted(this)) {
            WebService.startForeground(this)
        } else {
            webServiceLocalNetworkPermissionLauncher.launch("android.permission.ACCESS_LOCAL_NETWORK")
        }
    }

    override fun onResume() {
        super.onResume()
        requestLocalNetworkPermissionIfBlocked()
        // 从悬浮态/自由窗口切回时，强制重算一次 Insets 防截断
        requestInsetsAndLayout()
    }

    private fun requestLocalNetworkPermissionIfBlocked() {
        if (!LocalNetworkBlockedNotifier.consumePermissionRequest()) return
        if (LocalNetworkAccess.isGranted(this)) return
        localNetworkRequestPermissionLauncher.launch("android.permission.ACCESS_LOCAL_NETWORK")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(MainIntent.EXTRA_OPEN_READ_ALOUD_PLAYER, false)) {
            ReadAloudPlayerOverlayBus.request()
            return
        }
        if (intent.getBooleanExtra(MainIntent.EXTRA_WEB_SERVICE_LOCAL_NETWORK, false)) {
            startWebServiceWithLocalNetworkPermission()
            return
        }
        if (!intent.hasExplicitStartRoute()) return
        routeEvents.tryEmit(
            RouteEvent(
                route = MainNavigator.resolveStartRoute(intent),
                resetToHome = MainIntent.shouldOpenRouteWithHomeParent(intent),
            )
        )
    }

    // 🌟 监听多窗口状态变化，强制重新分发 Insets 与触发 Layout
    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        requestInsetsAndLayout()
    }

    // 🌟 监听全局配置变化（抛掷操作会触发此回调）
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        requestInsetsAndLayout()
    }

    // 🌟 原生重写画中画回调，彻底杜绝任何类型/包名解析错误
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (!isInPictureInPictureMode) {
            // 退出画中画时，延迟 300ms 等待系统窗口动画稳定，再强制刷新
            window?.decorView?.postDelayed({
                // 增加安全锁：确保视图依然附着在窗口上，防止销毁瞬间引发空指针
                if (window?.decorView?.isAttachedToWindow == true) {
                    requestInsetsAndLayout()
                }
            }, 300L)
        }
    }

    // 🌟 强制系统重算 Insets 并重新布局
    private fun requestInsetsAndLayout() {
        window?.decorView?.let { decorView ->
            ViewCompat.requestApplyInsets(decorView)
            decorView.requestLayout()
        }
    }

    @OptIn(ExperimentalSharedTransitionApi::class)
    @Composable
    override fun Content() {
        val orientation = resources.configuration.orientation
        val smallestWidthDp = resources.configuration.smallestScreenWidthDp
        val configuration = LocalAppUiConfiguration.current
        val predictiveBackEnabled by rememberUpdatedState(configuration.appShell.predictiveBackEnabled)
        val tabletInterface = configuration.appShell.tabletInterface
        val defaultToReadFlow = remember(otherSettingsGateway) {
            otherSettingsGateway.settings
                .map { it.defaultToRead }
                .distinctUntilChanged()
        }
        val defaultToRead by defaultToReadFlow.collectAsStateWithLifecycle(
            otherSettingsGateway.currentSettings.defaultToRead,
        )
        val mangaSettings by mangaSettingsGateway.settings.collectAsStateWithLifecycle(
            mangaSettingsGateway.currentSettings,
        )

        val useRail = when (tabletInterface) {
            "always" -> true
            "landscape" -> orientation == Configuration.ORIENTATION_LANDSCAPE
            "off" -> false
            "auto" -> smallestWidthDp >= 600
            else -> false
        }

        val startRoutes = remember(defaultToRead) {
            val resolved = MainNavigator.resolveStartRoute(intent)
            val hasExplicitStartRoute = intent?.hasExplicitStartRoute() == true
            when {
                resolved is MainRouteAudioPlay -> arrayOf(MainRouteHome)
                MainIntent.shouldOpenRouteWithHomeParent(intent) -> {
                    if (resolved == MainRouteHome) {
                        arrayOf(MainRouteHome)
                    } else {
                        arrayOf(MainRouteHome, resolved)
                    }
                }
                !hasExplicitStartRoute && restoredReadBookRoute != null -> {
                    arrayOf(MainRouteHome, restoredReadBookRoute!!)
                }
                shouldApplyDefaultToRead &&
                        defaultToRead &&
                        resolved == MainRouteHome -> {
                    arrayOf(MainRouteHome, MainRouteReadBook())
                }
                resolved is MainRouteSourceLogin -> {
                    arrayOf(MainRouteHome, resolved)
                }
                else -> {
                    arrayOf(resolved)
                }
            }
        }
        val backStack = rememberNavBackStack(*startRoutes)
        SideEffect { navRouteTracker.onBackStackChanged(backStack) }

        val pageShellPlayerViewModel: ReadAloudPlayerViewModel =
            org.koin.compose.koinInject()
        val pageShellPlayerState by pageShellPlayerViewModel.uiState.collectAsStateWithLifecycle()
        val pageShellAloudSettings by pageShellPlayerViewModel.readAloudSettings
            .collectAsStateWithLifecycle()
        val pageShellShowCapsule = pageShellAloudSettings.showReadAloudCapsule
        val pageShellCapsuleScope = rememberCoroutineScope()

        val initialAudioRoute =
            remember { MainNavigator.resolveStartRoute(intent) as? MainRouteAudioPlay }
        var initialAudioHandled by rememberSaveable { mutableStateOf(false) }
        var readAloudPlayerVisible by rememberSaveable {
            mutableStateOf(
                initialAudioRoute == null &&
                        intent.getBooleanExtra(MainIntent.EXTRA_OPEN_READ_ALOUD_PLAYER, false)
            )
        }
        var audioPlayerVisible by rememberSaveable { mutableStateOf(initialAudioRoute != null) }
        var audioPlayerBookUrl by rememberSaveable { mutableStateOf(initialAudioRoute?.bookUrl.orEmpty()) }
        var audioPlayerInBookshelf by rememberSaveable {
            mutableStateOf(
                initialAudioRoute?.inBookshelf ?: true
            )
        }
        var playerSource by rememberSaveable {
            mutableStateOf(if (initialAudioRoute != null) PlaybackCapsuleSource.AudioBook else PlaybackCapsuleSource.ReadAloud)
        }
        val readAloudMorph = rememberReadAloudMorphState()
        val playbackGateway: PlaybackCapsuleGateway = org.koin.compose.koinInject()
        val playbackCapsuleState by playbackGateway.state.collectAsStateWithLifecycle()
        val playerOpenMutex = remember { Mutex() }
        val morphPresent by remember { derivedStateOf { readAloudMorph.progress.value > 0f } }

        suspend fun openPlayer(request: PlaybackCapsuleState) = playerOpenMutex.withLock {
            val source = request.source ?: PlaybackCapsuleSource.ReadAloud
            val switchingPlayer = playerSource != source ||
                    (source == PlaybackCapsuleSource.AudioBook && audioPlayerBookUrl != request.bookUrl)
            if (switchingPlayer && readAloudMorph.progress.value > 0f) {
                readAloudPlayerVisible = false
                audioPlayerVisible = false
                snapshotFlow { readAloudMorph.progress.value }.first { it <= 0f }
            }
            playerSource = source
            if (source == PlaybackCapsuleSource.AudioBook) {
                audioPlayerBookUrl = request.bookUrl
                audioPlayerInBookshelf = request.inBookshelf
                audioPlayerVisible = true
                readAloudPlayerVisible = false
            } else {
                readAloudPlayerVisible = true
                audioPlayerVisible = false
            }
        }
        LaunchedEffect(Unit) {
            if (!initialAudioHandled) {
                initialAudioHandled = true
                initialAudioRoute?.let { route ->
                    openPlayer(
                        PlaybackCapsuleState(
                            source = PlaybackCapsuleSource.AudioBook,
                            bookUrl = route.bookUrl.orEmpty(),
                            inBookshelf = route.inBookshelf,
                        )
                    )
                }
            }
            ReadAloudPlayerOverlayBus.events.collect { openPlayer(it) }
        }
        val playerVisible = readAloudPlayerVisible || audioPlayerVisible
        val matchingCapsule = !playerVisible || capsuleMatchesPlayer(
            playbackCapsuleState, playerSource, audioPlayerBookUrl,
        )
        SideEffect {
            readAloudMorph.reportCapsuleCoverLinked(
                playbackCapsuleState.source == null || matchingCapsule,
            )
            if (!pageShellShowCapsule) readAloudMorph.clearStartAnchors()
        }
        val capsuleAnchorPreview = if (playerVisible && pageShellShowCapsule) {
            PlaybackCapsuleState(source = playerSource)
        } else null
        val currentRoute = backStack.lastOrNull()
        val onMainRoute = currentRoute is MainRouteHome
        val useFloatingBottomBar = LocalAppUiConfiguration.current.appShell.useFloatingBottomBar
        val useHomeCapsule = shouldUseHomePlaybackCapsule(
            onMainRoute, configuration.appShell.showBottomView, useFloatingBottomBar, useRail,
        )
        SideEffect {
            readAloudMorph.expectCapsuleAnchors(
                if (!pageShellShowCapsule) null
                else if (useHomeCapsule) CapsuleAnchorKind.HomeBar else CapsuleAnchorKind.Global,
            )
        }

        SideEffect {
            shouldApplyDefaultToRead = false
        }

        LaunchedEffect(backStack) {
            routeEvents.collect { event ->
                val audioRoute = event.route as? MainRouteAudioPlay
                if (audioRoute != null) {
                    openPlayer(
                        PlaybackCapsuleState(
                            source = PlaybackCapsuleSource.AudioBook,
                            bookUrl = audioRoute.bookUrl.orEmpty(),
                            inBookshelf = audioRoute.inBookshelf,
                        )
                    )
                } else {
                    MainNavigator.navigateToRoute(
                        backStack = backStack,
                        route = event.route,
                        tracker = navRouteTracker,
                        resetToHome = event.resetToHome,
                    )
                }
            }
        }

        LaunchedEffect(backStack) {
            snapshotFlow { backStack.toList() }
                .collect {
                    navRouteTracker.onBackStackChanged(it)
                    MainNavigator.onBackStackChanged()
                }
        }
        SharedTransitionLayout {
            PrivateAppStartGate {
                // 🌟 替换为 BoxWithConstraints 让 Compose 实时感知窗口物理尺寸
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    // 注意：这里绝不能用 key(maxWidth, maxHeight) 包裹，否则会重置所有状态
                    Box(
                        Modifier
                            .fillMaxSize()
                            .playerUnderlaySemantics(playerVisible || morphPresent)
                    ) {
                        NavDisplay(
                            backStack = backStack,
                            sharedTransitionScope = this@SharedTransitionLayout,
                            entryDecorators = listOf(
                                rememberSaveableStateHolderNavEntryDecorator(),
                                rememberViewModelStoreNavEntryDecorator(),
                            ),
                            sceneStrategies = listOf(
                                remember {
                                    ModalOverlaySceneStrategy(
                                        isTopEntry = {
                                            backStack.lastOrNull()?.let { top ->
                                                NavEntry(top) {}.contentKey == it
                                            } == true
                                        },
                                        predictiveBackEnabled = { predictiveBackEnabled },
                                    )
                                },
                                SinglePaneSceneStrategy(),
                            ),
                            transitionSpec = {
                                (slideIntoContainer(
                                    towards = AnimatedContentTransitionScope.SlideDirection.Start,
                                    animationSpec = tween(
                                        durationMillis = NAV_SLIDE_DURATION_MILLIS,
                                        easing = FastOutSlowInEasing
                                    ),
                                    initialOffset = { fullWidth -> fullWidth }
                                ) + fadeIn(
                                    animationSpec = tween(
                                        durationMillis = NAV_FADE_DURATION_MILLIS,
                                        easing = LinearOutSlowInEasing
                                    )
                                )) togetherWith (slideOutOfContainer(
                                    towards = AnimatedContentTransitionScope.SlideDirection.Start,
                                    animationSpec = tween(
                                        durationMillis = NAV_SLIDE_DURATION_MILLIS,
                                        easing = FastOutSlowInEasing
                                    ),
                                    targetOffset = { fullWidth -> fullWidth / 4 }
                                ) + fadeOut(
                                    animationSpec = tween(
                                        durationMillis = NAV_FADE_DURATION_MILLIS,
                                        easing = LinearOutSlowInEasing
                                    )
                                ))
                            },
                            popTransitionSpec = {
                                (slideIntoContainer(
                                    towards = AnimatedContentTransitionScope.SlideDirection.Start,
                                    animationSpec = tween(
                                        durationMillis = NAV_SLIDE_DURATION_MILLIS,
                                        easing = FastOutSlowInEasing
                                    ),
                                    initialOffset = { fullWidth -> -fullWidth / 4 }
                                ) + fadeIn(
                                    animationSpec = tween(
                                        durationMillis = NAV_FADE_DURATION_MILLIS,
                                        easing = LinearOutSlowInEasing
                                    )
                                )) togetherWith (scaleOut(
                                    targetScale = 0.8f,
                                    animationSpec = tween(
                                        durationMillis = NAV_SLIDE_DURATION_MILLIS,
                                        easing = FastOutSlowInEasing
                                    )
                                ) + fadeOut(animationSpec = tween(durationMillis = NAV_FADE_DURATION_MILLIS)))
                            },
                            predictivePopTransitionSpec = { _ ->
                                (slideIntoContainer(
                                    towards = AnimatedContentTransitionScope.SlideDirection.Start,
                                    animationSpec = tween(easing = FastOutSlowInEasing),
                                    initialOffset = { fullWidth -> -fullWidth / 4 }
                                ) + fadeIn(animationSpec = tween(easing = LinearOutSlowInEasing))) togetherWith (scaleOut(
                                    targetScale = 0.8f,
                                    animationSpec = tween(easing = FastOutSlowInEasing)
                                ) + fadeOut(animationSpec = tween()))
                            },
                            onBack = { MainNavigator.navigateBack(this@MainActivity, backStack) },
                            entryProvider = mainEntryProvider(
                                backStack = backStack,
                                configuration = configuration,
                                showMangaUi = mangaSettings.showMangaUi,
                                useRail = useRail,
                                sharedTransitionScope = this@SharedTransitionLayout,
                                onNavigateToRoute = { route ->
                                    if (route is MainRouteAudioPlay) {
                                        pageShellCapsuleScope.launch {
                                            openPlayer(
                                                PlaybackCapsuleState(
                                                    source = PlaybackCapsuleSource.AudioBook,
                                                    bookUrl = route.bookUrl.orEmpty(),
                                                    inBookshelf = route.inBookshelf,
                                                )
                                            )
                                        }
                                    } else {
                                        MainNavigator.navigateToRoute(
                                            backStack,
                                            route,
                                            navRouteTracker
                                        )
                                    }
                                },
                                onNavigateBack = {
                                    MainNavigator.navigateBack(
                                        this@MainActivity,
                                        backStack,
                                        navRouteTracker
                                    )
                                },
                                readAloudMorph = readAloudMorph.takeIf { useHomeCapsule },
                                homePlaybackCapsuleEnabled = useHomeCapsule,
                                capsuleAnchorPreview = capsuleAnchorPreview,
                            )
                        )
                    }
                    CompositionLocalProvider(
                        LocalReadAloudMorph provides readAloudMorph.takeIf {
                            !useHomeCapsule && (playerVisible || readAloudMorph.progress.isRunning)
                        }
                    ) {
                        ReadAloudShellHost(
                            showCapsule = pageShellShowCapsule,
                            hidden = useHomeCapsule,
                            anchorPreview = capsuleAnchorPreview,
                            onCapsulePositionChanged = { x, y ->
                                pageShellCapsuleScope.launch {
                                    readAloudSettingsRepository.putCapsulePosition(x, y)
                                }
                            },
                            onOpenPlayer = { ReadAloudPlayerOverlayBus.request(it) },
                        )
                    }
                    if (playerSource == PlaybackCapsuleSource.ReadAloud) ReadAloudPlayerMorphHost(
                        playerViewModel = pageShellPlayerViewModel,
                        playerState = pageShellPlayerState,
                        morph = readAloudMorph,
                        visible = readAloudPlayerVisible,
                        awaitCapsuleAnchor = pageShellShowCapsule,
                        predictiveBackEnabled = configuration.appShell.predictiveBackEnabled,
                        onDismiss = { readAloudPlayerVisible = false },
                        onSwitchToClassic = { bookUrl ->
                            if (isReaderOnTop(backStack)) {
                                ReadAloudControlsRequestBus.request()
                            } else {
                                MainNavigator.navigateToRoute(
                                    backStack,
                                    MainRouteReadBook(bookUrl = bookUrl.ifBlank { null }),
                                    navRouteTracker,
                                )
                            }
                        },
                        onOpenTtsEnginesAndVoices = { bookUrl ->
                            MainNavigator.navigateToRoute(
                                backStack,
                                MainRouteCloudTtsEngines(bookUrl.takeIf(String::isNotBlank)),
                                navRouteTracker,
                            )
                        },
                        onOpenTtsCache = {
                            MainNavigator.navigateToRoute(
                                backStack,
                                MainRouteTtsCache,
                                navRouteTracker,
                            )
                        },
                        onOpenBookVoiceCasting = { bookUrl ->
                            if (bookUrl.isNotBlank()) {
                                MainNavigator.navigateToRoute(
                                    backStack,
                                    MainRouteBookVoiceCasting(bookUrl),
                                    navRouteTracker,
                                )
                            }
                        },
                        onOpenReadAloudSubPage = { page ->
                            MainNavigator.navigateToRoute(
                                backStack,
                                page.toMainRoute(),
                                navRouteTracker,
                            )
                        },
                    )
                    if (playerSource == PlaybackCapsuleSource.AudioBook &&
                        (audioPlayerVisible || morphPresent)
                    ) {
                        key(audioPlayerBookUrl, audioPlayerInBookshelf) {
                            AudioPlayerMorphOverlay(
                                bookUrl = audioPlayerBookUrl,
                                inBookshelf = audioPlayerInBookshelf,
                                morph = readAloudMorph,
                                visible = audioPlayerVisible,
                                awaitCapsuleAnchor = pageShellShowCapsule,
                                predictiveBackEnabled = configuration.appShell.predictiveBackEnabled,
                                onDismiss = { audioPlayerVisible = false },
                            )
                        }
                    }
                }
                BackHandler(
                    enabled = shouldHandleActivityBack(
                        predictiveBackEnabled = configuration.appShell.predictiveBackEnabled,
                        playerPresent = playerVisible || morphPresent,
                        isRoot = backStack.size <= 1,
                    )
                ) {
                    MainNavigator.navigateBack(this@MainActivity, backStack)
                }
            }
        }
        TextSheetHost()
    }

    @Composable
    private fun TextSheetHost() {
        val sheet by textSheetFlow.collectAsStateWithLifecycle()
        sheet?.let { data ->
            MarkdownSheet(
                show = true,
                title = data.title,
                content = data.content,
                onDismissRequest = {
                    textSheetFlow.value = null
                    data.onDismiss?.invoke()
                },
            )
        }
    }

    private fun checkStartupRoute(): Boolean {
        return when {
            LocalConfig.isFirstOpenApp -> {
                startActivity<WelcomeActivity>()
                finish()
                true
            }
            else -> false
        }
    }

    private fun checkUpdateOnStart() {
        AppUpdateGitHub.check(lifecycleScope)
            .onSuccess { updateInfo ->
                showDialogFragment(UpdateDialog(updateInfo))
            }
    }

    private suspend fun upVersion() = suspendCoroutine<Unit?> { block ->
        if (LocalConfig.versionCode == appInfo.versionCode) {
            block.resume(null)
            return@suspendCoroutine
        }
        LocalConfig.versionCode = appInfo.versionCode
        if (!BuildConfig.DEBUG) {
            lifecycleScope.launch {
                try {
                    val info = AppUpdateGitHub.getReleaseByTag(BuildConfig.VERSION_NAME)
                    if (info != null) {
                        val dialog = UpdateDialog(info, UpdateDialog.Mode.VIEW_LOG)
                        dialog.setOnDismissListener { block.resume(null) }
                        showDialogFragment(dialog)
                    } else {
                        block.resume(null)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    block.resume(null)
                }
            }
        } else {
            block.resume(null)
        }
    }

    private fun backupSync() {
        if (!backupSettingsGateway.currentSettings.autoCheckNewBackup) {
            return
        }
        lifecycleScope.launch {
            val lastBackupFile = try {
                withContext(IO) { viewModel.getLatestWebDavBackup() }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return@launch
            } ?: return@launch
            if (lastBackupFile.lastModify - LocalConfig.lastBackup > DateUtils.MINUTE_IN_MILLIS) {
                LocalConfig.lastBackup = lastBackupFile.lastModify
                alert(R.string.restore, R.string.webdav_after_local_restore_confirm) {
                    cancelButton()
                    okButton {
                        viewModel.restoreWebDav(lastBackupFile.name)
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (otherSettingsGateway.currentSettings.autoRefresh) {
            outState.putBoolean("isAutoRefreshedBook", true)
        }
        val readRoute = navRouteTracker.backStack.value.lastOrNull() as? MainRouteReadBook
            ?: activeReadBookRoute
        if (readRoute != null) {
            outState.putBoolean(KEY_RESTORE_READ_ROUTE, true)
            outState.putString(KEY_RESTORE_READ_BOOK_URL, readRoute.bookUrl)
            outState.putBoolean(KEY_RESTORE_READ_ALOUD, readRoute.readAloud)
            outState.putBoolean(KEY_RESTORE_READ_IN_BOOKSHELF, readRoute.inBookshelf)
            outState.putBoolean(KEY_RESTORE_READ_CHAPTER_CHANGED, readRoute.chapterChanged)
        }
    }

    private fun Bundle.restoreReadBookRoute(): MainRouteReadBook? {
        if (!getBoolean(KEY_RESTORE_READ_ROUTE, false)) return null
        return MainRouteReadBook(
            bookUrl = getString(KEY_RESTORE_READ_BOOK_URL),
            readAloud = getBoolean(KEY_RESTORE_READ_ALOUD, false),
            inBookshelf = getBoolean(KEY_RESTORE_READ_IN_BOOKSHELF, true),
            chapterChanged = getBoolean(KEY_RESTORE_READ_CHAPTER_CHANGED, false),
        )
    }

    private fun Intent.hasExplicitStartRoute(): Boolean {
        return hasExtra(MainIntent.EXTRA_START_ROUTE)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val isDown = event.action == KeyEvent.ACTION_DOWN
        if (keyCode == KeyEvent.KEYCODE_MENU && isDown) {
            activeReadBookInputHandler?.toggleMenu()
            if (activeReadBookInputHandler != null) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val controller = activeReadBookInputHandler ?: return super.onGenericMotionEvent(event)
        if (0 != (event.source and InputDevice.SOURCE_CLASS_POINTER) &&
            event.action == MotionEvent.ACTION_SCROLL
        ) {
            val axisValue = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            LogUtils.d("onGenericMotionEvent", "axisValue = $axisValue")
            controller.mouseWheelPage(
                if (axisValue < 0.0f) PageDirection.NEXT else PageDirection.PREV
            )
            return true
        }
        if (0 != (event.source and InputDevice.SOURCE_CLASS_JOYSTICK) &&
            event.action == MotionEvent.ACTION_MOVE
        ) {
            val yAxis = event.getAxisValue(MotionEvent.AXIS_Y)
            if (kotlin.math.abs(yAxis) > 0.5f) {
                controller.handleKeyPage(
                    if (yAxis > 0) PageDirection.NEXT else PageDirection.PREV
                )
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (activeMangaKeyHandler?.invoke(keyCode) == true) return true
        if (activeReadBookInputHandler?.onKeyDown(keyCode, event) == true) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (activeReadBookInputHandler?.onKeyUp(keyCode, event) == true) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun setupSystemBar() {
        val host = activeReadBookInputHandler as? ReadBookRouteHost
        if (host != null) {
            host.upSystemUiVisibility()
        } else {
            super.setupSystemBar()
        }
    }

    override fun onDestroy() {
        // 🌟 解除 WindowInsets 监听，防止窗口抛掷销毁时系统回调引发崩溃
        window?.decorView?.let { decorView ->
            ViewCompat.setWindowInsetsAnimationCallback(decorView, null)
            ViewCompat.setOnApplyWindowInsetsListener(decorView, null)
        }
        
        super.onDestroy()
        Coroutine.async {
            BookHelp.clearInvalidCache()
        }
        if (!BuildConfig.DEBUG) {
            Backup.autoBack(this)
        }
    }

    // ===== AudioPlay.CallBack（有声书播放器路由注册，转发加载状态给当前播放器）=====

    override fun upLoading(loading: Boolean) {
        activeAudioPlayViewModel?.onLoadingChanged(loading)
    }

    override fun upLyric(lyric: String?) {
        activeAudioPlayViewModel?.onLyricChanged()
    }

    override fun upLyricP(position: Int) {
        // 歌词暂不在界面展示
    }

}

data class TextSheetData(
    val title: String,
    val content: String,
    val onDismiss: (() -> Unit)? = null,
)

class LauncherW : MainActivity()
class Launcher1 : MainActivity()
class Launcher2 : MainActivity()
class Launcher3 : MainActivity()
class Launcher4 : MainActivity()
class Launcher5 : MainActivity()
class Launcher6 : MainActivity()
class Launcher0 : MainActivity()