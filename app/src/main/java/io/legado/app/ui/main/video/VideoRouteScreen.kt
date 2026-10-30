package io.legado.app.ui.main.video

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding // ➕ 新增
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
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

    // 💡 修复：加上 statusBarsPadding()，防止内容与系统状态栏重叠
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
                .padding(16.dp)
        ) {
            AppText(text = "夸克网盘文件列表 (测试连通性)")
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
                // 使用可滚动或换行的文本展示错误，因为错误信息可能很长
                AppText(
                    text = uiState.errorMsg!!, 
                    color = Color.Red,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else if (uiState.fileList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AppText("暂无数据或 Cookie 为空")
                    OutlinedButton(
                        onClick = { viewModel.fetchQuarkFiles() },
                        modifier = Modifier.padding(top = 16.dp)
                    ) {
                        AppText("测试拉取网盘文件")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.fileList) { fileName ->
                    AppText(text = fileName)
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
                        viewModel.fetchQuarkFiles()
                    },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    AppText("保存并请求")
                }
            }
        }
    }
}