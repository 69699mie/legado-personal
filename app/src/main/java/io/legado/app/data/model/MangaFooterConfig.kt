package io.legado.app.data.model

import androidx.annotation.Keep

@Keep
data class MangaFooterConfig(
    var hideChapterLabel: Boolean = false,
    var hideChapter: Boolean = false,
    var hidePageNumberLabel: Boolean = false,
    var hidePageNumber: Boolean = false,
    var hideProgressRatioLabel: Boolean = false,
    var hideProgressRatio: Boolean = false,
    var footerOrientation: Int = 0,
    var hideFooter: Boolean = false,
    var hideChapterName: Boolean = false,
    // 💡 控制页脚右下角是否显示时间和电量
    var hideTimeBattery: Boolean = false,
    // 💡 页脚水平偏移（dp，-400..400，正值向右）
    var footerOffsetXDp: Int = 0,
    // 💡 页脚垂直偏移（dp，0..800，正值向上；0 = 紧贴屏幕底边）
    var footerOffsetYDp: Int = 0,
)
