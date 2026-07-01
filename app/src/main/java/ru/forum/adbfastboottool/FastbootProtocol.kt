package ru.forum.adbfastboottool

import android.hardware.usb.*
import java.io.File
import java.util.Locale

class FastbootProtocol(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val onLog: (String) -> Unit,
    private val onLogVerbose: (String) -> Unit = onLog,
    private val onProgress: (Int, String) -> Unit = { _, _ -> }
) {
    private var connection: UsbDeviceConnection? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null
    private var fastbootInterface: UsbInterface? = null

    var profilesDirectory: File? = null
    var debugLogging: Boolean = false

    @Volatile private var cancelled = false
    private var cachedDiagnostics: DeviceDiagnostics? = null

    val isConnected: Boolean
        get() = connection != null && endpointIn != null && endpointOut != null && fastbootInterface != null

    data class DeviceDiagnostics(
        val product: String? = null,
        val currentSlot: String? = null,
        val slotCount: String? = null,
        val slotSuffix: String? = null,
        val unlocked: String? = null,
        val secure: String? = null,
        val serialno: String? = null,
        val versionBootloader: String? = null,
        val antiRollback: String? = null,
        val isUserspace: String? = null,
        val superPartitionName: String? = null,
        val maxDownloadSizeRaw: String? = null,
        val maxDownloadSizeBytes: Long? = null,
        val maxFetchSizeRaw: String? = null,
        val maxFetchSizeBytes: Long? = null,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class LogicalPartitionInfo(
        val partition: String,
        val isLogical: String? = null,
        val sizeRaw: String? = null,
        val sizeBytes: Long? = null,
        val type: String? = null
    )

    private data class FastbootPacket(val type: String, val payload: String, val raw: String)

    // ─── ПОДКЛЮЧЕНИЕ ─────────────────────────────────────────────────────────

    fun connect(): Boolean {
        cancelled = false

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == 255 &&
                iface.interfaceSubclass == 66 &&
                iface.interfaceProtocol == 3
            ) {
                fastbootInterface = iface
                break
            }
        }
        if (fastbootInterface == null) {
            onLog("ОШИБКА: Fastboot интерфейс не найден")
            return false
        }

        for (i in 0 until fastbootInterface!!.endpointCount) {
            val ep = fastbootInterface!!.getEndpoint(i)
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

        if (!connection!!.claimInterface(fastbootInterface, true)) {
            onLog("ОШИБКА: Не удалось захватить Fastboot интерфейс")
            disconnect()
            return false
        }

        onLog("=== СОЕДИНЕНИЕ FASTBOOT УСТАНОВЛЕНО ===")
        if (debugLogging) {
            onLog("[debug] ${UsbDeviceInspector.summarizeDevice(device).replace("\n", " | ")}")
        }
        return true
    }

    // ─── КОМАНДЫ ─────────────────────────────────────────────────────────────

    fun sendCommand(command: String, timeout: Int = 5000): Boolean {
        cancelled = false
        if (isFlashWriteCommand(command) && isBootloaderLockedForFlash()) return false
        if (!writeCommand(command, timeout)) return false
        val finalPacket = readUntilFinalWithRetry(
            singleReadTimeoutMs = 2000,
            maxTotalTimeMs = 600_000
        ) ?: return false
        if (finalPacket.type == "OKAY") return true
        logFastbootFailure("Fastboot command failed: $command", finalPacket.payload.ifBlank { finalPacket.raw })
        return false
    }

    fun getVar(name: String, timeout: Int = 5000): String? {
        cancelled = false
        if (!writeCommand("getvar:$name", timeout)) return null
        val result = readGetVarResponse(name, timeout) ?: return null
        return result.trim().ifEmpty { null }
    }

    fun collectDiagnostics(): DeviceDiagnostics = refreshDiagnostics(force = true)

    fun refreshDiagnostics(force: Boolean = false, maxAgeMs: Long = DIAGNOSTICS_CACHE_TTL_MS): DeviceDiagnostics {
        val now = System.currentTimeMillis()
        val cached = cachedDiagnostics
        if (!force && cached != null && now - cached.timestamp <= maxAgeMs) {
            onLog("=== FASTBOOT ДАННЫЕ ИЗ КЭША ===")
            logDiagnostics(cached)
            return cached
        }

        onLog("=== ПРЕДПРОВЕРКА FASTBOOT ===")
        val product           = getVar("product")
        val currentSlot       = getVar("current-slot")
        // FIX #7: расширенная диагностика — slot-count, slot-suffix, serialno, version-bootloader
        val slotCount         = getVar("slot-count")
        val slotSuffix        = getVar("slot-suffix")
        val unlocked          = getVar("unlocked")
        val secure            = getVar("secure")
        val serialno          = getVar("serialno")
        val versionBootloader = getVar("version-bootloader")
        val antiRollback      = getVar("anti") ?: getVar("antirollback")
        val isUserspace       = getVar("is-userspace")
        val superPartitionName = getVar("super-partition-name")
        val maxDownloadSizeRaw = getVar("max-download-size")
        val maxDownloadSizeBytes = parseFastbootSize(maxDownloadSizeRaw)
        val maxFetchSizeRaw = getVar("max-fetch-size")
        val maxFetchSizeBytes = parseFastbootSize(maxFetchSizeRaw)

        val diagnostics = DeviceDiagnostics(
            product           = product,
            currentSlot       = currentSlot,
            slotCount         = slotCount,
            slotSuffix        = slotSuffix,
            unlocked          = unlocked,
            secure            = secure,
            serialno          = serialno,
            versionBootloader = versionBootloader,
            antiRollback      = antiRollback,
            isUserspace       = isUserspace,
            superPartitionName = superPartitionName,
            maxDownloadSizeRaw   = maxDownloadSizeRaw,
            maxDownloadSizeBytes = maxDownloadSizeBytes,
            maxFetchSizeRaw      = maxFetchSizeRaw,
            maxFetchSizeBytes    = maxFetchSizeBytes,
            timestamp = now
        )
        cachedDiagnostics = diagnostics
        logDiagnostics(diagnostics)
        return diagnostics
    }

    fun currentDiagnostics(): DeviceDiagnostics? = cachedDiagnostics

    fun runSelfTest(): Boolean {
        if (!isConnected) {
            onLog("❌ Fastboot self-test: соединение не открыто")
            return false
        }
        cancelled = false
        onLog("=== FASTBOOT SELF-TEST ===")
        val diagnostics = refreshDiagnostics(force = true)
        var ok = true

        fun pass(label: String, value: String?) {
            onLog("✅ $label: ${value ?: "—"}")
        }
        fun warn(label: String, value: String?) {
            ok = false
            onLog("⚠️ $label: ${value ?: "неизвестно"}")
        }

        if (diagnostics.product.isNullOrBlank()) warn("product не получен", diagnostics.product) else pass("product", diagnostics.product)
        if (diagnostics.serialno.isNullOrBlank()) warn("serialno не получен", diagnostics.serialno) else pass("serialno", diagnostics.serialno)
        if (diagnostics.unlocked.isNullOrBlank()) warn("unlocked не получен", diagnostics.unlocked) else pass("unlocked", diagnostics.unlocked)
        if (diagnostics.maxDownloadSizeBytes == null) warn("max-download-size не получен", diagnostics.maxDownloadSizeRaw) else pass("max-download-size", "${diagnostics.maxDownloadSizeRaw} / ${diagnostics.maxDownloadSizeBytes} байт")
        diagnostics.antiRollback?.let { pass("anti / rollback index", it) }

        val mode = when {
            diagnostics.isUserspace?.equals("yes", ignoreCase = true) == true -> "fastbootd / userspace"
            diagnostics.isUserspace?.equals("no", ignoreCase = true) == true -> "bootloader fastboot"
            else -> "неизвестно"
        }
        pass("mode", mode)
        if (diagnostics.isUserspace?.equals("yes", ignoreCase = true) == true) {
            pass("super-partition-name", diagnostics.superPartitionName)
            if (diagnostics.maxFetchSizeBytes == null) warn("max-fetch-size не получен", diagnostics.maxFetchSizeRaw) else pass("max-fetch-size", "${diagnostics.maxFetchSizeRaw} / ${diagnostics.maxFetchSizeBytes} байт")
            val suffix = diagnostics.slotSuffix ?: diagnostics.currentSlot?.let { if (it.startsWith("_")) it else "_$it" }
            val sample = listOfNotNull(
                suffix?.let { "system$it" },
                suffix?.let { "vendor$it" },
                "system_a",
                "vendor_a"
            ).distinct()
            val first = sample.firstOrNull { part ->
                val logical = getVar("is-logical:$part")
                if (!logical.isNullOrBlank()) {
                    onLog("ℹ️ is-logical:$part = $logical")
                    true
                } else false
            }
            if (first == null) onLog("ℹ️ Не удалось подтвердить logical partition через is-logical:<partition>; это может быть ограничением OEM fastbootd.")
        } else {
            onLog("ℹ️ Logical partition / fetch тесты пропущены: устройство не сообщает fastbootd/userspace.")
        }

        if (diagnostics.unlocked?.equals("no", ignoreCase = true) == true) {
            onLog("✅ Guard check: fastboot flash будет заблокирован приложением при unlocked=no.")
        } else if (diagnostics.unlocked?.equals("yes", ignoreCase = true) == true) {
            onLog("ℹ️ Guard check: bootloader unlocked=yes, flash-команды разрешены после обычных проверок файла/размера.")
        }
        onLog("ℹ️ Self-test не выполнял download/flash/erase/format/update-super.")
        return ok
    }

    private fun logDiagnostics(d: DeviceDiagnostics) {
        onLog("Устройство/product: ${d.product ?: "неизвестно"}")
        d.serialno?.let          { onLog("Serial: $it") }
        d.versionBootloader?.let { onLog("Bootloader version: $it") }
        d.antiRollback?.let { onLog("Anti-rollback index: $it") }
        val fbMode = when {
            d.isUserspace?.equals("yes", ignoreCase = true) == true -> "fastbootd / userspace"
            d.isUserspace?.equals("no", ignoreCase = true) == true -> "bootloader fastboot"
            else -> "неизвестно"
        }
        onLog("Fastboot mode: $fbMode")
        d.superPartitionName?.let { onLog("Super partition: $it") }
        onLog("Текущий слот: ${d.currentSlot ?: "—"}")
        d.slotCount?.let { onLog("Количество слотов: $it") }
        d.slotSuffix?.let { onLog("Суффикс слота: $it") }
        onLog("Bootloader unlocked: ${d.unlocked ?: "неизвестно"}")
        onLog("Secure: ${d.secure ?: "неизвестно"}")
        onLog("Max download size: ${d.maxDownloadSizeRaw ?: "неизвестно"}${d.maxDownloadSizeBytes?.let { " ($it байт)" } ?: ""}")
        d.maxFetchSizeRaw?.let { onLog("Max fetch size: $it${d.maxFetchSizeBytes?.let { bytes -> " ($bytes байт)" } ?: ""}") }
        if (d.unlocked?.equals("no", ignoreCase = true) == true) {
            onLog("⚠️ ВНИМАНИЕ: загрузчик сообщает unlocked=no. Полный терминал доступен, но fastboot flash будет заблокирован приложением.")
        }
    }

    // ─── ПРОШИВКА РАЗДЕЛА ────────────────────────────────────────────────────

    fun flashPartition(partition: String, file: File): Boolean {
        if (!isConnected) return false
        cancelled = false

        val normalizedPartition = partition.trim().lowercase()
        if (normalizedPartition.isBlank() || !normalizedPartition.matches(Regex("[A-Za-z0-9._:-]+"))) {
            onLog("❌ ОШИБКА: некорректное имя раздела: $partition")
            return false
        }
        // Некоторые Qualcomm/Xiaomi firmware-разделы шлются с суффиксом _ab —
        // это НЕ клиентская конвенция fastboot-бинаря (он суффикс не разбирает
        // и передаёт имя как есть), а команда, которую напрямую понимает сам
        // загрузчик устройства: пишет и в slot_a, и в slot_b за один вызов.
        // Поэтому для проверки "типовой список" суффикс сначала снимаем.
        val partitionBase = normalizedPartition.removeSuffix("_ab").removeSuffix("_a").removeSuffix("_b")
        if (partitionBase !in TYPICAL_FLASH_PARTITIONS) {
            onLog("⚠️ Раздел $normalizedPartition не входит в типовой список. Жёсткая блокировка снята, команда разрешена терминальным режимом.")
        }

        if (!file.exists() || !file.isFile || !file.canRead()) {
            onLog("❌ ОШИБКА: файл недоступен: ${file.name}")
            return false
        }
        if (file.length() <= 0L) {
            onLog("❌ ОШИБКА: файл пустой: ${file.name}")
            return false
        }
        if (file.length() > 0xFFFF_FFFFL) {
            onLog("❌ ОШИБКА: Fastboot download поддерживает размер до 4 GiB в этой реализации")
            return false
        }

        val fileSizeMb = file.length().toDouble() / 1024.0 / 1024.0
        onLog("Прошивка $normalizedPartition. Файл: ${file.name} (${"%.2f".format(fileSizeMb)} MB)")

        val diagnostics = refreshDiagnostics(force = false)
        if (diagnostics.unlocked?.equals("no", ignoreCase = true) == true) {
            onLog("⛔ Bootloader locked: fastboot flash заблокирован приложением, потому что getvar:unlocked вернул no.")
            onLog("Разрешены диагностические и сервисные fastboot-команды, но запись разделов требует unlocked=yes.")
            return false
        }
        DeviceProfileManager.checkPartition(profilesDirectory, diagnostics.product, normalizedPartition, onLog)
        val limit = diagnostics.maxDownloadSizeBytes
        if (limit != null && limit > 0L && file.length() > limit) {
            onLog("❌ ОШИБКА: файл (${file.length()} байт) больше max-download-size ($limit байт)")
            onLog("Прошивка отменена.")
            return false
        }

        // FIX #6: хэши уже посчитаны внутри verifyFileWithSidecars за один проход.
        // Повторного вызова ImageInspector здесь нет — двойного хэширования не происходит.
        if (!HashUtils.verifyFileWithSidecars(file, onLog)) {
            onLog("Прошивка отменена из-за ошибки контрольной суммы.")
            return false
        }

        // 1. download:<size>
        val hexSize = String.format("%08x", file.length())
        if (!writeCommand("download:$hexSize", 5000)) {
            onLog("ОШИБКА: Сбой отправки команды download")
            return false
        }

        val downloadPacket = readUntilDataOrFinal(10000) ?: return false
        when (downloadPacket.type) {
            "DATA" -> onLog("Загрузчик готов принять образ: ${downloadPacket.payload}")
            "FAIL" -> { logFastbootFailure("Загрузчик отказал в download", downloadPacket.payload); return false }
            else   -> { onLog("ОШИБКА: ожидался DATA, получено ${downloadPacket.raw}"); return false }
        }

        // 2. Передаём файл (без copyOfRange) с расширенным progress/speed/ETA логом.
        if (!transferDownloadPayload(file, "flash:$normalizedPartition")) return false

        if (cancelled) {
            onLog("⚠️ Прошивка отменена пользователем")
            return false
        }

        // 3. OKAY после передачи образа
        val downloadDone = readUntilFinal(30000) ?: return false
        if (downloadDone.type != "OKAY") {
            logFastbootFailure("Устройство забраковало образ после передачи", downloadDone.payload.ifBlank { downloadDone.raw })
            return false
        }

        // 4. flash:<partition>
        // FIX #3: readUntilFinalWithRetry — при записи в NAND/UFS устройство может
        // долго молчать между INFO-пакетами. Используем короткие таймауты × много попыток
        // вместо одного огромного таймаута 5 минут, который прерывается при первом -1.
        onLog("Запись образа в раздел $normalizedPartition (может занять несколько минут)...")
        if (!writeCommand("flash:$normalizedPartition", 5000)) {
            onLog("ОШИБКА: Сбой отправки команды flash")
            return false
        }

        val flashDone = readUntilFinalWithRetry(
            singleReadTimeoutMs = 2000,
            maxTotalTimeMs = 600_000  // 10 минут максимум
        ) ?: return false

        return if (flashDone.type == "OKAY") {
            onLog("✅ Прошивка $normalizedPartition успешно завершена!")
            true
        } else {
            logFastbootFailure("ОШИБКА записи раздела $normalizedPartition", flashDone.payload.ifBlank { flashDone.raw })
            false
        }
    }

    // ─── FASTBOOTD / DYNAMIC PARTITIONS ────────────────────────────────────

    fun inspectLogicalPartition(partition: String): LogicalPartitionInfo? {
        if (!isConnected) return null
        cancelled = false
        val normalized = normalizePartitionName(partition) ?: return null
        onLog("=== FASTBOOTD LOGICAL PARTITION INFO: $normalized ===")
        val diagnostics = refreshDiagnostics(force = false)
        if (diagnostics.isUserspace?.equals("yes", ignoreCase = true) != true) {
            onLog("⚠️ Устройство не сообщает is-userspace=yes. Для dynamic partitions обычно нужен userspace fastbootd. Выполните: fastboot reboot fastboot")
        }
        val isLogical = getVar("is-logical:$normalized")
        val sizeRaw = getVar("partition-size:$normalized")
        val sizeBytes = parseFastbootSize(sizeRaw)
        val type = getVar("partition-type:$normalized")
        val info = LogicalPartitionInfo(
            partition = normalized,
            isLogical = isLogical,
            sizeRaw = sizeRaw,
            sizeBytes = sizeBytes,
            type = type
        )
        onLog("Partition: ${info.partition}")
        onLog("Logical: ${info.isLogical ?: "неизвестно"}")
        onLog("Size: ${info.sizeRaw ?: "неизвестно"}${info.sizeBytes?.let { " ($it байт)" } ?: ""}")
        onLog("Type: ${info.type ?: "неизвестно"}")
        diagnostics.superPartitionName?.let { onLog("Super partition: $it") }
        return info
    }

    fun runLogicalPartitionCommand(command: String): Boolean {
        if (!isConnected) return false
        cancelled = false
        val clean = command.trim()
        if (!isLogicalPartitionManagementCommand(clean)) {
            onLog("❌ ОШИБКА: команда не является командой управления logical partition: $clean")
            return false
        }
        val diagnostics = refreshDiagnostics(force = false)
        if (diagnostics.isUserspace?.equals("yes", ignoreCase = true) != true) {
            onLog("⚠️ Устройство не сообщает is-userspace=yes. Команда может завершиться FAIL в bootloader fastboot.")
            onLog("Рекомендация: fastboot reboot fastboot, затем повторить команду в fastbootd.")
        }
        return sendCommand(clean)
    }

    fun fetchPartition(partition: String, outputFile: File): Boolean {
        if (!isConnected) return false
        cancelled = false
        val normalized = normalizePartitionName(partition) ?: return false
        val diagnostics = refreshDiagnostics(force = false)
        if (diagnostics.isUserspace?.equals("yes", ignoreCase = true) != true) {
            onLog("⚠️ fetch обычно реализован в fastbootd. Если устройство вернёт FAIL, выполните: fastboot reboot fastboot")
        }
        if (diagnostics.unlocked?.equals("yes", ignoreCase = true) != true) {
            onLog("⚠️ fetch в AOSP обычно требует unlocked/debuggable-состояние. Устройство может отказать.")
        }

        val parent = outputFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            onLog("❌ ОШИБКА: не удалось создать папку: ${parent.absolutePath}")
            return false
        }
        val partFile = File(outputFile.parentFile ?: File("."), outputFile.name + ".part")
        if (partFile.exists() && !partFile.delete()) {
            onLog("❌ ОШИБКА: не удалось удалить временный файл: ${partFile.absolutePath}")
            return false
        }

        val partitionSizeRaw = getVar("partition-size:$normalized")
        val partitionSize = parseFastbootSize(partitionSizeRaw)
        val maxFetch = diagnostics.maxFetchSizeBytes?.takeIf { it > 0L }
        onLog("Fastboot fetch: $normalized → ${outputFile.absolutePath}")
        partitionSize?.let { onLog("Partition size: $partitionSizeRaw ($it байт)") }
        maxFetch?.let { onLog("Max fetch chunk: $it байт") }

        return try {
            partFile.outputStream().use { out ->
                if (partitionSize != null && partitionSize > 0L && maxFetch != null) {
                    var offset = 0L
                    while (offset < partitionSize && !cancelled) {
                        val chunkSize = minOf(maxFetch, partitionSize - offset)
                        val command = "fetch:$normalized:$offset:$chunkSize"
                        val fetched = fetchChunk(command, out, offset, partitionSize) ?: return false
                        if (fetched <= 0L) {
                            onLog("❌ ОШИБКА fetch: устройство вернуло нулевой chunk")
                            return false
                        }
                        offset += fetched
                    }
                } else {
                    val command = "fetch:$normalized"
                    val fetched = fetchChunk(command, out, 0L, partitionSize ?: -1L)
                    if (fetched == null) return false
                }
            }
            if (cancelled) {
                onLog("⚠️ fetch отменён пользователем")
                false
            } else {
                if (outputFile.exists() && !outputFile.delete()) {
                    onLog("❌ ОШИБКА: не удалось заменить файл: ${outputFile.absolutePath}")
                    false
                } else if (!partFile.renameTo(outputFile)) {
                    onLog("❌ ОШИБКА: не удалось переименовать .part в итоговый файл")
                    false
                } else {
                    onLog("✅ Fastboot fetch завершён: ${outputFile.absolutePath} (${outputFile.length()} байт)")
                    true
                }
            }
        } catch (e: Exception) {
            onLog("❌ ОШИБКА fastboot fetch: ${e.message ?: e.javaClass.simpleName}")
            false
        } finally {
            if (partFile.exists() && partFile.length() == 0L) partFile.delete()
        }
    }

    // ─── УТИЛИТЫ ─────────────────────────────────────────────────────────────

    fun getVarAll(): Boolean = sendCommand("getvar:all")

    private fun isFlashWriteCommand(command: String): Boolean {
        val clean = command.trim().lowercase()
        return clean.startsWith("flash:") || clean.startsWith("flash ") || clean.startsWith("update-super:")
    }

    private fun isBootloaderLockedForFlash(): Boolean {
        val diagnostics = refreshDiagnostics(force = false)
        if (diagnostics.unlocked?.equals("no", ignoreCase = true) == true) {
            onLog("⛔ Bootloader locked: fastboot flash заблокирован приложением, потому что getvar:unlocked вернул no.")
            onLog("Разрешены диагностические и сервисные fastboot-команды, но запись разделов требует unlocked=yes.")
            return true
        }
        return false
    }

    fun downloadAndRun(file: File, commandAfterDownload: String): Boolean {
        if (!isConnected) return false
        cancelled = false

        val command = commandAfterDownload.trim()
        if (command.isBlank()) {
            onLog("❌ ОШИБКА: пустая команда после download")
            return false
        }
        if (command.any { it.code !in 32..126 }) {
            onLog("❌ ОШИБКА: Fastboot-команда должна быть ASCII")
            return false
        }

        if (!file.exists() || !file.isFile || !file.canRead()) {
            onLog("❌ ОШИБКА: файл недоступен: ${file.name}")
            return false
        }
        if (file.length() <= 0L) {
            onLog("❌ ОШИБКА: файл пустой: ${file.name}")
            return false
        }
        if (file.length() > 0xFFFF_FFFFL) {
            onLog("❌ ОШИБКА: Fastboot download поддерживает размер до 4 GiB в этой реализации")
            return false
        }

        val diagnostics = refreshDiagnostics(force = false)
        if (isFlashWriteCommand(command) && diagnostics.unlocked?.equals("no", ignoreCase = true) == true) {
            onLog("⛔ Bootloader locked: запись разделов заблокирована приложением, потому что getvar:unlocked вернул no.")
            onLog("Файл не будет передан в download-буфер; flash/update-super требует unlocked=yes.")
            return false
        }
        if (command.lowercase().startsWith("update-super:") && diagnostics.isUserspace?.equals("yes", ignoreCase = true) != true) {
            onLog("⛔ update-super разрешён только в fastbootd/userspace. Выполните fastboot reboot fastboot и повторите.")
            return false
        }
        val limit = diagnostics.maxDownloadSizeBytes
        if (limit != null && limit > 0L && file.length() > limit) {
            onLog("❌ ОШИБКА: файл (${file.length()} байт) больше max-download-size ($limit байт)")
            return false
        }
        if (!HashUtils.verifyFileWithSidecars(file, onLog)) {
            onLog("Операция отменена из-за ошибки контрольной суммы.")
            return false
        }

        val fileSizeMb = file.length().toDouble() / 1024.0 / 1024.0
        onLog("Fastboot download: ${file.name} (${"%.2f".format(fileSizeMb)} MB), затем команда: $command")

        val hexSize = String.format("%08x", file.length())
        if (!writeCommand("download:$hexSize", 5000)) {
            onLog("ОШИБКА: Сбой отправки команды download")
            return false
        }

        val downloadPacket = readUntilDataOrFinal(10000) ?: return false
        when (downloadPacket.type) {
            "DATA" -> onLog("Загрузчик готов принять файл: ${downloadPacket.payload}")
            "FAIL" -> { logFastbootFailure("Загрузчик отказал в download", downloadPacket.payload); return false }
            else   -> { onLog("ОШИБКА: ожидался DATA, получено ${downloadPacket.raw}"); return false }
        }

        if (!transferDownloadPayload(file, "download + $command")) return false

        if (cancelled) {
            onLog("⚠️ Операция отменена пользователем")
            return false
        }

        val downloadDone = readUntilFinal(30000) ?: return false
        if (downloadDone.type != "OKAY") {
            logFastbootFailure("Устройство забраковало файл после передачи", downloadDone.payload.ifBlank { downloadDone.raw })
            return false
        }

        if (!writeCommand(command, 5000)) {
            onLog("ОШИБКА: Сбой отправки команды после download")
            return false
        }

        val done = readUntilFinalWithRetry(
            singleReadTimeoutMs = 2000,
            maxTotalTimeMs = 600_000
        ) ?: return false

        return if (done.type == "OKAY") {
            onLog("✅ Fastboot-команда после download выполнена: $command")
            true
        } else {
            logFastbootFailure("Fastboot-команда после download не выполнена: $command", done.payload.ifBlank { done.raw })
            false
        }
    }

    /**
     * Разблокировка загрузчика Xiaomi: загружает encryptData (полученный от Mi
     * API) в download-буфер устройства, затем выполняет oem unlock.
     * Это финальный шаг официального протокола Mi Unlock.
     */
    fun stageAndOemUnlock(encryptDataFile: File): Boolean {
        if (!isConnected) {
            onLog("❌ Нет соединения с устройством")
            return false
        }
        cancelled = false
        if (!encryptDataFile.exists() || !encryptDataFile.isFile || encryptDataFile.length() <= 0L) {
            onLog("❌ ОШИБКА: файл разблокировки недоступен или пуст")
            return false
        }

        onLog("🔓 Staging данных разблокировки (${encryptDataFile.length()} байт)...")
        val hexSize = String.format("%08x", encryptDataFile.length())
        if (!writeCommand("download:$hexSize", 5000)) {
            onLog("❌ ОШИБКА: сбой команды download для разблокировки")
            return false
        }
        val downloadPacket = readUntilDataOrFinal(10000) ?: return false
        when (downloadPacket.type) {
            "DATA" -> onLog("Загрузчик готов принять данные разблокировки")
            "FAIL" -> { logFastbootFailure("Загрузчик отказал в download", downloadPacket.payload); return false }
            else   -> { onLog("❌ ОШИБКА: ожидался DATA, получено ${downloadPacket.raw}"); return false }
        }
        if (!transferDownloadPayload(encryptDataFile, "unlock data")) return false

        val downloadDone = readUntilFinal(30000) ?: return false
        if (downloadDone.type != "OKAY") {
            onLog("❌ ОШИБКА: download разблокировки не подтверждён: ${downloadDone.raw}")
            return false
        }

        onLog("🔓 Выполняется fastboot oem unlock...")
        if (!writeCommand("oem unlock", 10000)) {
            onLog("❌ ОШИБКА: сбой отправки oem unlock")
            return false
        }
        val unlockDone = readUntilFinal(30000) ?: return false
        return if (unlockDone.type == "OKAY") {
            onLog("✅ Загрузчик разблокирован успешно!")
            true
        } else {
            logFastbootFailure("oem unlock отклонён", unlockDone.payload)
            false
        }
    }

    private fun transferDownloadPayload(file: File, label: String): Boolean {
        val chunk = ByteArray(64 * 1024)
        val totalBytes = file.length().coerceAtLeast(1L)
        var totalSent = 0L
        var lastLoggedProgress = -1
        var lastRateLogMs = System.currentTimeMillis()
        var lastRateLogBytes = 0L
        val startedMs = lastRateLogMs

        onLog("Передача $label: ${formatBytes(totalBytes)}. Не сворачивайте приложение и не отключайте OTG/кабель.")

        // Короткая пауза перед стартом: даём USB-стеку устройства подготовиться
        // принимать данные после ответа DATA. Снижает обрывы на первом чанке.
        Thread.sleep(60)

        try {
            file.inputStream().use { input ->
                while (!cancelled) {
                    val read = input.read(chunk)
                    if (read <= 0) break

                    var offset = 0
                    while (offset < read) {
                        // USB OTG бывает нестабилен (особенно первый чанк / разные
                        // контроллеры). Один сбойный bulkTransfer не должен ронять всю
                        // прошивку — пробуем несколько раз с растущей паузой.
                        //
                        // ПРИМЕЧАНИЕ: 4×120мс (итого 0.48с) — это фикс из ПРОШЛОЙ сессии
                        // именно под обрывы на A/B onyx. На практике этого оказалось
                        // недостаточно (повторные сбои на реальном железе даже после этого
                        // фикса) — увеличиваем до 8 попыток с экспоненциальной паузой
                        // (150мс...2400мс, итого ~7.6с запаса), чтобы дать шине реальный
                        // шанс восстановиться, а не символическую полсекунды.
                        var sent = bulkWrite(chunk, offset, read - offset, 10000)
                        if (sent <= 0) {
                            var retry = 0
                            var backoffMs = 150L
                            while (sent <= 0 && retry < 8 && !cancelled) {
                                retry++
                                Thread.sleep(backoffMs)
                                onLogVerbose("⚠️ Повтор передачи чанка (попытка $retry, пауза ${backoffMs}мс) на offset $totalSent")
                                sent = bulkWrite(chunk, offset, read - offset, 10000)
                                backoffMs = (backoffMs * 3 / 2).coerceAtMost(2400L)
                            }
                            if (sent <= 0) throw Exception("Сбой передачи чанка USB после повторов: код $sent")
                        }
                        offset += sent
                    }
                    totalSent += read

                    val now = System.currentTimeMillis()
                    val progress = ((totalSent * 100L) / totalBytes).toInt().coerceIn(0, 100)
                    val shouldLogProgress = progress == 100 || progress >= lastLoggedProgress + 5
                    val shouldLogRate = now - lastRateLogMs >= 5000L
                    if (shouldLogProgress || shouldLogRate) {
                        val elapsedMs = (now - startedMs).coerceAtLeast(1L)
                        val avgBytesPerSec = (totalSent * 1000.0) / elapsedMs.toDouble()
                        val instantWindowMs = (now - lastRateLogMs).coerceAtLeast(1L)
                        val instantBytesPerSec = ((totalSent - lastRateLogBytes) * 1000.0) / instantWindowMs.toDouble()
                        val remainingBytes = (totalBytes - totalSent).coerceAtLeast(0L)
                        val etaMs = if (avgBytesPerSec > 1.0) ((remainingBytes / avgBytesPerSec) * 1000.0).toLong() else -1L
                        onLog(
                            "Передано: $progress% " +
                                "(${formatBytes(totalSent)}/${formatBytes(totalBytes)}), " +
                                "speed=${formatBytesPerSecond(instantBytesPerSec)}, " +
                                "avg=${formatBytesPerSecond(avgBytesPerSec)}, " +
                                "eta=${formatDuration(etaMs)}"
                        )
                        // Прогресс в полноэкранный диалог: процент + компактная деталь.
                        onProgress(
                            progress,
                            "${formatBytes(totalSent)} / ${formatBytes(totalBytes)}  ·  " +
                                "${formatBytesPerSecond(instantBytesPerSec)}  ·  ETA ${formatDuration(etaMs)}"
                        )
                        lastLoggedProgress = progress
                        lastRateLogMs = now
                        lastRateLogBytes = totalSent
                    }
                }
            }
        } catch (e: Exception) {
            onLog("ОШИБКА передачи: ${e.message}")
            onLog("Проверьте OTG-питание, кабель, USB-разрешение и не переводите приложение в фон во время передачи больших образов.")
            return false
        }

        if (!cancelled && totalSent < totalBytes) {
            onLog("❌ ОШИБКА: передача остановилась раньше конца файла (${formatBytes(totalSent)}/${formatBytes(totalBytes)})")
            return false
        }
        return !cancelled
    }

    private fun logFastbootFailure(context: String, payload: String) {
        val cleanPayload = payload.trim().ifBlank { "unknown fastboot failure" }
        onLog("❌ $context: $cleanPayload")
        explainFastbootFailure(cleanPayload)?.let { onLog("ℹ️ Расшифровка: $it") }
    }

    private fun explainFastbootFailure(payload: String): String? {
        val p = payload.lowercase(Locale.US)
        return when {
            "locked" in p || "unlock" in p && "not" in p -> "загрузчик заблокирован или раздел запрещён к записи при текущем состоянии bootloader."
            "not allowed" in p || "permission" in p || "denied" in p -> "OEM bootloader отказал в операции; проверьте unlocked=yes, режим fastbootd и разрешённость раздела."
            "no such partition" in p || "unknown partition" in p || "partition" in p && "not found" in p -> "раздел отсутствует на этой модели/слоте или ROM не соответствует product устройства."
            "too large" in p || "data too" in p || "max-download" in p -> "файл больше лимита max-download-size; нужен другой режим fastboot/fastbootd или раздельный/sparse-образ."
            "sparse" in p -> "ошибка sparse/sparsechunk-образа; проверьте целостность ROM и наличие всех chunk-файлов."
            "signature" in p || "verify" in p || "verification" in p || "vbmeta" in p -> "отказ проверки подписи/верификации; проверьте vbmeta/verity/verification и совместимость ROM."
            "not support" in p || "unknown command" in p || "unrecognized" in p -> "команда не поддерживается этим bootloader/fastbootd; возможно нужен другой режим или OEM-специфичный скрипт."
            "space" in p || "storage" in p || "allocation" in p -> "не хватает места/размера в dynamic partitions; проверьте update-super, super_empty.img и fastbootd."
            "timeout" in p || "timed out" in p -> "таймаут USB/fastboot; проверьте кабель, OTG-питание и не блокируйте экран хоста."
            else -> null
        }
    }

    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit += 1
        }
        return if (unit == 0) "${bytes} B" else String.format(Locale.US, "%.2f %s", value, units[unit])
    }

    private fun formatBytesPerSecond(bytesPerSec: Double): String =
        "${formatBytes(bytesPerSec.toLong().coerceAtLeast(0L))}/s"

    private fun formatDuration(ms: Long): String {
        if (ms < 0L) return "unknown"
        val totalSeconds = ms / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return if (minutes > 0L) "${minutes}m ${seconds}s" else "${seconds}s"
    }

    fun cancel() { cancelled = true }

    fun disconnect() {
        cancelled = true
        cachedDiagnostics = null
        fastbootInterface?.let { connection?.releaseInterface(it) }
        connection?.close()
        connection        = null
        endpointIn        = null
        endpointOut       = null
        fastbootInterface = null
    }

    private fun writeCommand(command: String, timeout: Int): Boolean {
        if (!isConnected) return false
        val cmdBytes = command.toByteArray(Charsets.US_ASCII)
        val sent = bulkWrite(cmdBytes, 0, cmdBytes.size, timeout)
        if (sent != cmdBytes.size) {
            onLog("ОШИБКА: команда отправлена не полностью ($sent/${cmdBytes.size} байт)")
            return false
        }
        if (debugLogging) onLog("[debug] USB OUT command bytes=${cmdBytes.size}")
        onLogVerbose("-> $command")
        return true
    }

    private fun bulkWrite(data: ByteArray, offset: Int, length: Int, timeout: Int): Int {
        val conn = connection ?: return -1
        val out  = endpointOut ?: return -1
        // Известная особенность Android UsbDeviceConnection.bulkTransfer(): передачи
        // крупнее ~16KB за один вызов ненадёжны на части USB host-контроллеров/ядер —
        // AdbProtocol.safeBulkWrite() уже режет на chunkSize=16384 по этой причине,
        // а здесь, в fastboot, резки не было — 64KB летели одним вызовом. Это вероятная
        // настоящая причина непредсказуемых обрывов на больших образах (100MB recovery
        // на onyx), а не кабель/питание: сбой одного слишком большого вызова выглядит
        // как случайный обрыв на разных offset, retry иногда "спасает" по счастливой
        // случайности. Ограничиваем один вызов тем же безопасным размером — вызывающий
        // код (transferDownloadPayload) уже умеет докидывать остаток следующими
        // вызовами (offset += sent, while offset < read), так что контракт функции
        // не меняется.
        val safeLength = length.coerceAtMost(16384)
        return conn.bulkTransfer(out, data, offset, safeLength, timeout)
    }

    private fun readPacket(timeoutMs: Int): FastbootPacket? {
        val conn  = connection ?: return null
        val input = endpointIn ?: return null
        // FIX: буфер 1024 байт — покрывает USB HS/SS пакеты и длинные INFO-строки
        val buffer = ByteArray(1024)
        val bytesRead = conn.bulkTransfer(input, buffer, buffer.size, timeoutMs)
        if (bytesRead <= 0) return null   // таймаут — caller обрабатывает

        val raw = String(buffer, 0, bytesRead, Charsets.US_ASCII).replace("\u0000", "").trim()
        if (raw.isNotEmpty()) {
            if (debugLogging) onLog("[debug] USB IN bytes=$bytesRead")
            onLogVerbose("<- $raw")
        }
        if (raw.length < 4) return FastbootPacket("UNKNOWN", raw, raw)
        return FastbootPacket(raw.take(4), raw.drop(4).trim(), raw)
    }

    private fun readUntilFinal(timeout: Int): FastbootPacket? {
        while (!cancelled) {
            val packet = readPacket(timeout) ?: return null
            when (packet.type) {
                "OKAY", "FAIL" -> return packet
                "INFO", "TEXT" -> continue
                "DATA"         -> return packet
                else           -> onLog("⚠️ Неизвестный ответ Fastboot: ${packet.raw}")
            }
        }
        onLog("⚠️ Операция отменена пользователем")
        return null
    }

    /**
     * FIX #3: Вместо одного readPacket(300_000) используем цикл с коротким
     * таймаутом (2 сек) и счётчиком суммарного времени.
     * Это позволяет:
     *  - корректно реагировать на cancelled в любой момент
     *  - не прерывать прошивку при временном молчании устройства (NAND erase)
     *  - логировать сколько секунд ждём
     */
    private fun readUntilFinalWithRetry(
        singleReadTimeoutMs: Int = 2000,
        maxTotalTimeMs: Long = 600_000L
    ): FastbootPacket? {
        val startTime = System.currentTimeMillis()
        var lastLogSec = 0L

        while (!cancelled) {
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed >= maxTotalTimeMs) {
                onLog("❌ Превышен лимит ожидания (${maxTotalTimeMs / 1000} сек). Проверьте устройство.")
                return null
            }

            val packet = readPacket(singleReadTimeoutMs)
            if (packet == null) {
                // Таймаут одного пакета — нормально при записи, логируем каждые 10 сек
                val elapsedSec = elapsed / 1000
                if (elapsedSec / 10 != lastLogSec / 10) {
                    onLog("⏳ Ожидание ответа устройства... ${elapsedSec} сек")
                    lastLogSec = elapsedSec
                }
                continue
            }

            when (packet.type) {
                "OKAY", "FAIL" -> return packet
                "INFO", "TEXT" -> continue
                else           -> onLog("⚠️ Неизвестный ответ: ${packet.raw}")
            }
        }
        onLog("⚠️ Операция отменена пользователем")
        return null
    }

    private fun readUntilDataOrFinal(timeout: Int): FastbootPacket? {
        while (!cancelled) {
            val packet = readPacket(timeout) ?: return null
            when (packet.type) {
                "DATA", "OKAY", "FAIL" -> return packet
                "INFO", "TEXT"         -> continue
                else                   -> onLog("⚠️ Неизвестный ответ Fastboot: ${packet.raw}")
            }
        }
        onLog("⚠️ Операция отменена пользователем")
        return null
    }

    private fun readGetVarResponse(name: String, timeout: Int): String? {
        val infoLines = mutableListOf<String>()
        while (!cancelled) {
            val packet = readPacket(timeout) ?: return null
            when (packet.type) {
                "INFO", "TEXT" -> if (packet.payload.isNotBlank()) infoLines += packet.payload.trim()
                "OKAY" -> {
                    val direct = packet.payload.trim()
                    if (direct.isNotEmpty()) return normalizeGetVarValue(name, direct)
                    return infoLines.asReversed()
                        .asSequence()
                        .mapNotNull { normalizeGetVarValue(name, it) }
                        .firstOrNull()
                }
                "FAIL" -> {
                    if (debugLogging) onLog("⚠️ getvar:$name не поддерживается: ${packet.payload}")
                    return null
                }
                else -> onLog("⚠️ Неизвестный ответ Fastboot: ${packet.raw}")
            }
        }
        return null
    }

    private fun normalizeGetVarValue(name: String, raw: String): String? {
        val cleaned  = raw.trim().removePrefix("INFO").trim()
        val variants = listOf(name, name.replace('-', '_'))
        for (variant in variants) {
            val prefix = "$variant:"
            if (cleaned.startsWith(prefix, ignoreCase = true)) {
                return cleaned.substringAfter(':').trim().ifBlank { null }
            }
        }
        return cleaned.substringAfter(':', cleaned).trim().ifBlank { null }
    }

    private fun normalizePartitionName(partition: String): String? {
        val normalized = partition.trim().lowercase()
        if (normalized.isBlank() || !normalized.matches(Regex("[A-Za-z0-9._:-]+"))) {
            onLog("❌ ОШИБКА: некорректное имя раздела: $partition")
            return null
        }
        return normalized
    }

    private fun isLogicalPartitionManagementCommand(command: String): Boolean {
        val clean = command.trim().lowercase()
        return clean.startsWith("create-logical-partition:") ||
            clean.startsWith("delete-logical-partition:") ||
            clean.startsWith("resize-logical-partition:") ||
            clean.startsWith("update-super:")
    }

    private fun fetchChunk(command: String, out: java.io.OutputStream, alreadyFetched: Long, totalSize: Long): Long? {
        if (!writeCommand(command, 5000)) {
            onLog("ОШИБКА: Сбой отправки команды $command")
            return null
        }
        val dataPacket = readUntilDataOrFinal(10000) ?: return null
        when (dataPacket.type) {
            "DATA" -> Unit
            "FAIL" -> { onLog("❌ ОШИБКА fetch: ${dataPacket.payload}"); return null }
            else -> { onLog("❌ ОШИБКА fetch: ожидался DATA, получено ${dataPacket.raw}"); return null }
        }
        val dataSize = parseFastbootDataSize(dataPacket.payload)
        if (dataSize == null || dataSize < 0L) {
            onLog("❌ ОШИБКА fetch: некорректный DATA размер: ${dataPacket.payload}")
            return null
        }
        if (!readRawDataTo(out, dataSize, alreadyFetched, totalSize)) return null
        val done = readUntilFinalWithRetry(singleReadTimeoutMs = 2000, maxTotalTimeMs = 120_000) ?: return null
        return if (done.type == "OKAY") {
            dataSize
        } else {
            onLog("❌ ОШИБКА fetch после data phase: ${done.payload}")
            null
        }
    }

    private fun readRawDataTo(out: java.io.OutputStream, expectedBytes: Long, alreadyFetched: Long, totalSize: Long): Boolean {
        val conn = connection ?: return false
        val input = endpointIn ?: return false
        val buffer = ByteArray(64 * 1024)
        var received = 0L
        var lastLoggedProgress = -1
        while (received < expectedBytes && !cancelled) {
            // Тот же safe-лимит 16KB на один bulkTransfer, что и в bulkWrite() —
            // см. комментарий там про ненадёжность крупных передач на части
            // USB host-контроллеров.
            val toRead = minOf(buffer.size.toLong(), expectedBytes - received, 16384L).toInt()
            val bytesRead = conn.bulkTransfer(input, buffer, toRead, 10000)
            if (bytesRead <= 0) {
                onLog("❌ ОШИБКА fetch: таймаут/сбой чтения raw data ($received/$expectedBytes байт)")
                return false
            }
            out.write(buffer, 0, bytesRead)
            received += bytesRead
            val absolute = alreadyFetched + received
            if (totalSize > 0L) {
                val progress = ((absolute * 100) / totalSize).toInt()
                if (progress % 10 == 0 && progress != lastLoggedProgress) {
                    onLog("Fetch: $progress% ($absolute/$totalSize байт)")
                    lastLoggedProgress = progress
                }
            } else if (received % (1024L * 1024L) < bytesRead) {
                onLog("Fetch принято: ${alreadyFetched + received} байт")
            }
        }
        if (cancelled) {
            onLog("⚠️ Fetch отменён пользователем")
            return false
        }
        return true
    }

    private fun parseFastbootDataSize(raw: String?): Long? {
        val token = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return try { token.toLong(16) } catch (_: NumberFormatException) { parseFastbootSize(token) }
    }

    private fun parseFastbootSize(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val token = Regex("0x[0-9A-Fa-f]+|[0-9]+").find(raw)?.value ?: return null
        return try {
            if (token.startsWith("0x", ignoreCase = true))
                token.removePrefix("0x").removePrefix("0X").toLong(16)
            else token.toLong()
        } catch (_: NumberFormatException) { null }
    }

    companion object {
        private const val DIAGNOSTICS_CACHE_TTL_MS = 5L * 60L * 1000L
        // Не whitelist: используется только для мягкого предупреждения в терминальном режиме.
        val TYPICAL_FLASH_PARTITIONS = setOf(
            "boot", "init_boot", "vendor_boot", "recovery", "dtbo", "vbmeta", "vbmeta_system",
            "vbmeta_vendor", "vendor_kernel_boot", "logo", "modem", "modemfirmware", "radio",
            "system", "vendor", "product", "odm", "super", "userdata", "metadata",
            // Qualcomm/Xiaomi firmware-разделы, встречающиеся в flash_all-скриптах
            // (обычно с суффиксом _ab, см. isKnownAbPartitionBase()):
            "abl", "aop", "aop_config", "bluetooth", "countrycode", "cpucp", "cpucp_dtb",
            "devcfg", "dsp", "featenabler", "hyp", "idmanager", "imagefv", "keymaster",
            "multiimgqti", "pvmfw", "qupfw", "shrm", "soccp_dcd", "soccp_debug",
            "spuservice", "tz", "uefi", "uefisecapp", "vm-bootsys", "xbl", "xbl_config",
            "xbl_ramdump"
        )
    }
}
