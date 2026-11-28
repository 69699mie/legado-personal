package io.legado.app.ui.main.video

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.net.TrafficStats
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Rational
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlin.math.cos
import kotlin.math.sin
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaSession
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import coil3.compose.AsyncImage
import io.legado.app.data.repository.VideoSettingsRepository
import io.legado.app.help.video.introoutro.EpisodeAudioCapture
import io.legado.app.help.BackupToastGate
import io.legado.app.help.storage.Backup
import io.legado.app.help.watchparty.WatchPartyConfig
import io.legado.app.help.watchparty.WatchPartySession
import io.legado.app.ui.main.video.proxy.LocalProxyServer
import io.legado.app.ui.widget.components.text.AppText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt
import java.text.Collator

data class RecentWatchedItem(
    val fid: String,
    val title: String,
    val seriesTitle: String,
    val coverPath: String,
    val timestamp: Long
)

val AppBackgroundColor = Color(0xFFF7F8FA)

const val PIP_ACTION = "com.mie.reading.ACTION_PIP_CONTROL"
const val EXTRA_CONTROL = "control"
const val CONTROL_PREV = 1
const val CONTROL_PLAY_PAUSE = 2
const val CONTROL_NEXT = 3

/** 自动识别的片头最晚只能结束在 5 分半（180s 起点 + 150s 上限），超过按脏数据丢弃 */
private const val MAX_AUTO_INTRO_END_MS = 330_000L

/** 播放进度落库间隔；不在播放中时靠暂停/退出/切集/播放结束/拖动松手补写 */
private const val PROGRESS_SAVE_INTERVAL_MS = 10_000L

val pinyinCollator = Collator.getInstance(Locale.CHINA)

/**
 * 普通文本字幕：统一基础样式——白字、不描边、18sp，且**不指定 typeface**（传 null）：
 * 不指定字体时系统会按当前主题字体渲染，跟着手机换主题走，也不用读任何系统文件、不要额外权限。
 */
private fun stylePlainSubtitle(view: SubtitleView) {
    view.setApplyEmbeddedStyles(false)
    view.setApplyEmbeddedFontSizes(false)
    view.setStyle(
        CaptionStyleCompat(
            android.graphics.Color.WHITE,
            android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_NONE,
            android.graphics.Color.BLACK,
            null
        )
    )
    view.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
    view.setBottomPaddingFraction(0.08f)
}

/**
 * ASS/SSA 这类自带完整样式的字幕轨：不覆盖它写好的颜色、字体、字号、描边和位置，
 * 只留一点底部留白，其余全部交给字幕文件自己。
 */
private fun styleEmbeddedSubtitle(view: SubtitleView) {
    view.setApplyEmbeddedStyles(true)
    view.setApplyEmbeddedFontSizes(true)
    view.setBottomPaddingFraction(0.08f)
}

/** 当前选中的字幕轨是否为 ASS/SSA（mime 为 text/ssa 或 text/x-ssa） */
internal fun isAssSubtitleSelected(tracks: Tracks): Boolean = try {
    tracks.groups.any { group ->
        group.type == C.TRACK_TYPE_TEXT && group.isSelected && (0 until group.length).any { i ->
            group.isTrackSelected(i) &&
                MimeTypes.TEXT_SSA == group.getTrackFormat(i).sampleMimeType
        }
    }
} catch (t: Throwable) {
    false
}

fun createPipIcon(type: String, context: Context): Bitmap {
    val size = 96
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = Paint().apply { color = android.graphics.Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true }
    val path = Path()
    val w = size.toFloat(); val h = size.toFloat()
    when(type) {
        "prev" -> { paint.strokeWidth = 8f; paint.strokeCap = Paint.Cap.ROUND; canvas.drawLine(w*0.3f, h*0.25f, w*0.3f, h*0.75f, paint); path.moveTo(w*0.75f, h*0.2f); path.lineTo(w*0.4f, h*0.5f); path.lineTo(w*0.75f, h*0.8f); path.close(); canvas.drawPath(path, paint) }
        "next" -> { paint.strokeWidth = 8f; paint.strokeCap = Paint.Cap.ROUND; canvas.drawLine(w*0.7f, h*0.25f, w*0.7f, h*0.75f, paint); path.moveTo(w*0.25f, h*0.2f); path.lineTo(w*0.6f, h*0.5f); path.lineTo(w*0.25f, h*0.8f); path.close(); canvas.drawPath(path, paint) }
        "play" -> { path.moveTo(w*0.35f, h*0.2f); path.lineTo(w*0.8f, h*0.5f); path.lineTo(w*0.35f, h*0.8f); path.close(); canvas.drawPath(path, paint) }
        "pause" -> { canvas.drawRoundRect(w*0.3f, h*0.2f, w*0.45f, h*0.8f, 4f, 4f, paint); canvas.drawRoundRect(w*0.55f, h*0.2f, w*0.7f, h*0.8f, 4f, 4f, paint) }
    }
    return bitmap
}

@Composable
fun ShimmerBox(modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = -400f, targetValue = 1200f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Restart), label = "shimmer_translate"
    )
    Box(modifier = modifier.background(Color(0xFFEEEEEE)).background(brush = Brush.linearGradient(colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.4f), Color.Transparent), start = Offset(translateAnim - 200f, 0f), end = Offset(translateAnim, 200f))))
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun VideoLibraryScreen(modifier: Modifier = Modifier, viewModel: VideoScraperViewModel = koinViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showSettingsDialog by remember { mutableStateOf(false) }
    BackHandler(enabled = uiState.currentFid != "0") { viewModel.goBack() }
    Column(modifier = modifier.fillMaxSize().background(AppBackgroundColor).statusBarsPadding()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (uiState.currentFid != "0") { IconButton(onClick = { viewModel.goBack() }, modifier = Modifier.padding(end = 8.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") } }
            AppText(text = "资源库", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f).padding(start = if (uiState.currentFid == "0") 8.dp else 0.dp))
            IconButton(onClick = { showSettingsDialog = true }) { Icon(Icons.Default.Settings, contentDescription = "设置", tint = Color.DarkGray) }
        }
        if (uiState.errorMsg != null && !uiState.isLoading) { Box(modifier = Modifier.fillMaxWidth().background(Color(0xFFFFCDD2)).padding(8.dp), contentAlignment = Alignment.Center) { AppText(uiState.errorMsg!!, color = Color.Red, style = MaterialTheme.typography.bodySmall) } }
        if (uiState.isLoading && uiState.fileList.isEmpty()) { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        else {
            if (uiState.fileList.isEmpty()) { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { AppText("当前文件夹为空", color = Color.Gray) } }
            else {
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(uiState.fileList) { file ->
                        Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).clickable(enabled = file.isFolder) { if (file.isFolder) viewModel.fetchQuarkFiles(file.fid, folderName = file.name) }.padding(horizontal = 12.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (file.isFolder) Icons.Default.Folder else Icons.Default.Movie, null, tint = if (file.isFolder) Color(0xFFFFB300) else Color.Gray, modifier = Modifier.size(28.dp))
                            Column(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
                                AppText(text = file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            }

                            if (file.isFolder) {
                                val isExplicitlySelected = uiState.selectedLibraryFids.contains(file.fid)
                                val isParentSelected = uiState.selectedLibraryFids.contains(uiState.currentFid)
                                val isExcluded = uiState.excludedLibraryFids.contains(file.fid)

                                IconButton(
                                    onClick = {
                                        val fullPath = (viewModel.browsePath + file.name).joinToString("/")
                                        viewModel.toggleLibrary(file.fid, fullPath)
                                    }
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(if (isExplicitlySelected || (isParentSelected && !isExcluded)) Color(0xFF333333) else Color.Transparent)
                                            .border(
                                                width = 1.dp,
                                                color = if (isExcluded || isExplicitlySelected || isParentSelected) Color(0xFF666666) else Color.LightGray,
                                                shape = RoundedCornerShape(4.dp)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isExcluded) {
                                            Icon(Icons.Default.Block, contentDescription = "已单独排除", tint = Color(0xFF666666), modifier = Modifier.size(16.dp))
                                        } else if (isExplicitlySelected || isParentSelected) {
                                            Icon(Icons.Default.Check, contentDescription = "已包含", tint = Color.White, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showSettingsDialog) { SettingsDialog(uiState = uiState, viewModel = viewModel, onDismiss = { showSettingsDialog = false }, onWebLoginClick = { }) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun VideoScraperScreen(isActive: Boolean = true, modifier: Modifier = Modifier, viewModel: VideoScraperViewModel = koinViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val videoSettingsRepository = koinInject<VideoSettingsRepository>()
    val view = LocalView.current
    val activity = context.findActivity()
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showWebLoginDialog by remember { mutableStateOf(false) }
    var showLoginConfirmDialog by remember { mutableStateOf(false) }
    var showManualScrapeDialog by remember { mutableStateOf(false) }
    var showAllHistoryPage by remember { mutableStateOf(false) }
    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedTitles = remember { mutableStateListOf<String>() }
    var viewAllCategory by remember { mutableStateOf<String?>(null) }
    var manualScrapeSeries by remember { mutableStateOf<MediaSeries?>(null) }
    var showClearHistoryConfirm by remember { mutableStateOf(false) }
    var showPartyDialog by remember { mutableStateOf(false) }
    val libraryPartyState by WatchPartySession.state.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.playUrl, uiState.currentSeries) {
        if (uiState.playUrl == null && uiState.currentSeries == null) {
            delay(150)
            activity?.window?.let { window ->
                WindowCompat.setDecorFitsSystemWindows(window, true)
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.show(WindowInsetsCompat.Type.systemBars())
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
                window.decorView.requestLayout()
                window.decorView.post { window.decorView.requestApplyInsets() }
            }
        }
    }

    val recentWatchedList = remember(uiState.seriesList, videoSettingsRepository.recentWatchedVideos) {
        val list = mutableListOf<RecentWatchedItem>()
        val seenSeries = mutableSetOf<String>()
        try {
            val jsonArray = JSONArray(videoSettingsRepository.recentWatchedVideos)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val sTitle = obj.optString("seriesTitle")
                if (sTitle.isNotBlank() && !seenSeries.contains(sTitle)) {
                    seenSeries.add(sTitle)
                    list.add(RecentWatchedItem(obj.optString("fid"), obj.optString("title"), sTitle, obj.optString("coverPath"), obj.optLong("timestamp")))
                }
            }
        } catch (e: Exception) {}
        list
    }

    val groupedHistory = remember(recentWatchedList, uiState.seriesList) {
        val groups = mutableMapOf<String, MutableList<RecentWatchedItem>>()
        recentWatchedList.forEach { item ->
            val series = uiState.seriesList.find { it.title == item.seriesTitle }
            val category = series?.category ?: "其他"
            if (!groups.containsKey(category)) groups[category] = mutableListOf()
            groups[category]?.add(item)
        }
        groups
    }

    BackHandler(enabled = uiState.currentSeries != null || viewAllCategory != null || showAllHistoryPage) {
        if (uiState.currentSeries != null) viewModel.selectSeries(null)
        else if (viewAllCategory != null) viewAllCategory = null
        else if (showAllHistoryPage) { if (isSelectionMode) { isSelectionMode = false; selectedTitles.clear() } else showAllHistoryPage = false }
    }

    Box(modifier = Modifier.fillMaxSize().background(AppBackgroundColor)) {
        if (uiState.currentSeries != null) {
            val latestSeries = remember(uiState.seriesList, uiState.currentSeries, uiState.currentPlayFile) {
                val current = uiState.currentSeries
                val byId = current?.let { c -> uiState.seriesList.find { it.id == c.id } }; if (byId != null) return@remember byId
                val byTitle = current?.let { c -> uiState.seriesList.find { it.title == c.title && it.videos.isNotEmpty() } }; if (byTitle != null) return@remember byTitle
                val byFid = uiState.currentPlayFile?.let { f -> uiState.seriesList.find { s -> s.videos.any { it.fid == f.fid } } }; if (byFid != null) return@remember byFid
                current
            }

            if (latestSeries != null) {
                val showLoading = !latestSeries.isManuallyEdited && (latestSeries.videos.isEmpty() || latestSeries.posterUrl == null)

                AnimatedContent(targetState = showLoading, transitionSpec = { fadeIn(animationSpec = tween(400)) togetherWith fadeOut(animationSpec = tween(400)) }, label = "detail_transition") { isLoading ->
                    if (isLoading) {
                        Column(modifier = Modifier.fillMaxSize().background(Color.White).statusBarsPadding().verticalScroll(rememberScrollState())) {
                            Box(modifier = Modifier.fillMaxWidth().height(350.dp).defaultMinSize(minHeight = 350.dp)) {
                                ShimmerBox(modifier = Modifier.fillMaxSize())
                                IconButton(onClick = { viewModel.selectSeries(null) }, modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.Black) }
                            }
                            Column(modifier = Modifier.padding(16.dp)) {
                                ShimmerBox(modifier = Modifier.fillMaxWidth(0.4f).height(28.dp).clip(RoundedCornerShape(4.dp))); Spacer(Modifier.height(12.dp))
                                ShimmerBox(modifier = Modifier.fillMaxWidth(0.6f).height(16.dp).clip(RoundedCornerShape(4.dp))); Spacer(Modifier.height(24.dp))
                                ShimmerBox(modifier = Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(25.dp))); Spacer(Modifier.height(24.dp))
                                ShimmerBox(modifier = Modifier.fillMaxWidth().height(100.dp).clip(RoundedCornerShape(8.dp))); Spacer(Modifier.height(24.dp))
                                ShimmerBox(modifier = Modifier.fillMaxWidth(0.3f).height(20.dp).clip(RoundedCornerShape(4.dp))); Spacer(Modifier.height(16.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(4) { ShimmerBox(modifier = Modifier.width(160.dp).height(90.dp).clip(RoundedCornerShape(8.dp))) } }
                            }
                        }
                    } else {
                        MediaDetailScreen(
                            series = latestSeries, allSeries = uiState.seriesList, isScraping = uiState.isScraping, onBack = { viewModel.selectSeries(null) },
                            onPlay = { series, file -> if (activity != null && !activity.isInMultiWindowMode) { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE }; viewModel.playVideo(context, series, file) },
                            onLongClickPoster = { manualScrapeSeries = it; showManualScrapeDialog = true }, onSelectSeries = { viewModel.selectSeries(it) }
                        )
                    }
                }
            }
        } else if (viewAllCategory != null) {
            val categorySeries = uiState.seriesList.filter { it.category == viewAllCategory }
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding().background(AppBackgroundColor)) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewAllCategory = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.Black) }
                    AppText(text = viewAllCategory ?: "", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
                }
                LazyVerticalGrid(columns = GridCells.Fixed(3), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(categorySeries.sortedWith(compareBy(pinyinCollator) { it.title })) { series ->
                        SeriesPosterCard(series = series, isScraping = uiState.isScraping, onClick = { viewModel.stopPlaying(); viewModel.selectSeries(series) }, onLongClick = { manualScrapeSeries = series; showManualScrapeDialog = true })
                    }
                }
            }
        } else if (showAllHistoryPage) {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding().background(AppBackgroundColor)) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (isSelectionMode) {
                        IconButton(onClick = { isSelectionMode = false; selectedTitles.clear() }) { Icon(Icons.Default.Close, contentDescription = "取消", tint = Color.Black) }
                        AppText(text = "已选 ${selectedTitles.size} 项", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 8.dp))
                        IconButton(onClick = { if (selectedTitles.isNotEmpty()) { viewModel.removeRecentWatchedBatch(selectedTitles.toSet()); selectedTitles.clear(); isSelectionMode = false } }) { Icon(Icons.Default.Delete, contentDescription = "删除", tint = Color.Red) }
                    } else {
                        IconButton(onClick = { showAllHistoryPage = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.Black) }
                        AppText(text = "最近观看", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 8.dp))
                        Box(modifier = Modifier.combinedClickable(onClick = { isSelectionMode = true }, onLongClick = { showClearHistoryConfirm = true })) { Icon(Icons.Default.Menu, contentDescription = "选择", tint = Color.Black, modifier = Modifier.padding(12.dp)) }
                    }
                }
                LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    groupedHistory.forEach { (category, items) ->
                        if (items.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) { AppText(text = category, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)) }
                            items(items) { historyItem ->
                                RecentWatchedGridCard(item = historyItem, isSelectionMode = isSelectionMode, isSelected = selectedTitles.contains(historyItem.seriesTitle), onClick = {
                                    if (isSelectionMode) { if (selectedTitles.contains(historyItem.seriesTitle)) selectedTitles.remove(historyItem.seriesTitle) else selectedTitles.add(historyItem.seriesTitle) }
                                    else {
                                        if (activity != null && !activity.isInMultiWindowMode) { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE }
                                        val series = uiState.seriesList.find { it.title == historyItem.seriesTitle }
                                        val file = series?.videos?.find { it.fid == historyItem.fid }
                                        if (series != null && file != null) viewModel.playVideo(context, series, file)
                                        else {
                                            val tempFile = QuarkFile(historyItem.fid, historyItem.title, false)
                                            val tempSeries = MediaSeries(id = "temp_${historyItem.fid}", title = historyItem.seriesTitle, originalParsedTitle = historyItem.seriesTitle, year = null, category = category, posterUrl = historyItem.coverPath.ifBlank { null }, videos = listOf(tempFile))
                                            viewModel.playVideo(context, tempSeries, tempFile)
                                        }
                                    }
                                })
                            }
                        }
                    }
                }
            }
        } else {
            Column(modifier = modifier.fillMaxSize().statusBarsPadding()) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppText(text = "影视库", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 16.dp))
                    IconButton(onClick = { showSettingsDialog = true }) { Icon(Icons.Default.Settings, contentDescription = "设置") }
                    IconButton(onClick = { viewModel.loadLibraryVideos() }, enabled = !uiState.isLoading) { Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = if (uiState.isLoading) Color.Gray else Color.Black) }
                }
                if (uiState.errorMsg != null && !uiState.isLoading) { Box(modifier = Modifier.fillMaxWidth().background(Color(0xFFFFCDD2)).padding(8.dp), contentAlignment = Alignment.Center) { AppText(uiState.errorMsg!!, color = Color.Red, style = MaterialTheme.typography.bodySmall) } }

                if (uiState.isLoading && uiState.seriesList.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize().background(AppBackgroundColor))
                }
                else {
                    if (uiState.selectedLibraryFids.isEmpty()) { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { AppText("请先在资源库中勾选媒体文件夹") } }
                    else if (uiState.seriesList.isEmpty() && !uiState.isLoading) { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { AppText("没找到任何视频，点击右上角刷新") } }
                    else {
                        val grouped = uiState.seriesList.groupBy { it.category }
                        val savedOrder = uiState.categoryOrder.split(",").filter { it.isNotBlank() }
                        val allCategories = grouped.keys.toMutableList()
                        val finalOrderList = mutableListOf<String>().apply { add("最近观看"); savedOrder.forEach { if (it != "最近观看" && !contains(it)) add(it) }; allCategories.forEach { if (!contains(it)) add(it) } }

                        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                            item {
                                val bannerList = remember(uiState.seriesList, uiState.bannerLogic) {
                                    val list = uiState.seriesList.filter { !it.backdropUrl.isNullOrBlank() }
                                    when (uiState.bannerLogic) { "最近播放" -> list.sortedByDescending { s -> recentWatchedList.find { it.seriesTitle == s.title }?.timestamp ?: 0L }; "最近新增" -> list; else -> list.shuffled() }.take(10)
                                }
                                // 🌟 首页轮播图改为纯展示：不响应点击、不触发横屏（想看详情走下方分类卡片）
                                if (bannerList.isNotEmpty()) BannerCarousel(bannerList)
                            }
                            finalOrderList.forEach { categoryName ->
                                if (categoryName == "最近观看") {
                                    if (recentWatchedList.isNotEmpty()) {
                                        item { Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { AppText(text = "最近观看", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); AppText(text = "全部 ${recentWatchedList.size} >", style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.clickable { showAllHistoryPage = true }) } }
                                        item {
                                            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                                items(recentWatchedList.take(10)) { item ->
                                                    RecentWatchedCard(item = item, onClick = {
                                                        if (activity != null && !activity.isInMultiWindowMode) { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE }
                                                        val series = uiState.seriesList.find { it.title == item.seriesTitle }
                                                        val file = series?.videos?.find { it.fid == item.fid }
                                                        if (series != null && file != null) viewModel.playVideo(context, series, file)
                                                        else {
                                                            val tempFile = QuarkFile(item.fid, item.title, false)
                                                            val tempSeries = MediaSeries(id = "temp_${item.fid}", title = item.seriesTitle, originalParsedTitle = item.seriesTitle, year = null, category = "未分类", posterUrl = item.coverPath.ifBlank { null }, videos = listOf(tempFile))
                                                            viewModel.playVideo(context, tempSeries, tempFile)
                                                        }
                                                    })
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    val seriesList = grouped[categoryName]
                                    if (seriesList != null && seriesList.isNotEmpty()) {
                                        item { Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { AppText(text = categoryName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); AppText(text = "全部 ${seriesList.size} 部 >", style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.clickable { viewAllCategory = categoryName }) } }
                                        item { LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(seriesList.sortedWith(compareBy(pinyinCollator) { it.title })) { series -> SeriesPosterCard(series = series, isScraping = uiState.isScraping, onClick = { viewModel.stopPlaying(); viewModel.selectSeries(series) }, onLongClick = { manualScrapeSeries = series; showManualScrapeDialog = true }) } } }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        val showLoadingOverlay = (uiState.isLoading || uiState.isScraping) && uiState.currentSeries == null && uiState.playUrl == null && uiState.currentPlayFile == null && !uiState.isDirectPlay
        if (showLoadingOverlay) {
            Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp).background(Color.White.copy(alpha = 0.95f), RoundedCornerShape(24.dp)).border(1.dp, Color.Gray.copy(alpha = 0.2f), RoundedCornerShape(24.dp)).padding(horizontal = 24.dp, vertical = 12.dp)) {
                AnimatedLoadingDots(color = Color.Black, fontSize = 14.sp, text = if (uiState.isScraping) "正在刮削数据" else "正在刷新列表")
            }
        }
    }

    if (showSettingsDialog) SettingsDialog(uiState = uiState, viewModel = viewModel, onDismiss = { showSettingsDialog = false }, onWebLoginClick = { showLoginConfirmDialog = true }, onPartyClick = { showSettingsDialog = false; showPartyDialog = true }, partyActive = libraryPartyState.active)
    // 一起看：影视库页保留「加入/管理房间」入口；房主开播后的自动跟随由主页面的全局跟随逻辑统一处理
    WatchPartyDialog(show = showPartyDialog, onDismiss = { showPartyDialog = false })
    // 进入播放页（含一起看自动跟随跳转）时收起影视库里的房间卡片，避免挡住播放画面
    LaunchedEffect(uiState.playUrl, uiState.currentPlayFile?.fid, uiState.isDirectPlay) {
        if (showPartyDialog && (uiState.playUrl != null || uiState.currentPlayFile != null || uiState.isDirectPlay)) {
            showPartyDialog = false
        }
    }
    if (showLoginConfirmDialog) { AlertDialog(onDismissRequest = { showLoginConfirmDialog = false }, title = { AppText("登录", fontWeight = FontWeight.Bold) }, text = { AppText(if (uiState.cookie.isNotBlank()) "当前状态：已登录" else "当前状态：未登录") }, confirmButton = { TextButton(onClick = { showLoginConfirmDialog = false; if (uiState.cookie.isNotBlank()) viewModel.loginWithCapturedCookie(""); showWebLoginDialog = true }) { AppText("登录") } }, dismissButton = { TextButton(onClick = { showLoginConfirmDialog = false }) { AppText("取消", color = Color.Gray) } }) }
    if (showWebLoginDialog) { Dialog(onDismissRequest = { showWebLoginDialog = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) { Box(modifier = Modifier.fillMaxSize().background(Color.White).statusBarsPadding()) { WebLoginView(onLoginSuccess = { cookie -> viewModel.loginWithCapturedCookie(cookie); showWebLoginDialog = false; showSettingsDialog = false }, onCancel = { showWebLoginDialog = false }) } } }
    if (showManualScrapeDialog && manualScrapeSeries != null) ManualScrapeDialog(series = manualScrapeSeries!!, viewModel = viewModel, onDismiss = { showManualScrapeDialog = false })
    if (showClearHistoryConfirm) { AlertDialog(onDismissRequest = { showClearHistoryConfirm = false }, title = { AppText("清空历史记录", fontWeight = FontWeight.Bold) }, text = { AppText("确定要清空所有历史观看记录吗？此操作不可恢复。") }, confirmButton = { TextButton(onClick = { viewModel.clearRecentWatched(); showClearHistoryConfirm = false }) { AppText("清空", color = Color.Red) } }, dismissButton = { TextButton(onClick = { showClearHistoryConfirm = false }) { AppText("取消", color = Color.Gray) } }) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BannerCarousel(seriesList: List<MediaSeries>, onSelect: ((MediaSeries) -> Unit)? = null) {
    val pagerState = rememberPagerState(pageCount = { seriesList.size })
    LaunchedEffect(pagerState, seriesList.size) { if (seriesList.size > 1) { while (true) { delay(4000); pagerState.animateScrollToPage((pagerState.currentPage + 1) % seriesList.size) } } }
    Box(modifier = Modifier.fillMaxWidth().height(220.dp).padding(bottom = 12.dp)) {
        HorizontalPager(state = pagerState, contentPadding = PaddingValues(horizontal = 16.dp), pageSpacing = 8.dp) { page ->
            val series = seriesList[page]
            Box(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).then(if (onSelect != null) Modifier.clickable { onSelect(series) } else Modifier)) {
                AsyncImage(model = series.backdropUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)), startY = 200f, endY = 600f)))
                Column(modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                    AppText(text = series.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val infoText = listOfNotNull(series.year?.toString(), series.category, series.rating?.let { "⭐$it" }).joinToString(" | ")
                    if (infoText.isNotBlank()) AppText(text = infoText, color = Color.LightGray, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp), horizontalArrangement = Arrangement.Center) { repeat(seriesList.size) { iteration -> Box(modifier = Modifier.padding(2.dp).clip(CircleShape).background(if (pagerState.currentPage == iteration) Color.White else Color.White.copy(alpha = 0.5f)).size(6.dp)) } }
    }
}

@Composable
fun RecentWatchedCard(item: RecentWatchedItem, onClick: () -> Unit) {
    Column(modifier = Modifier.width(110.dp).clickable { onClick() }) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(Color.DarkGray), contentAlignment = Alignment.Center) {
            if (item.coverPath.isNotEmpty()) AsyncImage(model = item.coverPath, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else Icon(Icons.Default.Movie, null, tint = Color.LightGray)
        }
        AppText(text = item.seriesTitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp), fontWeight = FontWeight.Bold)
    }
}

@Composable
fun RecentWatchedGridCard(item: RecentWatchedItem, isSelectionMode: Boolean, isSelected: Boolean, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(Color.DarkGray), contentAlignment = Alignment.Center) {
            if (item.coverPath.isNotEmpty()) AsyncImage(model = item.coverPath, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else Icon(Icons.Default.Movie, null, tint = Color.LightGray)
            if (isSelectionMode) Box(modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(24.dp).background(if (isSelected) Color(0xFF333333) else Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp)).border(1.dp, Color.White, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) { if (isSelected) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
        }
        AppText(text = item.seriesTitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SettingsDialog(uiState: VideoUiState, viewModel: VideoScraperViewModel, onDismiss: () -> Unit, onWebLoginClick: (() -> Unit)? = null, onPartyClick: (() -> Unit)? = null, partyActive: Boolean = false) {
    var inputCookie by remember { mutableStateOf(uiState.cookie) }
    var inputTmdbKey by remember { mutableStateOf(uiState.tmdbApiKey) }
    var inputTmdbUrl by remember { mutableStateOf(uiState.tmdbBaseUrl) }
    var isCookieVisible by remember { mutableStateOf(false) }
    var isTmdbKeyVisible by remember { mutableStateOf(false) }
    var showThreadSelection by remember { mutableStateOf(false) }
    var showBannerLogicSelection by remember { mutableStateOf(false) }
    var showCategoryOrderDialog by remember { mutableStateOf(false) }
    // 通道改为公共中继后不再需要服务器配置

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val view = LocalView.current
        val window = (view.parent as? DialogWindowProvider)?.window
        SideEffect { window?.let { it.setBackgroundDrawableResource(android.R.color.transparent); it.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); WindowCompat.setDecorFitsSystemWindows(it, false); val c = WindowCompat.getInsetsController(it, it.decorView); c.isAppearanceLightStatusBars = true; c.isAppearanceLightNavigationBars = true } }
        Surface(shape = RoundedCornerShape(0.dp), color = AppBackgroundColor, modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp).verticalScroll(rememberScrollState())) {
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { AppText(text = "设置", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.Black); IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.Black) } }
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { AppText(text = "夸克登录状态", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.Black); if (onWebLoginClick != null) OutlinedButton(onClick = onWebLoginClick, shape = RoundedCornerShape(20.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), modifier = Modifier.height(36.dp), border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.5f))) { AppText("登录", fontSize = 13.sp, color = Color.Black) } }
                OutlinedTextField(value = inputCookie, onValueChange = { inputCookie = it }, modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth(), visualTransformation = if (isCookieVisible) VisualTransformation.None else PasswordVisualTransformation(), maxLines = 2, label = { AppText("手动填写 Cookie（可选）") }, trailingIcon = { IconButton(onClick = { isCookieVisible = !isCookieVisible }) { Icon(if (isCookieVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff, contentDescription = "切换可见性") } })
                AppText(text = "TMDB API Key (v3 auth)", modifier = Modifier.padding(top = 8.dp), color = Color.Black)
                OutlinedTextField(value = inputTmdbKey, onValueChange = { inputTmdbKey = it }, modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth(), visualTransformation = if (isTmdbKeyVisible) VisualTransformation.None else PasswordVisualTransformation(), maxLines = 1, trailingIcon = { IconButton(onClick = { isTmdbKeyVisible = !isTmdbKeyVisible }) { Icon(if (isTmdbKeyVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff, contentDescription = "切换可见性") } })
                AppText(text = "TMDB API 代理地址", color = Color.Black)
                OutlinedTextField(value = inputTmdbUrl, onValueChange = { inputTmdbUrl = it }, modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth(), maxLines = 1)
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                Row(modifier = Modifier.fillMaxWidth().clickable { showThreadSelection = true }.padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { AppText("极速播放线程数", color = Color.Black); Row(verticalAlignment = Alignment.CenterVertically) { AppText("${uiState.downloadThreads} ", color = Color.Gray); Icon(Icons.Default.KeyboardArrowRight, null, tint = Color.Gray) } }
                Row(modifier = Modifier.fillMaxWidth().clickable { showBannerLogicSelection = true }.padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { AppText("首页轮播海报显示逻辑", color = Color.Black); Row(verticalAlignment = Alignment.CenterVertically) { AppText(uiState.bannerLogic, color = Color.Gray); Icon(Icons.Default.KeyboardArrowRight, null, tint = Color.Gray) } }
                Row(modifier = Modifier.fillMaxWidth().clickable { showCategoryOrderDialog = true }.padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { AppText("分类排序", color = Color.Black); Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.KeyboardArrowRight, null, tint = Color.Gray) } }
                Row(modifier = Modifier.fillMaxWidth().clickable { viewModel.setAutoIntroOutro(!uiState.autoIntroOutro) }.padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        AppText("自动识别片头片尾", color = Color.Black)
                        AppText("每集单独认，要播过这一集才认得出来；关掉就完全按手动", fontSize = 12.sp, color = Color.Gray)
                    }
                    AppText(if (uiState.autoIntroOutro) "开" else "关", color = Color.Gray)
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                if (onPartyClick != null) {
                    Row(modifier = Modifier.fillMaxWidth().clickable { onPartyClick() }.padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        AppText("一起看", color = Color.Black)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppText(if (partyActive) "已加入" else "未加入", fontSize = 13.sp, color = if (partyActive) Color.Black else Color.Gray)
                            Icon(Icons.Default.KeyboardArrowRight, null, tint = Color.Gray)
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        AppText("一起看通道", color = Color.Black)
                        AppText(
                            text = "公共中继，自动选节点，无需配置",
                            fontSize = 12.sp,
                            color = Color.Gray,
                        )
                    }
                }
                OutlinedButton(onClick = { viewModel.saveSettings(inputCookie, inputTmdbKey, inputTmdbUrl); onDismiss() }, shape = RoundedCornerShape(20.dp), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp), modifier = Modifier.fillMaxWidth().padding(top = 24.dp), border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.5f))) { AppText("确认保存", fontWeight = FontWeight.Bold, color = Color.Black) }
            }
        }
    }
    if (showThreadSelection) { AlertDialog(onDismissRequest = { showThreadSelection = false }, title = { AppText("极速播放线程数", fontWeight = FontWeight.Bold) }, text = { Column { listOf(3, 6, 10, 16, 24, 32).forEach { count -> Row(modifier = Modifier.fillMaxWidth().clickable { viewModel.saveDownloadThreads(count); showThreadSelection = false }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { AppText("$count", modifier = Modifier.weight(1f)); if (uiState.downloadThreads == count) Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF333333)) } } } }, confirmButton = { TextButton(onClick = { showThreadSelection = false }) { AppText("取消") } }) }
    if (showBannerLogicSelection) { AlertDialog(onDismissRequest = { showBannerLogicSelection = false }, title = { AppText("首页轮播海报显示逻辑", fontWeight = FontWeight.Bold) }, text = { Column { listOf("智能推荐", "最近新增", "最近播放").forEach { logic -> Row(modifier = Modifier.fillMaxWidth().clickable { viewModel.saveBannerLogic(logic); showBannerLogicSelection = false }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { AppText(logic, modifier = Modifier.weight(1f)); if (uiState.bannerLogic == logic) Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF333333)) } } } }, confirmButton = { TextButton(onClick = { showBannerLogicSelection = false }) { AppText("取消") } }) }
    if (showCategoryOrderDialog) CategoryOrderDialog(uiState, viewModel, onDismiss = { showCategoryOrderDialog = false })
}

@Composable
fun CategoryOrderDialog(uiState: VideoUiState, viewModel: VideoScraperViewModel, onDismiss: () -> Unit) {
    val presetCategories = listOf("剧集", "电影", "动漫", "综艺")
    val existingCategories = uiState.seriesList.map { it.category }.filter { it.isNotBlank() }.distinct()
    val orderList = remember(uiState.categoryOrder, existingCategories) {
        val list = mutableStateListOf<String>()
        list.addAll(uiState.categoryOrder.split(",").filter { it.isNotBlank() && it != "最近观看" })
        presetCategories.forEach { if (!list.contains(it)) list.add(it) }
        existingCategories.forEach { if (!list.contains(it)) list.add(it) }
        list
    }
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to -> if (to.index >= 0 && from.index != to.index) { val item = orderList.removeAt(from.index); orderList.add(to.index, item) } }
    Dialog(onDismissRequest = { viewModel.saveCategoryOrder("最近观看," + orderList.joinToString(",")); onDismiss() }) {
        Surface(shape = RoundedCornerShape(16.dp), color = Color.White, modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { AppText("拖动排序", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); TextButton(onClick = { viewModel.saveCategoryOrder("最近观看," + orderList.joinToString(",")); onDismiss() }) { AppText("保存", fontWeight = FontWeight.Bold) } }
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(state = lazyListState, modifier = Modifier.fillMaxWidth()) {
                    itemsIndexed(orderList, key = { _, item -> item }) { index, item ->
                        ReorderableItem(reorderableState, key = item) { isDragging ->
                            Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(8.dp), shadowElevation = if (isDragging) 8.dp else 0.dp, color = if (isDragging) Color(0xFFF0F0F0) else Color.White, border = BorderStroke(1.dp, Color(0xFFE0E0E0))) {
                                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { AppText(item, fontWeight = FontWeight.Medium); IconButton(onClick = {}, modifier = Modifier.draggableHandle()) { Icon(Icons.Default.Menu, contentDescription = "拖动排序", tint = Color.Gray) } }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ManualScrapeDialog(series: MediaSeries, viewModel: VideoScraperViewModel, onDismiss: () -> Unit) {
    var searchKeyword by remember { mutableStateOf(series.title) }
    var categoryInput by remember { mutableStateOf(series.category) }

    var currentStep by remember { mutableIntStateOf(0) }
    var searchResults by remember { mutableStateOf<List<TmdbSearchResult>?>(null) }
    var selectedResult by remember { mutableStateOf<TmdbSearchResult?>(null) }

    var posterList by remember { mutableStateOf<List<String>>(emptyList()) }
    var backdropList by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedPoster by remember { mutableStateOf<String?>(null) }
    var selectedBackdrop by remember { mutableStateOf<String?>(null) }

    var isLoadingImages by remember { mutableStateOf(false) }
    var selectedSeason by remember { mutableStateOf<Int?>(null) }

    var showSeasonPicker by remember { mutableStateOf(false) }
    var fetchedSeasons by remember { mutableStateOf<List<Int>>(emptyList()) }

    fun loadImagesAndProceed(result: TmdbSearchResult) {
        isLoadingImages = true
        viewModel.fetchTmdbImages(result.id, result.mediaType) { posters, backdrops ->
            posterList = posters
            backdropList = backdrops
            selectedPoster = result.posterUrl ?: posters.firstOrNull()
            selectedBackdrop = result.backdropUrl ?: backdrops.firstOrNull()
            isLoadingImages = false
            currentStep = 1
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth(0.92f).heightIn(max = 750.dp)) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    AppText(text = if (currentStep == 0) "编辑信息" else "选择封面与背景", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "关闭") }
                }

                AnimatedContent(targetState = currentStep, label = "step_transition") { step ->
                    if (step == 0) {
                        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                                AppText("分类：", modifier = Modifier.width(50.dp))
                                OutlinedTextField(value = categoryInput, onValueChange = { categoryInput = it }, modifier = Modifier.weight(1f), placeholder = { AppText("如：动漫、电影") }, singleLine = true)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
                                AppText("名称：", modifier = Modifier.width(50.dp))
                                OutlinedTextField(value = searchKeyword, onValueChange = { searchKeyword = it }, modifier = Modifier.weight(1f), singleLine = true)
                                Button(
                                    onClick = { viewModel.searchTmdbManually(searchKeyword) { searchResults = it } },
                                    modifier = Modifier.padding(start = 8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333))
                                ) { AppText("搜索", color = Color.White) }
                            }
                            HorizontalDivider(modifier = Modifier.padding(bottom = 12.dp))

                            if (searchResults == null) {
                                Box(modifier = Modifier.fillMaxWidth().weight(1f).padding(32.dp), contentAlignment = Alignment.Center) { AppText("输入名称并点击搜索", color = Color.Gray) }
                            } else if (searchResults!!.isEmpty()) {
                                Box(modifier = Modifier.fillMaxWidth().weight(1f).padding(32.dp), contentAlignment = Alignment.Center) { AppText("未找到相关影视，请修改名称", color = Color.Red) }
                            } else {
                                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(searchResults!!) { result ->
                                        Card(
                                            modifier = Modifier.fillMaxWidth().clickable {
                                                selectedResult = result
                                                selectedSeason = null

                                                if (result.mediaType == "tv") {
                                                    isLoadingImages = true
                                                    viewModel.fetchSeriesSeasons(result.id) { seasons ->
                                                        isLoadingImages = false
                                                        fetchedSeasons = seasons

                                                        if (seasons.size > 1) {
                                                            showSeasonPicker = true
                                                        } else {
                                                            selectedSeason = seasons.firstOrNull() ?: 1
                                                            loadImagesAndProceed(result)
                                                        }
                                                    }
                                                } else {
                                                    selectedSeason = null
                                                    loadImagesAndProceed(result)
                                                }
                                            },
                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                        ) {
                                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                                AsyncImage(model = result.posterUrl, contentDescription = null, modifier = Modifier.size(60.dp, 90.dp).clip(RoundedCornerShape(8.dp)).background(Color.LightGray), contentScale = ContentScale.Crop)
                                                Column(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
                                                    AppText(text = result.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                                    AppText(text = "${result.year} | ${if (result.mediaType == "movie") "电影" else "剧集"}", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                                                }
                                                Icon(Icons.Default.KeyboardArrowRight, null, tint = Color.Gray)
                                            }
                                        }
                                    }
                                }
                            }
                            OutlinedButton(onClick = { viewModel.applyManualScrape(series.id, null, categoryInput, searchKeyword); onDismiss() }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) { AppText("仅保存名称和分类") }
                        }
                    } else {
                        val result = selectedResult
                        if (isLoadingImages) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        } else if (result != null) {
                            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                                AppText("已选择版本：${result.title} (${result.year})", fontWeight = FontWeight.Medium, color = Color(0xFF333333))
                                Spacer(modifier = Modifier.height(16.dp))

                                AppText("竖屏海报 (列表封面)", style = MaterialTheme.typography.titleSmall, color = Color.Gray)
                                LazyRow(modifier = Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(posterList) { url ->
                                        val isSelected = url == selectedPoster
                                        Box(
                                            modifier = Modifier
                                                .size(90.dp, 135.dp)
                                                .shadow(if (isSelected) 12.dp else 0.dp, RoundedCornerShape(8.dp))
                                                .clip(RoundedCornerShape(8.dp))
                                                .border(if (isSelected) 3.dp else 0.dp, Color(0xFF333333), RoundedCornerShape(8.dp))
                                                .clickable { selectedPoster = url }
                                        ) {
                                            AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                            if (isSelected) {
                                                Box(
                                                    modifier = Modifier
                                                        .align(Alignment.TopEnd)
                                                        .padding(6.dp)
                                                        .size(22.dp)
                                                        .background(Color(0xFF333333), CircleShape)
                                                        .border(1.5.dp, Color.White, CircleShape),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                                                }
                                            }
                                        }
                                    }
                                    if (posterList.isEmpty()) { item { AppText("无海报", color = Color.Gray, fontSize = 12.sp) } }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                AppText("横屏背景图 (详情页顶部)", style = MaterialTheme.typography.titleSmall, color = Color.Gray)
                                LazyRow(modifier = Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(backdropList) { url ->
                                        val isSelected = url == selectedBackdrop
                                        Box(
                                            modifier = Modifier
                                                .size(160.dp, 90.dp)
                                                .shadow(if (isSelected) 12.dp else 0.dp, RoundedCornerShape(8.dp))
                                                .clip(RoundedCornerShape(8.dp))
                                                .border(if (isSelected) 3.dp else 0.dp, Color(0xFF333333), RoundedCornerShape(8.dp))
                                                .clickable { selectedBackdrop = url }
                                        ) {
                                            AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                            if (isSelected) {
                                                Box(
                                                    modifier = Modifier
                                                        .align(Alignment.TopEnd)
                                                        .padding(6.dp)
                                                        .size(22.dp)
                                                        .background(Color(0xFF333333), CircleShape)
                                                        .border(1.5.dp, Color.White, CircleShape),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                                                }
                                            }
                                        }
                                    }
                                    if (backdropList.isEmpty()) { item { AppText("无背景图", color = Color.Gray, fontSize = 12.sp) } }
                                }

                                Spacer(modifier = Modifier.weight(1f))

                                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    OutlinedButton(onClick = { currentStep = 0 }) { AppText("上一步") }
                                    Button(
                                        onClick = {
                                            viewModel.applyManualScrape(
                                                series.id,
                                                result,
                                                categoryInput,
                                                result.title,
                                                customPoster = selectedPoster,
                                                customBackdrop = selectedBackdrop,
                                                targetSeason = selectedSeason
                                            )
                                            onDismiss()
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333))
                                    ) { AppText("确认保存并更新剧集", color = Color.White) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSeasonPicker && selectedResult != null) {
        AlertDialog(
            onDismissRequest = { showSeasonPicker = false },
            title = { AppText("选择季数", fontWeight = FontWeight.Bold) },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                    items(fetchedSeasons) { season ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedSeason = season
                                    showSeasonPicker = false
                                    loadImagesAndProceed(selectedResult!!)
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppText("第 $season 季", fontSize = 16.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSeasonPicker = false }) { AppText("取消", color = Color.Gray) }
            }
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebLoginView(onLoginSuccess: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var reloadTrigger by remember { mutableIntStateOf(0) }
    var loadingProgress by remember { mutableFloatStateOf(0f) }
    DisposableEffect(Unit) { onDispose { webViewRef?.destroy(); webViewRef = null } }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().background(Color(0xFFF5F5F5)).padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onCancel) { AppText("取消", color = Color.Gray) }
            AppText("夸克扫码/账号登录", fontWeight = FontWeight.Bold)
            Row {
                TextButton(onClick = { CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush(); reloadTrigger++ }) { AppText("清空缓存", color = Color.Red) }
                TextButton(onClick = { val cookie = CookieManager.getInstance().getCookie("https://pan.quark.cn/"); if (!cookie.isNullOrBlank() && cookie.contains("__uid=")) onLoginSuccess(cookie) else Toast.makeText(context, "尚未检测到有效登录，请完成扫码或授权", Toast.LENGTH_SHORT).show() }) { AppText("完成登录", color = Color(0xFF333333)) }
            }
        }
        if (loadingProgress < 1f) LinearProgressIndicator(progress = { loadingProgress }, modifier = Modifier.fillMaxWidth().height(2.dp))
        AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { ctx ->
            WebView(ctx).apply {
                webViewRef = this
                settings.apply {
                    javaScriptEnabled = true; domStorageEnabled = true; databaseEnabled = true; useWideViewPort = true; loadWithOverviewMode = true; setSupportZoom(true); builtInZoomControls = true; displayZoomControls = false
                    userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }
                CookieManager.getInstance().setAcceptCookie(true); CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webChromeClient = object : WebChromeClient() { override fun onProgressChanged(view: WebView?, newProgress: Int) { loadingProgress = newProgress / 100f } }
                webViewClient = object : WebViewClient() { override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean { val url = request?.url.toString(); if (!url.startsWith("http://") && !url.startsWith("https://")) { Toast.makeText(context, "已拦截外部跳转，请在当前页面完成登录", Toast.LENGTH_SHORT).show(); return true }; return false } }
                loadUrl("https://pan.quark.cn/")
            }
        }, update = { webView -> if (reloadTrigger > 0) { webView.clearHistory(); webView.clearCache(true); webView.loadUrl("https://pan.quark.cn/"); reloadTrigger = 0 } })
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun InternalVideoPlayer(url: String, title: String, cookie: String, series: MediaSeries, currentFile: QuarkFile, viewModel: VideoScraperViewModel, downloadThreads: Int, onBack: () -> Unit) {
    val context = LocalContext.current

    // 播放界面期间不弹备份提示；离开播放页时立即推送（此刻进度已定型）
    DisposableEffect(Unit) {
        val appContext = context.applicationContext
        BackupToastGate.enter()
        onDispose {
            BackupToastGate.exit()
            Backup.backupNow(appContext)
        }
    }
    val activity = context.findActivity()
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val videoSettingsRepository = koinInject<VideoSettingsRepository>()
    val proxyServer = koinInject<LocalProxyServer>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val latestSeries = uiState.seriesList.find { it.id == series.id || it.title == series.title } ?: series
    // 播放监听器是长效的（DisposableEffect(exoPlayer)），里面要读「当前这一集」的最新值，不能捕获旧值
    val currentFileState = rememberUpdatedState(uiState.currentPlayFile)

    // 触觉反馈（震动）
    val haptic = LocalHapticFeedback.current

    // 防抖状态
    var hasVibratedBrightnessMin by remember { mutableStateOf(false) }
    var hasVibratedBrightnessMax by remember { mutableStateOf(false) }
    var hasVibratedVolumeMin by remember { mutableStateOf(false) }
    var hasVibratedVolumeMax by remember { mutableStateOf(false) }

    // 独立 UI 状态，防止退出动画期间数值跳变
    var brightnessDisplayValue by remember { mutableFloatStateOf(0f) }
    var volumeDisplayValue by remember { mutableFloatStateOf(0f) }

    val sortedVideos = remember(latestSeries.videos) {
        latestSeries.videos.sortedWith(
            compareBy<QuarkFile> { viewModel.extractEpisodeNumber(it.name, it.parsedInfo?.episode) }
                .thenBy { it.name }
        )
    }

    val isInMultiWindowMode = remember { activity?.isInMultiWindowMode == true }
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }

    // 一起看：房主在播的这一刻，若加入端索要当前集直链（对方本地缺这一集），
    // 把本机正在用的直链点对点回给他。只回正在播的这一集，且只在对方已同意时才算数（会话层已校验）。
    val partyStateForLink by io.legado.app.help.watchparty.WatchPartySession.state.collectAsStateWithLifecycle()
    LaunchedEffect(partyStateForLink.linkRequest, url, currentFile.fid) {
        val req = partyStateForLink.linkRequest ?: return@LaunchedEffect
        if (!partyStateForLink.isHost) return@LaunchedEffect
        if (req.fid != currentFile.fid || url.isBlank()) return@LaunchedEffect
        val sizeForPeer = if (currentFile.size > 0L) currentFile.size
        else (probedSizes[currentFile.fid] ?: 0L)
        io.legado.app.help.watchparty.WatchPartySession.respondLink(req.userId, req.fid, url, sizeForPeer)
    }

    val dynamicTitle = remember(latestSeries, currentFile) {
        val parsedInfo = currentFile.parsedInfo ?: io.legado.app.utils.VideoFileNameParser.parse(currentFile.name)
        val epNum = viewModel.extractEpisodeNumber(currentFile.name, parsedInfo.episode).takeIf { it > 0 }
        val season = parsedInfo.season ?: 1
        if (epNum != null && latestSeries.category != "电影") {
            val tmdbEpTitle = resolveEpisodeTitleOf(latestSeries, season, epNum)

            val cleanBaseTitle = latestSeries.title.replace(Regex("\\s*第\\s*\\d+\\s*集.*$"), "").trim().ifBlank { latestSeries.title }
            val cleanTmdbTitle = tmdbEpTitle?.replace(Regex("^\\s*第\\s*\\d+\\s*集\\s*"), "")?.trim()

            if (!cleanTmdbTitle.isNullOrBlank()) "$cleanBaseTitle 第${epNum}集 $cleanTmdbTitle" else "$cleanBaseTitle 第${epNum}集"
        } else latestSeries.title
    }

    // 一起看广播里只带「剧名 + 第几集」，不要把网盘里随便命名的文件名甩给对方
    val partyEpisodeLabel = remember(latestSeries.title, latestSeries.category, currentFile.name) {
        val parsed = currentFile.parsedInfo ?: io.legado.app.utils.VideoFileNameParser.parse(currentFile.name)
        val epNum = viewModel.extractEpisodeNumber(currentFile.name, parsed.episode).takeIf { it > 0 }
        when {
            latestSeries.category == "电影" ||
                latestSeries.title.contains("剧场版") ||
                currentFile.name.contains("剧场版") -> ""
            epNum != null -> "第${epNum}集"
            else -> ""
        }
    }

    // 集标题（如「舍哥儿」）：跟着 show 一起发给加入端，否则对方左上角只能显示「剧名 第2集」
    val partyEpisodeTitle = remember(latestSeries, currentFile) {
        val parsed = currentFile.parsedInfo ?: io.legado.app.utils.VideoFileNameParser.parse(currentFile.name)
        val epNum = viewModel.extractEpisodeNumber(currentFile.name, parsed.episode).takeIf { it > 0 }
        val season = parsed.season ?: 1
        if (latestSeries.category == "电影" || epNum == null) ""
        else resolveEpisodeTitleOf(latestSeries, season, epNum)
            ?.replace(Regex("^\\s*第\\s*\\d+\\s*集\\s*"), "")
            ?.trim()
            .orEmpty()
    }

    val isInPip = remember { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var errorDialogMsg by remember { mutableStateOf<String?>(null) }
    var isLocked by remember { mutableStateOf(false) }
    val currentIsLocked by rememberUpdatedState(isLocked)
    var currentSystemTime by remember { mutableStateOf("") }
    var hasRequestedLandscape = remember { false }

    var isLongPressing by remember { mutableStateOf(false) }
    var showLongPressText by remember { mutableStateOf(true) } // 🌟 控制长按提示短暂显示后自动淡出
    var playerToastMessage by remember { mutableStateOf<String?>(null) }
    // 一起看的两句提示（「张三」已退出房间 / 房主已离开，房间已结束）：单独一份，固定 2 秒消失
    var partyNoticeMessage by remember { mutableStateOf<String?>(null) }

    var lastCenterMessage by remember { mutableStateOf<String?>(null) }
    val currentCenterMessage = when {
        isLongPressing && showLongPressText -> "2.0x快进中"
        partyNoticeMessage != null -> partyNoticeMessage
        playerToastMessage != null -> playerToastMessage
        else -> null
    }

    LaunchedEffect(currentCenterMessage) {
        if (currentCenterMessage != null) {
            lastCenterMessage = currentCenterMessage
        }
    }

    // 🌟 长按提示只做"轻提示"：约 0.8 秒后淡出，不遮挡画面、不用等 2 秒
    LaunchedEffect(isLongPressing, showLongPressText) {
        if (isLongPressing && showLongPressText) {
            delay(800)
            showLongPressText = false
        }
    }

    LaunchedEffect(playerToastMessage) {
        if (playerToastMessage != null) {
            delay(2500)
            playerToastMessage = null
        }
    }

    LaunchedEffect(partyNoticeMessage) {
        if (partyNoticeMessage != null) {
            delay(2000)
            partyNoticeMessage = null
        }
    }

    LaunchedEffect(Unit) {
        if (!hasRequestedLandscape && !isInMultiWindowMode && activity?.isInPictureInPictureMode != true) { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE; hasRequestedLandscape = true }
        while (true) { currentSystemTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()); delay(1000) }
    }

    LaunchedEffect(isLandscape, isInPip.value, isInMultiWindowMode) {
        if (view.isInEditMode || isInPip.value || isInMultiWindowMode) return@LaunchedEffect
        val window = (view.parent as? DialogWindowProvider)?.window ?: activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (isLandscape) { WindowCompat.setDecorFitsSystemWindows(window, false); controller.hide(WindowInsetsCompat.Type.systemBars()); controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
            else { WindowCompat.setDecorFitsSystemWindows(window, true); controller.show(WindowInsetsCompat.Type.systemBars()) }
        }
    }

    val originalBrightness = remember { activity?.window?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
    DisposableEffect(Unit) {
        onDispose {
            if (!isInMultiWindowMode) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            activity?.window?.let { window -> WindowCompat.setDecorFitsSystemWindows(window, true); val controller = WindowCompat.getInsetsController(window, window.decorView); controller.show(WindowInsetsCompat.Type.systemBars()); window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE; window.decorView.requestApplyInsets() }
            activity?.window?.let { val params = it.attributes; params.screenBrightness = originalBrightness; it.attributes = params }
        }
    }

    // 界面的播放/暂停状态以 playWhenReady（用户的真实意图）为准，不用 isPlaying。
    // 缓冲期 isPlaying 本来就是 false，旧版初值又写死 true，加载中点暂停会执行成 pause，
    // 之后因为 isPlaying 仍为 false 永远只能走 pause → 图标不切回、双击/拖进度都救不回来。
    var isPlaying by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(true) }
    // 一起看
    val partyState by WatchPartySession.state.collectAsState()
    var showPartyPanel by remember { mutableStateOf(false) }
    // 一起看双向同步：本地操作时间 / 远端应用时间 / 最近一次跨集切换时间，用于防回环与防对切
    var lastLocalActionAt by remember { mutableLongStateOf(0L) }
    var lastAppliedRemoteAt by remember { mutableLongStateOf(0L) }
    // 一起看：上一帧的播放状态，用来区分「真的变了」还是「刚连上房间」，避免加入端一进门就把自己的进度推给房主
    var prevPartyFid by remember { mutableStateOf<String?>(null) }
    var prevPartyPlaying by remember { mutableStateOf<Boolean?>(null) }
    var prevPartySpeed by remember { mutableStateOf<Float?>(null) }
    var prevPartyActive by remember { mutableStateOf<Boolean?>(null) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    // 拖动进度条那一刻是否在播：seek 会触发缓冲，isPlaying 会短暂变 false，
    // 如果这时按 isPlaying 上报，对端会收到 paused=true 跟着暂停（用户反馈的「我拉进度对方停了」）
    var seekWasPlaying by remember { mutableStateOf(false) }
    // 刚 seek 完的那一两秒，playbackState 会因为重新缓冲短暂变成「不在播」，
    // 这时如果按实际状态广播，对端会收到 paused=true 抖一下（用户反馈的「拉进度对方暂停」）
    var lastSeekAt by remember { mutableLongStateOf(0L) }
    var bufferedPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var resolution by remember { mutableStateOf("") }
    var networkSpeed by remember { mutableStateOf("0 KB/s") }
    var showControls by remember { mutableStateOf(true) }
    var showUnlockIcon by remember { mutableStateOf(false) }
    // 🌟 倍速从本地记忆恢复：切集、退出重进都保持用户上次的选择
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var showSettingsPanel by remember { mutableStateOf(false) }
    var showEpisodeDrawer by remember { mutableStateOf(false) }
    var gestureType by remember { mutableStateOf("") }
    var gestureValue by remember { mutableFloatStateOf(0f) }
    var isDraggingSeekBar by remember { mutableStateOf(false) }
    var dragStartPosition by remember { mutableLongStateOf(0L) }
    var seekDeltaMillis by remember { mutableLongStateOf(0L) }
    var seekMessage by remember { mutableStateOf<String?>(null) }
    // 双击快进/快退的提示文案累加：实际每次仍跳 10 秒，连续点只让"已快进 N 秒"的数字往上走
    var seekMsgAccumSec by remember { mutableLongStateOf(0L) }
    var seekMsgDir by remember { mutableIntStateOf(0) }
    var seekMsgAt by remember { mutableLongStateOf(0L) }

    val season = currentFile.parsedInfo?.season ?: 1
    val episodeNumber = remember(currentFile.name, currentFile.parsedInfo) {
        viewModel.extractEpisodeNumber(currentFile.name, currentFile.parsedInfo?.episode).takeIf { it > 0 }
    }
    // 集级记录用 fid 做主键；ep 字段只用于“删剧/同步”时反查
    val episodeKey = remember(latestSeries.title, season, episodeNumber, currentFile.fid) {
        "${latestSeries.title}|S$season|E${episodeNumber ?: 0}|${currentFile.fid}"
    }
    // 片头终点 / 片尾起点统一用绝对毫秒：集级记录优先，没有则回退旧的季级设置（距结尾 N 秒换算成绝对时间）
    var introEndMs by remember(currentFile.fid) { mutableLongStateOf(0L) }
    var outroStartMs by remember(currentFile.fid) { mutableLongStateOf(0L) }
    // 两个字段各自的来源（manual/auto），手动设过的那一项自动永远不覆盖
    var introSrc by remember(currentFile.fid) { mutableStateOf("") }
    var outroSrc by remember(currentFile.fid) { mutableStateOf("") }
    fun reloadIntroOutro() {
        val episode = viewModel.getEpisodeIntroOutro(currentFile.fid)
        // 优先级：本集手动 > 本集自动(够稳) > 旧的季级手动 > 不跳。逐字段判断，互不牵连
        var pickedIntro = 0L
        var pickedOutro = 0L
        var pickedIntroSrc = ""
        var pickedOutroSrc = ""
        if (episode != null) {
            val introOk = episode.introEndMs > 0L && (
                episode.introSource == "manual" ||
                    (episode.confidence >= 0.6 && episode.introEndMs <= MAX_AUTO_INTRO_END_MS)
                )
            if (introOk) {
                pickedIntro = episode.introEndMs; pickedIntroSrc = episode.introSource
            }
            val outroOk = episode.outroStartMs > 0L && (
                episode.outroSource == "manual" || episode.confidence >= 0.6
                )
            if (outroOk) {
                pickedOutro = episode.outroStartMs; pickedOutroSrc = episode.outroSource
            }
        }
        val seasonPair = viewModel.getIntroOutro(latestSeries.title, season)
        if (pickedIntro <= 0L && seasonPair.first > 0) {
            pickedIntro = seasonPair.first * 1000L
            pickedIntroSrc = "manual"
        }
        if (pickedOutro <= 0L && seasonPair.second > 0 && duration > 0L) {
            pickedOutro = (duration - seasonPair.second * 1000L).coerceAtLeast(0L)
            pickedOutroSrc = "manual"
        }
        introEndMs = pickedIntro
        outroStartMs = pickedOutro
        introSrc = pickedIntroSrc
        outroSrc = pickedOutroSrc
    }
    LaunchedEffect(currentFile.fid, latestSeries.title, season) { reloadIntroOutro() }
    LaunchedEffect(duration, currentFile.fid) { if (duration > 0L) reloadIntroOutro() }
    // 自动片头片尾：只在开关打开时采集；切集时先把上一集结算掉
    LaunchedEffect(currentFile.fid) {
        EpisodeAudioCapture.begin(currentFile.fid, latestSeries.title, season, episodeNumber ?: 0)
    }

    var hasTriggeredAutoPlay by remember { mutableStateOf(false) }
    var hasAutoSkippedIntro by remember { mutableStateOf(false) }
    var outroWarningTriggered by remember { mutableStateOf(false) }
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var hasRestoredProgress by remember { mutableStateOf(false) }
    var entryTime by remember { mutableLongStateOf(0L) }
    var audioTracks by remember { mutableStateOf<List<Tracks.Group>>(emptyList()) }
    var textTracks by remember { mutableStateOf<List<Tracks.Group>>(emptyList()) }
    // 用户手动点过音轨后，本集内不再自动改回（换集/换片才重新按设备能力判定）
    var userPickedAudio by remember { mutableStateOf(false) }
    var autoAudioFid by remember { mutableStateOf("") }
    // 这台设备最终能出几个声道：≥6 才默认给环绕轨，否则默认给立体声轨
    val outputChannelLimit = remember { resolveOutputChannelLimit(context) }
    var trackSelectionVersion by remember { mutableIntStateOf(0) }
    val episodeListState = rememberLazyListState()

    LaunchedEffect(Unit) {
        var lastRxBytes = TrafficStats.getUidRxBytes(Process.myUid())
        while (true) {
            delay(1000)
            val currentRxBytes = TrafficStats.getUidRxBytes(Process.myUid())
            val delta = currentRxBytes - lastRxBytes
            lastRxBytes = currentRxBytes
            networkSpeed = if (delta > 1024 * 1024) String.format(Locale.getDefault(), "%.1f MB/s", delta / (1024f * 1024f)) else String.format(Locale.getDefault(), "%d KB/s", delta / 1024)
        }
    }

    val exoPlayer = remember {
        val renderersFactory = buildVideoRenderersFactory(context, videoSettingsRepository.autoIntroOutroEnabled)

        // 🌟 修复 OOM：强制限制缓冲区上限为 96MB，防止高码率视频撑爆内存（时间优先）
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                5_000,   // minBufferMs: 5秒
                30_000,  // maxBufferMs: 30秒
                1_500,   // bufferForPlaybackMs: 1.5秒
                3_000    // bufferForPlaybackAfterRebufferMs: 3秒
            )
            .setTargetBufferBytes(96 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true) // 🌟 防止“字节否决”导致卡死
            .build()

        ExoPlayer.Builder(context, renderersFactory)
            .setAudioAttributes(buildVideoAudioAttributes(), false)
            .setLoadControl(loadControl)
            .build()
            .apply {
                playWhenReady = true
                setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
            }
    }

    val mediaSession = remember { MediaSession.Builder(context, exoPlayer).build() }
    DisposableEffect(mediaSession) { onDispose { mediaSession.release() } }

    // 进度落库：10 秒一次 + 关键时刻补写（暂停/退出/切集/播完/拖完松手），避免每秒写库。
    // 记住"上次写进去的是哪一集、什么位置"，同一秒的重复值直接跳过。
    var lastWrittenFid by remember { mutableStateOf("") }
    var lastWrittenPos by remember { mutableLongStateOf(0L) }
    fun saveProgressPoint(fid: String, pos: Long, force: Boolean = false) {
        if (fid.isBlank() || pos <= 0L) return
        if (!force && fid == lastWrittenFid && abs(pos - lastWrittenPos) < 1_000L) return
        lastWrittenFid = fid
        lastWrittenPos = pos
        // 验证包里打一行，正式包会被 R8 剥掉（build.gradle.kts 的假设里有配置）
        if (io.legado.app.BuildConfig.TEST_HOOKS) {
            android.util.Log.d("ProgressSave", "save fid=${fid.take(8)} pos=$pos force=$force")
        }
        viewModel.saveVideoProgress(fid, pos)
    }

    // 切集/离开播放页时补写：这里必须用"进入时快照下来的 fid"，不能等 onDispose 再读当前集
    DisposableEffect(currentFile.fid) {
        val fidAtEntry = currentFile.fid
        onDispose {
            val pos = runCatching { exoPlayer.currentPosition }.getOrDefault(0L)
            saveProgressPoint(fidAtEntry, pos)
        }
    }

    // 一起看：把本机当前播放状态立即广播出去（房主、加入端都能推）
    fun pushPartyNow(pausedOverride: Boolean? = null) {
        if (!partyState.active || !partyState.connected) return
        android.util.Log.d("WatchParty", "push sync speed=$playbackSpeed pos=${exoPlayer.currentPosition}")
        WatchPartySession.pushLocalSync(
            fid = currentFile.fid,
            episode = partyEpisodeLabel,
            positionMs = exoPlayer.currentPosition,
            paused = pausedOverride ?: !exoPlayer.isPlaying,
            speed = playbackSpeed,
            force = true,
        )
    }

    /** 用户主动操作（拖进度等）时调用：记下本地操作时间并立刻广播 */
    fun pushLocalAction(pausedOverride: Boolean? = null) {
        if (!partyState.active || !partyState.connected) return
        lastLocalActionAt = System.currentTimeMillis()
        pushPartyNow(pausedOverride)
    }

    /**
     * 播放/暂停的唯一出口：只看 playWhenReady（用户意图），不看 isPlaying。
     * 缓冲中 isPlaying 恒为 false，按它判断会把「点暂停」执行成 pause，之后又因为 isPlaying 还是 false
     * 永远只能走 pause —— 这就是「加载中点暂停后永久卡在暂停」的根因。
     */
    fun togglePlayPause() {
        if (exoPlayer.playWhenReady) exoPlayer.pause() else exoPlayer.play()
        isPlaying = exoPlayer.playWhenReady
    }

    /**
     * 房主主动拖动 / 换集之后的「两端就位再一起播」：
     * 先广播「暂停在 X 秒」，等「正在看同一集」的加入端回报已就位（真缓冲到能播 + 进度对上），
     * 再统一点播放。对方网慢时不再 8 秒强行开播——等满 30 秒弹窗问房主「继续播放 / 再等一会」。
     * 被动卡顿（播放中网速抖动）不走这里：另一端照常播，卡顿端恢复后自己追上。
     */
    val partyUiScope = rememberCoroutineScope()
    var partyStaging by remember { mutableStateOf(false) }
    var stageWaitPrompt by remember { mutableStateOf(false) }
    var stagePlayNow by remember { mutableStateOf(false) }
    var stageWaitingHint by remember { mutableStateOf(false) }
    // stage 去重：一次拖动会触发两轮（进度条手势 + 全屏手势），1.5 秒内同位置只做一次
    var lastStageAt by remember { mutableStateOf(0L) }
    var lastStagePos by remember { mutableStateOf(-3000L) }
    // 「推送给观看端」不再是弹窗询问，改成一起看面板里一个按钮（见 showPartyPanel 处的 WatchPartyDialog）

    fun stagePartyPlayback(filterFid: String = currentFile.fid) {
        if (!partyState.active || !partyState.connected || !partyState.isHost) return
        if (partyStaging) return
        val targetFid = currentFile.fid
        // filterFid 为空 = 等房间里所有在线成员（换集/换片时，对方还停在上一集，按集号筛会永远筛不到）
        if (WatchPartySession.peersNeedReady(filterFid).isEmpty()) return
        val targetPos = exoPlayer.currentPosition
        // 一次拖动会先后走「进度条手势」和「全屏手势」两条 onDragEnd，位置几乎一样 → 1.5 秒内同位置不重复 stage
        val nowTs = System.currentTimeMillis()
        if (nowTs - lastStageAt < 1500L && abs(targetPos - lastStagePos) < 3000L) return
        lastStageAt = nowTs
        lastStagePos = targetPos
        lastLocalActionAt = nowTs
        val wantPlay = exoPlayer.isPlaying
        partyStaging = true
        stageWaitingHint = true
        stagePlayNow = false
        WatchPartySession.clearReadyAcks()
        if (wantPlay) exoPlayer.pause()
        WatchPartySession.pushLocalSync(
            fid = targetFid,
            episode = partyEpisodeLabel,
            positionMs = targetPos,
            paused = true,
            speed = playbackSpeed,
            force = true,
            stage = true,
        )
        partyUiScope.launch {
            val started = System.currentTimeMillis()
            var roundStart = started
            while (true) {
                val peers = WatchPartySession.peersNeedReady(filterFid)
                val allReady = peers.all { WatchPartySession.readyAckAt(it) >= started }
                if (peers.isEmpty() || allReady || stagePlayNow) break
                // 房主自己按了播放（不想再等），也算选择「继续播放」
                if (wantPlay && exoPlayer.isPlaying) break
                if (System.currentTimeMillis() - roundStart >= 30_000L) {
                    stageWaitPrompt = true
                    while (!stagePlayNow && stageWaitPrompt && System.currentTimeMillis() - started < 600_000L) {
                        delay(120)
                        if (WatchPartySession.peersNeedReady(filterFid).isEmpty()) break
                    }
                    if (stagePlayNow) break
                    stageWaitPrompt = false
                    // 用户选了「再等一会」：重新计 30 秒，期间就一直等
                    roundStart = System.currentTimeMillis()
                }
                delay(120)
            }
            stageWaitPrompt = false
            stageWaitingHint = false
            partyStaging = false
            stagePlayNow = false
            if (wantPlay) {
                playerToastMessage = "两端已就位"
                exoPlayer.play()
                WatchPartySession.pushLocalSync(
                    fid = targetFid,
                    episode = partyEpisodeLabel,
                    positionMs = exoPlayer.currentPosition,
                    paused = false,
                    speed = playbackSpeed,
                    force = true,
                )
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        var isPlayerReleased = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                // 熄屏/切后台一律暂停播放（省电、不后台放声音）；一起看的连接靠前台服务保活，不靠播放
                Handler(Looper.getMainLooper()).postDelayed({ if (!isPlayerReleased && activity?.isInPictureInPictureMode != true) exoPlayer.pause(); if (activity?.isInPictureInPictureMode == true) isInPip.value = true }, 100)
            } else if (event == Lifecycle.Event.ON_RESUME) { if (activity?.isInPictureInPictureMode != true) isInPip.value = false }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { isPlayerReleased = true; lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val latestSeriesForReceiver by rememberUpdatedState(latestSeries)
    val pipActionReceiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.getIntExtra(EXTRA_CONTROL, -1)) {
                    CONTROL_PREV -> { val idx = sortedVideos.indexOfFirst { it.fid == currentFile.fid }; if (idx > 0) viewModel.playVideo(ctx ?: return, latestSeriesForReceiver, sortedVideos[idx - 1]) else Toast.makeText(ctx, "已经是第一集了", Toast.LENGTH_SHORT).show() }
                    CONTROL_NEXT -> { val idx = sortedVideos.indexOfFirst { it.fid == currentFile.fid }; if (idx != -1 && idx < sortedVideos.size - 1) viewModel.playVideo(ctx ?: return, latestSeriesForReceiver, sortedVideos[idx + 1]) else Toast.makeText(ctx, "已经是最新集了", Toast.LENGTH_SHORT).show() }
                    CONTROL_PLAY_PAUSE -> { if (exoPlayer.playWhenReady) exoPlayer.pause() else exoPlayer.play() }
                }
            }
        }
    }

    DisposableEffect(Unit) { val filter = IntentFilter(PIP_ACTION); ContextCompat.registerReceiver(context, pipActionReceiver, filter, ContextCompat.RECEIVER_EXPORTED); onDispose { context.unregisterReceiver(pipActionReceiver) } }

    LaunchedEffect(isPlaying, isInPip.value) {
        if (isInPip.value && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Handler(Looper.getMainLooper()).post {
                try {
                    val prevAction = RemoteAction(Icon.createWithBitmap(createPipIcon("prev", context)), "上一集", "上一集", PendingIntent.getBroadcast(context, 100, Intent(PIP_ACTION).setPackage(context.packageName).putExtra(EXTRA_CONTROL, CONTROL_PREV), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                    val playPauseAction = RemoteAction(Icon.createWithBitmap(createPipIcon(if (isPlaying) "pause" else "play", context)), if (isPlaying) "暂停" else "播放", if (isPlaying) "暂停" else "播放", PendingIntent.getBroadcast(context, 101, Intent(PIP_ACTION).setPackage(context.packageName).putExtra(EXTRA_CONTROL, CONTROL_PLAY_PAUSE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                    val nextAction = RemoteAction(Icon.createWithBitmap(createPipIcon("next", context)), "下一集", "下一集", PendingIntent.getBroadcast(context, 102, Intent(PIP_ACTION).setPackage(context.packageName).putExtra(EXTRA_CONTROL, CONTROL_NEXT), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                    activity?.setPictureInPictureParams(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).setActions(listOf(prevAction, playPauseAction, nextAction)).setSeamlessResizeEnabled(false).build())
                } catch (e: Exception) {}
            }
        }
    }

    DisposableEffect(url) { entryTime = System.currentTimeMillis(); onDispose { val watchTime = System.currentTimeMillis() - entryTime; if (watchTime > 0) videoSettingsRepository.totalVideoWatchTime += watchTime } }

    LaunchedEffect(url) {
        currentPosition = 0L; duration = 0L; bufferedPosition = 0L
        hasRestoredProgress = false; hasTriggeredAutoPlay = false; hasAutoSkippedIntro = false
        outroWarningTriggered = false
        exoPlayer.stop(); exoPlayer.clearMediaItems()

        val savedPos = viewModel.getVideoProgress(currentFile.fid)
        val startPositionMs = if (savedPos > 0L) savedPos else 0L

        var totalLength = currentFile.size

        val cacheFile = File(context.cacheDir, "proxy_${currentFile.fid}.mp4")

        // 跟着房主播的直链是「房主账号」解析出来的，本机 Cookie 对它是多余的凭据，
        // 带上反而会被网盘判成越权（403/跳转 → Source error）。所以这条路径只留 Referer。
        val isPeerLink = viewModel.isPeerLink(currentFile.fid)
        val headers = if (isPeerLink) {
            mapOf("Referer" to "https://drive.quark.cn/")
        } else {
            mapOf(
                "Cookie" to cookie,
                "Referer" to "https://drive.quark.cn/"
            )
        }
        if (isPeerLink) android.util.Log.d("Video", "peer link 模式：不带本机 Cookie")
        android.util.Log.d(
            "Video",
            "起播 fid=${currentFile.fid} peer=$isPeerLink size=${totalLength} headers=${headers.keys} start=${startPositionMs} url=${url.take(70)}"
        )
        // 网盘/直链条目入库时常常没写文件大小，先实测一次 Content-Length，别再直接弹错
        if (totalLength <= 0L) {
            totalLength = withContext(Dispatchers.IO) { probeContentLength(url, headers) }
            android.util.Log.d("Video", "size 探测=${totalLength} url=${url.take(60)}")
        }
        probedSizes[currentFile.fid] = totalLength
        if (totalLength <= 0L) {
            errorDialogMsg = "无法获取视频文件大小（链接可能需重新解析），请重试"
            return@LaunchedEffect
        }
        val refreshUrl: (suspend () -> String?) = {
            withContext(Dispatchers.IO) {
                try {
                    // 房主给的直链本机解析不出来，过期了就让房主再给一条新的
                    val u = if (isPeerLink) viewModel.requestFreshPeerLink(currentFile.fid)
                    else viewModel.getPlayUrl(currentFile.fid)
                    android.util.Log.d("Video", "refreshUrl peer=$isPeerLink fid=${currentFile.fid} got=${u?.take(70)}")
                    u
                } catch (e: Exception) {
                    android.util.Log.d("Video", "refreshUrl 异常 ${e.javaClass.simpleName}: ${e.message}")
                    null
                }
            }
        }

        proxyServer.registerVideo(
            id = currentFile.fid,
            url = url,
            headers = headers,
            file = cacheFile,
            totalLength = totalLength,
            downloadThreads = downloadThreads,
            refreshUrl = refreshUrl
        )

        val proxyUrl = proxyServer.proxyUrlFor(currentFile.fid)
        val mediaItem = MediaItem.Builder()
            .setUri(proxyUrl)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .build()
        // 播放器读的是本机代理（localhost）。慢网下代理要等分片补上来，默认读超时 8s 会把"慢"判成"错"，
        // 这里放宽到 90s（比代理侧 60s 的等待更久），让慢网表现为缓冲而不是报错。
        val httpFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(90_000)
            .setAllowCrossProtocolRedirects(true)
        val mediaSource = ProgressiveMediaSource
            .Factory(DefaultDataSource.Factory(context, httpFactory))
            .createMediaSource(mediaItem)

        exoPlayer.setMediaSource(mediaSource)
        if (startPositionMs > 0L) exoPlayer.seekTo(startPositionMs)
        exoPlayer.prepare()
        // 🌟 每次重新 prepare（切集/重新加载）后重申一次倍速，防止速度被 prepare 重置回 1.0x
        exoPlayer.setPlaybackParameters(PlaybackParameters(playbackSpeed, 1.0f))
        exoPlayer.playWhenReady = true
        // 刚 prepare 时 onPlayWhenReadyChanged 不一定回调（值本来就可能是 true），这里同步一次
        isPlaying = true
    }

    LaunchedEffect(currentFile.fid, isPlaying) {
        // 播放中每 10 秒写一次；暂停/退出/切集/播完/拖完松手另有补写点
        while (isPlaying) {
            delay(PROGRESS_SAVE_INTERVAL_MS)
            saveProgressPoint(currentFile.fid, exoPlayer.currentPosition)
        }
    }

    DisposableEffect(exoPlayer) {
        // 慢网/限速下分片补不上来会让 ExoPlayer 直接报错中断，这里自动重试几次，
        // 把"一断就崩"变成"卡一会儿"；真正网络不通时才弹提示
        var autoRetry = 0
        val maxAutoRetry = 5
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                isPlaying = playWhenReady
                // 暂停即补写：锁屏、切后台、手点暂停都走这里，别等 10 秒一跳
                if (!playWhenReady) {
                    currentFileState.value?.let { saveProgressPoint(it.fid, exoPlayer.currentPosition) }
                }
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                // 只刷新画中画按钮和状态栏，不用它驱动播放/暂停图标（缓冲中它会 false、和用户意图不一致）
                if (isInPip.value) isInPip.value = activity?.isInPictureInPictureMode == true
            }
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
                if (state == Player.STATE_ENDED) isPlaying = false
                if (state == Player.STATE_ENDED) {
                    // 播完补写一次完整时长，别让后面的收尾逻辑读到最后一次 10 秒前的旧位置
                    currentFileState.value?.let { saveProgressPoint(it.fid, exoPlayer.duration) }
                }
                if (state == Player.STATE_READY) {
                    duration = exoPlayer.duration.coerceAtLeast(0L)
                    EpisodeAudioCapture.setDuration(duration)
                    // 一起看房间里一律不自动跳片头：两端标记不一致会把位置同步来回拽
                    if (introEndMs > 0 && !hasAutoSkippedIntro && !partyState.active &&
                        exoPlayer.currentPosition < introEndMs
                    ) {
                        exoPlayer.seekTo(introEndMs); currentPosition = introEndMs; hasAutoSkippedIntro = true
                    }
                    // 一起看（加入端）：被动卡顿（缓冲空了/网速抖动）恢复后立刻对齐一次，
                    // 不等下一次 10 秒心跳，否则会一直差十几秒
                    val ps = WatchPartySession.state.value
                    if (!ps.isHost && ps.active && ps.connected) {
                        val s = ps.remoteSync
                        val myFid = currentFileState.value?.fid.orEmpty()
                        if (s != null && s.fid == myFid && !s.paused &&
                            System.currentTimeMillis() - lastAppliedRemoteAt > 1_000L
                        ) {
                            val expected = s.positionMs +
                                ((System.currentTimeMillis() - s.sentAt) * s.speed).toLong()
                            if (expected > 0 && abs(expected - exoPlayer.currentPosition) > 1_500L) {
                                exoPlayer.seekTo(expected)
                                lastAppliedRemoteAt = System.currentTimeMillis()
                            }
                        }
                    }
                } else if (state == Player.STATE_ENDED) {
                    val idx = sortedVideos.indexOfFirst { it.fid == currentFile.fid }
                    if (idx != -1 && idx < sortedVideos.size - 1) {
                        viewModel.playVideo(context, latestSeries, sortedVideos[idx + 1])
                    } else {
                        if (!outroWarningTriggered) {
                            playerToastMessage = "已是最新集"
                        }
                    }
                }
            }
            override fun onTracksChanged(tracks: Tracks) {
                val audioList = mutableListOf<Tracks.Group>(); val textList = mutableListOf<Tracks.Group>()
                for (group in tracks.groups) { if (group.type == androidx.media3.common.C.TRACK_TYPE_AUDIO) audioList.add(group); if (group.type == androidx.media3.common.C.TRACK_TYPE_TEXT) textList.add(group) }
                audioTracks = audioList; textTracks = textList
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) { if (videoSize.width > 0 && videoSize.height > 0) resolution = "${videoSize.width}×${videoSize.height}" }
            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.d(
                    "Video",
                    "onPlayerError ${error.errorCodeName} pos=${exoPlayer.currentPosition} dur=${exoPlayer.duration} " +
                        "buffered=${exoPlayer.bufferedPosition} msg=${error.message}"
                )
                val sb = StringBuilder().append("错误码: ${error.errorCodeName}\n")
                var cause: Throwable? = error; var depth = 0
                while (cause != null) {
                    sb.append("\n[第 ${depth} 层] ${cause.javaClass.simpleName}\n")
                    if (cause is HttpDataSource.InvalidResponseCodeException) { sb.append("HTTP状态码: ${cause.responseCode}\n"); cause.responseBody?.let { sb.append("响应体: ${String(it)}\n") } }
                    sb.append("信息: ${cause.message ?: "无"}\n"); cause = cause.cause; depth++
                }
                // 网络类错误（分片补不上来/连接抖）先自动重试，别当场把用户踹出播放页
                val isNetworkish = error.errorCode in intArrayOf(
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                )
                if (isNetworkish && autoRetry < maxAutoRetry) {
                    autoRetry++
                    playerToastMessage = "正在重试…"
                    val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
                    android.util.Log.d("Video", "网络类错误，自动重试 $autoRetry/$maxAutoRetry pos=$pos")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        runCatching {
                            // 同一个 MediaItem 重新 prepare：代理任务还在，缺的分片会重新排队补
                            exoPlayer.prepare()
                            exoPlayer.seekTo(pos)
                            exoPlayer.setPlaybackParameters(PlaybackParameters(playbackSpeed, 1.0f))
                            exoPlayer.playWhenReady = true
                        }
                    }, 3000L)
                } else if (isNetworkish) {
                    errorDialogMsg = "网络速度跟不上，无法继续播放。\n\n" +
                        "常见原因：网盘对当前账号限速、或当前网络带宽不足（4K 片源需要稳定 15Mbps 以上）。\n" +
                        "可以稍后重试，或换更低码率的片源。\n\n详细错误：\n$sb"
                } else {
                    errorDialogMsg = sb.toString()
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            // 退出播放页前先补写一次进度，再结算音频特征、放掉解码器
            currentFileState.value?.let { f ->
                runCatching { saveProgressPoint(f.fid, exoPlayer.currentPosition) }
            }
            // 先结算这一集的音频特征，再放掉解码器
            EpisodeAudioCapture.finish()
            exoPlayer.removeListener(listener)
            exoPlayer.stop()
            exoPlayer.release()
            proxyServer.unregisterAll()
        }
    }

    // 音轨默认选择：这台设备最终能出几个声道就挑哪条（能出 6 声道 → 默认环绕；只有两声道 → 默认立体声）
    // 用户手动切过之后本集内不再自动改回去
    LaunchedEffect(currentFile.fid) {
        userPickedAudio = false
        autoAudioFid = ""
    }
    LaunchedEffect(audioTracks, outputChannelLimit, currentFile.fid) {
        if (userPickedAudio || autoAudioFid == currentFile.fid) return@LaunchedEffect
        val groups = audioTracks
        if (groups.isEmpty()) return@LaunchedEffect
        autoAudioFid = currentFile.fid
        if (groups.size <= 1) return@LaunchedEffect
        fun channels(g: Tracks.Group): Int = g.getTrackFormat(0).channelCount
        val target = if (outputChannelLimit >= 6) {
            groups.filter { channels(it) >= 6 }.maxByOrNull { channels(it) }
        } else {
            groups.firstOrNull { channels(it) in 1..2 }
        } ?: return@LaunchedEffect
        if (!target.isTrackSelected(0)) {
            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(target.mediaTrackGroup, 0))
                .build()
            trackSelectionVersion++
        }
    }

    // 这个循环不跟 isPlaying 走：起播落在片尾/暂停时界面认为“没在播”，
    // 以前会把位置同步一起停掉，导致音频帧被记到错误的时间上（片头学到离谱的值）。
    LaunchedEffect(Unit) {
        while (true) {
            if (!isDraggingSeekBar) { currentPosition = exoPlayer.currentPosition; bufferedPosition = exoPlayer.bufferedPosition }
            EpisodeAudioCapture.onPosition(exoPlayer.currentPosition)

            // 一起看房间里一律不自动跳片尾（同上，避免两端互相拽）
            if (outroStartMs > 0 && duration > 0 && outroStartMs < duration && !partyState.active) {
                val outroTriggerPos = outroStartMs
                val warningPos = (outroTriggerPos - 6000L).coerceAtLeast(0L)

                if (currentPosition >= warningPos && !outroWarningTriggered) {
                    outroWarningTriggered = true
                    val idx = sortedVideos.indexOfFirst { it.fid == currentFile.fid }
                    if (idx == sortedVideos.size - 1) {
                        playerToastMessage = "已是最新集"
                    } else {
                        playerToastMessage = "即将跳过片尾"
                    }
                }

                if (currentPosition >= outroTriggerPos) {
                    val idx = sortedVideos.indexOfFirst { it.fid == currentFile.fid }
                    if (idx != -1 && idx < sortedVideos.size - 1) {
                        if (!hasTriggeredAutoPlay) {
                            hasTriggeredAutoPlay = true
                            viewModel.playVideo(context, latestSeries, sortedVideos[idx + 1])
                        }
                    }
                }
            }
            delay(1000)
        }
    }

    LaunchedEffect(showEpisodeDrawer, sortedVideos) { if (showEpisodeDrawer) { val idx = sortedVideos.indexOfFirst { it.fid == currentFile.fid }; if (idx != -1) episodeListState.animateScrollToItem(idx) } }
    // 一起看：房主每 10 秒广播一次「第几集 + 播放位置 + 是否暂停 + 倍速」，给加入端对齐基准。
    // 不能太频繁：ntfy 公共中继限流 60 次请求/5 分钟，2 秒一次会在两分钟后被 429 静默丢掉。
    LaunchedEffect(partyState.isHost, partyState.connected, currentFile.fid) {
        if (!partyState.isHost || !partyState.connected) return@LaunchedEffect
        while (true) {
            WatchPartySession.pushLocalSync(
                fid = currentFile.fid,
                episode = partyEpisodeLabel,
                positionMs = exoPlayer.currentPosition,
                paused = !exoPlayer.isPlaying,
                speed = playbackSpeed,
            )
            delay(10_000)
        }
    }

    // 一起看：切集 / 暂停 / 倍速 变化时立刻广播（远端应用引起的变化在这里被过滤掉）
    LaunchedEffect(currentFile.fid, isPlaying, playbackSpeed, partyState.connected, partyState.isHost, partyState.active) {
        val fidChanged = prevPartyFid != null && prevPartyFid != currentFile.fid
        val firstEnter = prevPartyFid == null
        // 房间是在播放中才建好的（先看片、后建房）：也要补一次「当前节目」，否则加入端只能等 10 秒心跳
        val justActivated = prevPartyActive == false && partyState.active
        val playingChanged = prevPartyPlaying != null && prevPartyPlaying != isPlaying
        val speedChanged = prevPartySpeed != null && prevPartySpeed != playbackSpeed
        prevPartyFid = currentFile.fid
        prevPartyPlaying = isPlaying
        prevPartySpeed = playbackSpeed
        prevPartyActive = partyState.active
        if (!partyState.active) return@LaunchedEffect
        // 房主换集（含刚进播放页、刚建房）：立刻发「当前节目」，观看端不用等真正开播就能先跳过去。
        // 房间只绑一部影视，同剧换集一律自动跟随，不需要任何确认
        if (partyState.isHost && partyState.connected && (fidChanged || firstEnter || justActivated)) {
            WatchPartySession.notifyShow(currentFile.fid, partyEpisodeLabel, latestSeries.title, partyEpisodeTitle)
            // 换集也等两端就位再一起开：对方这时还停在上一集，按集号筛会永远筛不到，所以不筛集号
            if (fidChanged) stagePartyPlayback("")
        }
        if (!partyState.connected) return@LaunchedEffect
        if (!fidChanged && !playingChanged && !speedChanged) return@LaunchedEffect
        // 刚 seek 完的短暂缓冲态不要广播（否则对端会跟着抖一下暂停），进度由上面那次 pushLocalAction 负责
        if (System.currentTimeMillis() - lastSeekAt < 1_500L) return@LaunchedEffect
        if (System.currentTimeMillis() - lastAppliedRemoteAt < 800L) return@LaunchedEffect
        lastLocalActionAt = System.currentTimeMillis()
        pushPartyNow()
    }

    // 仅测试包：adb 广播改倍速，等价于点倍速按钮（正式包 TEST_HOOKS=false，这段不生效）
    if (io.legado.app.BuildConfig.TEST_HOOKS) {
        val testSpeed by io.legado.app.help.watchparty.PartyTestBus.speed.collectAsStateWithLifecycle()
        LaunchedEffect(testSpeed) {
            val target = testSpeed ?: return@LaunchedEffect
            playbackSpeed = target.coerceIn(0.5f, 3.0f)
            exoPlayer.setPlaybackParameters(PlaybackParameters(playbackSpeed, 1.0f))
            android.util.Log.d("WatchParty", "test speed applied=$playbackSpeed")
            io.legado.app.help.watchparty.PartyTestBus.speed.value = null
        }
    }

    // 一起看：任意一端的最新操作都同步过来（最后操作生效）
    LaunchedEffect(partyState.remoteSync, partyState.connected) {
        val sync = partyState.remoteSync ?: return@LaunchedEffect
        if (!partyState.active || !partyState.connected) return@LaunchedEffect
        val now = System.currentTimeMillis()
        // 本机 1.5 秒内刚操作过，以本机为准
        if (now - lastLocalActionAt < 1_500L) return@LaunchedEffect
        if (sync.fid.isNotBlank() && sync.fid != currentFile.fid) {
            // 跨集/跨剧的跳转统一交给主页面那条「3 秒倒计时」的跟随提示，
            // 这里不再直接切，否则会绕过倒计时、还可能和主页面同时切导致来回跳
            return@LaunchedEffect
        }
        lastAppliedRemoteAt = now
        android.util.Log.d(
            "WatchParty",
            "receive sync speed=${sync.speed} local=$playbackSpeed paused=${sync.paused}"
        )
        val drift = sync.positionMs - exoPlayer.currentPosition
        if (abs(drift) > 2_000L) exoPlayer.seekTo(sync.positionMs)
        if (sync.paused && exoPlayer.isPlaying) exoPlayer.pause()
        if (!sync.paused && !exoPlayer.isPlaying) exoPlayer.play()
        if (abs(playbackSpeed - sync.speed) > 0.01f) {
            playbackSpeed = sync.speed.coerceIn(0.5f, 3.0f)
            exoPlayer.setPlaybackParameters(PlaybackParameters(playbackSpeed, 1.0f))
            android.util.Log.d("WatchParty", "apply speed=$playbackSpeed")
        }
    }

    // 房主主动拖动/换集后停在某处等我们就位：先对齐进度，再等「真的缓冲到能播」才回报 ready。
    // 之前是「最多等 6 秒就强行回执」，网慢的时候房主已经开播了、这边还在转圈，就是那个原因。
    // 单独一条 effect：不受上面「本机刚操作过就以本机为准」的 1.5 秒拦截影响，否则会漏掉就位请求。
    LaunchedEffect(partyState.stageRequest?.sentAt, partyState.stageRequest?.fid, currentFile.fid) {
        val sync = partyState.stageRequest ?: return@LaunchedEffect
        if (!sync.stage || partyState.isHost || !partyState.active || !partyState.connected) return@LaunchedEffect
        if (sync.fid.isBlank() || sync.fid != currentFile.fid) return@LaunchedEffect
        if (abs(exoPlayer.currentPosition - sync.positionMs) > 500L) {
            exoPlayer.seekTo(sync.positionMs)
        }
        val limit = System.currentTimeMillis() + 180_000L
        while (exoPlayer.playbackState != Player.STATE_READY &&
            System.currentTimeMillis() < limit &&
            WatchPartySession.state.value.active
        ) {
            delay(150)
        }
        if (exoPlayer.playbackState == Player.STATE_READY) {
            WatchPartySession.notifyReady(currentFile.fid, exoPlayer.currentPosition)
        }
    }

    // 一起看：房主退出播放页 = 房间结束 → 观看端立刻停下、退回影视库首页。
    // 以前这里只弹一句提示、继续播当前集：房主走了另一端还在看，像没人管一样。
    LaunchedEffect(partyState.hostLeft) {
        if (partyState.hostLeft && !partyState.isHost) {
            exoPlayer.pause()
            isPlaying = false
            // hostLeft 留给主页面弹提示并消费，播放页只负责退出
            onBack()
            viewModel.selectSeries(null)
        }
    }

    // 房主端：谁退出了房间（Session 收到 bye 或心跳超时后推过来）
    LaunchedEffect(partyState.noticeSeq) {
        val notice = partyState.notice ?: return@LaunchedEffect
        partyNoticeMessage = notice
        WatchPartySession.consumeNotice()
    }
    DisposableEffect(Unit) {
        onDispose {
            val s = WatchPartySession.state.value
            if (s.isHost && s.active) WatchPartySession.notifyHostLeftPlayer()
        }
    }

    LaunchedEffect(showControls, isPlaying, gestureType, showSettingsPanel, showEpisodeDrawer, partyState.pending.size) {
        // 有申请在等房主点头时，控制条不收：否则左侧「一起看」按钮跟着一起消失，房主根本点不到
        if (partyState.isHost && partyState.pending.isNotEmpty()) return@LaunchedEffect
        if (showControls && isPlaying && !isLocked && gestureType.isEmpty() && !showSettingsPanel && !showEpisodeDrawer && !isInPip.value && !isDraggingSeekBar) { delay(3000); showControls = false }
    }
    LaunchedEffect(isLocked, showUnlockIcon) { if (isLocked && showUnlockIcon) { delay(3000); showUnlockIcon = false } }
    LaunchedEffect(seekMessage) { if (seekMessage != null) { delay(1500); seekMessage = null } }

    BackHandler {
        // 锁屏状态下，返回键 / 全面屏返回手势只解锁，不退出播放
        if (isLocked) { isLocked = false; showControls = true }
        else if (showSettingsPanel) showSettingsPanel = false
        else if (showEpisodeDrawer) showEpisodeDrawer = false
        else onBack()
    }

    // 一起看房间面板（播放页内的房间卡片：邀请码/成员/审批/退出）
    // 房间只绑一部影视，同剧换集自动跟随，所以不需要「推送当前播放」按钮
    WatchPartyDialog(
        show = showPartyPanel,
        onDismiss = { showPartyPanel = false },
    )

    if (errorDialogMsg != null) {
        AlertDialog(
            onDismissRequest = { errorDialogMsg = null },
            title = { AppText("播放失败详情", fontWeight = FontWeight.Bold) },
            text = { Column { SelectionContainer { Text(text = errorDialogMsg ?: "", modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), fontSize = 12.sp, color = Color.DarkGray) } } },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("播放错误", errorDialogMsg))
                        Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    }) { AppText("复制错误", color = Color(0xFF333333)) }
                    TextButton(onClick = {
                        errorDialogMsg = null
                        viewModel.playVideo(context, series, currentFile)
                    }) { AppText("重试", color = Color(0xFF333333)) }
                    TextButton(onClick = { errorDialogMsg = null }) { AppText("关闭", color = Color(0xFF333333)) }
                }
            }
        )
    }

    // 房主端：等对方缓冲超过 30 秒，问是否继续播放
    if (stageWaitPrompt) {
        AlertDialog(
            onDismissRequest = { stageWaitPrompt = false },
            title = { AppText("对方还在缓冲", fontWeight = FontWeight.Bold) },
            text = { AppText("观看端网速较慢，已经等了 30 秒还没就绪。继续播放的话，对方缓冲好了会自己追上来。") },
            confirmButton = {
                TextButton(onClick = {
                    stagePlayNow = true
                    stageWaitPrompt = false
                }) { AppText("继续播放") }
            },
            dismissButton = {
                TextButton(onClick = { stageWaitPrompt = false }) { AppText("再等一会", color = Color.Gray) }
            },
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black).pointerInput(isLocked, isInPip.value) {
        if (isLocked || isInPip.value) { if (isLocked && !isInPip.value) detectTapGestures(onTap = { showUnlockIcon = true }); return@pointerInput }
        var gestureMode = ""; var startX = 0f; var startY = 0f; var startPos = 0L; var startBrightness = 0f; var startVolume = 0
        detectDragGestures(
            onDragStart = { offset -> gestureMode = ""; startX = offset.x; startY = offset.y; startPos = exoPlayer.currentPosition; seekWasPlaying = exoPlayer.isPlaying; startBrightness = activity?.window?.attributes?.screenBrightness?.let { if (it < 0) 0.5f else it } ?: 0.5f; startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) },
            onDrag = { change, _ ->
                val deltaX = change.position.x - startX; val deltaY = change.position.y - startY; val absX = abs(deltaX); val absY = abs(deltaY)
                if (gestureMode.isEmpty()) {
                    if (absX > 15f || absY > 15f) {
                        if (absX > absY) {
                            gestureMode = "seek"; isDraggingSeekBar = true; dragStartPosition = startPos; change.consume()
                        } else {
                            if (startX < size.width / 3f) {
                                gestureMode = "brightness"; gestureType = "brightness";
                                gestureValue = startBrightness
                                brightnessDisplayValue = startBrightness
                                showControls = false // 🌟 修复：左侧滑动亮度时，强制隐藏主控制面板，避免重叠
                                change.consume()
                            } else if (startX > size.width * 2f / 3f) {
                                gestureMode = "volume"; gestureType = "volume";
                                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                                gestureValue = startVolume.toFloat() / maxVol
                                volumeDisplayValue = gestureValue
                                showControls = false // 🌟 修复：右侧滑动音量时，强制隐藏主控制面板，避免重叠
                                change.consume()
                            } else {
                                gestureMode = "none"; change.consume()
                            }
                        }
                    }
                } else change.consume()

                when (gestureMode) {
                    "seek" -> { val timeDeltaMs = ((deltaX / size.width) * 300 * 1000).toLong(); seekDeltaMillis = timeDeltaMs; currentPosition = (startPos + timeDeltaMs).coerceIn(0L, if (duration > 0) duration else Long.MAX_VALUE) }
                    "brightness" -> {
                        val newB = (startBrightness - (deltaY / size.height)).coerceIn(0f, 1f)
                        gestureValue = newB
                        brightnessDisplayValue = newB
                        activity?.window?.let { val params = it.attributes; params.screenBrightness = newB; it.attributes = params }

                        if (newB == 0f && !hasVibratedBrightnessMin) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); hasVibratedBrightnessMin = true }
                        if (newB > 0f) hasVibratedBrightnessMin = false
                        if (newB == 1f && !hasVibratedBrightnessMax) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); hasVibratedBrightnessMax = true }
                        if (newB < 1f) hasVibratedBrightnessMax = false
                    }
                    "volume" -> {
                        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                        val newV = ((startVolume.toFloat() / maxVol) - (deltaY / size.height)).coerceIn(0f, 1f)
                        gestureValue = newV
                        volumeDisplayValue = newV
                        // 🌟 只在整数音量级别真的变化时才下发系统音量：
                        // 压缩音轨（AC3/DTS）走直通输出，每帧调 setStreamVolume 会反复重建音频输出链路导致滑条卡顿
                        val targetVol = (newV * maxVol).roundToInt()
                        if (targetVol != audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) {
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                        }

                        if (newV == 0f && !hasVibratedVolumeMin) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); hasVibratedVolumeMin = true }
                        if (newV > 0f) hasVibratedVolumeMin = false
                        if (newV == 1f && !hasVibratedVolumeMax) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); hasVibratedVolumeMax = true }
                        if (newV < 1f) hasVibratedVolumeMax = false
                    }
                }
            },
            onDragEnd = {
                if (gestureMode == "seek") {
                    if (abs(currentPosition - dragStartPosition) > 1000L) {
                        exoPlayer.seekTo(currentPosition)
                        lastSeekAt = System.currentTimeMillis()
                        saveProgressPoint(currentFile.fid, currentPosition)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (partyState.active && partyState.isHost && WatchPartySession.peersNeedReady(currentFile.fid).isNotEmpty()) {
                            stagePartyPlayback()
                        } else {
                            if (seekWasPlaying) exoPlayer.play()
                            pushLocalAction(pausedOverride = !seekWasPlaying)
                        }
                    } else currentPosition = dragStartPosition
                };
                gestureMode = ""; gestureType = ""; isDraggingSeekBar = false
            },
            onDragCancel = { if (gestureMode == "seek") currentPosition = dragStartPosition; gestureMode = ""; gestureType = ""; isDraggingSeekBar = false }
        )
    }.pointerInput(isLocked, showControls, showSettingsPanel, showEpisodeDrawer, isInPip.value) {
        if (isLocked || showSettingsPanel || showEpisodeDrawer || isInPip.value) return@pointerInput
        detectTapGestures(
            onTap = { showControls = !showControls },
            onDoubleTap = { offset ->
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                val nowMs = System.currentTimeMillis()
                // 连续双击（间隔 ≤1.5 秒、同方向）只累加提示数字，每次实际仍跳 10 秒
                fun nextAccumSec(dir: Int): Long {
                    seekMsgAccumSec =
                        if (seekMsgDir == dir && nowMs - seekMsgAt <= 1500L) seekMsgAccumSec + 10L else 10L
                    seekMsgDir = dir
                    seekMsgAt = nowMs
                    return seekMsgAccumSec
                }
                when {
                    offset.x < size.width * 0.25f -> {
                        exoPlayer.seekTo((exoPlayer.currentPosition - 10000).coerceAtLeast(0))
                        seekMessage = "已快退 ${nextAccumSec(-1)} 秒"
                        pushLocalAction()
                    }
                    offset.x > size.width * 0.75f -> {
                        exoPlayer.seekTo((exoPlayer.currentPosition + 10000).coerceAtMost(if (duration > 0) duration else Long.MAX_VALUE))
                        seekMessage = "已快进 ${nextAccumSec(1)} 秒"
                        pushLocalAction()
                    }
                    else -> {
                        seekMsgAccumSec = 0L; seekMsgDir = 0
                        togglePlayPause(); pushLocalAction()
                    }
                }
            },
            onLongPress = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                isLongPressing = true
                showLongPressText = true // 🌟 重置长按文本显示状态
                // 🌟 显式下发倍速参数（pitch 固定 1.0），避免部分设备只改数字不改实际速度
                exoPlayer.setPlaybackParameters(PlaybackParameters(2.0f, 1.0f))
            },
            onPress = {
                try {
                    tryAwaitRelease()
                } finally {
                    // 🌟 只有"真的长按加速过"才需要还原倍速；单纯点击不再覆盖用户已选倍速
                    if (isLongPressing) {
                        exoPlayer.setPlaybackParameters(PlaybackParameters(playbackSpeed, 1.0f))
                    }
                    isLongPressing = false
                    showLongPressText = false // 🌟 松手即收，不留残影
                }
            }
        )
    }) {
        AndroidView(factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = false
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                // 字幕：普通文本轨用统一基础样式（不指定字体，跟随系统主题字体）；
                // ASS/SSA 轨自带完整样式，整条放行、不改它的颜色/字体/字号。切换字幕轨时重新判断。
                val subtitleSwitcher = object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) {
                        val v = subtitleView ?: return
                        if (isAssSubtitleSelected(player.currentTracks)) styleEmbeddedSubtitle(v) else stylePlainSubtitle(v)
                    }
                }
                subtitleView?.let { stylePlainSubtitle(it) }
                exoPlayer.addListener(subtitleSwitcher)
            }
        }, modifier = Modifier.fillMaxSize(), update = { view -> view.useController = false })


        // 一起看入口已收进「播放设置」面板，不再在播放页浮层上跟播放器控件抢位置

        if (activity?.isInPictureInPictureMode == true || isInPip.value) { Box(modifier = Modifier.fillMaxSize()) {}; return@Box }

        AnimatedVisibility(
            visible = currentCenterMessage != null,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(250)),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = if (isLandscape) 40.dp else 60.dp)
        ) {
            Box(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(10.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                AppText(
                    text = lastCenterMessage ?: "",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        if (isBuffering || duration == 0L) {
            Box(modifier = Modifier.align(Alignment.Center).padding(16.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(color = Color.White, modifier = Modifier.size(36.dp)); Spacer(modifier = Modifier.height(12.dp)); AnimatedLoadingDots(color = Color.White, fontSize = 14.sp) }
            }
        }

        // 贴边式亮度进度条 (显示在右侧，避免左手遮挡)
        AnimatedVisibility(
            visible = gestureType == "brightness",
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 48.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(40.dp)
                    .height(260.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(20.dp))
                    .padding(vertical = 16.dp)
            ) {
                // 自绘太阳图标：太阳本体随亮度微涨，8 条光线随亮度连续变长并旋转（小米风格，无跳变）
                BrightnessLevelIcon(value = brightnessDisplayValue, size = 20.dp)
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .width(4.dp)
                        .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(2.dp)),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(brightnessDisplayValue.coerceIn(0f, 1f))
                            .background(Color.White, RoundedCornerShape(2.dp))
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                AppText(
                    text = if (brightnessDisplayValue == 0f) "极暗" else "${(brightnessDisplayValue * 100).roundToInt()}%",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // 贴边式音量进度条 (显示在左侧，避免右手遮挡)
        AnimatedVisibility(
            visible = gestureType == "volume",
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 48.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(40.dp)
                    .height(260.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(20.dp))
                    .padding(vertical = 16.dp)
            ) {
                // 自绘音量图标：0% 静音带斜杠；1–40% 一弧；41–70% 两弧；71–100% 三弧，档间平滑过渡
                VolumeLevelIcon(volume = volumeDisplayValue, size = 20.dp)
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .width(4.dp)
                        .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(2.dp)),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(volumeDisplayValue.coerceIn(0f, 1f))
                            .background(Color.White, RoundedCornerShape(2.dp))
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                AppText(
                    text = if (volumeDisplayValue == 0f) "静音" else "${(volumeDisplayValue * 100).roundToInt()}%",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (isDraggingSeekBar) {
            val deltaSec = (currentPosition - dragStartPosition) / 1000
            val deltaText = if (deltaSec >= 0) "+${formatDeltaTime(deltaSec)}" else "-${formatDeltaTime(abs(deltaSec))}"
            Box(modifier = Modifier.align(Alignment.Center).padding(bottom = 140.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(horizontal = 24.dp, vertical = 16.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AppText(text = "${formatSmartTime(currentPosition)} / ${formatSmartTime(duration)}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp)); AppText(text = deltaText, color = Color.LightGray, fontSize = 16.sp)
                }
            }
        }

        if (isLocked) {
            // 只保留左侧一个解锁按钮（点击解锁），不再左右各放一个
            if (showUnlockIcon) {
                IconButton(onClick = { isLocked = false; showControls = true }, modifier = Modifier.align(Alignment.CenterStart).padding(32.dp)) { Icon(Icons.Default.Lock, contentDescription = "解锁", tint = Color.White, modifier = Modifier.size(24.dp)) }
            }
            return@Box
        }

        // 一起看：房主收到申请就在播放页顶部常驻一条，直到点同意/拒绝为止。
        // 必须放在控制条那一层之外：控制条 3 秒自动隐藏，写在里面就会跟着一起消失，
        // 表现成「申请到了但屏幕上完全没提示」。
        val partyPending = partyState.pending
        AnimatedVisibility(
            visible = partyState.isHost && partyState.active && partyPending.isNotEmpty(),
            enter = fadeIn() + slideInVertically { -it / 2 },
            exit = fadeOut() + slideOutVertically { -it / 2 },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Column(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(top = 16.dp)
                    .background(Color(0xE61A1A1A), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                partyPending.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${p.userName} 申请加入一起看",
                            color = Color.White,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                        if (p.userId in partyState.approving) {
                            Text("已同意，等待对方进入…", color = Color(0xFFB8B8B8), fontSize = 12.sp)
                        } else {
                            // 用紧凑的 Text 代替 TextButton：TextButton 有 64dp 最小宽度 + 内边距，
                            // 两个按钮会把整条横幅撑得比内容宽很多（用户反馈「太宽」）
                            Text("同意", color = Color(0xFF66BB6A), fontSize = 13.sp,
                                modifier = Modifier.clickable { WatchPartySession.approve(p.userId) }
                                    .padding(horizontal = 6.dp, vertical = 4.dp))
                            Text("拒绝", color = Color(0xFFEF5350), fontSize = 13.sp,
                                modifier = Modifier.clickable { WatchPartySession.reject(p.userId) }
                                    .padding(start = 6.dp, end = 2.dp, top = 4.dp, bottom = 4.dp))
                        }
                    }
                }
            }
        }

        AnimatedVisibility(visible = showControls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(30.dp)) {
                    Surface(color = Color.White.copy(alpha = 0.1f), shape = CircleShape, border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))) {
                        IconButton(onClick = { if (isLandscape) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE }) { Icon(Icons.Default.ScreenRotation, contentDescription = "旋转屏幕", tint = Color.White) }
                    }
                    // 一起看入口：和旋转按钮同一套圆形样式，建房后按钮右上角挂人数/待审批角标
                    WatchPartyHud(
                        state = partyState,
                        onOpenSettings = { showPartyPanel = true },
                    )
                }

                Column(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(30.dp)) {
                    Surface(color = Color.White.copy(alpha = 0.1f), shape = CircleShape, border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))) {
                        IconButton(onClick = {
                            if (isInMultiWindowMode) return@IconButton

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) { playerToastMessage = "当前设备不支持画中画功能"; return@IconButton }
                                val safeActivity = activity as? ComponentActivity
                                if (safeActivity == null || safeActivity.lifecycle.currentState != Lifecycle.State.RESUMED) { playerToastMessage = "请等待视频播放界面完全加载后再试"; return@IconButton }
                                try {
                                    val prevAction = RemoteAction(Icon.createWithBitmap(createPipIcon("prev", context)), "上一集", "上一集", PendingIntent.getBroadcast(context, 100, Intent(PIP_ACTION).setPackage(context.packageName).putExtra(EXTRA_CONTROL, CONTROL_PREV), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                                    val playPauseAction = RemoteAction(Icon.createWithBitmap(createPipIcon(if (isPlaying) "pause" else "play", context)), if (isPlaying) "暂停" else "播放", if (isPlaying) "暂停" else "播放", PendingIntent.getBroadcast(context, 101, Intent(PIP_ACTION).setPackage(context.packageName).putExtra(EXTRA_CONTROL, CONTROL_PLAY_PAUSE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                                    val nextAction = RemoteAction(Icon.createWithBitmap(createPipIcon("next", context)), "下一集", "下一集", PendingIntent.getBroadcast(context, 102, Intent(PIP_ACTION).setPackage(context.packageName).putExtra(EXTRA_CONTROL, CONTROL_NEXT), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                                    val paramsBuilder = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).setActions(listOf(prevAction, playPauseAction, nextAction)).setSeamlessResizeEnabled(false)
                                    safeActivity.window.decorView.post { val success = safeActivity.enterPictureInPictureMode(paramsBuilder.build()); if (success) isInPip.value = true else playerToastMessage = "画中画启动失败，请检查系统权限是否已开启" }
                                } catch (e: Exception) { playerToastMessage = "画中画启动异常：${e.message ?: "权限被系统拦截"}" }
                            } else playerToastMessage = "Android 8.0 及以上才支持画中画"
                        }) { Icon(Icons.Default.PictureInPicture, null, tint = Color.White) }
                    }
                    Surface(color = Color.White.copy(alpha = 0.1f), shape = CircleShape, border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))) { IconButton(onClick = { isLocked = true; showControls = false; showUnlockIcon = true }) { Icon(Icons.Default.Lock, null, tint = Color.White) } }
                }

                Row(modifier = Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xAA000000), Color.Transparent))).then(if (!isLandscape) Modifier.statusBarsPadding() else Modifier).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
                    AppText(text = dynamicTitle, color = Color.White, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 8.dp, end = 16.dp).basicMarquee())
                    if (isLandscape) { AppText(text = networkSpeed, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(end = 12.dp)); AppText(text = currentSystemTime, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(end = 12.dp)) }
                    if (resolution.isNotBlank()) AppText(text = resolution, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(end = 12.dp))
                    IconButton(onClick = { showSettingsPanel = true }) { Icon(Icons.Default.Settings, contentDescription = "设置", tint = Color.White) }
                }

                Box(modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp).widthIn(max = if (isLandscape) 600.dp else 360.dp).fillMaxWidth(0.9f).background(Color(0xB31A1A1A), RoundedCornerShape(16.dp)).border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppText(text = formatSmartTime(currentPosition), color = Color.White, fontSize = 12.sp)
                            var sliderWidth by remember { mutableStateOf(1f) }
                            val bufferedFraction = if (duration > 0) bufferedPosition.toFloat() / duration else 0f
                            val currentFraction = if (duration > 0) currentPosition.toFloat() / duration else 0f
                            Box(modifier = Modifier.weight(1f).padding(horizontal = 8.dp).height(32.dp).onSizeChanged { sliderWidth = it.width.toFloat() }
                                .pointerInput(duration) {
                                    detectTapGestures(
                                        onPress = { offset -> if (duration > 0) { isDraggingSeekBar = true; seekWasPlaying = exoPlayer.isPlaying; dragStartPosition = exoPlayer.currentPosition; val target = (offset.x / sliderWidth).coerceIn(0f, 1f); currentPosition = (target * duration).toLong(); seekDeltaMillis = currentPosition - dragStartPosition } },
                                        onTap = { offset -> if (duration > 0) { val target = (offset.x / sliderWidth).coerceIn(0f, 1f); val finalPos = (target * duration).toLong(); if (abs(finalPos - exoPlayer.currentPosition) > 1000L) { seekWasPlaying = exoPlayer.isPlaying; exoPlayer.seekTo(finalPos); lastSeekAt = System.currentTimeMillis(); if (partyState.active && partyState.isHost && WatchPartySession.peersNeedReady(currentFile.fid).isNotEmpty()) stagePartyPlayback() else pushLocalAction(pausedOverride = !seekWasPlaying) }; isDraggingSeekBar = false } }
                                    )
                                }.pointerInput(duration) {
                                    detectDragGestures(
                                        onDragStart = { offset -> if (duration > 0) { isDraggingSeekBar = true; seekWasPlaying = exoPlayer.isPlaying; dragStartPosition = exoPlayer.currentPosition; val target = (offset.x / sliderWidth).coerceIn(0f, 1f); currentPosition = (target * duration).toLong(); seekDeltaMillis = currentPosition - dragStartPosition } },
                                        onDrag = { change, _ -> change.consume(); if (duration > 0) { val target = (change.position.x / sliderWidth).coerceIn(0f, 1f); currentPosition = (target * duration).toLong(); seekDeltaMillis = currentPosition - dragStartPosition } },
                                        onDragEnd = { isDraggingSeekBar = false; if (abs(currentPosition - dragStartPosition) > 1000L) { exoPlayer.seekTo(currentPosition); lastSeekAt = System.currentTimeMillis(); saveProgressPoint(currentFile.fid, currentPosition); haptic.performHapticFeedback(HapticFeedbackType.LongPress); if (partyState.active && partyState.isHost && WatchPartySession.peersNeedReady(currentFile.fid).isNotEmpty()) stagePartyPlayback() else { if (seekWasPlaying) exoPlayer.play(); pushLocalAction(pausedOverride = !seekWasPlaying) } } else currentPosition = dragStartPosition },
                                        onDragCancel = { isDraggingSeekBar = false }
                                    )
                                }, contentAlignment = Alignment.Center
                            ) {
                                Canvas(modifier = Modifier.fillMaxWidth().height(12.dp)) {
                                    val progressY = size.height / 2f; val trackHeight = 3.dp.toPx(); val thumbRadius = 6.dp.toPx(); val maxWidth = size.width - thumbRadius * 2
                                    drawLine(color = Color.White.copy(alpha = 0.2f), start = Offset(thumbRadius, progressY), end = Offset(size.width - thumbRadius, progressY), strokeWidth = trackHeight, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                                    if (bufferedFraction > 0) drawLine(color = Color.White.copy(alpha = 0.5f), start = Offset(thumbRadius, progressY), end = Offset(thumbRadius + maxWidth * bufferedFraction.coerceIn(0f, 1f), progressY), strokeWidth = trackHeight, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                                    if (currentFraction > 0) drawLine(color = Color.White, start = Offset(thumbRadius, progressY), end = Offset(thumbRadius + maxWidth * currentFraction.coerceIn(0f, 1f), progressY), strokeWidth = trackHeight, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                                    drawCircle(color = Color.White, radius = thumbRadius, center = Offset(thumbRadius + maxWidth * currentFraction.coerceIn(0f, 1f), progressY))
                                }
                            }
                            AppText(text = formatSmartTime(duration), color = Color.White, fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "${playbackSpeed}x", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable {
                                val nextSpeed = when(playbackSpeed) { 0.5f -> 0.75f; 0.75f -> 1.0f; 1.0f -> 1.25f; 1.25f -> 1.5f; 1.5f -> 2.0f; 2.0f -> 3.0f; else -> 0.5f }
                                playbackSpeed = nextSpeed
                                // 🌟 只在本播放页内生效（切集保留），退出播放页或重启 App 恢复 1.0x
                                exoPlayer.setPlaybackParameters(PlaybackParameters(nextSpeed, 1.0f))
                                pushLocalAction()
                            }.padding(8.dp))
                            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                if (isLandscape) AppText(text = if (introEndMs > 0) formatIntroOutro((introEndMs / 1000L).toInt()) else "片头", color = Color.White, fontSize = 12.sp, modifier = Modifier.combinedClickable(
                                    onClick = { if (currentPosition > 0L) { introEndMs = currentPosition; introSrc = "manual"; viewModel.saveEpisodeIntroOutro(currentFile.fid, episodeKey, introEndMs, outroStartMs, introSource = "manual", outroSource = outroSrc.ifBlank { "manual" }) } },
                                    onLongClick = { introEndMs = 0L; viewModel.saveEpisodeIntroOutro(currentFile.fid, episodeKey, 0L, outroStartMs, introSource = "manual", outroSource = outroSrc.ifBlank { "manual" }) }
                                ).padding(horizontal = 8.dp, vertical = 4.dp))
                                IconButton(onClick = { val videos = sortedVideos; val idx = videos.indexOfFirst { it.fid == currentFile.fid }; if (idx > 0) viewModel.playVideo(context, latestSeries, videos[idx - 1]) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(24.dp)) }
                                IconButton(onClick = { togglePlayPause(); pushLocalAction() }, modifier = Modifier.size(40.dp)) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(28.dp)) }
                                IconButton(onClick = { val videos = sortedVideos; val idx = videos.indexOfFirst { it.fid == currentFile.fid }; if (idx != -1 && idx < videos.size - 1) viewModel.playVideo(context, latestSeries, videos[idx + 1]) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.SkipNext, null, tint = Color.White, modifier = Modifier.size(24.dp)) }
                                if (isLandscape) AppText(text = if (outroStartMs > 0 && duration > outroStartMs) formatIntroOutro(((duration - outroStartMs) / 1000L).toInt()) else "片尾", color = Color.White, fontSize = 12.sp, modifier = Modifier.combinedClickable(
                                    onClick = { if (duration > 0 && currentPosition in 1 until duration) { outroStartMs = currentPosition; outroSrc = "manual"; viewModel.saveEpisodeIntroOutro(currentFile.fid, episodeKey, introEndMs, outroStartMs, introSource = introSrc.ifBlank { "manual" }, outroSource = "manual"); outroWarningTriggered = false } },
                                    onLongClick = { outroStartMs = 0L; viewModel.saveEpisodeIntroOutro(currentFile.fid, episodeKey, introEndMs, 0L, introSource = introSrc.ifBlank { "manual" }, outroSource = "manual"); outroWarningTriggered = false }
                                ).padding(horizontal = 8.dp, vertical = 4.dp))
                            }
                            if (isLandscape) AppText(text = "选集", color = Color.White, modifier = Modifier.clickable { showEpisodeDrawer = true }.padding(8.dp)) else Spacer(modifier = Modifier.width(32.dp))
                        }
                    }
                }
            }
        }

        AnimatedVisibility(visible = seekMessage != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter).padding(top = if (isLandscape) 40.dp else 60.dp)) { Box(modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 8.dp)) { AppText(text = seekMessage ?: "", color = Color.White, fontSize = 16.sp) } }

        if (showEpisodeDrawer) {
            Box(modifier = Modifier.fillMaxSize().clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { showEpisodeDrawer = false })
            AnimatedVisibility(visible = showEpisodeDrawer, enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300)), exit = slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)), modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(280.dp)) {
                Surface(color = Color(0xEE1A1A1A), modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { AppText("选集", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold); IconButton(onClick = { showEpisodeDrawer = false }) { Icon(Icons.Default.Close, null, tint = Color.White) } }
                        Spacer(modifier = Modifier.height(16.dp))
                        LazyColumn(state = episodeListState) {
                            items(sortedVideos) { file ->
                                val e = viewModel.extractEpisodeNumber(file.name, file.parsedInfo?.episode).takeIf { it > 0 }
                                val s = file.parsedInfo?.season ?: 1
                                val tmdbTitle = if (e != null) resolveEpisodeTitleOf(latestSeries, s, e) else null
                                val cleanTmdbTitle = tmdbTitle?.replace(Regex("^\\s*第\\s*\\d+\\s*集\\s*"), "")?.trim()
                                val label = if (e != null && latestSeries.category != "电影" && latestSeries.videos.size > 1) "第${e}集" + if (!cleanTmdbTitle.isNullOrBlank()) " $cleanTmdbTitle" else "" else if (latestSeries.videos.size == 1) latestSeries.title else (file.folderPath.lastOrNull() ?: file.name.substringBeforeLast("."))
                                val isCurrent = file.fid == currentFile.fid
                                AppText(text = label, color = if (isCurrent) Color.White else Color.Gray, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().background(if (isCurrent) Color(0x33FFFFFF) else Color.Transparent, RoundedCornerShape(8.dp)).clickable { viewModel.playVideo(context, latestSeries, file); showEpisodeDrawer = false }.padding(vertical = 12.dp, horizontal = 8.dp))
                            }
                        }
                    }
                }
            }
        }

        if (showSettingsPanel) {
            Box(modifier = Modifier.fillMaxSize().clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { showSettingsPanel = false })
            AnimatedVisibility(visible = showSettingsPanel, enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300)), exit = slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)), modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(280.dp)) {
                Surface(color = Color(0xEE1A1A1A), modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { AppText("播放设置", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold); IconButton(onClick = { showSettingsPanel = false }) { Icon(Icons.Default.Close, null, tint = Color.White) } }
                        Spacer(modifier = Modifier.height(16.dp))
                        Spacer(modifier = Modifier.height(8.dp)); AppText("音频轨道", color = Color.Gray, fontSize = 14.sp)
                        audioTracks.forEachIndexed { index, trackGroup -> val format = trackGroup.getTrackFormat(0); val rawLabel = format.label ?: format.language ?: "音轨 ${index + 1}"; val ch = format.channelCount; val kind = when { ch >= 6 -> "环绕"; ch == 2 -> "立体声"; ch == 1 -> "单声道"; ch > 2 -> "${ch}声道"; else -> "" }; val label = if (kind.isBlank()) rawLabel else "$rawLabel · $kind"; val isSelected = remember(trackSelectionVersion, audioTracks) { trackGroup.isTrackSelected(0) }; Row(modifier = Modifier.fillMaxWidth().clickable { userPickedAudio = true; exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(trackGroup.mediaTrackGroup, 0)).build(); trackSelectionVersion++; playerToastMessage = "已切换至 $rawLabel" }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { AppText(label, color = if (isSelected) Color.White else Color.Gray, fontSize = 15.sp, modifier = Modifier.weight(1f)) } }
                        Spacer(modifier = Modifier.height(16.dp)); AppText("字幕轨道", color = Color.Gray, fontSize = 14.sp)
                        textTracks.forEachIndexed { index, trackGroup -> val format = trackGroup.getTrackFormat(0); val label = format.label ?: format.language ?: "字幕 ${index + 1}"; val isSelected = remember(trackSelectionVersion, textTracks) { trackGroup.isTrackSelected(0) }; Row(modifier = Modifier.fillMaxWidth().clickable { exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(trackGroup.mediaTrackGroup, 0)).build(); trackSelectionVersion++; playerToastMessage = "已切换至 $label" }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { AppText(label, color = if (isSelected) Color.White else Color.Gray, fontSize = 15.sp, modifier = Modifier.weight(1f)) } }
                    }
                }
            }
        }
    }
}

@Composable
fun AnimatedLoadingDots(color: Color, fontSize: androidx.compose.ui.unit.TextUnit, text: String = "正在加载中") {
    val transition = rememberInfiniteTransition(label = "loadingDots")
    val textStyle = TextStyle(color = color, fontSize = fontSize, shadow = if (color == Color.Black) null else Shadow(color = Color.Black.copy(alpha = 0.8f), offset = Offset(1f, 1f), blurRadius = 4f))
    val dot1Y by transition.animateFloat(initialValue = 0f, targetValue = 1f, animationSpec = infiniteRepeatable(animation = keyframes { durationMillis = 1200; 0f at 0 using LinearEasing; 1f at 300 using LinearEasing; 0f at 600 using LinearEasing; 0f at 1200 using LinearEasing }), label = "dot1")
    val dot2Y by transition.animateFloat(initialValue = 0f, targetValue = 1f, animationSpec = infiniteRepeatable(animation = keyframes { durationMillis = 1200; 0f at 0 using LinearEasing; 0f at 100 using LinearEasing; 1f at 400 using LinearEasing; 0f at 700 using LinearEasing; 0f at 1200 using LinearEasing }), label = "dot2")
    val dot3Y by transition.animateFloat(initialValue = 0f, targetValue = 1f, animationSpec = infiniteRepeatable(animation = keyframes { durationMillis = 1200; 0f at 0 using LinearEasing; 0f at 200 using LinearEasing; 1f at 500 using LinearEasing; 0f at 800 using LinearEasing; 0f at 1200 using LinearEasing }), label = "dot3")
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppText(text, color = color, fontSize = fontSize, style = textStyle)
        Box(modifier = Modifier.width(20.dp).padding(start = 2.dp)) { Row { AppText(".", color = color, fontSize = fontSize, style = textStyle, modifier = Modifier.offset(y = (-4 * dot1Y).dp)); AppText(".", color = color, fontSize = fontSize, style = textStyle, modifier = Modifier.offset(y = (-4 * dot2Y).dp)); AppText(".", color = color, fontSize = fontSize, style = textStyle, modifier = Modifier.offset(y = (-4 * dot3Y).dp)) } }
    }
}

private fun formatSmartTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSeconds = ms / 1000; val hours = totalSeconds / 3600; val minutes = (totalSeconds % 3600) / 60; val seconds = totalSeconds % 60
    return if (hours > 0) String.format("%02d:%02d:%02d", hours, minutes, seconds) else String.format("%02d:%02d", minutes, seconds)
}

private fun formatDeltaTime(seconds: Long): String {
    if (seconds <= 0) return "0s"
    val h = seconds / 3600; val m = (seconds % 3600) / 60; val s = seconds % 60
    return buildString { if (h > 0) append("${h}h"); if (m > 0) append("${m}m"); if (s > 0 || (h == 0L && m == 0L)) append("${s}s") }
}

private fun formatIntroOutro(seconds: Int): String {
    if (seconds <= 0) return "0秒"
    val h = seconds / 3600; val m = (seconds % 3600) / 60; val s = seconds % 60
    return buildString { if (h > 0) append("${h}小时"); if (m > 0) append("${m}分"); if (s > 0 || (h == 0 && m == 0)) append("${s}秒") }
}

private fun Context.findActivity(): Activity? {
    var context = this
    while (context is android.content.ContextWrapper) { if (context is Activity) return context; context = context.baseContext }
    return null
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SeriesPosterCard(series: MediaSeries, isScraping: Boolean = false, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(modifier = Modifier.width(110.dp).combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) {
            if (series.posterUrl != null) AsyncImage(model = series.posterUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else {
                if (isScraping) Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.Gray, modifier = Modifier.size(24.dp), strokeWidth = 2.dp) }
                else AppText("长按修正", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            }
            if (series.rating != null) AppText(text = series.rating, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, style = TextStyle(shadow = Shadow(color = Color.Black, offset = Offset(1f, 1f), blurRadius = 3f)), modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp))
        }
        AppText(text = series.title, maxLines = 1, modifier = Modifier.padding(top = 4.dp).basicMarquee(), style = MaterialTheme.typography.bodyMedium)
        val seasons = series.videos.mapNotNull { it.parsedInfo?.season }.distinct().size
        if (series.videos.size == 1 || series.category == "电影") AppText(text = series.year?.toString() ?: "电影", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        else if (seasons > 1) AppText(text = "共 $seasons 季", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        else AppText(text = "共 1 季", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaDetailScreen(
    series: MediaSeries, allSeries: List<MediaSeries>, isScraping: Boolean = false, onBack: () -> Unit,
    onPlay: (MediaSeries, QuarkFile) -> Unit, onLongClickPoster: (MediaSeries) -> Unit, onSelectSeries: (MediaSeries) -> Unit
) {
    val viewModel: VideoScraperViewModel = koinViewModel()
    val videoSettingsRepository = koinInject<VideoSettingsRepository>()
    val context = LocalContext.current
    val activity = context.findActivity()
    val view = LocalView.current

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var showAllEpisodesDialog by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val window = activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            WindowCompat.setDecorFitsSystemWindows(window, false)
            controller.hide(WindowInsetsCompat.Type.navigationBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                WindowCompat.setDecorFitsSystemWindows(window, true)
                controller.show(WindowInsetsCompat.Type.navigationBars())
                window.decorView.requestApplyInsets()
            }
        }
    }

    val relatedSeries = remember(series, allSeries) {
        if (series.groupName.isNullOrBlank()) emptyList() else allSeries.filter { it.groupName == series.groupName && it.id != series.id }
    }

    val currentSeasonVideos = remember(series) {
        series.videos.sortedWith(
            compareBy<QuarkFile> { viewModel.extractEpisodeNumber(it.name, it.parsedInfo?.episode) }
                .thenBy { it.name }
        )
    }

    val latestWatchedFid = remember(uiState.playUrl, videoSettingsRepository.recentWatchedVideos, series.title) {
        try {
            val jsonArray = JSONArray(videoSettingsRepository.recentWatchedVideos)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                if (obj.optString("seriesTitle") == series.title) {
                    return@remember obj.optString("fid")
                }
            }
        } catch (e: Exception) {}
        null
    }

    val latestWatchedInCurrentSeason = remember(currentSeasonVideos, uiState.playUrl, latestWatchedFid) {
        val matchedFile = latestWatchedFid?.let { fid -> currentSeasonVideos.find { it.fid == fid } }
        matchedFile ?: currentSeasonVideos.maxByOrNull { viewModel.getVideoProgress(it.fid) }
    }

    val hasProgressInCurrentSeason = remember(latestWatchedInCurrentSeason, uiState.playUrl) {
        latestWatchedInCurrentSeason != null && viewModel.getVideoProgress(latestWatchedInCurrentSeason.fid) > 0
    }

    val targetPlayFile = if (hasProgressInCurrentSeason && latestWatchedInCurrentSeason != null) latestWatchedInCurrentSeason else currentSeasonVideos.firstOrNull()

    val playBtnText = remember(targetPlayFile, series, uiState.playUrl) {
        if (targetPlayFile != null && viewModel.getVideoProgress(targetPlayFile.fid) > 0) {
            val ep = viewModel.extractEpisodeNumber(targetPlayFile.name, targetPlayFile.parsedInfo?.episode).takeIf { it > 0 }
            if (ep != null && series.category != "电影" && series.videos.size > 1) "继续播放 第${ep}集" else "继续播放"
        } else "播放"
    }

    val listState = rememberLazyListState()

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().background(Color.White), contentPadding = PaddingValues(bottom = 100.dp)) {
        item {
            Box(modifier = Modifier.fillMaxWidth().height(350.dp)) {
                val bgUrl = series.backdropUrl ?: series.posterUrl
                if (bgUrl != null) {
                    AsyncImage(model = bgUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.White), startY = 200f, endY = 900f)))
                } else Box(modifier = Modifier.fillMaxSize().background(Color.LightGray))
                Row(modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.Black) }
                    IconButton(onClick = { onLongClickPoster(series) }) { Icon(Icons.Default.Edit, contentDescription = "编辑", tint = Color.Black) }
                }
            }
        }
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                AppText(text = series.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    if (series.rating != null) { Icon(Icons.Default.Star, null, tint = Color(0xFFFFC107), modifier = Modifier.size(16.dp)); AppText(text = " ${series.rating} ", style = MaterialTheme.typography.bodyMedium); AppText(text = " | ", color = Color.Gray) }
                    AppText(text = "${series.year ?: ""}", style = MaterialTheme.typography.bodyMedium)
                    AppText(text = " | ${series.category}", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                }
                Button(onClick = { targetPlayFile?.let { onPlay(series, it) } }, modifier = Modifier.fillMaxWidth().height(50.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333))) {
                    Icon(Icons.Default.PlayArrow, null, tint = Color.White); AppText(text = " $playBtnText", color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
                AppText(text = "剧情简介", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
                AppText(text = series.overview ?: "暂无简介", style = MaterialTheme.typography.bodyMedium, color = Color.DarkGray, modifier = Modifier.padding(top = 8.dp))
            }
        }
        item {
            Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 24.dp, bottom = 8.dp, end = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                AppText(text = "剧集/视频列表", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (currentSeasonVideos.size > 10) AppText(text = "共 ${currentSeasonVideos.size} 集，查看全部 >", style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.clickable { showAllEpisodesDialog = true })
            }
        }
        item {
            if (currentSeasonVideos.isEmpty() && !isScraping) { Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { AppText("暂无可用视频源或解析失败", color = Color.Gray) } }
            else {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(currentSeasonVideos.take(10)) { file ->
                        val e = viewModel.extractEpisodeNumber(file.name, file.parsedInfo?.episode).takeIf { it > 0 }
                        val s = file.parsedInfo?.season ?: 1
                        val tmdbTitle = if (e != null) resolveEpisodeTitleOf(series, s, e) else null
                        val cleanTmdbTitle = tmdbTitle?.replace(Regex("^\\s*第\\s*\\d+\\s*集\\s*"), "")?.trim()
                        val stillUrl = if (e != null) resolveEpisodeStillOf(series, s, e) else null
                        val label = if (e != null && series.category != "电影" && series.videos.size > 1) "第${e}集" + if (!cleanTmdbTitle.isNullOrBlank()) " $cleanTmdbTitle" else "" else if (series.videos.size == 1) series.title else (file.folderPath.lastOrNull() ?: file.name.substringBeforeLast("."))
                        Column(modifier = Modifier.width(160.dp).clickable { onPlay(series, file) }) {
                            Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) {
                                if (stillUrl != null) AsyncImage(model = stillUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else Icon(Icons.Default.PlayArrow, null, tint = Color.White)
                            }
                            AppText(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        if (relatedSeries.isNotEmpty()) {
            item { AppText(text = "系列其他影片", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp)) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(relatedSeries) { relSeries ->
                        Column(modifier = Modifier.width(110.dp).clickable { onSelectSeries(relSeries) }) {
                            Box(modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) {
                                if (relSeries.posterUrl != null) AsyncImage(model = relSeries.posterUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else AppText("无海报", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                                if (relSeries.rating != null) AppText(text = relSeries.rating, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, style = TextStyle(shadow = Shadow(color = Color.Black, offset = Offset(1f, 1f), blurRadius = 3f)), modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp))
                            }
                            AppText(text = relSeries.title, maxLines = 1, modifier = Modifier.padding(top = 4.dp).basicMarquee(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        item {
            Column(modifier = Modifier.fillMaxWidth().background(Color(0xFFF5F5F5)).padding(16.dp).padding(top = 24.dp)) {
                AppText(text = "文件信息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                val infoFile = targetPlayFile ?: series.videos.firstOrNull()
                infoFile?.let { firstFile ->
                    AppText(text = firstFile.name, style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(top = 8.dp))
                    val deepestFile = series.videos.maxByOrNull { it.folderPath.size } ?: firstFile
                    var pathStr = deepestFile.folderPath.joinToString("/")
                    if (!pathStr.startsWith("/")) pathStr = "/$pathStr"
                    AppText(text = "夸克网盘: $pathStr", style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(top = 4.dp))
                    val sizeInBytes = firstFile.size; val sizeInMB = sizeInBytes / (1024f * 1024f)
                    val sizeText = if (sizeInMB >= 1024f) String.format(Locale.getDefault(), "%.2f GB", sizeInMB / 1024f) else String.format(Locale.getDefault(), "%.1f MB", sizeInMB)
                    AppText(text = "共 ${series.videos.size} 集 | $sizeText (单集)", style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }

    if (showAllEpisodesDialog) {
        Dialog(onDismissRequest = { showAllEpisodesDialog = false }) {
            Surface(shape = RoundedCornerShape(24.dp), color = Color.White, modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp).navigationBarsPadding()) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        AppText("全部剧集 (${currentSeasonVideos.size})", color = Color(0xFF1A1A1A), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        IconButton(onClick = { showAllEpisodesDialog = false }) { Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color(0xFF999999)) }
                    }

                    val allEpisodesGridState = rememberLazyGridState()
                    val currentPlayingIndex = currentSeasonVideos.indexOfFirst { it.fid == (targetPlayFile?.fid ?: "") }
                    LaunchedEffect(Unit) {
                        if (currentPlayingIndex != -1) {
                            allEpisodesGridState.scrollToItem(currentPlayingIndex)
                        }
                    }

                    LazyVerticalGrid(
                        state = allEpisodesGridState,
                        columns = GridCells.Fixed(4),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(currentSeasonVideos) { file ->
                            val e = viewModel.extractEpisodeNumber(file.name, file.parsedInfo?.episode).takeIf { it > 0 }
                            val label = if (e != null && series.category != "电影" && series.videos.size > 1) "第${e}集" else if (series.videos.size == 1) series.title else (file.folderPath.lastOrNull() ?: file.name.substringBeforeLast("."))
                            val isCurrent = file.fid == (targetPlayFile?.fid ?: "")
                            Box(
                                modifier = Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(12.dp)).background(if (isCurrent) Color(0xFF333333) else Color(0xFFF5F5F5)).clickable { showAllEpisodesDialog = false; onPlay(series, file) },
                                contentAlignment = Alignment.Center
                            ) { AppText(text = label, color = if (isCurrent) Color.White else Color(0xFF333333), fontSize = 14.sp, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            }
        }
    }
}

/** 判断是否为物理上不支持变速的压缩音轨（AC3/EAC3/DTS/TrueHD/MLP），用于给用户明确提示 */
private fun isSpeedUnsupportedAudioMime(mime: String?): Boolean {
    if (mime.isNullOrBlank()) return false
    val m = mime.lowercase()
    return m.contains("ac3") || m.contains("eac3") || m.contains("dts") || m.contains("truehd") || m.contains("mlp")
}

/** 设备是否自带压缩音轨解码器（AC3/EAC3/DTS/TrueHD）：杜比机型通常自带，可把直通改成解码 */
private fun hasCompressedAudioDecoder(): Boolean = try {
    val list = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
    list.codecInfos.any { info ->
        !info.isEncoder && info.supportedTypes.any { t ->
            val lower = t.lowercase()
            lower == "audio/ac3" || lower == "audio/eac3" || lower == "audio/dca" || lower == "audio/true-hd"
        }
    }
} catch (e: Exception) {
    false
}

/**
 * 构造播放器工厂（纯系统解码，零第三方解码实现）：
 * 1) 音频能力只声明 PCM，禁用 AC3/EAC3/DTS 编码直通 —— 官方明确「直通/隧道模式下无法变速」；
 * 2) 声道上限压到 2，避免多声道走进系统空间音频管线；
 * 3) 打开 AudioTrack 的 playbackParams，硬解出的 PCM 才能被倍速。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun buildVideoRenderersFactory(context: Context, enableAudioTap: Boolean): DefaultRenderersFactory {
    // 声道上限按「这台设备最终能出几个声道」定，而不是 AudioCapabilities 宣称的能力：
    // 小米 13 宣称 ≥6，但喇叭只有两个声道，5.1 会被 AudioFlinger 降混、电平被压掉 → 只开 2 声道，交给解码器按标准系数降混
    val outputChannels = resolveOutputChannelLimit(context)
    // 光把 AudioCapabilities 写成 2 是压不住声道的：AudioSink 建 AudioTrack 时只认输入 Format 的 channelCount，
    // capabilities 里的声道数只影响「支持哪些编码 / 能否直通」。所以要真降混，必须在 sink 前挂一个混音处理器，
    // 按 AC3 参考系数把 5.1/7.1 混成 2.0 再交给 AudioFlinger（这一步同时绕开了系统那层不可控的降混）。
    val downmixProcessor = if (outputChannels < 6) {
        // 降混系数加起来会超过 1，遇到响的场景会削顶爆音。按方案乙：整体压 3dB（0.7071）再混，
        // 保证"不会破音"，代价是降混后音量回到正常水平、不会额外变大。要更响把 0.7071 调大，要更稳调小。
        val downmixGain = 0.7071f
        ChannelMixingAudioProcessor().apply {
            // 注意：只要处理器挂上去，每一种输入声道数都必须有对应矩阵，否则 onConfigure 会抛 UnhandledAudioFormatException。
            // 1~6 用 media3 内置系数（2->2 是单位矩阵，等于直通），7.1(8ch) 内置没有，按 AC3 规则自己配一份。
            listOf(6, 5, 4, 3, 2, 1).forEach { src ->
                runCatching {
                    val matrix = ChannelMixingMatrix.createForConstantPower(src, 2)
                    // 2.0 内容是单位矩阵（直通），不能再压，否则普通立体声会平白小 3dB
                    putChannelMixingMatrix(if (src == 2) matrix else matrix.scaleBy(downmixGain))
                }
            }
            runCatching {
                // 7.1: FL, FR, FC, LFE, BL, BR, SL, SR
                putChannelMixingMatrix(
                    ChannelMixingMatrix(
                        8, 2, floatArrayOf(
                            /* left  */ 1f, 0f, 0.7071f, 0.5f, 0.7071f, 0f, 0.7071f, 0f,
                            /* right */ 0f, 1f, 0.7071f, 0.5f, 0f, 0.7071f, 0f, 0.7071f
                        ).map { it * downmixGain }.toFloatArray()
                    )
                )
            }
        }
    } else {
        null
    }
    val factory = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean
        ): AudioSink {
            // 采集器放在降混之后：拿到的就是最终 2.0 PCM，片头片尾比对只需要声道一致的信号
            val audioTap = if (enableAudioTap) TeeAudioProcessor(EpisodeAudioCapture) else null
            val processors = listOfNotNull(downmixProcessor, audioTap)
            return DefaultAudioSink.Builder(context)
                .setAudioCapabilities(AudioCapabilities(intArrayOf(C.ENCODING_PCM_16BIT), outputChannels))
                .apply { if (processors.isNotEmpty()) setAudioProcessors(processors.toTypedArray()) }
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .build()
        }
    }
    // 只声明 PCM 16bit，就不再要浮点输出（原先「要浮点」和「只声明 16bit」两处说法不一致）
    factory.setEnableAudioFloatOutput(false)
    return factory
}

/**
 * 这台设备「最终能出几个声道」，决定 5.1 是「解码端降混」还是「原样输出」。
 *
 * 不能用 AudioCapabilities.maxChannelCount 判断：它只表示这个 AudioTrack 配置能被接受，
 * 小米 13 会报 ≥6，但内置喇叭只有两声道，最后仍由 AudioFlinger 降混（音量就是在这一层掉的）。
 * 这里只认当前媒体输出设备自己声明的 channelCounts：
 *  - 内置喇叭 / 有线或蓝牙耳机 → 2（5.1 由解码器降混，电平正常）
 *  - HDMI / USB / 线路输出（电视、功放、多声道声卡）→ 取其最大值（常见 6/8，可原样输出环绕）
 */
fun resolveOutputChannelLimit(context: Context): Int {
    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return 2
    val devices = try {
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    } catch (e: Exception) {
        null
    } ?: return 2
    fun priority(d: android.media.AudioDeviceInfo): Int = when (d.type) {
        android.media.AudioDeviceInfo.TYPE_HDMI,
        android.media.AudioDeviceInfo.TYPE_HDMI_ARC,
        android.media.AudioDeviceInfo.TYPE_USB_DEVICE,
        android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
        android.media.AudioDeviceInfo.TYPE_LINE_ANALOG,
        android.media.AudioDeviceInfo.TYPE_LINE_DIGITAL,
        android.media.AudioDeviceInfo.TYPE_AUX_LINE -> 3
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 2
        android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 1
        else -> 0
    }
    val best = devices.filter { it.isSink }.maxByOrNull { priority(it) } ?: return 2
    val channels = best.channelCounts.maxOrNull() ?: 2
    val capability = AudioCapabilities.getCapabilities(context).maxChannelCount.let { if (it >= 1) it else 2 }
    return minOf(channels, capability).coerceAtLeast(2)
}

/** 播放器音频属性：媒体/电影用途，并主动退出系统空间音频（避免多声道被 spatializer 接管后倍速失效 / 音量被再压一层） */
private fun buildVideoAudioAttributes(): androidx.media3.common.AudioAttributes {
    val builder = androidx.media3.common.AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        // 原先这里写 true（「我这段内容是空间音频」），同时又要求「不要做空间处理」，自相矛盾；
        // 现在统一成「不是空间音频内容 + 系统不要做空间化」
        .setIsContentSpatialized(false)
    if (android.os.Build.VERSION.SDK_INT >= 32) {
        builder.setSpatializationBehavior(android.media.AudioAttributes.SPATIALIZATION_BEHAVIOR_NEVER)
    }
    return builder.build()
}

/** 该音轨格式是否属于“解码后能正常变速”的常规编码 */

/**
 * 音量自绘图标（小米风格，纯白单色）：
 * 粗壮实心喇叭（矩形+锥形一体）+ 圆头短圆弧，与喇叭留间距。
 * 方案乙四档：0% = 喇叭 + 叉；1–20% = 1 弧；21–50% = 2 弧；51–100% = 3 弧。
 * 不做任何过渡动画，每帧按当前音量直绘，拖动完全跟手。
 */
@Composable
private fun VolumeLevelIcon(volume: Float, size: androidx.compose.ui.unit.Dp) {
    val level = when {
        volume <= 0f -> 0
        volume < 0.2f -> 1
        volume < 0.5f -> 2
        else -> 3
    }
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val k = w / 24f
        // 喇叭本体：矩形 + 锥形一体（小米那种粗实心块）
        val speaker = androidx.compose.ui.graphics.Path().apply {
            moveTo(3f * k, 8.9f * k)
            lineTo(7.6f * k, 8.9f * k)
            lineTo(13.6f * k, 4f * k)
            lineTo(13.6f * k, 20f * k)
            lineTo(7.6f * k, 15.1f * k)
            lineTo(3f * k, 15.1f * k)
            close()
        }
        drawPath(speaker, Color.White)
        val cx = 13.8f * k
        val cy = 12f * k
        val sw = 2.1f * k
        val radii = floatArrayOf(4.0f, 6.6f, 9.1f)
        for (i in 0 until level) {
            val r = radii[i] * k
            drawArc(
                color = Color.White, startAngle = -52f, sweepAngle = 104f, useCenter = false,
                topLeft = Offset(cx - r, cy - r), size = Size(r * 2, r * 2),
                style = Stroke(width = sw, cap = StrokeCap.Round)
            )
        }
        if (level == 0) {
            drawLine(Color.White, Offset(15.4f * k, 9.6f * k), Offset(18.6f * k, 14.4f * k), strokeWidth = sw, cap = StrokeCap.Round)
            drawLine(Color.White, Offset(18.6f * k, 9.6f * k), Offset(15.4f * k, 14.4f * k), strokeWidth = sw, cap = StrokeCap.Round)
        }
    }
}

/**
 * 亮度自绘图标（小米同款太阳）：8 条短圆头光线、与圆留固定空隙，
 * 亮度升高顺时针转、降低逆时针转，整段亮度转 2 圈（720°）；
 * 完全不做动画过渡，每帧按当前值直接绘制 → 拖动跟手无延迟；颜色始终纯白。
 */
@Composable
private fun BrightnessLevelIcon(value: Float, size: androidx.compose.ui.unit.Dp) {
    val v = value.coerceIn(0f, 1f)
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val k = w / 24f
        val cx = w * 0.5f
        val cy = w * 0.5f
        val coreR = (2.9f + 1.7f * v) * k
        val gap = 2.0f * k
        val rayLen = (0.9f + 1.7f * v) * k
        val inner = coreR + gap
        val alpha = 0.7f + 0.3f * v
        val rotDeg = 720f * v
        drawCircle(Color.White, radius = coreR, center = Offset(cx, cy))
        for (i in 0 until 8) {
            val angle = Math.toRadians((i * 45f - 90f + rotDeg).toDouble())
            val dx = cos(angle).toFloat()
            val dy = sin(angle).toFloat()
            drawLine(
                color = Color.White.copy(alpha = alpha),
                start = Offset(cx + dx * inner, cy + dy * inner),
                end = Offset(cx + dx * (inner + rayLen), cy + dy * (inner + rayLen)),
                strokeWidth = 1.5f * k,
                cap = StrokeCap.Round
            )
        }
    }
}

/** 用 HEAD / Range 实测远端文件大小；网盘直链常缺 size，播放前补一次，失败返回 0 */
/** 播放前实测到的文件大小，供“给加入端回直链”时复用（fid -> size） */
private val probedSizes = java.util.concurrent.ConcurrentHashMap<String, Long>()

private fun probeContentLength(url: String, headers: Map<String, String>): Long {
    fun open(method: String, range: String?): java.net.HttpURLConnection? = runCatching {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.instanceFollowRedirects = true
        headers.forEach { (k, v) -> if (v.isNotBlank()) c.setRequestProperty(k, v) }
        if (range != null) c.setRequestProperty("Range", range)
        c
    }.getOrNull()

    open("HEAD", null)?.let { c ->
        runCatching {
            if (c.responseCode in 200..299) {
                val len = c.getHeaderField("Content-Length")?.toLongOrNull() ?: 0L
                if (len > 0L) {
                    c.disconnect()
                    return len
                }
            }
        }
        c.disconnect()
    }
    open("GET", "bytes=0-0")?.let { c ->
        runCatching {
            if (c.responseCode in 200..299) {
                val total = c.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: 0L
                val len = if (total > 0L) total else (c.getHeaderField("Content-Length")?.toLongOrNull() ?: 0L)
                if (len > 0L) {
                    c.disconnect()
                    return len
                }
            }
        }
        c.disconnect()
    }
    return 0L
}
