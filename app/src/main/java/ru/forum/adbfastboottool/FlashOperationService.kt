package ru.forum.adbfastboottool

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * ForegroundService держит USB-операции живыми при выключенном экране.
 * Реальная USB-работа остаётся в DeviceViewModel; этот сервис только управляет уведомлением.
 *
 * FIX #5: На Android 14+ startForeground() может бросить исключение если сервис
 * стартует в неподходящий момент (пользователь нажал Cancel, потом сразу Flash).
 * WakeLock в DeviceViewModel выдаётся ПОСЛЕ успешного старта сервиса, а не до.
 * Добавлен флаг isForegroundStarted для защиты от двойного вызова startForeground().
 */
class FlashOperationService : Service() {

    private var isForegroundStarted = false

    override fun onCreate() {
        super.onCreate()
        createChannelIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: getString(R.string.app_name)
        val text  = intent?.getStringExtra(EXTRA_TEXT)  ?: getString(R.string.service_default_text)

        // FIX #5: защита от двойного startForeground() — вызываем только при первом старте,
        // при обновлении уведомления обновляем через NotificationManager напрямую.
        if (!isForegroundStarted) {
            try {
                startForeground(NOTIFICATION_ID, buildNotification(title, text))
                isForegroundStarted = true
            } catch (e: Exception) {
                // Android 14: ForegroundServiceStartNotAllowedException если Activity в фоне
                // при старте. Логируем в системный журнал — пользователю это не показываем,
                // USB-операция продолжится без уведомления.
                android.util.Log.w("FlashOpService", "startForeground failed: ${e.message}")
            }
        } else {
            // Уже запущен — просто обновляем уведомление
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification(title, text))
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isForegroundStarted = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(title: String, text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.service_channel_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID       = "adb_fastboot_operations"
        private const val NOTIFICATION_ID  = 1401
        private const val EXTRA_TITLE      = "extra_title"
        private const val EXTRA_TEXT       = "extra_text"

        fun start(context: Context, title: String, text: String) {
            val intent = Intent(context, FlashOperationService::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // FGS старт заблокирован системой — операция продолжится без уведомления.
                android.util.Log.w("FlashOpService", "startForegroundService failed: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FlashOperationService::class.java))
        }
    }
}
