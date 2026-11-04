package com.sharefiles.app

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.sharefiles.app.core.AppLog
import com.sharefiles.app.ui.formatSpeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Keeps the process, the CPU and the WiFi radio awake while files are moving. */
class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("Đang chuẩn bị chuyển file", 0, 0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        @Suppress("DEPRECATION")
        wifiLock = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "sharefiles:transfer")
            .apply { acquire() }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sharefiles:transfer")
            .apply { acquire(MAX_LOCK_MILLIS) }

        watcher = scope.launch { watch() }
    }

    private suspend fun watch() {
        val queue = (application as ShareFilesApp).state.queue
        val manager = getSystemService(NotificationManager::class.java)
        while (true) {
            if (!queue.hasActive.value) {
                stopSelf()
                return
            }
            val items = queue.items.value
            val active = items.filter { it.state != TransferState.DONE && it.state != TransferState.FAILED }
            val total = active.sumOf { it.size }
            val done = active.sumOf { it.transferred }
            val speed = active.sumOf { it.speed }
            val text = if (speed > 0) "${active.size} file · ${formatSpeed(speed)}" else "${active.size} file đang chờ kết nối"
            manager.notify(NOTIFICATION_ID, buildNotification(text, done, total))
            delay(1000)
        }
    }

    /** Android 15+ limits dataSync services and tells us when the time is up. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        AppLog.add("Android dừng service chạy nền, mở lại app để chuyển tiếp")
        stopSelf()
    }

    override fun onDestroy() {
        watcher?.cancel()
        scope.cancel()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun buildNotification(text: String, done: Long, total: Long): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Share Files")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(0xFF7C4DFF.toInt())
        if (total > 0) {
            builder.setProgress(1000, (done * 1000 / total).toInt().coerceIn(0, 1000), false)
        }
        return builder.build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Chuyển file", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "transfers"
        private const val NOTIFICATION_ID = 1
        private const val MAX_LOCK_MILLIS = 6 * 60 * 60 * 1000L

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, TransferService::class.java))
            } catch (e: IllegalStateException) {
                // Started while in the background; transfers wait until the app is opened.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                    AppLog.add("Chưa chạy được service nền, mở app để chuyển tiếp")
                } else {
                    throw e
                }
            }
        }
    }
}
