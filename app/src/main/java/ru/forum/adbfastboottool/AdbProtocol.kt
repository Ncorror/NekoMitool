package ru.forum.adbfastboottool

import android.hardware.usb.*
import java.io.File
import java.io.RandomAccessFile
import java.util.ArrayDeque
import java.util.zip.ZipFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

class AdbProtocol(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val keyDirectory: File,
    private val onLog: (String) -> Unit,
    private val onProgress: (Int, String) -> Unit = { _, _ -> }
) {
    private var connection: UsbDeviceConnection? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null
    private var adbInterface: UsbInterface? = null

    @Volatile private var cancelled = false
    private var nextLocalId = 2
    private val adbKeyStore by lazy { AdbKeyStore(keyDirectory, onLog) }
    private val deviceFeatures = linkedSetOf<String>()
    private var remoteBanner: String = ""

    private val interactiveShellLock = Object()
    private var interactiveShellSession: InteractiveShellSession? = null

    val isConnected: Boolean
        get() = connection != null && endpointIn != null && endpointOut != null && adbInterface != null

    private val A_CNXN = 0x4E584E43L
    private val A_OPEN = 0x4E45504FL
    private val A_OKAY = 0x59414B4FL
    private val A_CLSE = 0x45534C43L
    private val A_WRTE = 0x45545257L
    private val A_AUTH = 0x48545541L

    private val AUTH_TOKEN        = 1
    private val AUTH_SIGNATURE    = 2
    private val AUTH_RSAPUBLICKEY = 3

    private val SIDELOAD_BLOCK_SIZE = 65536
    private val MAX_PAYLOAD = 1048576
    private val SYNC_DATA_CHUNK = 64 * 1024
    private val SYNC_MAX_STRING = 1024 * 1024
    private val SYNC_MODE_IFMT = 61440       // 0170000
    private val SYNC_MODE_IFDIR = 16384      // 0040000
    private val SYNC_MODE_IFREG = 32768      // 0100000

    private val SHELL_ID_STDIN = 0
    private val SHELL_ID_STDOUT = 1
    private val SHELL_ID_STDERR = 2
    private val SHELL_ID_EXIT = 3
    private val SHELL_ID_CLOSE_STDIN = 4
    private val SHELL_PACKET_HEADER = 5

    data class DeviceDiagnostics(
        val remoteBanner: String,
        val features: List<String>,
        val supportsShellV2: Boolean,
        val interactiveShellActive: Boolean,
        val publicKeyPath: String
    )

    val supportsShellV2: Boolean
        get() = deviceFeatures.contains("shell_v2")

    val hasInteractiveShell: Boolean
        get() = synchronized(interactiveShellLock) { interactiveShellSession != null }

    fun currentDiagnostics(): DeviceDiagnostics = DeviceDiagnostics(
        remoteBanner = remoteBanner,
        features = deviceFeatures.toList(),
        supportsShellV2 = supportsShellV2,
        interactiveShellActive = hasInteractiveShell,
        publicKeyPath = adbKeyStore.publicKeyPath()
    )

    // ─── ПОДКЛЮЧЕНИЕ ─────────────────────────────────────────────────────────

    fun connect(): Boolean {
        cancelled = false

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == 255 &&
                iface.interfaceSubclass == 66 &&
                iface.interfaceProtocol == 1
            ) {
                adbInterface = iface
                break
            }
        }
        if (adbInterface == null) {
            onLog("ОШИБКА: ADB интерфейс не найден")
            return false
        }

        for (i in 0 until adbInterface!!.endpointCount) {
            val ep = adbInterface!!.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN)  endpointIn  = ep
                if (ep.direction == UsbConstants.USB_DIR_OUT) endpointOut = ep
            }
        }

        connection = usbManager.openDevice(device)
        if (connection == null || endpointIn == null || endpointOut == null) {
            onLog("ОШИБКА: Не удалось открыть USB устройство или endpoints")
            disconnect()
            return false
        }

        if (!connection!!.claimInterface(adbInterface, true)) {
            onLog("ОШИБКА: Не удалось захватить ADB интерфейс")
            disconnect()
            return false
        }

        // Отправляем CNXN — инициируем рукопожатие
        sendMessageInternal(A_CNXN, 0x01000000, MAX_PAYLOAD,
            "host::NekoFlash\u0000".toByteArray(Charsets.UTF_8))

        val header = readHeader() ?: run {
            onLog("ОШИБКА: Сбой подключения ADB (нет ответа)")
            disconnect()
            return false
        }

        return when (header.command) {
            A_CNXN -> {
                handleConnectionBanner(header)
                onLog("=== СОЕДИНЕНИЕ ADB УСТАНОВЛЕНО ===")
                true
            }

            A_AUTH -> {
                val ok = handleAuthPacket(header)
                if (!ok) disconnect()
                ok
            }

            else -> {
                onLog("ОШИБКА: Неожиданный ответ (cmd=0x${header.command.toString(16)})")
                disconnect()
                false
            }
        }
    }


    private fun handleAuthPacket(firstHeader: AdbHeader): Boolean {
        if (firstHeader.arg0 != AUTH_TOKEN) {
            if (firstHeader.dataLength > 0) readData(firstHeader.dataLength)
            onLog("❌ Неподдерживаемый ADB AUTH тип: ${firstHeader.arg0}")
            return false
        }

        val firstToken = readData(firstHeader.dataLength) ?: run {
            onLog("❌ Не удалось прочитать ADB AUTH TOKEN")
            return false
        }

        onLog("🔐 ADB AUTH: устройство требует RSA-авторизацию")

        var publicKeySent = false
        try {
            val signature = adbKeyStore.signToken(firstToken)
            onLog("🔑 Пробуем авторизацию сохранённым ADB RSA-ключом")
            sendMessageInternal(A_AUTH, AUTH_SIGNATURE, 0, signature)
        } catch (e: Exception) {
            onLog("⚠️ Не удалось подписать ADB TOKEN: ${e.message ?: e.javaClass.simpleName}")
            publicKeySent = sendAdbPublicKeyForAuth()
            if (!publicKeySent) return false
        }

        repeat(12) { attempt ->
            if (cancelled) return false

            val timeout = if (publicKeySent) 60_000 else 10_000
            val header = readHeader(timeoutMs = timeout) ?: run {
                if (publicKeySent) {
                    onLog("❌ ADB RSA не подтверждён на устройстве за 60 сек")
                } else {
                    onLog("❌ Устройство не ответило на ADB RSA-подпись")
                }
                return false
            }

            when (header.command) {
                A_CNXN -> {
                    handleConnectionBanner(header)
                    onLog("✅ ADB авторизован. Соединение установлено.")
                    return true
                }

                A_AUTH -> {
                    when (header.arg0) {
                        AUTH_TOKEN -> {
                            if (header.dataLength > 0 && readData(header.dataLength) == null) {
                                onLog("❌ Не удалось прочитать повторный ADB AUTH TOKEN")
                                return false
                            }

                            if (!publicKeySent) {
                                publicKeySent = sendAdbPublicKeyForAuth()
                                if (!publicKeySent) return false
                            } else if (attempt % 3 == 2) {
                                onLog("⏳ Всё ещё ждём подтверждение ADB RSA на устройстве...")
                            }
                        }

                        else -> {
                            if (header.dataLength > 0) readData(header.dataLength)
                            onLog("❌ Неподдерживаемый ADB AUTH тип: ${header.arg0}")
                            return false
                        }
                    }
                }

                else -> {
                    if (header.dataLength > 0) readData(header.dataLength)
                    onLog("❌ Неожиданный ответ во время ADB AUTH: cmd=0x${header.command.toString(16)}")
                    return false
                }
            }
        }

        onLog("❌ ADB RSA-авторизация не завершена")
        onLog("💡 Проверьте экран устройства, USB-отладку и пункт 'Always allow from this computer'")
        return false
    }


    private fun sendAdbPublicKeyForAuth(): Boolean {
        return try {
            val publicKeyPayload = adbKeyStore.publicKeyPayload()
            sendMessageInternal(A_AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyPayload)
            onLog("📤 Отправлен ADB public key")
            onLog("⏳ Подтвердите запрос «Allow USB debugging» на экране устройства")
            onLog("ℹ️ Public key сохранён: ${adbKeyStore.publicKeyPath()}")
            true
        } catch (e: Exception) {
            onLog("❌ Не удалось отправить ADB public key: ${e.message ?: e.javaClass.simpleName}")
            false
        }

    }

    private fun handleConnectionBanner(header: AdbHeader) {
        val bannerBytes = readData(header.dataLength)
        remoteBanner = bannerBytes?.toString(Charsets.UTF_8)?.trimEnd('\u0000').orEmpty()
        parseRemoteFeatures(remoteBanner)
        if (remoteBanner.isNotBlank()) onLog("ADB banner: $remoteBanner")
        if (supportsShellV2) {
            onLog("✅ ADB feature shell_v2 обнаружена: доступен exit-code и разделение stdout/stderr")
        } else {
            onLog("ℹ️ ADB feature shell_v2 не заявлена: shell будет работать в legacy-режиме без точного exit-code")
        }
    }

    private fun parseRemoteFeatures(banner: String) {
        deviceFeatures.clear()
        val featureText = banner
            .split(';')
            .firstOrNull { it.startsWith("features=") }
            ?.substringAfter("features=")
            .orEmpty()
        if (featureText.isNotBlank()) {
            featureText.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { deviceFeatures.add(it) }
        }
    }

    // ─── SIDELOAD ─────────────────────────────────────────────────────────────

    fun sideloadZip(file: File) {
        if (!isConnected) return
        cancelled = false

        onLog("Анализ файла: ${file.name}")
        if (!HashUtils.verifyFileWithSidecars(file, onLog)) {
            onLog("ADB Sideload отменён из-за ошибки контрольной суммы.")
            return
        }

        onLog("Старт ADB Sideload...")

        sendMessageInternal(
            A_OPEN, 1, 0,
            "sideload-host:${file.length()}:$SIDELOAD_BLOCK_SIZE\u0000".toByteArray()
        )
        val openResp = readHeader()
        if (openResp == null || openResp.command != A_OKAY) {
            onLog("ОШИБКА: Устройство не в режиме Sideload (нет A_OKAY на OPEN).")
            onLog("💡 Убедитесь: Recovery → Apply update → Apply from ADB")
            return
        }
        val remoteId = openResp.arg0

        try {
            RandomAccessFile(file, "r").use { raf ->
                // FIX #10: считаем прогресс по уникальным обработанным блокам,
                // а не по blockNum — устройство может запрашивать в любом порядке.
                val processedBlocks = mutableSetOf<Int>()
                val totalBlocks = ceil(file.length() / SIDELOAD_BLOCK_SIZE.toDouble()).toLong()
                var lastLoggedProgress = -1

                while (!cancelled) {
                    val reqHeader = readHeader() ?: break

                    when (reqHeader.command) {
                        A_CLSE -> {
                            onLog("=== ПРОШИВКА ЗАВЕРШЕНА ===")
                            sendMessageInternal(A_CLSE, 1, remoteId, ByteArray(0))
                            break
                        }
                        A_WRTE -> {
                            val reqData = readData(reqHeader.dataLength)
                            sendMessageInternal(A_OKAY, 1, remoteId, ByteArray(0))

                            if (reqData != null && reqData.size >= 8) {
                                val blockStr = String(reqData, 0, 8, Charsets.US_ASCII)
                                val blockNum = blockStr.trim('\u0000', ' ').toIntOrNull()

                                if (blockNum != null) {
                                    val offset = blockNum.toLong() * SIDELOAD_BLOCK_SIZE
                                    raf.seek(offset)
                                    val buffer = ByteArray(SIDELOAD_BLOCK_SIZE)
                                    val readBytes = raf.read(buffer)

                                    val payload = if (readBytes > 0) buffer.copyOfRange(0, readBytes)
                                                  else ByteArray(0)
                                    sendMessageInternal(A_WRTE, 1, remoteId, payload)
                                    readHeader() // ACK от устройства

                                    if (readBytes > 0) {
                                        processedBlocks.add(blockNum)
                                        val progress = ((processedBlocks.size.toLong() * 100L) / totalBlocks).toInt()
                                        if (progress % 5 == 0 && progress != lastLoggedProgress) {
                                            onLog("Sideload: $progress% (блок $blockNum)")
                                            onProgress(progress, "ADB Sideload · блок $blockNum")
                                            lastLoggedProgress = progress
                                        }
                                    }
                                } else {
                                    onLog("ОШИБКА: Не удалось распознать номер блока")
                                    sendMessageInternal(A_WRTE, 1, remoteId, ByteArray(0))
                                    readHeader()
                                }
                            }
                        }
                        else -> {
                            if (reqHeader.dataLength > 0) readData(reqHeader.dataLength)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            onLog("ОШИБКА Sideload: ${e.message}")
        }
    }


    // ─── ADB SYNC / FILE TRANSFER ────────────────────────────────────────────

    fun pushPath(localPath: File, remotePath: String, mode: Int = 0x1A4): Boolean {
        if (!isConnected) return false
        cancelled = false

        return when {
            localPath.exists() && localPath.isFile && localPath.canRead() -> pushFile(localPath, remotePath, mode)
            localPath.exists() && localPath.isDirectory && localPath.canRead() -> pushDirectory(localPath, remotePath, mode)
            else -> {
                onLog("❌ adb push: локальный путь недоступен: ${localPath.absolutePath}")
                false
            }
        }
    }

    fun pushFile(localFile: File, remotePath: String, mode: Int = 0x1A4): Boolean {
        if (!isConnected) return false
        cancelled = false

        if (!localFile.exists() || !localFile.isFile || !localFile.canRead()) {
            onLog("❌ adb push: локальный файл недоступен: ${localFile.absolutePath}")
            return false
        }
        val cleanRemote = remotePath.trim()
        if (!isSafeRemotePath(cleanRemote)) {
            onLog("❌ adb push: некорректный remote path")
            return false
        }

        onLog("-> adb push ${localFile.name} $cleanRemote")
        onLog("Размер: ${localFile.length()} байт")

        val stream = openAdbStream("sync:") ?: return false
        var ok = false
        try {
            val spec = "$cleanRemote,$mode".toByteArray(Charsets.UTF_8)
            if (!writeSyncRequest(stream, "SEND", spec)) return false

            val total = localFile.length().coerceAtLeast(1L)
            var sentBytes = 0L
            var lastProgress = -1
            RandomAccessFile(localFile, "r").use { raf ->
                val buffer = ByteArray(SYNC_DATA_CHUNK)
                while (!cancelled) {
                    val read = raf.read(buffer)
                    if (read <= 0) break
                    if (!writeSyncData(stream, "DATA", buffer, read)) return false
                    sentBytes += read.toLong()
                    val progress = ((sentBytes * 100L) / total).toInt()
                    if (progress >= 100 || progress / 10 != lastProgress / 10) {
                        onLog("adb push: $progress% ($sentBytes/${localFile.length()} байт)")
                        lastProgress = progress
                    }
                }
            }
            if (cancelled) {
                onLog("⚠️ adb push отменён")
                return false
            }

            val mtime = (localFile.lastModified() / 1000L).toInt()
            if (!writeSyncIdAndInt(stream, "DONE", mtime)) return false
            ok = readSyncStatus(stream, "adb push")
            if (ok) onLog("✅ adb push завершён: $cleanRemote")
            return ok
        } catch (e: Exception) {
            onLog("❌ adb push ошибка: ${e.message ?: e.javaClass.simpleName}")
            return false
        } finally {
            closeAdbStream(stream)
        }
    }

    private fun pushDirectory(localDir: File, remotePath: String, mode: Int = 0x1A4): Boolean {
        if (!isConnected) return false
        cancelled = false

        if (!localDir.exists() || !localDir.isDirectory || !localDir.canRead()) {
            onLog("❌ adb push: локальная папка недоступна: ${localDir.absolutePath}")
            return false
        }
        val cleanRemote = remotePath.trim()
        if (!isSafeRemotePath(cleanRemote)) {
            onLog("❌ adb push: некорректный remote path")
            return false
        }

        val remoteStat = if (cleanRemote.endsWith("/")) null else statRemotePath(cleanRemote, logMissing = false)
        val targetRoot = if (cleanRemote.endsWith("/") || remoteStat?.isDirectory == true) {
            joinRemotePath(cleanRemote.trimEnd('/'), localDir.name)
        } else {
            cleanRemote.trimEnd('/')
        }

        val allEntries = localDir.walkTopDown().toList()
        val directories = allEntries.filter { it.isDirectory }
        val files = allEntries.filter { it.isFile }
        onLog("-> adb push -r ${localDir.absolutePath} $targetRoot")
        onLog("ℹ️ Каталог: ${directories.size} папок, ${files.size} файлов")

        directories.forEach { dir ->
            if (cancelled) return false
            val relative = dir.relativeTo(localDir).path.replace(File.separatorChar, '/')
            val remoteDir = if (relative.isBlank() || relative == ".") targetRoot else joinRemotePath(targetRoot, relative)
            if (!ensureRemoteDirectory(remoteDir)) return false
        }

        var pushed = 0
        files.forEach { file ->
            if (cancelled) return false
            val relative = file.relativeTo(localDir).path.replace(File.separatorChar, '/')
            val remoteFile = joinRemotePath(targetRoot, relative)
            onLog("ℹ️ adb push file ${pushed + 1}/${files.size}: $relative")
            if (!pushFile(file, remoteFile, mode)) return false
            pushed++
        }

        return if (cancelled) {
            onLog("⚠️ adb push каталога отменён")
            false
        } else {
            onLog("✅ adb push каталога завершён: $pushed файлов → $targetRoot")
            true
        }
    }

    fun pullFile(remotePath: String, localFile: File): Boolean {
        if (!isConnected) return false
        cancelled = false

        val cleanRemote = remotePath.trim()
        if (!isSafeRemotePath(cleanRemote)) {
            onLog("❌ adb pull: некорректный remote path")
            return false
        }

        val stat = statRemotePath(cleanRemote)
        if (stat == null || !stat.exists) {
            onLog("❌ adb pull: remote path не найден или недоступен: $cleanRemote")
            return false
        }

        return if (stat.isDirectory) {
            pullDirectory(cleanRemote, localFile)
        } else {
            pullFileSingle(cleanRemote, localFile, stat.size.takeIf { it >= 0L })
        }
    }

    private fun pullFileSingle(remotePath: String, localFile: File, expectedSize: Long?): Boolean {
        val cleanRemote = remotePath.trim()
        val parent = localFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            onLog("❌ adb pull: не удалось создать папку: ${parent.absolutePath}")
            return false
        }

        onLog("-> adb pull $cleanRemote ${localFile.absolutePath}")
        if (expectedSize != null && expectedSize >= 0L) {
            onLog("Размер remote-файла: $expectedSize байт")
        }

        val stream = openAdbStream("sync:") ?: return false
        val tempFile = File(localFile.absolutePath + ".part")
        var receivedBytes = 0L
        var lastProgress = -1
        try {
            val pathBytes = cleanRemote.toByteArray(Charsets.UTF_8)
            if (!writeSyncRequest(stream, "RECV", pathBytes)) return false

            RandomAccessFile(tempFile, "rw").use { raf ->
                raf.setLength(0)
                while (!cancelled) {
                    val header = readSyncHeader(stream) ?: return false
                    when (header.id) {
                        "DATA" -> {
                            if (header.value < 0 || header.value > SYNC_DATA_CHUNK * 4) {
                                onLog("❌ adb pull: некорректный размер DATA=${header.value}")
                                return false
                            }
                            val data = readAdbStreamExact(stream, header.value) ?: return false
                            raf.write(data)
                            receivedBytes += data.size.toLong()
                            if (expectedSize != null && expectedSize > 0L) {
                                val progress = ((receivedBytes * 100L) / expectedSize).toInt().coerceAtMost(100)
                                if (progress >= 100 || progress / 10 != lastProgress / 10) {
                                    onLog("adb pull: $progress% ($receivedBytes/$expectedSize байт)")
                                    lastProgress = progress
                                }
                            } else if (receivedBytes == data.size.toLong() || receivedBytes % (1024L * 1024L) < data.size) {
                                onLog("adb pull: принято $receivedBytes байт")
                            }
                        }
                        "DONE" -> {
                            raf.fd.sync()
                            if (localFile.exists() && !localFile.delete()) {
                                onLog("❌ adb pull: не удалось заменить файл: ${localFile.absolutePath}")
                                return false
                            }
                            if (!tempFile.renameTo(localFile)) {
                                onLog("❌ adb pull: не удалось сохранить файл: ${localFile.absolutePath}")
                                return false
                            }
                            onLog("✅ adb pull завершён: ${localFile.absolutePath} ($receivedBytes байт)")
                            return true
                        }
                        "FAIL" -> {
                            val message = readSyncString(stream, header.value)
                            onLog("❌ adb pull FAIL: $message")
                            return false
                        }
                        else -> {
                            onLog("❌ adb pull: неожиданный sync id=${header.id}")
                            return false
                        }
                    }
                }
            }

            onLog("⚠️ adb pull отменён")
            return false
        } catch (e: Exception) {
            onLog("❌ adb pull ошибка: ${e.message ?: e.javaClass.simpleName}")
            return false
        } finally {
            try { if (tempFile.exists()) tempFile.delete() } catch (_: Exception) {}
            closeAdbStream(stream)
        }
    }

    private fun pullDirectory(remoteDir: String, localDir: File): Boolean {
        if (!isConnected) return false
        cancelled = false

        if (localDir.exists() && localDir.isFile) {
            onLog("❌ adb pull: remote path является каталогом, а локальный путь — файл: ${localDir.absolutePath}")
            return false
        }
        if (!localDir.exists() && !localDir.mkdirs()) {
            onLog("❌ adb pull: не удалось создать локальную папку: ${localDir.absolutePath}")
            return false
        }

        onLog("-> adb pull -r $remoteDir ${localDir.absolutePath}")
        val listCommand = "cd ${shellQuote(remoteDir)} && echo AFT_DIRS_BEGIN && find . -type d -print && echo AFT_FILES_BEGIN && find . -type f -print"
        val listResult = runShellCommandForResult(listCommand, logOutput = false)
        if (!listResult.success) {
            onLog("❌ adb pull: не удалось получить список файлов remote-каталога")
            return false
        }

        val sections = parseFindSections(listResult.stdout)
        val dirs = sections.first
        val files = sections.second
        onLog("ℹ️ Remote-каталог: ${dirs.size} папок, ${files.size} файлов")

        dirs.forEach { relative ->
            if (cancelled) return false
            val localSubDir = if (relative == ".") localDir else File(localDir, normalizeRelativeRemotePath(relative))
            if (!localSubDir.exists() && !localSubDir.mkdirs()) {
                onLog("❌ adb pull: не удалось создать локальную папку: ${localSubDir.absolutePath}")
                return false
            }
        }

        var pulled = 0
        files.forEach { relative ->
            if (cancelled) return false
            val cleanRelative = normalizeRelativeRemotePath(relative)
            if (cleanRelative.isBlank()) return@forEach
            val remoteFile = joinRemotePath(remoteDir.trimEnd('/'), cleanRelative)
            val localTarget = File(localDir, cleanRelative)
            val stat = statRemotePath(remoteFile, logMissing = false)
            onLog("ℹ️ adb pull file ${pulled + 1}/${files.size}: $cleanRelative")
            if (!pullFileSingle(remoteFile, localTarget, stat?.size?.takeIf { it >= 0L })) return false
            pulled++
        }

        return if (cancelled) {
            onLog("⚠️ adb pull каталога отменён")
            false
        } else {
            onLog("✅ adb pull каталога завершён: $pulled файлов → ${localDir.absolutePath}")
            true
        }
    }

    fun installPackage(packageFile: File, options: List<String>): Boolean {
        if (!packageFile.exists() || !packageFile.isFile || !packageFile.canRead()) {
            onLog("❌ adb install: файл недоступен: ${packageFile.absolutePath}")
            return false
        }

        return when (packageFile.extension.lowercase()) {
            "apk" -> installApk(packageFile, options)
            "apks", "xapk" -> installPackageArchive(packageFile, options)
            else -> {
                onLog("⚠️ adb install: неизвестное расширение .${packageFile.extension}. Пробуем как APK.")
                installApk(packageFile, options)
            }
        }
    }

    fun installApk(apkFile: File, options: List<String>): Boolean {
        if (!apkFile.exists() || !apkFile.isFile || !apkFile.canRead()) {
            onLog("❌ adb install: APK недоступен: ${apkFile.absolutePath}")
            return false
        }
        val safeName = apkFile.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val remotePath = "/data/local/tmp/aft-${System.currentTimeMillis()}-$safeName"
        val installOptions = options.filter { it.isNotBlank() }
        onLog("-> adb install ${installOptions.joinToString(" ")} ${apkFile.name}".trim())
        if (!pushFile(apkFile, remotePath, 0x1A4)) return false

        val optionText = installOptions.joinToString(" ") { shellQuote(it) }
        val command = buildString {
            append("pm install")
            if (optionText.isNotBlank()) append(' ').append(optionText)
            append(' ').append(shellQuote(remotePath))
            append("; rc=\$?; echo AFT_PM_INSTALL_RC:\$rc; rm -f ").append(shellQuote(remotePath)).append("; exit \$rc")
        }
        onLog("ℹ️ APK временно загружен в $remotePath")
        onLog("ℹ️ Запускаем package manager на target-устройстве")
        return runShellCommand(command)
    }

    private fun installPackageArchive(archiveFile: File, options: List<String>): Boolean {
        if (!isConnected) return false
        cancelled = false

        val cacheRoot = File(keyDirectory.parentFile ?: keyDirectory, "adb-package-cache")
        if (!cacheRoot.exists() && !cacheRoot.mkdirs()) {
            onLog("❌ adb install: не удалось создать временную папку: ${cacheRoot.absolutePath}")
            return false
        }
        val workDir = File(cacheRoot, "pkg-${System.currentTimeMillis()}")
        if (!workDir.mkdirs()) {
            onLog("❌ adb install: не удалось создать временную папку: ${workDir.absolutePath}")
            return false
        }

        onLog("-> adb install ${options.joinToString(" ")} ${archiveFile.name}".trim())
        onLog("ℹ️ Контейнер ${archiveFile.extension.uppercase()}: распаковываем APK-файлы")

        try {
            val contents = extractPackageArchiveContents(archiveFile, workDir)
            val extracted = contents.apks
            if (extracted.isEmpty()) {
                onLog("❌ В контейнере не найдено APK-файлов: ${archiveFile.name}")
                return false
            }
            if (contents.obbs.isNotEmpty()) {
                onLog("ℹ️ Найдено OBB в контейнере: ${contents.obbs.size}")
            }

            val selected = selectArchiveApksForInstall(extracted)
            onLog("ℹ️ Выбрано APK для установки: ${selected.size}")
            selected.forEachIndexed { index, item ->
                onLog("   ${index + 1}. ${item.file.name} ← ${item.entryName}")
            }

            val installOk = if (selected.size == 1) {
                installApk(selected.first().file, options)
            } else {
                installMultipleApks(selected.map { it.file }, options)
            }
            if (!installOk) return false

            return pushArchiveObbs(contents.obbs, contents.manifestPackageName)
        } catch (e: Exception) {
            onLog("❌ adb install: ошибка обработки контейнера ${archiveFile.name}: ${e.message ?: e.javaClass.simpleName}")
            return false
        } finally {
            deleteRecursivelySafe(workDir)
        }
    }

    private fun extractPackageArchiveContents(archiveFile: File, workDir: File): PackageArchiveContents {
        val apks = mutableListOf<ExtractedArchiveApk>()
        val obbs = mutableListOf<ExtractedArchiveObb>()
        var manifestPackageName: String? = null

        ZipFile(archiveFile).use { zip ->
            val entries = zip.entries()
            var apkIndex = 0
            var obbIndex = 0
            while (entries.hasMoreElements()) {
                if (cancelled) break
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val normalizedName = entry.name.replace('\\', '/')
                val lowerName = normalizedName.lowercase()
                if (!isSafeArchiveEntryName(normalizedName)) {
                    onLog("⚠️ Пропущен небезопасный путь в архиве: $normalizedName")
                    continue
                }

                if (lowerName == "manifest.json" || lowerName.endsWith("/manifest.json")) {
                    zip.getInputStream(entry).use { input ->
                        val text = input.readBytesLimited(2 * 1024 * 1024).toString(Charsets.UTF_8)
                        manifestPackageName = extractPackageNameFromManifestText(text) ?: manifestPackageName
                    }
                    continue
                }

                when {
                    lowerName.endsWith(".apk") -> {
                        val baseName = normalizedName.substringAfterLast('/').ifBlank { "entry-$apkIndex.apk" }
                        val outputName = "${apkIndex.toString().padStart(3, '0')}-${baseName.replace(Regex("[^A-Za-z0-9._-]"), "_")}"
                        val outFile = File(workDir, outputName)
                        zip.getInputStream(entry).use { input ->
                            outFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        apks.add(ExtractedArchiveApk(outFile, normalizedName))
                        apkIndex++
                    }
                    lowerName.endsWith(".obb") -> {
                        val baseName = normalizedName.substringAfterLast('/').ifBlank { "entry-$obbIndex.obb" }
                        val outputName = "obb-${obbIndex.toString().padStart(3, '0')}-${baseName.replace(Regex("[^A-Za-z0-9._-]"), "_")}"
                        val outFile = File(workDir, outputName)
                        zip.getInputStream(entry).use { input ->
                            outFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        obbs.add(ExtractedArchiveObb(outFile, normalizedName, extractObbPackageNameFromPath(normalizedName)))
                        obbIndex++
                    }
                }
            }
        }
        onLog("ℹ️ Найдено APK в контейнере: ${apks.size}")
        manifestPackageName?.let { onLog("ℹ️ package_name из manifest.json: $it") }
        return PackageArchiveContents(apks, obbs, manifestPackageName)
    }

    private fun pushArchiveObbs(obbs: List<ExtractedArchiveObb>, manifestPackageName: String?): Boolean {
        if (obbs.isEmpty()) return true
        var ok = true
        obbs.forEachIndexed { index, obb ->
            if (cancelled) return false
            val packageName = obb.packageNameFromPath ?: manifestPackageName
            if (packageName.isNullOrBlank()) {
                onLog("⚠️ OBB ${obb.entryName}: пакет не определён, файл не отправлен. Распакуйте XAPK и выполните adb push вручную.")
                ok = false
                return@forEachIndexed
            }
            val remoteDir = "/sdcard/Android/obb/$packageName"
            val remoteName = obb.entryName.substringAfterLast('/').ifBlank { obb.file.name.removePrefix("obb-") }
            val remotePath = "$remoteDir/$remoteName"
            onLog("ℹ️ OBB ${index + 1}/${obbs.size}: ${obb.entryName} → $remotePath")
            val mkdirResult = runShellCommandForResult("mkdir -p ${shellQuote(remoteDir)}", logOutput = false)
            if (!mkdirResult.success) {
                onLog("❌ Не удалось создать папку OBB: $remoteDir")
                ok = false
                return@forEachIndexed
            }
            if (!pushFile(obb.file, remotePath, 0x1A4)) {
                ok = false
                return@forEachIndexed
            }
        }
        if (ok) onLog("✅ OBB-файлы отправлены")
        return ok
    }

    private fun extractObbPackageNameFromPath(entryName: String): String? {
        val parts = entryName.replace('\\', '/').split('/').filter { it.isNotBlank() }
        for (i in 0 until parts.size - 2) {
            if (parts[i].equals("Android", ignoreCase = true) && parts[i + 1].equals("obb", ignoreCase = true)) {
                val candidate = parts[i + 2]
                if (isValidPackageName(candidate)) return candidate
            }
        }
        return null
    }

    private fun extractPackageNameFromManifestText(text: String): String? {
        val patterns = listOf(
            Regex("\"package_name\"\\s*:\\s*\"([^\"]+)\""),
            Regex("\"packageName\"\\s*:\\s*\"([^\"]+)\""),
            Regex("\"package\"\\s*:\\s*\"([^\"]+)\"")
        )
        patterns.forEach { regex ->
            val candidate = regex.find(text)?.groupValues?.getOrNull(1)
            if (!candidate.isNullOrBlank() && isValidPackageName(candidate)) return candidate
        }
        return null
    }

    private fun isValidPackageName(value: String): Boolean =
        Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$").matches(value)

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val buffer = ByteArray(8192)
        val out = java.io.ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            total += read
            if (total > limit) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun selectArchiveApksForInstall(apks: List<ExtractedArchiveApk>): List<ExtractedArchiveApk> {
        val universal = apks.firstOrNull { it.entryName.substringAfterLast('/').equals("universal.apk", ignoreCase = true) }
        if (universal != null) {
            onLog("ℹ️ Найден universal.apk — используем одиночную установку вместо split-набора")
            return listOf(universal)
        }

        val nonStandalone = apks.filterNot {
            val path = it.entryName.replace('\\', '/').lowercase()
            path.startsWith("standalones/") || path.contains("/standalones/")
        }
        val splitLike = nonStandalone.filter {
            val path = it.entryName.replace('\\', '/').lowercase()
            path.startsWith("splits/") || path.contains("/splits/") || isBaseApkLike(it) || isConfigSplitLike(it)
        }
        val splitSet = if (splitLike.any { isBaseApkLike(it) }) splitLike else emptyList()
        if (splitSet.isNotEmpty()) {
            onLog("ℹ️ Найден split-набор с base APK")
            return sortApksForInstall(splitSet)
        }

        val xapkSet = apks.filter { isBaseApkLike(it) || isConfigSplitLike(it) }
        if (xapkSet.any { isBaseApkLike(it) }) {
            onLog("ℹ️ Найден XAPK/APK-набор с base/config split")
            return sortApksForInstall(xapkSet)
        }

        val standalone = apks.filter {
            val path = it.entryName.replace('\\', '/').lowercase()
            path.startsWith("standalones/") || path.contains("/standalones/")
        }
        if (standalone.size == 1) {
            onLog("ℹ️ Найден один standalone APK")
            return standalone
        }
        if (standalone.size > 1) {
            onLog("⚠️ В контейнере несколько standalone APK. Автоматически выбран первый; для точного выбора распакуйте архив и установите нужный APK вручную.")
            return listOf(standalone.first())
        }

        if (apks.size > 1) {
            onLog("⚠️ Не удалось уверенно определить base/split структуру. Пробуем установить все APK из контейнера.")
        }
        return sortApksForInstall(apks)
    }

    private fun sortApksForInstall(apks: List<ExtractedArchiveApk>): List<ExtractedArchiveApk> =
        apks.sortedWith(
            compareBy<ExtractedArchiveApk> { archiveApkInstallRank(it) }
                .thenBy { it.entryName.lowercase() }
        )

    private fun archiveApkInstallRank(item: ExtractedArchiveApk): Int = when {
        isPrimaryBaseApkLike(item) -> 0
        isBaseApkLike(item) -> 1
        isConfigSplitLike(item) -> 2
        else -> 3
    }

    private fun isPrimaryBaseApkLike(item: ExtractedArchiveApk): Boolean {
        val name = item.entryName.substringAfterLast('/').lowercase()
        return name == "base.apk" || name == "base_master.apk" || name == "base-master.apk"
    }

    private fun isBaseApkLike(item: ExtractedArchiveApk): Boolean {
        val name = item.entryName.substringAfterLast('/').lowercase()
        return isPrimaryBaseApkLike(item) || name.startsWith("base-")
    }

    private fun isConfigSplitLike(item: ExtractedArchiveApk): Boolean {
        val name = item.entryName.substringAfterLast('/').lowercase()
        return name.startsWith("split_config.") || name.startsWith("config.") || name.contains("split_config")
    }

    private fun isSafeArchiveEntryName(name: String): Boolean {
        if (name.startsWith("/") || name.startsWith("../") || name.contains("/../")) return false
        if (name.contains('\u0000')) return false
        return true
    }

    private fun deleteRecursivelySafe(file: File) {
        try {
            if (file.exists()) file.deleteRecursively()
        } catch (_: Exception) {}
    }

    fun installMultipleApks(apkFiles: List<File>, options: List<String>): Boolean {
        if (!isConnected) return false
        cancelled = false

        val files = apkFiles.distinctBy { it.absolutePath }
        if (files.size < 2) {
            onLog("❌ adb install-multiple: нужно минимум 2 APK-файла")
            return false
        }
        files.forEach { file ->
            if (!file.exists() || !file.isFile || !file.canRead()) {
                onLog("❌ adb install-multiple: APK недоступен: ${file.absolutePath}")
                return false
            }
        }

        val installOptions = options.filter { it.isNotBlank() }
        val stamp = System.currentTimeMillis()
        val remoteFiles = files.mapIndexed { index, file ->
            val safeName = file.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            file to "/data/local/tmp/aft-session-$stamp-$index-$safeName"
        }

        onLog("-> adb install-multiple ${installOptions.joinToString(" ")} ${files.joinToString(" ") { it.name }}".trim())
        onLog("ℹ️ Split APK: ${files.size} файлов. Используется package-manager session API.")

        var sessionId: String? = null
        try {
            remoteFiles.forEach { (file, remotePath) ->
                if (!pushFile(file, remotePath, 0x1A4)) return false
            }

            val optionText = installOptions.joinToString(" ") { shellQuote(it) }
            val hasSessionSizeOption = installOptions.any { it == "-S" || it == "--size" }
            val totalSize = files.sumOf { it.length() }
            val createCommand = buildString {
                append("pm install-create")
                if (!hasSessionSizeOption) append(" -S ").append(totalSize)
                if (optionText.isNotBlank()) append(' ').append(optionText)
            }
            val createResult = runShellCommandForResult(createCommand)
            if (!createResult.success) {
                onLog("❌ install-multiple: pm install-create завершился ошибкой")
                return false
            }

            sessionId = parseInstallSessionId(createResult.combinedOutput())
            if (sessionId.isNullOrBlank()) {
                onLog("❌ install-multiple: не удалось определить session id из вывода pm install-create")
                return false
            }
            onLog("ℹ️ install session: $sessionId")

            remoteFiles.forEachIndexed { index, (file, remotePath) ->
                if (cancelled) return false
                val splitName = buildSplitName(index, file)
                val writeCommand = "pm install-write -S ${file.length()} $sessionId ${shellQuote(splitName)} ${shellQuote(remotePath)}"
                onLog("ℹ️ install-write ${index + 1}/${remoteFiles.size}: $splitName")
                val writeResult = runShellCommandForResult(writeCommand)
                if (!writeResult.success) {
                    onLog("❌ install-multiple: ошибка install-write для ${file.name}")
                    abandonInstallSession(sessionId)
                    return false
                }
            }

            val commitResult = runShellCommandForResult("pm install-commit $sessionId")
            return if (commitResult.success) {
                onLog("✅ adb install-multiple завершён")
                true
            } else {
                onLog("❌ install-multiple: pm install-commit завершился ошибкой")
                abandonInstallSession(sessionId)
                false
            }
        } catch (e: Exception) {
            onLog("❌ adb install-multiple ошибка: ${e.message ?: e.javaClass.simpleName}")
            sessionId?.let { abandonInstallSession(it) }
            return false
        } finally {
            val cleanup = remoteFiles.joinToString(" ") { (_, remotePath) -> shellQuote(remotePath) }
            if (cleanup.isNotBlank() && isConnected && !cancelled) {
                runShellCommandForResult("rm -f $cleanup", logOutput = false)
            }
        }
    }

    private fun abandonInstallSession(sessionId: String) {
        if (sessionId.isBlank()) return
        onLog("ℹ️ Отменяем install session $sessionId")
        runShellCommandForResult("pm install-abandon $sessionId", logOutput = false)
    }

    private fun parseInstallSessionId(output: String): String? {
        val bracketMatch = Regex("\\[(\\d+)]").find(output)
        if (bracketMatch != null) return bracketMatch.groupValues[1]
        return Regex("(?i)session\\s+(\\d+)").find(output)?.groupValues?.getOrNull(1)
    }

    private fun buildSplitName(index: Int, file: File): String {
        val clean = file.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val withoutExtractPrefix = clean.replace(Regex("^\\d{3}-"), "")
        val lower = withoutExtractPrefix.lowercase()
        return if (index == 0 && (lower.startsWith("base") || lower == "base_master.apk" || lower == "base-master.apk")) {
            "base.apk"
        } else {
            withoutExtractPrefix.ifBlank { clean }
        }
    }

    fun runShellCommand(command: String, forceLegacy: Boolean = false): Boolean {
        if (!isConnected) return false
        cancelled = false

        val cleanCommand = command.trim()
        if (cleanCommand.isBlank()) {
            return startInteractiveShell()
        }

        return runShellCommandForResult(cleanCommand, logOutput = true, forceLegacy = forceLegacy).success
    }

    fun sendInteractiveShellInput(line: String): Boolean {
        val payload = (line + "\n").toByteArray(Charsets.UTF_8)
        val accepted = queueInteractiveShellBytes(payload)
        if (accepted) onLog("adb-shell$ ${line.trimEnd()}")
        return accepted
    }

    fun sendInteractiveShellInterrupt(): Boolean {
        val accepted = queueInteractiveShellBytes(byteArrayOf(0x03))
        if (accepted) onLog("adb-shell: SIGINT / Ctrl+C")
        return accepted
    }

    fun sendInteractiveShellEof(): Boolean {
        val accepted = queueInteractiveShellBytes(byteArrayOf(0x04))
        if (accepted) onLog("adb-shell: EOF / Ctrl+D")
        return accepted
    }

    private fun queueInteractiveShellBytes(payload: ByteArray): Boolean {
        return synchronized(interactiveShellLock) {
            val session = interactiveShellSession ?: return false
            session.stdinQueue.add(payload)
            true
        }
    }

    fun stopInteractiveShell(): Boolean {
        synchronized(interactiveShellLock) {
            val session = interactiveShellSession ?: return false
            session.stopRequested = true
            session.stdinQueue.add("exit\n".toByteArray(Charsets.UTF_8))
        }
        onLog("⏹ Запрошено закрытие интерактивного adb shell")
        return true
    }

    private fun startInteractiveShell(): Boolean {
        if (!isConnected) return false
        cancelled = false

        synchronized(interactiveShellLock) {
            if (interactiveShellSession != null) {
                onLog("ℹ️ Интерактивный adb shell уже открыт. Вводите команды без префикса adb shell.")
                return true
            }
            interactiveShellSession = InteractiveShellSession()
        }

        val useShellV2 = supportsShellV2
        val service = if (useShellV2) "shell,v2,pty:" else "shell:"
        onLog("=== ADB INTERACTIVE SHELL START ===")
        onLog("-> adb open: $service")
        onLog("ℹ️ Вводите команды в нижнюю строку. Для выхода: exit, adb shell-stop или кнопка Стоп.")
        onLog("ℹ️ Прерывание процесса: :ctrl-c, :interrupt или adb shell-ctrl-c. EOF: :ctrl-d.")

        val stream = openAdbStream(service, logOpen = false) ?: run {
            clearInteractiveShellSession()
            return false
        }

        return try {
            if (useShellV2) runInteractiveShellV2(stream) else runInteractiveLegacyShell(stream)
        } catch (e: Exception) {
            onLog("❌ interactive adb shell ошибка: ${e.message ?: e.javaClass.simpleName}")
            false
        } finally {
            closeAdbStream(stream)
            clearInteractiveShellSession()
            onLog("=== ADB INTERACTIVE SHELL CLOSED ===")
        }
    }

    private fun runInteractiveShellV2(stream: AdbStream): Boolean {
        var exitCode: Int? = null
        while (!cancelled && !stream.closed) {
            drainInteractiveShellInputV2(stream)
            consumeInteractiveShellV2Packets(stream) { code -> exitCode = code }
            if (exitCode != null) break

            val header = readHeader(timeoutMs = 250)
            if (header == null) continue

            when (header.command) {
                A_WRTE -> {
                    val data = readData(header.dataLength) ?: return false
                    sendMessageInternal(A_OKAY, stream.localId, header.arg0, ByteArray(0))
                    if (data.isNotEmpty()) stream.pending.add(data)
                    consumeInteractiveShellV2Packets(stream) { code -> exitCode = code }
                    if (exitCode != null) break
                }
                A_OKAY -> {
                    if (header.dataLength > 0) readData(header.dataLength)
                    stream.remoteId = header.arg0
                }
                A_CLSE -> {
                    if (header.dataLength > 0) readData(header.dataLength)
                    stream.closed = true
                    break
                }
                else -> {
                    if (header.dataLength > 0) readData(header.dataLength)
                    onLog("⚠️ interactive shell/v2: неожиданный ADB packet cmd=0x${header.command.toString(16)}")
                }
            }
        }

        exitCode?.let { onLog("=== ADB INTERACTIVE SHELL EXIT: $it ===") }
        return !cancelled
    }

    private fun drainInteractiveShellInputV2(stream: AdbStream) {
        while (!cancelled && !stream.closed) {
            val payload = pollInteractiveShellInput() ?: break
            if (!writeShellPacket(stream, SHELL_ID_STDIN, payload)) {
                stream.closed = true
                break
            }
            consumeInteractiveShellV2Packets(stream) { code ->
                onLog("=== ADB INTERACTIVE SHELL EXIT: $code ===")
                stream.closed = true
            }
        }
        if (shouldCloseInteractiveShell() && !isInteractiveShellCloseStdinSent()) {
            if (writeShellPacket(stream, SHELL_ID_CLOSE_STDIN, ByteArray(0))) {
                markInteractiveShellCloseStdinSent()
            }
        }
    }

    private fun consumeInteractiveShellV2Packets(stream: AdbStream, onExit: (Int) -> Unit) {
        while (pendingByteCount(stream) >= SHELL_PACKET_HEADER) {
            val headerRaw = peekPendingExact(stream, SHELL_PACKET_HEADER) ?: return
            val id = headerRaw[0].toInt() and 0xFF
            val length = ByteBuffer.wrap(headerRaw, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (length < 0 || length > MAX_PAYLOAD) {
                onLog("❌ interactive shell_v2: некорректная длина packet=$length")
                stream.closed = true
                return
            }
            if (pendingByteCount(stream) < SHELL_PACKET_HEADER + length) return
            readPendingExact(stream, SHELL_PACKET_HEADER)
            val payload = readPendingExact(stream, length) ?: ByteArray(0)

            when (id) {
                SHELL_ID_STDOUT -> logShellOutput(payload, isStderr = false)
                SHELL_ID_STDERR -> logShellOutput(payload, isStderr = true)
                SHELL_ID_EXIT -> {
                    val code = payload.firstOrNull()?.toInt()?.and(0xFF) ?: 0
                    onExit(code)
                    stream.closed = true
                    return
                }
                SHELL_ID_STDIN, SHELL_ID_CLOSE_STDIN -> Unit
                else -> onLog("⚠️ interactive shell_v2: неизвестный packet id=$id, length=$length")
            }
        }
    }

    private fun runInteractiveLegacyShell(stream: AdbStream): Boolean {
        onLog("ℹ️ legacy shell: stdout/stderr и exit-code не разделяются")
        while (!cancelled && !stream.closed) {
            drainInteractiveLegacyOutput(stream)
            drainInteractiveLegacyInput(stream)
            drainInteractiveLegacyOutput(stream)

            val header = readHeader(timeoutMs = 250)
            if (header == null) continue

            when (header.command) {
                A_WRTE -> {
                    stream.remoteId = header.arg0
                    val data = readData(header.dataLength) ?: return false
                    sendMessageInternal(A_OKAY, stream.localId, stream.remoteId, ByteArray(0))
                    logServiceOutput(data)
                }
                A_OKAY -> {
                    if (header.dataLength > 0) readData(header.dataLength)
                    stream.remoteId = header.arg0
                }
                A_CLSE -> {
                    if (header.dataLength > 0) readData(header.dataLength)
                    stream.closed = true
                    break
                }
                else -> {
                    if (header.dataLength > 0) readData(header.dataLength)
                    onLog("⚠️ interactive legacy shell: неожиданный ADB packet cmd=0x${header.command.toString(16)}")
                }
            }
        }
        return !cancelled
    }

    private fun drainInteractiveLegacyInput(stream: AdbStream) {
        while (!cancelled && !stream.closed) {
            val payload = pollInteractiveShellInput() ?: break
            if (!writeAdbStream(stream, payload)) {
                stream.closed = true
                break
            }
        }
    }

    private fun drainInteractiveLegacyOutput(stream: AdbStream) {
        while (stream.pending.isNotEmpty()) {
            val remaining = pendingByteCount(stream)
            val data = readPendingExact(stream, remaining) ?: return
            logServiceOutput(data)
        }
    }

    private fun pollInteractiveShellInput(): ByteArray? = synchronized(interactiveShellLock) {
        val session = interactiveShellSession ?: return@synchronized null
        if (session.stdinQueue.isEmpty()) null else session.stdinQueue.removeFirst()
    }

    private fun shouldCloseInteractiveShell(): Boolean = synchronized(interactiveShellLock) {
        interactiveShellSession?.stopRequested == true
    }

    private fun isInteractiveShellCloseStdinSent(): Boolean = synchronized(interactiveShellLock) {
        interactiveShellSession?.closeStdinSent == true
    }

    private fun markInteractiveShellCloseStdinSent() {
        synchronized(interactiveShellLock) { interactiveShellSession?.closeStdinSent = true }
    }

    private fun clearInteractiveShellSession() {
        synchronized(interactiveShellLock) { interactiveShellSession = null }
    }

    private data class ShellResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int?,
        val success: Boolean
    ) {
        fun combinedOutput(): String = listOf(stdout, stderr).filter { it.isNotBlank() }.joinToString("\n")
    }

    private fun runShellCommandForResult(
        command: String,
        logOutput: Boolean = true,
        forceLegacy: Boolean = false
    ): ShellResult {
        val cleanCommand = command.trim()
        return if (!forceLegacy && supportsShellV2) {
            runShellV2ForResult(cleanCommand, logOutput)
        } else {
            if (logOutput) onLog("ℹ️ adb shell legacy: exit-code недоступен на этом устройстве/режиме")
            runLegacyShellForResult(cleanCommand, logOutput)
        }
    }

    private fun runShellV2ForResult(command: String, logOutput: Boolean): ShellResult {
        val service = "shell,v2,raw:$command"
        if (logOutput) onLog("-> adb shell/v2: $command")
        val stream = openAdbStream(service, logOpen = false) ?: run {
            if (logOutput) onLog("⚠️ shell_v2 открыть не удалось, пробуем legacy shell")
            return runShellCommandForResult(command, logOutput = logOutput, forceLegacy = true)
        }

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        var exitCode: Int? = null
        try {
            // Непосредственно как в shell protocol: закрываем stdin для one-shot команд.
            if (!writeShellPacket(stream, SHELL_ID_CLOSE_STDIN, ByteArray(0))) {
                return ShellResult(stdout.toString(), stderr.toString(), exitCode, false)
            }

            while (!cancelled && !stream.closed) {
                val header = readShellPacketHeader(stream) ?: break
                when (header.id) {
                    SHELL_ID_STDOUT -> {
                        val data = readAdbStreamExact(stream, header.length)
                            ?: return ShellResult(stdout.toString(), stderr.toString(), exitCode, false)
                        val text = data.toShellText()
                        stdout.append(text)
                        if (logOutput) logShellOutput(data, isStderr = false)
                    }
                    SHELL_ID_STDERR -> {
                        val data = readAdbStreamExact(stream, header.length)
                            ?: return ShellResult(stdout.toString(), stderr.toString(), exitCode, false)
                        val text = data.toShellText()
                        stderr.append(text)
                        if (logOutput) logShellOutput(data, isStderr = true)
                    }
                    SHELL_ID_EXIT -> {
                        val data = readAdbStreamExact(stream, header.length) ?: ByteArray(0)
                        exitCode = data.firstOrNull()?.toInt()?.and(0xFF) ?: 0
                        if (logOutput) onLog("=== ADB SHELL EXIT: $exitCode ===")
                    }
                    SHELL_ID_STDIN, SHELL_ID_CLOSE_STDIN -> {
                        if (header.length > 0 && readAdbStreamExact(stream, header.length) == null) {
                            return ShellResult(stdout.toString(), stderr.toString(), exitCode, false)
                        }
                    }
                    else -> {
                        if (header.length > 0 && readAdbStreamExact(stream, header.length) == null) {
                            return ShellResult(stdout.toString(), stderr.toString(), exitCode, false)
                        }
                        if (logOutput) onLog("⚠️ shell_v2: неизвестный packet id=${header.id}, length=${header.length}")
                    }
                }
            }
        } catch (e: Exception) {
            if (logOutput) onLog("❌ adb shell/v2 ошибка: ${e.message ?: e.javaClass.simpleName}")
            return ShellResult(stdout.toString(), stderr.toString(), exitCode, false)
        } finally {
            closeAdbStream(stream)
        }

        if (exitCode == null && logOutput) onLog("⚠️ shell_v2 завершился без exit packet")
        return ShellResult(stdout.toString(), stderr.toString(), exitCode, exitCode == 0)
    }

    private fun runLegacyShellForResult(command: String, logOutput: Boolean): ShellResult {
        if (logOutput) onLog("-> adb shell: $command")
        val stream = openAdbStream("shell:$command", logOpen = false)
            ?: return ShellResult("", "", null, false)
        val stdout = StringBuilder()
        try {
            while (!cancelled && !stream.closed) {
                val header = readHeader(timeoutMs = 30_000) ?: break
                when (header.command) {
                    A_WRTE -> {
                        stream.remoteId = header.arg0
                        val data = readData(header.dataLength) ?: return ShellResult(stdout.toString(), "", null, false)
                        sendMessageInternal(A_OKAY, stream.localId, stream.remoteId, ByteArray(0))
                        val text = data.toShellText()
                        stdout.append(text)
                        if (logOutput) logShellOutput(data, isStderr = false)
                    }
                    A_OKAY -> {
                        if (header.dataLength > 0) readData(header.dataLength)
                        stream.remoteId = header.arg0
                    }
                    A_CLSE -> {
                        if (header.dataLength > 0) readData(header.dataLength)
                        stream.closed = true
                        return ShellResult(stdout.toString(), "", null, true)
                    }
                    else -> {
                        if (header.dataLength > 0) readData(header.dataLength)
                        if (logOutput) onLog("⚠️ legacy shell: неожиданный packet cmd=0x${header.command.toString(16)}")
                    }
                }
            }
        } catch (e: Exception) {
            if (logOutput) onLog("❌ adb shell legacy ошибка: ${e.message ?: e.javaClass.simpleName}")
            return ShellResult(stdout.toString(), "", null, false)
        } finally {
            closeAdbStream(stream)
        }
        return ShellResult(stdout.toString(), "", null, !cancelled)
    }

    fun runSelfTest(): Boolean {
        if (!isConnected) {
            onLog("❌ ADB self-test: соединение не открыто")
            return false
        }
        cancelled = false
        onLog("=== ADB SELF-TEST ===")
        if (remoteBanner.isBlank()) {
            onLog("⚠️ ADB banner пустой или не был прочитан")
        } else {
            onLog("✅ ADB banner: $remoteBanner")
        }
        onLog("ADB features: ${if (deviceFeatures.isEmpty()) "—" else deviceFeatures.joinToString(",")}")
        onLog(if (supportsShellV2) "✅ shell_v2 поддерживается" else "⚠️ shell_v2 не заявлен: exit-code будет недоступен в legacy shell")
        onLog("ADB public key: ${adbKeyStore.publicKeyPath()}")

        var ok = true
        fun check(label: String, command: String, required: Boolean = true): ShellResult {
            onLog("--- $label ---")
            val result = runShellCommandForResult(command, logOutput = false)
            val out = result.combinedOutput().trim()
            if (out.isNotBlank()) {
                out.lines().take(12).forEach { onLog("│ $it") }
                if (out.lines().size > 12) onLog("│ ...")
            }
            val good = result.success || (!supportsShellV2 && result.combinedOutput().isNotBlank())
            if (good) {
                onLog("✅ $label: OK${result.exitCode?.let { " (exit=$it)" } ?: ""}")
            } else {
                if (required) ok = false
                onLog("⚠️ $label: FAIL${result.exitCode?.let { " (exit=$it)" } ?: ""}")
            }
            return result
        }

        check("shell basic", "echo AFT_SHELL_OK")
        check("device props", "getprop ro.product.device; getprop ro.build.version.release; getprop ro.build.version.sdk", required = false)
        check("identity", "id", required = false)
        check("usb state", "getprop sys.usb.state", required = false)
        val pm = check("package manager", "pm path android", required = false)
        if (!pm.success) onLog("ℹ️ В recovery package manager может быть недоступен — это нормально для sideload/recovery режима.")

        if (supportsShellV2) {
            val exitProbe = runShellCommandForResult("sh -c 'exit 7'", logOutput = false)
            if (exitProbe.exitCode == 7) {
                onLog("✅ shell_v2 exit-code probe: OK (exit=7)")
            } else {
                ok = false
                onLog("⚠️ shell_v2 exit-code probe: ожидался 7, получено ${exitProbe.exitCode ?: "нет exit packet"}")
            }
        }

        val sdcard = statRemotePath("/sdcard", logMissing = false)
        when {
            sdcard?.exists == true -> onLog("✅ sync STAT /sdcard: mode=${sdcard.mode} size=${sdcard.size}")
            else -> onLog("⚠️ sync STAT /sdcard недоступен; pull/push в пользовательскую память могут не работать в этом режиме")
        }
        val tmp = statRemotePath("/data/local/tmp", logMissing = false)
        when {
            tmp?.exists == true -> onLog("✅ sync STAT /data/local/tmp: mode=${tmp.mode} size=${tmp.size}")
            else -> onLog("⚠️ sync STAT /data/local/tmp недоступен; adb install через staging может не работать")
        }

        onLog("ℹ️ Self-test не выполнял install, push с записью, pull больших файлов, root/remount или reboot.")
        return ok
    }

    private class InteractiveShellSession {
        val stdinQueue: ArrayDeque<ByteArray> = ArrayDeque()
        var stopRequested: Boolean = false
        var closeStdinSent: Boolean = false
    }

    private data class AdbStream(
        val localId: Int,
        var remoteId: Int,
        val pending: ArrayDeque<ByteArray> = ArrayDeque(),
        var pendingOffset: Int = 0,
        var closed: Boolean = false
    )

    private data class ShellPacketHeader(val id: Int, val length: Int)

    private data class SyncHeader(val id: String, val value: Int)

    private data class SyncStat(
        val mode: Int,
        val size: Long,
        val mtime: Int,
        val isDirectory: Boolean,
        val isRegularFile: Boolean
    ) {
        val exists: Boolean get() = mode != 0
    }

    private data class ExtractedArchiveApk(
        val file: File,
        val entryName: String
    )

    private data class ExtractedArchiveObb(
        val file: File,
        val entryName: String,
        val packageNameFromPath: String?
    )

    private data class PackageArchiveContents(
        val apks: List<ExtractedArchiveApk>,
        val obbs: List<ExtractedArchiveObb>,
        val manifestPackageName: String?
    )

    private fun openAdbStream(service: String, logOpen: Boolean = true): AdbStream? {
        val localId = nextLocalId++
        if (logOpen) onLog("-> adb open: $service")
        sendMessageInternal(A_OPEN, localId, 0, "$service\u0000".toByteArray(Charsets.UTF_8))

        while (!cancelled) {
            val header = readHeader(timeoutMs = 10_000) ?: run {
                onLog("❌ ADB stream не ответил: $service")
                return null
            }
            when (header.command) {
                A_OKAY -> {
                    if (header.dataLength > 0) readData(header.dataLength)
                    return AdbStream(localId, header.arg0)
                }
                A_CLSE -> {
                    if (header.dataLength > 0) readData(header.dataLength)
                    onLog("❌ ADB stream закрыт устройством: $service")
                    return null
                }
                A_WRTE -> {
                    val data = readData(header.dataLength)
                    sendMessageInternal(A_OKAY, localId, header.arg0, ByteArray(0))
                    if (data != null && data.isNotEmpty()) logServiceOutput(data)
                }
                else -> {
                    if (header.dataLength > 0) readData(header.dataLength)
                    onLog("⚠️ Неожиданный ADB packet при open: cmd=0x${header.command.toString(16)}")
                }
            }
        }
        return null
    }

    private fun closeAdbStream(stream: AdbStream) {
        if (stream.closed) return
        try { sendMessageInternal(A_CLSE, stream.localId, stream.remoteId, ByteArray(0)) } catch (_: Exception) {}
        stream.closed = true
    }

    private fun writeAdbStream(stream: AdbStream, payload: ByteArray): Boolean {
        if (stream.closed) return false
        sendMessageInternal(A_WRTE, stream.localId, stream.remoteId, payload)
        while (!cancelled) {
            val header = readHeader(timeoutMs = 10_000) ?: run {
                onLog("❌ ADB stream: нет ACK на WRTE")
                return false
            }
            when (header.command) {
                A_OKAY -> {
                    if (header.dataLength > 0) readData(header.dataLength)
                    stream.remoteId = header.arg0
                    return true
                }
                A_WRTE -> {
                    val data = readData(header.dataLength)
                    sendMessageInternal(A_OKAY, stream.localId, header.arg0, ByteArray(0))
                    if (data != null && data.isNotEmpty()) stream.pending.add(data)
                }
                A_CLSE -> {
                    if (header.dataLength > 0) readData(header.dataLength)
                    stream.closed = true
                    onLog("❌ ADB stream закрыт во время записи")
                    return false
                }
                else -> {
                    if (header.dataLength > 0) readData(header.dataLength)
                    onLog("⚠️ ADB stream: неожиданный packet cmd=0x${header.command.toString(16)}")
                }
            }
        }
        return false
    }

    private fun readAdbStreamExact(stream: AdbStream, length: Int): ByteArray? {
        if (length < 0 || length > MAX_PAYLOAD * 16) return null
        val out = ByteArray(length)
        var written = 0

        while (written < length) {
            while (stream.pending.isNotEmpty() && written < length) {
                val first = stream.pending.first()
                val available = first.size - stream.pendingOffset
                val copy = minOf(available, length - written)
                System.arraycopy(first, stream.pendingOffset, out, written, copy)
                stream.pendingOffset += copy
                written += copy
                if (stream.pendingOffset >= first.size) {
                    stream.pending.removeFirst()
                    stream.pendingOffset = 0
                }
            }
            if (written >= length) break
            if (stream.closed || cancelled) return null

            val header = readHeader(timeoutMs = 30_000) ?: run {
                onLog("❌ ADB stream: таймаут чтения данных")
                return null
            }
            when (header.command) {
                A_WRTE -> {
                    val data = readData(header.dataLength) ?: return null
                    sendMessageInternal(A_OKAY, stream.localId, header.arg0, ByteArray(0))
                    if (data.isNotEmpty()) stream.pending.add(data)
                }
                A_OKAY -> {
                    if (header.dataLength > 0) readData(header.dataLength)
                    stream.remoteId = header.arg0
                }
                A_CLSE -> {
                    if (header.dataLength > 0) readData(header.dataLength)
                    stream.closed = true
                    return null
                }
                else -> {
                    if (header.dataLength > 0) readData(header.dataLength)
                    onLog("⚠️ ADB stream: неожиданный packet cmd=0x${header.command.toString(16)}")
                }
            }
        }
        return out
    }

    private fun pendingByteCount(stream: AdbStream): Int {
        var total = 0
        stream.pending.forEachIndexed { index, bytes ->
            total += if (index == 0) bytes.size - stream.pendingOffset else bytes.size
        }
        return total.coerceAtLeast(0)
    }

    private fun readPendingExact(stream: AdbStream, length: Int): ByteArray? {
        if (length < 0 || pendingByteCount(stream) < length) return null
        val out = ByteArray(length)
        var written = 0
        while (written < length) {
            val first = stream.pending.firstOrNull() ?: return null
            val available = first.size - stream.pendingOffset
            val copy = minOf(available, length - written)
            System.arraycopy(first, stream.pendingOffset, out, written, copy)
            stream.pendingOffset += copy
            written += copy
            if (stream.pendingOffset >= first.size) {
                stream.pending.removeFirst()
                stream.pendingOffset = 0
            }
        }
        return out
    }

    private fun peekPendingExact(stream: AdbStream, length: Int): ByteArray? {
        if (length < 0 || pendingByteCount(stream) < length) return null
        val out = ByteArray(length)
        var written = 0
        var first = true
        for (bytes in stream.pending) {
            val start = if (first) stream.pendingOffset else 0
            first = false
            if (start >= bytes.size) continue
            val available = bytes.size - start
            val copy = minOf(available, length - written)
            System.arraycopy(bytes, start, out, written, copy)
            written += copy
            if (written >= length) break
        }
        return if (written == length) out else null
    }

    private fun writeShellPacket(stream: AdbStream, id: Int, payload: ByteArray): Boolean {
        val packet = ByteBuffer.allocate(SHELL_PACKET_HEADER + payload.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(id.toByte())
            putInt(payload.size)
            put(payload)
        }.array()
        return writeAdbStream(stream, packet)
    }

    private fun readShellPacketHeader(stream: AdbStream): ShellPacketHeader? {
        val raw = readAdbStreamExact(stream, SHELL_PACKET_HEADER) ?: return null
        val id = raw[0].toInt() and 0xFF
        val length = ByteBuffer.wrap(raw, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (length < 0 || length > MAX_PAYLOAD) {
            onLog("❌ shell_v2: некорректная длина packet=$length")
            return null
        }
        return ShellPacketHeader(id, length)
    }

    private fun ByteArray.toShellText(): String = String(this, Charsets.UTF_8).replace("\u0000", "")

    private fun logShellOutput(data: ByteArray, isStderr: Boolean) {
        if (data.isEmpty()) return
        val text = data.toShellText()
        if (text.isBlank()) return
        val prefix = if (isStderr) "│ stderr: " else "│ "
        text.split('\n').forEach { rawLine ->
            val line = rawLine.trimEnd('\r')
            if (line.isNotEmpty()) onLog(prefix + line)
        }
    }

    private fun writeSyncRequest(stream: AdbStream, id: String, payload: ByteArray): Boolean {
        val packet = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(id.toByteArray(Charsets.US_ASCII))
            putInt(payload.size)
            put(payload)
        }.array()
        return writeAdbStream(stream, packet)
    }

    private fun writeSyncIdAndInt(stream: AdbStream, id: String, value: Int): Boolean {
        val packet = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(id.toByteArray(Charsets.US_ASCII))
            putInt(value)
        }.array()
        return writeAdbStream(stream, packet)
    }

    private fun writeSyncData(stream: AdbStream, id: String, data: ByteArray, length: Int): Boolean {
        val packet = ByteBuffer.allocate(8 + length).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(id.toByteArray(Charsets.US_ASCII))
            putInt(length)
            put(data, 0, length)
        }.array()
        return writeAdbStream(stream, packet)
    }

    private fun readSyncHeader(stream: AdbStream): SyncHeader? {
        val raw = readAdbStreamExact(stream, 8) ?: return null
        val id = String(raw, 0, 4, Charsets.US_ASCII)
        val value = ByteBuffer.wrap(raw, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        return SyncHeader(id, value)
    }

    private fun readSyncStatus(stream: AdbStream, opName: String): Boolean {
        val header = readSyncHeader(stream) ?: return false
        return when (header.id) {
            "OKAY" -> {
                if (header.value > 0) readSyncString(stream, header.value)
                true
            }
            "FAIL" -> {
                val message = readSyncString(stream, header.value)
                onLog("❌ $opName FAIL: $message")
                false
            }
            else -> {
                onLog("❌ $opName: неожиданный sync id=${header.id}")
                false
            }
        }
    }

    private fun statRemotePath(remotePath: String, logMissing: Boolean = true): SyncStat? {
        val cleanRemote = remotePath.trim()
        if (!isSafeRemotePath(cleanRemote)) return null
        val stream = openAdbStream("sync:", logOpen = false) ?: return null
        try {
            if (!writeSyncRequest(stream, "STAT", cleanRemote.toByteArray(Charsets.UTF_8))) return null
            val stat = readSyncStatResponse(stream, "adb stat") ?: return null
            if (!stat.exists && logMissing) {
                onLog("❌ adb stat: remote path не найден: $cleanRemote")
            } else if (stat.exists && logMissing) {
                val kind = when {
                    stat.isDirectory -> "каталог"
                    stat.isRegularFile -> "файл"
                    else -> "объект"
                }
                onLog("ℹ️ adb stat: $kind, size=${stat.size}, mode=0${stat.mode.toString(8)}")
            }
            return stat
        } catch (e: Exception) {
            if (logMissing) onLog("❌ adb stat ошибка: ${e.message ?: e.javaClass.simpleName}")
            return null
        } finally {
            closeAdbStream(stream)
        }
    }

    private fun readSyncStatResponse(stream: AdbStream, opName: String): SyncStat? {
        val idRaw = readAdbStreamExact(stream, 4) ?: return null
        val id = String(idRaw, Charsets.US_ASCII)
        return when (id) {
            "STAT" -> {
                val body = readAdbStreamExact(stream, 12) ?: return null
                val bb = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
                val mode = bb.int
                val size = bb.int.toLong() and 0xFFFFFFFFL
                val mtime = bb.int
                val exists = mode != 0
                val type = mode and SYNC_MODE_IFMT
                SyncStat(
                    mode = mode,
                    size = size,
                    mtime = mtime,
                    isDirectory = exists && type == SYNC_MODE_IFDIR,
                    isRegularFile = exists && type == SYNC_MODE_IFREG
                )
            }
            "FAIL" -> {
                val lenRaw = readAdbStreamExact(stream, 4) ?: return null
                val length = ByteBuffer.wrap(lenRaw).order(ByteOrder.LITTLE_ENDIAN).int
                val message = readSyncString(stream, length)
                onLog("❌ $opName FAIL: $message")
                null
            }
            else -> {
                onLog("❌ $opName: неожиданный sync id=$id")
                null
            }
        }
    }

    private fun ensureRemoteDirectory(remoteDir: String): Boolean {
        if (!isSafeRemotePath(remoteDir)) return false
        val result = runShellCommandForResult("mkdir -p ${shellQuote(remoteDir)}", logOutput = false)
        if (!result.success) {
            onLog("❌ Не удалось создать remote-папку: $remoteDir")
            return false
        }
        return true
    }

    private fun parseFindSections(output: String): Pair<List<String>, List<String>> {
        val dirs = mutableListOf<String>()
        val files = mutableListOf<String>()
        var section = ""
        output.lines().forEach { raw ->
            val line = raw.trimEnd('\r')
            when (line) {
                "AFT_DIRS_BEGIN" -> section = "dirs"
                "AFT_FILES_BEGIN" -> section = "files"
                else -> {
                    if (line.isBlank()) return@forEach
                    when (section) {
                        "dirs" -> dirs.add(line)
                        "files" -> files.add(line)
                    }
                }
            }
        }
        return dirs.distinct() to files.distinct()
    }

    private fun normalizeRelativeRemotePath(path: String): String {
        return path.replace('\\', '/')
            .removePrefix("./")
            .trim('/')
    }

    private fun joinRemotePath(base: String, child: String): String {
        val left = base.trimEnd('/')
        val right = child.replace('\\', '/').trimStart('/')
        return when {
            left.isBlank() -> "/$right"
            right.isBlank() -> left
            else -> "$left/$right"
        }
    }

    private fun readSyncString(stream: AdbStream, length: Int): String {
        if (length <= 0) return ""
        if (length > SYNC_MAX_STRING) return "message too long ($length bytes)"
        return readAdbStreamExact(stream, length)?.toString(Charsets.UTF_8).orEmpty()
    }

    private fun isSafeRemotePath(path: String): Boolean {
        return path.isNotBlank() && !path.contains('\u0000')
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }

    fun runService(service: String): Boolean {
        if (!isConnected) return false
        cancelled = false

        val normalizedService = service.trim()
        if (normalizedService.isBlank()) {
            onLog("❌ ОШИБКА: пустой ADB service")
            return false
        }

        val localId = nextLocalId++
        var remoteId = 0
        var opened = false
        var idleTimeouts = 0

        onLog("-> adb service: $normalizedService")
        try {
            sendMessageInternal(
                A_OPEN,
                localId,
                0,
                "$normalizedService\u0000".toByteArray(Charsets.UTF_8)
            )

            while (!cancelled) {
                val header = readHeader(timeoutMs = 10_000)
                if (header == null) {
                    if (!opened) {
                        onLog("❌ ОШИБКА: ADB service не ответил")
                        return false
                    }
                    idleTimeouts++
                    if (idleTimeouts >= 3) {
                        onLog("ℹ️ ADB service не присылает данные 30 сек; операция завершена по таймауту ожидания.")
                        return true
                    }
                    continue
                }
                idleTimeouts = 0

                when (header.command) {
                    A_OKAY -> {
                        remoteId = header.arg0
                        opened = true
                        if (header.dataLength > 0) readData(header.dataLength)
                    }
                    A_WRTE -> {
                        remoteId = header.arg0
                        val data = readData(header.dataLength)
                        sendMessageInternal(A_OKAY, localId, remoteId, ByteArray(0))
                        logServiceOutput(data)
                    }
                    A_CLSE -> {
                        if (header.dataLength > 0) readData(header.dataLength)
                        if (remoteId != 0) sendMessageInternal(A_CLSE, localId, remoteId, ByteArray(0))
                        onLog("=== ADB SERVICE CLOSED ===")
                        return true
                    }
                    else -> {
                        if (header.dataLength > 0) readData(header.dataLength)
                        onLog("⚠️ Неожиданный ADB packet: cmd=0x${header.command.toString(16)}")
                    }
                }
            }
        } catch (e: Exception) {
            onLog("ОШИБКА ADB service: ${e.message ?: e.javaClass.simpleName}")
            return false
        }

        onLog("⚠️ ADB service отменён пользователем")
        return false
    }

    private fun logServiceOutput(data: ByteArray?) {
        if (data == null || data.isEmpty()) return
        val text = String(data, Charsets.UTF_8).replace("\u0000", "")
        if (text.isBlank()) return
        text.split('\n').forEach { rawLine ->
            val line = rawLine.trimEnd('\r')
            if (line.isNotEmpty()) onLog("│ $line")
        }
    }

    fun cancel() {
        cancelled = true
        synchronized(interactiveShellLock) {
            interactiveShellSession?.stopRequested = true
        }
    }

    // ─── ВНУТРЕННИЕ МЕТОДЫ ───────────────────────────────────────────────────

    private data class AdbHeader(
        val command: Long,
        val arg0: Int,
        val arg1: Int,
        val dataLength: Int,
        val checksum: Int,
        val magic: Int
    )

    private fun sendMessageInternal(command: Long, arg0: Int, arg1: Int, data: ByteArray) {
        val checksum = data.sumOf { (it.toLong() and 0xFFL) }.toInt()

        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(command.toInt())
            putInt(arg0)
            putInt(arg1)
            putInt(data.size)
            putInt(checksum)
            putInt(command.inv().toInt())
        }.array()

        if (!safeBulkWrite(header)) throw Exception("Ошибка передачи заголовка ADB")

        if (data.isNotEmpty()) {
            var offset = 0
            val chunkSize = 16384
            while (offset < data.size) {
                val len = minOf(chunkSize, data.size - offset)
                if (!safeBulkWrite(data, offset, len)) throw Exception("Ошибка передачи данных ADB")
                offset += len
            }
        }
    }

    private fun safeBulkWrite(data: ByteArray, offset: Int = 0, length: Int = data.size, timeout: Int = 5000): Boolean {
        val conn = connection ?: return false
        val ep = endpointOut ?: return false
        var written = 0
        while (written < length) {
            val sent = conn.bulkTransfer(ep, data, offset + written, length - written, timeout)
            if (sent <= 0) return false
            written += sent
        }
        return true
    }

    // timeoutMs — параметр для AUTH-ожидания (до 30 сек)
    private fun readHeader(timeoutMs: Int = 10000): AdbHeader? {
        val conn = connection ?: return null
        val ep = endpointIn ?: return null
        val buffer = ByteArray(24)
        var totalRead = 0

        while (totalRead < 24) {
            val temp = ByteArray(24 - totalRead)
            val read = conn.bulkTransfer(ep, temp, temp.size, timeoutMs)
            if (read <= 0) return null
            System.arraycopy(temp, 0, buffer, totalRead, read)
            totalRead += read
        }

        val bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        val cmd   = bb.int.toLong() and 0xFFFFFFFFL
        val a0    = bb.int
        val a1    = bb.int
        val len   = bb.int
        val chk   = bb.int
        val magic = bb.int

        if (magic != cmd.inv().toInt()) return null
        if (len < 0 || len > MAX_PAYLOAD) return null

        return AdbHeader(cmd, a0, a1, len, chk, magic)
    }

    private fun readData(length: Int): ByteArray? {
        val conn = connection ?: return null
        val ep = endpointIn ?: return null
        if (length == 0) return ByteArray(0)
        if (length < 0 || length > MAX_PAYLOAD) return null

        val buffer = ByteArray(length)
        var totalRead = 0

        while (totalRead < length) {
            val temp = ByteArray(length - totalRead)
            val read = conn.bulkTransfer(ep, temp, temp.size, 5000)
            if (read <= 0) return null
            System.arraycopy(temp, 0, buffer, totalRead, read)
            totalRead += read
        }
        return buffer
    }

    fun disconnect() {
        cancelled = true
        adbInterface?.let { connection?.releaseInterface(it) }
        connection?.close()
        connection   = null
        endpointIn   = null
        endpointOut  = null
        adbInterface = null
        deviceFeatures.clear()
        remoteBanner = ""
    }
}
