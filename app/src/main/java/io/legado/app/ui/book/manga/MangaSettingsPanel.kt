package io.legado.app.ui.book.manga

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.book.manga.config.MangaDoublePageMode
import io.legado.app.ui.book.manga.config.MangaScrollMode
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.button.series.MediumTonalButton
import io.legado.app.ui.widget.components.dialog.ColorPickerSheet
import io.legado.app.ui.widget.components.icon.AppIcons
import io.legado.app.ui.widget.components.pager.pagerHeight
import io.legado.app.ui.widget.components.pager.rememberPagerAnimatedHeight
import io.legado.app.ui.widget.components.settingItem.TinyColorSettingItem
import io.legado.app.ui.widget.components.settingItem.TinyDropdownSettingItem
import io.legado.app.ui.widget.components.settingItem.TinySliderSettingItem
import io.legado.app.ui.widget.components.settingItem.TinySwitchSettingItem
import io.legado.app.ui.widget.components.tabRow.CardTabRow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val MangaSettingsPanelContentMaxHeight = 380.dp

private val MangaSettingsTabs = listOf(
    MangaReaderSettingsCategory.READER,
    MangaReaderSettingsCategory.FOOTER,
    MangaReaderSettingsCategory.MENU,
    MangaReaderSettingsCategory.FILTER,
    MangaReaderSettingsCategory.CLICK_ACTIONS,
)

private val MangaReaderSettingsCategory.titleRes: Int
    get() = when (this) {
        MangaReaderSettingsCategory.READER -> R.string.general
        MangaReaderSettingsCategory.FOOTER -> R.string.footer
        MangaReaderSettingsCategory.MENU -> R.string.manga_reader_menu_layout
        MangaReaderSettingsCategory.FILTER -> R.string.manga_reader_filter_short
        MangaReaderSettingsCategory.CLICK_ACTIONS -> R.string.manga_reader_click_area_short
        MangaReaderSettingsCategory.AUTO_READ -> R.string.manga_reader_auto_read
    }

private enum class MangaColorPickerTarget { BACKGROUND, FILTER, MENU_SEED }

@Composable
internal fun MangaSettingsPanel(
    state: MangaReaderUiState,
    onIntent: (MangaReaderIntent) -> Unit,
) {
    val current = state.settingsCategory ?: MangaReaderSettingsCategory.READER
    if (current == MangaReaderSettingsCategory.AUTO_READ) {
        AutoReadSettingsPage(state, onIntent)
        return
    }
    val scope = rememberCoroutineScope()
    val tabIndex = MangaSettingsTabs.indexOf(current).coerceAtLeast(0)
    val pagerState = rememberPagerState(
        initialPage = tabIndex,
        pageCount = { MangaSettingsTabs.size },
    )
    var selectedTab by remember { mutableIntStateOf(tabIndex) }
    var clickScrollCount by remember { mutableIntStateOf(0) }
    var colorPickerTarget by remember { mutableStateOf<MangaColorPickerTarget?>(null) }
    val pageHeights = remember { mutableStateMapOf<Int, Int>() }
    val animatedHeight by rememberPagerAnimatedHeight(pagerState, pageHeights)

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { page ->
                if (clickScrollCount == 0) selectedTab = page
            }
    }
    LaunchedEffect(tabIndex) {
        if (pagerState.currentPage != tabIndex) {
            pagerState.scrollToPage(tabIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MediumTonalButton(
                onClick = { onIntent(MangaReaderIntent.CloseSettings) },
                icon = AppIcons.Back,
                contentDescription = stringResource(R.string.back),
            )
            CardTabRow(
                tabTitles = MangaSettingsTabs.map { stringResource(it.titleRes) },
                selectedTabIndex = selectedTab,
                onTabSelected = { index ->
                    selectedTab = index
                    clickScrollCount++
                    scope.launch {
                        try {
                            pagerState.animateScrollToPage(
                                page = index,
                                animationSpec = tween(
                                    durationMillis = 300,
                                    easing = FastOutSlowInEasing,
                                ),
                            )
                        } finally {
                            clickScrollCount = (clickScrollCount - 1).coerceAtLeast(0)
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        HorizontalPager(
            state = pagerState,
            verticalAlignment = Alignment.Top,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = MangaSettingsPanelContentMaxHeight)
                .clipToBounds()
                .pagerHeight(animatedHeight),
        ) { page ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { size -> pageHeights[page] = size.height },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    when (MangaSettingsTabs[page]) {
                        MangaReaderSettingsCategory.READER -> ReaderSettingsContent(
                            state.settings,
                            onIntent
                        )

                        MangaReaderSettingsCategory.FOOTER -> FooterSettingsContent(
                            state.settings,
                            onIntent
                        )

                        MangaReaderSettingsCategory.MENU -> MenuSettingsContent(
                            settings = state.settings,
                            onPickSeedColor = { colorPickerTarget = MangaColorPickerTarget.MENU_SEED },
                            onIntent = onIntent,
                        )

                        MangaReaderSettingsCategory.FILTER -> FilterSettingsContent(
                            settings = state.settings,
                            onPickBackground = {
                                colorPickerTarget = MangaColorPickerTarget.BACKGROUND
                            },
                            onPickFilter = { colorPickerTarget = MangaColorPickerTarget.FILTER },
                            onIntent = onIntent,
                        )

                        MangaReaderSettingsCategory.CLICK_ACTIONS -> ClickActionsSettingsContent(
                            state.settings,
                            onIntent
                        )

                        MangaReaderSettingsCategory.AUTO_READ -> Unit
                    }
                }
            }
        }
    }

    colorPickerTarget?.let { target ->
        ColorPickerSheet(
            show = true,
            initialColor = when (target) {
                MangaColorPickerTarget.BACKGROUND -> state.settings.backgroundColor.toArgb()
                MangaColorPickerTarget.FILTER -> Color(
                    red = 255 - state.settings.filterRed,
                    green = 255 - state.settings.filterGreen,
                    blue = 255 - state.settings.filterBlue,
                ).toArgb()
                MangaColorPickerTarget.MENU_SEED -> state.settings.menuSeedColor.toArgb()
            },
            onDismissRequest = { colorPickerTarget = null },
            onColorSelected = { argb ->
                val picked = Color(argb)
                val red = (picked.red * 255).roundToInt()
                val green = (picked.green * 255).roundToInt()
                val blue = (picked.blue * 255).roundToInt()
                when (target) {
                    MangaColorPickerTarget.BACKGROUND -> {
                        onIntent(
                            MangaReaderIntent.UpdateSetting(
                                MangaReaderSettingKey.BACKGROUND_RED,
                                red
                            )
                        )
                        onIntent(
                            MangaReaderIntent.UpdateSetting(
                                MangaReaderSettingKey.BACKGROUND_GREEN,
                                green
                            )
                        )
                        onIntent(
                            MangaReaderIntent.UpdateSetting(
                                MangaReaderSettingKey.BACKGROUND_BLUE,
                                blue
                            )
                        )
                    }

                    MangaColorPickerTarget.FILTER -> {
                        onIntent(
                            MangaReaderIntent.UpdateSetting(
                                MangaReaderSettingKey.FILTER_RED,
                                255 - red
                            )
                        )
                        onIntent(
                            MangaReaderIntent.UpdateSetting(
                                MangaReaderSettingKey.FILTER_GREEN,
                                255 - green
                            )
                        )
                        onIntent(
                            MangaReaderIntent.UpdateSetting(
                                MangaReaderSettingKey.FILTER_BLUE,
                                255 - blue
                            )
                        )
                    }

                    MangaColorPickerTarget.MENU_SEED -> onIntent(
                        MangaReaderIntent.UpdateSetting(MangaReaderSettingKey.MENU_SEED_COLOR, argb)
                    )
                }
                colorPickerTarget = null
            },
        )
    }
}

@Composable
private fun AutoReadSettingsPage(
    state: MangaReaderUiState,
    onIntent: (MangaReaderIntent) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MediumTonalButton(
                onClick = { onIntent(MangaReaderIntent.CloseSettings) },
                icon = AppIcons.Back,
                contentDescription = stringResource(R.string.back),
            )
            Text(
                text = stringResource(MangaReaderSettingsCategory.AUTO_READ.titleRes),
                style = LegadoTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            AutoReadSettingsContent(state, onIntent)
        }
    }
}

@Composable
private fun ReaderSettingsContent(
    settings: MangaReaderSettings,
    onIntent: (MangaReaderIntent) -> Unit,
) {
    val isWebtoon = settings.scrollMode == MangaScrollMode.WEBTOON ||
        settings.scrollMode == MangaScrollMode.WEBTOON_WITH_GAP
    val isHorizontalPaged = settings.scrollMode == MangaScrollMode.PAGE_LEFT_TO_RIGHT ||
        settings.scrollMode == MangaScrollMode.PAGE_RIGHT_TO_LEFT
    TinyDropdownSettingItem(
        title = stringResource(R.string.read_type),
        selectedValue = settings.scrollMode.toString(),
        displayEntries = arrayOf(
            stringResource(R.string.webtoon),
            stringResource(R.string.manga_reader_webtoon_gap),
            stringResource(R.string.manga_reader_left_to_right),
            stringResource(R.string.manga_reader_right_to_left),
            stringResource(R.string.manga_reader_top_to_bottom),
        ),
        entryValues = arrayOf(
            MangaScrollMode.WEBTOON,
            MangaScrollMode.WEBTOON_WITH_GAP,
            MangaScrollMode.PAGE_LEFT_TO_RIGHT,
            MangaScrollMode.PAGE_RIGHT_TO_LEFT,
            MangaScrollMode.PAGE_TOP_TO_BOTTOM,
        ).map(Int::toString).toTypedArray(),
        onValueChange = {
            onIntent(MangaReaderIntent.UpdateSetting(MangaReaderSettingKey.SCROLL_MODE, it.toInt()))
        },
    )
    if (isWebtoon) {
        SettingSlider(
            stringResource(R.string.manga_reader_side_padding),
            settings.sidePaddingPercent,
            0..45
        ) {
            onIntent(MangaReaderIntent.UpdateSetting(MangaReaderSettingKey.SIDE_PADDING, it))
        }
    } else {
        SettingDropdown(
            stringResource(R.string.manga_reader_scale_type),
            settings.pageScaleType,
            arrayOf(
                R.string.manga_reader_scale_fit_screen,
                R.string.manga_reader_scale_stretch,
                R.string.manga_reader_scale_fit_width,
                R.string.manga_reader_scale_fit_height,
                R.string.manga_reader_scale_original,
                R.string.manga_reader_scale_smart,
            ),
            MangaReader