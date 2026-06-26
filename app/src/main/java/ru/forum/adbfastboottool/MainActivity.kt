package ru.forum.adbfastboottool

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Html
import android.text.InputType
import android.text.TextUtils
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var usbManager: UsbManager
    private lateinit var tvLog: TextView
    private lateinit var etCommand: EditText
    private lateinit var scrollViewLog: ScrollView
    private lateinit var tvStatus: TextView
    private lateinit var tvSelfTestStatus: TextView
    private lateinit var tvOperationCenterStatus: TextView
    private lateinit var tvOperationCenterLastEvent: TextView
    private lateinit var tvOperationStepQueue: TextView
    private lateinit var viewModel: DeviceViewModel

    private val actionUsbPermission: String by lazy { "$packageName.USB_PERMISSION" }
    private val folderName = "NekoMiFlash"
    private lateinit var workspacePath: File
    private lateinit var importFileLauncher: ActivityResultLauncher<Intent>

    // Управление вкладками вынесено в TabController (декомпозиция MainActivity).
    // by lazy — чтобы не обращаться к this в инициализаторе поля (leaking this).
    private val tabController by lazy { TabController(this) }
    // Совместимость: остальной код читает currentTab/selectedWindow как раньше.
    private val currentTab: String get() = tabController.commandContext
    private val selectedWindow: String get() = tabController.selectedWindow

    private var lastWindowBeforeOperation = "home"
    private var safetyProfile = SafetyProfile.STANDARD
    private var expertModeEnabled = false
    private var highRiskActionsUnlocked = false
    private val flashQueue = linkedMapOf<String, File>()
    private var overlayProtectionLogged = false

    private val usbPermissionHandler = Handler(Looper.getMainLooper())
    private val usbPermissionTimeouts = mutableMapOf<Int, Runnable>()

    private val autoScanHandler = Handler(Looper.getMainLooper())
    private var autoScanEnabled = false
    private var lastAutoScanSignature: String? = null
    private val autoScanRunnable = object : Runnable {
        override fun run() {
            if (!autoScanEnabled) return
            performAutoScan()
            autoScanHandler.postDelayed(this, AUTO_SCAN_INTERVAL_MS)
        }
    }

    private val commandHistory = mutableListOf<String>()
    private var historyIndex = -1

    private enum class SafetyProfile {
        NOVICE,
        STANDARD,
        EXPERT
    }

    private sealed class TerminalAction {
        data object LocalStatus : TerminalAction()
        data object SelfTest : TerminalAction()
        data object SelfTestReport : TerminalAction()
        data object SelfTestForumReport : TerminalAction()
        data object OpenReportsFolder : TerminalAction()
        data class RawFastboot(val command: String) : TerminalAction()
        data class DestructiveFastboot(val command: String, val risk: String) : TerminalAction()
        data class DestructiveFastbootDownloadAndRun(val file: File, val commandAfterDownload: String, val risk: String) : TerminalAction()
        data class FastbootFlash(val partition: String, val file: File) : TerminalAction()
        data class FastbootDownloadAndRun(val file: File, val commandAfterDownload: String) : TerminalAction()
        data class FastbootLogicalInfo(val partition: String) : TerminalAction()
        data class FastbootFetch(val partition: String, val outputFile: File) : TerminalAction()
        data class AdbService(val service: String) : TerminalAction()
        data class AdbShell(val command: String) : TerminalAction()
        data class AdbPush(val localFile: File, val remotePath: String) : TerminalAction()
        data class AdbPull(val remotePath: String, val localFile: File) : TerminalAction()
        data class AdbInstall(val packageFile: File, val options: List<String>) : TerminalAction()
        data class AdbInstallMultiple(val apkFiles: List<File>, val options: List<String>) : TerminalAction()
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                actionUsbPermission -> handleUsbPermissionResult(intent)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> handleUsbDetached(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applySavedLanguage()
        super.onCreate(savedInstanceState)

        // Онбординг-гейт: если обязательные разрешения не выданы и это не USB-attach,
        // отправляем пользователя на WelcomeActivity.
        if (!PermissionGate.areAllRequiredGranted(this) &&
            intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED
        ) {
            startActivity(Intent(this, WelcomeActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)

        tvLog = findViewById(R.id.tvLog)
        etCommand = findViewById(R.id.etCommand)
        scrollViewLog = findViewById(R.id.scrollViewLog)
        tvStatus = findViewById(R.id.tvStatus)
        tvSelfTestStatus = findViewById(R.id.tvSelfTestStatus)
        tvOperationCenterStatus = findViewById(R.id.tvOperationCenterStatus)
        tvOperationCenterLastEvent = findViewById(R.id.tvOperationCenterLastEvent)
        tvOperationStepQueue = findViewById(R.id.tvOperationStepQueue)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        viewModel = ViewModelProvider(this)[DeviceViewModel::class.java]
        enableOverlayProtection()

        viewModel.logLines.observe(this) { lines ->
            renderLog(lines)
            updateOperationCenter(lines)
        }

        viewModel.connectionState.observe(this) { state ->
            val (text, color) = when (state) {
                DeviceViewModel.ConnectionState.NONE -> getString(R.string.status_no_device) to "#4B5563"
                DeviceViewModel.ConnectionState.CONNECTING -> getString(R.string.status_connecting) to "#D97706"
                DeviceViewModel.ConnectionState.FASTBOOT -> getString(R.string.status_fastboot) to "#F97316"
                DeviceViewModel.ConnectionState.ADB -> getString(R.string.status_adb) to "#10B981"
                DeviceViewModel.ConnectionState.ERROR -> getString(R.string.status_error) to "#EF4444"
            }
            tvStatus.text = text
            tvStatus.setTextColor(android.graphics.Color.parseColor(color))
            updateDeviceOverview()
        }

        viewModel.connectionInfo.observe(this) { updateDeviceOverview() }
        viewModel.fastbootDiagnostics.observe(this) { updateDeviceOverview() }
        viewModel.selfTestStatus.observe(this) { renderSelfTestStatus(it) }

        viewModel.operationActive.observe(this) { active ->
            if (active) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                // Авто-снижение яркости на время записи: экономит энергию
                // и снижает нагрев/троттлинг при долгой прошивке.
                applyReducedBrightness()
                showOperationWindow()
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                restoreBrightness()
                // v4: лог всегда виден, не нужно переключать вкладку
            }
            updateDeviceOverview()
            updateOperationCenter(viewModel.logSnapshot())
            updateSafetyProfileUi()
        }

        viewModel.operationSteps.observe(this) { steps -> renderOperationSteps(steps) }

        registerUsbReceiver()
        registerImportLauncher()
        setupButtons()
        loadSafetyPreferences()
        updateSafetyProfileUi()
        updateFlashQueueUi()
        updateDeviceOverview()
        checkPermissions()
        requestNotificationPermissionIfNeeded()
        logBatteryOptimizationState()
        viewModel.log(getString(R.string.log_init_v20))
        handleAutoUsbIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAutoUsbIntent(intent)
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.btnScan).setOnClickListener { scanForDevices() }
        findViewById<Button>(R.id.btnLanguage).setOnClickListener { showLanguageDialog() }
        findViewById<Button>(R.id.btnExpertMode).setOnClickListener { showSafetyProfileDialog() }
        findViewById<Button>(R.id.btnHelp).setOnClickListener { showHelpDialog() }
        findViewById<Button>(R.id.btnSafetyNovice).setOnClickListener { setSafetyProfile(SafetyProfile.NOVICE) }
        findViewById<Button>(R.id.btnSafetyStandard).setOnClickListener { setSafetyProfile(SafetyProfile.STANDARD) }
        findViewById<Button>(R.id.btnSafetyExpert).setOnClickListener { setSafetyProfile(SafetyProfile.EXPERT) }
        findViewById<Button>(R.id.btnSafetyHighRisk).setOnClickListener { toggleHighRiskActions() }
        findViewById<Button>(R.id.btnExportLog).setOnClickListener { showLogActions() }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener { viewModel.clearLog() }
        findViewById<Button>(R.id.btnImportFile).setOnClickListener { startImportFilePicker() }
        findViewById<Button>(R.id.btnAnalyzeFile).setOnClickListener { showFirmwareAnalysisSelector() }
        findViewById<Button>(R.id.btnXiaomiRomAnalyze).setOnClickListener { chooseXiaomiRomForAnalysis() }
        findViewById<Button>(R.id.btnXiaomiRomWizard).setOnClickListener { chooseXiaomiRomWizard() }
        findViewById<Button>(R.id.btnXiaomiRomResume).setOnClickListener { resumeLastXiaomiRomFlashFromUi() }
        findViewById<Button>(R.id.btnXiaomiRomFlashClean).setOnClickListener { chooseXiaomiRomAndConfirm(XiaomiFastbootRomManager.FlashMode.CLEAN_ALL) }
        findViewById<Button>(R.id.btnXiaomiRomFlashSaveData).setOnClickListener { chooseXiaomiRomAndConfirm(XiaomiFastbootRomManager.FlashMode.SAVE_USER_DATA) }
        findViewById<Button>(R.id.btnRecoveryCheckSlot).setOnClickListener { recoveryCheckSlotFromUi() }
        findViewById<Button>(R.id.btnRecoverySwitchSlot).setOnClickListener { recoverySwitchSlotFromUi() }
        findViewById<Button>(R.id.btnRecoveryRebootFastbootd).setOnClickListener { recoveryRebootFastbootdFromUi() }
        findViewById<Button>(R.id.btnRecoveryRebootBootloader).setOnClickListener { recoveryRebootBootloaderFromUi() }
        findViewById<Button>(R.id.btnRecoveryFlashBoot).setOnClickListener { chooseRecoveryImage("boot") }
        findViewById<Button>(R.id.btnRecoveryFlashInitBoot).setOnClickListener { chooseRecoveryImage("init_boot") }
        findViewById<Button>(R.id.btnRecoveryFlashVendorBoot).setOnClickListener { chooseRecoveryImage("vendor_boot") }
        findViewById<Button>(R.id.btnRecoveryFlashVbmeta).setOnClickListener { chooseRecoveryImage("vbmeta") }
        findViewById<Button>(R.id.btnMagiskAutoFlash).setOnClickListener { chooseMagiskPatchedAuto() }
        findViewById<Button>(R.id.btnMagiskFlashBoot).setOnClickListener { chooseMagiskPatchedImage("boot") }
        findViewById<Button>(R.id.btnMagiskFlashInitBoot).setOnClickListener { chooseMagiskPatchedImage("init_boot") }
        findViewById<Button>(R.id.btnMagiskGuide).setOnClickListener { showMagiskGuide() }
        findViewById<Button>(R.id.btnBatteryOpt).setOnClickListener { showBatteryOptimizationDialog() }
        findViewById<Button>(R.id.btnPermissions).setOnClickListener { showPermissionsDialog() }
        findViewById<Button>(R.id.btnAutoScan).setOnClickListener { toggleAutoScan() }
        findViewById<Button>(R.id.btnDebugLog).setOnClickListener { toggleDebugLog() }
        findViewById<Button>(R.id.btnRefreshInfo).setOnClickListener { refreshDeviceDataFromUi() }
        findViewById<Button>(R.id.btnSelfTest).setOnClickListener { viewModel.runSelfTest() }
        findViewById<Button>(R.id.btnSelfTestReport).setOnClickListener { runSelfTestReportFromUi() }
        findViewById<Button>(R.id.btnSelfTestForumReport).setOnClickListener { runSelfTestForumReportFromUi() }
        findViewById<Button>(R.id.btnOpenReportsFolder).setOnClickListener { openReportsFolder() }
        findViewById<Button>(R.id.btnHomeRefreshData).setOnClickListener { refreshDeviceDataFromUi() }
        findViewById<Button>(R.id.btnHomeService).setOnClickListener { switchTab("diagnostics") }
        findViewById<Button>(R.id.btnHomeConsole).setOnClickListener { switchTab("console") }
        findViewById<Button>(R.id.btnTileXiaomiRom).setOnClickListener {
            viewModel.log(getString(R.string.classic_recovery_ui_log) + ": Xiaomi ROM")
            switchTab("fastboot")
        }
        findViewById<Button>(R.id.btnTileRecovery).setOnClickListener {
            viewModel.log(getString(R.string.classic_recovery_ui_log) + ": Recovery / Bootloop")
            switchTab("fastboot")
        }
        findViewById<Button>(R.id.btnTileMagisk).setOnClickListener {
            viewModel.log(getString(R.string.classic_recovery_ui_log) + ": Magisk")
            switchTab("fastboot")
        }
        findViewById<Button>(R.id.btnTileFiles).setOnClickListener { switchTab("files") }
        findViewById<Button>(R.id.btnTileConsole).setOnClickListener { switchTab("console") }
        findViewById<Button>(R.id.btnTileReports).setOnClickListener { switchTab("diagnostics") }
        findViewById<Button>(R.id.btnOperationCenterConsole).setOnClickListener { switchTab("console") }
        findViewById<Button>(R.id.btnOperationCenterReports).setOnClickListener { openReportsFolder() }
        findViewById<Button>(R.id.btnOperationCenterLog).setOnClickListener { showLogActions() }
        findViewById<Button>(R.id.btnOperationCenterForumZip).setOnClickListener { createForumReport() }
        findViewById<Button>(R.id.btnOperationCenterCancel).setOnClickListener { viewModel.cancelActiveOperation() }
        findViewById<Button>(R.id.btnOperationConsole).setOnClickListener { switchTab("console") }
        findViewById<Button>(R.id.btnOperationStop).setOnClickListener { viewModel.cancelActiveOperation() }
        findViewById<Button>(R.id.btnForumReport).setOnClickListener { createForumReport() }
        findViewById<Button>(R.id.btnQueueBoot).setOnClickListener { chooseFlashQueueFile("boot") }
        findViewById<Button>(R.id.btnQueueInitBoot).setOnClickListener { chooseFlashQueueFile("init_boot") }
        findViewById<Button>(R.id.btnQueueVendorBoot).setOnClickListener { chooseFlashQueueFile("vendor_boot") }
        findViewById<Button>(R.id.btnQueueRecovery).setOnClickListener { chooseFlashQueueFile("recovery") }
        findViewById<Button>(R.id.btnQueueDtbo).setOnClickListener { chooseFlashQueueFile("dtbo") }
        findViewById<Button>(R.id.btnQueueClear).setOnClickListener { clearFlashQueue() }
        findViewById<Button>(R.id.btnQueueStart).setOnClickListener { confirmFlashQueue() }

        // v4 UI: прямые кнопки разделов (vbmeta УДАЛЕНА)
        fun flashPartBtn(partition: String) {
            showFileSelector(".img") { file -> showFlashConfirmation(partition, file) }
        }
        findViewById<View>(R.id.btnFlashBoot).setOnClickListener       { flashPartBtn("boot") }
        findViewById<View>(R.id.btnFlashInitBoot).setOnClickListener   { flashPartBtn("init_boot") }
        findViewById<View>(R.id.btnFlashRecovery).setOnClickListener   { flashPartBtn("recovery") }
        findViewById<View>(R.id.btnFlashVendorBoot).setOnClickListener { flashPartBtn("vendor_boot") }
        findViewById<View>(R.id.btnFlashDtbo).setOnClickListener       { flashPartBtn("dtbo") }

        // v4 UI: прямые кнопки reboot вместо меню
        fun rebootCmd(cmd: String) {
            if (viewModel.fastbootProtocol?.isConnected == true)
                viewModel.runFastbootCommand(cmd)
            else
                viewModel.log("ОШИБКА: Нет Fastboot-соединения")
        }
        findViewById<View>(R.id.btnRebootSystem).setOnClickListener    { rebootCmd("reboot") }
        findViewById<View>(R.id.btnRebootBootloader).setOnClickListener { rebootCmd("reboot-bootloader") }
        findViewById<View>(R.id.btnRebootRecovery).setOnClickListener  { rebootCmd("reboot-recovery") }
        // Совместимость: btnRebootMenu скрыт, но код ниже в setupButtons его не трогает

        findViewById<Button>(R.id.btnAdbSideload).setOnClickListener {
            showFileSelector(".zip") { file ->
                showSideloadConfirmation(file)
            }
        }

        findViewById<Button>(R.id.btnGetvar).setOnClickListener {
            if (viewModel.fastbootProtocol?.isConnected == true) {
                viewModel.runFastbootCommand("getvar:all")
            } else {
                viewModel.log("ОШИБКА: Нет Fastboot-соединения")
            }
        }

        findViewById<Button>(R.id.btnCancel).setOnClickListener {
            viewModel.cancelActiveOperation()
        }

        findViewById<Button>(R.id.btnCopyPath).setOnClickListener { copyWorkspacePath() }
        findViewById<Button>(R.id.btnHistoryUp).setOnClickListener { navigateHistory(-1) }
        findViewById<Button>(R.id.btnHistoryDown).setOnClickListener { navigateHistory(1) }

        findViewById<Button>(R.id.btnSend).setOnClickListener { handleCommandInput() }
        etCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                handleCommandInput()
                true
            } else {
                false
            }
        }

        findViewById<Button>(R.id.tabHome).setOnClickListener { switchTab("home") }
        findViewById<Button>(R.id.tabFastboot).setOnClickListener { switchTab("fastboot") }
        findViewById<Button>(R.id.tabAdb).setOnClickListener { switchTab("adb") }
        findViewById<Button>(R.id.tabFiles).setOnClickListener { switchTab("files") }
        findViewById<Button>(R.id.tabDiagnostics).setOnClickListener { switchTab("diagnostics") }
        findViewById<Button>(R.id.tabReports).setOnClickListener { switchTab("console") }
        findViewById<Button>(R.id.tabHelp).setOnClickListener { switchTab("help") }

        findViewById<Button>(R.id.btnWizardFlash).setOnClickListener { switchTab("fastboot") }
        findViewById<Button>(R.id.btnWizardSideload).setOnClickListener { switchTab("adb") }
        findViewById<Button>(R.id.btnWizardPrepare).setOnClickListener { switchTab("files") }
    }

    // ─── USB ─────────────────────────────────────────────────────────────────

    private fun registerUsbReceiver() {
        val filter = IntentFilter(actionUsbPermission).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
    }

    private fun handleUsbPermissionResult(intent: Intent) {
        synchronized(this@MainActivity) {
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }

            device?.let { cancelUsbPermissionTimeout(it.deviceId) }

            if (!intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                viewModel.log("ОШИБКА: Доступ к USB отклонён пользователем")
                return
            }
            if (device == null) {
                viewModel.log("ОШИБКА: USB-устройство не передано системой")
                return
            }

            viewModel.log("Доступ к USB разрешён. Анализ интерфейсов...")
            analyzeAndConnectDevice(device)
        }
    }

    private fun handleUsbDetached(intent: Intent) {
        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
        viewModel.log("USB-устройство отключено: ${device?.productName ?: "неизвестно"}")
        viewModel.disconnectCurrent()
    }

    private fun handleAutoUsbIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            device?.let { requestUsbAccess(it) }
        }
    }

    private fun requestUsbAccess(device: UsbDevice) {
        viewModel.log("Запрос доступа к устройству: ${device.productName ?: "Неизвестно"} (VID=${device.vendorId}, PID=${device.productId})")
        if (usbManager.hasPermission(device)) {
            viewModel.log("USB-доступ уже разрешён")
            analyzeAndConnectDevice(device)
            return
        }

        val intent = Intent(actionUsbPermission).setPackage(packageName)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getBroadcast(this, device.deviceId, intent, flags)
        scheduleUsbPermissionTimeout(device)
        usbManager.requestPermission(device, pi)
    }

    private fun analyzeAndConnectDevice(device: UsbDevice) {
        var isFastboot = false
        var isAdb = false

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == 255 && iface.interfaceSubclass == 66) {
                when (iface.interfaceProtocol) {
                    3 -> isFastboot = true
                    1 -> isAdb = true
                }
            }
        }

        when {
            isFastboot -> {
                viewModel.log("Режим: FASTBOOT")
                viewModel.connectDevice(usbManager, device, isFastboot = true)
            }
            isAdb -> {
                viewModel.log("Режим: ADB")
                viewModel.connectDevice(usbManager, device, isFastboot = false)
            }
            else -> viewModel.log("ОШИБКА: Устройство не распознано как ADB/Fastboot")
        }
    }

    private fun scheduleUsbPermissionTimeout(device: UsbDevice) {
        cancelUsbPermissionTimeout(device.deviceId)
        val timeout = Runnable {
            usbPermissionTimeouts.remove(device.deviceId)
            if (!usbManager.hasPermission(device)) {
                viewModel.log("ОШИБКА: нет ответа на запрос USB-доступа за 30 секунд. Переподключите OTG-кабель и нажмите «Поиск» ещё раз.")
            }
        }
        usbPermissionTimeouts[device.deviceId] = timeout
        usbPermissionHandler.postDelayed(timeout, USB_PERMISSION_TIMEOUT_MS)
    }

    private fun cancelUsbPermissionTimeout(deviceId: Int) {
        val timeout = usbPermissionTimeouts.remove(deviceId) ?: return
        usbPermissionHandler.removeCallbacks(timeout)
    }

    private fun scanForDevices() {
        val candidates = UsbDeviceInspector.findAllCandidates(usbManager.deviceList.values)
        when {
            candidates.isEmpty() -> {
                viewModel.log("ОШИБКА: совместимые ADB/Fastboot USB-устройства не найдены")
                logUsbInventoryForTroubleshooting()
            }
            candidates.size == 1 -> {
                val candidate = candidates.first()
                viewModel.log("Найдено устройство: ${candidate.displayTitle()} | ${candidate.displaySubtitle()}")
                requestUsbAccess(candidate.device)
            }
            else -> showUsbDeviceChooser(candidates)
        }
    }

    private fun showUsbDeviceChooser(candidates: List<UsbDeviceInspector.Candidate>) {
        val items = candidates.mapIndexed { index, candidate ->
            candidate.displayTitle(index + 1) + "\n" + candidate.displaySubtitle()
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_usb_choose_title))
            .setItems(items) { _, which ->
                val selected = candidates[which]
                viewModel.log("Выбрано: ${selected.displayTitle()} | ${selected.displaySubtitle()}")
                requestUsbAccess(selected.device)
            }
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .show()
    }

    private fun performAutoScan() {
        val candidates = UsbDeviceInspector.findAllCandidates(usbManager.deviceList.values)
        val signature = candidates.joinToString(";") { it.stableKey }
        if (signature == lastAutoScanSignature) return
        lastAutoScanSignature = signature

        if (candidates.isEmpty()) {
            viewModel.log("Автопоиск: совместимые ADB/Fastboot устройства не найдены")
            return
        }

        viewModel.log("Автопоиск: найдено совместимых устройств: ${candidates.size}")
        candidates.forEachIndexed { index, candidate ->
            viewModel.log("${index + 1}) ${candidate.displayTitle()} | ${candidate.displaySubtitle()}")
        }
        viewModel.log("💡 Нажмите «Поиск», чтобы выбрать устройство и запросить USB-доступ.")
    }

    private fun toggleAutoScan() {
        autoScanEnabled = !autoScanEnabled
        findViewById<Button>(R.id.btnAutoScan).text = if (autoScanEnabled) getString(R.string.auto_on) else getString(R.string.auto_off)
        if (autoScanEnabled) {
            lastAutoScanSignature = null
            viewModel.log("Автопоиск USB включён: проверка каждые 3 секунды")
            autoScanHandler.removeCallbacks(autoScanRunnable)
            autoScanHandler.post(autoScanRunnable)
        } else {
            autoScanHandler.removeCallbacks(autoScanRunnable)
            viewModel.log("Автопоиск USB выключен")
        }
    }

    private fun toggleDebugLog() {
        val enabled = !viewModel.isDebugLoggingEnabled()
        viewModel.setDebugLogging(enabled)
        findViewById<Button>(R.id.btnDebugLog).text = if (enabled) getString(R.string.debug_on) else getString(R.string.debug_off)
    }

    private fun logUsbInventoryForTroubleshooting() {
        val devices = usbManager.deviceList.values
        if (devices.isEmpty()) {
            viewModel.log("USB-инвентарь: Android не видит ни одного USB-устройства. Проверьте OTG и data-кабель.")
            return
        }
        devices.forEach { device ->
            viewModel.log("USB найден, но не ADB/Fastboot: ${device.productName ?: device.deviceName} VID=${device.vendorId} PID=${device.productId} interfaces=${device.interfaceCount}")
        }
    }

    // ─── КОМАНДЫ ─────────────────────────────────────────────────────────────

    private fun handleCommandInput() {
        val raw = etCommand.text.toString().trim()
        if (raw.isEmpty()) return
        etCommand.text.clear()
        addToHistory(raw)

        val rawLower = raw.lowercase(Locale.US)
        if (isOpenReportsCommand(rawLower)) {
            viewModel.log("> $raw")
            openReportsFolder()
            return
        }

        if (isSelfTestForumReportCommand(rawLower)) {
            viewModel.log("> $raw")
            runSelfTestForumReportFromUi()
            return
        }

        if (isSelfTestReportCommand(rawLower)) {
            viewModel.log("> $raw")
            runSelfTestReportFromUi()
            return
        }

        if (rawLower == "self-test" || rawLower == "selftest" || rawLower == "smoke-test" || rawLower == "doctor") {
            viewModel.log("> $raw")
            viewModel.runSelfTest()
            return
        }

        val (type, cmd) = when {
            raw.startsWith("fastboot ", ignoreCase = true) -> "fastboot" to raw.substringAfter(" ").trim()
            raw.startsWith("adb ", ignoreCase = true) -> "adb" to raw.substringAfter(" ").trim()
            else -> currentTab to raw
        }

        if (viewModel.isInteractiveAdbShellActive()) {
            handleInteractiveAdbShellInput(raw, type, cmd)
            return
        }

        when (type) {
            "fastboot" -> handleFastbootTerminalCommand(cmd)
            "adb" -> handleAdbTerminalCommand(cmd)
            else -> {
                viewModel.log("> $raw")
                viewModel.log("⚠️ Не выбран контекст ADB/Fastboot. Введите префикс adb или fastboot.")
            }
        }
    }

    private fun handleInteractiveAdbShellInput(raw: String, type: String, cmd: String) {
        val cleanRaw = raw.trim()
        val cleanCmd = cmd.trim()
        val lowerRaw = cleanRaw.lowercase(Locale.US)
        val lowerCmd = cleanCmd.lowercase(Locale.US)

        val stopRequested = lowerRaw == ":close" ||
            lowerRaw == ":exit" ||
            lowerRaw == "adb shell-stop" ||
            lowerRaw == "adb shell-exit" ||
            (type == "adb" && (lowerCmd == "shell-stop" || lowerCmd == "shell-exit"))

        if (stopRequested) {
            viewModel.log("> $cleanRaw")
            viewModel.stopInteractiveAdbShell()
            return
        }

        val interruptRequested = lowerRaw == ":ctrl-c" ||
            lowerRaw == ":sigint" ||
            lowerRaw == ":interrupt" ||
            lowerRaw == "adb shell-ctrl-c" ||
            lowerRaw == "adb shell-interrupt" ||
            (type == "adb" && (lowerCmd == "shell-ctrl-c" || lowerCmd == "shell-interrupt"))

        if (interruptRequested) {
            viewModel.log("> $cleanRaw")
            viewModel.interruptInteractiveAdbShell()
            return
        }

        val eofRequested = lowerRaw == ":ctrl-d" ||
            lowerRaw == ":eof" ||
            lowerRaw == "adb shell-ctrl-d" ||
            lowerRaw == "adb shell-eof" ||
            (type == "adb" && (lowerCmd == "shell-ctrl-d" || lowerCmd == "shell-eof"))

        if (eofRequested) {
            viewModel.log("> $cleanRaw")
            viewModel.sendInteractiveAdbShellEof()
            return
        }

        val shellLine = when {
            type == "adb" && lowerCmd == "shell" -> ""
            type == "adb" && lowerCmd.startsWith("shell ") -> cleanCmd.substringAfterWord("shell").trimStart()
            type == "adb" -> cleanCmd
            else -> cleanRaw
        }

        if (shellLine.isBlank()) {
            viewModel.log("ℹ️ Интерактивный adb shell уже открыт. Введите команду или adb shell-stop для выхода.")
            return
        }

        viewModel.sendInteractiveAdbShellInput(shellLine)
    }

    private fun handleFastbootTerminalCommand(cmd: String) {
        viewModel.log("> fastboot $cmd")
        when (val action = parseFastbootCommand(cmd)) {
            null -> return
            TerminalAction.LocalStatus -> viewModel.logConnectionStatus()
            TerminalAction.SelfTest -> viewModel.runSelfTest()
            TerminalAction.SelfTestReport -> runSelfTestReportFromUi()
            TerminalAction.SelfTestForumReport -> runSelfTestForumReportFromUi()
            TerminalAction.OpenReportsFolder -> openReportsFolder()
            is TerminalAction.RawFastboot -> viewModel.runFastbootCommand(action.command)
            is TerminalAction.DestructiveFastboot -> confirmDestructiveFastbootCommand(action.command, action.risk)
            is TerminalAction.DestructiveFastbootDownloadAndRun -> confirmDestructiveFastbootDownloadAndRun(action.file, action.commandAfterDownload, action.risk)
            is TerminalAction.FastbootFlash -> viewModel.runFlash(action.partition, action.file)
            is TerminalAction.FastbootDownloadAndRun -> viewModel.runFastbootDownloadAndRun(action.file, action.commandAfterDownload)
            is TerminalAction.FastbootLogicalInfo -> viewModel.inspectFastbootLogicalPartition(action.partition)
            is TerminalAction.FastbootFetch -> viewModel.runFastbootFetch(action.partition, action.outputFile)
            is TerminalAction.AdbService,
            is TerminalAction.AdbShell,
            is TerminalAction.AdbPush,
            is TerminalAction.AdbPull,
            is TerminalAction.AdbInstall,
            is TerminalAction.AdbInstallMultiple -> Unit
        }
    }

    private fun handleAdbTerminalCommand(cmd: String) {
        viewModel.log("> adb $cmd")
        when (val action = parseAdbCommand(cmd)) {
            null -> return
            TerminalAction.LocalStatus -> viewModel.logConnectionStatus()
            TerminalAction.SelfTest -> viewModel.runSelfTest()
            TerminalAction.SelfTestReport -> runSelfTestReportFromUi()
            TerminalAction.SelfTestForumReport -> runSelfTestForumReportFromUi()
            TerminalAction.OpenReportsFolder -> openReportsFolder()
            is TerminalAction.AdbService -> viewModel.runAdbService(action.service)
            is TerminalAction.AdbShell -> viewModel.runAdbShell(action.command)
            is TerminalAction.AdbPush -> viewModel.runAdbPush(action.localFile, action.remotePath)
            is TerminalAction.AdbPull -> viewModel.runAdbPull(action.remotePath, action.localFile)
            is TerminalAction.AdbInstall -> viewModel.runAdbInstall(action.packageFile, action.options)
            is TerminalAction.AdbInstallMultiple -> viewModel.runAdbInstallMultiple(action.apkFiles, action.options)
            is TerminalAction.RawFastboot,
            is TerminalAction.DestructiveFastboot,
            is TerminalAction.DestructiveFastbootDownloadAndRun,
            is TerminalAction.FastbootFlash,
            is TerminalAction.FastbootDownloadAndRun,
            is TerminalAction.FastbootLogicalInfo,
            is TerminalAction.FastbootFetch -> Unit
        }
    }

    private fun parseFastbootCommand(cmd: String): TerminalAction? {
        val clean = cmd.trim()
        if (clean.isBlank()) return null
        val tokens = tokenizeCommandLine(clean)
        if (tokens.isEmpty()) return null
        val op = tokens[0].lowercase(Locale.US)

        return when (op) {
            "status", "devices" -> TerminalAction.LocalStatus
            "reports", "open-reports", "report-folder", "reports-folder" -> TerminalAction.OpenReportsFolder
            "self-test", "selftest", "smoke-test", "doctor" -> parseSelfTestAction(tokens)

            "flash" -> {
                if (tokens.size < 3) {
                    viewModel.log("❌ Формат: fastboot flash <partition> <file.img>")
                    return null
                }
                val partition = tokens[1]
                val file = resolveTerminalFile(tokens[2]) ?: return null
                TerminalAction.FastbootFlash(partition, file)
            }

            "boot" -> {
                if (tokens.size < 2) {
                    viewModel.log("❌ Формат: fastboot boot <file.img>")
                    return null
                }
                val file = resolveTerminalFile(tokens[1]) ?: return null
                TerminalAction.FastbootDownloadAndRun(file, "boot")
            }

            "getvar" -> {
                val variable = tokens.drop(1).joinToString(" ").ifBlank { "all" }
                TerminalAction.RawFastboot("getvar:$variable")
            }

            "is-logical", "logical-info" -> {
                if (tokens.size < 2) {
                    viewModel.log("❌ Формат: fastboot $op <partition>")
                    return null
                }
                TerminalAction.FastbootLogicalInfo(tokens[1])
            }

            "create-logical-partition" -> {
                if (tokens.size < 3) {
                    viewModel.log("❌ Формат: fastboot create-logical-partition <partition> <size>")
                    return null
                }
                val partition = tokens[1]
                val size = parseFastbootSizeArgument(tokens[2]) ?: return null
                val wire = "create-logical-partition:$partition:$size"
                TerminalAction.DestructiveFastboot(wire, "Создание logical partition изменит metadata super-раздела. Обычно команда должна выполняться в fastbootd и только при точном понимании разметки устройства.")
            }

            "delete-logical-partition" -> {
                if (tokens.size < 2) {
                    viewModel.log("❌ Формат: fastboot delete-logical-partition <partition>")
                    return null
                }
                val partition = tokens[1]
                val wire = "delete-logical-partition:$partition"
                TerminalAction.DestructiveFastboot(wire, "Удаление logical partition фактически стирает раздел из metadata super. Неверный раздел может привести к незагружаемой системе.")
            }

            "resize-logical-partition" -> {
                if (tokens.size < 3) {
                    viewModel.log("❌ Формат: fastboot resize-logical-partition <partition> <size>")
                    return null
                }
                val partition = tokens[1]
                val size = parseFastbootSizeArgument(tokens[2]) ?: return null
                val wire = "resize-logical-partition:$partition:$size"
                TerminalAction.DestructiveFastboot(wire, "Изменение размера logical partition меняет metadata super и может завершиться FAIL при нехватке места. Перед запуском проверьте слот, размер и наличие fastbootd.")
            }

            "update-super" -> {
                if (tokens.size < 2) {
                    viewModel.log("❌ Формат: fastboot update-super <super.img> [wipe] [superPartition]")
                    return null
                }
                val file = resolveTerminalFile(tokens[1]) ?: return null
                val wipe = tokens.drop(2).any { it.equals("wipe", ignoreCase = true) || it.equals("--wipe", ignoreCase = true) }
                val explicitSuper = tokens.drop(2).firstOrNull { !it.equals("wipe", ignoreCase = true) && !it.equals("--wipe", ignoreCase = true) }
                val superName = explicitSuper ?: viewModel.currentFastbootDiagnostics()?.superPartitionName ?: "super"
                val wire = "update-super:$superName" + if (wipe) ":wipe" else ""
                TerminalAction.DestructiveFastbootDownloadAndRun(file, wire, "update-super переписывает metadata super-раздела из образа lpmake. Режим wipe удаляет существующие logical partitions. Обычно требуется fastbootd, корректный super image и разблокированный bootloader.")
            }

            "fetch" -> {
                if (tokens.size < 2) {
                    viewModel.log("❌ Формат: fastboot fetch <partition> [out.img]")
                    return null
                }
                val partition = tokens[1]
                val defaultName = "$partition-fetch.img"
                val output = resolveTerminalOutputFile(tokens.getOrNull(2).orEmpty(), defaultName) ?: return null
                TerminalAction.FastbootFetch(partition, output)
            }

            "erase" -> {
                if (tokens.size < 2) {
                    viewModel.log("❌ Формат: fastboot erase <partition>")
                    return null
                }
                TerminalAction.DestructiveFastboot("erase:${tokens[1]}", "Команда erase удалит содержимое раздела ${tokens[1]}. Восстановление возможно только прошивкой корректного образа или полной прошивкой устройства.")
            }

            "format" -> {
                if (tokens.size < 2) {
                    viewModel.log("❌ Формат: fastboot format <partition>")
                    return null
                }
                TerminalAction.DestructiveFastboot("format:${tokens[1]}", "Команда format переформатирует раздел ${tokens[1]} и может удалить пользовательские данные или служебную разметку.")
            }

            "set_active" -> {
                if (tokens.size < 2) {
                    viewModel.log("❌ Формат: fastboot set_active <a|b>")
                    return null
                }
                TerminalAction.DestructiveFastboot("set_active:${tokens[1].removePrefix("_")}", "Команда set_active переключит активный слот. При несовместимой прошивке устройство может перестать загружаться.")
            }

            "reboot" -> {
                val target = tokens.getOrNull(1)?.lowercase(Locale.US)
                val command = when (target) {
                    null, "system" -> "reboot"
                    "bootloader" -> "reboot-bootloader"
                    "recovery" -> "reboot-recovery"
                    "fastboot" -> "reboot-fastboot"
                    else -> clean
                }
                TerminalAction.RawFastboot(command)
            }

            "flashing" -> {
                if (tokens.size < 2) {
                    viewModel.log("❌ Формат: fastboot flashing <unlock|lock|unlock_critical|lock_critical|...>")
                    return null
                }
                val sub = tokens[1].lowercase(Locale.US)
                if (sub.contains("unlock") || sub.contains("lock")) {
                    TerminalAction.DestructiveFastboot(clean, "Команда $clean меняет состояние блокировки загрузчика. Обычно это стирает данные и может изменить возможность загрузки/прошивки.")
                } else {
                    TerminalAction.RawFastboot(clean)
                }
            }

            "oem" -> {
                if (isPotentiallyDestructiveFastbootCommand(clean)) {
                    TerminalAction.DestructiveFastboot(clean, "OEM-команды зависят от производителя. Эта команда похожа на destructive/service-команду и может стереть данные, изменить блокировку или критические настройки загрузчика.")
                } else {
                    TerminalAction.RawFastboot(clean)
                }
            }

            "update", "flashall" -> {
                viewModel.log("⚠️ fastboot $op требует пакетной логики desktop-fastboot и здесь не эмулируется. Используйте отдельные flash-команды или ADB Sideload.")
                null
            }

            else -> {
                if (isPotentiallyDestructiveFastbootCommand(clean)) {
                    TerminalAction.DestructiveFastboot(clean, "Команда выглядит как запись, очистка, форматирование или изменение критического состояния загрузчика. Проверьте модель, слот и смысл команды перед запуском.")
                } else {
                    TerminalAction.RawFastboot(clean)
                }
            }
        }
    }

    private fun parseAdbCommand(cmd: String): TerminalAction? {
        val clean = cmd.trim()
        if (clean.isBlank()) return null
        val tokens = tokenizeCommandLine(clean)
        if (tokens.isEmpty()) return null
        val op = tokens[0].lowercase(Locale.US)

        return when (op) {
            "status", "devices", "get-state" -> TerminalAction.LocalStatus
            "reports", "open-reports", "report-folder", "reports-folder" -> TerminalAction.OpenReportsFolder
            "self-test", "selftest", "smoke-test", "doctor" -> parseSelfTestAction(tokens)

            "shell" -> {
                val shellCommand = clean.substringAfterWord("shell").trim()
                TerminalAction.AdbShell(shellCommand)
            }

            "exec" -> {
                val execCommand = clean.substringAfterWord("exec").trim()
                if (execCommand.isBlank()) {
                    viewModel.log("❌ Формат: adb exec <command>")
                    null
                } else {
                    TerminalAction.AdbService("exec:$execCommand")
                }
            }

            "reboot" -> {
                val target = tokens.getOrNull(1)?.lowercase(Locale.US).orEmpty()
                TerminalAction.AdbService(if (target.isBlank() || target == "system") "reboot:" else "reboot:$target")
            }

            "root", "unroot", "remount", "disable-verity", "enable-verity", "usb" ->
                TerminalAction.AdbService("$op:")

            "tcpip" -> {
                val port = tokens.getOrNull(1) ?: "5555"
                TerminalAction.AdbService("tcpip:$port")
            }

            "raw", "service" -> {
                val service = clean.substringAfterWord(op).trim()
                if (service.isBlank()) {
                    viewModel.log("❌ Формат: adb $op <service>, например adb raw shell:getprop")
                    null
                } else {
                    TerminalAction.AdbService(service)
                }
            }

            "logcat" -> TerminalAction.AdbShell(clean)
            "getprop", "setprop", "pm", "am", "cmd", "settings", "wm", "input", "svc", "dumpsys", "cat", "ls", "cd", "pwd", "id", "su", "sh" ->
                TerminalAction.AdbShell(clean)

            "push" -> {
                if (tokens.size < 3) {
                    viewModel.log("❌ Формат: adb push <local-file> <remote-path>")
                    null
                } else {
                    val localPath = resolveTerminalInputPath(tokens[1]) ?: return null
                    val remoteArg = tokens[2]
                    val remotePath = if (localPath.isFile && remoteArg.endsWith("/")) remoteArg + localPath.name else remoteArg
                    TerminalAction.AdbPush(localPath, remotePath)
                }
            }

            "pull" -> {
                if (tokens.size < 2) {
                    viewModel.log("❌ Формат: adb pull <remote-path> [local-file]")
                    null
                } else {
                    val remotePath = tokens[1]
                    val defaultName = remotePath.substringAfterLast('/').ifBlank { "adb-pull.bin" }
                    val localFile = resolveTerminalOutputFile(tokens.getOrNull(2).orEmpty(), defaultName) ?: return null
                    TerminalAction.AdbPull(remotePath, localFile)
                }
            }

            "install" -> {
                if (tokens.size < 2) {
                    viewModel.log("❌ Формат: adb install [-r] [-d] [-g] <local.apk|local.apks|local.xapk>")
                    null
                } else {
                    val packageToken = tokens.last()
                    val packageFile = resolveTerminalFile(packageToken) ?: return null
                    val lowerName = packageFile.name.lowercase(Locale.US)
                    if (!(lowerName.endsWith(".apk") || lowerName.endsWith(".apks") || lowerName.endsWith(".xapk"))) {
                        viewModel.log("⚠️ Файл не похож на APK/APKS/XAPK: ${packageFile.name}")
                    }
                    val options = tokens.drop(1).dropLast(1)
                    TerminalAction.AdbInstall(packageFile, options)
                }
            }

            "install-multiple" -> parseAdbInstallMultiple(tokens)

            "sync" -> {
                viewModel.log("ℹ️ adb sync каталогов пока не реализован. Используйте adb push/adb pull для отдельных файлов.")
                null
            }

            "sideload" -> {
                viewModel.log("ℹ️ Для sideload используйте кнопку ADB Sideload: она передаёт ZIP через sideload-host с прогрессом.")
                null
            }

            else -> TerminalAction.AdbShell(clean)
        }
    }


    private fun parseAdbInstallMultiple(tokens: List<String>): TerminalAction? {
        if (tokens.size < 3) {
            viewModel.log("❌ Формат: adb install-multiple [-r] [-d] [-g] <base.apk> <split1.apk> [split2.apk...]")
            return null
        }

        val options = mutableListOf<String>()
        val files = mutableListOf<File>()
        tokens.drop(1).forEach { token ->
            if (token.lowercase(Locale.US).endsWith(".apk")) {
                val file = resolveTerminalFile(token) ?: return null
                files.add(file)
            } else {
                options.add(token)
            }
        }

        if (files.size < 2) {
            viewModel.log("❌ install-multiple требует минимум 2 APK: base.apk и один или несколько split/config APK")
            viewModel.log("💡 Пример: adb install-multiple -r base.apk split_config.arm64_v8a.apk split_config.xxhdpi.apk")
            return null
        }

        val hasBaseLikeFile = files.any { it.name.equals("base.apk", ignoreCase = true) || it.name.startsWith("base-", ignoreCase = true) }
        if (!hasBaseLikeFile) {
            viewModel.log("⚠️ Среди файлов не видно base.apk. Если base APK отсутствует, установка split APK обычно завершится ошибкой.")
        }

        return TerminalAction.AdbInstallMultiple(files, options)
    }


    private fun confirmDestructiveFastbootCommand(command: String, risk: String) {
        if (!ensureHighRiskAllowed("fastboot $command")) return
        showTypedDangerConfirmation(
            title = getString(R.string.dialog_destructive_fastboot_title),
            message = getString(R.string.dialog_destructive_fastboot_message, command, risk),
            requiredPhrase = dangerPhraseForCommand(command),
            logLabel = "destructive fastboot: $command"
        ) {
            viewModel.log("⚠️ Typed-confirmed destructive Fastboot command: $command")
            if (command.startsWith("create-logical-partition:", ignoreCase = true) ||
                command.startsWith("delete-logical-partition:", ignoreCase = true) ||
                command.startsWith("resize-logical-partition:", ignoreCase = true) ||
                command.startsWith("update-super:", ignoreCase = true)
            ) {
                viewModel.runFastbootLogicalPartitionCommand(command)
            } else {
                viewModel.runFastbootCommand(command)
            }
        }
    }

    private fun confirmDestructiveFastbootDownloadAndRun(file: File, commandAfterDownload: String, risk: String) {
        if (!ensureHighRiskAllowed("fastboot $commandAfterDownload")) return
        showTypedDangerConfirmation(
            title = getString(R.string.dialog_destructive_fastboot_title),
            message = getString(R.string.dialog_destructive_fastboot_message, "$commandAfterDownload ← ${file.name}", risk),
            requiredPhrase = dangerPhraseForCommand(commandAfterDownload),
            logLabel = "destructive fastboot download+command: $commandAfterDownload ← ${file.name}"
        ) {
            viewModel.log("⚠️ Typed-confirmed destructive Fastboot download+command: $commandAfterDownload ← ${file.name}")
            viewModel.runFastbootDownloadAndRun(file, commandAfterDownload)
        }
    }

    private fun showTypedDangerConfirmation(
        title: String,
        message: String,
        requiredPhrase: String,
        logLabel: String,
        onConfirmed: () -> Unit
    ) {
        val input = EditText(this).apply {
            isSingleLine = true
            hint = requiredPhrase
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setSelectAllOnFocus(true)
        }
        val body = TextView(this).apply {
            text = getString(R.string.typed_confirm_message, message, requiredPhrase)
            setTextIsSelectable(true)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(20)
            setPadding(pad, 0, pad, 0)
            addView(body)
            addView(input)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(container)
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .setPositiveButton(getString(R.string.start_upper), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val typed = input.text?.toString()?.trim().orEmpty()
                if (typed == requiredPhrase) {
                    dialog.dismiss()
                    onConfirmed()
                } else {
                    input.error = getString(R.string.typed_confirm_error, requiredPhrase)
                    viewModel.log(getString(R.string.typed_confirm_failed_log, logLabel))
                }
            }
        }
        dialog.show()
        input.requestFocus()
    }

    private fun dangerPhraseForCommand(command: String): String {
        val clean = command.trim().lowercase(Locale.US)
        return when {
            "unlock" in clean || "lock" in clean -> "LOCK"
            "wipe" in clean || clean.startsWith("erase") || clean.startsWith("format") -> "WIPE"
            clean.startsWith("update-super") || "update-super" in clean -> "SUPER"
            else -> "CONFIRM"
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun isPotentiallyDestructiveFastbootCommand(command: String): Boolean {
        val clean = command.trim().lowercase(Locale.US)
        val destructivePrefixes = listOf(
            "erase:", "erase ",
            "format:", "format ",
            "set_active:", "set_active ",
            "create-logical-partition:", "create-logical-partition ",
            "delete-logical-partition:", "delete-logical-partition ",
            "resize-logical-partition:", "resize-logical-partition ",
            "update-super:", "update-super ",
            "wipe-super", "snapshot-update",
            "flashing unlock", "flashing lock",
            "flashing unlock_critical", "flashing lock_critical",
            "oem unlock", "oem lock"
        )
        if (destructivePrefixes.any { clean.startsWith(it) }) return true
        val riskyTokens = listOf(" erase", " wipe", " format", " unlock", " lock", " factory", " reset")
        return clean.startsWith("oem ") && riskyTokens.any { clean.contains(it) }
    }

    private fun parseFastbootSizeArgument(raw: String): Long? {
        val token = raw.trim().lowercase(Locale.US)
        if (token.isBlank()) {
            viewModel.log("❌ Не указан размер")
            return null
        }
        val multiplier = when {
            token.endsWith("k") || token.endsWith("kb") -> 1024L
            token.endsWith("m") || token.endsWith("mb") -> 1024L * 1024L
            token.endsWith("g") || token.endsWith("gb") -> 1024L * 1024L * 1024L
            else -> 1L
        }
        val numberPart = token.removeSuffix("kb").removeSuffix("mb").removeSuffix("gb").removeSuffix("k").removeSuffix("m").removeSuffix("g")
        val value = try {
            if (numberPart.startsWith("0x")) numberPart.removePrefix("0x").toLong(16) else numberPart.toLong()
        } catch (_: NumberFormatException) {
            viewModel.log("❌ Некорректный размер: $raw. Используйте байты, 512M, 2G или 0x...")
            return null
        }
        val bytes = value * multiplier
        if (bytes <= 0L) {
            viewModel.log("❌ Размер должен быть больше нуля")
            return null
        }
        return bytes
    }

    private fun resolveTerminalInputPath(pathText: String): File? {
        val rawPath = pathText.trim().trim('"', '\'')
        if (rawPath.isBlank()) {
            viewModel.log("❌ Не указан локальный путь")
            return null
        }

        val file = when {
            rawPath.startsWith("/") -> File(rawPath)
            rawPath.startsWith("/sdcard/") -> File(rawPath)
            else -> {
                if (!ensureWorkspaceReady()) return null
                File(workspacePath, rawPath)
            }
        }

        return if (file.exists() && file.canRead()) {
            file
        } else {
            viewModel.log("❌ Локальный путь не найден или недоступен: ${file.absolutePath}")
            viewModel.log("💡 Для относительного пути положите файл/папку в /sdcard/Download/$folderName или импортируйте файл кнопкой «Импорт».")
            null
        }
    }

    private fun resolveTerminalFile(pathText: String): File? {
        val file = resolveTerminalInputPath(pathText) ?: return null
        return if (file.isFile) {
            file
        } else {
            viewModel.log("❌ Ожидался файл, но указан каталог: ${file.absolutePath}")
            null
        }
    }

    private fun resolveTerminalOutputFile(pathText: String, defaultName: String): File? {
        if (!ensureWorkspaceReady()) return null
        val rawPath = pathText.trim().trim('"', '\'')

        val candidate = if (rawPath.isBlank()) {
            File(workspacePath, defaultName)
        } else {
            val base = if (rawPath.startsWith("/")) File(rawPath) else File(workspacePath, rawPath)
            when {
                rawPath.endsWith("/") -> File(base, defaultName)
                base.exists() && base.isDirectory -> File(base, defaultName)
                else -> base
            }
        }

        val parent = candidate.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            viewModel.log("❌ Не удалось создать папку для файла: ${parent.absolutePath}")
            return null
        }
        return candidate
    }

    private fun tokenizeCommandLine(input: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaping = false

        input.forEach { ch ->
            when {
                escaping -> {
                    current.append(ch)
                    escaping = false
                }
                ch == '\\' -> escaping = true
                quote != null -> {
                    if (ch == quote) quote = null else current.append(ch)
                }
                ch == '\'' || ch == '"' -> quote = ch
                ch.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        tokens += current.toString()
                        current.clear()
                    }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }

    private fun String.substringAfterWord(word: String): String {
        if (!startsWith(word, ignoreCase = true)) return this
        return drop(word.length)
    }

    private fun addToHistory(command: String) {
        if (commandHistory.lastOrNull() != command) {
            commandHistory.add(command)
            if (commandHistory.size > 50) commandHistory.removeAt(0)
        }
        historyIndex = commandHistory.size
    }

    private fun navigateHistory(direction: Int) {
        if (commandHistory.isEmpty()) return
        historyIndex = (historyIndex + direction).coerceIn(0, commandHistory.size)
        etCommand.setText(if (historyIndex == commandHistory.size) "" else commandHistory[historyIndex])
        etCommand.setSelection(etCommand.text.length)
    }

    // ─── РАЗРЕШЕНИЯ И ФАЙЛЫ ──────────────────────────────────────────────────

    private fun registerImportLauncher() {
        importFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) {
                viewModel.log("Импорт файла отменён")
                return@registerForActivityResult
            }
            val uri = result.data?.data
            if (uri == null) {
                viewModel.log("ОШИБКА: системный выбор файла не вернул URI")
                return@registerForActivityResult
            }
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
                // Не все проводники выдают persistable-доступ. Для немедленного копирования достаточно временного доступа.
            }
            importFirmwareFile(uri)
        }
    }

    private fun startImportFilePicker() {
        if (!ensureWorkspaceReady()) return

        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/octet-stream", "application/zip", "application/x-zip-compressed", "application/vnd.android.package-archive", "text/plain", "*/*")
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }

        try {
            importFileLauncher.launch(intent)
        } catch (e: Exception) {
            viewModel.log("ОШИБКА: не удалось открыть системный выбор файла: ${e.message}")
        }
    }

    private fun ensureWorkspaceReady(): Boolean {
        if (::workspacePath.isInitialized && workspacePath.exists()) return true
        viewModel.log("ОШИБКА: рабочая папка ещё не готова. Выдайте доступ ко всем файлам и повторите.")
        checkPermissions()
        return false
    }

    private fun importFirmwareFile(uri: Uri) {
        if (!ensureWorkspaceReady()) return

        val displayName = sanitizeImportedFileName(queryDisplayName(uri) ?: "imported-${System.currentTimeMillis()}")
        val lower = displayName.lowercase(Locale.US)
        val allowed = lower.endsWith(".img") || lower.endsWith(".zip") || lower.endsWith(".bin") ||
            lower.endsWith(".tgz") || lower.endsWith(".tar") || lower.endsWith(".tar.gz") ||
            lower.endsWith(".apk") || lower.endsWith(".apks") || lower.endsWith(".xapk") || lower.endsWith(".obb") ||
            lower.endsWith(".sha256") || lower.endsWith(".md5")
        if (!allowed) {
            viewModel.log("ОШИБКА: импорт разрешён только для .img, .zip, .tgz, .tar, .tar.gz, .bin, .apk, .apks, .xapk, .obb, .sha256 и .md5. Выбран файл: $displayName")
            return
        }

        val target = uniqueTargetFile(displayName)
        viewModel.log("Импорт файла: $displayName → /sdcard/Download/$folderName/${target.name}")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openInputStream(uri).use { input ->
                    if (input == null) throw IllegalStateException("не удалось открыть входной поток")
                    target.outputStream().use { output -> input.copyTo(output, DEFAULT_COPY_BUFFER_SIZE) }
                }
                viewModel.log("✅ Файл импортирован: /sdcard/Download/$folderName/${target.name} (${formatFileSize(target.length())})")
            } catch (e: Exception) {
                target.delete()
                viewModel.log("ОШИБКА: не удалось импортировать файл: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                cursor.getString(0)
            } else {
                uri.lastPathSegment?.substringAfterLast('/')
            }
        } catch (_: Exception) {
            uri.lastPathSegment?.substringAfterLast('/')
        } finally {
            cursor?.close()
        }
    }

    private fun sanitizeImportedFileName(name: String): String {
        val safe = name.trim()
            .replace(Regex("[\\/:*?\"<>|\r\n]+"), "_")
            .replace(Regex("\\s+"), "_")
            .take(160)
        return safe.ifBlank { "imported-${System.currentTimeMillis()}" }
    }

    private fun uniqueTargetFile(fileName: String): File {
        var candidate = File(workspacePath, fileName)
        if (!candidate.exists()) return candidate

        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        var index = 1
        while (candidate.exists()) {
            candidate = File(workspacePath, "$base-$index$ext")
            index++
        }
        return candidate
    }

    private fun formatFileSize(bytes: Long): String {
        val mb = bytes.toDouble() / 1024.0 / 1024.0
        return "%.2f MB".format(Locale.US, mb)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                viewModel.log("⚠️ Требуется доступ ко всем файлам для чтения /sdcard/Download/$folderName.")
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    })
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            } else if (!::workspacePath.isInitialized) {
                initWorkspace()
            }
        } else {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    arrayOf(
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ),
                    100
                )
            } else if (!::workspacePath.isInitialized) {
                initWorkspace()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            100 -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    initWorkspace()
                } else {
                    viewModel.log("ОШИБКА: Нет прав на чтение памяти")
                }
            }
            101 -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    viewModel.log("Разрешение на уведомления выдано")
                } else {
                    viewModel.log("⚠️ Уведомления отключены. ForegroundService всё равно будет запущен, но Android может скрыть уведомление.")
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        enableOverlayProtection()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager() && !::workspacePath.isInitialized) {
                initWorkspace()
            }
        }
    }

    private fun initWorkspace() {
        // Рабочая папка теперь в системной папке «Загрузки»: /sdcard/Download/NekoMiFlash
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        workspacePath = File(downloadsDir, folderName)
        if (!workspacePath.exists() && !workspacePath.mkdirs()) {
            viewModel.log("ОШИБКА: Не удалось создать папку ${workspaceDisplayPath()}")
            return
        }
        viewModel.log("Рабочая папка: ${workspaceDisplayPath()}")
        viewModel.configureLogDirectory(workspacePath)
        updateDeviceOverview()
    }

    /** Человекочитаемый путь рабочей папки для логов и диалогов. */
    private fun workspaceDisplayPath(): String = "/sdcard/Download/$folderName"

    private fun showFileSelector(extension: String, onFileSelected: (File) -> Unit) {
        if (!::workspacePath.isInitialized || !workspacePath.exists()) {
            viewModel.log("ОШИБКА: Папка не инициализирована. Выдайте разрешения.")
            return
        }
        // FIX #8: сортируем по дате изменения — свежий импорт всегда первый
        val files = workspacePath
            .listFiles { _, name -> name.lowercase().endsWith(extension) }
            ?.filter { it.isFile && it.canRead() }
            ?.sortedByDescending { it.lastModified() }
            ?.toTypedArray()

        if (files.isNullOrEmpty()) {
            viewModel.log("ОШИБКА: Нет файлов *$extension в папке $folderName. Нажмите «Импорт», чтобы скопировать файл через системный выбор.")
            return
        }
        runOnUiThread {
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.dialog_file_choose_title))
                .setItems(files.map { "📄 ${it.name}" }.toTypedArray()) { _, which ->
                    onFileSelected(files[which])
                }
                .setNegativeButton(getString(R.string.cancel_upper), null)
                .show()
        }
    }


    private fun showXiaomiRomSelector(onFileSelected: (File) -> Unit) {
        if (!ensureWorkspaceReady()) return
        val files = workspacePath
            .listFiles { file -> XiaomiFastbootRomManager.isSupportedRomFile(file) }
            ?.filter { it.isFile && it.canRead() }
            ?.sortedByDescending { it.lastModified() }
            ?.toTypedArray()

        if (files.isNullOrEmpty()) {
            viewModel.log(getString(R.string.xiaomi_rom_no_files_log))
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.xiaomi_rom_choose_title))
            .setItems(files.map { "📦 ${it.name} (${formatFileSize(it.length())})" }.toTypedArray()) { _, which ->
                onFileSelected(files[which])
            }
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .show()
    }

    private fun chooseXiaomiRomWizard() {
        showXiaomiRomSelector { file ->
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.xiaomi_wizard_title))
                .setMessage(getString(R.string.xiaomi_wizard_message, file.name))
                .setPositiveButton(getString(R.string.xiaomi_wizard_clean)) { _, _ ->
                    showXiaomiWizardFinalConfirm(file, XiaomiFastbootRomManager.FlashMode.CLEAN_ALL)
                }
                .setNeutralButton(getString(R.string.xiaomi_wizard_save)) { _, _ ->
                    showXiaomiWizardFinalConfirm(file, XiaomiFastbootRomManager.FlashMode.SAVE_USER_DATA)
                }
                .setNegativeButton(getString(R.string.cancel_upper), null)
                .show()
        }
    }

    private fun showXiaomiWizardFinalConfirm(file: File, mode: XiaomiFastbootRomManager.FlashMode) {
        val modeTitle = when (mode) {
            XiaomiFastbootRomManager.FlashMode.CLEAN_ALL -> getString(R.string.xiaomi_rom_mode_clean)
            XiaomiFastbootRomManager.FlashMode.SAVE_USER_DATA -> getString(R.string.xiaomi_rom_mode_save_data)
        }
        val wipeWarning = when (mode) {
            XiaomiFastbootRomManager.FlashMode.CLEAN_ALL -> getString(R.string.xiaomi_rom_clean_warning)
            XiaomiFastbootRomManager.FlashMode.SAVE_USER_DATA -> getString(R.string.xiaomi_rom_save_data_warning)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.xiaomi_wizard_title))
            .setMessage(getString(R.string.xiaomi_rom_confirm_message, file.name, modeTitle, wipeWarning))
            .setPositiveButton(getString(R.string.flash_upper)) { _, _ ->
                confirmXiaomiRomDanger(file, mode)
            }
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .show()
    }

    private fun confirmXiaomiRomDanger(file: File, mode: XiaomiFastbootRomManager.FlashMode) {
        if (safetyProfile == SafetyProfile.NOVICE) {
            showSafetyBlockedDialog(getString(R.string.safety_blocked_novice_flash))
            return
        }
        if (mode == XiaomiFastbootRomManager.FlashMode.CLEAN_ALL && !ensureHighRiskAllowed("Xiaomi clean all")) return
        val phrase = when (mode) {
            XiaomiFastbootRomManager.FlashMode.CLEAN_ALL -> "CLEAN ALL"
            XiaomiFastbootRomManager.FlashMode.SAVE_USER_DATA -> "FLASH"
        }
        val modeTitle = when (mode) {
            XiaomiFastbootRomManager.FlashMode.CLEAN_ALL -> getString(R.string.xiaomi_rom_mode_clean)
            XiaomiFastbootRomManager.FlashMode.SAVE_USER_DATA -> getString(R.string.xiaomi_rom_mode_save_data)
        }
        showTypedDangerConfirmation(
            title = getString(R.string.xiaomi_rom_typed_confirm_title),
            message = getString(R.string.xiaomi_rom_typed_confirm_message, file.name, modeTitle),
            requiredPhrase = phrase,
            logLabel = "Xiaomi ROM flash: ${file.name}, mode=$modeTitle"
        ) {
            viewModel.runXiaomiFastbootRom(file, workspacePath, mode)
            switchTab("console")
        }
    }

    private fun ensureFastbootForTool(): Boolean {
        if (viewModel.fastbootProtocol?.isConnected == true) return true
        viewModel.log(getString(R.string.error_no_fastboot))
        return false
    }

    private fun recoveryCheckSlotFromUi() {
        if (!ensureFastbootForTool()) return
        viewModel.refreshFastbootDiagnostics()
        val d = viewModel.currentFastbootDiagnostics()
        viewModel.log("=== RECOVERY SLOT CHECK ===")
        viewModel.log("product=${d?.product ?: "—"}, slot=${d?.currentSlot ?: d?.slotSuffix ?: "—"}, slot-count=${d?.slotCount ?: "—"}, unlocked=${d?.unlocked ?: "—"}, userspace=${d?.isUserspace ?: "—"}")
        switchTab("console")
    }

    private fun recoverySwitchSlotFromUi() {
        if (!ensureFastbootForTool()) return
        if (!ensureHighRiskAllowed("switch active slot")) return
        val currentRaw = viewModel.currentFastbootDiagnostics()?.currentSlot ?: viewModel.currentFastbootDiagnostics()?.slotSuffix
        val current = currentRaw?.removePrefix("_")?.lowercase(Locale.US)
        val target = when (current) {
            "a" -> "b"
            "b" -> "a"
            else -> null
        }
        if (target == null) {
            viewModel.log("❌ Не удалось определить текущий слот. Выполните Refresh device data и повторите.")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.recovery_switch_slot_title))
            .setMessage(getString(R.string.recovery_switch_slot_message, current, target))
            .setPositiveButton(getString(R.string.continue_upper)) { _, _ ->
                showTypedDangerConfirmation(
                    title = getString(R.string.recovery_switch_slot_title),
                    message = getString(R.string.recovery_switch_slot_message, current, target),
                    requiredPhrase = "SLOT",
                    logLabel = "switch active slot $current → $target"
                ) {
                    viewModel.runFastbootCommand("set_active:$target")
                    switchTab("console")
                }
            }
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .show()
    }

    private fun recoveryRebootFastbootdFromUi() {
        if (!ensureFastbootForTool()) return
        viewModel.log("Recovery tool: rebooting to fastbootd/userspace via fastboot reboot fastboot")
        viewModel.runFastbootCommand("reboot:fastboot")
        switchTab("console")
    }

    private fun recoveryRebootBootloaderFromUi() {
        if (!ensureFastbootForTool()) return
        viewModel.log("Recovery tool: rebooting to bootloader fastboot")
        viewModel.runFastbootCommand("reboot-bootloader")
        switchTab("console")
    }

    private fun chooseRecoveryImage(partition: String) {
        if (!ensureGuidedFlashAllowed(partition)) return
        showFileSelector(".img") { file ->
            viewModel.log("Recovery tool selected: $partition ← ${file.name}")
            showFlashConfirmation(partition, file)
        }
    }

    private fun chooseMagiskPatchedImage(partition: String) {
        if (!ensureGuidedFlashAllowed(partition)) return
        showFileSelector(".img") { file ->
            viewModel.log("Magisk helper selected: $partition ← ${file.name}")
            showFlashConfirmation(partition, file)
        }
    }

    private fun chooseMagiskPatchedAuto() {
        if (!ensureGuidedFlashAllowed("boot")) return
        showFileSelector(".img") { file ->
            val lower = file.name.lowercase(Locale.US)
            val partition = when {
                "init_boot" in lower || "initboot" in lower -> "init_boot"
                "vendor_boot" in lower || "vendorboot" in lower -> "vendor_boot"
                else -> "boot"
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.magisk_auto_title))
                .setMessage(getString(R.string.magisk_auto_message, file.name, partition))
                .setPositiveButton(getString(R.string.continue_upper)) { _, _ -> showFlashConfirmation(partition, file) }
                .setNegativeButton(getString(R.string.cancel_upper), null)
                .show()
        }
    }

    private fun showMagiskGuide() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.magisk_guide_title))
            .setMessage(getString(R.string.magisk_guide_message))
            .setPositiveButton(getString(R.string.close_upper), null)
            .show()
    }

    private fun chooseXiaomiRomForAnalysis() {
        showXiaomiRomSelector { file ->
            viewModel.analyzeXiaomiFastbootRom(file, workspacePath)
            switchTab("console")
        }
    }

    private fun resumeLastXiaomiRomFlashFromUi() {
        if (!ensureWorkspaceReady()) return
        if (viewModel.fastbootProtocol?.isConnected != true) {
            viewModel.log(getString(R.string.error_no_fastboot))
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.xiaomi_rom_resume_confirm_title))
            .setMessage(getString(R.string.xiaomi_rom_resume_confirm_message))
            .setPositiveButton(getString(R.string.resume_upper)) { _, _ ->
                viewModel.resumeLastXiaomiRomFlash(workspacePath)
                switchTab("console")
            }
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .show()
    }

    private fun chooseXiaomiRomAndConfirm(mode: XiaomiFastbootRomManager.FlashMode) {
        if (viewModel.fastbootProtocol?.isConnected != true) {
            viewModel.log(getString(R.string.error_no_fastboot))
            return
        }
        showXiaomiRomSelector { file ->
            val modeTitle = when (mode) {
                XiaomiFastbootRomManager.FlashMode.CLEAN_ALL -> getString(R.string.xiaomi_rom_mode_clean)
                XiaomiFastbootRomManager.FlashMode.SAVE_USER_DATA -> getString(R.string.xiaomi_rom_mode_save_data)
            }
            val wipeWarning = when (mode) {
                XiaomiFastbootRomManager.FlashMode.CLEAN_ALL -> getString(R.string.xiaomi_rom_clean_warning)
                XiaomiFastbootRomManager.FlashMode.SAVE_USER_DATA -> getString(R.string.xiaomi_rom_save_data_warning)
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.xiaomi_rom_confirm_title))
                .setMessage(getString(R.string.xiaomi_rom_confirm_message, file.name, modeTitle, wipeWarning))
                .setPositiveButton(getString(R.string.flash_upper)) { _, _ ->
                    confirmXiaomiRomDanger(file, mode)
                }
                .setNegativeButton(getString(R.string.cancel_upper), null)
                .show()
        }
    }

    private fun showFirmwareAnalysisSelector() {
        if (!ensureWorkspaceReady()) return
        val files = workspacePath
            .listFiles { file -> ImageInspector.isSupportedFirmwareFile(file) }
            ?.filter { it.isFile && it.canRead() }
            ?.sortedBy { it.name.lowercase(Locale.US) }
            ?.toTypedArray()

        if (files.isNullOrEmpty()) {
            viewModel.log("ОШИБКА: Нет файлов для анализа в /sdcard/Download/$folderName. Нажмите «Импорт» или скопируйте .img/.zip вручную.")
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_analysis_title))
            .setItems(files.map { "🔍 ${it.name}" }.toTypedArray()) { _, which ->
                showFirmwareAnalysisResult(files[which])
            }
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .show()
    }

    private fun showFirmwareAnalysisResult(file: File) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val analysis = ImageInspector.analyze(file, includeHashes = true)
                viewModel.log(getString(R.string.analysis_header))
                analysis.toDisplayText().lines().forEach { line ->
                    if (line.isNotBlank()) viewModel.log(line)
                }
                runOnUiThread {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(getString(R.string.analysis_file_title, file.name))
                        .setMessage(analysis.toDisplayText())
                        .setPositiveButton(getString(R.string.to_log_upper)) { _, _ -> viewModel.analyzeFirmwareFile(file) }
                        .setNeutralButton(getString(R.string.copy_sha256_upper)) { _, _ ->
                            val sha = analysis.sha256
                            if (sha != null) copyTextToClipboard("SHA-256", sha, getString(R.string.sha256_copied))
                        }
                        .setNegativeButton(getString(R.string.close_upper), null)
                        .show()
                }
            } catch (e: Exception) {
                viewModel.log("ОШИБКА: не удалось проанализировать файл: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun showFlashConfirmation(partition: String, file: File) {
        if (!ensureGuidedFlashAllowed(partition)) return
        if (viewModel.fastbootProtocol?.isConnected != true) {
            viewModel.log(getString(R.string.error_no_fastboot))
            return
        }
        val diagnostics = viewModel.currentFastbootDiagnostics()
        if (diagnostics?.unlocked?.equals("no", ignoreCase = true) == true) {
            viewModel.log("⛔ Bootloader locked: fastboot flash заблокирован. Разблокируйте загрузчик или используйте только команды без записи разделов.")
            MaterialAlertDialogBuilder(this)
                .setTitle("Fastboot flash заблокирован")
                .setMessage("Устройство сообщает bootloader unlocked = no. Приложение не будет выполнять fastboot flash, чтобы не создавать ложное ощущение безопасной прошивки. Диагностика, reboot, getvar, erase/format/raw-команды остаются в полном терминале.")
                .setPositiveButton(getString(R.string.close_upper), null)
                .show()
            return
        }

        // ─── ПРЕДПОЛЁТНАЯ ПРОВЕРКА (Foolproof) ───
        val preflight = PreflightValidator.validateFlash(
            context = this,
            partition = partition,
            file = file,
            currentSlot = diagnostics?.currentSlot,
            safePartitions = FastbootProtocol.TYPICAL_FLASH_PARTITIONS
        )
        preflight.toDisplayText().lines().forEach { if (it.isNotBlank()) viewModel.log(it) }

        val sizeMb = "%.2f".format(file.length().toDouble() / 1024.0 / 1024.0)
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.preflight_title))
            .setMessage(
                "Раздел: $partition\n" +
                    "Файл: ${file.name}\n" +
                    "Размер: $sizeMb MB\n\n" +
                    preflight.toDisplayText()
            )
            .setNegativeButton(getString(R.string.cancel_upper), null)

        if (preflight.canProceed) {
            builder.setPositiveButton(getString(R.string.preflight_proceed)) { _, _ ->
                // ─── DOUBLE-CHECK для критических разделов ───
                if (PreflightValidator.requiresDoubleConfirm(partition)) {
                    confirmCriticalFlash(partition) { viewModel.runFlash(partition, file) }
                } else {
                    viewModel.runFlash(partition, file)
                }
            }
        } else {
            // Критическая ошибка — кнопка прошивки недоступна
            builder.setPositiveButton(getString(R.string.preflight_blocked), null)
        }
        builder.show()
    }

    /** Typed-confirm для опасных разделов (boot/recovery/vbmeta/...). */
    private fun confirmCriticalFlash(partition: String, onConfirmed: () -> Unit) {
        val phrase = when (partition.trim().lowercase(Locale.US)) {
            "vbmeta", "vbmeta_system", "vbmeta_vendor" -> "VBMETA"
            "boot", "init_boot", "vendor_boot", "recovery" -> "FLASH"
            else -> "CONFIRM"
        }
        showTypedDangerConfirmation(
            title = getString(R.string.double_check_title),
            message = getString(R.string.double_check_message, partition),
            requiredPhrase = phrase,
            logLabel = "critical partition flash: $partition"
        ) {
            onConfirmed()
        }
    }

    private fun showSideloadConfirmation(file: File) {
        if (viewModel.adbProtocol?.isConnected != true) {
            viewModel.log("ОШИБКА: Нет ADB-соединения")
            return
        }
        val sizeMb = "%.2f".format(file.length().toDouble() / 1024.0 / 1024.0)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_sideload_confirm_title))
            .setMessage(
                "Архив: ${file.name}\n" +
                    "Размер: $sizeMb MB\n\n" +
                    "Перед отправкой приложение проверит SHA-256/MD5 и сравнит их с .sha256/.md5, если такие файлы лежат рядом.\n\n" +
                    "Целевое устройство должно быть в recovery/sideload mode."
            )
            .setPositiveButton(getString(R.string.start_upper)) { _, _ -> viewModel.runSideload(file) }
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .show()
    }


    private fun chooseFlashQueueFile(partition: String) {
        showFileSelector(".img") { file ->
            flashQueue[partition] = file
            viewModel.log(getString(R.string.flash_queue_updated_log, partition, file.name))
            val guessed = guessPartitionFromFileName(file.name)
            if (guessed != null && guessed != partition) {
                viewModel.log(getString(R.string.flash_queue_filename_warning, file.name, guessed, partition))
            }
            updateFlashQueueUi()
            switchTab("fastboot")
        }
    }

    private fun clearFlashQueue() {
        flashQueue.clear()
        updateFlashQueueUi()
        viewModel.log(getString(R.string.flash_queue_cleared_log))
    }

    private fun updateFlashQueueUi() {
        val text = if (flashQueue.isEmpty()) {
            getString(R.string.flash_queue_empty)
        } else {
            flashQueue.entries.joinToString("\n") { (partition, file) ->
                val guessed = guessPartitionFromFileName(file.name)
                val warning = if (guessed != null && guessed != partition) "  ⚠ looks like $guessed" else ""
                "$partition ← ${file.name} (${formatFileSize(file.length())})$warning"
            }
        }
        findViewById<TextView>(R.id.tvFlashQueueSummary).text = text
    }

    private fun confirmFlashQueue() {
        if (safetyProfile == SafetyProfile.NOVICE) {
            showSafetyBlockedDialog(getString(R.string.safety_blocked_novice_flash))
            return
        }
        if (flashQueue.keys.any { isHighRiskPartition(it) } && !ensureHighRiskAllowed("flash queue with high-risk partition")) return
        if (flashQueue.isEmpty()) {
            viewModel.log(getString(R.string.flash_queue_empty_log))
            return
        }
        if (viewModel.fastbootProtocol?.isConnected != true) {
            viewModel.log(getString(R.string.error_no_fastboot))
            return
        }
        val diagnostics = viewModel.currentFastbootDiagnostics()
        if (diagnostics?.unlocked?.equals("no", ignoreCase = true) == true) {
            viewModel.log("⛔ Bootloader locked: очередь fastboot flash заблокирована.")
            MaterialAlertDialogBuilder(this)
                .setTitle("Очередь прошивки заблокирована")
                .setMessage("Устройство сообщает bootloader unlocked = no. Приложение не будет выполнять команды fastboot flash из очереди. Диагностика и остальные терминальные команды остаются доступны.")
                .setPositiveButton(getString(R.string.close_upper), null)
                .show()
            return
        }

        val queueText = flashQueue.entries.joinToString("\n") { (partition, file) ->
            val guessed = guessPartitionFromFileName(file.name)
            val warning = if (guessed != null && guessed != partition) "  ⚠ ${getString(R.string.flash_queue_filename_warning, file.name, guessed, partition)}" else ""
            "$partition ← ${file.name} (${formatFileSize(file.length())})$warning"
        }
        val extraWarning = buildString {
            if (diagnostics == null) append("\n\n⚠ Fastboot-данные устройства ещё не обновлены. Лучше нажмите «Данные» на главной странице.")
            if (flashQueue.containsKey("vbmeta")) append("\n\n⚠ В очереди есть vbmeta. Убедитесь, что образ подходит именно для этого устройства.")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.flash_queue_confirm_title))
            .setMessage(getString(R.string.flash_queue_confirm_message, queueText) + extraWarning)
            .setPositiveButton(getString(R.string.flash_upper)) { _, _ ->
                val phrase = if (flashQueue.keys.any { it.equals("vbmeta", ignoreCase = true) }) "VBMETA" else "FLASH QUEUE"
                showTypedDangerConfirmation(
                    title = getString(R.string.flash_queue_confirm_title),
                    message = getString(R.string.flash_queue_typed_confirm_message, queueText),
                    requiredPhrase = phrase,
                    logLabel = "flash queue (${flashQueue.size} item(s))"
                ) {
                    val items = flashQueue.entries.map { DeviceViewModel.FlashQueueItem(it.key, it.value) }
                    viewModel.runFlashQueue(items)
                }
            }
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .show()
    }

    private fun guessPartitionFromFileName(fileName: String): String? {
        val name = fileName.lowercase(Locale.US)
        return when {
            "vendor_boot" in name -> "vendor_boot"
            "init_boot" in name -> "init_boot"
            "vbmeta" in name -> "vbmeta"
            "recovery" in name -> "recovery"
            "dtbo" in name -> "dtbo"
            Regex("(^|[-_ .])boot([-_ .]|\\.img$)").containsMatchIn(name) -> "boot"
            else -> null
        }
    }

    private fun refreshDeviceDataFromUi() {
        viewModel.refreshFastbootDiagnostics()
        Handler(Looper.getMainLooper()).postDelayed({ updateDeviceOverview() }, 800L)
        Handler(Looper.getMainLooper()).postDelayed({ updateDeviceOverview() }, 2500L)
    }



    private fun isOpenReportsCommand(rawLower: String): Boolean {
        return rawLower == "reports" ||
            rawLower == "open reports" ||
            rawLower == "open-reports" ||
            rawLower == "reports open" ||
            rawLower == "report folder" ||
            rawLower == "reports folder" ||
            rawLower == "self-test reports" ||
            rawLower == "selftest reports" ||
            rawLower == "adb reports" ||
            rawLower == "fastboot reports"
    }

    private fun parseSelfTestAction(tokens: List<String>): TerminalAction {
        val sub = tokens.getOrNull(1)?.lowercase(Locale.US)
        return when (sub) {
            "report", "export", "log", "txt", "json" -> TerminalAction.SelfTestReport
            "forum", "zip", "bundle", "full", "support" -> TerminalAction.SelfTestForumReport
            else -> TerminalAction.SelfTest
        }
    }

    private fun isSelfTestReportCommand(rawLower: String): Boolean {
        return rawLower == "self-test report" ||
            rawLower == "self-test export" ||
            rawLower == "self-test log" ||
            rawLower == "self-test txt" ||
            rawLower == "self-test json" ||
            rawLower == "selftest report" ||
            rawLower == "selftest export" ||
            rawLower == "selftest log" ||
            rawLower == "selftest txt" ||
            rawLower == "selftest json" ||
            rawLower == "smoke-test report" ||
            rawLower == "smoke-test export" ||
            rawLower == "smoke-test log" ||
            rawLower == "smoke-test txt" ||
            rawLower == "smoke-test json" ||
            rawLower == "doctor report" ||
            rawLower == "doctor export" ||
            rawLower == "doctor log" ||
            rawLower == "doctor txt" ||
            rawLower == "doctor json" ||
            rawLower == "adb self-test report" ||
            rawLower == "fastboot self-test report"
    }

    private fun isSelfTestForumReportCommand(rawLower: String): Boolean {
        return rawLower == "self-test forum" ||
            rawLower == "self-test zip" ||
            rawLower == "self-test bundle" ||
            rawLower == "self-test full" ||
            rawLower == "self-test support" ||
            rawLower == "selftest forum" ||
            rawLower == "selftest zip" ||
            rawLower == "selftest bundle" ||
            rawLower == "selftest full" ||
            rawLower == "selftest support" ||
            rawLower == "smoke-test forum" ||
            rawLower == "smoke-test zip" ||
            rawLower == "smoke-test bundle" ||
            rawLower == "smoke-test full" ||
            rawLower == "smoke-test support" ||
            rawLower == "doctor forum" ||
            rawLower == "doctor zip" ||
            rawLower == "doctor bundle" ||
            rawLower == "doctor full" ||
            rawLower == "doctor support" ||
            rawLower == "adb self-test forum" ||
            rawLower == "fastboot self-test forum"
    }

    private fun runSelfTestReportFromUi() {
        if (!ensureWorkspaceReady()) return
        viewModel.runSelfTestReportArtifacts { artifacts ->
            runOnUiThread { showSelfTestReportDialog(artifacts) }
        }
    }

    private fun showSelfTestReportDialog(artifacts: DeviceViewModel.SelfTestReportArtifacts) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Self-test отчёт создан")
            .setMessage("Файлы сохранены:\n${artifacts.textFile.absolutePath}\n${artifacts.jsonFile.absolutePath}\n\nОтчёты санитизированы: серийники, приватные пути и host-идентификаторы замаскированы. TXT удобно читать человеку, JSON удобно прикладывать к баг-репорту.")
            .setPositiveButton(getString(R.string.share_upper)) { _, _ -> shareGenericFile(artifacts.textFile, "text/plain", "ADB Fastboot Tool self-test report") }
            .setNeutralButton("ОТКРЫТЬ REPORTS") { _, _ -> openReportsFolder() }
            .setNegativeButton(getString(R.string.close_upper), null)
            .show()
    }

    private fun runSelfTestForumReportFromUi() {
        if (!ensureWorkspaceReady()) return
        viewModel.runSelfTestReportArtifacts { artifacts ->
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val report = ForumReportManager.createReport(
                        context = this@MainActivity,
                        usbManager = usbManager,
                        workspace = workspacePath,
                        currentLogFile = viewModel.currentLogFile(),
                        visibleLogLines = viewModel.logSnapshot(),
                        connectionInfo = viewModel.currentConnectionInfo(),
                        fastbootDiagnostics = viewModel.currentFastbootDiagnostics(),
                        adbDiagnostics = viewModel.currentAdbDiagnostics(),
                        debugLogging = viewModel.isDebugLoggingEnabled(),
                        extraFiles = listOf(
                            artifacts.textFile to "self-test/selftest.txt",
                            artifacts.jsonFile to "self-test/selftest.json"
                        )
                    )
                    viewModel.log("✅ Self-test ZIP для форума создан: ${report.absolutePath} (privacy: sanitized)")
                    runOnUiThread { showForumReportDialog(report) }
                } catch (e: Exception) {
                    viewModel.log("ОШИБКА: не удалось создать ZIP с self-test: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }


    private fun createForumReport() {
        if (!ensureWorkspaceReady()) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val report = ForumReportManager.createReport(
                    context = this@MainActivity,
                    usbManager = usbManager,
                    workspace = workspacePath,
                    currentLogFile = viewModel.currentLogFile(),
                    visibleLogLines = viewModel.logSnapshot(),
                    connectionInfo = viewModel.currentConnectionInfo(),
                    fastbootDiagnostics = viewModel.currentFastbootDiagnostics(),
                    adbDiagnostics = viewModel.currentAdbDiagnostics(),
                    debugLogging = viewModel.isDebugLoggingEnabled()
                )
                viewModel.log("✅ Отчёт для форума создан: ${report.absolutePath} (privacy: sanitized)")
                runOnUiThread { showForumReportDialog(report) }
            } catch (e: Exception) {
                viewModel.log("ОШИБКА: не удалось создать отчёт для форума: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun showForumReportDialog(report: File) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_report_title))
            .setMessage(getString(R.string.report_created_message, report.absolutePath))
            .setPositiveButton(getString(R.string.share_upper)) { _, _ -> shareGenericFile(report, "application/zip", "ADB Fastboot Tool forum report") }
            .setNeutralButton("ОТКРЫТЬ REPORTS") { _, _ -> openReportsFolder() }
            .setNegativeButton(getString(R.string.close_upper), null)
            .show()
    }

    private fun openReportsFolder() {
        if (!ensureWorkspaceReady()) return
        val reportsDir = File(workspacePath, "reports")
        if (!reportsDir.exists() && !reportsDir.mkdirs()) {
            viewModel.log("ОШИБКА: не удалось создать папку reports: ${reportsDir.absolutePath}")
            return
        }

        val documentId = "primary:Download/$folderName/reports"
        val treeUri = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", documentId)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, treeUri)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        }

        try {
            viewModel.log("Открываю папку отчётов: /sdcard/Download/$folderName/reports")
            startActivity(intent)
        } catch (e: Exception) {
            viewModel.log("ОШИБКА: не удалось открыть DocumentsUI для reports: ${e.message ?: e.javaClass.simpleName}")
            copyTextToClipboard("ADB Fastboot reports", reportsDir.absolutePath, "Путь к reports скопирован")
        }
    }

    private fun shareGenericFile(file: File, mimeType: String, subject: String) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, getString(R.string.share_file_text, file.name))
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(contentResolver, file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share_file_chooser)))
        } catch (e: Exception) {
            viewModel.log("ОШИБКА: не удалось отправить файл: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun copyWorkspacePath() {
        val path = "/sdcard/Download/$folderName"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ADB Fastboot folder", path))
        viewModel.log(getString(R.string.workspace_path_copied, path))
    }

    private fun showLogActions() {
        val file = viewModel.currentLogFile()
        if (file == null || !file.exists()) {
            viewModel.log(getString(R.string.log_file_not_created))
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_log_title))
            .setMessage(getString(R.string.log_dialog_message, file.absolutePath))
            .setPositiveButton(getString(R.string.copy_path_upper)) { _, _ ->
                copyTextToClipboard("ADB Fastboot log", file.absolutePath, getString(R.string.log_path_copied))
            }
            .setNeutralButton(getString(R.string.share_file_upper)) { _, _ -> shareLogFile(file) }
            .setNegativeButton(getString(R.string.close_upper), null)
            .show()
    }

    private fun shareLogFile(file: File) {
        shareGenericFile(file, "text/plain", "ADB Fastboot Tool log")
    }

    private fun copyTextToClipboard(label: String, text: String, logMessage: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        viewModel.log(logMessage)
    }

    private fun showHelpDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_help_title))
            .setMessage(getString(R.string.help_long))
            .setPositiveButton(getString(R.string.ok_understood_upper), null)
            .show()
    }


    private fun enableOverlayProtection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                window.setHideOverlayWindows(true)
                if (!overlayProtectionLogged) {
                    viewModel.log(getString(R.string.overlay_protection_enabled))
                    overlayProtectionLogged = true
                }
            } catch (e: Exception) {
                if (!overlayProtectionLogged) {
                    viewModel.log(getString(R.string.overlay_protection_error, e.message ?: e.javaClass.simpleName))
                    overlayProtectionLogged = true
                }
            }
        } else if (!overlayProtectionLogged) {
            viewModel.log(getString(R.string.overlay_protection_unsupported))
            overlayProtectionLogged = true
        }
    }

    private fun showPermissionsDialog() {
        val notifications = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            getString(R.string.permission_status_not_required)
        } else if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            getString(R.string.permission_status_granted)
        } else {
            getString(R.string.permission_status_not_granted)
        }

        val storage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) getString(R.string.permission_status_granted) else getString(R.string.permission_status_not_granted)
        } else {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) getString(R.string.permission_status_granted) else getString(R.string.permission_status_not_granted)
        }

        val battery = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            getString(R.string.permission_status_not_required)
        } else {
            val powerManager = getSystemService(PowerManager::class.java)
            if (powerManager.isIgnoringBatteryOptimizations(packageName)) getString(R.string.permission_status_granted) else getString(R.string.permission_status_optional)
        }

        val overlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getString(R.string.permission_status_enabled)
        } else {
            getString(R.string.permission_status_not_supported)
        }

        val message = getString(
            R.string.permissions_dialog_message,
            storage,
            notifications,
            battery,
            overlay
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_permissions_title))
            .setMessage(message)
            .setPositiveButton(getString(R.string.ok_understood_upper), null)
            .setNeutralButton(getString(R.string.open_app_settings_upper)) { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                } catch (e: Exception) {
                    viewModel.log(getString(R.string.app_settings_open_error, e.message ?: e.javaClass.simpleName))
                }
            }
            .setNegativeButton(getString(R.string.close_upper), null)
            .show()
    }

    private fun logBatteryOptimizationState() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val powerManager = getSystemService(PowerManager::class.java)
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            viewModel.log("⚠️ Для долгой прошивки рекомендуется отключить оптимизацию батареи: кнопка «Батарея».")
        }
    }

    private fun showBatteryOptimizationDialog() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            viewModel.log(getString(R.string.battery_optimization_old_android))
            return
        }

        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            viewModel.log(getString(R.string.battery_optimization_already_disabled))
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_battery_title))
            .setMessage(getString(R.string.battery_optimization_message))
            .setPositiveButton(getString(R.string.open_upper)) { _, _ -> requestDisableBatteryOptimization() }
            .setNegativeButton(getString(R.string.later_upper), null)
            .show()
    }

    private fun requestDisableBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: Exception) {
                viewModel.log(getString(R.string.battery_optimization_open_error, e.message ?: e.javaClass.simpleName))
            }
        }
    }


    private fun applySavedLanguage() {
        val tag = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_LANGUAGE_TAG, "") ?: ""
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    private fun showLanguageDialog() {
        val options = arrayOf(
            getString(R.string.language_system),
            getString(R.string.language_russian),
            getString(R.string.language_english)
        )
        val tags = arrayOf("", "ru", "en")
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentTag = prefs.getString(PREF_LANGUAGE_TAG, "") ?: ""
        val checked = tags.indexOf(currentTag).takeIf { it >= 0 } ?: 0

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.language_dialog_title))
            .setMessage(getString(R.string.language_dialog_message))
            .setSingleChoiceItems(options, checked) { dialog, which ->
                val selectedTag = tags[which]
                prefs.edit().putString(PREF_LANGUAGE_TAG, selectedTag).apply()
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(selectedTag))
                dialog.dismiss()
                viewModel.log(getString(R.string.language_changed))
                recreate()
            }
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .show()
    }

    // ─── UI ──────────────────────────────────────────────────────────────────


    private fun updateDeviceOverview() {
        val state = viewModel.connectionState.value ?: DeviceViewModel.ConnectionState.NONE
        val diagnostics = viewModel.currentFastbootDiagnostics()
        val connectionInfo = viewModel.currentConnectionInfo()
        val modeText = when (state) {
            DeviceViewModel.ConnectionState.NONE -> getString(R.string.status_no_device)
            DeviceViewModel.ConnectionState.CONNECTING -> getString(R.string.status_connecting)
            DeviceViewModel.ConnectionState.FASTBOOT -> getString(R.string.status_fastboot)
            DeviceViewModel.ConnectionState.ADB -> getString(R.string.status_adb)
            DeviceViewModel.ConnectionState.ERROR -> getString(R.string.status_error)
        }

        val product = diagnostics?.product ?: extractConnectionField(connectionInfo, "Устройство") ?: "—"
        val slot = diagnostics?.currentSlot ?: "—"
        val unlocked = diagnostics?.unlocked ?: "—"
        val maxDownload = diagnostics?.maxDownloadSizeRaw?.let { raw ->
            val bytes = diagnostics.maxDownloadSizeBytes
            if (bytes != null && bytes > 0L) "$raw / ${formatFileSize(bytes)}" else raw
        } ?: "—"
        val workspace = if (::workspacePath.isInitialized) workspacePath.absolutePath else "/sdcard/Download/$folderName"

        val serialno = diagnostics?.serialno?.let { " | Serial: $it" } ?: ""
        val slotExtra = buildString {
            diagnostics?.slotCount?.let { append(" | Слотов: $it") }
            diagnostics?.slotSuffix?.let { append(" | Суффикс: $it") }
        }
        val slotDisplay = if (slotExtra.isNotBlank()) "$slot$slotExtra" else slot
        val vbl = diagnostics?.versionBootloader?.let { " | BL: $it" } ?: ""

        val fastbootMode = diagnostics?.isUserspace?.let { if (it.equals("yes", ignoreCase = true)) " / fastbootd" else " / bootloader" } ?: ""
        findViewById<TextView>(R.id.tvDeviceModeValue).text = "Режим: $modeText$fastbootMode"
        findViewById<TextView>(R.id.tvDeviceProductValue).text = "Модель/product: $product$serialno$vbl"
        findViewById<TextView>(R.id.tvDeviceSlotValue).text = "Слот: $slotDisplay"
        findViewById<TextView>(R.id.tvDeviceUnlockedValue).text = "Bootloader: $unlocked"
        val maxFetch = diagnostics?.maxFetchSizeRaw?.let { raw ->
            val bytes = diagnostics.maxFetchSizeBytes
            if (bytes != null && bytes > 0L) "$raw / ${formatFileSize(bytes)}" else raw
        }
        val superPart = diagnostics?.superPartitionName?.let { " | super: $it" } ?: ""
        findViewById<TextView>(R.id.tvDeviceMaxDownloadValue).text = "Max download: $maxDownload${maxFetch?.let { " | fetch: $it" } ?: ""}$superPart"
        findViewById<TextView>(R.id.tvDeviceWorkspaceValue).text = "Папка: $workspace"
    }


    private fun renderSelfTestStatus(status: DeviceViewModel.SelfTestStatus) {
        val (prefix, color) = when (status.result) {
            DeviceViewModel.SelfTestResult.NOT_RUN -> "SELF-TEST: NOT RUN" to "#94A3B8"
            DeviceViewModel.SelfTestResult.RUNNING -> "SELF-TEST: RUNNING" to "#F59E0B"
            DeviceViewModel.SelfTestResult.PASS -> "SELF-TEST: PASS" to "#22C55E"
            DeviceViewModel.SelfTestResult.WARN_FAIL -> "SELF-TEST: WARN/FAIL" to "#EF4444"
        }
        val reportInfo = listOfNotNull(
            status.textReportPath?.let { "TXT: ${File(it).name}" },
            status.jsonReportPath?.let { "JSON: ${File(it).name}" }
        ).joinToString(" | ")
        tvSelfTestStatus.text = buildString {
            append(prefix)
            status.updatedAt?.let { append(" · ").append(it) }
            append("\n")
            append(status.summary)
            if (reportInfo.isNotBlank()) append("\n").append(reportInfo)
        }
        tvSelfTestStatus.setTextColor(android.graphics.Color.parseColor(color))
    }

    private fun extractConnectionField(info: String?, field: String): String? {
        if (info.isNullOrBlank()) return null
        val marker = "$field:"
        val start = info.indexOf(marker)
        if (start < 0) return null
        val after = info.substring(start + marker.length).trim()
        return after.substringBefore("|").trim().ifBlank { null }
    }

    private fun loadSafetyPreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(PREF_SAFETY_PROFILE, null)
        safetyProfile = when (stored?.uppercase(Locale.US)) {
            SafetyProfile.NOVICE.name -> SafetyProfile.NOVICE
            SafetyProfile.EXPERT.name -> SafetyProfile.EXPERT
            SafetyProfile.STANDARD.name -> SafetyProfile.STANDARD
            else -> if (prefs.getBoolean(PREF_EXPERT_MODE, false)) SafetyProfile.EXPERT else SafetyProfile.STANDARD
        }
        expertModeEnabled = safetyProfile == SafetyProfile.EXPERT
        highRiskActionsUnlocked = prefs.getBoolean(PREF_HIGH_RISK_UNLOCKED, false) && safetyProfile == SafetyProfile.EXPERT
    }

    private fun setSafetyProfile(profile: SafetyProfile) {
        safetyProfile = profile
        expertModeEnabled = profile == SafetyProfile.EXPERT
        if (profile != SafetyProfile.EXPERT) highRiskActionsUnlocked = false
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_SAFETY_PROFILE, profile.name)
            .putBoolean(PREF_EXPERT_MODE, expertModeEnabled)
            .putBoolean(PREF_HIGH_RISK_UNLOCKED, highRiskActionsUnlocked)
            .apply()
        updateSafetyProfileUi()
        viewModel.log(getString(R.string.safety_profile_changed_log, safetyProfileTitle()))
    }

    private fun showSafetyProfileDialog() {
        val labels = arrayOf(
            getString(R.string.safety_profile_novice),
            getString(R.string.safety_profile_standard),
            getString(R.string.safety_profile_expert)
        )
        val profiles = arrayOf(SafetyProfile.NOVICE, SafetyProfile.STANDARD, SafetyProfile.EXPERT)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.safety_profile_dialog_title))
            .setItems(labels) { _, which -> setSafetyProfile(profiles[which]) }
            .setNegativeButton(getString(R.string.cancel_upper), null)
            .show()
    }

    private fun toggleHighRiskActions() {
        if (safetyProfile != SafetyProfile.EXPERT) {
            showSafetyBlockedDialog(getString(R.string.safety_high_risk_expert_only))
            return
        }
        if (highRiskActionsUnlocked) {
            highRiskActionsUnlocked = false
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_HIGH_RISK_UNLOCKED, false)
                .apply()
            updateSafetyProfileUi()
            viewModel.log(getString(R.string.safety_high_risk_disabled_log))
            return
        }
        showTypedDangerConfirmation(
            title = getString(R.string.safety_high_risk_title),
            message = getString(R.string.safety_high_risk_message),
            requiredPhrase = "EXPERT",
            logLabel = "enable high-risk safety layer"
        ) {
            highRiskActionsUnlocked = true
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_HIGH_RISK_UNLOCKED, true)
                .apply()
            updateSafetyProfileUi()
            viewModel.log(getString(R.string.safety_high_risk_enabled_log))
        }
    }

    private fun safetyProfileTitle(): String = when (safetyProfile) {
        SafetyProfile.NOVICE -> getString(R.string.safety_profile_novice)
        SafetyProfile.STANDARD -> getString(R.string.safety_profile_standard)
        SafetyProfile.EXPERT -> getString(R.string.safety_profile_expert)
    }

    private fun updateSafetyProfileUi() {
        expertModeEnabled = safetyProfile == SafetyProfile.EXPERT
        findViewById<Button>(R.id.btnExpertMode).text = when (safetyProfile) {
            SafetyProfile.NOVICE -> getString(R.string.safety_header_novice)
            SafetyProfile.STANDARD -> getString(R.string.safety_header_standard)
            SafetyProfile.EXPERT -> getString(R.string.safety_header_expert)
        }
        findViewById<View>(R.id.consoleInputBar).visibility = if (expertModeEnabled) View.VISIBLE else View.GONE
        findViewById<View>(R.id.tvExpertModeHint).visibility = if (expertModeEnabled) View.GONE else View.VISIBLE

        val description = when (safetyProfile) {
            SafetyProfile.NOVICE -> getString(R.string.safety_profile_novice_desc)
            SafetyProfile.STANDARD -> getString(R.string.safety_profile_standard_desc)
            SafetyProfile.EXPERT -> if (highRiskActionsUnlocked) getString(R.string.safety_profile_expert_unlocked_desc) else getString(R.string.safety_profile_expert_desc)
        }
        findViewById<TextView>(R.id.tvSafetyProfileValue).text = description
        findViewById<Button>(R.id.btnSafetyHighRisk).text = if (highRiskActionsUnlocked) getString(R.string.safety_high_risk_unlocked) else getString(R.string.safety_high_risk_locked)

        val guidedFlashAllowed = safetyProfile != SafetyProfile.NOVICE
        val highRiskAllowed = safetyProfile == SafetyProfile.EXPERT && highRiskActionsUnlocked
        setButtonSafetyState(R.id.btnXiaomiRomFlashSaveData, guidedFlashAllowed)
        setButtonSafetyState(R.id.btnXiaomiRomFlashClean, highRiskAllowed)
        setButtonSafetyState(R.id.btnRecoverySwitchSlot, highRiskAllowed)
        setButtonSafetyState(R.id.btnRecoveryFlashBoot, guidedFlashAllowed)
        setButtonSafetyState(R.id.btnRecoveryFlashInitBoot, guidedFlashAllowed)
        setButtonSafetyState(R.id.btnRecoveryFlashVendorBoot, guidedFlashAllowed)
        setButtonSafetyState(R.id.btnRecoveryFlashVbmeta, highRiskAllowed)
        setButtonSafetyState(R.id.btnMagiskAutoFlash, guidedFlashAllowed)
        setButtonSafetyState(R.id.btnMagiskFlashBoot, guidedFlashAllowed)
        setButtonSafetyState(R.id.btnMagiskFlashInitBoot, guidedFlashAllowed)
        setButtonSafetyState(R.id.btnQueueStart, guidedFlashAllowed)
        listOf(R.id.btnFlashBoot, R.id.btnFlashInitBoot, R.id.btnFlashRecovery, R.id.btnFlashVendorBoot, R.id.btnFlashDtbo).forEach {
            setButtonSafetyState(it, guidedFlashAllowed)
        }
        setButtonSafetyState(R.id.btnSafetyHighRisk, safetyProfile == SafetyProfile.EXPERT)
    }

    private fun setButtonSafetyState(id: Int, enabled: Boolean) {
        val view = findViewById<View>(id)
        view.isEnabled = enabled
        view.alpha = if (enabled) 1.0f else 0.38f
    }

    private fun ensureGuidedFlashAllowed(partition: String): Boolean {
        if (safetyProfile == SafetyProfile.NOVICE) {
            showSafetyBlockedDialog(getString(R.string.safety_blocked_novice_flash))
            return false
        }
        if (isHighRiskPartition(partition) && !ensureHighRiskAllowed("flash $partition")) return false
        return true
    }

    private fun ensureHighRiskAllowed(action: String): Boolean {
        if (safetyProfile == SafetyProfile.EXPERT && highRiskActionsUnlocked) return true
        showSafetyBlockedDialog(getString(R.string.safety_high_risk_blocked_message, action, safetyProfileTitle()))
        return false
    }

    private fun showSafetyBlockedDialog(message: String) {
        viewModel.log(getString(R.string.safety_action_blocked_log, message))
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.safety_blocked_title))
            .setMessage(message)
            .setPositiveButton(getString(R.string.close_upper), null)
            .show()
    }

    private fun isHighRiskPartition(partition: String): Boolean {
        val clean = partition.trim().lowercase(Locale.US)
        return clean == "vbmeta" ||
            clean == "vbmeta_system" ||
            clean == "vbmeta_vendor" ||
            clean == "userdata" ||
            clean == "metadata" ||
            clean == "super"
    }

    private fun showOperationWindow() {
        // v4 UI: операция НЕ переключает вкладку — лог всегда виден внизу.
        // Просто переключаемся на HOME чтобы был виден статус устройства.
        if (selectedWindow == "diagnostics") switchTab("home")
    }

    private fun switchTab(tab: String) {
        tabController.switchTab(tab)
    }



    private fun renderOperationSteps(steps: List<DeviceViewModel.OperationStep>) {
        if (!::tvOperationStepQueue.isInitialized) return
        if (steps.isEmpty()) {
            tvOperationStepQueue.text = getString(R.string.layout_operation_steps_empty)
            tvOperationStepQueue.setTextColor(android.graphics.Color.parseColor("#64748B"))
            return
        }
        val runningIndex = steps.indexOfFirst { it.status == DeviceViewModel.OperationStepStatus.RUNNING }
        val visibleSteps = when {
            steps.size <= 12 -> steps
            runningIndex >= 0 -> {
                val from = (runningIndex - 4).coerceAtLeast(0)
                val to = (runningIndex + 8).coerceAtMost(steps.size)
                steps.subList(from, to)
            }
            else -> steps.take(12)
        }
        val hiddenCount = steps.size - visibleSteps.size
        val body = buildString {
            visibleSteps.forEach { step ->
                val icon = when (step.status) {
                    DeviceViewModel.OperationStepStatus.PENDING -> "·"
                    DeviceViewModel.OperationStepStatus.RUNNING -> "▶"
                    DeviceViewModel.OperationStepStatus.OK -> "✓"
                    DeviceViewModel.OperationStepStatus.FAILED -> "✕"
                    DeviceViewModel.OperationStepStatus.SKIPPED -> "↷"
                    DeviceViewModel.OperationStepStatus.INFO -> "i"
                }
                append(icon)
                append(' ')
                append(step.index)
                append('/')
                append(step.total)
                append(' ')
                append(step.title.take(96))
                step.subtitle?.takeIf { it.isNotBlank() }?.let {
                    append(" — ")
                    append(it.take(72))
                }
                append('
')
            }
            if (hiddenCount > 0) append(getString(R.string.layout_operation_steps_more, hiddenCount))
        }.trimEnd()
        tvOperationStepQueue.text = body
        val hasFailed = steps.any { it.status == DeviceViewModel.OperationStepStatus.FAILED }
        val hasRunning = steps.any { it.status == DeviceViewModel.OperationStepStatus.RUNNING }
        val allOk = steps.isNotEmpty() && steps.all { it.status == DeviceViewModel.OperationStepStatus.OK || it.status == DeviceViewModel.OperationStepStatus.SKIPPED }
        val color = when {
            hasFailed -> "#F87171"
            hasRunning -> "#F59E0B"
            allOk -> "#34D399"
            else -> "#94A3B8"
        }
        tvOperationStepQueue.setTextColor(android.graphics.Color.parseColor(color))
    }

    private fun updateOperationCenter(lines: List<String>) {
        if (!::tvOperationCenterStatus.isInitialized || !::tvOperationCenterLastEvent.isInitialized) return
        val active = viewModel.operationActive.value == true
        val recent = lines.asReversed().firstOrNull { line ->
            val trimmed = line.trim()
            trimmed.isNotBlank() &&
                !trimmed.startsWith("💡") &&
                !trimmed.contains("System terminal ready", ignoreCase = true) &&
                !trimmed.contains("Full terminal", ignoreCase = true)
        }
        val recentText = recent?.let { if (it.length > 260) it.take(257) + "…" else it }
        val (status, color) = when {
            active -> getString(R.string.layout_operation_center_running) to "#F59E0B"
            recentText == null -> getString(R.string.layout_operation_center_idle) to "#94A3B8"
            recentText.contains("❌") || recentText.contains("ОШИБКА") || recentText.contains("FAILED", ignoreCase = true) || recentText.contains("БЛОКИРОВКА") ->
                getString(R.string.layout_operation_center_failed) to "#F87171"
            recentText.contains("✅") || recentText.contains("COMPLETED", ignoreCase = true) || recentText.contains("ЗАВЕРШЕНА") ->
                getString(R.string.layout_operation_center_completed) to "#34D399"
            recentText.contains("⚠") || recentText.contains("WARN", ignoreCase = true) ->
                getString(R.string.layout_operation_center_warning) to "#F59E0B"
            else -> getString(R.string.layout_operation_center_idle) to "#94A3B8"
        }
        tvOperationCenterStatus.text = status
        tvOperationCenterStatus.setTextColor(android.graphics.Color.parseColor(color))
        tvOperationCenterLastEvent.text = recentText?.let { getString(R.string.layout_operation_center_last_event, it) }
            ?: getString(R.string.layout_operation_center_last_event_empty)

        val cancelButton = findViewById<Button>(R.id.btnOperationCenterCancel)
        cancelButton.isEnabled = active
        cancelButton.alpha = if (active) 1.0f else 0.45f
    }

    // FIX #2: инкрементальный renderLog — добавляем только новые строки,
    // а не перестраиваем весь HTML с нуля. Устраняет лаги при getvar:all (100+ строк).
    private var renderedCount = 0

    private fun renderLog(lines: List<String>) {
        if (lines.isEmpty()) { tvLog.text = ""; renderedCount = 0; return }

        // Лог был очищен (clearLog) — сбрасываем
        if (lines.size < renderedCount) { tvLog.text = ""; renderedCount = 0 }

        val newLines = lines.drop(renderedCount)
        if (newLines.isEmpty()) return

        val sb = android.text.SpannableStringBuilder()
        newLines.forEach { line ->
            val color = when {
                line.contains("ОШИБКА") || line.contains("БЛОКИРОВКА") || line.contains("❌") -> "#F87171"
                line.startsWith("💡") -> "#FBBF24"
                line.contains("✅") || line.contains("===") || line.contains("ЗАВЕРШЕНА") -> "#34D399"
                line.startsWith(">") || line.startsWith("->") || line.startsWith("<-") -> "#94A3B8"
                line.startsWith("⏳") || line.startsWith("⚠") -> "#F59E0B"
                line.startsWith("[") -> "#475569"
                else -> "#1A7A4A"
            }
            val safe = TextUtils.htmlEncode(line)
            sb.append(Html.fromHtml("<font color=\"$color\">$safe</font><br>", Html.FROM_HTML_MODE_LEGACY))
        }
        tvLog.append(sb)
        renderedCount = lines.size
        scrollViewLog.post { scrollViewLog.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    // ─── Авто-снижение яркости во время записи ───────────────────────────────
    private var savedBrightness: Float = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

    private fun applyReducedBrightness() {
        try {
            val lp = window.attributes
            savedBrightness = lp.screenBrightness
            lp.screenBrightness = 0.15f // 15% — экран читаем, но без перегрева
            window.attributes = lp
        } catch (_: Exception) {
        }
    }

    private fun restoreBrightness() {
        try {
            val lp = window.attributes
            lp.screenBrightness = savedBrightness
            window.attributes = lp
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(usbReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver could already be unregistered by the system; safe to ignore.
        }
        usbPermissionTimeouts.values.forEach { usbPermissionHandler.removeCallbacks(it) }
        usbPermissionTimeouts.clear()
        autoScanHandler.removeCallbacks(autoScanRunnable)
    }

    companion object {
        private const val USB_PERMISSION_TIMEOUT_MS = 30_000L
        private const val AUTO_SCAN_INTERVAL_MS = 3_000L
        private const val DEFAULT_COPY_BUFFER_SIZE = 1024 * 1024
        private const val PREFS_NAME = "settings"
        private const val PREF_LANGUAGE_TAG = "language_tag"
        private const val PREF_EXPERT_MODE = "expert_mode"
        private const val PREF_SAFETY_PROFILE = "safety_profile"
        private const val PREF_HIGH_RISK_UNLOCKED = "high_risk_unlocked"
    }
}
