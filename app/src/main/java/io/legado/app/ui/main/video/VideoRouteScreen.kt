package io.legado.app.ui.main.video

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
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
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import io.legado.app.ui.widget.components.text.AppText
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun VideoRouteScreen(
    modifier: Modifier = Modifier,
    viewModel: VideoScraperViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showWebLoginDialog by remember { mutableStateOf(false) }
    var showManualScrapeDialog by remember { mutableStateOf(false) }
    var manualScrapeSeries by remember { mutableStateOf<MediaSeries?>(null) }
    var viewAllCategory by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = uiState.playUrl != null || uiState.currentSeries != null || viewAllCategory != null || (!uiState.isLibraryMode && uiState.currentFid != "0")) {
        if (uiState.playUrl != null) {
            viewModel.stopPlaying()
        } else if (uiState.currentSeries != null) {
            viewModel.selectSeries(null)
        } else if (viewAllCategory != null) {
            viewAllCategory = null
        } else {
            viewModel.goBack()
        }
    }

    if (uiState.playUrl != null) {
        InternalVideoPlayer(
            url = uiState.playUrl!!,
            title = uiState.playTitle ?: "播放中",
            cookie = uiState.cookie,
            onBack = { viewModel.stopPlaying() }
        )
    } else if (uiState.currentSeries != null) {
        MediaDetailScreen(
            series = uiState.currentSeries!!,
            onBack = { viewModel.selectSeries(null) },
            onPlay = { series, file -> viewModel.playVideo(context, series, file) },
            onLongClickPoster = {
                manualScrapeSeries = it
                showManualScrapeDialog = true
            }
        )
    } else if (viewAllCategory != null) {
        val categorySeries = uiState.seriesList.filter { it.category == viewAllCategory }
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().background(Color.White)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { viewAllCategory = null }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.Black)
                }
                AppText(
                    text = viewAllCategory ?: "",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(categorySeries) { series ->
                    SeriesPosterCard(
                        series = series,
                        onClick = { viewModel.selectSeries(series) },
                        onLongClick = {
                            manualScrapeSeries = series
                            showManualScrapeDialog = true
                        }
                    )
                }
            }
        }
    } else {
        Column(modifier = modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().background(Color(0xFFF5F5F5)).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (uiState.currentFid != "0" && !uiState.isLibraryMode) {
                    IconButton(onClick = { viewModel.goBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
                TabRow(
                    selectedTabIndex = if (uiState.isLibraryMode) 1 else 0,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Tab(selected = !uiState.isLibraryMode, onClick = { viewModel.setMode(false) }, text = { AppText("网盘浏览") })
                    Tab(selected = uiState.isLibraryMode, onClick = { viewModel.setMode(true) }, text = { AppText("影视库") })
                }
                IconButton(onClick = { showSettingsDialog = true }) {
                    Icon(Icons.Default.Settings, contentDescription = "设置")
                }
            }

            if (uiState.errorMsg != null && !uiState.isLoading) {
                Box(modifier = Modifier.fillMaxWidth().background(Color(0xFFFFCDD2)).padding(8.dp), contentAlignment = Alignment.Center) {
                    AppText(uiState.errorMsg!!, color = Color.Red, style = MaterialTheme.typography.bodySmall)
                }
            }

            if (uiState.isLoading && uiState.seriesList.isEmpty() && uiState.fileList.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
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
                                Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                                    AppText(text = file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (file.isFolder) {
                                    IconButton(onClick = { viewModel.toggleLibrary(file.fid, file.name) }) {
                                        Icon(
                                            if (uiState.selectedLibraryFids.contains(file.fid)) Icons.Default.Star else Icons.Outlined.StarBorder,
                                            "加入影视库",
                                            tint = if (uiState.selectedLibraryFids.contains(file.fid)) Color(0xFFFFB300) else Color.Gray
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                PullToRefreshBox(
                    isRefreshing = uiState.isLoading,
                    onRefresh = { viewModel.loadLibraryVideos() },
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (uiState.selectedLibraryFids.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { AppText("请先在网盘浏览中勾选媒体文件夹") }
                    } else if (uiState.seriesList.isEmpty() && !uiState.isLoading) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { AppText("没找到任何视频，下拉可刷新") }
                    } else {
                        val grouped = uiState.seriesList.groupBy { it.category }
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 16.dp)
                        ) {
                            grouped.forEach { (categoryName, seriesList) ->
                                if (seriesList.isNotEmpty()) {
                                    item {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            AppText(text = categoryName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                            AppText(
                                                text = "全部 ${seriesList.size} 部 >",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color.Gray,
                                                modifier = Modifier.clickable { viewAllCategory = categoryName }
                                            )
                                        }
                                    }
                                    item {
                                        LazyRow(
                                            contentPadding = PaddingValues(horizontal = 16.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            items(seriesList) { series ->
                                                SeriesPosterCard(
                                                    series = series,
                                                    onClick = { viewModel.selectSeries(series) },
                                                    onLongClick = {
                                                        manualScrapeSeries = series
                                                        showManualScrapeDialog = true
                                                    }
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

    if (showSettingsDialog) {
        var inputCookie by remember { mutableStateOf(uiState.cookie) }
        var inputTmdbKey by remember { mutableStateOf(uiState.tmdbApiKey) }
        var inputTmdbUrl by remember { mutableStateOf(uiState.tmdbBaseUrl) }
        Dialog(onDismissRequest = { showSettingsDialog = false }) {
            Column(modifier = Modifier.background(Color.White).padding(16.dp).fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    AppText(text = "夸克登录状态", fontWeight = FontWeight.Bold)
                    Button(onClick = { showWebLoginDialog = true }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                        AppText("网页扫码登录")
                    }
                }
                OutlinedTextField(
                    value = inputCookie, 
                    onValueChange = { inputCookie = it }, 
                    modifier = Modifier.padding(vertical = 8.dp), 
                    visualTransformation = PasswordVisualTransformation(), 
                    maxLines = 2,
                    label = { AppText("手动填写 Cookie（可选）") }
                )
                AppText(text = "TMDB API Key (v3 auth)")
                OutlinedTextField(value = inputTmdbKey, onValueChange = { inputTmdbKey = it }, modifier = Modifier.padding(vertical = 8.dp), visualTransformation = PasswordVisualTransformation(), maxLines = 1)
                AppText(text = "TMDB API 代理地址")
                OutlinedTextField(value = inputTmdbUrl, onValueChange = { inputTmdbUrl = it }, modifier = Modifier.padding(vertical = 8.dp), maxLines = 1)
                OutlinedButton(onClick = { viewModel.saveSettings(inputCookie, inputTmdbKey, inputTmdbUrl); showSettingsDialog = false }, modifier = Modifier.align(Alignment.End)) {
                    AppText("保存并刷新")
                }
            }
        }
    }

    if (showWebLoginDialog) {
        Dialog(
            onDismissRequest = { showWebLoginDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color.White).statusBarsPadding()) {
                WebLoginView(
                    onLoginSuccess = { cookie ->
                        viewModel.loginWithCapturedCookie(cookie)
                        showWebLoginDialog = false
                        Toast.makeText(context, "登录成功！", Toast.LENGTH_SHORT).show()
                    },
                    onCancel = { showWebLoginDialog = false }
                )
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
                    OutlinedButton(onClick = {
                        viewModel.applyManualScrape(manualScrapeSeries!!.id, null, categoryInput, searchKeyword)
                        showManualScrapeDialog = false
                    }, modifier = Modifier.padding(start = 8.dp)) { AppText("仅保存") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                    Spacer(modifier = Modifier.width(50.dp))
                    OutlinedButton(onClick = { viewModel.searchTmdbManually(searchKeyword) { searchResults = it } }, modifier = Modifier.fillMaxWidth()) {
                        AppText("去 TMDB 搜索海报")
                    }
                }
                if (searchResults == null) {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { AppText("输入名称并搜索", color = Color.Gray) }
                } else if (searchResults!!.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { AppText("未找到", color = Color.Red) }
                } else {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(searchResults!!) { result ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    viewModel.applyManualScrape(manualScrapeSeries!!.id, result, categoryInput, result.title)
                                    showManualScrapeDialog = false
                                }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (result.posterUrl != null) {
                                    AsyncImage(model = result.posterUrl, contentDescription = null, modifier = Modifier.size(60.dp, 90.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                                } else {
                                    Box(modifier = Modifier.size(60.dp, 90.dp).background(Color.LightGray).clip(RoundedCornerShape(8.dp)))
                                }
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
    val desktopUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.destroy()
            webViewRef = null
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFFF5F5F5)).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onCancel) { AppText("取消", color = Color.Gray) }
            AppText("夸克扫码/账号登录", fontWeight = FontWeight.Bold)
            TextButton(onClick = {
                val cookie = CookieManager.getInstance().getCookie("https://pan.quark.cn/")
                if (!cookie.isNullOrBlank() && cookie.contains("__uid=") && cookie.contains("__puus=")) {
                    onLoginSuccess(cookie)
                } else {
                    Toast.makeText(context, "尚未检测到有效登录，请完成扫码或授权", Toast.LENGTH_SHORT).show()
                }
            }) { AppText("完成登录", color = MaterialTheme.colorScheme.primary) }
        }

        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    webViewRef = this
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.userAgentString = desktopUserAgent
                    
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            val cookie = CookieManager.getInstance().getCookie("https://pan.quark.cn/")
                            if (!cookie.isNullOrBlank() && cookie.contains("__uid=") && cookie.contains("__puus=")) {
                                onLoginSuccess(cookie)
                            }
                        }
                    }
                    loadUrl("https://pan.quark.cn/")
                }
            }
        )
    }
}

// 【核心重构】播放器组件
@Composable
fun InternalVideoPlayer(url: String, title: String, cookie: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // 沉浸式全屏控制
    DisposableEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        activity?.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            activity?.window?.let { window ->
                WindowCompat.setDecorFitsSystemWindows(window, true)
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // 播放器状态
    var isPlaying by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var resolution by remember { mutableStateOf("") }
    var showControls by remember { mutableStateOf(true) }

    // 初始化 ExoPlayer
    val exoPlayer = remember {
        val cleanCookie = cookie.replace("\n", "").replace("\r", "").trim()
        val defaultRequestProperties = mapOf(
            "Referer" to "https://pan.quark.cn/",
            "Cookie" to cleanCookie,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch"
        )

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(defaultRequestProperties)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)

        val mediaItemBuilder = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())

        val isHls = try {
            Uri.parse(url).path?.contains(".m3u8") == true
        } catch (e: Exception) {
            url.contains(".m3u8")
        }

        val mediaSource = if (isHls) {
            HlsMediaSource.Factory(dataSourceFactory)
                .setAllowChunklessPreparation(true)
                .createMediaSource(mediaItemBuilder.build())
        } else {
            ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItemBuilder.build())
        }

        ExoPlayer.Builder(context)
            .build()
            .apply {
                setMediaSource(mediaSource)
                prepare()
                playWhenReady = true
            }
    }

    // 监听播放器状态更新 UI
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    duration = exoPlayer.duration.coerceAtLeast(0L)
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    resolution = "${videoSize.width}×${videoSize.height}"
                }
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
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // 进度同步
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            currentPosition = exoPlayer.currentPosition
            delay(1000)
        }
    }

    // 自动隐藏控制栏
    LaunchedEffect(showControls, isPlaying) {
        if (showControls && isPlaying) {
            delay(3000)
            showControls = false
        }
    }

    BackHandler {
        onBack()
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false // 禁用原生控制器
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(onTap = { showControls = !showControls })
            }
        )

        // Compose 自定义控制层
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color(0x44000000))) {
                // 顶部：返回、标题、分辨率、旋转按钮
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0xAA000000), Color.Transparent)))
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
                    }
                    AppText(
                        text = title,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 8.dp)
                    )
                    if (resolution.isNotBlank()) {
                        AppText(
                            text = resolution,
                            color = Color.White,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(end = 16.dp)
                        )
                    }
                    IconButton(onClick = {
                        activity?.requestedOrientation = if (isLandscape) {
                            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        } else {
                            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        }
                    }) {
                        // 简单使用内置图标或自定义
                        Icon(
                            imageVector = Icons.Default.Settings, // 临时占位，可以通过自定义 vector 替换为旋转图标
                            contentDescription = "切换横竖屏",
                            tint = Color.White
                        )
                    }
                }

                // 底部：进度条、时间、播放/暂停
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xAA000000))))
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppText(
                            text = formatTime(currentPosition),
                            color = Color.White,
                            fontSize = 12.sp
                        )
                        Slider(
                            value = if (duration > 0) currentPosition.toFloat() / duration else 0f,
                            onValueChange = { 
                                val newPos = (it * duration).toLong()
                                exoPlayer.seekTo(newPos)
                                currentPosition = newPos
                            },
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            )
                        )
                        AppText(
                            text = formatTime(duration),
                            color = Color.White,
                            fontSize = 12.sp
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 倒放 15s (可自行加 icon，此处留白)
                        Spacer(modifier = Modifier.width(48.dp))
                        IconButton(onClick = {
                            if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                        }) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "暂停" else "播放",
                                tint = Color.White,
                                modifier = Modifier.size(48.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(48.dp))
                    }
                }
            }
        }
    }
}

// 格式化时间辅助函数
private fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SeriesPosterCard(series: MediaSeries, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        modifier = Modifier.width(110.dp).combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(Color(0xFFE0E0E0)),
            contentAlignment = Alignment.Center
        ) {
            if (series.posterUrl != null) {
                AsyncImage(model = series.posterUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                AppText("长按修正", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            }
            if (series.rating != null) {
                AppText(
                    text = series.rating,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    style = TextStyle(
                        shadow = Shadow(color = Color.Black, offset = Offset(1f, 1f), blurRadius = 3f)
                    ),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                )
            }
        }
        AppText(text = series.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium)
        val seasons = series.videos.mapNotNull { it.parsedInfo?.season }.distinct().size
        if (seasons > 1) {
            AppText(text = "共 $seasons 季", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        } else {
            AppText(text = "共 1 季", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun MediaDetailScreen(
    series: MediaSeries, 
    onBack: () -> Unit, 
    onPlay: (MediaSeries, QuarkFile) -> Unit, 
    onLongClickPoster: (MediaSeries) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize().background(Color.White)) {
        item {
            Box(modifier = Modifier.fillMaxWidth().height(350.dp)) {
                if (series.posterUrl != null) {
                    AsyncImage(
                        model = series.posterUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.White), startY = 200f, endY = 900f)))
                } else {
                    Box(modifier = Modifier.fillMaxSize().background(Color.LightGray))
                }
                Row(
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.Black)
                    }
                    IconButton(onClick = { onLongClickPoster(series) }) {
                        Icon(Icons.Default.Edit, contentDescription = "编辑", tint = Color.Black)
                    }
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
                    onClick = { onPlay(series, series.videos.firstOrNull() ?: return@Button) },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333))
                ) {
                    Icon(Icons.Default.PlayArrow, null, tint = Color.White)
                    AppText(text = " 播放", color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
                AppText(text = "剧情简介", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
                AppText(text = series.overview ?: "暂无简介", style = MaterialTheme.typography.bodyMedium, color = Color.DarkGray, modifier = Modifier.padding(top = 8.dp))
            }
        }
        item {
            AppText(text = "剧集列表", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp))
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(series.videos) { file ->
                    val e = file.parsedInfo?.episode
                    val tmdbTitle = if (e != null) series.episodeTitles[e] else null
                    val stillUrl = if (e != null) series.episodeStills[e] else null
                    
                    val label = if (e != null) {
                        "第${e}集" + if (!tmdbTitle.isNullOrBlank()) " $tmdbTitle" else ""
                    } else file.name
                    
                    Column(modifier = Modifier.width(160.dp).clickable { onPlay(series, file) }) {
                        Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Color.DarkGray), contentAlignment = Alignment.Center) {
                            if (stillUrl != null) {
                                AsyncImage(model = stillUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            } else {
                                Icon(Icons.Default.PlayArrow, null, tint = Color.White)
                            }
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