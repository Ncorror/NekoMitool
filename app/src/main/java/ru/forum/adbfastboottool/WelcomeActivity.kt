package ru.forum.adbfastboottool

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Гостевой/онбординг-экран.
 *
 * Поведение по ТЗ:
 *  - Полноэкранный фон bg_welcome.png (centerCrop) — задаётся в layout.
 *  - Переключатель (SwitchCompat) для подтверждения входа.
 *  - Чекбокс ЗАБЛОКИРОВАН, пока не выданы все обязательные разрешения.
 *  - Кнопка «Дать разрешения» открывает системные диалоги.
 *  - Как только всё выдано — чекбокс разблокируется.
 *  - Клик по разблокированному чекбоксу → переход в MainActivity.
 *  - При повторном запуске (разрешения уже есть) чекбокс активен сразу.
 */
class WelcomeActivity : AppCompatActivity() {

    private lateinit var checkbox: SwitchCompat
    private lateinit var btnPermissions: Button
    private lateinit var tvPermissionStatus: TextView

    private val storagePermissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
            refreshGateState()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Извлекаем палитру из фоновой картинки в фоне (для MainActivity)
        lifecycleScope.launch(Dispatchers.IO) { ThemePalette.extractAndCache(applicationContext) }

        // Если всё уже выдано — можно сразу пропустить онбординг при желании.
        // По ТЗ оставляем экран, но чекбокс будет активен сразу.
        setContentView(R.layout.activity_welcome)

        checkbox = findViewById(R.id.checkboxAgree)
        btnPermissions = findViewById(R.id.btnGrantPermissions)
        tvPermissionStatus = findViewById(R.id.tvPermissionStatus)

        btnPermissions.setOnClickListener { requestNextMissingPermission() }

        checkbox.setOnClickListener {
            if (!PermissionGate.areAllRequiredGranted(this)) {
                // Защита: даже если чекбокс как-то нажали — не пускаем
                checkbox.isChecked = false
                refreshGateState()
                return@setOnClickListener
            }
            // Все разрешения есть — открываем главный экран.
            // Флаг говорит MainActivity не отправлять нас обратно на Welcome.
            startActivity(Intent(this, MainActivity::class.java).apply {
                putExtra("from_welcome", true)
            })
            finish()
        }

        refreshGateState()
    }

    override fun onResume() {
        super.onResume()
        refreshGateState()
    }

    /** Обновляет доступность чекбокса и текст статуса. */
    private fun refreshGateState() {
        val status = PermissionGate.status(this)
        val allGranted = status.allRequiredGranted

        checkbox.isEnabled = allGranted
        if (!allGranted) checkbox.isChecked = false

        btnPermissions.visibility = if (allGranted) android.view.View.GONE else android.view.View.VISIBLE

        tvPermissionStatus.text = buildString {
            append(line(getString(R.string.perm_storage), status.storage))
            append("\n")
            append(line(getString(R.string.perm_notifications), status.notifications))
            append("\n")
            append(line(getString(R.string.perm_battery_opt), status.batteryOptIgnored))
            append("\n\n")
            append(
                if (allGranted) getString(R.string.onboarding_ready)
                else getString(R.string.onboarding_need_permissions)
            )
        }
    }

    private fun line(label: String, granted: Boolean): String =
        (if (granted) "✅ " else "⛔ ") + label

    /** Запрашивает первое из недостающих разрешений по очереди. */
    private fun requestNextMissingPermission() {
        // 1. Storage (MANAGE_EXTERNAL_STORAGE на R+)
        if (!PermissionGate.hasStorage(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    storagePermissionLauncher.launch(
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
                } catch (_: Exception) {
                    // На части прошивок (MIUI/HyperOS и др.) точечный экран
                    // недоступен. Пробуем общий список «все файлы», затем —
                    // как крайний фолбэк — экран сведений о приложении.
                    try {
                        storagePermissionLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    } catch (_: Exception) {
                        try {
                            storagePermissionLauncher.launch(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.parse("package:$packageName")
                                }
                            )
                        } catch (_: Exception) {
                            Toast.makeText(
                                this,
                                getString(R.string.perm_open_settings_manually),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            } else {
                requestPermissions(
                    arrayOf(
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ),
                    REQ_STORAGE
                )
            }
            return
        }

        // 2. Уведомления (Android 13+)
        if (!PermissionGate.hasNotifications(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
            }
            return
        }

        // 3. Оптимизация батареи (необязательно для гейта, но предлагаем)
        if (!PermissionGate.isBatteryOptIgnored(this) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                storagePermissionLauncher.launch(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            } catch (_: Exception) {
                refreshGateState()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshGateState()
        // Цепочка: после выдачи одного — сразу предлагаем следующее
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            requestNextMissingPermission()
        }
    }

    companion object {
        private const val REQ_STORAGE = 200
        private const val REQ_NOTIFICATIONS = 201
    }
}
