package io.legado.app.help.storage

import android.content.Context
import io.legado.app.constant.AppLog
import io.legado.app.help.AppWebDav
import io.legado.app.help.config.AppConfigStore
import io.legado.app.utils.GSON
import io.legado.app.utils.GlobalToast
import io.legado.app.utils.getPrefLong
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefLong
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import splitties.init.appCtx
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object WebDavSyncManager {

    private const val TAG = "WebDavSyncManager"
    private const val SYNC_FILE_VIDEO = "video_settings.json"
    private const val SYNC_FILE_BOOKMARK = "bookmark.json"
    private const val SYNC_FILE_MARKING = "bookMarking.json"

    private const val PREF_ROLE = "sync_device_role" // "master" or "slave"
    private const val PREF_LAST_SYNC_TIME = "sync_last_sync_time"
    private const val PREF_LAST_CLOUD_MODIFIED = "sync_last_cloud_modified"

    const val ROLE_MASTER = "master"
    const val ROLE_SLAVE = "slave"

    private val syncFiles = listOf(SYNC_FILE_VIDEO, SYNC_FILE_BOOKMARK, SYNC_FILE_MARKING)

    fun getDeviceRole(): String = appCtx.getPrefString(PREF_ROLE, ROLE_MASTER)

    fun setDeviceRole(role: String) {
        appCtx.putPrefString(PREF_ROLE, role)
    }

    suspend fun sync(context: Context) {
        withContext(Dispatchers.IO) {
            try {
                val role = getDeviceRole()
                val lastSyncTime = appCtx.getPrefLong(PREF_LAST_SYNC_TIME, 0L)

                for (fileName in syncFiles) {
                    val cloudModified = getCloudLastModified(fileName) ?: continue
                    val localModified = getLocalLastModified(fileName)

                    if (cloudModified <= lastSyncTime && localModified <= lastSyncTime) continue

                    when {
                        cloudModified > lastSyncTime && role == ROLE_SLAVE -> {
                            downloadFile(fileName)
                            GlobalToast.show("自动同步成功：已拉取主设备数据")
                        }
                        cloudModified > lastSyncTime && role == ROLE_MASTER -> {
                            // 主设备发现云端更新（可能来自备用机手动上传）
                            // 由调用方（设置页或启动流程）弹出确认框
                        }
                        localModified > lastSyncTime && role == ROLE_MASTER -> {
                            uploadFile(fileName)
                            GlobalToast.show("自动同步成功：已备份至云端")
                        }
                    }
                }

                appCtx.putPrefLong(PREF_LAST_SYNC_TIME, System.currentTimeMillis())
            } catch (e: Exception) {
                AppLog.put("自动同步失败: ${e.localizedMessage}")
                GlobalToast.show("同步失败，请检查网络")
            }
        }
    }

    suspend fun manualUpload(context: Context) {
        withContext(Dispatchers.IO) {
            try {
                for (fileName in syncFiles) {
                    if (File(backupTempPath(fileName)).exists()) {
                        uploadFile(fileName)
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

    private fun backupTempPath(fileName: String): String =
        appCtx.filesDir.resolve("backup_temp/$fileName").absolutePath

    private fun getLocalLastModified(fileName: String): Long {
        val f = File(backupTempPath(fileName))
        return if (f.exists()) f.lastModified() else 0L
    }

    private fun getCloudLastModified(fileName: String): Long? {
        return try {
            val baseUrl = AppWebDav.getWebDavUrl() ?: return null
            val dir = AppWebDav.getWebDavDir() ?: return null
            val url = "$baseUrl/$dir/$fileName"
            val client = OkHttpClient()
            val request = Request.Builder()
                .url(url)
                .method("PROPFIND", """<?xml version="1.0" encoding="utf-8" ?>
                    <D:propfind xmlns:D="DAV:"><D:prop><D:getlastmodified/></D:prop></D:propfind>"""
                    .toRequestBody("application/xml".toMediaType()))
                .header("Depth", "0")
                .apply { AppWebDav.addAuthHeaders(this) }
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val match = Regex("<D:getlastmodified>(.*?)</D:getlastmodified>").find(body)
                match?.let {
                    val dateStr = it.groupValues[1]
                    SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(dateStr)?.time
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun downloadFile(fileName: String) {
        val baseUrl = AppWebDav.getWebDavUrl() ?: return
        val dir = AppWebDav.getWebDavDir() ?: return
        val url = "$baseUrl/$dir/$fileName"
        val client = OkHttpClient()
        val request = Request.Builder().url(url)
            .apply { AppWebDav.addAuthHeaders(this) }
            .build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val content = response.body?.string() ?: return
                val file = File(backupTempPath(fileName))
                file.parentFile?.mkdirs()
                file.writeText(content)
            }
        }
    }

    private fun uploadFile(fileName: String) {
        val baseUrl = AppWebDav.getWebDavUrl() ?: return
        val dir = AppWebDav.getWebDavDir() ?: return
        val url = "$baseUrl/$dir/$fileName"
        val file = File(backupTempPath(fileName))
        if (!file.exists()) return
        val client = OkHttpClient()
        val request = Request.Builder().url(url)
            .put(file.readText().toRequestBody("application/json".toMediaType()))
            .apply { AppWebDav.addAuthHeaders(this) }
            .build()
        client.newCall(request).execute().use { it }
    }
}