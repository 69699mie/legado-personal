package io.legado.app.help.storage

import io.legado.app.help.coroutine.Coroutine
import io.legado.app.utils.LogUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import splitties.init.appCtx

/**
 * 数据变更后的自动推送调度。
 *
 * 为什么主力触发放在「前台轮询」而不是「退出时备份」：
 * 1. Doze（息屏待机）下 JobScheduler/WorkManager 不会执行，官方文档明确说明；
 * 2. MIUI 等国产 ROM 有锁屏清理、上滑清理、耗电查杀，退出后进程可能直接消失；
 * 3. 应用长期不活跃会被降进「受限」分桶，后台作业每天只有一次窗口。
 *
 * 前台轮询不受以上限制：只要用户还在 App 里，协程就一定会跑。
 * 每 3 分钟比对一次数据指纹，有变化才上传，避免无意义流量。
 */
object AutoBackupScheduler {

    private const val TAG = "AutoBackupScheduler"
    private const val INTERVAL_MS = 2 * 60 * 1000L

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        Coroutine.async {
            while (isActive) {
                delay(INTERVAL_MS)
                runCatching { Backup.backupIfChanged(appCtx) }
                    .onFailure { LogUtils.e(TAG, "自动推送失败: ${it.localizedMessage}") }
            }
        }
    }
}
