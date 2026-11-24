package io.legado.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.utils.LogUtils
import io.legado.app.utils.startForegroundServiceCompat
import io.legado.app.utils.stopService
import splitties.systemservices.notificationManager
import splitties.systemservices.powerManager

/**
 * 一起看保活服务。
 *
 * 真机（小米 Android 16）上熄屏后普通后台进程会被判成缓存进程冻结/挂起，
 * 一起看的心跳和长流一起停掉，另一端就看不到这一端了。
 * 这里用一个前台服务 + PARTIAL_WAKE_LOCK 把进程钉住，熄屏期间通信照常。
 */
class WatchPartyService : BaseService() {

    companion object {
        private const val TAG = "WatchPartyService"
        private const val CHANNEL_ID = "channel_watch_party_quiet"
        private const val LEGACY_CHANNEL_ID = "channel_watch_party"
        private const val NOTIFICATION_ID = 109

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            if (isRunning) return
            val intent = Intent(context, WatchPartyService::class.java)
            context.startForegroundServiceCompat(intent)
        }

        fun stop(context: Context) {
            if (!isRunning) return
            context.stopService<WatchPartyService>()
        }
    }

    private val wakeLock: PowerManager.WakeLock by lazy {
        powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "legado:WatchPartyService",
        ).apply { setReferenceCounted(false) }
    }

    private var wakeLockHeld = false

    /**
     * HyperOS/MIUI 熄屏后会主动回收应用的 partial wakelock（加进电池优化白名单也没用，
     * 40 秒内就能看到 DISABLED），一回收 CPU 就睡、网络跟着挂、心跳发不出去。
     * 这里每 5 秒检查一次，被回收了立刻重新申请，把它按住。
     */
    private val reAcquireRunnable = object : Runnable {
        override fun run() {
            acquireWakeLock(force = true)
            mainHandler.postDelayed(this, 5_000L)
        }
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private fun acquireWakeLock(force: Boolean = false) {
        if (!force && wakeLock.isHeld) {
            wakeLockHeld = true
            return
        }
        runCatching {
            if (wakeLock.isHeld) wakeLock.release()
            wakeLock.acquire()
            wakeLockHeld = true
        }.onFailure { LogUtils.d(TAG, "wakelock 获取失败: ${it.message}") }
    }

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        ensureChannel()
        super.onCreate()
        acquireWakeLock()
        mainHandler.postDelayed(reAcquireRunnable, 5_000L)
        isRunning = true
        LogUtils.d(TAG, "一起看保活服务已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun startForegroundNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_network_check)
            .setContentTitle("一起看")
            .setContentText("正在同步播放进度，熄屏也不断线")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(reAcquireRunnable)
        if (wakeLockHeld) {
            runCatching { wakeLock.release() }
            wakeLockHeld = false
        }
        isRunning = false
        LogUtils.d(TAG, "一起看保活服务已停止")
    }

    /**
     * 前台服务必须有通知，但用户不需要看到它。
     * 用独占的 IMPORTANCE_MIN 渠道：没有状态栏图标、不发声、不弹横幅，
     * 只有下拉到底部「其他通知」里才看得到一行。
     * 旧的可见渠道（channel_watch_party）直接删掉，别留在通知设置页。
     */
    private fun ensureChannel() {
        val manager = notificationManager
        runCatching { manager.deleteNotificationChannel(LEGACY_CHANNEL_ID) }
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "一起看保活",
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = "仅用于熄屏保持一起看连线，不提醒"
            enableLights(false)
            enableVibration(false)
            setShowBadge(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        manager.createNotificationChannel(channel)
    }
}
