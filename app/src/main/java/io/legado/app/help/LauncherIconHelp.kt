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
 * 强制启用第 7 个图标（对应代码里的 Launcher4），禁用其他所有图标。
 */
object LauncherIconHelp {
    private val packageManager: PackageManager = appCtx.packageManager

    fun changeIcon(icon: String?) {
        // 强制指定使用 Launcher4
        val targetComponent = ComponentName(appCtx, Launcher4::class.java.name)
        val mainActivityComponent = ComponentName(appCtx, MainActivity::class.java.name)
        
        val disabledComponents = listOf(
            ComponentName(appCtx, LauncherW::class.java.name),
            ComponentName(appCtx, Launcher0::class.java.name),
            ComponentName(appCtx, Launcher1::class.java.name),
            ComponentName(appCtx, Launcher2::class.java.name),
            ComponentName(appCtx, Launcher3::class.java.name),
            ComponentName(appCtx, Launcher5::class.java.name),
            ComponentName(appCtx, Launcher6::class.java.name)
        )

        try {
            // 1. 启用 Launcher4
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
            
            // 3. 禁用其他所有 Launcher 图标
            disabledComponents.forEach {
                packageManager.setComponentEnabledSetting(
                    it,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}