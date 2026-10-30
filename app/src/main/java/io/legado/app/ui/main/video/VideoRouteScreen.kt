package io.legado.app.ui.main.video

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.legado.app.ui.widget.components.text.AppText
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoRouteScreen(
    modifier: Modifier = Modifier,
    viewModel: VideoScraperViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showSettingsDialog by remember { mutableStateOf(false) }
    
    // 手动刮削弹窗状态
    var showManualScrapeDialog by remember { mutableStateOf(false) }
    var manualScrapeVideo by remember { mutableStateOf<QuarkFile?>(null) }

    BackHandler(enabled = uiState.currentFid != "0" && !uiState.isLibraryMode) {
        viewModel.goBack()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // 顶部操作栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF5F5F5))
                .padding(horizontal = 16.dp, vertical = 8.dp),
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

        if (uiState.isLoading && uiState.libraryVideos.isEmpty() && uiState.fileList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (!uiState.isLibraryMode) {
            // 网盘浏览模式
            if (uiState.fileList.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { AppText("当前文件夹为空") }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(uiState.fileList) { file ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable(enabled = file.isFolder) {
                                if (file.isFolder) viewModel.fetchQuarkFiles(file.fid)
                            }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(if (file.isFolder) Icons.Default.Folder else Icons.Default.Movie, null, tint = if (file.isFolder) Color(0xFFFFB300) else Color.Gray)
                            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                                // 网盘浏览模式显示原文件名，方便定位
                                AppText(text = file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (file.isFolder) {
                                IconButton(onClick = { viewModel.toggleLibrary(file.fid) }) {
                                    Icon(if (uiState.selectedLibraryFids.contains(file.fid)) Icons.Default.Star else Icons.Outlined.StarBorder, "加入影视库", tint = if (uiState.selectedLibraryFids.contains(file.fid)) Color(0xFFFFB300) else Color.Gray)
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // 影视库模式
            if (uiState.selectedLibraryFids.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { AppText("请勾选媒体文件夹") }
            } else if (uiState.libraryVideos.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (uiState.isLoading) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            AppText("正在深度扫描网盘...", modifier = Modifier.padding(top = 16.dp))
                        }
                    } else { AppText("没找到任何视频") }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(uiState.libraryVideos) { video ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { /* 播放逻辑 */ },
                                    onLongClick = {
                                        manualScrapeVideo = video
                                        showManualScrapeDialog = true
                                    }
                                )
                        ) {
                            Box(
                                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(Color.LightGray),
                                contentAlignment = Alignment.Center
                            ) {
                                if (video.posterUrl != null) {
                                    AsyncImage(model = video.posterUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                } else {
                                    AppText("长按修正", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            // 💡 优先显示 TMDB 官方名字，如果没有再用本地解析的名字
                            AppText(
                                text = video.tmdbTitle ?: video.parsedInfo?.title ?: video.name,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }

    // 设置弹窗
    if (showSettingsDialog) {
        var inputCookie by remember { mutableStateOf(uiState.cookie) }
        var inputTmdbKey by remember { mutableStateOf(uiState.tmdbApiKey) }
        var inputTmdbUrl by remember { mutableStateOf(uiState.tmdbBaseUrl) }
        
        Dialog(onDismissRequest = { showSettingsDialog = false }) {
            Column(modifier = Modifier.background(Color.White).padding(16.dp).fillMaxWidth()) {
                AppText(text = "夸克 Cookie")
                OutlinedTextField(value = inputCookie, onValueChange = { inputCookie = it }, modifier = Modifier.padding(vertical = 8.dp), visualTransformation = PasswordVisualTransformation(), maxLines = 3)
                
                AppText(text = "TMDB API Key (v3 auth)")
                OutlinedTextField(value = inputTmdbKey, onValueChange = { inputTmdbKey = it }, modifier = Modifier.padding(vertical = 8.dp), visualTransformation = PasswordVisualTransformation(), maxLines = 1)
                
                AppText(text = "TMDB API 代理地址 (防直连失败)")
                OutlinedTextField(value = inputTmdbUrl, onValueChange = { inputTmdbUrl = it }, modifier = Modifier.padding(vertical = 8.dp), maxLines = 1)
                
                OutlinedButton(
                    onClick = {
                        viewModel.saveSettings(inputCookie, inputTmdbKey, inputTmdbUrl)
                        showSettingsDialog = false
                    },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    AppText("保存并刷新")
                }
            }
        }
    }
    
    // 手动修正弹窗
    if (showManualScrapeDialog && manualScrapeVideo != null) {
        var searchKeyword by remember { mutableStateOf(manualScrapeVideo!!.parsedInfo?.title ?: "") }
        var searchResults by remember { mutableStateOf<List<TmdbSearchResult>?>(null) }
        
        Dialog(onDismissRequest = { showManualScrapeDialog = false }) {
            Column(modifier = Modifier.background(Color.White).padding(16.dp).fillMaxWidth().heightIn(max = 600.dp)) {
                AppText("手动修正 / 重新刮削")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = searchKeyword, 
                        onValueChange = { searchKeyword = it }, 
                        modifier = Modifier.weight(1f), 
                        placeholder = { AppText("输入影视名称") }
                    )
                    OutlinedButton(
                        onClick = { viewModel.searchTmdbManually(searchKeyword) { searchResults = it } },
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        AppText("搜索")
                    }
                }
                
                if (searchResults == null) {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        AppText("输入名称并点击搜索", color = Color.Gray)
                    }
                } else if (searchResults!!.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        AppText("未找到相关影视", color = Color.Red)
                    }
                } else {
                    LazyColumn(modifier = Modifier.padding(top = 16.dp)) {
                        items(searchResults!!) { result ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    viewModel.applyManualScrape(manualScrapeVideo!!.fid, result)
                                    showManualScrapeDialog = false
                                }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (result.posterUrl != null) {
                                    AsyncImage(model = result.posterUrl, contentDescription = null, modifier = Modifier.size(60.dp, 90.dp), contentScale = ContentScale.Crop)
                                } else {
                                    Box(modifier = Modifier.size(60.dp, 90.dp).background(Color.LightGray))
                                }
                                Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                                    AppText(result.title, style = MaterialTheme.typography.titleMedium)
                                    AppText(result.year, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                                    AppText(result.overview, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Color.DarkGray)
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