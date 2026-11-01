package io.legado.app.ui.main.video

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.layout.ContentScale
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import io.legado.app.ui.widget.components.text.AppText
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

@Composable
fun InternalVideoPlayer(url: String, title: String, cookie: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val exoPlayer = remember {
        val cleanCookie = cookie.replace("\n", "").replace("\r", "").trim()

        // 1. 构建统一的请求头，注入到所有网络请求中
        val defaultRequestProperties = mapOf(
            "Referer" to "https://pan.quark.cn/",
            "Cookie" to cleanCookie,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch"
        )

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(defaultRequestProperties)
            .setAllowCrossProtocolRedirects(true) // 允许夸克 CDN 可能发生的跨协议重定向
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)

        val mediaItemBuilder = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())

        // 2. 【核心修复】使用 Uri 解析路径，正确判断是否为 HLS 流
        // 这解决了链接带查询参数（如 ?token=xxx）时 endsWith(".m3u8") 判断失效的问题
        val isHls = try {
            Uri.parse(url).path?.contains(".m3u8") == true
        } catch (e: Exception) {
            url.contains(".m3u8") // 解析失败时回退到简单判断
        }

        val mediaSource = if (isHls) {
            // HLS 流：使用 HlsMediaSource，并开启 chunkless 优化以兼容非标准清单
            HlsMediaSource.Factory(dataSourceFactory)
                .setAllowChunklessPreparation(true)
                .createMediaSource(mediaItemBuilder.build())
        } else {
            // 普通视频流（MP4 等）：使用 ProgressiveMediaSource
            ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItemBuilder.build())
        }

        ExoPlayer.Builder(context)
            .build()
            .apply {
                setMediaSource(mediaSource)
                prepare()
                playWhenReady = true

                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        super.onPlayerError(error)
                        Log.e("ExoPlayer", "Error: ${error.errorCodeName}", error)

                        val msg = when (val cause = error.cause) {
                            is HttpDataSource.InvalidResponseCodeException ->
                                "播放失败: HTTP ${cause.responseCode}"
                            else -> "播放失败: ${error.errorCodeName}"
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                })
            }
    }

    BackHandler {
        exoPlayer.release()
        onBack()
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    useController = true
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xAA000000), Color.Transparent)))
                .statusBarsPadding()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { exoPlayer.release(); onBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
            }
            AppText(
                text = title,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp, end = 16.dp)
            )
        }
    }
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