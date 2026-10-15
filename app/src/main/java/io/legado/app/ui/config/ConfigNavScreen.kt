package io.legado.app.ui.config

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.theme.adaptiveContentPadding
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.SplicedColumnGroup
import io.legado.app.ui.widget.components.settingItem.ClickableSettingItem
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigNavScreen(
    onBackClick: () -> Unit,
    onNavigateToOther: () -> Unit,
    onNavigateToRead: () -> Unit,
    onNavigateToReadAloud: () -> Unit,
    onNavigateToCover: () -> Unit,
    onNavigateToTheme: () -> Unit,
    onNavigateToBackup: () -> Unit,
    onNavigateToAi: () -> Unit,
    onNavigateToDownloadCache: () -> Unit,
    onNavigateToTranslation: () -> Unit,
    onNavigateToLab: () -> Unit,
    onNavigateToPrivate: () -> Unit
) {
    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()

    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = stringResource(R.string.setting),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    TopBarNavigationButton(onClick = onBackClick)
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = adaptiveContentPadding(
                top = paddingValues.calculateTopPadding(),
                bottom = 120.dp
            )
        ) {
            item {
                SplicedColumnGroup {
                    ClickableSettingItem(
                        title = stringResource(R.string.theme_setting),
                        onClick = onNavigateToTheme
                    )
                    ClickableSettingItem(
                        title = stringResource(R.string.other_setting),
                        onClick = onNavigateToOther
                    )
                    // 💡 漫画专用 App：「阅读界面」入口隐藏（小说文本阅读配置）
                    // ClickableSettingItem(
                    //     title = stringResource(R.string.read_config),
                    //     onClick = onNavigateToRead
                    // )
                    // 💡 漫画专用 App：「朗读设置」入口隐藏（小说 TTS 朗读）
                    // ClickableSettingItem(
                    //     title = stringResource(R.string.aloud_config),
                    //     description = stringResource(R.string.multi_role_rule_summary),
                    //     onClick = onNavigateToReadAloud
                    // )
                    ClickableSettingItem(
                        title = stringResource(R.string.cover_config),
                        onClick = onNavigateToCover
                    )
                    ClickableSettingItem(
                        title = stringResource(R.string.download_cache_config),
                        onClick = onNavigateToDownloadCache
                    )
                    ClickableSettingItem(
                        title = stringResource(R.string.backup_restore),
                        onClick = onNavigateToBackup
                    )
                    // 💡 已按需隐藏：「AI 设置」
                    // ClickableSettingItem(
                    //     title = stringResource(R.string.ai_config),
                    //     onClick = onNavigateToAi
                    // )
                    // 💡 漫画专用 App：「翻译设置」入口隐藏（小说翻译）
                    // ClickableSettingItem(
                    //     title = stringResource(R.string.translation_config),
                    //     onClick = onNavigateToTranslation
                    // )
                    // 💡 已按需隐藏：「隐私」
                    // ClickableSettingItem(
                    //     title = stringResource(R.string.privacy),
                    //     onClick = onNavigateToPrivate
                    // )
                    // 💡 已按需隐藏：「实验室」
                    // ClickableSettingItem(
                    //     title = stringResource(R.string.lab_setting),
                    //     onClick = onNavigateToLab
                    // )
                }
            }
        }
    }
}