package io.legado.app.help

import android.content.ComponentName
import android.content.pm.PackageManager
import io.legado.app.ui.main.Launcher0
import io.legado.app.ui.main.Launcher1
import io.legado.app.ui.main.Launcher2
import io.legado.app.ui.main.Launcher3
import io.legado.app.ui.main.Launcher4
import io.legado.app.ui.main.Launcher5
import io.legado.app.ui.main.Launcher6
import io.legado.app.ui.main.LauncherW
import io.legado.app.ui.main.MainActivity
import splitties.init.appCtx

/**
 * 修改为：强制启用第 7 个图标（Launcher6），禁用其他所有图标。
 */
object LauncherIconHelp {
    private val packageManager: PackageManager = appCtx.packageManager

    fun changeIcon(icon: String?) {
        // 🌟 无论传入什么参数，强制指定使用 Launcher6
        val targetComponent = ComponentName(appCtx, Launcher6::class.java.name)
        val mainActivityComponent = ComponentName(appCtx, MainActivity::class.java.name)
        
        // 需要禁用的其他 Launcher 列表
        val disabledComponents = listOf(
            ComponentName(appCtx, LauncherW::class.java.name),
            ComponentName(appCtx, Launcher0::class.java.name),
            ComponentName(appCtx, Launcher1::class.java.name),
            ComponentName(appCtx, Launcher2::class.java.name),
            ComponentName(appCtx, Launcher3::class.java.name),
            ComponentName(appCtx, Launcher4::class.java.name),
            ComponentName(appCtx, Launcher5::class.java.name)
        )

        try {
            // 1. 启用 Launcher6
            packageManager.setComponentEnabledSetting(
                targetComponent,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            
            // 2. 禁用默认的 MainActivity 图标
            packageManager.setComponentEnabledSetting(
                mainActivityComponent,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            
            // 3. 禁用其他所有 Launcher 图标（防止用户以前切换过导致桌面残留多个图标）
            disabledComponents.forEach {
                packageManager.setComponentEnabledSetting(
                    it,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        } catch (e: Exception) {
            // 捕获异常防止崩溃
            e.printStackTrace()
        }
    }
}