package ru.forum.adbfastboottool

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class DeviceViewModel(application: Application) : AndroidViewModel(application) {

    enum class ConnectionState { NONE, CONNECTING, FASTBOOT, ADB, ERROR }

    data class SelfTestReportArtifacts(
        val textFile: File,
        val jsonFile: File
    )

    enum class SelfTestResult { NOT_RUN, RUNNING, PASS, WARN_FAIL }

    data class SelfTestStatus(
        val result: SelfTestResult,
        val summary: String,
        val updatedAt: String? = null,
        val textReportPath: String? = null,
        val jsonReportPath: String? = null
    )

    enum class OperationStepStatus { PENDING, RUNNING, OK, FAILED, SKIPPED, INFO }

    data class OperationStep(
        val index: Int,
        val total: Int,
        val title: String,
        val subtitle: String? = null,
        val status: OperationStepStatus = OperationStepStatus.PENDING
    )

    private val _connectionState   = MutableLiveData(ConnectionState.NONE)
    val connectionState: LiveData<ConnectionState> = _connectionState

    private val _operationActive   = MutableLiveData(false)
    val operationActive: LiveData<Boolean> = _operationActive

    private val _operationSteps = MutableLiveData<List<OperationStep>>(emptyList())
    val operationSteps: LiveData<List<OperationStep>> = _operationSteps

    private val _selfTestStatus = MutableLiveData(
        SelfTestStatus(
            result = SelfTestResult.NOT_RUN,
            summary = "Self-test ещё не запускался"
        )
    )
    val selfTestStatus: LiveData<SelfTestStatus> = _selfTestStatus

    private val _connectionInfo    = MutableLiveData<String?>(null)
    val connectionInfo: LiveData<String?> = _connectionInfo

    private val _fastbootDiagnostics = MutableLiveData<FastbootProtocol.DeviceDiagnostics?>(null)
    val fastbootDiagnostics: LiveData<FastbootProtocol.DeviceDiagnostics?> = _fastbootDiagnostics

    data class FlashQueueItem(val partition: String, val file: File)

    private data class PendingXiaomiRomFlash(
        val file: File,
        val workspaceDir: File,
        val mode: XiaomiFastbootRomManager.FlashMode,
        val resumeFromActionIndex: Int,
        val expectedProducts: List<String>,
        val requestedAtMs: Long = System.currentTimeMillis()
    )

    private data class XiaomiRomResumeState(
        val file: File,
        val workspaceDir: File,
        val mode: XiaomiFastbootRomManager.FlashMode,
        val resumeFromActionIndex: Int,
        val expectedProducts: List<String>,
        val reason: String,
        val markerFile: File?
    )

    private data class XiaomiRomFlashSession(
        val reportFile: File,
        val sourceName: String,
        val modeText: String,
        val scriptName: String,
        val totalActions: Int,
        val startedAtMs: Long = System.currentTimeMillis()
    )

    private var pendingXiaomiRomFlash: PendingXiaomiRomFlash? = null

    private fun text(resId: Int, vararg args: Any): String =
        getApplication<Application>().getString(resId, *args)

    private val initialLines = listOf(
        text(R.string.system_terminal_ready),
        text(R.string.security_full_terminal_active)
    )

    private val _logLines = MutableLiveData(initialLines)
    val logLines: LiveData<List<String>> = _logLines

    private val lines    = initialLines.toMutableList()
    private val logLock  = Any()
    private val operationStepLock = Any()
    private var operationStepSnapshot: List<OperationStep> = emptyList()
    private var logFile:     File? = null
    private var profilesDir: File? = null
    private val adbKeyDir: File = File(application.filesDir, "adbkeys")

    var fastbootProtocol: FastbootProtocol? = null
        private set
    var adbProtocol: AdbProtocol? = null
        private set

    private var activeJob: Job? = null
    private var connectedDeviceInfo: String? = null
    private var debugLoggingEnabled: Boolean = false
    private var operationWakeLock: PowerManager.WakeLock? = null

    // FIX #9: AtomicLong вместо обычного Long — потокобезопасно
    private val operationGeneration = AtomicLong(0L)

    // ─── ЛОГИРОВАНИЕ ─────────────────────────────────────────────────────────

    fun configureLogDirectory(workspacePath: File) {
        profilesDir = File(workspacePath, "profiles")
        DeviceProfileManager.ensureProfilesDirectory(workspacePath) { msg -> log(msg) }
        fastbootProtocol?.profilesDirectory = profilesDir

        val logsDir = File(workspacePath, "logs")
        if (!logsDir.exists() && !logsDir.mkdirs()) {
            log("⚠️ Не удалось создать папку логов: ${logsDir.absolutePath}")
            return
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(Date())
        val createdLog = File(logsDir, "log-$stamp.txt")
        logFile = createdLog
        appendRawToLogFile("# ADB Fastboot Tool log\n# Created: $stamp\n\n")
        synchronized(logLock) { lines.forEach { appendRawToLogFile(formatLogLine(it)) } }
        log("Лог-файл: /sdcard/Download/NekoMiFlash/logs/${createdLog.name}")
    }

    fun log(message: String) {
        synchronized(logLock) {
            if (message.trim().startsWith("💡")) {
                lines.removeAll { it.trim().startsWith("💡") }
            }
            lines.add(message)
            if (lines.size > 1000) lines.removeAt(0)
            _logLines.postValue(lines.toList())
            appendRawToLogFile(formatLogLine(message))
        }
    }

    fun clearLog() {
        synchronized(logLock) { lines.clear() }
        log(text(R.string.log_cleared))
    }

    private fun appendRawToLogFile(text: String) {
        try { logFile?.appendText(text) } catch (_: Exception) {}
    }

    private fun formatLogLine(message: String): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return "[$stamp] $message\n"
    }

    // ─── ПОДКЛЮЧЕНИЕ ─────────────────────────────────────────────────────────

    fun connectDevice(usbManager: UsbManager, device: UsbDevice, isFastboot: Boolean) {
        disconnectCurrent()
        connectedDeviceInfo = buildDeviceInfo(device, isFastboot)
        _connectionInfo.postValue(connectedDeviceInfo)
        _fastbootDiagnostics.postValue(null)
        _connectionState.postValue(ConnectionState.CONNECTING)

        activeJob = viewModelScope.launch(Dispatchers.IO) {
            if (isFastboot) {
                val proto = FastbootProtocol(usbManager, device) { msg -> log(msg) }
                proto.debugLogging = debugLoggingEnabled
                if (proto.connect()) {
                    proto.profilesDirectory = profilesDir
                    fastbootProtocol = proto
                    _connectionState.postValue(ConnectionState.FASTBOOT)
                    logConnectionStatus()
                    val diagnostics = proto.refreshDiagnostics(force = true)
                    _fastbootDiagnostics.postValue(diagnostics)
                    schedulePendingXiaomiRomResumeIfReady(diagnostics)
                } else {
                    _connectionState.postValue(ConnectionState.ERROR)
                }
            } else {
                val proto = AdbProtocol(usbManager, device, adbKeyDir) { msg -> log(msg) }
                if (proto.connect()) {
                    adbProtocol = proto
                    _fastbootDiagnostics.postValue(null)
                    _connectionState.postValue(ConnectionState.ADB)
                    logConnectionStatus()
                } else {
                    _connectionState.postValue(ConnectionState.ERROR)
                }
            }
        }
    }

    private fun buildDeviceInfo(device: UsbDevice, isFastboot: Boolean): String {
        val mode = if (isFastboot) "FASTBOOT" else "ADB"
        val name = device.productName ?: device.deviceName ?: text(R.string.unknown_device)
        return "Режим: $mode | Устройство: $name | VID=${device.vendorId} | PID=${device.productId}"
    }

    private fun setOperationSteps(steps: List<OperationStep>) {
        val safeSteps = steps.take(MAX_OPERATION_STEPS_IN_UI)
        synchronized(operationStepLock) { operationStepSnapshot = safeSteps }
        _operationSteps.postValue(safeSteps)
    }

    private fun markOperationStep(index: Int, status: OperationStepStatus, subtitle: String? = null) {
        val updated = synchronized(operationStepLock) {
            operationStepSnapshot.map { step ->
                if (step.index == index) step.copy(status = status, subtitle = subtitle ?: step.subtitle) else step
            }.also { operationStepSnapshot = it }
        }
        _operationSteps.postValue(updated)
    }

    fun logConnectionStatus() {
        log(text(R.string.connection_status_header))
        val state = when {
            fastbootProtocol?.isConnected == true -> ConnectionState.FASTBOOT
            adbProtocol?.isConnected == true      -> ConnectionState.ADB
            else -> _connectionState.value ?: ConnectionState.NONE
        }
        log(text(R.string.state_label, state.toString()))
        log(connectedDeviceInfo ?: text(R.string.device_not_connected))
        log(text(R.string.log_auto_saved, logFile?.absolutePath ?: text(R.string.log_folder_not_ready)))
    }

    fun currentLogFile(): File? = logFile
    fun logSnapshot(): List<String> = synchronized(logLock) { lines.toList() }
    fun currentConnectionInfo(): String? = connectedDeviceInfo
    fun currentFastbootDiagnostics(): FastbootProtocol.DeviceDiagnostics? = fastbootProtocol?.currentDiagnostics()
    fun currentAdbDiagnostics(): AdbProtocol.DeviceDiagnostics? = adbProtocol?.currentDiagnostics()
    fun isDebugLoggingEnabled(): Boolean = debugLoggingEnabled

    fun setDebugLogging(enabled: Boolean) {
        debugLoggingEnabled = enabled
        fastbootProtocol?.debugLogging = enabled
        log(if (enabled) text(R.string.debug_enabled) else text(R.string.debug_disabled))
    }

    fun refreshFastbootDiagnostics() {
        startOperation(text(R.string.notif_fastboot_diagnostics), text(R.string.notif_updating_device)) {
            val proto = fastbootProtocol
            if (proto?.isConnected == true) {
                val diagnostics = proto.refreshDiagnostics(force = true)
                _fastbootDiagnostics.postValue(diagnostics)
            } else {
                log(text(R.string.error_no_fastboot))
            }
        }
    }

    // ─── ВЫПОЛНЕНИЕ КОМАНД ───────────────────────────────────────────────────

    fun runSelfTest() {
        updateSelfTestStatus(SelfTestResult.RUNNING, "Self-test выполняется…")
        startOperation("Self-test", "Проверка ADB/Fastboot без записи на устройство") {
            try {
                val ok = performSelfTestBody()
                updateSelfTestStatus(
                    result = if (ok) SelfTestResult.PASS else SelfTestResult.WARN_FAIL,
                    summary = if (ok) "PASS: read-only проверка завершена без критических ошибок" else "WARN/FAIL: проверка завершена с предупреждениями"
                )
            } catch (e: Exception) {
                updateSelfTestStatus(SelfTestResult.WARN_FAIL, "FAIL: ${e.message ?: e.javaClass.simpleName}")
                throw e
            }
        }
    }

    fun runSelfTestReport(onReportCreated: ((File) -> Unit)? = null) {
        runSelfTestReportArtifacts { artifacts -> onReportCreated?.invoke(artifacts.textFile) }
    }

    fun runSelfTestReportArtifacts(onArtifactsCreated: ((SelfTestReportArtifacts) -> Unit)? = null) {
        updateSelfTestStatus(SelfTestResult.RUNNING, "Self-test report выполняется…")
        startOperation("Self-test report", "Проверка устройства и сохранение отчёта") {
            try {
                val startedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val startIndex = synchronized(logLock) { lines.size }
                val ok = performSelfTestBody()
                val reportLines = synchronized(logLock) { lines.drop(startIndex).toList() }
                val artifacts = writeSelfTestReports(startedAt, ok, reportLines)
                log("✅ Self-test TXT создан: ${artifacts.textFile.absolutePath}")
                log("✅ Self-test JSON создан: ${artifacts.jsonFile.absolutePath}")
                updateSelfTestStatus(
                    result = if (ok) SelfTestResult.PASS else SelfTestResult.WARN_FAIL,
                    summary = if (ok) "PASS: отчёт создан" else "WARN/FAIL: отчёт создан, но проверка дала предупреждения",
                    textReportPath = artifacts.textFile.absolutePath,
                    jsonReportPath = artifacts.jsonFile.absolutePath
                )
                onArtifactsCreated?.invoke(artifacts)
            } catch (e: Exception) {
                updateSelfTestStatus(SelfTestResult.WARN_FAIL, "FAIL: ${e.message ?: e.javaClass.simpleName}")
                throw e
            }
        }
    }

    fun reportsDirectory(): File {
        val workspace = profilesDir?.parentFile ?: logFile?.parentFile?.parentFile ?: getApplication<Application>().filesDir
        return File(workspace, "reports")
    }

    private fun updateSelfTestStatus(
        result: SelfTestResult,
        summary: String,
        textReportPath: String? = null,
        jsonReportPath: String? = null
    ) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        _selfTestStatus.postValue(SelfTestStatus(result, summary, stamp, textReportPath, jsonReportPath))
    }

    private fun performSelfTestBody(): Boolean {
        log("=== SELF-TEST / SMOKE TEST ===")
        log("Версия приложения: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        log(connectedDeviceInfo ?: "USB-устройство не подключено через ADB/Fastboot")
        log("Лог-файл: ${logFile?.absolutePath ?: "не создан"}")
        log("ADB key dir: ${adbKeyDir.absolutePath}")
        log("Profiles dir: ${profilesDir?.absolutePath ?: "не настроена"}")

        val ok = when {
            fastbootProtocol?.isConnected == true -> {
                log("Режим проверки: FASTBOOT")
                val fastbootOk = fastbootProtocol?.runSelfTest() == true
                val diagnostics = fastbootProtocol?.currentDiagnostics()
                if (diagnostics != null) _fastbootDiagnostics.postValue(diagnostics)
                log(if (fastbootOk) "✅ Fastboot self-test завершён" else "⚠️ Fastboot self-test завершён с предупреждениями")
                fastbootOk
            }
            adbProtocol?.isConnected == true -> {
                log("Режим проверки: ADB")
                val adbOk = adbProtocol?.runSelfTest() == true
                log(if (adbOk) "✅ ADB self-test завершён" else "⚠️ ADB self-test завершён с предупреждениями")
                adbOk
            }
            else -> {
                log("❌ Нет активного ADB/Fastboot подключения. Подключите устройство и нажмите «Сканировать».")
                log("Проверены только локальные компоненты: лог, папка ADB-ключей, профили.")
                false
            }
        }
        log("=== SELF-TEST DONE ===")
        return ok
    }

    private fun writeSelfTestReports(startedAt: String, ok: Boolean, reportLines: List<String>): SelfTestReportArtifacts {
        val workspace = profilesDir?.parentFile ?: logFile?.parentFile?.parentFile ?: getApplication<Application>().filesDir
        val reportsDir = File(workspace, "reports")
        if (!reportsDir.exists() && !reportsDir.mkdirs()) {
            throw IllegalStateException("не удалось создать папку отчётов: ${reportsDir.absolutePath}")
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(Date())
        val textReport = File(reportsDir, "selftest-$stamp.txt")
        val jsonReport = File(reportsDir, "selftest-$stamp.json")
        val visibleSnapshot = synchronized(logLock) { lines.toList() }
        val sanitizerScope = ReportSanitizer.Scope(
            workspace = workspace,
            logFile = logFile,
            adbKeyDir = adbKeyDir,
            profilesDir = profilesDir,
            packageName = getApplication<Application>().packageName
        )
        val safeReportLines = ReportSanitizer.sanitizeLines(reportLines, sanitizerScope)
        val safeVisibleSnapshot = ReportSanitizer.sanitizeLines(visibleSnapshot, sanitizerScope)

        val text = buildString {
            appendLine("ADB Fastboot Tool — Self-test report")
            appendLine("Created: $stamp")
            appendLine("Started: $startedAt")
            appendLine("Result: ${if (ok) "PASS" else "WARN/FAIL"}")
            appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Privacy mode: sanitized")
            appendLine("Connection state: ${_connectionState.value ?: ConnectionState.NONE}")
            appendLine("Connection: ${connectedDeviceInfo ?: "not connected"}")
            appendLine("Debug logging: $debugLoggingEnabled")
            appendLine("Log file: ${logFile?.absolutePath ?: "not created"}")
            appendLine("ADB key dir: ${adbKeyDir.absolutePath}")
            appendLine("Profiles dir: ${profilesDir?.absolutePath ?: "not configured"}")
            appendLine()
            appendLine("--- SELF-TEST LOG ---")
            if (reportLines.isEmpty()) {
                appendLine("No self-test lines captured.")
            } else {
                safeReportLines.forEach { appendLine(it) }
            }
            appendLine()
            appendLine("--- FULL VISIBLE LOG SNAPSHOT ---")
            safeVisibleSnapshot.forEach { appendLine(it) }
        }

        textReport.writeText(ReportSanitizer.sanitizeText(text, sanitizerScope), Charsets.UTF_8)
        jsonReport.writeText(ReportSanitizer.sanitizeText(buildSelfTestJson(stamp, startedAt, ok, safeReportLines, safeVisibleSnapshot), sanitizerScope), Charsets.UTF_8)
        return SelfTestReportArtifacts(textReport, jsonReport)
    }

    private fun buildSelfTestJson(
        createdAt: String,
        startedAt: String,
        ok: Boolean,
        reportLines: List<String>,
        visibleSnapshot: List<String>
    ): String {
        val q = DiagnosticJson::quote
        val fastboot = fastbootProtocol?.currentDiagnostics()
        val adb = adbProtocol?.currentDiagnostics()
        val state = (_connectionState.value ?: ConnectionState.NONE).toString()

        fun fastbootJson(): String = if (fastboot == null) {
            "null"
        } else {
            buildString {
                appendLine("{")
                appendLine("      \"product\": ${q(fastboot.product)},")
                appendLine("      \"currentSlot\": ${q(fastboot.currentSlot)},")
                appendLine("      \"slotCount\": ${q(fastboot.slotCount)},")
                appendLine("      \"slotSuffix\": ${q(fastboot.slotSuffix)},")
                appendLine("      \"unlocked\": ${q(fastboot.unlocked)},")
                appendLine("      \"secure\": ${q(fastboot.secure)},")
                appendLine("      \"serialno\": ${q(fastboot.serialno)},")
                appendLine("      \"versionBootloader\": ${q(fastboot.versionBootloader)},")
                appendLine("      \"isUserspace\": ${q(fastboot.isUserspace)},")
                appendLine("      \"superPartitionName\": ${q(fastboot.superPartitionName)},")
                appendLine("      \"maxDownloadSizeRaw\": ${q(fastboot.maxDownloadSizeRaw)},")
                appendLine("      \"maxDownloadSizeBytes\": ${DiagnosticJson.number(fastboot.maxDownloadSizeBytes)},")
                appendLine("      \"maxFetchSizeRaw\": ${q(fastboot.maxFetchSizeRaw)},")
                appendLine("      \"maxFetchSizeBytes\": ${DiagnosticJson.number(fastboot.maxFetchSizeBytes)},")
                appendLine("      \"timestamp\": ${fastboot.timestamp}")
                append("    }")
            }
        }

        fun adbJson(): String = if (adb == null) {
            "null"
        } else {
            buildString {
                appendLine("{")
                appendLine("      \"remoteBanner\": ${q(adb.remoteBanner)},")
                appendLine("      \"features\": ${DiagnosticJson.stringArray(adb.features, "        ")},")
                appendLine("      \"supportsShellV2\": ${DiagnosticJson.bool(adb.supportsShellV2)},")
                appendLine("      \"interactiveShellActive\": ${DiagnosticJson.bool(adb.interactiveShellActive)},")
                appendLine("      \"publicKeyPath\": ${q(adb.publicKeyPath)}")
                append("    }")
            }
        }

        return buildString {
            appendLine("{")
            appendLine("  \"schema\": \"ru.forum.adbfastboottool.selftest.v2\",")
            appendLine("  \"privacyMode\": \"sanitized\",")
            appendLine("  \"createdAt\": ${q(createdAt)},")
            appendLine("  \"startedAt\": ${q(startedAt)},")
            appendLine("  \"result\": ${q(if (ok) "PASS" else "WARN_FAIL")},")
            appendLine("  \"app\": {")
            appendLine("    \"versionName\": ${q(BuildConfig.VERSION_NAME)},")
            appendLine("    \"versionCode\": ${BuildConfig.VERSION_CODE}")
            appendLine("  },")
            appendLine("  \"connection\": {")
            appendLine("    \"state\": ${q(state)},")
            appendLine("    \"info\": ${q(connectedDeviceInfo)}")
            appendLine("  },")
            appendLine("  \"debugLogging\": ${DiagnosticJson.bool(debugLoggingEnabled)},")
            appendLine("  \"paths\": {")
            appendLine("    \"logFile\": ${q(logFile?.absolutePath)},")
            appendLine("    \"adbKeyDir\": ${q(adbKeyDir.absolutePath)},")
            appendLine("    \"profilesDir\": ${q(profilesDir?.absolutePath)}")
            appendLine("  },")
            appendLine("  \"adb\": ${adbJson()},")
            appendLine("  \"fastboot\": ${fastbootJson()},")
            appendLine("  \"selfTestLog\": ${DiagnosticJson.stringArray(reportLines, "    ")},")
            appendLine("  \"visibleLogSnapshot\": ${DiagnosticJson.stringArray(visibleSnapshot, "    ")}")
            appendLine("}")
        }
    }

    fun analyzeFirmwareFile(file: File) {
        startOperation(text(R.string.notif_file_analysis), text(R.string.notif_checking_file, file.name)) {
            val analysis = ImageInspector.analyze(file, includeHashes = true)
            analysis.toDisplayText().lines().forEach { line -> if (line.isNotBlank()) log(line) }
        }
    }

    fun runFastbootCommand(cmd: String) {
        startOperation(text(R.string.notif_fastboot_command), text(R.string.notif_executing, cmd)) {
            fastbootProtocol?.sendCommand(cmd) ?: log(text(R.string.error_no_fastboot))
        }
    }

    fun runFastbootDownloadAndRun(file: File, commandAfterDownload: String) {
        startOperation(text(R.string.notif_fastboot_command), text(R.string.notif_executing, commandAfterDownload)) {
            fastbootProtocol?.downloadAndRun(file, commandAfterDownload) ?: log(text(R.string.error_no_fastboot))
        }
    }

    fun runFastbootLogicalPartitionCommand(command: String) {
        startOperation(text(R.string.notif_fastboot_command), text(R.string.notif_executing, command)) {
            fastbootProtocol?.runLogicalPartitionCommand(command) ?: log(text(R.string.error_no_fastboot))
        }
    }

    fun inspectFastbootLogicalPartition(partition: String) {
        startOperation(text(R.string.notif_fastboot_diagnostics), text(R.string.notif_updating_device)) {
            val info = fastbootProtocol?.inspectLogicalPartition(partition)
            if (info == null) log(text(R.string.error_no_fastboot))
        }
    }

    fun runFastbootFetch(partition: String, outputFile: File) {
        startOperation(text(R.string.notif_fastboot_command), text(R.string.notif_executing, "fetch $partition")) {
            fastbootProtocol?.fetchPartition(partition, outputFile) ?: log(text(R.string.error_no_fastboot))
        }
    }

    fun runAdbService(service: String) {
        startOperation(text(R.string.notif_adb_command), text(R.string.notif_executing, service)) {
            adbProtocol?.runService(service) ?: log(text(R.string.error_no_adb))
        }
    }

    fun runAdbShell(command: String) {
        val label = if (command.isBlank()) "interactive shell" else "shell $command"
        startOperation(text(R.string.notif_adb_command), text(R.string.notif_executing, label)) {
            val proto = adbProtocol
            if (proto?.isConnected == true) proto.runShellCommand(command)
            else log(text(R.string.error_no_adb))
        }
    }

    fun isInteractiveAdbShellActive(): Boolean = adbProtocol?.hasInteractiveShell == true

    fun sendInteractiveAdbShellInput(line: String) {
        val proto = adbProtocol
        if (proto?.isConnected == true && proto.hasInteractiveShell) {
            proto.sendInteractiveShellInput(line)
        } else {
            log("❌ Интерактивный adb shell не открыт")
        }
    }

    fun interruptInteractiveAdbShell() {
        val proto = adbProtocol
        if (proto?.isConnected == true && proto.hasInteractiveShell) {
            proto.sendInteractiveShellInterrupt()
        } else {
            log("❌ Интерактивный adb shell не открыт")
        }
    }

    fun sendInteractiveAdbShellEof() {
        val proto = adbProtocol
        if (proto?.isConnected == true && proto.hasInteractiveShell) {
            proto.sendInteractiveShellEof()
        } else {
            log("❌ Интерактивный adb shell не открыт")
        }
    }

    fun stopInteractiveAdbShell() {
        val proto = adbProtocol
        if (proto?.isConnected == true && proto.hasInteractiveShell) {
            proto.stopInteractiveShell()
        } else {
            log("ℹ️ Интерактивный adb shell уже закрыт")
        }
    }

    fun runAdbPush(localFile: File, remotePath: String) {
        startOperation(text(R.string.notif_adb_command), text(R.string.notif_executing, "push ${localFile.name} $remotePath")) {
            val proto = adbProtocol
            if (proto?.isConnected == true) proto.pushPath(localFile, remotePath)
            else log(text(R.string.error_no_adb))
        }
    }

    fun runAdbPull(remotePath: String, localFile: File) {
        startOperation(text(R.string.notif_adb_command), text(R.string.notif_executing, "pull $remotePath")) {
            val proto = adbProtocol
            if (proto?.isConnected == true) proto.pullFile(remotePath, localFile)
            else log(text(R.string.error_no_adb))
        }
    }

    fun runAdbInstall(packageFile: File, options: List<String>) {
        startOperation(text(R.string.notif_adb_command), text(R.string.notif_executing, "install ${packageFile.name}")) {
            val proto = adbProtocol
            if (proto?.isConnected == true) proto.installPackage(packageFile, options)
            else log(text(R.string.error_no_adb))
        }
    }

    fun runAdbInstallMultiple(apkFiles: List<File>, options: List<String>) {
        val names = apkFiles.joinToString(" ") { it.name }
        startOperation(text(R.string.notif_adb_command), text(R.string.notif_executing, "install-multiple $names")) {
            val proto = adbProtocol
            if (proto?.isConnected == true) proto.installMultipleApks(apkFiles, options)
            else log(text(R.string.error_no_adb))
        }
    }

    fun runFlash(partition: String, file: File) {
        startOperation(text(R.string.notif_flash_img), text(R.string.notif_flashing_partition, file.name, partition)) {
            val proto = fastbootProtocol
            if (proto?.isConnected == true) proto.flashPartition(partition, file)
            else log(text(R.string.error_no_fastboot))
        }
    }

    fun runFlashQueue(items: List<FlashQueueItem>) {
        val queue = items.filter { it.partition.isNotBlank() }
        if (queue.isEmpty()) { log(text(R.string.flash_queue_empty_log)); return }

        // FIX: очередь прошивается в рекомендованном порядке:
        // vbmeta → boot → init_boot → vendor_boot → recovery → dtbo
        val order = listOf("vbmeta", "boot", "init_boot", "vendor_boot", "recovery", "dtbo")
        val sorted = queue.sortedBy { item ->
            val idx = order.indexOf(item.partition.lowercase())
            if (idx < 0) order.size else idx
        }

        startOperation(text(R.string.notif_flash_img), "Flash queue: ${sorted.size} шт. Не отключайте кабель.") {
            setOperationSteps(sorted.mapIndexed { index, item ->
                OperationStep(
                    index = index + 1,
                    total = sorted.size,
                    title = "flash ${item.partition} ← ${item.file.name}",
                    subtitle = formatBytesShort(item.file.length()),
                    status = OperationStepStatus.PENDING
                )
            })
            val proto = fastbootProtocol
            if (proto?.isConnected != true) {
                markOperationStep(1, OperationStepStatus.FAILED, text(R.string.error_no_fastboot))
                log(text(R.string.error_no_fastboot))
                return@startOperation
            }
            sorted.forEachIndexed { index, item ->
                val stepNumber = index + 1
                markOperationStep(stepNumber, OperationStepStatus.RUNNING, "fastboot flash ${item.partition}")
                log("=== FLASH QUEUE ${stepNumber}/${sorted.size}: ${item.partition} ← ${item.file.name} ===")
                val ok = proto.flashPartition(item.partition, item.file)
                val diagnostics = proto.currentDiagnostics()
                if (diagnostics != null) _fastbootDiagnostics.postValue(diagnostics)
                markOperationStep(stepNumber, if (ok) OperationStepStatus.OK else OperationStepStatus.FAILED, diagnosticsBrief(diagnostics))
                if (!ok) { log("❌ Очередь остановлена на разделе ${item.partition}"); return@startOperation }
            }
            log("✅ Очередь прошивки завершена")
        }
    }


    fun analyzeXiaomiFastbootRom(file: File, workspaceDir: File) {
        startOperation(text(R.string.notif_xiaomi_rom_analysis), text(R.string.notif_checking_file, file.name)) {
            val analysis = XiaomiFastbootRomManager.analyze(file)
            analysis.toDisplayText().lines().forEach { line -> if (line.isNotBlank()) log(line) }
            val cleanPlan = XiaomiFastbootRomManager.selectPlan(analysis, XiaomiFastbootRomManager.FlashMode.CLEAN_ALL)
            val savePlan = XiaomiFastbootRomManager.selectPlan(analysis, XiaomiFastbootRomManager.FlashMode.SAVE_USER_DATA)
            log("Clean all script: ${cleanPlan?.scriptName ?: "not found"}")
            log("Save user data script: ${savePlan?.scriptName ?: "not found"}")
            log("Workspace for extraction: ${File(workspaceDir, "roms").absolutePath}")
            val report = writeXiaomiRomAnalysisReport(file, analysis, cleanPlan, savePlan)
            log("✅ Xiaomi ROM analysis report: ${report.absolutePath}")
        }
    }

    private fun writeXiaomiRomAnalysisReport(
        file: File,
        analysis: XiaomiFastbootRomManager.RomAnalysis,
        cleanPlan: XiaomiFastbootRomManager.ScriptPlan?,
        savePlan: XiaomiFastbootRomManager.ScriptPlan?
    ): File {
        val reportsDir = reportsDirectory()
        if (!reportsDir.exists() && !reportsDir.mkdirs()) {
            throw IllegalStateException("не удалось создать папку отчётов: ${reportsDir.absolutePath}")
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(Date())
        val report = File(reportsDir, "xiaomi-rom-analysis-$stamp.txt")
        val text = buildString {
            appendLine("NekoMiFlash — Xiaomi Fastboot ROM analysis")
            appendLine("Created: $stamp")
            appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Source file: ${file.name}")
            appendLine("Source size: ${file.length()} bytes")
            appendLine("Detected anti-rollback indexes: ${if (analysis.antiRollbackIndexes.isEmpty()) "not detected" else analysis.antiRollbackIndexes.joinToString(", ")}")
            appendLine()
            appendLine(analysis.toDisplayText())
            appendLine()
            appendLine("=== SELECTED SAFE MODES ===")
            appendLine("Clean all: ${cleanPlan?.scriptName ?: "not found"}${cleanPlan?.let { if (it.canFlash) " / allowed" else " / blocked" } ?: ""}")
            appendLine("Save user data: ${savePlan?.scriptName ?: "not found"}${savePlan?.let { if (it.canFlash) " / allowed" else " / blocked" } ?: ""}")
            appendLine()
            appendLine(xiaomiPlanDryRunText("clean all", cleanPlan))
            appendLine()
            appendLine(xiaomiPlanDryRunText("save user data", savePlan))
            appendLine()
            appendLine("Safety policy:")
            appendLine("- flash_all_lock and any lock command are blocked")
            appendLine("- product mismatch is blocked during real flash")
            appendLine("- unsupported fastboot lines block automatic flashing")
            appendLine("- update-super is executed only after fastbootd/userspace is confirmed and product is checked")
        }
        report.writeText(text)
        return report
    }

    private fun xiaomiPlanDryRunText(label: String, plan: XiaomiFastbootRomManager.ScriptPlan?): String = buildString {
        appendLine("=== DRY-RUN PLAN: $label ===")
        if (plan == null) {
            appendLine("Script: not found")
            return@buildString
        }
        appendLine("Script: ${plan.scriptName}")
        appendLine("Allowed by static parser: ${if (plan.canFlash) "yes" else "no"}")
        appendLine("Expected product: ${if (plan.expectedProducts.isEmpty()) "not declared" else plan.expectedProducts.joinToString(", ")}")
        appendLine("Actions: ${plan.commands.size}")
        appendLine("Flash actions: ${plan.commands.count { it is XiaomiFastbootRomManager.PlanCommand.Flash }}")
        appendLine("Update-super actions: ${plan.commands.count { it is XiaomiFastbootRomManager.PlanCommand.UpdateSuper }}")
        appendLine("Fastbootd transitions: ${plan.commands.count { it is XiaomiFastbootRomManager.PlanCommand.Reboot && isFastbootdRebootTarget(it.target) }}")
        appendLine("Storage wipe detected: ${if (plan.storageWipeDetected) "yes" else "no"}")
        appendLine("Data impact: ${plan.dataImpact.shortLabel()}")
        appendLine(XiaomiFastbootRomManager.dataImpactText(plan).trimEnd())
        appendLine("Anti-rollback indexes: ${if (plan.antiRollbackIndexes.isEmpty()) "not detected" else plan.antiRollbackIndexes.joinToString(", ")}")
        appendLine("Critical firmware partitions: ${if (plan.criticalPartitionDetected) "yes" else "no"}")
        appendLine("Lock command detected: ${if (plan.lockCommandDetected) "YES" else "no"}")
        if (plan.blockedReasons.isNotEmpty()) {
            appendLine("Blocked reasons:")
            plan.blockedReasons.forEach { appendLine("  - $it") }
        }
        if (plan.warnings.isNotEmpty()) {
            appendLine("Warnings:")
            plan.warnings.forEach { appendLine("  - $it") }
        }
        appendLine("Commands:")
        plan.commands.forEachIndexed { index, command -> appendLine("${index + 1}. ${command.toDisplayText()}") }
    }

    fun runXiaomiFastbootRom(file: File, workspaceDir: File, mode: XiaomiFastbootRomManager.FlashMode) {
        clearPersistentXiaomiRomResume(appendAudit = false)
        runXiaomiFastbootRomInternal(file, workspaceDir, mode, resumeFromActionIndex = 0, resumeAfterFastbootd = false)
    }

    fun resumeLastXiaomiRomFlash(defaultWorkspaceDir: File) {
        val state = readPersistentXiaomiRomResume()
        if (state == null) {
            log("ℹ️ Нет сохранённой Xiaomi ROM resume-сессии.")
            return
        }
        if (!state.file.exists() || !state.file.canRead()) {
            log("❌ Resume невозможен: ROM-файл недоступен: ${state.file.absolutePath}")
            clearPersistentXiaomiRomResume(appendAudit = true)
            return
        }
        if (fastbootProtocol?.isConnected != true) {
            log(text(R.string.error_no_fastboot))
            log("Подключите устройство в fastbootd/userspace и нажмите Resume ещё раз.")
            return
        }
        val workspace = state.workspaceDir.takeIf { it.exists() || it.mkdirs() } ?: defaultWorkspaceDir
        log("↩️ Resume Xiaomi ROM: ${state.file.name}, mode=${state.mode}, step=${state.resumeFromActionIndex + 1}, reason=${state.reason}")
        state.markerFile?.takeIf { it.exists() }?.let { log("Resume marker: ${it.absolutePath}") }
        runXiaomiFastbootRomInternal(
            state.file,
            workspace,
            state.mode,
            resumeFromActionIndex = state.resumeFromActionIndex,
            resumeAfterFastbootd = true
        )
    }

    private fun runXiaomiFastbootRomInternal(
        file: File,
        workspaceDir: File,
        mode: XiaomiFastbootRomManager.FlashMode,
        resumeFromActionIndex: Int,
        resumeAfterFastbootd: Boolean
    ) {
        val modeText = when (mode) {
            XiaomiFastbootRomManager.FlashMode.CLEAN_ALL -> "clean all / wipe data"
            XiaomiFastbootRomManager.FlashMode.SAVE_USER_DATA -> "save user data"
        }
        val titleSuffix = if (resumeAfterFastbootd) "resume in fastbootd" else modeText
        startOperation(text(R.string.notif_xiaomi_rom_flash), "Xiaomi Fastboot ROM: $titleSuffix") {
            if (resumeAfterFastbootd) pendingXiaomiRomFlash = null
            val proto = fastbootProtocol
            if (proto?.isConnected != true) { log(text(R.string.error_no_fastboot)); return@startOperation }

            val analysis = XiaomiFastbootRomManager.analyze(file)
            val selectedPlan = XiaomiFastbootRomManager.selectPlan(analysis, mode)
            if (selectedPlan == null) {
                log("❌ Xiaomi ROM: не найден подходящий скрипт для режима $modeText")
                analysis.toDisplayText().lines().forEach { line -> if (line.isNotBlank()) log(line) }
                return@startOperation
            }

            val diagnostics = proto.refreshDiagnostics(force = true)
            _fastbootDiagnostics.postValue(diagnostics)
            if (diagnostics.unlocked?.equals("no", ignoreCase = true) == true) {
                log("⛔ Bootloader locked: полная прошивка Fastboot ROM заблокирована. Требуется unlocked=yes.")
                return@startOperation
            }

            val product = diagnostics.product?.trim()
            if (!product.isNullOrBlank() && selectedPlan.expectedProducts.isNotEmpty()) {
                val allowed = selectedPlan.expectedProducts.any { it.equals(product, ignoreCase = true) }
                if (!allowed) {
                    log("⛔ Product mismatch: device=$product, ROM=${selectedPlan.expectedProducts.joinToString(", ")}")
                    log("Прошивка отменена, чтобы не прошить ROM от другой модели.")
                    return@startOperation
                }
            } else if (selectedPlan.expectedProducts.isEmpty()) {
                log("⚠️ В скрипте ROM не найден product guard. Продолжение возможно только на ответственность пользователя.")
            }

            val arbReason = xiaomiAntiRollbackBlockReason(diagnostics, selectedPlan)
            if (arbReason != null) {
                log("⛔ Anti-rollback guard: $arbReason")
                log("Прошивка отменена, чтобы не выполнить rollback на более старый ROM.")
                return@startOperation
            } else {
                logXiaomiAntiRollbackSummary(diagnostics, selectedPlan)
            }

            val prepared = XiaomiFastbootRomManager.prepareForFlash(file, workspaceDir, mode) { msg -> log(msg) }
            prepared.toDisplayText().lines().forEach { line -> if (line.isNotBlank()) log(line) }
            if (prepared.blockedReasons.isNotEmpty()) {
                log("❌ Xiaomi Fastboot ROM flash заблокирован: ${prepared.blockedReasons.joinToString("; ")}")
                return@startOperation
            }

            if (prepared.actions.isEmpty()) {
                log("❌ Xiaomi ROM: план прошивки пустой")
                return@startOperation
            }

            xiaomiTransferLimitText(prepared, diagnostics).lines().forEach { line -> if (line.isNotBlank()) log(line) }
            val transferLimitBlocks = xiaomiTransferLimitBlockReasons(prepared, diagnostics)
            if (transferLimitBlocks.isNotEmpty()) {
                log("❌ Xiaomi Fastboot ROM flash заблокирован по лимиту передачи: ${transferLimitBlocks.joinToString("; ")}")
                return@startOperation
            }

            setOperationSteps(prepared.actions.mapIndexed { index, action ->
                OperationStep(
                    index = index + 1,
                    total = prepared.actions.size,
                    title = action.toDisplayText(),
                    subtitle = actionTransferBytes(action)?.let { formatBytesShort(it) },
                    status = if (index < resumeFromActionIndex) OperationStepStatus.SKIPPED else OperationStepStatus.PENDING
                )
            })

            val inFastbootd = diagnostics.isUserspace?.equals("yes", ignoreCase = true) == true
            val explicitFastbootdRebootIndex = prepared.actions.indexOfFirst { action ->
                action is XiaomiFastbootRomManager.PreparedAction.Reboot && isFastbootdRebootTarget(action.target)
            }
            var startIndex = resumeFromActionIndex.coerceIn(0, prepared.actions.size)

            if (!resumeAfterFastbootd && inFastbootd && explicitFastbootdRebootIndex > 0) {
                log("⛔ ROM-скрипт содержит bootloader-стадию до перехода в fastbootd.")
                log("Сейчас устройство уже в fastbootd. Вернитесь в bootloader fastboot и запустите прошивку заново, чтобы не пропустить первые разделы.")
                return@startOperation
            }
            if (!resumeAfterFastbootd && inFastbootd && explicitFastbootdRebootIndex == 0) {
                log("ℹ️ Устройство уже в fastbootd; команда reboot fastboot из скрипта будет пропущена.")
                startIndex = 1
            }

            val session = createXiaomiRomFlashSessionReport(prepared, modeText, diagnostics, startIndex, resumeAfterFastbootd)
            log("🧾 Xiaomi ROM flash session report: ${session.reportFile.absolutePath}")

            if (!inFastbootd) {
                if (explicitFastbootdRebootIndex >= startIndex) {
                    val beforeRebootEnd = explicitFastbootdRebootIndex
                    if (beforeRebootEnd > startIndex) {
                        log("=== XIAOMI ROM: bootloader-стадия перед fastbootd (${beforeRebootEnd - startIndex} шаг.) ===")
                        val ok = executeXiaomiRomActions(proto, prepared.actions, startIndex, beforeRebootEnd, session)
                        if (!ok) return@startOperation
                    }
                    requestFastbootdAndResume(
                        proto = proto,
                        file = file,
                        workspaceDir = workspaceDir,
                        mode = mode,
                        resumeIndex = explicitFastbootdRebootIndex + 1,
                        expectedProducts = selectedPlan.expectedProducts,
                        reason = "ROM-скрипт требует перехода в fastbootd",
                        session = session
                    )
                    return@startOperation
                }

                if (remainingActionsRequireFastbootd(prepared.actions.drop(startIndex))) {
                    requestFastbootdAndResume(
                        proto = proto,
                        file = file,
                        workspaceDir = workspaceDir,
                        mode = mode,
                        resumeIndex = startIndex,
                        expectedProducts = selectedPlan.expectedProducts,
                        reason = "в плане есть dynamic/logical partitions, для них нужен fastbootd",
                        session = session
                    )
                    return@startOperation
                }
            } else if (remainingActionsRequireFastbootd(prepared.actions.drop(startIndex))) {
                log("✅ Устройство уже в fastbootd/userspace, продолжаем dynamic partitions stage.")
            }

            val ok = executeXiaomiRomActions(proto, prepared.actions, startIndex, prepared.actions.size, session)
            if (!ok) return@startOperation
            appendXiaomiRomFlashProgress(session, prepared.actions.size, null, "COMPLETED", proto.currentDiagnostics())
            clearPersistentXiaomiRomResume(appendAudit = true)
            log("✅ Xiaomi Fastboot ROM flash завершён: $modeText")
        }
    }

    private fun executeXiaomiRomActions(
        proto: FastbootProtocol,
        actions: List<XiaomiFastbootRomManager.PreparedAction>,
        startInclusive: Int,
        endExclusive: Int,
        session: XiaomiRomFlashSession?
    ): Boolean {
        val safeStart = startInclusive.coerceIn(0, actions.size)
        val safeEnd = endExclusive.coerceIn(safeStart, actions.size)
        for (index in safeStart until safeEnd) {
            val action = actions[index]
            if (action is XiaomiFastbootRomManager.PreparedAction.Reboot && isFastbootdRebootTarget(action.target)) {
                markOperationStep(index + 1, OperationStepStatus.SKIPPED, "handled by fastbootd resume")
                log("=== XIAOMI ROM ${index + 1}/${actions.size}: переход в fastbootd будет выполнен отдельным resume-шагом ===")
                continue
            }
            markOperationStep(index + 1, OperationStepStatus.RUNNING, actionTransferBytes(action)?.let { formatBytesShort(it) })
            log("=== XIAOMI ROM ${index + 1}/${actions.size}: ${action.toDisplayText()} ===")
            appendXiaomiRomFlashProgress(session, index + 1, action, "START", proto.currentDiagnostics())
            val ok = when (action) {
                is XiaomiFastbootRomManager.PreparedAction.Flash -> proto.flashPartition(action.partition, action.imageFile)
                is XiaomiFastbootRomManager.PreparedAction.Erase -> proto.sendCommand("erase:${action.partition}")
                is XiaomiFastbootRomManager.PreparedAction.WipeData -> executeXiaomiWipeDataAction(proto, action)
                is XiaomiFastbootRomManager.PreparedAction.SetActive -> proto.sendCommand("set_active:${action.slot.removePrefix("_")}")
                is XiaomiFastbootRomManager.PreparedAction.UpdateSuper -> executeUpdateSuperAction(proto, action)
                is XiaomiFastbootRomManager.PreparedAction.Reboot -> proto.sendCommand(fastbootRebootCommand(action.target))
            }
            val current = proto.currentDiagnostics()
            if (current != null) _fastbootDiagnostics.postValue(current)
            appendXiaomiRomFlashProgress(session, index + 1, action, if (ok) "OK" else "FAILED", current)
            markOperationStep(index + 1, if (ok) OperationStepStatus.OK else OperationStepStatus.FAILED, diagnosticsBrief(current))
            if (!ok) {
                log("❌ Xiaomi Fastboot ROM flash остановлен на шаге ${index + 1}: ${action.toDisplayText()}")
                return false
            }
        }
        return true
    }

    private fun createXiaomiRomFlashSessionReport(
        prepared: XiaomiFastbootRomManager.PreparedPlan,
        modeText: String,
        diagnostics: FastbootProtocol.DeviceDiagnostics,
        startIndex: Int,
        resumeAfterFastbootd: Boolean
    ): XiaomiRomFlashSession {
        val reportsDir = reportsDirectory()
        if (!reportsDir.exists() && !reportsDir.mkdirs()) {
            throw IllegalStateException("не удалось создать папку отчётов: ${reportsDir.absolutePath}")
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(Date())
        val report = File(reportsDir, "xiaomi-rom-flash-session-$stamp.txt")
        val session = XiaomiRomFlashSession(
            reportFile = report,
            sourceName = prepared.source.name,
            modeText = modeText,
            scriptName = prepared.plan.scriptName,
            totalActions = prepared.actions.size
        )
        report.writeText(buildString {
            appendLine("NekoMiFlash — Xiaomi Fastboot ROM flash session")
            appendLine("Created: $stamp")
            appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Source file: ${prepared.source.name}")
            appendLine("Mode: $modeText")
            appendLine("Script: ${prepared.plan.scriptName}")
            appendLine("Resume after fastbootd: ${if (resumeAfterFastbootd) "yes" else "no"}")
            appendLine("Start index: ${startIndex + 1}/${prepared.actions.size}")
            appendLine("Initial diagnostics: ${diagnosticsBrief(diagnostics)}")
            appendLine("Operational note: keep the host app in foreground, keep OTG power stable, and do not unplug USB during large image transfers.")
            appendLine()
            appendLine(xiaomiTransferLimitText(prepared, diagnostics).trimEnd())
            appendLine()
            appendLine(prepared.toDisplayText())
            appendLine()
            appendLine("=== PROGRESS ===")
        })
        return session
    }

    private fun appendXiaomiRomFlashProgress(
        session: XiaomiRomFlashSession?,
        stepNumber: Int,
        action: XiaomiFastbootRomManager.PreparedAction?,
        status: String,
        diagnostics: FastbootProtocol.DeviceDiagnostics?
    ) {
        if (session == null) return
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val safeStep = if (session.totalActions > 0) stepNumber.coerceIn(0, session.totalActions) else stepNumber
        val actionText = action?.toDisplayText() ?: "session"
        val elapsedMs = System.currentTimeMillis() - session.startedAtMs
        val line = buildString {
            append("[$stamp] ")
            append(status)
            append(" elapsed=")
            append(formatDurationShort(elapsedMs))
            append(" step=")
            append(safeStep)
            append("/")
            append(session.totalActions)
            append(" action=\"")
            append(actionText.replace("\"", "'"))
            append("\"")
            actionTransferBytes(action)?.let { bytes ->
                append(" transfer=")
                append(formatBytesShort(bytes))
            }
            append(" diagnostics=")
            append(diagnosticsBrief(diagnostics))
            append('\n')
        }
        try {
            session.reportFile.appendText(line)
        } catch (t: Throwable) {
            log("⚠️ Не удалось обновить Xiaomi ROM flash session report: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun actionTransferBytes(action: XiaomiFastbootRomManager.PreparedAction?): Long? = when (action) {
        is XiaomiFastbootRomManager.PreparedAction.Flash -> action.imageFile.length().takeIf { it > 0L }
        is XiaomiFastbootRomManager.PreparedAction.UpdateSuper -> action.imageFile.length().takeIf { it > 0L }
        else -> null
    }

    private data class XiaomiTransferItem(
        val step: Int,
        val label: String,
        val bytes: Long
    )

    private fun xiaomiTransferItems(prepared: XiaomiFastbootRomManager.PreparedPlan): List<XiaomiTransferItem> =
        prepared.actions.mapIndexedNotNull { index, action ->
            val bytes = actionTransferBytes(action) ?: return@mapIndexedNotNull null
            XiaomiTransferItem(
                step = index + 1,
                label = action.toDisplayText(),
                bytes = bytes
            )
        }

    private fun xiaomiTransferLimitBlockReasons(
        prepared: XiaomiFastbootRomManager.PreparedPlan,
        diagnostics: FastbootProtocol.DeviceDiagnostics
    ): List<String> {
        val result = mutableListOf<String>()
        val deviceLimit = diagnostics.maxDownloadSizeBytes?.takeIf { it > 0L }
        xiaomiTransferItems(prepared).forEach { item ->
            if (item.bytes > FASTBOOT_DOWNLOAD_IMPLEMENTATION_LIMIT_BYTES) {
                result += "step ${item.step} ${item.label} is ${formatBytesShort(item.bytes)}, but this Fastboot implementation supports one download up to ${formatBytesShort(FASTBOOT_DOWNLOAD_IMPLEMENTATION_LIMIT_BYTES)}"
            }
            if (deviceLimit != null && item.bytes > deviceLimit) {
                result += "step ${item.step} ${item.label} is ${formatBytesShort(item.bytes)}, larger than device max-download-size ${formatBytesShort(deviceLimit)}"
            }
        }
        return result.distinct()
    }

    private fun xiaomiTransferLimitText(
        prepared: XiaomiFastbootRomManager.PreparedPlan,
        diagnostics: FastbootProtocol.DeviceDiagnostics
    ): String = buildString {
        val items = xiaomiTransferItems(prepared)
        val deviceLimit = diagnostics.maxDownloadSizeBytes?.takeIf { it > 0L }
        val largest = items.maxByOrNull { it.bytes }
        appendLine("=== FASTBOOT DOWNLOAD LIMIT PREFLIGHT ===")
        appendLine("Transfer actions: ${items.size}")
        appendLine("Device max-download-size: ${deviceLimit?.let { formatBytesShort(it) } ?: diagnostics.maxDownloadSizeRaw?.takeIf { it.isNotBlank() } ?: "unknown"}")
        appendLine("NekoMiFlash single-download implementation limit: ${formatBytesShort(FASTBOOT_DOWNLOAD_IMPLEMENTATION_LIMIT_BYTES)}")
        appendLine("Largest transfer: ${largest?.let { "step ${it.step}: ${formatBytesShort(it.bytes)} — ${it.label}" } ?: "none"}")
        val blocks = xiaomiTransferLimitBlockReasons(prepared, diagnostics)
        if (blocks.isEmpty()) {
            if (deviceLimit == null) {
                appendLine("Warnings:")
                appendLine("  - Device did not report max-download-size. NekoMiFlash will rely on protocol/runtime checks during download.")
            } else {
                appendLine("Result: OK — all planned transfers are within the reported download limit.")
            }
        } else {
            appendLine("Blocked:")
            blocks.forEach { appendLine("  - $it") }
            appendLine("Recommendation: use a ROM package with smaller sparse chunks, enter fastbootd if the ROM requires it, or flash from a PC fastboot client that can handle this package.")
        }
    }

    private fun formatBytesShort(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit += 1
        }
        return if (unit == 0) "${bytes} B" else String.format(Locale.US, "%.2f %s", value, units[unit])
    }

    private fun formatDurationShort(ms: Long): String {
        val safeMs = ms.coerceAtLeast(0L)
        val totalSeconds = safeMs / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return if (minutes > 0L) "${minutes}m${seconds}s" else "${seconds}s"
    }

    private fun diagnosticsBrief(diagnostics: FastbootProtocol.DeviceDiagnostics?): String {
        if (diagnostics == null) return "none"
        val parts = mutableListOf<String>()
        diagnostics.product?.takeIf { it.isNotBlank() }?.let { parts += "product=$it" }
        diagnostics.currentSlot?.takeIf { it.isNotBlank() }?.let { parts += "slot=$it" }
        diagnostics.unlocked?.takeIf { it.isNotBlank() }?.let { parts += "unlocked=$it" }
        diagnostics.secure?.takeIf { it.isNotBlank() }?.let { parts += "secure=$it" }
        diagnostics.antiRollback?.takeIf { it.isNotBlank() }?.let { parts += "anti=$it" }
        diagnostics.isUserspace?.takeIf { it.isNotBlank() }?.let { parts += "is-userspace=$it" }
        diagnostics.superPartitionName?.takeIf { it.isNotBlank() }?.let { parts += "super=$it" }
        return if (parts.isEmpty()) "empty" else parts.joinToString(", ")
    }

    private fun xiaomiAntiRollbackBlockReason(
        diagnostics: FastbootProtocol.DeviceDiagnostics,
        plan: XiaomiFastbootRomManager.ScriptPlan
    ): String? {
        val romAnti = XiaomiFastbootRomManager.maxAntiRollbackIndex(plan) ?: return null
        val deviceAnti = parseFastbootAntiRollbackIndex(diagnostics.antiRollback) ?: return null
        return if (deviceAnti > romAnti) {
            "device anti=$deviceAnti, ROM anti=$romAnti. ROM rollback index is lower than the device rollback index."
        } else {
            null
        }
    }

    private fun logXiaomiAntiRollbackSummary(
        diagnostics: FastbootProtocol.DeviceDiagnostics,
        plan: XiaomiFastbootRomManager.ScriptPlan
    ) {
        val romAnti = XiaomiFastbootRomManager.maxAntiRollbackIndex(plan)
        val deviceAnti = parseFastbootAntiRollbackIndex(diagnostics.antiRollback)
        when {
            romAnti != null && deviceAnti != null -> log("✅ Anti-rollback check: device anti=$deviceAnti, ROM anti=$romAnti")
            romAnti != null -> log("⚠️ ROM anti-rollback index=$romAnti, но устройство не отдало getvar:anti. Продолжаем только с product/unlocked guard.")
            deviceAnti != null -> log("ℹ️ Устройство сообщает anti=$deviceAnti, но ROM-скрипт не содержит anti-index. Product/unlocked guard остаётся активным.")
            else -> log("ℹ️ Anti-rollback index не обнаружен ни в getvar:anti, ни в ROM-скрипте.")
        }
    }

    private fun parseFastbootAntiRollbackIndex(raw: String?): Int? {
        val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        Regex("\\b(\\d{1,2})\\b").find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { parsed ->
            return parsed.takeIf { it in 0..99 }
        }
        return value.toIntOrNull()?.takeIf { it in 0..99 }
    }


    private fun executeXiaomiWipeDataAction(
        proto: FastbootProtocol,
        action: XiaomiFastbootRomManager.PreparedAction.WipeData
    ): Boolean {
        log("Fastboot wipe requested by ROM script: ${action.reason}")
        log("NekoMiFlash protocol equivalent: erase userdata, then erase metadata/cache only if the partition exists.")

        val targets = mutableListOf("userdata")
        listOf("metadata", "cache").forEach { partition ->
            val size = proto.getVar("partition-size:$partition")
            if (!size.isNullOrBlank()) targets += partition
        }

        for (target in targets.distinct()) {
            log("Wipe target: erase:$target")
            val ok = proto.sendCommand("erase:$target")
            if (!ok) {
                log("❌ Wipe failed on $target. Xiaomi ROM flash stopped before continuing.")
                return false
            }
        }
        return true
    }

    private fun executeUpdateSuperAction(
        proto: FastbootProtocol,
        action: XiaomiFastbootRomManager.PreparedAction.UpdateSuper
    ): Boolean {
        val diagnostics = proto.refreshDiagnostics(force = true)
        _fastbootDiagnostics.postValue(diagnostics)
        if (diagnostics.isUserspace?.equals("yes", ignoreCase = true) != true) {
            log("⛔ update-super требует fastbootd/userspace. Текущий режим не подтвердил is-userspace=yes.")
            return false
        }
        val superName = diagnostics.superPartitionName?.trim()?.takeIf { it.isNotBlank() } ?: action.superPartition
        val command = "update-super:$superName${if (action.wipe) ":wipe" else ""}"
        log("Fastbootd update-super command: $command")
        return proto.downloadAndRun(action.imageFile, command)
    }

    private fun persistXiaomiRomResumeState(
        file: File,
        workspaceDir: File,
        mode: XiaomiFastbootRomManager.FlashMode,
        resumeIndex: Int,
        expectedProducts: List<String>,
        reason: String,
        session: XiaomiRomFlashSession?
    ) {
        val marker = writeXiaomiRomResumeMarker(file, workspaceDir, mode, resumeIndex, expectedProducts, reason, session)
        xiaomiRomResumePrefs().edit()
            .putString(XIAOMI_RESUME_FILE_PATH, file.absolutePath)
            .putString(XIAOMI_RESUME_WORKSPACE_PATH, workspaceDir.absolutePath)
            .putString(XIAOMI_RESUME_MODE, mode.name)
            .putInt(XIAOMI_RESUME_INDEX, resumeIndex)
            .putString(XIAOMI_RESUME_PRODUCTS, expectedProducts.joinToString("|"))
            .putString(XIAOMI_RESUME_REASON, reason)
            .putString(XIAOMI_RESUME_MARKER_PATH, marker?.absolutePath)
            .putLong(XIAOMI_RESUME_CREATED_AT, System.currentTimeMillis())
            .apply()
        marker?.let { log("💾 Resume state сохранён: ${it.absolutePath}") }
    }

    private fun readPersistentXiaomiRomResume(): XiaomiRomResumeState? {
        val prefs = xiaomiRomResumePrefs()
        val filePath = prefs.getString(XIAOMI_RESUME_FILE_PATH, null)?.takeIf { it.isNotBlank() } ?: return null
        val workspacePath = prefs.getString(XIAOMI_RESUME_WORKSPACE_PATH, null)?.takeIf { it.isNotBlank() }
        val modeName = prefs.getString(XIAOMI_RESUME_MODE, null)?.takeIf { it.isNotBlank() } ?: return null
        val mode = runCatching { XiaomiFastbootRomManager.FlashMode.valueOf(modeName) }.getOrNull() ?: return null
        val products = prefs.getString(XIAOMI_RESUME_PRODUCTS, "").orEmpty()
            .split('|')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val markerPath = prefs.getString(XIAOMI_RESUME_MARKER_PATH, null)?.takeIf { it.isNotBlank() }
        return XiaomiRomResumeState(
            file = File(filePath),
            workspaceDir = File(workspacePath ?: ""),
            mode = mode,
            resumeFromActionIndex = prefs.getInt(XIAOMI_RESUME_INDEX, 0).coerceAtLeast(0),
            expectedProducts = products,
            reason = prefs.getString(XIAOMI_RESUME_REASON, "fastbootd resume").orEmpty(),
            markerFile = markerPath?.let { File(it) }
        )
    }

    private fun clearPersistentXiaomiRomResume(appendAudit: Boolean) {
        val state = readPersistentXiaomiRomResume()
        if (appendAudit) {
            state?.markerFile?.takeIf { it.exists() }?.let { marker ->
                runCatching {
                    val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                    marker.appendText("[$stamp] RESUME_STATE_CLEARED\n")
                }
            }
        }
        xiaomiRomResumePrefs().edit().clear().apply()
    }

    private fun writeXiaomiRomResumeMarker(
        file: File,
        workspaceDir: File,
        mode: XiaomiFastbootRomManager.FlashMode,
        resumeIndex: Int,
        expectedProducts: List<String>,
        reason: String,
        session: XiaomiRomFlashSession?
    ): File? {
        return runCatching {
            val reportsDir = reportsDirectory()
            if (!reportsDir.exists()) reportsDir.mkdirs()
            val stamp = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(Date())
            val marker = File(reportsDir, "xiaomi-rom-resume-state-$stamp.txt")
            marker.writeText(buildString {
                appendLine("NekoMiFlash — Xiaomi Fastboot ROM resume state")
                appendLine("Created: $stamp")
                appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("Source file: ${file.absolutePath}")
                appendLine("Workspace: ${workspaceDir.absolutePath}")
                appendLine("Mode: ${mode.name}")
                appendLine("Resume step: ${resumeIndex + 1}")
                appendLine("Expected product: ${if (expectedProducts.isEmpty()) "not declared" else expectedProducts.joinToString(", ")}")
                appendLine("Reason: $reason")
                appendLine("Flash session report: ${session?.reportFile?.absolutePath ?: "not created"}")
                appendLine()
                appendLine("Use this state only after the phone reconnects in fastbootd/userspace.")
            })
            marker
        }.getOrElse { t ->
            log("⚠️ Не удалось сохранить resume marker: ${t.message ?: t.javaClass.simpleName}")
            null
        }
    }

    private fun xiaomiRomResumePrefs() =
        getApplication<Application>().getSharedPreferences(XIAOMI_RESUME_PREFS, Context.MODE_PRIVATE)

    private fun requestFastbootdAndResume(
        proto: FastbootProtocol,
        file: File,
        workspaceDir: File,
        mode: XiaomiFastbootRomManager.FlashMode,
        resumeIndex: Int,
        expectedProducts: List<String>,
        reason: String,
        session: XiaomiRomFlashSession?
    ) {
        pendingXiaomiRomFlash = PendingXiaomiRomFlash(
            file = file,
            workspaceDir = workspaceDir,
            mode = mode,
            resumeFromActionIndex = resumeIndex,
            expectedProducts = expectedProducts
        )
        persistXiaomiRomResumeState(
            file = file,
            workspaceDir = workspaceDir,
            mode = mode,
            resumeIndex = resumeIndex,
            expectedProducts = expectedProducts,
            reason = reason,
            session = session
        )
        log("=== FASTBOOTD TRANSITION ===")
        log("Причина: $reason")
        log("Команда: fastboot reboot fastboot")
        log("После перезагрузки в fastbootd NekoMiFlash автоматически продолжит с шага ${resumeIndex + 1}, если Android снова выдаст USB-доступ.")
        val ok = proto.sendCommand(fastbootRebootCommand("fastboot"))
        if (!ok) {
            pendingXiaomiRomFlash = null
            clearPersistentXiaomiRomResume(appendAudit = true)
            log("❌ Не удалось отправить fastboot reboot fastboot. Перейдите в fastbootd вручную и запустите прошивку снова.")
            return
        }
        appendXiaomiRomFlashProgress(session, resumeIndex, XiaomiFastbootRomManager.PreparedAction.Reboot("fastboot"), "WAIT_FASTBOOTD", proto.currentDiagnostics())
        log("✅ Устройство переводится в fastbootd. Дождитесь переподключения USB.")
    }

    private fun schedulePendingXiaomiRomResumeIfReady(diagnostics: FastbootProtocol.DeviceDiagnostics) {
        val pending = pendingXiaomiRomFlash ?: return
        val age = System.currentTimeMillis() - pending.requestedAtMs
        if (age > FASTBOOTD_RESUME_TIMEOUT_MS) {
            pendingXiaomiRomFlash = null
            clearPersistentXiaomiRomResume(appendAudit = true)
            log("⚠️ Ожидание fastbootd для Xiaomi ROM истекло. Автопродолжение отменено.")
            return
        }
        if (diagnostics.isUserspace?.equals("yes", ignoreCase = true) != true) {
            log("ℹ️ Ожидается fastbootd/userspace для продолжения Xiaomi ROM. Текущий режим ещё bootloader fastboot.")
            return
        }
        val product = diagnostics.product?.trim()
        if (!product.isNullOrBlank() && pending.expectedProducts.isNotEmpty()) {
            val allowed = pending.expectedProducts.any { it.equals(product, ignoreCase = true) }
            if (!allowed) {
                log("⛔ Fastbootd resume отменён: product=$product, ROM=${pending.expectedProducts.joinToString(", ")}")
                pendingXiaomiRomFlash = null
                clearPersistentXiaomiRomResume(appendAudit = true)
                return
            }
        }
        log("✅ Fastbootd обнаружен. Автоматически продолжаем Xiaomi Fastboot ROM с шага ${pending.resumeFromActionIndex + 1}.")
        viewModelScope.launch(Dispatchers.IO) {
            delay(800)
            val latest = pendingXiaomiRomFlash ?: return@launch
            runXiaomiFastbootRomInternal(
                latest.file,
                latest.workspaceDir,
                latest.mode,
                resumeFromActionIndex = latest.resumeFromActionIndex,
                resumeAfterFastbootd = true
            )
        }
    }

    private fun remainingActionsRequireFastbootd(actions: List<XiaomiFastbootRomManager.PreparedAction>): Boolean {
        return actions.any { action ->
            action is XiaomiFastbootRomManager.PreparedAction.UpdateSuper ||
                (action is XiaomiFastbootRomManager.PreparedAction.Flash && isLikelyFastbootdPartition(action.partition))
        }
    }

    private fun isLikelyFastbootdPartition(partition: String): Boolean {
        val clean = partition.lowercase(Locale.US).removeSuffix("_a").removeSuffix("_b")
        return clean in FASTBOOTD_PARTITIONS || clean.startsWith("system") || clean.startsWith("vendor") || clean.startsWith("product") || clean.startsWith("odm")
    }

    private fun isFastbootdRebootTarget(target: String?): Boolean {
        return target?.lowercase(Locale.US) in setOf("fastboot", "fastbootd")
    }

    private fun fastbootRebootCommand(target: String?): String {
        return when (target?.lowercase(Locale.US)) {
            null, "", "system" -> "reboot"
            "bootloader" -> "reboot-bootloader"
            "fastboot", "fastbootd" -> "reboot:fastboot"
            "recovery" -> "reboot-recovery"
            else -> "reboot:${target}"
        }
    }

    fun runSideload(file: File) {
        startOperation(text(R.string.notif_adb_sideload), text(R.string.notif_sideload_sending, file.name)) {
            val proto = adbProtocol
            if (proto?.isConnected == true) proto.sideloadZip(file)
            else log(text(R.string.error_no_adb))
        }
    }

    /**
     * Запускает ровно одну долгую USB-операцию.
     *
     * Важно: previousJob захватывается ДО присваивания activeJob. Если читать
     * activeJob уже внутри новой coroutine, новая операция может увидеть саму
     * себя, отменить себя же через cancelAndJoin() и не выполнить блок.
     */
    private fun startOperation(title: String, text: String, block: suspend () -> Unit) {
        val previousJob = activeJob
        val gen = operationGeneration.incrementAndGet()

        releaseOperationWakeLock(logRelease = false)
        acquireOperationWakeLock()
        FlashOperationService.start(getApplication(), title, text)
        _operationActive.postValue(true)

        val newJob = viewModelScope.launch(Dispatchers.IO) {
            if (previousJob != null && previousJob.isActive) {
                fastbootProtocol?.cancel()
                adbProtocol?.cancel()
                try { previousJob.cancelAndJoin() } catch (_: Exception) {}
            }

            try {
                block()
            } catch (e: Exception) {
                log(text(R.string.operation_error, e.message ?: e.javaClass.simpleName))
            } finally {
                // AtomicLong.get() — видимость между потоками гарантирована.
                // Старые отменённые операции не должны гасить уведомление новой операции.
                if (gen == operationGeneration.get()) {
                    _operationActive.postValue(false)
                    releaseOperationWakeLock(logRelease = true)
                    FlashOperationService.stop(getApplication())
                }
            }
        }
        activeJob = newJob
    }

    private fun acquireOperationWakeLock() {
        try {
            val wl = getApplication<Application>()
                .getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NekoMiFlash:FlashOperation")
                .apply { setReferenceCounted(false); acquire(WAKE_LOCK_TIMEOUT_MS) }
            operationWakeLock = wl
            log(text(R.string.wake_lock_acquired))
        } catch (e: Exception) {
            operationWakeLock = null
            log(text(R.string.wake_lock_error, e.message ?: e.javaClass.simpleName))
        }
    }

    private fun releaseOperationWakeLock(logRelease: Boolean) {
        val wl = operationWakeLock ?: return
        operationWakeLock = null
        try {
            if (wl.isHeld) wl.release()
            if (logRelease) log(text(R.string.wake_lock_released))
        } catch (e: Exception) {
            if (logRelease) log(text(R.string.wake_lock_release_error, e.message ?: e.javaClass.simpleName))
        }
    }

    fun cancelActiveOperation() {
        pendingXiaomiRomFlash = null
        operationGeneration.incrementAndGet()
        fastbootProtocol?.cancel()
        adbProtocol?.cancel()
        activeJob?.cancel()
        _operationActive.postValue(false)
        releaseOperationWakeLock(logRelease = true)
        FlashOperationService.stop(getApplication())
        log(text(R.string.operation_cancelled))
    }

    // ─── ОТКЛЮЧЕНИЕ ──────────────────────────────────────────────────────────

    fun disconnectCurrent() {
        operationGeneration.incrementAndGet()
        activeJob?.cancel()
        releaseOperationWakeLock(logRelease = false)
        FlashOperationService.stop(getApplication())
        fastbootProtocol?.disconnect()
        adbProtocol?.disconnect()
        fastbootProtocol    = null
        adbProtocol         = null
        connectedDeviceInfo = null
        _connectionInfo.postValue(null)
        _fastbootDiagnostics.postValue(null)
        _operationActive.postValue(false)
        _connectionState.postValue(ConnectionState.NONE)
    }

    override fun onCleared() {
        super.onCleared()
        disconnectCurrent()
    }

    companion object {
        private const val FASTBOOT_DOWNLOAD_IMPLEMENTATION_LIMIT_BYTES = 0xFFFF_FFFFL
        private const val WAKE_LOCK_TIMEOUT_MS = 6L * 60L * 60L * 1000L
        private const val FASTBOOTD_RESUME_TIMEOUT_MS = 2L * 60L * 1000L
        private const val MAX_OPERATION_STEPS_IN_UI = 240
        private const val XIAOMI_RESUME_PREFS = "xiaomi_rom_resume"
        private const val XIAOMI_RESUME_FILE_PATH = "file_path"
        private const val XIAOMI_RESUME_WORKSPACE_PATH = "workspace_path"
        private const val XIAOMI_RESUME_MODE = "mode"
        private const val XIAOMI_RESUME_INDEX = "resume_index"
        private const val XIAOMI_RESUME_PRODUCTS = "expected_products"
        private const val XIAOMI_RESUME_REASON = "reason"
        private const val XIAOMI_RESUME_MARKER_PATH = "marker_path"
        private const val XIAOMI_RESUME_CREATED_AT = "created_at"
        private val FASTBOOTD_PARTITIONS = setOf(
            "system", "system_ext", "product", "vendor", "odm", "cust", "mi_ext",
            "vendor_dlkm", "odm_dlkm", "system_dlkm", "product_dlkm",
            "vendor_bootconfig", "system_other"
        )
    }
}
