package io.legado.app.ui.config.proxyConfig

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.R
import io.legado.app.help.config.ProxyConfig
import io.legado.app.ui.theme.adaptiveContentPadding
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.button.series.MediumOutlinedButton
import io.legado.app.ui.widget.components.button.series.MediumTonalButton
import io.legado.app.ui.widget.components.card.NormalCard
import io.legado.app.ui.widget.components.settingItem.ClickableSettingItem
import io.legado.app.ui.widget.components.settingItem.DropdownListSettingItem
import io.legado.app.ui.widget.components.settingItem.InputSettingItem
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyConfigScreen(
    onBackClick: () -> Unit,
    viewModel: ProxyConfigViewModel = viewModel()
) {
    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()
    val sources by viewModel.sources.collectAsState()
    val testResult by viewModel.testResult.collectAsState()

    var editingItem by remember { mutableStateOf<SourceProxyItem?>(null) }

    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = "书源代理设置",
                scrollBehavior = scrollBehavior,
                navigationIcon = { TopBarNavigationButton(onClick = onBackClick) }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = adaptiveContentPadding(
                top = paddingValues.calculateTopPadding(),
                bottom = 120.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(sources, key = { it.source.bookSourceUrl }) { item ->
                NormalCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clickable { editingItem = item }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            AppText(text = item.source.bookSourceName, style = MaterialTheme.typography.titleMedium)
                            AppText(
                                text = item.source.bookSourceUrl,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        val proxyText = if (item.config != null) {
                            "${item.config.type}://${item.config.host}:${item.config.port}"
                        } else "未设置 (直连)"
                        AppText(text = proxyText, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }

    if (editingItem != null) {
        ProxyEditDialog(
            item = editingItem!!,
            testResult = testResult,
            onDismiss = {
                editingItem = null
                viewModel.clearTestResult()
            },
            onSave = { config ->
                viewModel.saveProxy(editingItem!!.source.bookSourceUrl, config)
                editingItem = null
                viewModel.clearTestResult()
            },
            onTest = { config ->
                viewModel.testProxy(editingItem!!.source.bookSourceUrl, config)
            }
        )
    }
}

@Composable
private fun ProxyEditDialog(
    item: SourceProxyItem,
    testResult: ProxyTestResult,
    onDismiss: () -> Unit,
    onSave: (ProxyConfig?) -> Unit,
    onTest: (ProxyConfig) -> Unit
) {
    var type by remember { mutableStateOf(item.config?.type ?: "HTTP") }
    var host by remember { mutableStateOf(item.config?.host ?: "") }
    var port by remember { mutableStateOf(if (item.config != null && item.config.port > 0) item.config.port.toString() else "") }
    var username by remember { mutableStateOf(item.config?.username ?: "") }
    var password by remember { mutableStateOf(item.config?.password ?: "") }

    val currentConfig = {
        ProxyConfig(type, host, port.toIntOrNull() ?: 0, username, password)
    }

    AppAlertDialog(
        show = true,
        onDismissRequest = onDismiss,
        title = "代理设置: ${item.source.bookSourceName}",
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DropdownListSettingItem(
                    title = "协议类型",
                    selectedValue = type,
                    displayEntries = arrayOf("HTTP", "HTTPS", "SOCKS5"),
                    entryValues = arrayOf("HTTP", "HTTPS", "SOCKS5"),
                    onValueChange = { type = it }
                )
                InputSettingItem(
                    title = "主机 IP / 域名",
                    value = host,
                    onConfirm = { host = it }
                )
                InputSettingItem(
                    title = "端口",
                    value = port,
                    onConfirm = { port = it }
                )
                InputSettingItem(
                    title = "用户名 (可选)",
                    value = username,
                    onConfirm = { username = it }
                )
                InputSettingItem(
                    title = "密码 (可选)",
                    value = password,
                    onConfirm = { password = it }
                )
                
                // 测试状态展示
                if (testResult.isTesting) {
                    AppText("正在测试...", color = MaterialTheme.colorScheme.primary)
                } else if (testResult.success != null) {
                    AppText(
                        text = testResult.message,
                        color = if (testResult.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MediumOutlinedButton(
                        text = "清除代理",
                        onClick = { onSave(null) },
                        modifier = Modifier.weight(1f)
                    )
                    MediumTonalButton(
                        text = "测试代理",
                        onClick = { onTest(currentConfig()) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmText = "保存",
        onConfirm = { onSave(currentConfig()) },
        dismissText = "取消",
        onDismiss = onDismiss
    )
}