package io.legado.app

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.graphics.scale
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.github.liuyueyi.quick.transfer.constants.TransType
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import com.jeremyliao.liveeventbus.LiveEventBus
import com.jeremyliao.liveeventbus.logger.DefaultLogger
import com.script.rhino.ReadOnlyJavaObject
import com.script.rhino.RhinoScriptEngine
import com.script.rhino.RhinoWrapFactory
import io.legado.app.constant.AppConst.channelIdBookSourceCheck
import io.legado.app.constant.AppConst.channelIdDownload
import io.legado.app.constant.AppConst.channelIdReadAloud
import io.legado.app.constant.AppConst.channelIdWeb
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.entities.rule.ExploreRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.di.appDatabaseModule
import io.legado.app.di.appModule
import io.legado.app.domain.gateway.AppLocaleGateway
import io.legado.app.domain.gateway.AppShellSettingsGateway
import io.legado.app.domain.gateway.BackupSettingsGateway
import io.legado.app.domain.gateway.OtherSettingsGateway
import io.legado.app.domain.gateway.ReadSettingsGateway
import io.legado.app.domain.gateway.ReadStyleGateway
import io.legado.app.domain.gateway.ThemeSettingsGateway
import io.legado.app.help.AppFreezeMonitor
import io.legado.app.help.AppWebDav
import io.legado.app.help.CrashHandler
import io.legado.app.help.DefaultData
import io.legado.app.help.DispatchersMonitor
import io.legado.app.help.LauncherIconHelp
import io.legado.app.help.LifecycleHelp
import io.legado.app.help.RuleBigDataHelp
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfigStore
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfigStore
import io.legado.app.help.config.ThemeConfigStore.applyDayNightInit
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.Cronet
import io.legado.app.help.http.ObsoleteUrlFactory
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.rhino.NativeBaseSource
import io.legado.app.help.source.SourceHelp
import io.legado.app.help.storage.Backup
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.BookCover
import io.legado.app.utils.ChineseUtils
import io.legado.app.utils.FirebaseManager
import io.legado.app.utils.LogUtils
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isDebuggable
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.chromium.base.ThreadUtils
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext.startKoin
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import java.io.File
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.logging.Level

class App : Application(), SingletonImageLoader.Factory {

    private val themeGateway get() = get<ThemeSettingsGateway>()
    private val otherGateway get() = get<OtherSettingsGateway>()
    private val backupGateway get() = get<BackupSettingsGateway>()
    private val readGateway get() = get<ReadSettingsGateway>()

    override fun newImageLoader(context: Context): ImageLoader {
        return get()
    }

    override fun onCreate() {
        AppConfigStore.init(this)
        val legacyLanguage = if (!LocalConfig.appLocaleMigrated) {
            LocalConfig.appLocaleMigrated = true
            AppConfigStore.getString(PreferKey.language) ?: "auto"
        } else null
        startKoin {
            androidContext(this@App)
            modules(appDatabaseModule, appModule)
        }
        @Suppress("DEPRECATION")
        AppConfig.initialize(
            shellGateway = get(),
            themeGateway = get(),
            bookshelfGateway = get(),
            otherGateway = get(),
            backupGateway = get(),
            cacheGateway = get(),
            coverGateway = get(),
            readGateway = get(),
            aloudGateway = get(),
            importBookGateway = get(),
            exportGateway = get(),
        )
        ReadBookConfig.initialize(
            configStore = get(),
            readSettingsGateway = get(),
        )
        if (legacyLanguage != null) {
            get<AppLocaleGateway>().migrateLegacyLanguage(legacyLanguage)
        }
        applyDayNightInit(this)
        if (getPrefString("app_theme", "0") == "12") {
            if (themeGateway.currentSettings.customMode == "accent")
                setTheme(R.style.ThemeOverlay_WhiteBackground)

            val colorImagePath = getPrefString(PreferKey.colorImage)
            if (!colorImagePath.isNullOrBlank()) {
                val file = File(colorImagePath)
                if (file.exists()) {
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    if (bitmap != null) {
                        val colorAccuracy = true
                        val targetWidth = if (colorAccuracy) (bitmap.width / 4).coerceAtMost(256) else 16
                        val targetHeight = if (colorAccuracy) (bitmap.height / 4).coerceAtMost(256) else 16
                        val scaledBitmap = bitmap.scale(targetWidth, targetHeight, false)

                        val options = DynamicColorsOptions.Builder()
                            .setContentBasedSource(scaledBitmap)
                            .build()

                        DynamicColors.applyToActivitiesIfAvailable(this, options)
                        bitmap.recycle()
                    }
                }
            } else {
                DynamicColors.applyToActivitiesIfAvailable(
                    this,
                    DynamicColorsOptions.Builder()
                        .setContentBasedSource(this.primaryColor)
                        .build()
                )
            }
        }
        super.onCreate()
        FirebaseManager.init(this)
        CrashHandler(this)
        if (isDebuggable) {
            ThreadUtils.setThreadAssertsDisabledForTesting(true)
        }
        
        // 🌟 核心修复：全局拦截系统窗口分离异常，防止自由窗口抛掷闪退
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            var isWindowCrash = false
            var cause: Throwable? = throwable
            while (cause != null) {
                if (cause is IllegalArgumentException && cause.message?.contains("not attached to window manager") == true) {
                    isWindowCrash = true
                    break
                }
                cause = cause.cause
            }
            if (isWindowCrash) {
                LogUtils.e("AppCrashHandler", "Intercepted window detach crash: ${throwable.message}")
            } else {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
        
        registerActivityLifecycleCallbacks(LifecycleHelp)
        initRhino()

        // ❌ 已移除 VideoCacheManager，完全由 Room 接管本地缓存
        
        Coroutine.async {
            get<BackupSettingsGateway>().settings
                .map {
                    listOf(it.webDavUrl, it.webDavDir, it.webDavAccount, it.webDavPassword)
                }
                .distinctUntilChanged()
                .collect { AppWebDav.upConfig() }
        }
        Coroutine.async {
            get<AppShellSettingsGateway>().settings
                .map { it.themeMode }
                .distinctUntilChanged()
                .collect {
                    withContext(Main) { ThemeConfigStore.initNightMode() }
                }
        }
        Coroutine.async {
            LogUtils.init(this@App)
            LogUtils.d("App", "onCreate")
            LogUtils.logDeviceInfo()
            Cronet.preDownload()
            createNotificationChannels()
            LiveEventBus.config()
                .lifecycleObserverAlwaysActive(true)
                .autoClear(false)
                .enableLogger(BuildConfig.DEBUG || otherGateway.currentSettings.recordLog)
                .setLogger(EventLogger())
            DefaultData.upVersion()
            AppFreezeMonitor.init(this@App)
            DispatchersMonitor.init()
            URL.setURLStreamHandlerFactory(ObsoleteUrlFactory(okHttpClient))
            launch { installGmsTlsProvider(appCtx) }
            BookCover.toString()
            appDb.cacheDao.clearDeadline(System.currentTimeMillis())
            if (getPrefBoolean(PreferKey.autoClearExpired, true)) {
                val clearTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
                appDb.searchBookDao.clearExpired(clearTime)
            }
            RuleBigDataHelp.clearInvalid()
            BookHelp.clearInvalidCache()
            Backup.clearCache()
            get<ReadStyleGateway>().clearUnusedBackgrounds()
            ThemeConfigStore.clearBg()
            when (readGateway.currentSettings.chineseConverterType) {
                1 -> {
                    ChineseUtils.fixT2sDict()
                    ChineseUtils.preLoad(true, TransType.TRADITIONAL_TO_SIMPLE)
                }
                2 -> ChineseUtils.preLoad(true, TransType.SIMPLE_TO_TRADITIONAL)
            }
            SourceHelp.adjustSortNumber()
            if (backupGateway.currentSettings.syncBookProgress) {
                AppWebDav.upConfig()
                AppWebDav.downloadAllBookProgress()
            }
        }

        if (!BuildConfig.DEBUG) {
            LauncherIconHelp.changeIcon("Launcher4")
        }
    }

    private fun installGmsTlsProvider(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return
        }
        try {
            val gmsPackageName = "com.google.android.gms"
            val appInfo = packageManager.getApplicationInfo(gmsPackageName, 0)
            if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0) {
                return
            }
            val gms = context.createPackageContext(
                gmsPackageName,
                CONTEXT_INCLUDE_CODE or CONTEXT_IGNORE_SECURITY
            )
            gms.classLoader
                .loadClass("com.google.android.gms.common.security.ProviderInstallerImpl")
                .getMethod("insertProvider", Context::class.java)
                .invoke(null, gms)
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
    }

    private fun createNotificationChannels() {
        val downloadChannel = NotificationChannel(
            channelIdDownload,
            getString(R.string.action_download),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val readAloudChannel = NotificationChannel(
            channelIdReadAloud,
            getString(R.string.read_aloud),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val bookSourceCheckChannel = NotificationChannel(
            channelIdBookSourceCheck,
            getString(R.string.check_book_source),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val webChannel = NotificationChannel(
            channelIdWeb,
            getString(R.string.web_service),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        notificationManager.createNotificationChannels(
            listOf(
                downloadChannel,
                bookSourceCheckChannel,
                readAloudChannel,
                webChannel
            )
        )
    }

    private fun initRhino() {
        @Suppress("UNUSED_EXPRESSION")
        RhinoScriptEngine
        RhinoWrapFactory.register(BookSource::class.java, NativeBaseSource.factory)
        RhinoWrapFactory.register(RssSource::class.java, NativeBaseSource.factory)
        RhinoWrapFactory.register(HttpTTS::class.java, NativeBaseSource.factory)
        RhinoWrapFactory.register(ExploreRule::class.java, ReadOnlyJavaObject.factory)
        RhinoWrapFactory.register(SearchRule::class.java, ReadOnlyJavaObject.factory)
        RhinoWrapFactory.register(BookInfoRule::class.java, ReadOnlyJavaObject.factory)
        RhinoWrapFactory.register(ContentRule::class.java, ReadOnlyJavaObject.factory)
        RhinoWrapFactory.register(BookChapter::class.java, ReadOnlyJavaObject.factory)
        RhinoWrapFactory.register(Book.ReadConfig::class.java, ReadOnlyJavaObject.factory)
    }

    class EventLogger : DefaultLogger() {

        override fun log(level: Level, msg: String) {
            super.log(level, msg)
            LogUtils.d(TAG, msg)
        }

        override fun log(level: Level, msg: String, th: Throwable?) {
            super.log(level, msg, th)
            LogUtils.d(TAG, "$msg\n${th?.stackTraceToString()}")
        }

        companion object {
            private const val TAG = "[LiveEventBus]"
        }
    }

    companion object {
        init {
            if (BuildConfig.DEBUG) {
                System.setProperty("kotlinx.coroutines.debug", "on")
            }
        }
    }
}