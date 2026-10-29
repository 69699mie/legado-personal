package io.legado.app.ui.config.themeConfig

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.domain.model.settings.AppShellSettings
import io.legado.app.ui.main.MainDestination
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.card.ReorderableSelectionItem
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.settingItem.CompactClickableSettingItem
import io.legado.app.ui.widget.components.settingItem.CompactDropdownSettingItem
import io.legado.app.utils.move
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun MainNavigationSettingsSheet(
    show: Boolean,
    settings: AppShellSettings,
    onDismissRequest: () -> Unit,
    onSetVisible: (String, Boolean) -> Unit,
    onSetOrder: (String) -> Unit,
    onSetDefault: (String) -> Unit,
    onRequestNavigationIcon: (String) -> Unit,
    onClearNavigationIcon: (String) -> Unit,
    onSetLabelVisibilityMode: (String) -> Unit,
) {
    var showNavigationIcons by remember(show) { mutableStateOf(false) }
    
    // 读取所有项，此时 mainDestinations 里已经没有 Rss 了
    var navigationItems by remember(show) {
        mutableStateOf(MainDestination.ordered(settings.mainNavigationOrder))
    }
    val navigationListState = rememberLazyListState()
    val reorderableState =
        rememberReorderableLazyListState(navigationListState) { from, to ->
            navigationItems = navigationItems.toMutableList().apply {
                move(from.index, to.index)
            }
        }

    LaunchedEffect(reorderableState.isAnyItemDragging) {
        if (!reorderableState.isAnyItemDragging) {
            onSetOrder(navigationItems.joinToString(",") { it.route })
        }
    }

    fun isRouteVisible(route: String): Boolean = when (route) {
        MainDestination.Home.route -> settings.showHome
        MainDestination.Explore.route -> settings.showDiscovery
        MainDestination.Video.route -> settings.showVideo
        else -> true
    }

    fun setVisibilityForRoute(route: String, visible: Boolean) {
        onSetVisible(route, visible)
        if (!visible) {
            val item = navigationItems.find { it.route == route } ?: return
            navigationItems = navigationItems.filter { it.route != route } + item
            onSetOrder(navigationItems.joinToString(",") { it.route })
        }
    }

    // 💡 将可见项和隐藏项合并为一个列表，避免 LazyColumn 渲染与拖拽索引错位
    val orderedItems = navigationItems.filter { isRouteVisible(it.route) } +
            navigationItems.filter { !isRouteVisible(it.route) }
            
    val visibleItems = orderedItems.filter { isRouteVisible(it.route) }
    val selectedDefault = settings.defaultHomePage.takeIf { route ->
        visibleItems.any { it.route == route }
    } ?: MainDestination.Bookshelf.route

    AppModalBottomSheet(
        show = show,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.main_navigation_settings),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            CompactDropdownSettingItem(
                title = stringResource(R.string.default_home_page),
                selectedValue = selectedDefault,
                displayEntries = visibleItems.map { stringResource(it.labelId) }.toTypedArray(),
                entryValues = visibleItems.map { it.route }.toTypedArray(),
                onValueChange = onSetDefault,
            )
            CompactDropdownSettingItem(
                title = stringResource(R.string.nav_label_mode),
                selectedValue = settings.labelVisibilityMode,
                displayEntries = stringArrayResource(R.array.label_vis_mode),
                entryValues = stringArrayResource(R.array.label_vis_mode_value),
                onValueChange = onSetLabelVisibilityMode,
            )
            Spacer(modifier = Modifier.padding(bottom = 4.dp))
            val customIconCount = listOf(
                settings.navIconHome,
                settings.navIconBookshelf,
                settings.navIconExplore,
                settings.navIconVideo, // 替换 Rss
                settings.navIconMy,
                settings.navIconHomeSelected,
                settings.navIconBookshelfSelected,
                settings.navIconExploreSelected,
                settings.navIconVideoSelected, // 替换 Rss
                settings.navIconMySelected,
            ).count { it.isNotEmpty() }
            
            CompactClickableSettingItem(
                title = stringResource(R.string.theme_config_nav_icons),
                description = if (customIconCount > 0) {
                    stringResource(R.string.theme_config_nav_icons_custom_count, customIconCount)
                } else {
                    stringResource(R.string.theme_config_nav_icons_default)
                },
                onClick = { showNavigationIcons = true },
            )
            Spacer(modifier = Modifier.padding(bottom = 4.dp))
            
            LazyColumn(
                state = navigationListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(
                    items = orderedItems,
                    key = { it.route },
                ) { destination ->
                    val isVisible = isRouteVisible(destination.route)
                    ReorderableSelectionItem(
                        state = reorderableState,
                        key = destination.route,
                        reorderIndex = orderedItems.indexOf(destination),
                        reorderItemCount = orderedItems.size,
                        onMoveItem = { from, to ->
                            val fromItem = orderedItems[from]
                            val toItem = orderedItems[to]
                            navigationItems = navigationItems.toMutableList().apply {
                                move(indexOf(fromItem), indexOf(toItem))
                            }
                            onSetOrder(navigationItems.joinToString(",") { it.route })
                        },
                        title = stringResource(destination.labelId),
                        isEnabled = isVisible,
                        canReorder = isVisible,
                        containerColor = LegadoTheme.colorScheme.onSheetContent,
                        onEnabledChange = { enabled ->
                            setVisibilityForRoute(destination.route, enabled)
                        },
                    )
                }
            }
        }
    }

    NavIconManageSheet(
        show = showNavigationIcons,
        settings = settings,
        onDismissRequest = { showNavigationIcons = false },
        onSelectIcon = onRequestNavigationIcon,
        onClearIcon = onClearNavigationIcon,
    )
}