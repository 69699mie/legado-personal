package io.legado.app.ui.main.video

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import io.legado.app.ui.widget.components.text.AppText
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun VideoRouteScreen(
    modifier: Modifier = Modifier,
    viewModel: VideoScraperViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context.findActivity()
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showWebLoginDialog by remember { mutableStateOf(false) }
    var showManualScrapeDialog by remember { mutableStateOf(false) }
    var manualScrapeSeries by remember { mutableStateOf<MediaSeries?>(null) }
    var viewAllCategory by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = uiState.playUrl != null || uiState.currentSeries != null || viewAllCategory != null || (!uiState.isLibraryMode && uiState.currentFid != "0")) {
        if (uiState.playUrl != null) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            viewModel.stopPlaying()
        } else if (uiState.currentSeries != null) viewModel.selectSeries(null)
        else if (viewAllCategory != null) viewAllCategory = null
        else viewModel.goBack()
    }

    if (uiState.playUrl != null && uiState.currentSeries != null && uiState.currentPlayFile != null) {
        Dialog(
            onDismissRequest = { 
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                viewModel.stopPlaying() 
            },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            InternalVideoPlayer(
                url = uiState.playUrl!!,
                title = uiState.playTitle ?: "播放中",
                cookie = uiState.cookie,
                series = uiState.currentSeries!!,
                currentFile = uiState.currentPlayFile!!,
                viewModel = viewModel,
                onBack = { 
                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    viewModel.stopPlaying() 
                }
            )
        }
    } else if (uiState.currentSeries != null) {
        MediaDetailScreen(
            series = uiState.currentSeries!!,
            onBack = { viewModel.selectSeries(null) },
            onPlay = { series, file -> viewModel.playVideo(context, series, file) },
            onLongClickPoster = { manualScrapeSeries = it; showManualScrapeDialog = true }
        )
    } else if (viewAllCategory != null) {
        val categorySeries = uiState.seriesList.filter { it.category == viewAllCategory }
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().background(Color.White)) {
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
    } else {
        Column(modifier = modifier.fillMaxSize().statusBarsPadding()) {
            Row(modifier = Modifier.fillMaxWidth().background(Color(0xFFF5F5F5)).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (uiState.currentFid != "0" && !uiState.isLibraryMode) {
                    IconButton(onClick = { viewModel.goBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                }
                TabRow(
                    selectedTabIndex = if (uiState.isLibraryMode) 1 else 0,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Tab(selected = !uiState.isLibraryMode, onClick = { viewModel.setMode(false) }, text = { AppText("网盘浏览") })
                    Tab(selected = uiState.isLibraryMode, onClick = { viewModel.setMode(true) }, text = { AppText("影视库") })
                }
                IconButton(onClick = { showSettingsDialog = true }) { Icon(Icons.Default.Settings, contentDescription = "设置") }
            }

            if (uiState.errorMsg != null && !uiState.isLoading) {
                Box(modifier = Modifier.fillMaxWidth().background(Color(0xFFFFCDD2)).padding(8.dp), contentAlignment = Alignment.Center) {
                    AppText(uiState.errorMsg!!, color = Color.Red, style = MaterialTheme.typography.bodySmall)
                }
            }

            if (uiState.isLoading && uiState.seriesList.isEmpty() && uiState.fileList.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (!uiState.isLibraryMode) {
                if (uiState.fileList.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { AppText("当前文件夹为空") }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(uiState.fileList) { file ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable(enabled = file.isFolder) { if (file.isFolder) viewModel.fetchQuarkFiles(file.fid) }.padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(if (file.isFolder) Icons.Default.Folder else Icons.Default.Movie, null, tint = if (file.isFolder) Color(0xFFFFB300) else Color.Gray)
                                Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) { AppText(text = file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                if (file.isFolder) {
                                    IconButton(onClick = { viewModel.toggleLibrary(file.fid, file.name) }) {
                                        Icon(if (uiState.selectedLibraryFids.contains(file.fid)) Icons.Default.Star else Icons.Outlined.StarBorder, "加入影视库",
                                            tint = if (uiState.selectedLibraryFids.contains(file.fid)) Color(0xFFFFB300) else Color.Gray)
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                PullToRefreshBox(isRefreshing = uiState.isLoading, onRefresh = { viewModel.loadLibraryVideos() }, modifier = Modifier.fillMaxSize()) {
                    if (uiState.selectedLibraryFids.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { AppText("请先在网盘浏览中勾选媒体文件夹") }
                    } else if (uiState.seriesList.isEmpty() && !uiState.isLoading) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { AppText("没找到任何视频，下拉可刷新") }
                    } else {
                        val grouped = uiState.seriesList.groupBy { it.category }
                        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                            grouped.forEach { (categoryName, seriesList) ->
                                if (seriesList.isNotEmpty()) {
                                    item {
                                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            AppText(text = categoryName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                            AppText(text = "全部 ${seriesList.size} 部 >", style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.clickable { viewAllCategory = categoryName })
                                        }
                                    }
                                    item {
                                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            items(seriesList) { series ->
                                                SeriesPosterCard(series = series, onClick = { viewModel.selectSeries(series) }, onLongClick = { manualScrapeSeries = series; showManualScrapeDialog = true })
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

    if (showSettingsDialog) {
        var inputCookie by remember { mutableStateOf(uiState.cookie) }
        var inputTmdbKey by remember { mutableStateOf(uiState.tmdbApiKey) }
        var inputTmdbUrl by remember { mutableStateOf(uiState.tmdbBaseUrl) }
        Dialog(onDismissRequest = { showSettingsDialog = false }) {
            Column(modifier = Modifier.background(Color.White).padding(16.dp).fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    AppText(text = "夸克登录状态", fontWeight = FontWeight.Bold)
                    Button(onClick = { showWebLoginDialog = true }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { AppText("网页扫码登录") }
                }
                OutlinedTextField(value = inputCookie, onValueChange = { inputCookie = it }, modifier = Modifier.padding(vertical = 8.dp), visualTransformation = PasswordVisualTransformation(), maxLines = 2, label = { AppText("手动填写 Cookie（可选）") })
                AppText(text = "TMDB API Key (v3 auth)")
                OutlinedTextField(value = inputTmdbKey, onValueChange = { inputTmdbKey = it }, modifier = Modifier.padding(vertical = 8.dp), visualTransformation = PasswordVisualTransformation(), maxLines = 1)
                AppText(text = "TMDB API 代理地址")
                OutlinedTextField(value = inputTmdbUrl, onValueChange = { inputTmdbUrl = it }, modifier = Modifier.padding(vertical = 8.dp), maxLines = 1)
                OutlinedButton(onClick = { viewModel.saveSettings(inputCookie, inputTmdbKey, inputTmdbUrl); showSettingsDialog = false }, modifier = Modifier.align(Alignment.End)) { AppText("保存并刷新") }
            }
        }
    }

    if (showWebLoginDialog) {
        Dialog(onDismissRequest = { showWebLoginDialog = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(modifier = Modifier.fillMaxSize().background(Color.White).statusBarsPadding()) {
                WebLoginView(onLoginSuccess = { cookie -> viewModel.loginWithCapturedCookie(cookie); showWebLoginDialog = false; Toast.makeText(context, "登录成功！", Toast.LENGTH_SHORT).show() }, onCancel = { showWebLoginDialog = false })
            }
        }
    }

    if (showManualScrapeDialog && manualScrapeSeries != null) {
        var searchKeyword by remember { mutableStateOf(manualScrapeSeries!!.title) }
        var searchResults by remember { mutableStateOf<List<TmdbSearchResult>?>(null) }
        var categoryInput by remember { mutableStateOf(manualScrapeSeries!!.category) }
        Dialog(onDismissRequest = { showManualScrapeDialog = false }) {
            Column(modifier = Modifier.background(Color.White).padding(16.dp).fillMaxWidth().heightIn(max = 600.dp)) {
                AppText("手动修正刮削信息")
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    AppText("分类：", modifier = Modifier.width(50.dp))
                    OutlinedTextField(value = categoryInput, onValueChange = { categoryInput = it }, modifier = Modifier.weight(1f), placeholder = { AppText("如：动漫、电影") })
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                    AppText("名称：", modifier = Modifier.width(50.dp))
                    OutlinedTextField(value = searchKeyword, onValueChange = { searchKeyword = it }, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { viewModel.applyManualScrape(manualScrapeSeries!!.id, null, categoryInput, searchKeyword); showManualScrapeDialog = false }, modifier = Modifier.padding(start = 8.dp)) { AppText("仅保存") }
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
                            Row(modifier = Modifier.fillMaxWidth().clickable { viewModel.applyManualScrape(manualScrapeSeries!!.id, result, categoryInput, result.title); showManualScrapeDialog = false }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebLoginView(onLoginSuccess: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(Unit) { onDispose { webViewRef?.destroy(); webViewRef = null } }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().background(Color(0xFFF5F5F5)).padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onCancel) { AppText("取消", color = Color.Gray) }
            AppText("夸克扫码/账号登录", fontWeight = FontWeight.Bold)
            TextButton(onClick = {
                val cookie = CookieManager.getInstance().getCookie("https://pan.quark.cn/")
                if (!cookie.isNullOrBlank() && cookie.contains("__uid=") && cookie.contains("__puus=")) onLoginSuccess(cookie)
                else Toast.makeText(context, "尚未检测到有效登录，请完成扫码或授权", Toast.LENGTH_SHORT).show()
            }) { AppText("完成登录", color = MaterialTheme.colorScheme.primary) }
        }
        AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { ctx ->
            WebView(ctx).apply {
                webViewRef = this
                settings.javaScriptEnabled = true; settings.domStorageEnabled = true; settings.databaseEnabled = true
                settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        val cookie = CookieManager.getInstance().getCookie("https://pan.quark.cn/")
                        if (!cookie.isNullOrBlank() && cookie.contains("__uid=") && cookie.contains("__puus=")) onLoginSuccess(cookie)
                    }
                }
                loadUrl("https://pan.quark.cn/")
            }
        })
    }
}

// ==========================================
// 核心播放器组件
// ==========================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InternalVideoPlayer(
    url: String,
    title: String,
    cookie: String,
    series: MediaSeries,
    currentFile: QuarkFile,
    viewModel: VideoScraperViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // 监听横竖屏状态来隐藏/显示状态栏，绝对不去重置屏幕方向
    LaunchedEffect(isLandscape) {
        activity?.window?.let { window ->
            val controller = WindowInsetsControllerCompat(window, window.decorView)
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
            activity?.window?.let {
                val params = it.attributes
                params.screenBrightness = originalBrightness
                it.attributes = params
            }
        }
    }

    var isPlaying by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var resolution by remember { mutableStateOf("") }
    var showControls by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var showUnlockIcon by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableStateOf(1.0f) }
    
    var showSettingsPanel by remember { mutableStateOf(false) }
    var showEpisodeDrawer by remember { mutableStateOf(false) }
    var showIntroOutroDialog by remember { mutableStateOf(false) }
    
    var seekMessage by remember { mutableStateOf<String?>(null) }
    var gestureType by remember { mutableStateOf("") }
    var gestureValue by remember { mutableFloatStateOf(0f) }

    val season = currentFile.parsedInfo?.season ?: 1
    var introSeconds by remember { mutableIntStateOf(0) }
    var outroSeconds by remember { mutableIntStateOf(0) }
    
    LaunchedEffect(series.title, season) {
        val pair = viewModel.getIntroOutro(series.title, season)
        introSeconds = pair.first
        outroSeconds = pair.second
    }
    
    var showSkipIntro by remember { mutableStateOf(false) }
    var showSkipOutro by remember { mutableStateOf(false) }

    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    var hasRestoredProgress by remember { mutableStateOf(false) }

    val exoPlayer = remember {
        val cleanCookie = cookie.replace("\n", "").replace("\r", "").trim()
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("Referer" to "https://pan.quark.cn/", "Cookie" to cleanCookie, "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch"))
            .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(15000).setReadTimeoutMs(15000)
        
        val mediaItem = MediaItem.Builder().setUri(url).setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build()
        val isHls = try { Uri.parse(url).path?.contains(".m3u8") == true } catch (e: Exception) { url.contains(".m3u8") }
        val mediaSource = if (isHls) HlsMediaSource.Factory(dataSourceFactory).setAllowChunklessPreparation(true).createMediaSource(mediaItem) else ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)

        ExoPlayer.Builder(context).build().apply { setMediaSource(mediaSource); prepare(); playWhenReady = true }
    }

    LaunchedEffect(url) {
        hasRestoredProgress = false
        val cleanCookie = cookie.replace("\n", "").replace("\r", "").trim()
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("Referer" to "https://pan.quark.cn/", "Cookie" to cleanCookie, "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch"))
            .setAllowCrossProtocolRedirects(true)
        val mediaItem = MediaItem.Builder().setUri(url).setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build()
        val isHls = try { Uri.parse(url).path?.contains(".m3u8") == true } catch (e: Exception) { url.contains(".m3u8") }
        val mediaSource = if (isHls) HlsMediaSource.Factory(dataSourceFactory).setAllowChunklessPreparation(true).createMediaSource(mediaItem) else ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        exoPlayer.setMediaSource(mediaSource); exoPlayer.prepare(); exoPlayer.playWhenReady = true
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    duration = exoPlayer.duration.coerceAtLeast(0L)
                    if (!hasRestoredProgress) {
                        hasRestoredProgress = true
                        val savedPos = viewModel.getVideoProgress(currentFile.fid)
                        if (savedPos > 0L && savedPos < duration) {
                            exoPlayer.seekTo(savedPos)
                            currentPosition = savedPos
                        }
                    }
                } else if (state == Player.STATE_ENDED) {
                    val idx = series.videos.indexOfFirst { it.fid == currentFile.fid }
                    if (idx != -1 && idx < series.videos.size - 1) viewModel.playVideo(context, series, series.videos[idx + 1])
                }
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) resolution = "${videoSize.width}×${videoSize.height}"
            }
            override fun onPlayerError(error: PlaybackException) {
                val msg = when (val cause = error.cause) {
                    is HttpDataSource.InvalidResponseCodeException -> "播放失败: HTTP ${cause.responseCode}"
                    else -> "播放失败: ${error.errorCodeName}"
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
        exoPlayer.addListener(listener)
        onDispose { 
            if (exoPlayer.currentPosition > 0) viewModel.saveVideoProgress(currentFile.fid, exoPlayer.currentPosition)
            exoPlayer.removeListener(listener)
            exoPlayer.release() 
        }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            currentPosition = exoPlayer.currentPosition
            if (currentPosition > 0 && currentPosition % 5000 < 1000) {
                viewModel.saveVideoProgress(currentFile.fid, currentPosition)
            }

            if (introSeconds > 0 && currentPosition in (introSeconds * 1000L)..(introSeconds * 1000L + 1000L)) showSkipIntro = true
            else if (currentPosition > introSeconds * 1000L) showSkipIntro = false

            if (outroSeconds > 0 && duration > 0 && currentPosition >= (duration - outroSeconds * 1000L)) showSkipOutro = true
            else if (duration == 0L || currentPosition < (duration - outroSeconds * 1000L)) showSkipOutro = false

            delay(1000)
        }
    }

    LaunchedEffect(showControls, isPlaying, gestureType, showSettingsPanel, showEpisodeDrawer) {
        if (showControls && isPlaying && !isLocked && gestureType.isEmpty() && !showSettingsPanel && !showEpisodeDrawer) { 
            delay(3000); showControls = false 
        }
    }

    LaunchedEffect(seekMessage) {
        if (seekMessage != null) { delay(2000); seekMessage = null }
    }

    LaunchedEffect(isLocked, showUnlockIcon) {
        if (isLocked && showUnlockIcon) { delay(3000); showUnlockIcon = false }
    }

    BackHandler { 
        if (isLocked) { showUnlockIcon = true }
        else if (showSettingsPanel) showSettingsPanel = false
        else if (showEpisodeDrawer) showEpisodeDrawer = false
        else onBack() 
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(isLocked) {
                if (isLocked) {
                    detectTapGestures(onTap = { showUnlockIcon = true })
                    return@pointerInput
                }
                detectDragGestures(
                    onDragStart = { offset ->
                        if (offset.x < size.width / 2) {
                            gestureType = "brightness"
                            val currentB = activity?.window?.attributes?.screenBrightness ?: 0.5f
                            gestureValue = if (currentB < 0) 0.5f else currentB
                        } else {
                            gestureType = "volume"
                            gestureValue = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        if (gestureType == "brightness") {
                            gestureValue = (gestureValue - dragAmount.y / size.height).coerceIn(0f, 1f)
                            activity?.window?.let { val params = it.attributes; params.screenBrightness = gestureValue; it.attributes = params }
                        } else if (gestureType == "volume") {
                            gestureValue = (gestureValue - dragAmount.y / size.height).coerceIn(0f, 1f)
                            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (gestureValue * maxVol).roundToInt(), 0)
                        }
                    },
                    onDragEnd = { gestureType = "" },
                    onDragCancel = { gestureType = "" }
                )
            }
            .pointerInput(isLocked, showControls, showSettingsPanel, showEpisodeDrawer) {
                if (isLocked || showSettingsPanel || showEpisodeDrawer) return@pointerInput
                detectTapGestures(
                    onTap = { showControls = !showControls },
                    onDoubleTap = { offset ->
                        when {
                            offset.x < size.width / 3 -> {
                                exoPlayer.seekTo((exoPlayer.currentPosition - 10000).coerceAtLeast(0))
                                seekMessage = "快退 10 秒"
                            }
                            offset.x > size.width * 2 / 3 -> {
                                exoPlayer.seekTo((exoPlayer.currentPosition + 10000).coerceAtMost(if (duration > 0) duration else Long.MAX_VALUE))
                                seekMessage = "快进 10 秒"
                            }
                            else -> { if (isPlaying) exoPlayer.pause() else exoPlayer.play() }
                        }
                    },
                    onLongPress = { exoPlayer.setPlaybackSpeed(2.0f) },
                    onPress = { tryAwaitRelease(); exoPlayer.setPlaybackSpeed(playbackSpeed) }
                )
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer; useController = false
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (seekMessage != null) {
            Box(modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 8.dp)) {
                AppText(text = seekMessage!!, color = Color.White, fontSize = 16.sp)
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

        if (isLocked) {
            if (showUnlockIcon) {
                IconButton(onClick = { isLocked = false; showControls = true }, modifier = Modifier.align(Alignment.CenterStart).padding(32.dp)) {
                    Icon(Icons.Default.Lock, contentDescription = "解锁", tint = Color.White, modifier = Modifier.size(36.dp))
                }
            }
            return@Box
        }

        AnimatedVisibility(visible = showControls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                
                // 左右悬浮按钮
                Column(modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(30.dp)) {
                    // 1. 横竖屏切换（竖屏、横屏都必须有）
                    Surface(color = Color.White.copy(alpha = 0.1f), shape = CircleShape, border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))) {
                        IconButton(onClick = { 
                            if (isLandscape) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT 
                            else activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE 
                        }) {
                            Icon(Icons.Default.ScreenRotation, null, tint = Color.White)
                        }
                    }
                    
                    // 2. 选集（仅在横屏时显示在左侧，竖屏不显示）
                    if (isLandscape) {
                        Surface(color = Color.White.copy(alpha = 0.1f), shape = CircleShape, border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))) {
                            IconButton(onClick = { showEpisodeDrawer = true }) {
                                Icon(Icons.Default.FormatListBulleted, null, tint = Color.White)
                            }
                        }
                    }
                }

                Column(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(30.dp)) {
                    // 音频（仅横屏）
                    if (isLandscape) {
                        Surface(color = Color.White.copy(alpha = 0.1f), shape = CircleShape, border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))) {
                            IconButton(onClick = { Toast.makeText(context, "暂无音频轨道", Toast.LENGTH_SHORT).show() }) { Icon(Icons.Default.Headset, null, tint = Color.White) }
                        }
                    }
                    
                    // 锁屏（始终显示）
                    Surface(color = Color.White.copy(alpha = 0.1f), shape = CircleShape, border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))) {
                        IconButton(onClick = { isLocked = true; showControls = false; showUnlockIcon = true }) { Icon(Icons.Default.Lock, null, tint = Color.White) }
                    }
                    
                    // 字幕（仅横屏）
                    if (isLandscape) {
                        Surface(color = Color.White.copy(alpha = 0.1f), shape = CircleShape, border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))) {
                            IconButton(onClick = { Toast.makeText(context, "暂无字幕", Toast.LENGTH_SHORT).show() }) { Icon(Icons.Default.Subtitles, null, tint = Color.White) }
                        }
                    }
                }

                // 顶部区域
                Row(modifier = Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xAA000000), Color.Transparent))).statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
                    AppText(text = title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    if (resolution.isNotBlank()) AppText(text = resolution, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(end = 16.dp))
                    IconButton(onClick = { showSettingsPanel = true }) { Icon(Icons.Default.Settings, contentDescription = "设置", tint = Color.White) }
                }

                // 底部悬浮胶囊控制栏
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp) // 减小底部 padding，避免过度上移
                        .widthIn(max = if (isLandscape) 640.dp else 400.dp) // 限制最大宽度
                        .fillMaxWidth(0.9f) // 占屏幕宽度的 90% 左右，确保两侧有空间
                        .background(Color(0xB31A1A1A), RoundedCornerShape(20.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 20.dp, vertical = 12.dp) // 压缩内边距
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // 进度条
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppText(text = formatTime(currentPosition), color = Color.White, fontSize = 12.sp)
                            Slider(
                                value = if (duration > 0) currentPosition.toFloat() / duration else 0f,
                                onValueChange = { currentPosition = (it * duration).toLong() },
                                onValueChangeFinished = { exoPlayer.seekTo(currentPosition); if (isPlaying) exoPlayer.play() },
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp).height(20.dp),
                                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f))
                            )
                            AppText(text = formatTime(duration), color = Color.White, fontSize = 12.sp)
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // 按钮
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

                            // 中间控制按钮区，使用 weight 自动分配空间，避免被挤压
                            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = {
                                    val idx = series.videos.indexOfFirst { it.fid == currentFile.fid }
                                    if (idx > 0) viewModel.playVideo(context, series, series.videos[idx - 1])
                                }) { Icon(Icons.Default.SkipPrevious, null, tint = Color.White) }
                                
                                if (isLandscape) {
                                    IconButton(onClick = { exoPlayer.seekTo((exoPlayer.currentPosition - 10000).coerceAtLeast(0)); seekMessage = "快退 10 秒" }) {
                                        Icon(Icons.Default.FastRewind, null, tint = Color.White)
                                    }
                                }
                                IconButton(onClick = { if (isPlaying) exoPlayer.pause() else exoPlayer.play() }) {
                                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(32.dp))
                                }
                                if (isLandscape) {
                                    IconButton(onClick = { exoPlayer.seekTo((exoPlayer.currentPosition + 10000).coerceAtMost(if (duration > 0) duration else Long.MAX_VALUE)); seekMessage = "快进 10 秒" }) {
                                        Icon(Icons.Default.FastForward, null, tint = Color.White)
                                    }
                                }
                                
                                IconButton(onClick = {
                                    val idx = series.videos.indexOfFirst { it.fid == currentFile.fid }
                                    if (idx != -1 && idx < series.videos.size - 1) viewModel.playVideo(context, series, series.videos[idx + 1])
                                }) { Icon(Icons.Default.SkipNext, null, tint = Color.White) }
                            }

                            // 选集按钮
                            if (isLandscape) {
                                AppText(text = "选集", color = Color.White, modifier = Modifier.clickable { showEpisodeDrawer = true }.padding(8.dp))
                            } else {
                                Spacer(modifier = Modifier.width(40.dp))
                            }
                        }
                    }
                }

                if (showSkipIntro) {
                    Button(onClick = { exoPlayer.seekTo(introSeconds * 1000L); showSkipIntro = false }, modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 100.dp, end = 16.dp)) { AppText("跳过片头") }
                }
                if (showSkipOutro) {
                    Button(onClick = { 
                        val idx = series.videos.indexOfFirst { it.fid == currentFile.fid }
                        if (idx != -1 && idx < series.videos.size - 1) viewModel.playVideo(context, series, series.videos[idx + 1])
                    }, modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 100.dp, end = 16.dp)) { AppText("下一集") }
                }
            }
        }

        // 右侧抽屉：选集 (包含遮罩)
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
                        LazyColumn {
                            items(series.videos) { file ->
                                val e = file.parsedInfo?.episode
                                val tmdbTitle = if (e != null) series.episodeTitles[e] else null
                                val label = if (e != null) "第${e}集" + if (!tmdbTitle.isNullOrBlank()) " $tmdbTitle" else "" else file.name
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

        // 右侧抽屉：播放设置
        if (showSettingsPanel) {
            Box(modifier = Modifier.fillMaxSize().clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { showSettingsPanel = false })
            AnimatedVisibility(
                visible = showSettingsPanel,
                enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300)),
                exit = slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(280.dp)
            ) {
                Surface(color = Color(0xEE1A1A1A), modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
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
                                AppText("片头: ${formatTime(introSeconds * 1000L)}  片尾: ${formatTime(outroSeconds * 1000L)}", color = Color.Gray, fontSize = 12.sp)
                            }
                            Icon(Icons.Default.KeyboardArrowRight, null, tint = Color.Gray)
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
                AppText("片头时长: ${inputIntro.toInt()} 秒", color = Color.White)
                Slider(value = inputIntro, onValueChange = { inputIntro = it }, valueRange = 0f..300f)
                Spacer(modifier = Modifier.height(8.dp))
                AppText("片尾时长: ${inputOutro.toInt()} 秒", color = Color.White)
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

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d:%02d", hours, minutes, seconds)
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
fun SeriesPosterCard(series: MediaSeries, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(modifier = Modifier.width(110.dp).combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) {
            if (series.posterUrl != null) AsyncImage(model = series.posterUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else AppText("长按修正", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            if (series.rating != null) {
                AppText(text = series.rating, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, style = TextStyle(shadow = Shadow(color = Color.Black, offset = Offset(1f, 1f), blurRadius = 3f)), modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp))
            }
        }
        AppText(text = series.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium)
        val seasons = series.videos.mapNotNull { it.parsedInfo?.season }.distinct().size
        if (seasons > 1) AppText(text = "共 $seasons 季", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        else AppText(text = "共 1 季", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun MediaDetailScreen(
    series: MediaSeries, onBack: () -> Unit, onPlay: (MediaSeries, QuarkFile) -> Unit, onLongClickPoster: (MediaSeries) -> Unit
) {
    val viewModel: VideoScraperViewModel = koinViewModel()
    val latestWatchedFile = series.videos.maxByOrNull { viewModel.getVideoProgress(it.fid) }
    val hasProgress = latestWatchedFile != null && viewModel.getVideoProgress(latestWatchedFile.fid) > 0
    val playBtnText = if (hasProgress && latestWatchedFile != null) {
        val ep = latestWatchedFile.parsedInfo?.episode
        if (ep != null) "继续播放 第${ep}集" else "继续播放"
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
                        if (hasProgress && latestWatchedFile != null) onPlay(series, latestWatchedFile)
                        else onPlay(series, series.videos.firstOrNull() ?: return@Button)
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
        item { AppText(text = "剧集列表", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp)) }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(series.videos) { file ->
                    val e = file.parsedInfo?.episode
                    val tmdbTitle = if (e != null) series.episodeTitles[e] else null
                    val stillUrl = if (e != null) series.episodeStills[e] else null
                    val label = if (e != null) "第${e}集" + if (!tmdbTitle.isNullOrBlank()) " $tmdbTitle" else "" else file.name
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
        item {
            Column(modifier = Modifier.fillMaxWidth().background(Color(0xFFF5F5F5)).padding(16.dp).padding(top = 24.dp)) {
                AppText(text = "文件信息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                series.videos.firstOrNull()?.let { firstFile ->
                    AppText(text = firstFile.name, style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(top = 8.dp))
                    val pathStr = firstFile.folderPath.joinToString(" / ")
                    AppText(text = "夸克网盘: /$pathStr", style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(top = 4.dp))
                    val sizeMb = firstFile.size / (1024 * 1024)
                    AppText(text = "共 ${series.videos.size} 集 | ${sizeMb}MB (单集)", style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}