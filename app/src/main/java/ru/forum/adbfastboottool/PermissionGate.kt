package ru.forum.adbfastboottool

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager

/**
 * Единая точка проверки всех разрешений из Manifest.
 *
 * Onboarding-чекбокс разблокируется только когда areAllGranted() == true.
 */
object PermissionGate {

    data class Status(
        val storage: Boolean,
        val notifications: Boolean,
        val batteryOptIgnored: Boolean
    ) {
        /**
         * Storage — обязательное (без него нельзя читать образы).
         * Уведомления — обязательны на Android 13+ для ForegroundService-уведомления.
         * Игнор оптимизации батареи — желательно, но НЕ блокирует чекбокс
         * (его нельзя выдать «тихо», требует отдельного системного диалога).
         */
        val allRequiredGranted: Boolean get() = storage && notifications
    }

    fun status(context: Context): Status = Status(
        storage = hasStorage(context),
        notifications = hasNotifications(context),
        batteryOptIgnored = isBatteryOptIgnored(context)
    )

    fun areAllRequiredGranted(context: Context): Boolean = status(context).allRequiredGranted

    fun hasStorage(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasNotifications(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun isBatteryOptIgnored(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }
}
