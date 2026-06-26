package ru.forum.adbfastboottool

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ForumReportManager {

    enum class PrivacyMode { SANITIZED, RAW }

    fun createReport(
        context: Context,
        usbManager: UsbManager,
        workspace: File,
        currentLogFile: File?,
        visibleLogLines: List<String>,
        connectionInfo: String?,
        fastbootDiagnostics: FastbootProtocol.DeviceDiagnostics?,
        adbDiagnostics: AdbProtocol.DeviceDiagnostics? = null,
        debugLogging: Boolean,
        extraFiles: List<Pair<File, String>> = emptyList(),
        privacyMode: PrivacyMode = PrivacyMode.SANITIZED
    ): File {
        val reportsDir = File(workspace, "reports")
        if (!reportsDir.exists()) reportsDir.mkdirs()
        val stamp  = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(Date())
        val report = File(reportsDir, "report-$stamp.zip")

        val profilesDir = File(workspace, "profiles")
        val sanitizerScope = ReportSanitizer.Scope(
            workspace = workspace,
            logFile = currentLogFile,
            adbKeyDir = File(context.filesDir, "adbkeys"),
            profilesDir = profilesDir,
            packageName = context.packageName
        )
        val sanitized = privacyMode == PrivacyMode.SANITIZED
        fun safe(text: String): String = if (sanitized) ReportSanitizer.sanitizeText(text, sanitizerScope) else text
        fun safeLines(text: List<String>): List<String> = if (sanitized) ReportSanitizer.sanitizeLines(text, sanitizerScope) else text

        ZipOutputStream(report.outputStream().buffered()).use { zip ->
            zipText(zip, "manifest.txt",       safe(buildManifest(stamp, extraFiles, privacyMode)))
            zipText(zip, "app-info.txt",       safe(buildAppInfo(context, debugLogging, privacyMode)))
            zipText(zip, "usb-info.txt",       safe(buildUsbInfo(usbManager.deviceList.values)))
            zipText(zip, "fastboot-info.txt",  safe(buildFastbootInfo(connectionInfo, fastbootDiagnostics)))
            zipText(zip, "adb-info.txt",       safe(buildAdbInfo(adbDiagnostics)))
            zipText(zip, "diagnostic-summary.json", safe(buildDiagnosticJson(context, connectionInfo, fastbootDiagnostics, adbDiagnostics, debugLogging, safeLines(visibleLogLines), privacyMode)))
            zipText(zip, "files.txt",          safe(buildWorkspaceFiles(workspace)))
            // FIX #12: includeHashes = false — не считаем хэши для отчёта, только тип и размер
            zipText(zip, "file-analysis.txt",  safe(buildFirmwareFileAnalysis(workspace)))
            zipText(zip, "visible-log.txt",    safeLines(visibleLogLines).joinToString("\n"))

            if (currentLogFile?.exists() == true) zipText(zip, "log.txt", safe(readTextBestEffort(currentLogFile)))

            extraFiles.forEach { (file, entryName) ->
                if (file.exists() && file.isFile) zipText(zip, sanitizeEntryName(entryName), safe(readTextBestEffort(file)))
            }

            profilesDir
                .listFiles { f -> f.isFile && f.extension.equals("json", ignoreCase = true) }
                ?.sortedBy { it.name }
                ?.forEach { zipText(zip, "profiles/${sanitizeEntryName(it.name)}", safe(readTextBestEffort(it))) }
        }
        return report
    }

    private fun buildAppInfo(context: Context, debugLogging: Boolean, privacyMode: PrivacyMode): String {
        val pkg  = context.packageName
        val info = context.packageManager.getPackageInfo(pkg, 0)
        @Suppress("DEPRECATION")
        val vc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            info.longVersionCode.toString() else info.versionCode.toString()
        return listOf(
            "Package: $pkg",
            "Version name: ${info.versionName}",
            "Version code: $vc",
            "Host Android SDK: ${Build.VERSION.SDK_INT}",
            "Host Android release: ${Build.VERSION.RELEASE}",
            "Manufacturer: ${Build.MANUFACTURER}",
            "Model: ${Build.MODEL}",
            "Device: ${Build.DEVICE}",
            "Debug logging: $debugLogging",
            "Privacy mode: ${privacyMode.name.lowercase(Locale.US)}"
        ).joinToString("\n")
    }

    private fun buildManifest(stamp: String, extraFiles: List<Pair<File, String>>, privacyMode: PrivacyMode): String {
        val lines = mutableListOf(
            "ADB Fastboot Tool forum report",
            "Created: $stamp",
            "Schema: ru.forum.adbfastboottool.forum-report.v3",
            "Privacy mode: ${privacyMode.name.lowercase(Locale.US)}",
            "",
            "Included core files:",
            "- manifest.txt",
            "- app-info.txt",
            "- usb-info.txt",
            "- adb-info.txt",
            "- fastboot-info.txt",
            "- diagnostic-summary.json",
            "- files.txt",
            "- file-analysis.txt",
            "- visible-log.txt",
            "- log.txt, if a current log file exists",
            "- profiles/*.json, if device profiles exist"
        )
        if (privacyMode == PrivacyMode.SANITIZED) {
            lines.add("")
            lines.add("Privacy filter:")
            lines.add("- serial numbers are redacted")
            lines.add("- host model/manufacturer/device are redacted")
            lines.add("- app-private paths and user-storage paths are redacted")
            lines.add("- ADB public-key path is redacted")
            lines.add("- long hex identifiers in logs are redacted")
        } else {
            lines.add("")
            lines.add("Privacy filter: disabled")
        }
        if (extraFiles.isNotEmpty()) {
            lines.add("")
            lines.add("Included extra files:")
            extraFiles.forEach { (_, entryName) -> lines.add("- ${sanitizeEntryName(entryName)}") }
        }
        return lines.joinToString("\n")
    }

    private fun buildAdbInfo(diagnostics: AdbProtocol.DeviceDiagnostics?): String {
        if (diagnostics == null) return "ADB diagnostics: none"
        return listOf(
            "ADB banner: ${diagnostics.remoteBanner.ifBlank { "unknown" }}",
            "features: ${if (diagnostics.features.isEmpty()) "—" else diagnostics.features.joinToString(",")}",
            "supports shell_v2: ${diagnostics.supportsShellV2}",
            "interactive shell active: ${diagnostics.interactiveShellActive}",
            "ADB public key: ${diagnostics.publicKeyPath}"
        ).joinToString("\n")
    }

    private fun buildDiagnosticJson(
        context: Context,
        connectionInfo: String?,
        fastboot: FastbootProtocol.DeviceDiagnostics?,
        adb: AdbProtocol.DeviceDiagnostics?,
        debugLogging: Boolean,
        visibleLogLines: List<String>,
        privacyMode: PrivacyMode
    ): String {
        val q = DiagnosticJson::quote
        val pkg = context.packageName
        val info = context.packageManager.getPackageInfo(pkg, 0)
        @Suppress("DEPRECATION")
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

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
            appendLine("  \"schema\": \"ru.forum.adbfastboottool.forum-report.v3\",")
            appendLine("  \"privacyMode\": ${q(privacyMode.name.lowercase(Locale.US))},")
            appendLine("  \"package\": ${q(pkg)},")
            appendLine("  \"versionName\": ${q(info.versionName)},")
            appendLine("  \"versionCode\": $versionCode,")
            appendLine("  \"hostAndroid\": {")
            appendLine("    \"sdk\": ${Build.VERSION.SDK_INT},")
            appendLine("    \"release\": ${q(Build.VERSION.RELEASE)},")
            appendLine("    \"manufacturer\": ${q(Build.MANUFACTURER)},")
            appendLine("    \"model\": ${q(Build.MODEL)},")
            appendLine("    \"device\": ${q(Build.DEVICE)}")
            appendLine("  },")
            appendLine("  \"connectionInfo\": ${q(connectionInfo)},")
            appendLine("  \"debugLogging\": ${DiagnosticJson.bool(debugLogging)},")
            appendLine("  \"adb\": ${adbJson()},")
            appendLine("  \"fastboot\": ${fastbootJson()},")
            appendLine("  \"visibleLogTail\": ${DiagnosticJson.stringArray(visibleLogLines.takeLast(200), "    ")}")
            appendLine("}")
        }
    }

    private fun buildUsbInfo(devices: Collection<UsbDevice>): String {
        if (devices.isEmpty()) return "USB devices: none"
        val candidates = UsbDeviceInspector.findAllCandidates(devices)
        val sections = mutableListOf<String>()
        sections.add("Compatible candidates:\n${UsbDeviceInspector.summarizeCandidates(candidates)}")
        devices.sortedBy { it.deviceName }.forEach { sections.add(UsbDeviceInspector.summarizeDevice(it)) }
        return sections.joinToString("\n\n---\n\n")
    }

    private fun buildFastbootInfo(connectionInfo: String?, diagnostics: FastbootProtocol.DeviceDiagnostics?): String {
        val lines = mutableListOf("Connection: ${connectionInfo ?: "not connected"}")
        if (diagnostics == null) {
            lines.add("Fastboot diagnostics: none")
        } else {
            lines.add("product: ${diagnostics.product ?: "unknown"}")
            lines.add("current-slot: ${diagnostics.currentSlot ?: "unknown"}")
            lines.add("slot-count: ${diagnostics.slotCount ?: "unknown"}")
            lines.add("slot-suffix: ${diagnostics.slotSuffix ?: "unknown"}")
            lines.add("unlocked: ${diagnostics.unlocked ?: "unknown"}")
            lines.add("secure: ${diagnostics.secure ?: "unknown"}")
            lines.add("serialno: ${diagnostics.serialno ?: "unknown"}")
            lines.add("version-bootloader: ${diagnostics.versionBootloader ?: "unknown"}")
            lines.add("is-userspace: ${diagnostics.isUserspace ?: "unknown"}")
            lines.add("super-partition-name: ${diagnostics.superPartitionName ?: "unknown"}")
            lines.add("max-download-size raw: ${diagnostics.maxDownloadSizeRaw ?: "unknown"}")
            lines.add("max-download-size bytes: ${diagnostics.maxDownloadSizeBytes ?: "unknown"}")
            lines.add("max-fetch-size raw: ${diagnostics.maxFetchSizeRaw ?: "unknown"}")
            lines.add("max-fetch-size bytes: ${diagnostics.maxFetchSizeBytes ?: "unknown"}")
            lines.add("diagnostics timestamp: ${diagnostics.timestamp}")
        }
        return lines.joinToString("\n")
    }

    private fun buildWorkspaceFiles(workspace: File): String {
        if (!workspace.exists()) return "Workspace not found: ${workspace.absolutePath}"
        val lines = mutableListOf("Workspace: ${workspace.absolutePath}")
        workspace.listFiles()
            ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase(Locale.US) })
            ?.forEach { f ->
                val type = if (f.isDirectory) "DIR " else "FILE"
                val size = if (f.isFile) f.length().toString() else "-"
                lines.add("$type\t$size\t${f.name}")
            }
        return lines.joinToString("\n")
    }

    // FIX #12: includeHashes=false — файлы только анализируются по заголовку,
    // хэши не считаются. Это делает создание отчёта мгновенным.
    private fun buildFirmwareFileAnalysis(workspace: File): String {
        if (!workspace.exists()) return "Workspace not found: ${workspace.absolutePath}"
        val files = workspace.listFiles()
            ?.filter { ImageInspector.isSupportedFirmwareFile(it) &&
                !it.name.endsWith(".sha256", ignoreCase = true) &&
                !it.name.endsWith(".md5", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase(Locale.US) }
            ?: emptyList()
        if (files.isEmpty()) return "Firmware files: none"
        return files.joinToString("\n\n---\n\n") { file ->
            try {
                ImageInspector.analyze(file, includeHashes = false).toDisplayText()
            } catch (e: Exception) {
                "Файл: ${file.name}\nОшибка анализа: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun sanitizeEntryName(name: String): String {
        val normalized = name.replace('\\', '/').trim('/')
        return normalized
            .split('/')
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString("/")
            .ifBlank { "extra-file" }
    }

    private fun readTextBestEffort(file: File): String = try {
        file.readText(Charsets.UTF_8)
    } catch (e: Exception) {
        "<unable to read ${file.name}: ${e.message ?: e.javaClass.simpleName}>"
    }

    private fun zipText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}
