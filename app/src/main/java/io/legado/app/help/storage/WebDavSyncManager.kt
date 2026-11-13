package io.legado.app.help.storage

import android.content.Context
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookMarking
import io.legado.app.data.entities.Bookmark
import io.legado.app.domain.gateway.BackupSettingsGateway
import io.legado.app.lib.webdav.Authorization
import io.legado.app.lib.webdav.WebDav
import io.legado.app.lib.webdav.WebDavFile
import io.legado.app.utils.GSON
import io.legado.app.utils.GlobalToast
import io.legado.app.utils.getPrefLong
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefLong
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.koin.core.context.GlobalContext
import splitties.init.appCtx
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object WebDavSyncManager {

    private val backupGateway by lazy { GlobalContext.get().get<BackupSettingsGateway>() }

    private val syncTempDir: File by lazy {
        appCtx.filesDir.resolve("sync_temp").apply { mkdirs() }
    }

    private val SYNC_FILES = listOf("video_settings.json", "bookmark.json", "bookMarking.json")
    private const val SYNC_META_FILE = "sync_meta.json"
    private const val ROLE_MASTER = "master"
    private const val ROLE_SLAVE = "slave"
    private const val PREF_ROLE = "sync_device_role"

    fun getDeviceRole(): String = appCtx.getPrefString(PREF_ROLE, ROLE_MASTER)
    fun setDeviceRole(role: String) = appCtx.putPrefString(PREF_ROLE, role)

    private fun getSyncUrl(): String {
        val settings = backupGateway.currentSettings
        var url = settings.webDavUrl.trim()
        if (url.isEmpty()) url = "https://dav.jianguoyun.com/dav/"
        if (!url.endsWith("/")) url = "$url/"
        if (settings.webDavDir.isNotBlank()) url = "${url}${settings.webDavDir.trim()}/"
        return "${url}sync/"
    }

    private fun getAuth(): Authorization? {
        val settings = backupGateway.currentSettings
        if (settings.webDavAccount.isBlank() || settings.webDavPassword.isBlank()) return null
        return Authorization(settings.webDavAccount, settings.webDavPassword)
    }

    private fun loadSyncMeta(): JSONObject {
        val file = File(syncTempDir, SYNC_META_FILE)
        if (!file.exists()) return JSONObject()
        return try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun saveSyncMeta(meta: JSONObject) {
        File(syncTempDir, SYNC_META_FILE).writeText(meta.toString())
    }

    private fun getCloudVersion(meta: JSONObject, fileName: String): Long {
        return meta.optJSONObject(fileName)?.optLong("cloudVersion", 0L) ?: 0L
    }

    private fun getLocalVersion(meta: JSONObject, fileName: String): Long {
        return meta.optJSONObject(fileName)?.optLong("localVersion", 0L) ?: 0L
    }

    private fun setCloudVersion(meta: JSONObject, fileName: String, version: Long) {
        val obj = meta.optJSONObject(fileName) ?: JSONObject().also { meta.put(fileName, it) }
        obj.put("cloudVersion", version)
    }

    private fun setLocalVersion(meta: JSONObject, fileName: String, version: Long) {
        val obj = meta.optJSONObject(fileName) ?: JSONObject().also { meta.put(fileName, it) }
        obj.put("localVersion", version)
    }

    suspend fun sync(context: Context) {
        withContext(Dispatchers.IO) {
            try {
                val auth = getAuth() ?: return@withContext
                val syncUrl = getSyncUrl()
                WebDav(syncUrl, auth).makeAsDir()

                val role = getDeviceRole()
                val meta = loadSyncMeta()
                val cloudFiles = WebDav(syncUrl, auth).listFiles()
                    .associateBy { it.displayName }
                var hasDownloaded = false

                for (fileName in SYNC_FILES) {
                    val cloudFile = cloudFiles[fileName]
                    val cloudVersion = if (cloudFile != null) getCloudVersion(meta, fileName) else 0L
                    val localVersion = getLocalVersion(meta, fileName)
                    val localFile = File(syncTempDir, fileName)
                    val localExists = localFile.exists()
                    // 如果云端存在且云端版本号更高，或者本地文件存在且本地版本号更高
                    val cloudNewer = cloudFile != null && cloudVersion > localVersion
                    val localNewer = localExists && localVersion > cloudVersion

                    if (cloudNewer) {
                        // 云端较新，下载并覆盖本地
                        downloadAndApply(syncUrl, fileName, auth, meta)
                        hasDownloaded = true
                    } else if (localNewer && role == ROLE_MASTER) {
                        // 本地较新且是主设备，上传并递增云端版本号
                        uploadFile(syncUrl, fileName, auth, meta)
                    }
                }

                saveSyncMeta(meta)
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

                // 先备份云端旧版本，留后悔药
                val backupDir = File(syncTempDir, "cloud_backup_before_upload").apply { mkdirs() }
                for (fileName in SYNC_FILES) {
                    try {
                        val bytes = WebDav("$syncUrl$fileName", auth).download()
                        if (bytes != null) {
                            File(backupDir, fileName).writeBytes(bytes)
                        }
                    } catch (_: Exception) {}
                }

                val meta = loadSyncMeta()
                for (fileName in SYNC_FILES) {
                    val localFile = File(syncTempDir, fileName)
                    if (localFile.exists()) {
                        // 手动上传：以本地为准，强制覆盖云端，版本号直接取本地版本号 + 1
                        val currentLocalVersion = getLocalVersion(meta, fileName)
                        val newVersion = currentLocalVersion + 1
                        WebDav("$syncUrl$fileName", auth).upload(localFile.readBytes(), "application/json")
                        setCloudVersion(meta, fileName, newVersion)
                        setLocalVersion(meta, fileName, newVersion)
                    }
                }
                saveSyncMeta(meta)
                GlobalToast.show("手动上传成功")
            } catch (e: Exception) {
                AppLog.put("手动上传失败: ${e.localizedMessage}")
                GlobalToast.show("手动上传失败，请检查网络")
            }
        }
    }

    private fun downloadAndApply(syncUrl: String, fileName: String, auth: Authorization, meta: JSONObject) {
        val bytes = WebDav("$syncUrl$fileName", auth).download() ?: return
        val localFile = File(syncTempDir, fileName)
        localFile.writeBytes(bytes)
        val newVersion = getCloudVersion(meta, fileName) + 1
        setCloudVersion(meta, fileName, newVersion)
        setLocalVersion(meta, fileName, newVersion)
        applySyncedFile(fileName, localFile)
    }

    private fun uploadFile(syncUrl: String, fileName: String, auth: Authorization, meta: JSONObject) {
        val localFile = File(syncTempDir, fileName)
        if (!localFile.exists()) return
        WebDav("$syncUrl$fileName", auth).upload(localFile.readBytes(), "application/json")
        val newVersion = getLocalVersion(meta, fileName) + 1
        setCloudVersion(meta, fileName, newVersion)
        setLocalVersion(meta, fileName, newVersion)
    }

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

    private fun applySyncedFile(fileName: String, file: File) {
        val LONG_KEYS = setOf(
            "total_video_watch_time", "total_reading_time", "last_backup_time",
            "book_shelf_last_update_time", "version_code", "read_record_total_time",
            "backup_interval_hours"
        )
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