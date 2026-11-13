package io.legado.app.help.storage

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.legado.app.constant.AppLog
import io.legado.app.utils.GlobalToast
import splitties.init.appCtx

class SyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            WebDavSyncManager.sync(appCtx)
            Result.success()
        } catch (e: Exception) {
            AppLog.put("SyncWorker failed: ${e.localizedMessage}")
            // 同步失败时，GlobalToast 会在主线程弹出提示（如果权限已授予）
            GlobalToast.show("后台同步失败，请检查网络")
            Result.retry()
        }
    }
}