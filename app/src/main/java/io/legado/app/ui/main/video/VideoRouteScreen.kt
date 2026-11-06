package io.legado.app.ui.main.video

import android.annotation.SuppressLint
import android.app.Activity
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
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.net.TrafficStats
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Rational
import android.view.OrientationEventListener
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
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import io.legado.app.data.repository.VideoSettingsRepository
import io.legado.app.ui.widget.components.text.AppText
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

// 用于解析最近观看的历史记录
data class RecentWatchedItem(
    val fid: String,
    val title: String,
    val seriesTitle: String,
    val coverPath: String,
    val timestamp: Long
)

// 全局统一背景色
val AppBackgroundColor = Color(0xFFF7F8FA)

// 使用原生 Canvas 绘制 PiP 图标
fun createPipIcon(type: String, context: Context): Bitmap {
    val size = 96 
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = Paint().apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    val path = Path()
    val w = size.toFloat()
    val h = size.toFloat()
    when(type) {
        "prev" -> {
            paint.strokeWidth = 8f
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(w*0.3f, h*0.25f, w*0.3f, h*0.75f, paint)
            path.moveTo(w*0.75f, h*0.2f); path.lineTo(w*0.4f, h*0.5f); path.lineTo(w*0.75f, h*0.8f); path.close()
            canvas.drawPath(path, paint)
        }
        "next" -> {
            paint.strokeWidth = 8f
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(w*0.7f, h*0.25f, w*0.7f, h*0.75f, paint)
            path.moveTo(w*0.25f, h*0.2f); path.lineTo(w*0.6f, h*0.5f); path.lineTo(w*0.25f, h*0.8f); path.close()
            canvas.drawPath(path, paint)
        }
        "play" -> {
            path.moveTo(w*0.35f, h*0.2f); path.lineTo(w*0.8f, h*0.5f); path.lineTo(w*0.35f, h*0.8f); path.close()
            canvas.drawPath(path, paint)
        }
        "pause" -> {
            canvas.drawRoundRect(w*0.3f, h*0.2f, w*0.45f, h*0.8f, 4f, 4f, paint)
            canvas.drawRoundRect(w*0.55f, h*0.2f, w*0.7f, h*0.8f, 4f, 4f, paint)
        }
    }
    return bitmap
}

// ==========================================
// 1. 资源库独立页面
// ==========================================
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun VideoLibraryScreen(
    modifier: Modifier = Modifier,
    viewModel: VideoScraperViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showSettingsDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = uiState.currentFid != "0") {
        viewModel.goBack()
    }

    Column(modifier = modifier.fillMaxSize().background(AppBackgroundColor).statusBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp), 
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (uiState.currentFid != "0") {
                IconButton(onClick = { viewModel.goBack() }, modifier = Modifier.padding(end = 8.dp)) { 
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") 
                }
            }
            AppText(
                text = "资源库",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.weight(1f).padding(start = if (uiState.currentFid == "0") 8.dp else 0.dp)
            )
            IconButton(onClick = { showSettingsDialog = true }) { 
                Icon(Icons.Default.Settings, contentDescription = "设置", tint = Color.DarkGray) 
            }
        }

        if (uiState.errorMsg != null && !uiState.isLoading) {
            Box(modifier = Modifier.fillMaxWidth().background(Color(0xFFFFCDD2)).padding(8.dp), contentAlignment = Alignment.Center) {
                AppText(uiState.errorMsg!!, color = Color.Red, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (uiState.isLoading && uiState.fileList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            if (uiState.fileList.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { AppText("当前文件夹为空", color = Color.Gray) }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(), 
                    contentPadding = PaddingValues(16.dp), 
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(uiState.fileList) { file ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White) 
                                .clickable(enabled = file.isFolder) { 
                                    if (file.isFolder) viewModel.fetchQuarkFiles(file.fid, folderName = file.name) 
                                }
                                .padding(horizontal = 12.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (file.isFolder) Icons.Default.Folder else Icons.Default.Movie, 
                                null, 
                                tint = if (file.isFolder) Color(0xFFFFB300) else Color.Gray,
                                modifier = Modifier.size(28.dp)
                            )
                            Column(modifier = Modifier.padding(start = 16.dp).weight(1f)) { 
                                AppText(text = file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium) 
                            }
                            if (file.isFolder) {
                                IconButton(onClick = { 
                                    val fullPath = (viewModel.browsePath + file.name).joinToString("/")
                                    viewModel.toggleLibrary(file.fid, fullPath) 
                                }) {
                                    Icon(
                                        if (uiState.selectedLibraryFids.contains(file.fid)) Icons.Default.Star else Icons.Outlined.StarBorder, 
                                        "加入影视库",
                                        tint = if (uiState.selectedLibraryFids.contains(file.fid)) Color(0xFFFFB300) else Color.LightGray
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSettingsDialog) {
        SettingsDialog(
            uiState = uiState,
            viewModel = viewModel,
            onDismiss = { showSettingsDialog = false },
            onWebLoginClick = { }
        )
    }
}


// ==========================================
// 2. 影视库独立页面
// ==========================================
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun VideoScraperScreen(
    isActive: Boolean = true,
    modifier: Modifier = Modifier,
    viewModel: VideoScraperViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context.findActivity()
    val videoSettingsRepository = koinInject<VideoSettingsRepository>()

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
                    list.add(
                        RecentWatchedItem(
                            fid = obj.optString("fid"),
                            title = obj.optString("title"),
                            seriesTitle = sTitle,
                            coverPath = obj.optString("coverPath"),
                            timestamp = obj.optLong("timestamp")
                        )
                    )
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
        else if (showAllHistoryPage) {
            if (isSelectionMode) {
                isSelectionMode = false
                selectedTitles.clear()
            } else {
                showAllHistoryPage = false
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(AppBackgroundColor)) {
        if (uiState.currentSeries != null) {
            MediaDetailScreen(
                series = uiState.currentSeries!!,
                allSeries = uiState.seriesList,
                onBack = { viewModel.selectSeries(null) },
                onPlay = { series, file -> viewModel.playVideo(context, series, file) },
                onLongClickPoster = { manualScrapeSeries = it; showManualScrapeDialog = true },
                onSelectSeries = { viewModel.selectSeries(it) }
            )
        } else if (viewAllCategory != null) {
            val categorySeries = uiState.seriesList.filter { it.category == viewAllCategory }
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding().background(AppBackgroundColor)) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewAllCategory = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.Black) }
                    AppText(text = viewAllCategory ?: "", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3), contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(categorySeries) { series ->
                        SeriesPosterCard(series = series, onClick = { viewModel.selectSeries(series) }, onLongClick = { manualScrapeSeries = series; showManualScrapeDialog = true })
                    }
                }
            }
        } else if (showAllHistoryPage) {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding().background(AppBackgroundColor)) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (isSelectionMode) {
                        IconButton(onClick = { isSelectionMode = false; selectedTitles.clear() }) { 
                            Icon(Icons.Default.Close, contentDescription = "取消", tint = Color.Black) 
                        }
                        AppText(text = "已选 ${selectedTitles.size} 项", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 8.dp))
                        IconButton(onClick = { 
                            if (selectedTitles.isNotEmpty()) {
                                viewModel.removeRecentWatchedBatch(selectedTitles.toSet())
                                selectedTitles.clear()
                                isSelectionMode = false
                            }
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除", tint = Color.Red)
                        }
                    } else {
                        IconButton(onClick = { showAllHistoryPage = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.Black) }
                        AppText(text = "最近观看", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 8.dp))
                        Box(modifier = Modifier.combinedClickable(
                            onClick = { isSelectionMode = true },
                            onLongClick = { showClearHistoryConfirm = true }
                        )) {
                            Icon(Icons.Default.Menu, contentDescription = "选择", tint = Color.Black, modifier = Modifier.padding(12.dp))
                        }
                    }
                }
                
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    groupedHistory.forEach { (category, items) ->
                        if (items.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                AppText(
                                    text = category,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                                )
                            }
                            items(items) { historyItem ->
                                RecentWatchedGridCard(
                                    item = historyItem, 
                                    isSelectionMode = isSelectionMode,
                                    isSelected = selectedTitles.contains(historyItem.seriesTitle),
                                    onClick = {
                                        if (isSelectionMode) {
                                            if (selectedTitles.contains(historyItem.seriesTitle)) {
                                                selectedTitles.remove(historyItem.seriesTitle)
                                            } else {
                                                selectedTitles.add(historyItem.seriesTitle)
                                            }
                                        } else {
                                            val series = uiState.seriesList.find { it.title == historyItem.seriesTitle }
                                            val file = series?.videos?.find { it.fid == historyItem.fid }
                                            if (series != null && file != null) {
                                                viewModel.playVideo(context, series, file)
                                            } else {
                                                val tempFile = QuarkFile(historyItem.fid, historyItem.title, false)
                                                val tempSeries = MediaSeries(
                                                    id = "temp_${historyItem.fid}",
                                                    title = historyItem.seriesTitle,
                                                    originalParsedTitle = historyItem.seriesTitle,
                                                    year = null,
                                                    category = category,
                                                    posterUrl = historyItem.coverPath.ifBlank { null },
                                                    videos = listOf(tempFile)
                                                )
                                                viewModel.playVideo(context, tempSeries, tempFile)
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        } else {
            Column(modifier = modifier.fillMaxSize().statusBarsPadding()) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppText(
                        text = "影视库",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f).padding(start = 16.dp)
                    )
                    IconButton(onClick = { showSettingsDialog = true }) { Icon(Icons.Default.Settings, contentDescription = "设置") }
                    IconButton(onClick = { viewModel.loadLibraryVideos() }, enabled = !uiState.isLoading) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = if (uiState.isLoading) Color.Gray else Color.Black)
                    }
                }

                if (uiState.errorMsg != null && !uiState.isLoading) {
                    Box(modifier = Modifier.fillMaxWidth().background(Color(0xFFFFCDD2)).padding(8.dp), contentAlignment = Alignment.Center) {
                        AppText(uiState.errorMsg!!, color = Color.Red, style = MaterialTheme.typography.bodySmall)
                    }
                }

                if (uiState.isLoading && uiState.seriesList.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    if (uiState.selectedLibraryFids.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { AppText("请先在资源库中勾选媒体文件夹") }
                    } else if (uiState.seriesList.isEmpty() && !uiState.isLoading) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { AppText("没找到任何视频，点击右上角刷新") }
                    } else {
                        val grouped = uiState.seriesList.groupBy { it.category }
                        val orderList = uiState.categoryOrder.split(",").filter { it.isNotBlank() }
                        val allCategories = grouped.keys.toMutableList()
                        orderList.forEach { if (allCategories.contains(it)) allCategories.remove(it) }
                        val finalOrderList = orderList + allCategories

                        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                            item {
                                val bannerList = remember(uiState.seriesList, uiState.bannerLogic) {
                                    val list = uiState.seriesList.filter { !it.backdropUrl.isNullOrBlank() }
                                    when (uiState.bannerLogic) {
                                        "最近播放" -> list.sortedByDescending { s -> 
                                            recentWatchedList.find { it.seriesTitle == s.title }?.timestamp ?: 0L 
                                        }
                                        "最近新增" -> list
                                        else -> list.shuffled() 
                                    }.take(10)
                                }
                                if (bannerList.isNotEmpty()) {
                                    BannerCarousel(bannerList, onSelect = { viewModel.selectSeries(it) })
                                }
                            }

                            finalOrderList.forEach { categoryName ->
                                if (categoryName == "最近观看") {
                                    if (recentWatchedList.isNotEmpty()) {
                                        item {
                                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                AppText(text = "最近观看", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                                AppText(text = "全部 ${recentWatchedList.size} >", style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.clickable { showAllHistoryPage = true })
                                            }
                                        }
                                        item {
                                            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                                items(recentWatchedList.take(10)) { item ->
                                                    RecentWatchedCard(
                                                        item = item, 
                                                        onClick = {
                                                            val series = uiState.seriesList.find { it.title == item.seriesTitle }
                                                            val file = series?.videos?.find { it.fid == item.fid }
                                                            if (series != null && file != null) {
                                                                viewModel.playVideo(context, series, file)
                                                            } else {
                                                                val tempFile = QuarkFile(item.fid, item.title, false)
                                                                val tempSeries = MediaSeries(
                                                                    id = "temp_${item.fid}",
                                                                    title = item.seriesTitle,
                                                                    originalParsedTitle = item.seriesTitle,
                                                                    year = null,
                                                                    category = "未分类",
                                                                    posterUrl = item.coverPath.ifBlank { null },
                                                                    videos = listOf(tempFile)
                                                                )
                                                                viewModel.playVideo(context, tempSeries, tempFile)
                                                            }
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    val seriesList = grouped[categoryName]
                                    if (seriesList != null && seriesList.isNotEmpty()) {
                                        item {
                                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                AppText(text = categoryName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                                AppText(text = "全部 ${seriesList.size} 部 >", style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.clickable { viewAllCategory = categoryName })
                                            }
                                        }
                                        item {
                                            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                                items(seriesList) { series ->
                                                    SeriesPosterCard(
                                                        series = series, 
                                                        isScraping = uiState.isScraping,
                                                        onClick = { viewModel.selectSeries(series) }, 
                                                        onLongClick = { manualScrapeSeries = series; showManualScrapeDialog = true }
                                                    )
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
        }

        if (uiState.isLoading || uiState.isScraping) {
            val isVideoPlaying = uiState.playUrl != null || uiState.currentPlayFile != null || uiState.isDirectPlay
            if (!isVideoPlaying) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 100.dp)
                        .background(Color.White.copy(alpha = 0.95f), RoundedCornerShape(24.dp))
                        .border(1.dp, Color.Gray.copy(alpha = 0.2f), RoundedCornerShape(24.dp))
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    AnimatedLoadingDots(
                        color = Color.Black, 
                        fontSize = 14.sp, 
                        text = if (uiState.isScraping) "正在刮削数据" else "正在刷新列表"
                    )
                }
            }
        }
    }

    if (showSettingsDialog) {
        SettingsDialog(
            uiState = uiState, 
            viewModel = viewModel, 
            onDismiss = { showSettingsDialog = false }, 
            onWebLoginClick = { showLoginConfirmDialog = true }
        )
    }

    if (showLoginConfirmDialog) {
        val isLoggedIn = uiState.cookie.isNotBlank()
        AlertDialog(
            onDismissRequest = { showLoginConfirmDialog = false },
            title = { AppText("登录", fontWeight = FontWeight.Bold) },
            text = { AppText(if (isLoggedIn) "当前状态：已登录" else "当前状态：未登录") },
            confirmButton = {
                TextButton(onClick = {
                    showLoginConfirmDialog = false
                    if (isLoggedIn) {
                        viewModel.loginWithCapturedCookie("")
                    }
                    showWebLoginDialog = true
                }) { AppText("登录") }
            },
            dismissButton = {
                TextButton(onClick = { showLoginConfirmDialog = false }) { AppText("取消", color = Color.Gray) }
            }
        )
    }

    if (showWebLoginDialog) {
        Dialog(onDismissRequest = { showWebLoginDialog = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(modifier = Modifier.fillMaxSize().background(Color.White).statusBarsPadding()) {
                WebLoginView(
                    onLoginSuccess = { cookie ->
                        viewModel.loginWithCapturedCookie(cookie)
                        showWebLoginDialog = false
                        showSettingsDialog = false
                    },
                    onCancel = { showWebLoginDialog = false }
                )
            }
        }
    }

    if (showManualScrapeDialog && manualScrapeSeries != null) {
        ManualScrapeDialog(series = manualScrapeSeries!!, viewModel = viewModel, onDismiss = { showManualScrapeDialog = false })
    }

    if (showClearHistoryConfirm) {
        AlertDialog(
            onDismissRequest = { showClearHistoryConfirm = false },
            title = { AppText("清空历史记录", fontWeight = FontWeight.Bold) },
            text = { AppText("确定要清空所有历史观看记录吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearRecentWatched()
                    showClearHistoryConfirm = false
                }) { AppText("清空", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryConfirm = false }) { AppText("取消", color = Color.Gray) }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BannerCarousel(seriesList: List<MediaSeries>, onSelect: (MediaSeries) -> Unit) {
    val pagerState = rememberPagerState(pageCount = { seriesList.size })
    
    LaunchedEffect(pagerState, seriesList.size) {
        if (seriesList.size > 1) {
            while (true) {
                delay(4000)
                val nextPage = (pagerState.currentPage + 1) % seriesList.size
                pagerState.animateScrollToPage(nextPage)
            }
        }
    }

    Box(modifier = Modifier.fillMaxWidth().height(220.dp).padding(bottom = 12.dp)) {
        HorizontalPager(state = pagerState, contentPadding = PaddingValues(horizontal = 16.dp), pageSpacing = 8.dp) { page ->
            val series = seriesList[page]
            Box(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).clickable { onSelect(series) }) {
                AsyncImage(
                    model = series.backdropUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)), startY = 200f, endY = 600f))
                )
                Column(modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                    AppText(text = series.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val infoText = listOfNotNull(series.year?.toString(), series.category, series.rating?.let { "⭐$it" }).joinToString(" | ")
                    if (infoText.isNotBlank()) {
                        AppText(text = infoText, color = Color.LightGray, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            repeat(seriesList.size) { iteration ->
                val color = if (pagerState.currentPage == iteration) Color.White else Color.White.copy(alpha = 0.5f)
                Box(
                    modifier = Modifier
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(color)
                        .size(6.dp)
                )
            }
        }
    }
}

@Composable
fun RecentWatchedCard(item: RecentWatchedItem, onClick: () -> Unit) {
    Column(modifier = Modifier.width(110.dp).clickable { onClick() }) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(Color.DarkGray), contentAlignment = Alignment.Center) {
            if (item.coverPath.isNotEmpty()) {
                AsyncImage(model = item.coverPath, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(Icons.Default.Movie, null, tint = Color.LightGray)
            }
        }
        AppText(text = item.seriesTitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp), fontWeight = FontWeight.Bold)
    }
}

@Composable
fun RecentWatchedGridCard(
    item: RecentWatchedItem, 
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(Color.DarkGray), contentAlignment = Alignment.Center) {
            if (item.coverPath.isNotEmpty()) {
                AsyncImage(model = item.coverPath, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(Icons.Default.Movie, null, tint = Color.LightGray)
            }
            if (isSelectionMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(24.dp)
                        .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                        .border(1.dp, Color.White, RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        AppText(text = item.seriesTitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp), fontWeight = FontWeight.Bold)
    }
}

// ==========================================
// 辅助组件：设置弹窗、手动刮削弹窗
// ==========================================
@Composable
private fun SettingsDialog(
    uiState: VideoUiState,
    viewModel: VideoScraperViewModel,
    onDismiss: () -> Unit,
    onWebLoginClick: (() -> Unit)? = null
) {
    var inputCookie by remember { mutableStateOf(uiState.cookie) }
    var inputTmdbKey by remember { mutableStateOf(uiState.tmdbApiKey) }
    var inputTmdbUrl by remember { mutableStateOf(uiState.tmdbBaseUrl) }
    
    var isCookieVisible by remember { mutableStateOf(false) }
    var isTmdbKeyVisible by remember { mutableStateOf(false) }
    
    var showThreadSelection by remember { mutableStateOf(false) }
    var showBannerLogicSelection by remember { mutableStateOf(false) }
    var showCategoryOrderDialog by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val view = LocalView.current
        LaunchedEffect(Unit) {
            val window = (view.parent as? DialogWindowProvider)?.window
            window?.setDimAmount(0f)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
        }

        Surface(
            shape = RoundedCornerShape(0.dp),
            color = AppBackgroundColor, 
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()) 
            ) {
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    AppText(text = "设置", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                }

                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    AppText(text = "夸克登录状态", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    if (onWebLoginClick != null) {
                        OutlinedButton(
                            onClick = onWebLoginClick,
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            modifier = Modifier.height(36.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                        ) {
                            AppText("登录", fontSize = 13.sp)
                        }
                    }
                }
                
                OutlinedTextField(
                    value = inputCookie,
                    onValueChange = { inputCookie = it },
                    modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth(),
                    visualTransformation = if (isCookieVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    maxLines = 2,
                    label = { AppText("手动填写 Cookie（可选）") },
                    trailingIcon = {
                        IconButton(onClick = { isCookieVisible = !isCookieVisible }) {
                            Icon(
                                imageVector = if (isCookieVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = "切换可见性"
                            )
                        }
                    }
                )
                
                AppText(text = "TMDB API Key (v3 auth)", modifier = Modifier.padding(top = 8.dp))
                
                OutlinedTextField(
                    value = inputTmdbKey,
                    onValueChange = { inputTmdbKey = it },
                    modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth(),
                    visualTransformation = if (isTmdbKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    maxLines = 1,
                    trailingIcon = {
                        IconButton(onClick = { isTmdbKeyVisible = !isTmdbKeyVisible }) {
                            Icon(
                                imageVector = if (isTmdbKeyVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = "切换可见性"
                            )
                        }
                    }
                )

                AppText(text = "TMDB API 代理地址")
                OutlinedTextField(value = inputTmdbUrl, onValueChange = { inputTmdbUrl = it }, modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth(), maxLines = 1)
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { showThreadSelection = true }.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppText("极速播放线程数")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppText("${uiState.downloadThreads} ", color = Color.Gray)
                        Icon(Icons.Default.KeyboardArrowRight, null, tint = Color.Gray)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().clickable { showBannerLogicSelection = true }.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppText("首页轮播海报显示逻辑")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppText(uiState.bannerLogic, color = Color.Gray)
                        Icon(Icons.Default.KeyboardArrowRight, null, tint = Color.Gray)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().clickable { showCategoryOrderDialog = true }.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppText("分类排序")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.KeyboardArrowRight, null, tint = Color.Gray)
                    }
                }

                OutlinedButton(
                    onClick = {
                        viewModel.saveSettings(inputCookie, inputTmdbKey, inputTmdbUrl)
                        onDismiss()
                    }, 
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                ) { 
                    AppText("确认保存", fontWeight = FontWeight.Bold) 
                }
            }
        }
    }

    if (showThreadSelection) {
        AlertDialog(
            onDismissRequest = { showThreadSelection = false },
            title = { AppText("极速播放线程数", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    val options = listOf(3, 6, 10, 16, 24, 32)
                    options.forEach { count ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                viewModel.saveDownloadThreads(count)
                                showThreadSelection = false
                            }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppText("$count", modifier = Modifier.weight(1f))
                            if (uiState.downloadThreads == count) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThreadSelection = false }) { AppText("取消") }
            }
        )
    }

    if (showBannerLogicSelection) {
        AlertDialog(
            onDismissRequest = { showBannerLogicSelection = false },
            title = { AppText("首页轮播海报显示逻辑", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    val options = listOf("智能推荐", "最近新增", "最近播放")
                    options.forEach { logic ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                viewModel.saveBannerLogic(logic)
                                showBannerLogicSelection = false
                            }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppText(logic, modifier = Modifier.weight(1f))
                            if (uiState.bannerLogic == logic) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBannerLogicSelection = false }) { AppText("取消") }
            }
        )
    }

    if (showCategoryOrderDialog) {
        CategoryOrderDialog(uiState, viewModel, onDismiss = { showCategoryOrderDialog = false })
    }
}

@Composable
fun CategoryOrderDialog(
    uiState: VideoUiState,
    viewModel: VideoScraperViewModel,
    onDismiss: () -> Unit
) {
    val currentOrder = uiState.categoryOrder.split(",").filter { it.isNotBlank() }
    val existingCategories = uiState.seriesList.map { it.category }.filter { it.isNotBlank() }.distinct()
    
    val orderList = remember(uiState.categoryOrder) { 
        val list = mutableStateListOf<String>()
        list.addAll(currentOrder)
        existingCategories.forEach { if (!list.contains(it)) list.add(it) }
        if (!list.contains("最近观看")) list.add(0, "最近观看") else {
            list.remove("最近观看")
            list.add(0, "最近观看")
        }
        list
    }

    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        if (from.index == 0) return@rememberReorderableLazyListState
        if (to.index > 0 && from.index != to.index) {
            val item = orderList.removeAt(from.index)
            orderList.add(to.index, item)
        }
    }

    Dialog(onDismissRequest = {
        viewModel.saveCategoryOrder(orderList.joinToString(","))
        onDismiss()
    }) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.White,
            modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    AppText("拖动排序", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { 
                        viewModel.saveCategoryOrder(orderList.joinToString(","))
                        onDismiss() 
                    }) { AppText("保存", fontWeight = FontWeight.Bold) }
                }
                Spacer(modifier = Modifier.height(8.dp))
                
                LazyColumn(state = lazyListState, modifier = Modifier.fillMaxWidth()) {
                    itemsIndexed(orderList, key = { _, item -> item }) { index, item ->
                        ReorderableItem(reorderableState, key = item) { isDragging ->
                            val elevation = if (isDragging) 8.dp else 0.dp
                            Surface(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp),
                                shadowElevation = elevation,
                                color = if (isDragging) Color(0xFFF0F0F0) else Color.White,
                                border = BorderStroke(1.dp, Color(0xFFE0E0E0))
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AppText(item, fontWeight = FontWeight.Medium)
                                    IconButton(
                                        onClick = {},
                                        modifier = Modifier.draggableHandle()
                                    ) {
                                        Icon(Icons.Default.Menu, contentDescription = "拖动排序", tint = if (item != "最近观看") Color.Gray else Color.LightGray)
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

@Composable
private fun ManualScrapeDialog(
    series: MediaSeries,
    viewModel: VideoScraperViewModel,
    onDismiss: () -> Unit
) {
    var searchKeyword by remember { mutableStateOf(series.title) }
    var searchResults by remember { mutableStateOf<List<TmdbSearchResult>?>(null) }
    var categoryInput by remember { mutableStateOf(series.category) }

    Dialog(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.background(Color.White).padding(16.dp).fillMaxWidth().heightIn(max = 600.dp)) {
            AppText("手动修正刮削信息")
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                AppText("分类：", modifier = Modifier.width(50.dp))
                OutlinedTextField(value = categoryInput, onValueChange = { categoryInput = it }, modifier = Modifier.weight(1f), placeholder = { AppText("如：动漫、电影") })
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                AppText("名称：", modifier = Modifier.width(50.dp))
                OutlinedTextField(value = searchKeyword, onValueChange = { searchKeyword = it }, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { viewModel.applyManualScrape(series.id, null, categoryInput, searchKeyword); onDismiss() }, modifier = Modifier.padding(start = 8.dp)) { AppText("仅保存") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Spacer(modifier = Modifier.width(50.dp))
                OutlinedButton(onClick = { viewModel.searchTmdbManually(searchKeyword) { searchResults = it } }, modifier = Modifier.fillMaxWidth()) { AppText("去 TMDB 搜索海报") }
            }
            if (searchResults == null) {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { AppText("输入名称并搜索", color = Color.Gray) }
            } else if (searchResults!!.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { AppText("未找到", color = Color.Red) }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(searchResults!!) { result ->
                        Row(modifier = Modifier.fillMaxWidth().clickable { viewModel.applyManualScrape(series.id, result, categoryInput, result.title); onDismiss() }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (result.posterUrl != null) AsyncImage(model = result.posterUrl, contentDescription = null, modifier = Modifier.size(60.dp, 90.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                            else Box(modifier = Modifier.size(60.dp, 90.dp).background(Color.LightGray).clip(RoundedCornerShape(8.dp)))
                            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                                AppText(result.title, style = MaterialTheme.typography.titleMedium)
                                AppText(result.year, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}


// ==========================================
// 播放器与登录页面
// ==========================================
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
                TextButton(onClick = {
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                    reloadTrigger++
                }) { AppText("清空缓存", color = Color.Red) }
                
                TextButton(onClick = {
                    val cookie = CookieManager.getInstance().getCookie("https://pan.quark.cn/")
                    if (!cookie.isNullOrBlank() && cookie.contains("__uid=")) {
                        onLoginSuccess(cookie)
                    } else {
                        Toast.makeText(context, "尚未检测到有效登录，请完成扫码或授权", Toast.LENGTH_SHORT).show()
                    }
                }) { AppText("完成登录", color = MaterialTheme.colorScheme.primary) }
            }
        }
        if (loadingProgress < 1f) {
            LinearProgressIndicator(progress = { loadingProgress }, modifier = Modifier.fillMaxWidth().height(2.dp))
        }
        AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { ctx ->
            WebView(ctx).apply {
                webViewRef = this
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                    userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }
                }
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        loadingProgress = newProgress / 100f
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val url = request?.url.toString()
                        if (!url.startsWith("http://") && !url.startsWith("https://")) {
                            Toast.makeText(context, "已拦截外部跳转，请在当前页面完成登录", Toast.LENGTH_SHORT).show()
                            return true 
                        }
                        return false 
                    }
                }
                loadUrl("https://pan.quark.cn/")
            }
        }, update = { webView ->
            if (reloadTrigger > 0) {
                webView.clearHistory()
                webView.clearCache(true)
                webView.loadUrl("https://pan.quark.cn/")
                reloadTrigger = 0 
            }
        })
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun InternalVideoPlayer(
    url: String,
    title: String,
    cookie: String,
    series: MediaSeries,
    currentFile: QuarkFile,
    viewModel: VideoScraperViewModel,
    downloadThreads: Int,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val videoSettingsRepository = koinInject<VideoSettingsRepository>()

    val isInPip = remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    var errorDialogMsg by remember { mutableStateOf<String?>(null) }
    val lastManualSwitchTime = remember { longArrayOf(0L) }
    var isLocked by remember { mutableStateOf(false) }
    val currentIsLocked by rememberUpdatedState(isLocked)

    var currentSystemTime by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        while (true) {
            currentSystemTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            delay(1000)
        }
    }

    DisposableEffect(Unit) {
        val orientationListener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                if (currentIsLocked || isInPip.value) return
                if (System.currentTimeMillis() - lastManualSwitchTime[0] < 2000) return

                when (orientation) {
                    in 45..135 -> {
                        if (activity?.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE) {
                            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                        }
                    }
                    in 135..225 -> {}
                    in 225..315 -> {
                        if (activity?.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        }
                    }
                    else -> {
                        if (activity?.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
                            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        }
                    }
                }
            }
        }
        orientationListener.enable()
        onDispose { orientationListener.disable() }
    }

    LaunchedEffect(isLandscape, isInPip.value) {
        if (view.isInEditMode || isInPip.value) return@LaunchedEffect
        val window = (view.parent as? DialogWindowProvider)?.window ?: activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (isLandscape) {
                WindowCompat.setDecorFitsSystemWindows(window, false)
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                WindowCompat.setDecorFitsSystemWindows(window, true)
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    val originalBrightness = remember { activity?.window?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            val window = (view.parent as? DialogWindowProvider)?.window ?: activity?.window
            window?.let {
                WindowCompat.setDecorFitsSystemWindows(it, true)
                WindowCompat.getInsetsController(it, it.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
            activity?.window?.let {
                val params = it.attributes
                params.screenBrightness = originalBrightness
                it.attributes = params
            }
        }
    }

    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var bufferedPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var resolution by remember { mutableStateOf("") }
    var networkSpeed by remember { mutableStateOf("0 KB/s") }
    var showControls by remember { mutableStateOf(true) }
    var showUnlockIcon by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableStateOf(1.0f) }

    var showSettingsPanel by remember { mutableStateOf(false) }
    var showEpisodeDrawer by remember { mutableStateOf(false) }
    var showIntroOutroDialog by remember { mutableStateOf(false) }

    var isLongPressing by remember { mutableStateOf(false) }
    var gestureType by remember { mutableStateOf("") }
    var gestureValue by remember { mutableFloatStateOf(0f) }
    var isDraggingSeekBar by remember { mutableStateOf(false) }
    
    // 用于记录拖动进度条时的秒数差值
    var dragStartPosition by remember { mutableLongStateOf(0L) }
    var seekDeltaMillis by remember { mutableLongStateOf(0L) }
    
    // 用于双击快进/快退的提示文字
    var seekMessage by remember { mutableStateOf<String?>(null) }

    val season = currentFile.parsedInfo?.season ?: 1
    var introSeconds by remember { mutableIntStateOf(0) }
    var outroSeconds by remember { mutableIntStateOf(0) }

    LaunchedEffect(series.title, season) {
        val pair = viewModel.getIntroOutro(series.title, season)
        introSeconds = pair.first
        outroSeconds = pair.second
    }

    var hasTriggeredAutoPlay by remember { mutableStateOf(false) }
    var hasAutoSkippedIntro by remember { mutableStateOf(false) }
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var hasRestoredProgress by remember { mutableStateOf(false) }
    
    var entryTime by remember { mutableLongStateOf(0L) }

    var audioTracks by remember { mutableStateOf<List<Tracks.Group>>(emptyList()) }
    var textTracks by remember { mutableStateOf<List<Tracks.Group>>(emptyList()) }
    var trackSelectionVersion by remember { mutableIntStateOf(0) } 

    val episodeListState = rememberLazyListState()

    LaunchedEffect(Unit) {
        var lastRxBytes = TrafficStats.getUidRxBytes(Process.myUid())
        while (true) {
            delay(1000)
            val currentRxBytes = TrafficStats.getUidRxBytes(Process.myUid())
            val delta = currentRxBytes - lastRxBytes
            lastRxBytes = currentRxBytes

            networkSpeed = if (delta > 1024 * 1024) {
                String.format(Locale.getDefault(), "%.1f MB/s", delta / (1024f * 1024f))
            } else {
                String.format(Locale.getDefault(), "%d KB/s", delta / 1024)
            }
        }
    }

    val exoPlayer = remember {
        val headers = mapOf(
            "Cookie" to cookie,
            "Referer" to "https://drive.quark.cn/"
        )
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        val dataSourceFactory = ParallelDownloadDataSource.Factory(okHttpClient, headers, downloadThreads)
        val renderersFactory = DefaultRenderersFactory(context).setEnableAudioFloatOutput(true)

        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .build()

        val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        val loadControl = DefaultLoadControl.Builder().setBufferDurationsMs(30000, 120000, 1500, 3000).build()

        ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .build()
            .apply {
                setMediaSource(mediaSource)
                prepare()
                playWhenReady = true
                setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
            }
    }

    DisposableEffect(lifecycleOwner) {
        var isPlayerReleased = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!isPlayerReleased && activity?.isInPictureInPictureMode != true) {
                        exoPlayer.pause()
                    }
                    if (activity?.isInPictureInPictureMode == true) {
                        isInPip.value = true
                    }
                }, 100)
            } else if (event == Lifecycle.Event.ON_RESUME) {
                if (activity?.isInPictureInPictureMode != true) {
                    isInPip.value = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { 
            isPlayerReleased = true
            lifecycleOwner.lifecycle.removeObserver(observer) 
        }
    }

    val pipActionReceiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    "com.mie.reading.ACTION_PIP_PREV" -> {
                        val idx = series.videos.indexOfFirst { it.fid == currentFile.fid }
                        if (idx > 0) {
                            viewModel.playVideo(context, series, series.videos[idx - 1])
                        } else {
                            Toast.makeText(context, "已经是第一集了", Toast.LENGTH_SHORT).show()
                        }
                    }
                    "com.mie.reading.ACTION_PIP_NEXT" -> {
                        val idx = series.videos.indexOfFirst { it.fid == currentFile.fid }
                        if (idx != -1 && idx < series.videos.size - 1) {
                            viewModel.playVideo(context, series, series.videos[idx + 1])
                        } else {
                            Toast.makeText(context, "已经是最后一集了", Toast.LENGTH_SHORT).show()
                        }
                    }
                    "com.mie.reading.ACTION_PIP_PLAY_PAUSE" -> {
                        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        val filter = IntentFilter().apply {
            addAction("com.mie.reading.ACTION_PIP_PREV")
            addAction("com.mie.reading.ACTION_PIP_NEXT")
            addAction("com.mie.reading.ACTION_PIP_PLAY_PAUSE")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(pipActionReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(pipActionReceiver, filter)
        }
        onDispose {
            context.unregisterReceiver(pipActionReceiver)
        }
    }

    LaunchedEffect(isPlaying, isInPip.value) {
        if (isInPip.value && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                try {
                    val timeStamp = System.currentTimeMillis()
                    
                    val prevBitmap = createPipIcon("prev", context)
                    val playPauseBitmap = createPipIcon(if (isPlaying) "pause" else "play", context)
                    val nextBitmap = createPipIcon("next", context)

                    val prevIntent = Intent("com.mie.reading.ACTION_PIP_PREV").setPackage(context.packageName).setData(Uri.parse("pip://prev$timeStamp"))
                    val prevPendingIntent = PendingIntent.getBroadcast(context, 1, prevIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    val prevAction = RemoteAction(Icon.createWithBitmap(prevBitmap), "上一集", "上一集", prevPendingIntent)

                    val playPauseIntent = Intent("com.mie.reading.ACTION_PIP_PLAY_PAUSE").setPackage(context.packageName).setData(Uri.parse("pip://play$timeStamp"))
                    val playPauseRequestCode = if (isPlaying) 2 else 3
                    val playPausePendingIntent = PendingIntent.getBroadcast(context, playPauseRequestCode, playPauseIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    val playPauseAction = RemoteAction(
                        Icon.createWithBitmap(playPauseBitmap),
                        if (isPlaying) "暂停" else "播放",
                        if (isPlaying) "暂停" else "播放",
                        playPausePendingIntent
                    )

                    val nextIntent = Intent("com.mie.reading.ACTION_PIP_NEXT").setPackage(context.packageName).setData(Uri.parse("pip://next$timeStamp"))
                    val nextPendingIntent = PendingIntent.getBroadcast(context, 4, nextIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    val nextAction = RemoteAction(Icon.createWithBitmap(nextBitmap), "下一集", "下一集", nextPendingIntent)

                    val params = PictureInPictureParams.Builder()
                        .setAspectRatio(Rational(16, 9))
                        .setActions(listOf(prevAction, playPauseAction, nextAction))
                        .build()
                    activity?.setPictureInPictureParams(params)
                } catch (e: Exception) {}
            }
        }
    }

    DisposableEffect(url) {
        entryTime = System.currentTimeMillis()
        onDispose {
            val endTime = System.currentTimeMillis()
            val watchTime = endTime - entryTime
            if (watchTime > 0) {
                videoSettingsRepository.totalVideoWatchTime += watchTime
            }
        }
    }

    LaunchedEffect(url) {
        hasRestoredProgress = false
        hasTriggeredAutoPlay = false
        hasAutoSkippedIntro = false
        exoPlayer.stop()
        exoPlayer.clearMediaItems()

        val headers = mapOf("Cookie" to cookie, "Referer" to "https://drive.quark.cn/")
        val okHttpClient = OkHttpClient.Builder().connectTimeout(60, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
        val dataSourceFactory = ParallelDownloadDataSource.Factory(okHttpClient, headers, downloadThreads)

        val mediaItem = MediaItem.Builder().setUri(url).setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build()
        val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        
        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { 
                isPlaying = playing
                if (isInPip.value) {
                    isInPip.value = activity?.isInPictureInPictureMode == true
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
                if (state == Player.STATE_READY) {
                    duration = exoPlayer.duration.coerceAtLeast(0L)
                    
                    if (!hasRestoredProgress) {
                        hasRestoredProgress = true
                        val savedPos = viewModel.getVideoProgress(currentFile.fid)
                        if (savedPos > 0L && savedPos < duration) {
                            exoPlayer.seekTo(savedPos)
                            currentPosition = savedPos
                            if (savedPos >= introSeconds * 1000L) {
                                hasAutoSkippedIntro = true
                            }
                        }
                    }
                    
                    if (introSeconds > 0 && !hasAutoSkippedIntro && exoPlayer.currentPosition < introSeconds * 1000L) {
                        exoPlayer.seekTo(introSeconds * 1000L)
                        currentPosition = introSeconds * 1000L
                        hasAutoSkippedIntro = true
                        Toast.makeText(context, "已自动跳过片头", Toast.LENGTH_SHORT).show()
                    }

                } else if (state == Player.STATE_ENDED) {
                    val idx = series.videos.indexOfFirst { it.fid == currentFile.fid }
                    if (idx != -1 && idx < series.videos.size - 1) viewModel.playVideo(context, series, series.videos[idx + 1])
                }
            }
            override fun onTracksChanged(tracks: Tracks) {
                val audioList = mutableListOf<Tracks.Group>()
                val textList = mutableListOf<Tracks.Group>()
                for (group in tracks.groups) {
                    if (group.type == androidx.media3.common.C.TRACK_TYPE_AUDIO) audioList.add(group)
                    if (group.type == androidx.media3.common.C.TRACK_TYPE_TEXT) textList.add(group)
                }
                audioTracks = audioList
                textTracks = textList
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) resolution = "${videoSize.width}×${videoSize.height}"
            }
            override fun onPlayerError(error: PlaybackException) {
                val sb = StringBuilder().append("错误码: ${error.errorCodeName}\n")
                var cause: Throwable? = error
                var depth = 0
                while (cause != null) {
                    sb.append("\n[第 ${depth} 层] ${cause.javaClass.simpleName}\n")
                    if (cause is HttpDataSource.InvalidResponseCodeException) {
                        sb.append("HTTP状态码: ${cause.responseCode}\n")
                        cause.responseBody?.let { sb.append("响应体: ${String(it)}\n") }
                    }
                    sb.append("信息: ${cause.message ?: "无"}\n")
                    cause = cause.cause
                    depth++
                }
                errorDialogMsg = sb.toString()
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            if (exoPlayer.currentPosition > 0) viewModel.saveVideoProgress(currentFile.fid, exoPlayer.currentPosition)
            exoPlayer.removeListener(listener)
            exoPlayer.stop()
            exoPlayer.release()
        }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            if (!isDraggingSeekBar) {
                currentPosition = exoPlayer.currentPosition
                bufferedPosition = exoPlayer.bufferedPosition
            }

            if (outroSeconds > 0 && duration > 0 && outroSeconds * 1000L < duration && currentPosition >= (duration - outroSeconds * 1000L)) {
                if (!hasTriggeredAutoPlay) {
                    hasTriggeredAutoPlay = true
                    val idx = series.videos.indexOfFirst { it.fid == currentFile.fid }
                    if (idx != -1 && idx < series.videos.size - 1) {
                        viewModel.playVideo(context, series, series.videos[idx + 1])
                    }
                }
            }
            delay(1000)
        }
    }

    LaunchedEffect(showEpisodeDrawer) {
        if (showEpisodeDrawer) {
            val idx = series.videos.indexOfFirst { it.fid == currentFile.fid }
            if (idx != -1) {
                episodeListState.animateScrollToItem(idx)
            }
        }
    }

    LaunchedEffect(showControls, isPlaying, gestureType, showSettingsPanel, showEpisodeDrawer) {
        if (showControls && isPlaying && !isLocked && gestureType.isEmpty() && !showSettingsPanel && !showEpisodeDrawer && !isInPip.value) {
            delay(3000); showControls = false
        }
    }

    LaunchedEffect(isLocked, showUnlockIcon) {
        if (isLocked && showUnlockIcon) { delay(3000); showUnlockIcon = false }
    }

    // 双击快进/快退提示自动消失
    LaunchedEffect(seekMessage) {
        if (seekMessage != null) {
            delay(1500)
            seekMessage = null
        }
    }

    BackHandler {
        if (isLocked) { showUnlockIcon = true }
        else if (showSettingsPanel) showSettingsPanel = false
        else if (showEpisodeDrawer) showEpisodeDrawer = false
        else onBack()
    }

    if (errorDialogMsg != null) {
        AlertDialog(
            onDismissRequest = { errorDialogMsg = null },
            title = { AppText("播放失败详情", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    SelectionContainer {
                        Text(
                            text = errorDialogMsg ?: "",
                            modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                            fontSize = 12.sp,
                            color = Color.DarkGray
                        )
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("播放错误", errorDialogMsg))
                        Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    }) { AppText("复制全部", color = MaterialTheme.colorScheme.primary) }
                    TextButton(onClick = { errorDialogMsg = null }) { AppText("关闭") }
                }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(isLocked, isInPip.value) {
                if (isLocked || isInPip.value) {
                    if (isLocked && !isInPip.value) {
                        detectTapGestures(onTap = { showUnlockIcon = true })
                    }
                    return@pointerInput
                }
                
                var gestureMode = "" 
                var startX = 0f
                var startY = 0f
                var startPos = 0L
                var startBrightness = 0f
                var startVolume = 0
                
                detectDragGestures(
                    onDragStart = { offset ->
                        gestureMode = ""
                        startX = offset.x
                        startY = offset.y
                        startPos = exoPlayer.currentPosition
                        startBrightness = activity?.window?.attributes?.screenBrightness?.let { if (it < 0) 0.5f else it } ?: 0.5f
                        startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    },
                    onDrag = { change, _ ->
                        
                        val deltaX = change.position.x - startX
                        val deltaY = change.position.y - startY
                        val absX = abs(deltaX)
                        val absY = abs(deltaY)
                        
                        if (gestureMode.isEmpty()) {
                            if (absX > 15f || absY > 15f) {
                                if (absX > absY) {
                                    gestureMode = "seek"
                                    isDraggingSeekBar = true
                                    dragStartPosition = startPos 
                                    change.consume()
                                } else {
                                    if (startX < size.width / 3f) {
                                        gestureMode = "brightness"
                                        gestureType = "brightness"
                                        gestureValue = startBrightness
                                        change.consume()
                                    } else if (startX > size.width * 2f / 3f) {
                                        gestureMode = "volume"
                                        gestureType = "volume"
                                        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                        gestureValue = startVolume.toFloat() / maxVol
                                        change.consume()
                                    } else {
                                        gestureMode = "none"
                                        change.consume()
                                    }
                                }
                            }
                        } else {
                            change.consume()
                        }
                        
                        when (gestureMode) {
                            "seek" -> {
                                val timeDeltaMs = ((deltaX / size.width) * 300 * 1000).toLong()
                                seekDeltaMillis = timeDeltaMs
                                val target = (startPos + timeDeltaMs).coerceIn(0L, if (duration > 0) duration else Long.MAX_VALUE)
                                currentPosition = target
                            }
                            "brightness" -> {
                                gestureValue = (startBrightness - (deltaY / size.height)).coerceIn(0f, 1f)
                                activity?.window?.let { val params = it.attributes; params.screenBrightness = gestureValue; it.attributes = params }
                            }
                            "volume" -> {
                                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                val newVolFloat = (startVolume.toFloat() / maxVol) - (deltaY / size.height)
                                gestureValue = newVolFloat.coerceIn(0f, 1f)
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (gestureValue * maxVol).roundToInt(), 0)
                            }
                        }
                    },
                    onDragEnd = { 
                        if (gestureMode == "seek") {
                            // 只有移动距离超过1秒才真正加载
                            if (abs(currentPosition - dragStartPosition) > 1000L) {
                                exoPlayer.seekTo(currentPosition)
                                if (isPlaying) exoPlayer.play()
                            } else {
                                currentPosition = dragStartPosition
                            }
                        }
                        gestureMode = "" 
                        gestureType = "" 
                        isDraggingSeekBar = false 
                    },
                    onDragCancel = { 
                        if (gestureMode == "seek") {
                            currentPosition = dragStartPosition
                        }
                        gestureMode = "" 
                        gestureType = ""
                        isDraggingSeekBar = false 
                    }
                )
            }
            .pointerInput(isLocked, showControls, showSettingsPanel, showEpisodeDrawer, isInPip.value) {
                if (isLocked || showSettingsPanel || showEpisodeDrawer || isInPip.value) return@pointerInput
                detectTapGestures(
                    onTap = { showControls = !showControls },
                    onDoubleTap = { offset ->
                        when {
                            offset.x < size.width / 3 -> {
                                exoPlayer.seekTo((exoPlayer.currentPosition - 10000).coerceAtLeast(0))
                                seekMessage = "已快退 10 秒"
                            }
                            offset.x > size.width * 2 / 3 -> {
                                exoPlayer.seekTo((exoPlayer.currentPosition + 10000).coerceAtMost(if (duration > 0) duration else Long.MAX_VALUE))
                                seekMessage = "已快进 10 秒"
                            }
                            else -> { if (isPlaying) exoPlayer.pause() else exoPlayer.play() }
                        }
                    },
                    onLongPress = {
                        isLongPressing = true
                        exoPlayer.setPlaybackSpeed(2.0f)
                    },
                    onPress = {
                        tryAwaitRelease()
                        isLongPressing = false
                        exoPlayer.setPlaybackSpeed(playbackSpeed)
                    }
                )
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                view.useController = false
            }
        )

        if (isInPip.value) {
            Box(modifier = Modifier.fillMaxSize()) {
                // 系统级按钮，不显示 Compose UI
            }
            return@Box
        }

        if (isBuffering || duration == 0L) {
            Box(
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(36.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    AnimatedLoadingDots(color = Color.White, fontSize = 14.sp)
                }
            }
        }

        if (gestureType.isNotEmpty()) {
            Box(modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp)).padding(16.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = if (gestureType == "volume") {
                            if (gestureValue == 0f) Icons.Default.VolumeMute else Icons.Default.VolumeUp
                        } else {
                            Icons.Default.BrightnessHigh
                        },
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { gestureValue }, modifier = Modifier.width(150.dp), color = Color.White, trackColor = Color.DarkGray)
                    Spacer(modifier = Modifier.height(8.dp))
                    AppText(text = "${(gestureValue * 100).roundToInt()}%", color = Color.White, fontSize = 14.sp)
                }
            }
        }

        if (isDraggingSeekBar) {
            val deltaSec = (currentPosition - dragStartPosition) / 1000
            val deltaText = if (deltaSec >= 0) "+${formatDeltaTime(deltaSec)}" else "-${formatDeltaTime(abs(deltaSec))}"
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AppText(
                        text = "${formatSmartTime(currentPosition)} / ${formatSmartTime(duration)}",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    AppText(
                        text = deltaText,
                        color = Color.LightGray,
                        fontSize = 16.sp
                    )
                }
            }
        }

        if (isLocked) {
            if (showUnlockIcon) {
                IconButton(onClick = { isLocked = false; showControls = true }, modifier = Modifier.align(Alignment.CenterStart).padding(32.dp)) {
                    Icon(Icons.Default.Lock, contentDescription = "解锁", tint = Color.White, modifier = Modifier.size(24.dp))
                }
            }
            return@Box
        }

        AnimatedVisibility(visible = showControls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {

                Column(modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(30.dp)) {
                    Surface(color = Color.White.copy(alpha = 0.1f), shape = CircleShape, border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))) {
                        IconButton(onClick = {
                            lastManualSwitchTime[0] = System.currentTimeMillis()
                            if (isLandscape) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            else activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        }) {
                            Icon(Icons.Default.ScreenRotation, null, tint = Color.White)
                        }
                    }
                }

                Column(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(30.dp)) {
                    Surface(color = Color.White.copy(alpha = 0.1f), shape = CircleShape, border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))) {
                        IconButton(onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                try {
                                    val timeStamp = System.currentTimeMillis()
                                    
                                    val prevBitmap = createPipIcon("prev", context)
                                    val playPauseBitmap = createPipIcon(if (isPlaying) "pause" else "play", context)
                                    val nextBitmap = createPipIcon("next", context)

                                    val prevIntent = Intent("com.mie.reading.ACTION_PIP_PREV").setPackage(context.packageName).setData(Uri.parse("pip://prev$timeStamp"))
                                    val prevPendingIntent = PendingIntent.getBroadcast(context, 1, prevIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                                    val prevAction = RemoteAction(Icon.createWithBitmap(prevBitmap), "上一集", "上一集", prevPendingIntent)

                                    val playPauseIntent = Intent("com.mie.reading.ACTION_PIP_PLAY_PAUSE").setPackage(context.packageName).setData(Uri.parse("pip://play$timeStamp"))
                                    val playPauseRequestCode = if (isPlaying) 2 else 3
                                    val playPausePendingIntent = PendingIntent.getBroadcast(context, playPauseRequestCode, playPauseIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                                    val playPauseAction = RemoteAction(
                                        Icon.createWithBitmap(playPauseBitmap),
                                        if (isPlaying) "暂停" else "播放",
                                        if (isPlaying) "暂停" else "播放",
                                        playPausePendingIntent
                                    )

                                    val nextIntent = Intent("com.mie.reading.ACTION_PIP_NEXT").setPackage(context.packageName).setData(Uri.parse("pip://next$timeStamp"))
                                    val nextPendingIntent = PendingIntent.getBroadcast(context, 4, nextIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                                    val nextAction = RemoteAction(Icon.createWithBitmap(nextBitmap), "下一集", "下一集", nextPendingIntent)

                                    val params = PictureInPictureParams.Builder()
                                        .setAspectRatio(Rational(16, 9))
                                        .setActions(listOf(prevAction, playPauseAction, nextAction))
                                        .build()
                                    val success = activity?.enterPictureInPictureMode(params) ?: false
                                    if (success) {
                                        isInPip.value = true 
                                    }
                                } catch (e: Exception) {
                                    Toast.makeText(context, "设备不支持画中画", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(context, "Android 8.0 及以上才支持画中画", Toast.LENGTH_SHORT).show()
                            }
                        }) { Icon(Icons.Default.PictureInPicture, null, tint = Color.White) }
                    }

                    Surface(color = Color.White.copy(alpha = 0.1f), shape = CircleShape, border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))) {
                        IconButton(onClick = { isLocked = true; showControls = false; showUnlockIcon = true }) { Icon(Icons.Default.Lock, null, tint = Color.White) }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0xAA000000), Color.Transparent)))
                        .then(if (!isLandscape) Modifier.statusBarsPadding() else Modifier)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }

                    AppText(
                        text = title,
                        color = Color.White,
                        maxLines = 1,
                        modifier = Modifier.weight(1f).padding(start = 8.dp, end = 16.dp).basicMarquee()
                    )

                    // 网速和时间位置互换
                    if (isLandscape) {
                        AppText(text = networkSpeed, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(end = 12.dp))
                        AppText(text = currentSystemTime, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(end = 12.dp))
                    }
                    if (resolution.isNotBlank()) {
                        AppText(text = resolution, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(end = 16.dp))
                    }

                    IconButton(onClick = { showSettingsPanel = true }) { Icon(Icons.Default.Settings, contentDescription = "设置", tint = Color.White) }
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 12.dp)
                        .widthIn(max = if (isLandscape) 600.dp else 360.dp)
                        .fillMaxWidth(0.9f)
                        .background(Color(0xB31A1A1A), RoundedCornerShape(16.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppText(text = formatSmartTime(currentPosition), color = Color.White, fontSize = 12.sp)
                            
                            var sliderWidth by remember { mutableStateOf(1f) }
                            val bufferedFraction = if (duration > 0) bufferedPosition.toFloat() / duration else 0f
                            val currentFraction = if (duration > 0) currentPosition.toFloat() / duration else 0f

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp)
                                    .height(32.dp) 
                                    .onSizeChanged { sliderWidth = it.width.toFloat() }
                                    .pointerInput(duration) {
                                        detectTapGestures(
                                            onPress = { offset ->
                                                if (duration > 0) {
                                                    isDraggingSeekBar = true
                                                    dragStartPosition = exoPlayer.currentPosition
                                                    val target = (offset.x / sliderWidth).coerceIn(0f, 1f)
                                                    currentPosition = (target * duration).toLong()
                                                    seekDeltaMillis = currentPosition - dragStartPosition
                                                }
                                            },
                                            onTap = { offset ->
                                                if (duration > 0) {
                                                    val target = (offset.x / sliderWidth).coerceIn(0f, 1f)
                                                    val finalPos = (target * duration).toLong()
                                                    if (abs(finalPos - exoPlayer.currentPosition) > 1000L) {
                                                        exoPlayer.seekTo(finalPos)
                                                    }
                                                    isDraggingSeekBar = false
                                                }
                                            }
                                        )
                                    }
                                    .pointerInput(duration) {
                                        detectDragGestures(
                                            onDragStart = { offset ->
                                                if (duration > 0) {
                                                    isDraggingSeekBar = true
                                                    dragStartPosition = exoPlayer.currentPosition
                                                    val target = (offset.x / sliderWidth).coerceIn(0f, 1f)
                                                    currentPosition = (target * duration).toLong()
                                                    seekDeltaMillis = currentPosition - dragStartPosition
                                                }
                                            },
                                            onDrag = { change, _ ->
                                                change.consume()
                                                if (duration > 0) {
                                                    val target = (change.position.x / sliderWidth).coerceIn(0f, 1f)
                                                    currentPosition = (target * duration).toLong()
                                                    seekDeltaMillis = currentPosition - dragStartPosition
                                                }
                                            },
                                            onDragEnd = {
                                                isDraggingSeekBar = false
                                                if (abs(currentPosition - dragStartPosition) > 1000L) {
                                                    exoPlayer.seekTo(currentPosition)
                                                    if (isPlaying) exoPlayer.play()
                                                } else {
                                                    currentPosition = dragStartPosition
                                                }
                                            },
                                            onDragCancel = { isDraggingSeekBar = false }
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Canvas(modifier = Modifier.fillMaxWidth().height(12.dp)) {
                                    val progressY = size.height / 2f
                                    val trackHeight = 3.dp.toPx()
                                    val thumbRadius = 6.dp.toPx()
                                    val maxWidth = size.width - thumbRadius * 2
                                    
                                    drawLine(color = Color.White.copy(alpha = 0.2f), start = Offset(thumbRadius, progressY), end = Offset(size.width - thumbRadius, progressY), strokeWidth = trackHeight, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                                    if (bufferedFraction > 0) {
                                        drawLine(color = Color.White.copy(alpha = 0.5f), start = Offset(thumbRadius, progressY), end = Offset(thumbRadius + maxWidth * bufferedFraction.coerceIn(0f, 1f), progressY), strokeWidth = trackHeight, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                                    }
                                    if (currentFraction > 0) {
                                        drawLine(color = Color.White, start = Offset(thumbRadius, progressY), end = Offset(thumbRadius + maxWidth * currentFraction.coerceIn(0f, 1f), progressY), strokeWidth = trackHeight, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                                    }
                                    val circleX = thumbRadius + maxWidth * currentFraction.coerceIn(0f, 1f)
                                    drawCircle(color = Color.White, radius = thumbRadius, center = Offset(circleX, progressY))
                                }
                            }
                            
                            AppText(text = formatSmartTime(duration), color = Color.White, fontSize = 12.sp)
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${playbackSpeed}x", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable {
                                    val nextSpeed = when(playbackSpeed) {
                                        0.5f -> 0.75f; 0.75f -> 1.0f; 1.0f -> 1.25f; 1.25f -> 1.5f; 1.5f -> 2.0f; 2.0f -> 3.0f; else -> 0.5f
                                    }
                                    playbackSpeed = nextSpeed
                                    exoPlayer.setPlaybackSpeed(nextSpeed)
                                }.padding(8.dp)
                            )

                            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {

                                if (isLandscape) {
                                    AppText(
                                        text = if (introSeconds > 0) formatIntroOutro(introSeconds) else "片头",
                                        color = Color.White, fontSize = 12.sp,
                                        modifier = Modifier.combinedClickable(
                                            onClick = {
                                                introSeconds = (currentPosition / 1000).toInt()
                                                viewModel.saveIntroOutro(series.title, season, introSeconds, outroSeconds)
                                                Toast.makeText(context, "片头已设为 ${formatIntroOutro(introSeconds)}", Toast.LENGTH_SHORT).show()
                                            },
                                            onLongClick = {
                                                introSeconds = 0
                                                viewModel.saveIntroOutro(series.title, season, introSeconds, outroSeconds)
                                                Toast.makeText(context, "已清空片头", Toast.LENGTH_SHORT).show()
                                            }
                                        ).padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }

                                IconButton(onClick = {
                                    val idx = series.videos.indexOfFirst { it.fid == currentFile.fid }
                                    if (idx > 0) viewModel.playVideo(context, series, series.videos[idx - 1])
                                }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(24.dp)) }

                                IconButton(onClick = { if (isPlaying) exoPlayer.pause() else exoPlayer.play() }, modifier = Modifier.size(40.dp)) {
                                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(28.dp))
                                }

                                IconButton(onClick = {
                                    val idx = series.videos.indexOfFirst { it.fid == currentFile.fid }
                                    if (idx != -1 && idx < series.videos.size - 1) viewModel.playVideo(context, series, series.videos[idx + 1])
                                }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.SkipNext, null, tint = Color.White, modifier = Modifier.size(24.dp)) }

                                if (isLandscape) {
                                    AppText(
                                        text = if (outroSeconds > 0) formatIntroOutro(outroSeconds) else "片尾",
                                        color = Color.White, fontSize = 12.sp,
                                        modifier = Modifier.combinedClickable(
                                            onClick = {
                                                val remain = duration - currentPosition
                                                if (remain > 0) {
                                                    outroSeconds = (remain / 1000).toInt()
                                                    viewModel.saveIntroOutro(series.title, season, introSeconds, outroSeconds)
                                                    Toast.makeText(context, "片尾已设为剩余 ${formatIntroOutro(outroSeconds)}", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            onLongClick = {
                                                outroSeconds = 0
                                                viewModel.saveIntroOutro(series.title, season, introSeconds, outroSeconds)
                                                Toast.makeText(context, "已清空片尾", Toast.LENGTH_SHORT).show()
                                            }
                                        ).padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            if (isLandscape) {
                                AppText(text = "选集", color = Color.White, modifier = Modifier.clickable { showEpisodeDrawer = true }.padding(8.dp))
                            } else {
                                Spacer(modifier = Modifier.width(32.dp))
                            }
                        }
                    }
                }
            }
        }

        // 双击快退/快进提示
        AnimatedVisibility(
            visible = seekMessage != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = if (isLandscape) 40.dp else 60.dp)
        ) {
            Box(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) { AppText(text = seekMessage ?: "", color = Color.White, fontSize = 16.sp) }
        }

        // 2.0快进中位置往上调
        AnimatedVisibility(
            visible = isLongPressing,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = if (isLandscape) 40.dp else 60.dp)
        ) {
            Box(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) { AppText(text = "2.0x快进中", color = Color.White, fontSize = 16.sp) }
        }

        if (showEpisodeDrawer) {
            Box(modifier = Modifier.fillMaxSize().clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { showEpisodeDrawer = false })
            AnimatedVisibility(
                visible = showEpisodeDrawer,
                enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300)),
                exit = slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(280.dp)
            ) {
                Surface(color = Color(0xEE1A1A1A), modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            AppText("选集", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            IconButton(onClick = { showEpisodeDrawer = false }) { Icon(Icons.Default.Close, null, tint = Color.White) }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        LazyColumn(state = episodeListState) {
                            items(series.videos) { file ->
                                val e = file.parsedInfo?.episode
                                val s = file.parsedInfo?.season ?: 1
                                val tmdbTitle = if (e != null) series.seasonEpisodeTitles["${s}_${e}"] ?: series.episodeTitles[e] else null
                                
                                val label = if (e != null && series.category != "电影" && series.videos.size > 1) {
                                    "第${e}集" + if (!tmdbTitle.isNullOrBlank()) " $tmdbTitle" else ""
                                } else {
                                    if (series.videos.size == 1) series.title else (file.folderPath.lastOrNull() ?: file.name.substringBeforeLast("."))
                                }
                                
                                val isCurrent = file.fid == currentFile.fid
                                AppText(
                                    text = label,
                                    color = if (isCurrent) Color.White else Color.Gray,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 15.sp,
                                    modifier = Modifier.fillMaxWidth().background(if (isCurrent) Color(0x33FFFFFF) else Color.Transparent, RoundedCornerShape(8.dp)).clickable {
                                        viewModel.playVideo(context, series, file)
                                        showEpisodeDrawer = false
                                    }.padding(vertical = 12.dp, horizontal = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showSettingsPanel) {
            Box(modifier = Modifier.fillMaxSize().clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { showSettingsPanel = false })
            AnimatedVisibility(
                visible = showSettingsPanel,
                enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300)),
                exit = slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(280.dp)
            ) {
                Surface(color = Color(0xEE1A1A1A), modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            AppText("播放设置", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            IconButton(onClick = { showSettingsPanel = false }) { Icon(Icons.Default.Close, null, tint = Color.White) }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().background(Color(0xFF333333), RoundedCornerShape(8.dp))
                                .clickable { showIntroOutroDialog = true; showSettingsPanel = false }.padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                AppText("设置片头片尾", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(4.dp))
                                AppText("片头: ${formatIntroOutro(introSeconds)}  片尾: ${formatIntroOutro(outroSeconds)}", color = Color.Gray, fontSize = 12.sp)
                            }
                            Icon(Icons.Default.KeyboardArrowRight, null, tint = Color.Gray)
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        AppText("音频轨道", color = Color.Gray, fontSize = 14.sp)
                        audioTracks.forEachIndexed { index, trackGroup ->
                            val format = trackGroup.getTrackFormat(0)
                            val label = format.label ?: format.language ?: "音轨 ${index + 1}"
                            val isSelected = remember(trackSelectionVersion, audioTracks) { trackGroup.isTrackSelected(0) }
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                                        .setOverrideForType(TrackSelectionOverride(trackGroup.mediaTrackGroup, 0)).build()
                                    trackSelectionVersion++
                                    Toast.makeText(context, "已切换至 $label", Toast.LENGTH_SHORT).show()
                                }.padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AppText(label, color = if (isSelected) Color.White else Color.Gray, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        AppText("字幕轨道", color = Color.Gray, fontSize = 14.sp)
                        textTracks.forEachIndexed { index, trackGroup ->
                            val format = trackGroup.getTrackFormat(0)
                            val label = format.label ?: format.language ?: "字幕 ${index + 1}"
                            val isSelected = remember(trackSelectionVersion, textTracks) { trackGroup.isTrackSelected(0) }
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                                        .setOverrideForType(TrackSelectionOverride(trackGroup.mediaTrackGroup, 0)).build()
                                    trackSelectionVersion++
                                    Toast.makeText(context, "已切换至 $label", Toast.LENGTH_SHORT).show()
                                }.padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AppText(label, color = if (isSelected) Color.White else Color.Gray, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showIntroOutroDialog) {
        var inputIntro by remember { mutableFloatStateOf(introSeconds.toFloat()) }
        var inputOutro by remember { mutableFloatStateOf(outroSeconds.toFloat()) }
        Dialog(onDismissRequest = { showIntroOutroDialog = false }) {
            Column(modifier = Modifier.background(Color(0xFF222222), RoundedCornerShape(8.dp)).padding(16.dp).fillMaxWidth()) {
                AppText("设置片头片尾", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(16.dp))
                AppText("片头时长: ${formatIntroOutro(inputIntro.toInt())}", color = Color.White)
                Slider(value = inputIntro, onValueChange = { inputIntro = it }, valueRange = 0f..300f)
                Spacer(modifier = Modifier.height(8.dp))
                AppText("片尾时长: ${formatIntroOutro(inputOutro.toInt())}", color = Color.White)
                Slider(value = inputOutro, onValueChange = { inputOutro = it }, valueRange = 0f..300f)
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        viewModel.saveIntroOutro(series.title, season, inputIntro.toInt(), inputOutro.toInt())
                        introSeconds = inputIntro.toInt()
                        outroSeconds = inputOutro.toInt()
                        showIntroOutroDialog = false
                    },
                    modifier = Modifier.align(Alignment.End)
                ) { AppText("保存设置") }
            }
        }
    }
}

// ==========================================
// 辅助工具
// ==========================================
@Composable
fun AnimatedLoadingDots(color: Color, fontSize: androidx.compose.ui.unit.TextUnit, text: String = "正在加载中") {
    val transition = rememberInfiniteTransition(label = "loadingDots")
    
    val textStyle = TextStyle(
        color = color,
        fontSize = fontSize,
        shadow = if (color == Color.Black) null else Shadow(
            color = Color.Black.copy(alpha = 0.8f),
            offset = Offset(1f, 1f),
            blurRadius = 4f
        )
    )

    val dot1Y by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1200
                0f at 0 using LinearEasing
                1f at 300 using LinearEasing
                0f at 600 using LinearEasing
                0f at 1200 using LinearEasing
            }
        ), label = "dot1"
    )
    val dot2Y by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1200
                0f at 0 using LinearEasing
                0f at 100 using LinearEasing
                1f at 400 using LinearEasing
                0f at 700 using LinearEasing
                0f at 1200 using LinearEasing
            }
        ), label = "dot2"
    )
    val dot3Y by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1200
                0f at 0 using LinearEasing
                0f at 200 using LinearEasing
                1f at 500 using LinearEasing
                0f at 800 using LinearEasing
                0f at 1200 using LinearEasing
            }
        ), label = "dot3"
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        AppText(text, color = color, fontSize = fontSize, style = textStyle)
        Box(modifier = Modifier.width(20.dp).padding(start = 2.dp)) {
            Row {
                AppText(".", color = color, fontSize = fontSize, style = textStyle, modifier = Modifier.offset(y = (-4 * dot1Y).dp))
                AppText(".", color = color, fontSize = fontSize, style = textStyle, modifier = Modifier.offset(y = (-4 * dot2Y).dp))
                AppText(".", color = color, fontSize = fontSize, style = textStyle, modifier = Modifier.offset(y = (-4 * dot3Y).dp))
            }
        }
    }
}

private fun formatSmartTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) String.format("%02d:%02d:%02d", hours, minutes, seconds)
    else String.format("%02d:%02d", minutes, seconds)
}

private fun formatDeltaTime(seconds: Long): String {
    if (seconds <= 0) return "0s"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return buildString {
        if (h > 0) append("${h}h")
        if (m > 0) append("${m}m")
        if (s > 0 || (h == 0L && m == 0L)) append("${s}s")
    }
}

private fun formatIntroOutro(seconds: Int): String {
    if (seconds <= 0) return "0秒"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return buildString {
        if (h > 0) append("${h}小时")
        if (m > 0) append("${m}分")
        if (s > 0 || (h == 0 && m == 0)) append("${s}秒")
    }
}

private fun Context.findActivity(): Activity? {
    var context = this
    while (context is android.content.ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SeriesPosterCard(
    series: MediaSeries, 
    isScraping: Boolean = false,
    onClick: () -> Unit, 
    onLongClick: () -> Unit
) {
    Column(modifier = Modifier.width(110.dp).combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) {
            if (series.posterUrl != null) {
                AsyncImage(model = series.posterUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                if (isScraping) {
                    Box(modifier = Modifier.fillMaxSize().background(Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.Gray, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                } else {
                    AppText("长按修正", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (series.rating != null) {
                AppText(text = series.rating, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, style = TextStyle(shadow = Shadow(color = Color.Black, offset = Offset(1f, 1f), blurRadius = 3f)), modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp))
            }
        }

        AppText(
            text = series.title,
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp).basicMarquee(),
            style = MaterialTheme.typography.bodyMedium
        )

        val seasons = series.videos.mapNotNull { it.parsedInfo?.season }.distinct().size
        if (series.videos.size == 1 || series.category == "电影") {
            AppText(text = series.year?.toString() ?: "电影", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        } else if (seasons > 1) {
            AppText(text = "共 $seasons 季", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        } else {
            AppText(text = "共 1 季", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaDetailScreen(
    series: MediaSeries, 
    allSeries: List<MediaSeries>,
    onBack: () -> Unit, 
    onPlay: (MediaSeries, QuarkFile) -> Unit, 
    onLongClickPoster: (MediaSeries) -> Unit,
    onSelectSeries: (MediaSeries) -> Unit
) {
    val viewModel: VideoScraperViewModel = koinViewModel()

    val relatedSeries = remember(series, allSeries) {
        if (series.groupName.isNullOrBlank()) emptyList()
        else allSeries.filter { it.groupName == series.groupName && it.id != series.id }
    }

    val currentSeasonVideos = remember(series) {
        series.videos.sortedWith(compareBy<QuarkFile> { it.parsedInfo?.season ?: 1 }.thenBy { it.parsedInfo?.episode ?: 0 }.thenBy { it.name })
    }

    val latestWatchedInCurrentSeason = currentSeasonVideos.maxByOrNull { viewModel.getVideoProgress(it.fid) }
    val hasProgressInCurrentSeason = latestWatchedInCurrentSeason != null && viewModel.getVideoProgress(latestWatchedInCurrentSeason.fid) > 0

    val targetPlayFile = if (hasProgressInCurrentSeason && latestWatchedInCurrentSeason != null) {
        latestWatchedInCurrentSeason
    } else {
        currentSeasonVideos.firstOrNull()
    }

    val playBtnText = if (targetPlayFile != null && viewModel.getVideoProgress(targetPlayFile.fid) > 0) {
        val ep = targetPlayFile.parsedInfo?.episode
        if (ep != null && series.category != "电影" && series.videos.size > 1) "继续播放 第${ep}集" else "继续播放"
    } else {
        "播放"
    }

    LazyColumn(modifier = Modifier.fillMaxSize().background(Color.White)) {
        item {
            Box(modifier = Modifier.fillMaxWidth().height(350.dp)) {
                if (series.posterUrl != null) {
                    AsyncImage(model = series.posterUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
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
                    if (series.rating != null) {
                        Icon(Icons.Default.Star, null, tint = Color(0xFFFFC107), modifier = Modifier.size(16.dp))
                        AppText(text = " ${series.rating} ", style = MaterialTheme.typography.bodyMedium)
                        AppText(text = " | ", color = Color.Gray)
                    }
                    AppText(text = "${series.year ?: ""}", style = MaterialTheme.typography.bodyMedium)
                    AppText(text = " | ${series.category}", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                }
                Button(
                    onClick = {
                        targetPlayFile?.let { onPlay(series, it) }
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333))
                ) {
                    Icon(Icons.Default.PlayArrow, null, tint = Color.White); AppText(text = " $playBtnText", color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
                AppText(text = "剧情简介", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
                AppText(text = series.overview ?: "暂无简介", style = MaterialTheme.typography.bodyMedium, color = Color.DarkGray, modifier = Modifier.padding(top = 8.dp))
            }
        }

        item {
            AppText(
                text = "剧集/视频列表",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp)
            )
        }

        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(currentSeasonVideos) { file ->
                    val e = file.parsedInfo?.episode
                    val s = file.parsedInfo?.season ?: 1
                    val tmdbTitle = if (e != null) series.seasonEpisodeTitles["${s}_${e}"] ?: series.episodeTitles[e] else null
                    val stillUrl = if (e != null) series.seasonEpisodeStills["${s}_${e}"] ?: series.episodeStills[e] else null
                    
                    val label = if (e != null && series.category != "电影" && series.videos.size > 1) {
                        "第${e}集" + if (!tmdbTitle.isNullOrBlank()) " $tmdbTitle" else ""
                    } else {
                        if (series.videos.size == 1) series.title else (file.folderPath.lastOrNull() ?: file.name.substringBeforeLast("."))
                    }
                    
                    Column(modifier = Modifier.width(160.dp).clickable { onPlay(series, file) }) {
                        Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Color.DarkGray), contentAlignment = Alignment.Center) {
                            if (stillUrl != null) AsyncImage(model = stillUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            else Icon(Icons.Default.PlayArrow, null, tint = Color.White)
                        }
                        AppText(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (relatedSeries.isNotEmpty()) {
            item {
                AppText(
                    text = "系列其他影片",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp)
                )
            }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(relatedSeries) { relSeries ->
                        Column(modifier = Modifier.width(110.dp).clickable { onSelectSeries(relSeries) }) {
                            Box(modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) {
                                if (relSeries.posterUrl != null) AsyncImage(model = relSeries.posterUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                else AppText("无海报", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                                if (relSeries.rating != null) {
                                    AppText(text = relSeries.rating, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, style = TextStyle(shadow = Shadow(color = Color.Black, offset = Offset(1f, 1f), blurRadius = 3f)), modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp))
                                }
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
                series.videos.firstOrNull()?.let { firstFile ->
                    AppText(text = firstFile.name, style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(top = 8.dp))
                    val pathStr = firstFile.folderPath.joinToString("/")
                    AppText(text = "夸克网盘: /$pathStr", style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(top = 4.dp))
                    val sizeMb = firstFile.size / (1024 * 1024)
                    AppText(text = "共 ${series.videos.size} 集 | ${sizeMb}MB (单集)", style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}