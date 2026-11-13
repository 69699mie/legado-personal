package io.legado.app.help.storage

import android.content.Context
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookMarking
import io.legado.app.data.entities.Bookmark
import io.legado.app.domain.gateway.BackupSettingsGateway
import io.legado.app.lib.webdav.Authorization
import io.legado.app.lib.webdav.WebDav
import io.legado.app.utils.GSON
import io.legado.app.utils.GlobalToast
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.getPrefLong
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefLong
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import splitties.init.appCtx
import java.io.File

object WebDavSyncManager {

    private val backupGateway by lazy { GlobalContext.get().get<BackupSettingsGateway>() }

    private val syncTempDir get() = appCtx.filesDir.resolve("sync_temp").apply { mkdirs() }
    
    // 纳入同步的核心小文件
    private val SYNC_FILES = listOf("video_settings.json", "bookmark.json", "bookMarking.json")

    // 设备角色
    const val ROLE_MASTER = "master"
    const val ROLE_SLAVE = "slave"
    private const val PREF_ROLE = "sync_device_role"
    private const val PREF_LAST_SYNC_TIME = "sync_last_sync_time"

    // 专门用于存放同步文件的目录（与原有备份包隔离）
    fun getDeviceRole(): String = appCtx.getPrefString(PREF_ROLE, ROLE_MASTER)
    fun setDeviceRole(role: String) = appCtx.putPrefString(PREF_ROLE, role)

    private fun getSyncUrl(): String {
        val settings = backupGateway.currentSettings
        var url = settings.webDavUrl.trim()
        if (url.isEmpty()) url = "https://dav.jianguoyun.com/dav/"
        if (!url.endsWith("/")) url = "$url/"
        if (settings.webDavDir.isNotBlank()) {
            url = "${url}${settings.webDavDir.trim()}/"
        }
        return "${url}sync/"
    }

    private fun getAuth(): Authorization? {
        val settings = backupGateway.currentSettings
        if (settings.webDavAccount.isBlank() || settings.webDavPassword.isBlank()) return null
        return Authorization(settings.webDavAccount, settings.webDavPassword)
    }

    suspend fun sync(context: Context) {
        withContext(Dispatchers.IO) {
            try {
                val auth = getAuth() ?: return@withContext
                val syncUrl = getSyncUrl()
                WebDav(syncUrl, auth).makeAsDir()

                val role = getDeviceRole()
                val lastSyncTime = appCtx.getPrefLong(PREF_LAST_SYNC_TIME, 0L)
                var hasDownloaded = false

                val cloudFiles = WebDav(syncUrl, auth).listFiles().associateBy { it.displayName }

                for (fileName in SYNC_FILES) {
                    val cloudFile = cloudFiles[fileName]
                    val cloudModified = cloudFile?.lastModify ?: 0L
                    val localFile = File(syncTempDir, fileName)
                    val localModified = if (localFile.exists()) localFile.lastModified() else 0L

                    if (cloudModified <= lastSyncTime && localModified <= lastSyncTime) continue

                    if (cloudModified > localModified && cloudModified > lastSyncTime) {
                        // 云端较新，下载
                        val bytes = WebDav(syncUrl + fileName, auth).download()
                        if (bytes != null) {
                            localFile.writeBytes(bytes)
                            applySyncedFile(fileName, localFile)
                            hasDownloaded = true
                        }
                    } else if (localModified > cloudModified && role == ROLE_MASTER) {
                        // 本地较新且是主设备，上传
                        WebDav(syncUrl + fileName, auth).upload(localFile.readBytes(), "application/json")
                    }
                }

                appCtx.putPrefLong(PREF_LAST_SYNC_TIME, System.currentTimeMillis())
                
                if (hasDownloaded) {
                    GlobalToast.show("自动同步成功：已拉取最新数据")
                }
            } catch (e: Exception) {
                AppLog.put("自动同步失败: ${e.localizedMessage}")
                GlobalToast.show("同步失败，请检查网络")
            }
        }
    }

    suspend fun manualUpload(context: Context) {
        withContext(Dispatchers.IO) {
            try {
                val auth = getAuth() ?: return@withContext
                val syncUrl = getSyncUrl()
                WebDav(syncUrl, auth).makeAsDir()
                
                for (fileName in SYNC_FILES) {
                    val localFile = File(syncTempDir, fileName)
                    if (localFile.exists()) {
                        WebDav(syncUrl + fileName, auth).upload(localFile.readBytes(), "application/json")
                    }
                }
                appCtx.putPrefLong(PREF_LAST_SYNC_TIME, System.currentTimeMillis())
                GlobalToast.show("手动上传成功")
            } catch (e: Exception) {
                AppLog.put("手动上传失败: ${e.localizedMessage}")
                GlobalToast.show("手动上传失败，请检查网络")
            }
        }
    }

    // 备份完成后，把生成的小文件拷到 sync_temp
    fun prepareLocalSyncFiles(backupDir: String) {
        SYNC_FILES.forEach { fileName ->
            val src = File(backupDir, fileName)
            if (src.exists()) {
                val dest = File(syncTempDir, fileName)
                src.copyTo(dest, overwrite = true)
                dest.setLastModified(System.currentTimeMillis())
            }
        }
    }

    // 将下载到的文件应用到本地 (复用 Restore 的类型安全逻辑)
    private fun applySyncedFile(fileName: String, file: File) {
        val LONG_KEYS = setOf("total_video_watch_time", "total_reading_time", "last_backup_time", "book_shelf_last_update_time", "version_code", "read_record_total_time", "backup_interval_hours")
        when (fileName) {
            "video_settings.json" -> {
                val json = file.readText()
                val mapType = object : com.google.gson.reflect.TypeToken<Map<String, Any>>() {}.type
                val videoMap: Map<String, Any> = GSON.fromJson(json, mapType)
                val editor = appCtx.getSharedPreferences("video_settings", Context.MODE_PRIVATE).edit()
                videoMap.forEach { (key, value) ->
                    if (key in LONG_KEYS) {
                        editor.putLong(key, (value as? Number)?.toLong() ?: 0L)
                    } else {
                        when (value) {
                            is String -> editor.putString(key, value)
                            is Boolean -> editor.putBoolean(key, value)
                            is Number -> {
                                val num = value.toDouble()
                                if (num % 1.0 == 0.0) {
                                    if (num >= Int.MIN_VALUE && num <= Int.MAX_VALUE) editor.putInt(key, num.toInt())
                                    else editor.putLong(key, num.toLong())
                                } else editor.putFloat(key, num.toFloat())
                            }
                        }
                    }
                }
                editor.apply()
            }
            "bookmark.json" -> {
                val list = GSON.fromJsonArray<Bookmark>(file.readText()).getOrNull()
                if (list != null) appDb.bookmarkDao.insert(*list.toTypedArray())
            }
            "bookMarking.json" -> {
                val list = GSON.fromJsonArray<BookMarking>(file.readText()).getOrNull()
                if (list != null) appDb.bookMarkingDao.insert(*list.toTypedArray())
            }
        }
    }
}