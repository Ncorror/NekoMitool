package ru.forum.adbfastboottool

import java.io.File

/**
 * Privacy filter for user-shareable reports.
 *
 * The filter keeps operationally useful facts such as command names, modes,
 * VID/PID, partition names, sizes, ADB features and bootloader state, but masks
 * identifiers that are usually not needed in a public forum thread.
 */
object ReportSanitizer {

    data class Scope(
        val workspace: File? = null,
        val logFile: File? = null,
        val adbKeyDir: File? = null,
        val profilesDir: File? = null,
        val packageName: String? = null
    )

    const val REDACTED_SERIAL = "<redacted:serial>"
    const val REDACTED_PATH = "<redacted:path>"
    const val REDACTED_HOST = "<redacted:host>"
    const val REDACTED_USB_NAME = "<redacted:usb-device-name>"
    const val REDACTED_USB_PATH = "<redacted:usb-device-path>"
    const val REDACTED_ADB_KEY_PATH = "<redacted:adb-key-path>"

    fun sanitizeLines(lines: List<String>, scope: Scope = Scope()): List<String> =
        lines.map { sanitizeText(it, scope) }

    fun sanitizeNullable(value: String?, scope: Scope = Scope()): String? =
        value?.let { sanitizeText(it, scope) }

    fun sanitizeSerial(value: String?): String? = when {
        value.isNullOrBlank() -> value
        value.equals("unknown", ignoreCase = true) -> value
        else -> REDACTED_SERIAL
    }

    fun sanitizePath(value: String?, scope: Scope = Scope()): String? {
        if (value.isNullOrBlank()) return value
        return sanitizeText(value, scope)
    }

    fun sanitizeAdbBanner(value: String?, scope: Scope = Scope()): String? {
        if (value.isNullOrBlank()) return value
        var out = sanitizeText(value, scope)
        val productKeys = listOf(
            "ro.product.name",
            "ro.product.model",
            "ro.product.device",
            "ro.product.manufacturer",
            "ro.product.brand"
        )
        productKeys.forEach { key ->
            out = out.replace(Regex("(?i)(" + Regex.escape(key) + "=)[^;\\s]+")) { match ->
                match.groupValues[1] + "<redacted:target-product>"
            }
        }
        return out
    }

    fun sanitizeText(text: String, scope: Scope = Scope()): String {
        if (text.isEmpty()) return text
        var out = text

        val knownPaths = listOfNotNull(
            scope.workspace?.absolutePath,
            scope.logFile?.absolutePath,
            scope.logFile?.parentFile?.absolutePath,
            scope.adbKeyDir?.absolutePath,
            scope.profilesDir?.absolutePath,
            scope.packageName?.let { "/data/user/0/$it" },
            scope.packageName?.let { "/data/data/$it" }
        ).distinctBy { it.length }.sortedByDescending { it.length }

        knownPaths.forEach { path ->
            if (path.isNotBlank()) out = out.replace(path, tokenForKnownPath(path, scope))
        }

        out = out
            .replace(Regex("(?i)(ADB public key:\\s*)[^\\s\"]+"), "$1$REDACTED_ADB_KEY_PATH")
            .replace(Regex("(?i)(publicKeyPath\"?\\s*[:=]\\s*\"?)[^\"\\s,}]+"), "$1$REDACTED_ADB_KEY_PATH")
            .replace(Regex("(?i)(ADB key dir:\\s*)[^\\s\"]+"), "$1$REDACTED_ADB_KEY_PATH")
            .replace(Regex("(?i)(adbKeyDir\"?\\s*[:=]\\s*\"?)[^\"\\s,}]+"), "$1$REDACTED_ADB_KEY_PATH")
            .replace(Regex("(?i)(serialno|serial|ro\\.serialno|ro\\.boot\\.serialno)(\\s*[:=]\\s*)[^\\s,;|\"]+")) { m ->
                m.groupValues[1] + m.groupValues[2] + REDACTED_SERIAL
            }
            .replace(Regex("(?i)(\"(?:serialno|serial|ro\\.serialno|ro\\.boot\\.serialno)\"\\s*:\\s*\")[^\"]*\""), "$1$REDACTED_SERIAL\"")
            .replace(Regex("(?i)(DeviceName:\\s*)/dev/bus/usb/[^\\s\"]+"), "$1$REDACTED_USB_PATH")
            .replace(Regex("(?i)(Device:\\s*)(?!<redacted)[^\\n\"]+"), "$1$REDACTED_USB_NAME")
            .replace(Regex("(?i)(Fastboot —\\s*)(?!<redacted)[^\\n\"]+"), "$1$REDACTED_USB_NAME")
            .replace(Regex("(?i)(ADB —\\s*)(?!<redacted)[^\\n\"]+"), "$1$REDACTED_USB_NAME")
            .replace(Regex("(?i)(Устройство:\\s*)([^|\\n\"]+)"), "$1$REDACTED_USB_NAME ")
            .replace(Regex("(?i)(Manufacturer:\\s*)[^\\n\"]+"), "$1$REDACTED_HOST")
            .replace(Regex("(?i)(Model:\\s*)[^\\n\"]+"), "$1$REDACTED_HOST")
            .replace(Regex("(?i)(Device:\\s*)$REDACTED_USB_NAME"), "$1$REDACTED_USB_NAME")
            .replace(Regex("(?i)(Host Android release:\\s*)[^\\n\"]+"), "$1<redacted:host-release>")
            .replace(Regex("(?i)(\"manufacturer\"\\s*:\\s*)\"[^\"]*\""), "$1\"$REDACTED_HOST\"")
            .replace(Regex("(?i)(\"model\"\\s*:\\s*)\"[^\"]*\""), "$1\"$REDACTED_HOST\"")
            .replace(Regex("(?i)(\"device\"\\s*:\\s*)\"[^\"]*\""), "$1\"$REDACTED_HOST\"")
            .replace(Regex("(?i)(\"release\"\\s*:\\s*)\"[^\"]*\""), "$1\"<redacted:host-release>\"")

        out = sanitizeDeviceStoragePaths(out)
        out = sanitizePrivateAppPaths(out)
        out = sanitizeLongHexIdentifiers(out)
        return out
    }

    private fun tokenForKnownPath(path: String, scope: Scope): String = when {
        scope.logFile?.absolutePath == path -> "<log-file>"
        scope.logFile?.parentFile?.absolutePath == path -> "<logs-dir>"
        scope.adbKeyDir?.absolutePath == path -> REDACTED_ADB_KEY_PATH
        scope.profilesDir?.absolutePath == path -> "<profiles-dir>"
        scope.workspace?.absolutePath == path -> "<workspace>"
        else -> REDACTED_PATH
    }

    private fun sanitizeDeviceStoragePaths(value: String): String {
        var out = value
        out = out.replace(Regex("(?<![A-Za-z0-9_])/(sdcard|storage/emulated/0|data/media/0)(/[^\\s\"'`<>]*)?")) { m ->
            val root = m.groupValues[1]
            val suffix = m.groupValues.getOrNull(2).orEmpty()
            if (suffix.isBlank() || suffix == "/") "/$root" else "/$root/<path>"
        }
        return out
    }

    private fun sanitizePrivateAppPaths(value: String): String =
        value.replace(Regex("(?<![A-Za-z0-9_])/(data/user/0|data/data)/[^\\s\"'`<>]+"), REDACTED_PATH)

    private fun sanitizeLongHexIdentifiers(value: String): String =
        value.replace(Regex("(?i)\\b[0-9a-f]{16,}\\b"), "<redacted:hex-id>")
}
