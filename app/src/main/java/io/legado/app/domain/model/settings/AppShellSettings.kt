package io.legado.app.domain.model.settings

data class AppShellSettings(
    val themeMode: String = "0",
    val fontScale: Int = 10,
    val composeEngine: String = "material",
    val showHome: Boolean = true,
    val showBookshelf: Boolean = true, // ➕ 补上：原版没有的字段
    val showDiscovery: Boolean = true,
    val showVideo: Boolean = true,     // ➕ 补上：影视字段
    val showMy: Boolean = true,        // ➕ 补上：原版没有的字段
    val showRss: Boolean = true,       // 废弃，防崩溃保留
    val showStatusBar: Boolean = true,
    val swipeAnimation: Boolean = true,
    val predictiveBackEnabled: Boolean = true,
    val showBottomView: Boolean = true,
    val useFloatingBottomBar: Boolean = false,
    val useFloatingBottomBarLiquidGlass: Boolean = false,
    val tabletInterface: String = "auto",
    val labelVisibilityMode: String = "auto",
    val defaultHomePage: String = "bookshelf",
    val mainNavigationOrder: String = "home,bookshelf,explore,video,my",
    val navExtended: Boolean = false,
    val navIconHome: String = "",
    val navIconBookshelf: String = "",
    val navIconExplore: String = "",
    val navIconVideo: String = "",
    val navIconMy: String = "",
    val navIconHomeSelected: String = "",
    val navIconBookshelfSelected: String = "",
    val navIconExploreSelected: String = "",
    val navIconVideoSelected: String = "",
    val navIconMySelected: String = "",
    val navIconRss: String = "",
    val navIconRssSelected: String = "",
    val launcherIcon: String = "ic_launcher",
)
