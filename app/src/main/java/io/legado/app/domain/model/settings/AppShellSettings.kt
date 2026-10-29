package io.legado.app.domain.model.settings

data class AppShellSettings(
    val themeMode: String = "0",
    val fontScale: Int = 10,
    val composeEngine: String = "material",
    val showHome: Boolean = true,
    val showDiscovery: Boolean = true,
    val showRss: Boolean = true, // 原订阅开关，保留防崩溃
    val showVideo: Boolean = true, // ➕ 新增影视开关
    val showStatusBar: Boolean = true,
    val swipeAnimation: Boolean = true,
    val predictiveBackEnabled: Boolean = true,
    val showBottomView: Boolean = true,
    val useFloatingBottomBar: Boolean = false,
    val useFloatingBottomBarLiquidGlass: Boolean = false,
    val tabletInterface: String = "auto",
    val labelVisibilityMode: String = "auto",
    val defaultHomePage: String = "bookshelf",
    // ➕ 将默认排序里的 rss 替换为 video
    val mainNavigationOrder: String = "home,bookshelf,explore,video,my",
    val navExtended: Boolean = false,
    val navIconHome: String = "",
    val navIconBookshelf: String = "",
    val navIconExplore: String = "",
    val navIconRss: String = "",
    val navIconVideo: String = "", // ➕ 新增
    val navIconMy: String = "",
    val navIconHomeSelected: String = "",
    val navIconBookshelfSelected: String = "",
    val navIconExploreSelected: String = "",
    val navIconRssSelected: String = "",
    val navIconVideoSelected: String = "", // ➕ 新增
    val navIconMySelected: String = "",
    val launcherIcon: String = "ic_launcher",
)