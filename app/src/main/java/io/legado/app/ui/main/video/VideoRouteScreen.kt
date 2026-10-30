package io.legado.app.ui.main.video

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    var showCookieDialog by remember { mutableStateOf(false) }

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
                // 如果不是根目录，显示返回按钮
                if (uiState.currentFid != "0") {
                    IconButton(onClick = { viewModel.goBack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回上一级"
                        )
                    }
                }
                AppText(
                    text = "夸克网盘", 
                    modifier = Modifier.padding(start = if (uiState.currentFid != "0") 0.dp else 16.dp)
                )
            }
            
            IconButton(
                onClick = { showCookieDialog = true },
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Icon(imageVector = Icons.Default.Settings, contentDescription = "设置Cookie")
            }
        }

        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (uiState.errorMsg != null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AppText(
                    text = uiState.errorMsg!!, 
                    color = Color.Red,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else if (uiState.fileList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AppText("当前文件夹为空")
                    OutlinedButton(
                        onClick = { viewModel.fetchQuarkFiles(uiState.currentFid) },
                        modifier = Modifier.padding(top = 16.dp)
                    ) {
                        AppText("刷新列表")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.fileList) { file ->
                    // 列表项
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = file.isFolder) {
                                if (file.isFolder) {
                                    viewModel.fetchQuarkFiles(file.fid)
                                } else {
                                    // 后续这里处理视频播放
                                }
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (file.isFolder) Icons.Default.Folder else Icons.Default.Movie,
                            contentDescription = null,
                            tint = if (file.isFolder) Color(0xFFFFB300) else Color.Gray
                        )
                        AppText(
                            text = file.name,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                }
            }
        }
    }

    // Cookie 设置弹窗
    if (showCookieDialog) {
        var inputCookie by remember { mutableStateOf(uiState.cookie) }
        Dialog(onDismissRequest = { showCookieDialog = false }) {
            Column(
                modifier = Modifier
                    .background(Color.White)
                    .padding(16.dp)
                    .fillMaxWidth()
            ) {
                AppText(text = "导入夸克网盘 Cookie")
                OutlinedTextField(
                    value = inputCookie,
                    onValueChange = { inputCookie = it },
                    modifier = Modifier.padding(vertical = 16.dp),
                    visualTransformation = PasswordVisualTransformation(),
                    maxLines = 5
                )
                OutlinedButton(
                    onClick = {
                        viewModel.saveCookie(inputCookie)
                        showCookieDialog = false
                        viewModel.fetchQuarkFiles("0")
                    },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    AppText("保存并请求")
                }
            }
        }
    }
}