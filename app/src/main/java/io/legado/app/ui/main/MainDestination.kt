package io.legado.app.ui.main

import androidx.annotation.StringRes
import io.legado.app.R
import kotlinx.collections.immutable.persistentListOf

sealed class MainDestination(
    val route: String,
    @StringRes val labelId: Int
) {
    object Home : MainDestination("home", R.string.home)
    object Bookshelf : MainDestination("bookshelf", R.string.bookshelf)
    object Explore : MainDestination("explore", R.string.discovery)
    object Video : MainDestination("video", R.string.movie)
    object My : MainDestination("my", R.string.my)

    // 保留这个类防崩溃，但不在列表中展示
    object Rss : MainDestination("rss", R.string.rss)

    companion object {
        // ➕ 彻底移除 Rss，只保留 Video
        val mainDestinations = persistentListOf<MainDestination>(Home, Bookshelf, Explore, Video, My)

        fun ordered(order: String): List<MainDestination> {
            val byRoute = mainDestinations.associateBy { it.route }
            val ordered = order
                .split(',')
                .map(String::trim)
                .map { if (it == "rss") "video" else it } // 将老数据的 rss 自动变为 video
                .distinct()
                .mapNotNull(byRoute::get)
            
            // 补齐缺失的项
            return ordered + mainDestinations.filterNot { it in ordered }
        }
    }
}