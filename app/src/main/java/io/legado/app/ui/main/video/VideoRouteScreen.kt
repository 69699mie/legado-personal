package io.legado.app.ui.main.video

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.ui.widget.components.text.AppText
import org.koin.androidx.compose.koinViewModel

@Composable
fun VideoRouteScreen(
    modifier: Modifier = Modifier,
    viewModel: VideoScraperViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showSettingsDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // 顶部操作栏
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF5F5F5))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (uiState.currentFid != "0") {
                    IconButton(onClick = { viewModel.goBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
                AppText(
                    text = "夸克网盘",
                    modifier = Modifier.padding(start = if (uiState.currentFid != "0") 0.dp else 16.dp)
                )
            }
            
            IconButton(
                onClick = { showSettingsDialog = true },
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Icon(Icons.Default.Settings, contentDescription = "设置")
            }
        }

        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (uiState.errorMsg != null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AppText(uiState.errorMsg!!, color = Color.Red, modifier = Modifier.padding(16.dp))
            }
        } else if (uiState.fileList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AppText("暂无视频文件")
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
                        Column(modifier = Modifier.padding(start = 12.dp)) {
                            if (file.isFolder) {
                                AppText(text = file.name)
                            } else {
                                // ➕ 展示解析后的结果
                                val info = file.parsedInfo
                                if (info != null) {
                                    AppText(text = info.title, style = MaterialTheme.typography.titleMedium)
                                    val subText = buildString {
                                        info.year?.let { append("$it年 ") }
                                        info.season?.let { append("第${it}季 ") }
                                        info.episode?.let { append("第${it}集") }
                                    }
                                    if (subText.isNotBlank()) {
                                        AppText(text = subText, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                                    }
                                } else {
                                    AppText(text = file.name)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 设置弹窗 (Cookie + TMDB Key)
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
                
                AppText(text = "TMDB API Key (选填)")
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
                        viewModel.fetchQuarkFiles("0")
                    },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    AppText("保存并刷新")
                }
            }
        }
    }
}