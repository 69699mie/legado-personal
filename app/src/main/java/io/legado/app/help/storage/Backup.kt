package io.legado.app.help.storage

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.domain.gateway.BackupSettingsGateway
import io.legado.app.domain.gateway.ReadStyleGateway
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.BackupToastGate
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfigStore
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfigStore
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.BookCover
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.GlobalToast
import io.legado.app.utils.LogUtils
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.createFolderIfNotExist
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.outputStream
import io.legado.app.utils.writeToOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 备份
 */
object Backup {

    private val readStyleGateway: ReadStyleGateway
        get() = GlobalContext.get().get()

    private val backupSettingsGateway: BackupSettingsGateway
        get() = GlobalContext.get().get()

    val backupPath: String by lazy {
        appCtx.filesDir.getFile("backup").createFolderIfNotExist().absolutePath
    }
    val zipFilePath = "${appCtx.externalFiles.absolutePath}${File.separator}tmp_backup.zip"

    private const val TAG = "Backup"

    private val backupFileNames by lazy {
        arrayOf(
            "bookshelf.json",
            "bookmark.json",
            "bookMarking.json",
            "bookGroup.json",
            "bookSource.json",
            "rssSources.json",
            "rssStar.json",
            "replaceRule.json",
            "readRecord.json",
            "readRecordDetail.json",
            "readRecordSession.json",
            "searchHistory.json",
            "sourceSub.json",
            "txtTocRule.json",
            "httpTTS.json",
            "keyboardAssists.json",
            "dictRule.json",
            "homepageModules.json",
            "homepageCustomSets.json",
            "highlightRule.json",
            "highlightTagRule.json",
            "tagGroupRule.json",
            "servers.json",
            "video_settings.json",
            "videoScraped.json",
            DirectLinkUpload.ruleFileName,
            ReadBookConfig.configFileName,
            ReadBookConfig.shareConfigFileName,
            ThemeConfigStore.configFileName,
            BookCover.configFileName,
            "config.xml"
        )
    }

    private fun getNowZipFileName(): String {
        val backupDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(Date(System.currentTimeMillis()))
        val deviceName = backupSettingsGateway.currentSettings.webDavDeviceName
        return if (deviceName.isNotBlank()) {
            "backup${backupDate}-${deviceName}.zip"
        } else {
            "backup${backupDate}.zip"
        }.normalizeFileName()
    }

    private fun shouldBackup(): Boolean {
        val intervalHours = appCtx.getPrefInt("backup_interval_hours", 24)
        if (intervalHours == 0) return false 
        if (intervalHours == -1) return true 

        val lastBackup = LocalConfig.lastBackup
        val intervalMillis = TimeUnit.HOURS.toMillis(intervalHours.toLong())
        return lastBackup + intervalMillis < System.currentTimeMillis()
    }

    /** 上次备份包大小（字节），供设置页展示 */
    @Volatile
    var lastBackupSize: Long = 0L
        private set

    private const val STATUS_PREF = "backup_status"

    /**
     * 自动备份：遵循设置里的备份间隔（App 启动时触发）
     */
    fun autoBack(context: Context) {
        if (!shouldBackup()) return
        Coroutine.async { runAutoBackup(context, ignoreInterval = false, force = true) }
    }

    /**
     * 立即备份：忽略间隔与节流，仅比对数据指纹
     * 用于切后台/退出/离开阅读播放界面时的收尾
     */
    fun backupNow(context: Context) {
        Coroutine.async { runAutoBackup(context, ignoreInterval = true, force = true) }
    }

    /** 指纹有变化才备份（前台定时调度用，受 30 分钟上传节流约束） */
    fun backupIfChanged(context: Context) {
        Coroutine.async {
            val fingerprint = buildDataFingerprint()
            if (fingerprint == LocalConfig.lastBackupFingerprint) return@async
            runAutoBackup(context, ignoreInterval = true, force = false)
        }
    }

    /** 上次备份失败时间，失败后先退避一段时间，避免每两分钟刷一次失败提示 */
    @Volatile
    private var lastFailTime = 0L

    private const val FAIL_BACKOFF_MS = 10 * 60 * 1000L

    /** 上次成功上传时间，前台轮询受此节流，避免看片时每两分钟传一次整包 */
    @Volatile
    private var lastUploadTime = 0L

    private const val UPLOAD_THROTTLE_MS = 30 * 60 * 1000L

    /** 云端有更新待用户处理时，暂停自动上传，避免旧数据覆盖云端新数据 */
    @Volatile
    private var suppressUntil = 0L

    fun suppressAutoBack(durationMs: Long = 30 * 60 * 1000L) {
        suppressUntil = System.currentTimeMillis() + durationMs
    }

    fun clearSuppress() {
        suppressUntil = 0L
    }

    private suspend fun runAutoBackup(context: Context, ignoreInterval: Boolean, force: Boolean = false) {
        BackupRestoreLock.withLock {
            if (System.currentTimeMillis() < suppressUntil) {
                LogUtils.d(TAG, "云端有更新待处理，暂停自动上传")
                return@withLock
            }
            if (!ignoreInterval && !shouldBackup()) return@withLock
            if (System.currentTimeMillis() - lastFailTime < FAIL_BACKOFF_MS) {
                LogUtils.d(TAG, "上次备份失败，退避中")
                return@withLock
            }
            if (!force && System.currentTimeMillis() - lastUploadTime < UPLOAD_THROTTLE_MS) {
                LogUtils.d(TAG, "距上次上传不足 30 分钟，等下一次")
                return@withLock
            }
            val settings = backupSettingsGateway.currentSettings
            if (settings.webDavAccount.isBlank() || settings.webDavPassword.isBlank()) {
                saveBackupStatus(false, "未配置 WebDAV 账号，已跳过")
                return@withLock
            }
            val fingerprint = buildDataFingerprint()
            if (fingerprint == LocalConfig.lastBackupFingerprint) {
                LogUtils.d(TAG, "数据无变化，跳过备份")
                return@withLock
            }
            // 云优先：上传前先确认云端有没有比本机更新的备份（时间 + 内容指纹），
            // 避免旧数据把另一台设备刚推上去的新备份覆盖掉
            if (cloudHasNewerBackup()) {
                suppressAutoBack(UPLOAD_THROTTLE_MS)
                saveBackupStatus(false, "云端有更新的备份，已暂停上传")
                showBackupToast("云端有更新的备份，已暂停上传，可到「备份与恢复」确认")
                return@withLock
            }
            try {
                // 只上传云端，本地不留包
                val uploaded = backup(context, null, "webdav")
                if (uploaded) {
                    LocalConfig.lastBackup = System.currentTimeMillis()
                    LocalConfig.lastBackupFingerprint = fingerprint
                    lastUploadTime = System.currentTimeMillis()
                    saveBackupStatus(true, "OK")
                    showBackupToast("备份成功，已上传云端")
                } else {
                    lastFailTime = System.currentTimeMillis()
                    saveBackupStatus(false, "上传云端失败，下次会重试")
                    showBackupToast("备份失败：上传云端失败")
                }
            } catch (e: Exception) {
                lastFailTime = System.currentTimeMillis()
                AppLog.put("自动备份失败\n${e.localizedMessage}")
                saveBackupStatus(false, e.localizedMessage ?: "未知错误")
                showBackupToast("备份失败：${e.localizedMessage ?: "未知错误"}")
            }
        }
    }

    /**
     * 云端是否比本机更新：比对云端 backup_meta.json 的时间与内容指纹。
     * 只比时间戳、内容一样时不算"新数据"（那是本机自己推的）。
     */
    private suspend fun cloudHasNewerBackup(): Boolean {
        val raw = runCatching { AppWebDav.downloadBackupMeta() }.getOrNull() ?: return false
        val meta = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: return false
        val remoteTime = meta.optLong("time", 0L)
        if (remoteTime <= 0L) return false
        val remoteContent = meta.optString("content", "")
        if (remoteContent.isBlank()) return false
        val localContent = runCatching { contentFingerprint() }.getOrDefault("")
        if (localContent.isBlank()) return false
        if (remoteContent == localContent) return false
        val lastLocalOk = LocalConfig.lastBackup
        if (remoteTime <= lastLocalOk) return false
        return true
    }

    /**
     * 内容指纹：只反映"内容数据"，不含本机设置
     * 用来判断另一台设备的云端备份与本机是不是同一份数据
     */
    suspend fun contentFingerprint(): String {
        val books = appDb.bookDao.all
        return buildString {
            append(books.size).append('|')
            append(books.sumOf { it.durChapterTime }).append('|')
            append(appDb.bookmarkDao.all.size).append('|')
            append(appDb.bookMarkingDao.all.size).append('|')
            append(appDb.bookSourceDao.all.size).append('|')
            append(appDb.readRecordDao.all.size).append('|')
            append(appDb.videoScrapedDao.getAll().size)
        }
    }

    /**
     * 数据指纹：记录数 + 阅读进度总和 + 设置哈希，用来判断"有没有变化"
     */
    suspend fun buildDataFingerprint(): String {
        val books = appDb.bookDao.all
        return buildString {
            append(books.size).append('|')
            append(books.sumOf { it.durChapterTime }).append('|')
            append(appDb.bookmarkDao.all.size).append('|')
            append(appDb.bookMarkingDao.all.size).append('|')
            append(appDb.bookGroupDao.all.size).append('|')
            append(appDb.bookSourceDao.all.size).append('|')
            append(appDb.readRecordDao.all.size).append('|')
            append(appDb.videoScrapedDao.getAll().size).append('|')
            append(AppConfigStore.preferences.asMap().hashCode()).append('|')
            append(appCtx.getSharedPreferences("video_settings", Context.MODE_PRIVATE).all.hashCode())
        }
    }

    private fun saveBackupStatus(success: Boolean, message: String) {
        appCtx.getSharedPreferences(STATUS_PREF, Context.MODE_PRIVATE).edit()
            .putBoolean("last_ok", success)
            .putLong("last_time", System.currentTimeMillis())
            .putLong("last_size", lastBackupSize)
            .putString("last_message", message)
            .apply()
    }

    /** 设置页展示用 */
    fun backupStatusSummary(): String {
        val pref = appCtx.getSharedPreferences(STATUS_PREF, Context.MODE_PRIVATE)
        val time = pref.getLong("last_time", 0L)
        if (time <= 0L) return "尚未自动备份（配好 WebDAV 账号后自动开始）"
        val ok = pref.getBoolean("last_ok", false)
        val size = pref.getLong("last_size", 0L)
        val sizeText = if (size > 0) "${size / 1024} KB" else "—"
        val timeText = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(time))
        return if (ok) {
            "上次备份：$timeText 成功（$sizeText）"
        } else {
            "上次备份：$timeText 失败（${pref.getString("last_message", "") ?: ""}）"
        }
    }

    /**
     * 备份提示：阅读页/播放页期间静音，避免打断
     */
    private fun showBackupToast(message: String) {
        if (!BackupToastGate.isQuiet) {
            GlobalToast.show(message)
        }
    }

    /**
     * 恢复前把本机数据存一份快照，保留最近 3 份，用于误恢复后回退
     */
    suspend fun createRestoreSnapshot(context: Context) {
        runCatching {
            val dir = appCtx.filesDir.getFile("restore_snapshot").createFolderIfNotExist()
            backup(context, dir.absolutePath, "local")
            val newest = dir.listFiles()?.filter { it.isFile }
                ?.maxByOrNull { it.lastModified() } ?: return@runCatching
            val target = File(
                dir,
                "before_restore_" +
                    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + ".zip"
            )
            newest.renameTo(target)
            dir.listFiles()
                ?.filter { it.isFile && it.name.startsWith("before_restore_") }
                ?.sortedByDescending { it.name }
                ?.drop(3)
                ?.forEach { it.delete() }
        }.onFailure { AppLog.put("恢复前快照失败\n${it.localizedMessage}") }
    }

    suspend fun backupLocked(context: Context, path: String?, mode: String = "both") {
        BackupRestoreLock.withLock {
            withContext(Dispatchers.IO) {
                backup(context, path, mode)
            }
        }
    }

    private suspend fun backup(context: Context, path: String?, mode: String = "both"): Boolean {
        LogUtils.d(TAG, "开始备份 path:$path")
        val aes = BackupAES()
        FileUtils.delete(backupPath)
        
        writeListToJson(
            appDb.bookDao.all.filterNot { BackupConfig.backupIgnoreLocalBook && it.isLocal },
            "bookshelf.json",
            backupPath,
        )
        
        if (BackupConfig.dbIsNotIgnored("bookmark", true)) {
            writeListToJson(appDb.bookmarkDao.all, "bookmark.json", backupPath)
            writeListToJson(appDb.bookMarkingDao.all, "bookMarking.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("bookGroup", true)) {
            writeListToJson(appDb.bookGroupDao.all, "bookGroup.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("bookSource", true)) {
            writeListToJson(appDb.bookSourceDao.all, "bookSource.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("rssSource", true)) {
            writeListToJson(appDb.rssSourceDao.all, "rssSources.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("rssStar", true)) {
            writeListToJson(appDb.rssStarDao.all, "rssStar.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("replaceRule", true)) {
            writeListToJson(appDb.replaceRuleDao.all, "replaceRule.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("readRecord", true)) {
            writeListToJson(appDb.readRecordDao.all, "readRecord.json", backupPath)
            writeListToJson(appDb.readRecordDao.allDetail, "readRecordDetail.json", backupPath)
            writeListToJson(appDb.readRecordDao.allSession, "readRecordSession.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("searchHistory", true)) {
            writeListToJson(appDb.searchKeywordDao.all, "searchHistory.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("sourceSub", true)) {
            writeListToJson(appDb.ruleSubDao.all, "sourceSub.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("txtTocRule", true)) {
            writeListToJson(appDb.txtTocRuleDao.all, "txtTocRule.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("httpTTS", true)) {
            writeListToJson(appDb.httpTTSDao.all, "httpTTS.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("keyboardAssists", true)) {
            writeListToJson(appDb.keyboardAssistsDao.all, "keyboardAssists.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("dictRule", true)) {
            writeListToJson(appDb.dictRuleDao.all, "dictRule.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("homepageModules", true)) {
            writeListToJson(appDb.homepageModuleDao.getAll(), "homepageModules.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("homepageCustomSets", true)) {
            writeListToJson(
                appDb.homepageCustomSetDao.getAll(),
                "homepageCustomSets.json",
                backupPath
            )
        }
        if (BackupConfig.dbIsNotIgnored("highlightRule", true)) {
            writeListToJson(appDb.highlightRuleDao.getAll(), "highlightRule.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("highlightTagRule", true)) {
            writeListToJson(appDb.highlightTagRuleDao.getAll(), "highlightTagRule.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("tagGroupRule", true)) {
            writeListToJson(appDb.tagGroupRuleDao.getAll(), "tagGroupRule.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("server", true)) {
            GSON.toJson(appDb.serverDao.all).let { json ->
                aes.runCatching {
                    encryptBase64(json)
                }.getOrDefault(json).let {
                    FileUtils.createFileIfNotExist(backupPath + File.separator + "servers.json")
                        .writeText(it)
                }
            }
        }
        
        currentCoroutineContext().ensureActive()
        if (!BackupConfig.backupIgnoreReadConfig) {
            readStyleGateway.exportConfigsJson().let {
                FileUtils.createFileIfNotExist(backupPath + File.separator + ReadBookConfig.configFileName)
                    .writeText(it)
            }
            readStyleGateway.exportShareConfigJson().let {
                FileUtils.createFileIfNotExist(backupPath + File.separator + ReadBookConfig.shareConfigFileName)
                    .writeText(it)
            }
        }
        if (!BackupConfig.backupIgnoreThemeConfig) {
            GSON.toJson(ThemeConfigStore.configList).let {
                FileUtils.createFileIfNotExist(backupPath + File.separator + ThemeConfigStore.configFileName)
                    .writeText(it)
            }
        }
        DirectLinkUpload.getConfig()?.let {
            FileUtils.createFileIfNotExist(backupPath + File.separator + DirectLinkUpload.ruleFileName)
                .writeText(GSON.toJson(it))
        }
        if (!BackupConfig.backupIgnoreCoverConfig) {
            BookCover.getConfig()?.let {
                FileUtils.createFileIfNotExist(backupPath + File.separator + BookCover.configFileName)
                    .writeText(GSON.toJson(it))
            }
        }

        val videoPrefs = appCtx.getSharedPreferences("video_settings", Context.MODE_PRIVATE)
        val videoMap = videoPrefs.all
        if (videoMap.isNotEmpty()) {
            FileUtils.createFileIfNotExist(backupPath + File.separator + "video_settings.json")
                .writeText(GSON.toJson(videoMap))
        }

        if (BackupConfig.dbIsNotIgnored("videoScraped", true)) {
            val scrapedList = appDb.videoScrapedDao.getAll()
            if (scrapedList.isNotEmpty()) {
                writeListToJson(scrapedList, "videoScraped.json", backupPath)
            }
        }

        currentCoroutineContext().ensureActive()
        val configMap = AppConfigStore.preferences.asMap()
            .mapKeys { it.key.name }
            .toMutableMap()
        if (PreferKey.bookshelfSelectedGroupId !in configMap) {
            (configMap[PreferKey.saveTabPosition] as? Long)?.let {
                configMap[PreferKey.bookshelfSelectedGroupId] = it
            }
        }
        if (PreferKey.readBrightnessMode !in configMap) {
            when (val legacy = configMap[PreferKey.showBrightnessView]) {
                is String -> configMap[PreferKey.readBrightnessMode] = legacy
                is Boolean -> configMap[PreferKey.readBrightnessMode] = if (legacy) "1" else "0"
            }
        }
        if (PreferKey.readBrightnessControlPosition !in configMap) {
            when (val legacy = configMap[PreferKey.brightnessVwPos]) {
                is String -> configMap[PreferKey.readBrightnessControlPosition] = legacy
                is Boolean -> configMap[PreferKey.readBrightnessControlPosition] =
                    if (legacy) "1" else "0"
            }
        }
        val xmlBuilder = StringBuilder()
        xmlBuilder.append("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n")
        xmlBuilder.append("<map>\n")
        configMap.forEach { (key, value) ->
            if (key !in setOf(
                    PreferKey.saveTabPosition,
                    PreferKey.showBrightnessView,
                    PreferKey.brightnessVwPos
                )
                && BackupConfig.keyIsNotIgnore(key, true)
            ) {
                val finalValue = if (key == PreferKey.webDavPassword) {
                    aes.runCatching { encryptBase64(value.toString()) }.getOrDefault(value.toString())
                } else value

                when (finalValue) {
                    is String -> xmlBuilder.append("    <string name=\"$key\">${finalValue.replace("&", "&amp;").replace("<", "&lt;")}</string>\n")
                    is Int -> xmlBuilder.append("    <int name=\"$key\" value=\"$finalValue\" />\n")
                    is Long -> xmlBuilder.append("    <long name=\"$key\" value=\"$finalValue\" />\n")
                    is Float -> xmlBuilder.append("    <float name=\"$key\" value=\"$finalValue\" />\n")
                    is Boolean -> xmlBuilder.append("    <boolean name=\"$key\" value=\"$finalValue\" />\n")
                }
            }
        }
        xmlBuilder.append("</map>")
        FileUtils.createFileIfNotExist(backupPath + File.separator + "config.xml")
            .writeText(xmlBuilder.toString())

        currentCoroutineContext().ensureActive()
        val zipFileName = getNowZipFileName()
        val paths = backupFileNames
            .map { File(backupPath, it) }
            .filter(File::isFile)
            .map(File::getAbsolutePath)
        FileUtils.delete(zipFilePath)
        FileUtils.delete(zipFilePath.replace("tmp_", ""))
        val backupFileName = if (backupSettingsGateway.currentSettings.onlyLatestBackup) {
            "backup.zip"
        } else {
            zipFileName
        }
        var uploaded = true
        if (ZipUtils.zipFiles(paths, zipFilePath)) {
            lastBackupSize = File(zipFilePath).length()
            if (mode == "both" || mode == "local") {
                when {
                    path.isNullOrBlank() -> {
                        copyBackup(context.getExternalFilesDir(null)!!, backupFileName)
                    }
                    path.isContentScheme() -> {
                        copyBackup(context, path.toUri(), backupFileName)
                    }
                    else -> {
                        copyBackup(File(path), backupFileName)
                    }
                }
            }
            if (mode == "both" || mode == "webdav") {
                try {
                    // 主槽：所有设备共用同一个文件名，保证各设备拿到的是同一份数据
                    AppWebDav.backUpWebDav(backupFileName)
                    // 云端元信息：时间 + 内容指纹，供别的设备启动时判断"云端到底是不是新数据"
                    runCatching {
                        val metaJson = buildString {
                            append("{\"time\":").append(System.currentTimeMillis())
                            append(",\"content\":\"").append(contentFingerprint()).append("\"}")
                        }
                        AppWebDav.uploadBackupMeta(metaJson)
                    }
                    // 历史槽：按日期另存一份，保留最近 7 天用于回滚
                    if (backupFileName != zipFileName) {
                        AppWebDav.backUpWebDavHistory(zipFileName)
                    }
                    AppWebDav.cleanWebDavHistory(7)
                } catch (e: Exception) {
                    uploaded = false
                    AppLog.put("上传备份至webdav失败\n$e", e)
                }
            }
        } else {
            uploaded = false
        }
        
        // 🌟 新增：提取核心文件供多设备同步使用
        WebDavSyncManager.prepareLocalSyncFiles(backupPath)

        FileUtils.delete(backupPath)
        FileUtils.delete(zipFilePath)
        currentCoroutineContext().ensureActive()
        readStyleGateway.allBackgroundImagePaths().map {
            if (it.contains(File.separator)) {
                File(it)
            } else {
                appCtx.externalFiles.getFile("bg", it)
            }
        }.let {
            AppWebDav.upBgs(it.toTypedArray())
        }
        return uploaded
    }

    private suspend fun writeListToJson(list: List<Any>, fileName: String, path: String) {
        currentCoroutineContext().ensureActive()
        withContext(Dispatchers.IO) {
            if (list.isNotEmpty()) {
                LogUtils.d(TAG, "阅读备份 $fileName 列表大小 ${list.size}")
                val file = FileUtils.createFileIfNotExist(path + File.separator + fileName)
                file.outputStream().buffered().use {
                    GSON.writeToOutputStream(it, list)
                }
                LogUtils.d(TAG, "阅读备份 $fileName 写入大小 ${file.length()}")
            } else {
                LogUtils.d(TAG, "阅读备份 $fileName 列表为空")
            }
        }
    }

    @Throws(Exception::class)
    @Suppress("SameParameterValue")
    private fun copyBackup(context: Context, uri: Uri, fileName: String) {
        val treeDoc = DocumentFile.fromTreeUri(context, uri)!!
        treeDoc.findFile(fileName)?.delete()
        val fileDoc = treeDoc.createFile("", fileName)
            ?: throw NoStackTraceException("创建文件失败")
        val outputS = fileDoc.openOutputStream()
            ?: throw NoStackTraceException("打开OutputStream失败")
        outputS.use {
            FileInputStream(zipFilePath).use { inputS ->
                inputS.copyTo(outputS)
            }
        }
    }

    @Throws(Exception::class)
    @Suppress("SameParameterValue")
    private fun copyBackup(rootFile: File, fileName: String) {
        FileInputStream(File(zipFilePath)).use { inputS ->
            val file = FileUtils.createFileIfNotExist(rootFile, fileName)
            FileOutputStream(file).use { outputS ->
                inputS.copyTo(outputS)
            }
        }
    }

    fun clearCache() {
        FileUtils.delete(backupPath)
        FileUtils.delete(zipFilePath)
    }
}