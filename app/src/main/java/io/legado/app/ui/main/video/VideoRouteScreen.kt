package io.legado.app.ui.main.video

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

@Composable
fun VideoRouteScreen(
    modifier: Modifier = Modifier,
    viewModel: VideoScraperViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showSettingsDialog by remember { mutableStateOf(false) }

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
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Tab(
                    selected = !uiState.isLibraryMode,
                    onClick = { viewModel.setMode(false) },
                    text = { AppText("网盘浏览") }
                )
                Tab(
                    selected = uiState.isLibraryMode,
                    onClick = { viewModel.setMode(true) },
                    text = { AppText("影视库") }
                )
            }
            
            IconButton(onClick = { showSettingsDialog = true }) {
                Icon(Icons.Default.Settings, contentDescription = "设置")
            }
        }

        // 始终显示错误信息，不隐藏
        if (uiState.errorMsg != null && !uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFFFCDD2))
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                AppText(uiState.errorMsg!!, color = Color.Red, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (uiState.isLoading && uiState.libraryVideos.isEmpty() && uiState.fileList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (!uiState.isLibraryMode) {
            // 网盘浏览模式
            if (uiState.fileList.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    AppText("当前文件夹为空")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(), 
                    contentPadding = PaddingValues(16.dp), 
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uiState.fileList) { file ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = file.isFolder) {
                                    if (file.isFolder) viewModel.fetchQuarkFiles(file.fid)
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (file.isFolder) Icons.Default.Folder else Icons.Default.Movie,
                                contentDescription = null,
                                tint = if (file.isFolder) Color(0xFFFFB300) else Color.Gray
                            )
                            Column(modifier = Modifier
                                .padding(start = 12.dp)
                                .weight(1f)) {
                                if (file.isFolder) {
                                    AppText(text = file.name)
                                } else {
                                    AppText(text = file.parsedInfo?.title ?: file.name, style = MaterialTheme.typography.titleMedium)
                                }
                            }
                            if (file.isFolder) {
                                IconButton(onClick = { viewModel.toggleLibrary(file.fid) }) {
                                    Icon(
                                        imageVector = if (uiState.selectedLibraryFids.contains(file.fid)) Icons.Default.Star else Icons.Outlined.StarBorder,
                                        contentDescription = "加入影视库",
                                        tint = if (uiState.selectedLibraryFids.contains(file.fid)) Color(0xFFFFB300) else Color.Gray
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // 影视库模式
            if (uiState.selectedLibraryFids.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    AppText("请勾选媒体文件夹")
                }
            } else if (uiState.libraryVideos.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (uiState.isLoading) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            AppText("正在深度扫描网盘...", modifier = Modifier.padding(top = 16.dp))
                        }
                    } else {
                        AppText("没找到任何视频")
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3), 
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp), 
                    horizontalArrangement = Arrangement.spacedBy(8.dp), 
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(uiState.libraryVideos) { video ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(2f / 3f)
                                    .background(Color.LightGray),
                                contentAlignment = Alignment.Center
                            ) {
                                if (video.posterUrl != null) {
                                    AsyncImage(
                                        model = video.posterUrl, 
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(), 
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    AppText("无海报", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            AppText(
                                text = video.parsedInfo?.title ?: video.name,
                                maxLines = 2, 
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp), 
                                style = MaterialTheme.typography.bodySmall
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
        
        Dialog(onDismissRequest = { showSettingsDialog = false }) {
            Column(
                modifier = Modifier
                    .background(Color.White)
                    .padding(16.dp)
                    .fillMaxWidth()
            ) {
                AppText(text = "夸克 Cookie")
                OutlinedTextField(
                    value = inputCookie,
                    onValueChange = { inputCookie = it },
                    modifier = Modifier.padding(vertical = 8.dp),
                    visualTransformation = PasswordVisualTransformation(),
                    maxLines = 3
                )
                
                AppText(text = "TMDB API Key (v3 auth)")
                OutlinedTextField(
                    value = inputTmdbKey,
                    onValueChange = { inputTmdbKey = it },
                    modifier = Modifier.padding(vertical = 8.dp),
                    visualTransformation = PasswordVisualTransformation(),
                    maxLines = 1
                )
                
                OutlinedButton(
                    onClick = {
                        viewModel.saveSettings(inputCookie, inputTmdbKey)
                        showSettingsDialog = false
                    },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    AppText("保存并刷新")
                }
            }
        }
    }
}