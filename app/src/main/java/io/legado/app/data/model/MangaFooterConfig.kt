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
    var hideTimeBattery: Boolean = false,
    // 💡 页脚（章节信息部分）偏移（dp，正值向右）
    var footerOffsetXDp: Int = 0,
    // 💡 页脚（章节信息部分）偏移（dp，正值向上；0 = 贴底）
    var footerOffsetYDp: Int = 0,
    // 💡 时间电量的独立偏移
    var timeBatteryOffsetXDp: Int = 0,
    var timeBatteryOffsetYDp: Int = 0,
)