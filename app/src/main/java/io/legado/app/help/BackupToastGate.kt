package io.legado.app.help

import java.util.concurrent.atomic.AtomicInteger

/**
 * 阅读/播放界面期间静音备份提示。
 *
 * 这些界面用户注意力在内容上，弹 toast 会直接打断观看；
 * 但备份结果仍会写进设置页的状态行，用户想查随时能查到。
 *
 * 用引用计数而不是布尔值，兼容界面叠加（详情页上再开播放器）。
 */
object BackupToastGate {

    private val counter = AtomicInteger(0)

    val isQuiet: Boolean get() = counter.get() > 0

    fun enter() {
        counter.incrementAndGet()
    }

    fun exit() {
        counter.updateAndGet { if (it > 0) it - 1 else 0 }
    }
}
