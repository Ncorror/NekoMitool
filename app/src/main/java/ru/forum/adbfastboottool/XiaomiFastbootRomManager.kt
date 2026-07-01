package ru.forum.adbfastboottool

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

object XiaomiFastbootRomManager {
    enum class FlashMode {
        CLEAN_ALL,
        SAVE_USER_DATA
    }

    data class RomAnalysis(
        val source: File,
        val format: String,
        val scripts: List<String>,
        val plans: List<ScriptPlan>,
        val imageCount: Int,
        val totalImageBytes: Long,
        val antiRollbackIndexes: List<Int> = emptyList(),
        val imageIntegrity: ImageIntegrityReport = ImageIntegrityReport.EMPTY
    ) {
        fun toDisplayText(): String = buildString {
            appendLine("=== XIAOMI FASTBOOT ROM ANALYSIS ===")
            appendLine("Source: ${source.name}")
            appendLine("Format: $format")
            appendLine("Scripts: ${if (scripts.isEmpty()) "none" else scripts.joinToString(", ")}")
            appendLine("Images: $imageCount (${formatBytes(totalImageBytes)})")
            appendLine("Anti-rollback index: ${if (antiRollbackIndexes.isEmpty()) "not detected" else antiRollbackIndexes.joinToString(", ")}")
            appendLine()
            append(imageIntegrity.toDisplayText())
            if (plans.isEmpty()) {
                appendLine("❌ No supported flash_all script found")
            } else {
                plans.forEach { plan ->
                    appendLine()
                    appendLine(plan.toDisplayText())
                }
            }
        }
    }

    data class RomImageEntry(
        val archivePath: String,
        val size: Long
    ) {
        val baseName: String get() = archivePath.substringAfterLast('/').lowercase(Locale.US)
    }

    data class ImageIntegrityReport(
        val imageEntries: List<RomImageEntry>,
        val zeroByteEntries: List<RomImageEntry>,
        val suspiciousSmallEntries: List<RomImageEntry>,
        val duplicateBasenames: Map<String, List<RomImageEntry>>,
        val sparseChunkEntries: List<RomImageEntry>,
        val splitImageGroups: Map<String, List<RomImageEntry>>,
        val splitSequenceWarnings: List<String>
    ) {
        fun toDisplayText(): String = buildString {
            appendLine("=== IMAGE MANIFEST CHECK ===")
            appendLine("Image-like entries: ${imageEntries.size}")
            appendLine("Zero-byte images: ${if (zeroByteEntries.isEmpty()) "no" else zeroByteEntries.size}")
            appendLine("Suspicious small images (<512 B): ${if (suspiciousSmallEntries.isEmpty()) "no" else suspiciousSmallEntries.size}")
            appendLine("Duplicate image basenames: ${if (duplicateBasenames.isEmpty()) "no" else duplicateBasenames.size}")
            appendLine("Sparse/split chunks: ${if (sparseChunkEntries.isEmpty()) "no" else sparseChunkEntries.size}")
            appendLine("Split image groups: ${if (splitImageGroups.isEmpty()) "no" else splitImageGroups.size}")
            zeroByteEntries.take(10).forEach { appendLine("  ZERO: ${it.archivePath}") }
            suspiciousSmallEntries.take(10).forEach { appendLine("  SMALL: ${it.archivePath} (${it.size} B)") }
            duplicateBasenames.entries.take(10).forEach { (name, entries) ->
                appendLine("  DUPLICATE: $name → ${entries.joinToString { it.archivePath }}")
            }
            splitImageGroups.entries.take(10).forEach { (name, entries) ->
                appendLine("  SPLIT: $name → ${entries.size} chunks (${formatBytes(entries.sumOf { it.size.coerceAtLeast(0L) })})")
            }
            splitSequenceWarnings.take(10).forEach { appendLine("  SPLIT WARNING: $it") }
        }

        companion object {
            val EMPTY = ImageIntegrityReport(emptyList(), emptyList(), emptyList(), emptyMap(), emptyList(), emptyMap(), emptyList())
        }
    }

    data class ScriptPlan(
        val scriptName: String,
        val expectedProducts: List<String>,
        val commands: List<PlanCommand>,
        val warnings: List<String>,
        val blockedReasons: List<String>,
        val lockCommandDetected: Boolean,
        val storageWipeDetected: Boolean,
        val antiRollbackIndexes: List<Int> = emptyList(),
        val criticalPartitionDetected: Boolean = false,
        val dataImpact: DataImpact = DataImpact.EMPTY
    ) {
        val canFlash: Boolean get() = blockedReasons.isEmpty() && !lockCommandDetected

        fun toDisplayText(): String = buildString {
            appendLine("Script: $scriptName")
            appendLine("Script role: ${scriptRoleLabel(scriptName, lockCommandDetected, dataImpact)}")
            appendLine("Expected product: ${if (expectedProducts.isEmpty()) "not declared" else expectedProducts.joinToString(", ")}")
            appendLine("Commands: ${commands.size}")
            appendLine("Storage wipe: ${if (storageWipeDetected) "yes" else "no"}")
            appendLine("Data impact: ${dataImpact.shortLabel()}")
            appendLine("Anti-rollback index: ${if (antiRollbackIndexes.isEmpty()) "not detected" else antiRollbackIndexes.joinToString(", ")}")
            appendLine("Critical firmware partitions: ${if (criticalPartitionDetected) "yes" else "no"}")
            appendLine("Lock command: ${if (lockCommandDetected) "YES — BLOCKED" else "no"}")
            if (blockedReasons.isNotEmpty()) {
                appendLine("Blocked:")
                blockedReasons.forEach { appendLine("  - $it") }
            }
            if (warnings.isNotEmpty()) {
                appendLine("Warnings:")
                warnings.forEach { appendLine("  - $it") }
            }
            commands.take(40).forEachIndexed { index, command ->
                appendLine("${index + 1}. ${command.toDisplayText()}")
            }
            if (commands.size > 40) appendLine("... ${commands.size - 40} more commands")
        }
    }


    data class DataImpact(
        val wipesUserdata: Boolean,
        val wipesMetadata: Boolean,
        val wipesCache: Boolean,
        val updateSuperWipe: Boolean,
        val explicitFastbootWipe: Boolean,
        val details: List<String>
    ) {
        fun hasDataLossRisk(): Boolean = wipesUserdata || wipesMetadata || wipesCache || updateSuperWipe || explicitFastbootWipe

        fun shortLabel(): String = if (hasDataLossRisk()) {
            "DATA WIPE RISK"
        } else {
            "no userdata wipe detected"
        }

        fun toDisplayText(): String = buildString {
            appendLine("=== DATA IMPACT SUMMARY ===")
            appendLine("Userdata wipe: ${if (wipesUserdata) "yes" else "no"}")
            appendLine("Metadata wipe: ${if (wipesMetadata) "yes" else "no"}")
            appendLine("Cache wipe: ${if (wipesCache) "yes" else "no"}")
            appendLine("update-super wipe: ${if (updateSuperWipe) "yes" else "no"}")
            appendLine("fastboot -w / --wipe: ${if (explicitFastbootWipe) "yes" else "no"}")
            if (details.isNotEmpty()) {
                appendLine("Details:")
                details.forEach { appendLine("  - $it") }
            }
        }

        companion object {
            val EMPTY = DataImpact(false, false, false, false, false, emptyList())
        }
    }

    data class StoragePreflight(
        val sourceBytes: Long,
        val selectedImageBytes: Long,
        val estimatedFastbootTransferBytes: Long,
        val requiredWorkspaceBytes: Long,
        val workspaceFreeBytes: Long,
        val referencedImageCount: Int,
        val largestImageBytes: Long,
        val requiresFastbootd: Boolean,
        val largeImages: List<RomImageEntry>,
        val warnings: List<String>,
        val blockedReasons: List<String>
    ) {
        fun toDisplayText(): String = buildString {
            appendLine("=== STORAGE / TRANSFER PREFLIGHT ===")
            appendLine("Source ROM size: ${formatBytes(sourceBytes)}")
            appendLine("Referenced images: $referencedImageCount")
            appendLine("Selected image bytes: ${formatBytes(selectedImageBytes)}")
            appendLine("Estimated fastboot transfer: ${formatBytes(estimatedFastbootTransferBytes)}")
            appendLine("Largest referenced image: ${formatBytes(largestImageBytes)}")
            appendLine("Workspace free: ${if (workspaceFreeBytes > 0L) formatBytes(workspaceFreeBytes) else "unknown"}")
            appendLine("Required workspace free: ${formatBytes(requiredWorkspaceBytes)}")
            appendLine("Requires fastbootd/userspace: ${if (requiresFastbootd) "yes" else "no"}")
            if (largeImages.isNotEmpty()) {
                appendLine("Large images:")
                largeImages.take(10).forEach { appendLine("  - ${it.archivePath} (${formatBytes(it.size)})") }
            }
            if (warnings.isNotEmpty()) {
                appendLine("Warnings:")
                warnings.forEach { appendLine("  - $it") }
            }
            if (blockedReasons.isNotEmpty()) {
                appendLine("Blocked:")
                blockedReasons.forEach { appendLine("  - $it") }
            }
        }

        companion object {
            val EMPTY = StoragePreflight(
                sourceBytes = 0L,
                selectedImageBytes = 0L,
                estimatedFastbootTransferBytes = 0L,
                requiredWorkspaceBytes = 0L,
                workspaceFreeBytes = 0L,
                referencedImageCount = 0,
                largestImageBytes = 0L,
                requiresFastbootd = false,
                largeImages = emptyList(),
                warnings = emptyList(),
                blockedReasons = emptyList()
            )
        }
    }

    sealed class PlanCommand {
        data class Flash(val partition: String, val imageRef: String) : PlanCommand()
        data class Erase(val partition: String) : PlanCommand()
        data class WipeData(val reason: String) : PlanCommand()
        data class SetActive(val slot: String) : PlanCommand()
        data class UpdateSuper(val imageRef: String, val wipe: Boolean) : PlanCommand()
        data class Reboot(val target: String?) : PlanCommand()

        fun toDisplayText(): String = when (this) {
            is Flash -> "flash $partition ← $imageRef"
            is Erase -> "erase $partition"
            is WipeData -> "wipe userdata/metadata/cache ($reason)"
            is SetActive -> "set_active $slot"
            is UpdateSuper -> "update-super ← $imageRef${if (wipe) " wipe" else ""}"
            is Reboot -> "reboot${target?.let { " $it" } ?: ""}"
        }
    }

    data class PreparedPlan(
        val source: File,
        val mode: FlashMode,
        val plan: ScriptPlan,
        val extractionDir: File,
        val actions: List<PreparedAction>,
        val blockedReasons: List<String>,
        val warnings: List<String> = emptyList(),
        val storagePreflight: StoragePreflight = StoragePreflight.EMPTY
    ) {
        fun toDisplayText(): String = buildString {
            appendLine("=== XIAOMI FASTBOOT ROM PLAN ===")
            appendLine("Mode: ${mode.name}")
            appendLine("Script: ${plan.scriptName}")
            appendLine("Script role: ${scriptRoleLabel(plan.scriptName, plan.lockCommandDetected, plan.dataImpact)}")
            appendLine("Extraction: ${extractionDir.absolutePath}")
            appendLine("Expected product: ${if (plan.expectedProducts.isEmpty()) "not declared" else plan.expectedProducts.joinToString(", ")}")
            appendLine("Anti-rollback index: ${if (plan.antiRollbackIndexes.isEmpty()) "not detected" else plan.antiRollbackIndexes.joinToString(", ")}")
            appendLine(plan.dataImpact.toDisplayText().trimEnd())
            if (storagePreflight != StoragePreflight.EMPTY) {
                appendLine(storagePreflight.toDisplayText().trimEnd())
            }
            if (warnings.isNotEmpty()) {
                appendLine("Warnings:")
                warnings.forEach { appendLine("  - $it") }
            }
            if (blockedReasons.isNotEmpty()) {
                appendLine("Blocked:")
                blockedReasons.forEach { appendLine("  - $it") }
            }
            actions.take(80).forEachIndexed { index, action -> appendLine("${index + 1}. ${action.toDisplayText()}") }
            if (actions.size > 80) appendLine("... ${actions.size - 80} more actions")
        }
    }

    sealed class PreparedAction {
        data class Flash(val partition: String, val imageFile: File) : PreparedAction()
        data class Erase(val partition: String) : PreparedAction()
        data class WipeData(val reason: String) : PreparedAction()
        data class SetActive(val slot: String) : PreparedAction()
        data class UpdateSuper(val imageFile: File, val wipe: Boolean, val superPartition: String = "super") : PreparedAction()
        data class Reboot(val target: String?) : PreparedAction()

        fun toDisplayText(): String = when (this) {
            is Flash -> "flash $partition ← ${imageFile.name} (${formatBytes(imageFile.length())})"
            is Erase -> "erase $partition"
            is WipeData -> "wipe userdata/metadata/cache ($reason)"
            is SetActive -> "set_active $slot"
            is UpdateSuper -> "update-super $superPartition ← ${imageFile.name} (${formatBytes(imageFile.length())})${if (wipe) " wipe" else ""}"
            is Reboot -> "reboot${target?.let { " $it" } ?: ""}"
        }
    }

    fun isSupportedRomFile(file: File): Boolean {
        val name = file.name.lowercase(Locale.US)
        return file.isFile && (
            name.endsWith(".zip") ||
                name.endsWith(".tgz") ||
                name.endsWith(".tar") ||
                name.endsWith(".tar.gz")
            )
    }

    fun analyze(source: File): RomAnalysis {
        val format = detectFormat(source)
        val scripts = readScripts(source)
        val imageEntries = collectImageManifest(source)
        val imageStats = imageEntries.size to imageEntries.sumOf { if (it.size > 0) it.size else 0L }
        val imageIntegrity = buildImageIntegrityReport(imageEntries)
        val archiveAntiRollback = readArchiveAntiRollbackIndexes(source)
        val plans = scripts.map { (name, text) -> parseScript(name, text) }
            .filter { it.commands.isNotEmpty() || it.lockCommandDetected || it.blockedReasons.isNotEmpty() }
            .sortedBy { it.scriptName.lowercase(Locale.US) }
        val antiRollbackIndexes = (archiveAntiRollback + plans.flatMap { it.antiRollbackIndexes }).distinct().sorted()
        return RomAnalysis(
            source = source,
            format = format,
            scripts = scripts.keys.sorted(),
            plans = plans,
            imageCount = imageStats.first,
            totalImageBytes = imageStats.second,
            antiRollbackIndexes = antiRollbackIndexes,
            imageIntegrity = imageIntegrity
        )
    }

    fun prepareForFlash(
        source: File,
        workspaceDir: File,
        mode: FlashMode,
        onLog: (String) -> Unit
    ): PreparedPlan {
        val analysis = analyze(source)
        val selected = selectPlan(analysis, mode)
            ?: return PreparedPlan(
                source = source,
                mode = mode,
                plan = ScriptPlan("—", emptyList(), emptyList(), emptyList(), listOf("No matching script for $mode"), false, false, dataImpact = DataImpact.EMPTY),
                extractionDir = File(workspaceDir, "roms"),
                actions = emptyList(),
                blockedReasons = listOf("No matching script for $mode")
            )

        val blocked = selected.blockedReasons.toMutableList()
        val warnings = selected.warnings.toMutableList()
        if (selected.lockCommandDetected) blocked += "Selected script contains bootloader lock command. NekoFlash blocks flash_all_lock scenarios."
        if (mode == FlashMode.SAVE_USER_DATA && selected.dataImpact.hasDataLossRisk()) {
            blocked += "Save user data mode selected, but the script has data-loss risk: ${selected.dataImpact.shortLabel()}."
        }
        val scriptMatrix = analysis.plans.joinToString { plan ->
            "${plan.scriptName} [${scriptRoleLabel(plan.scriptName, plan.lockCommandDetected, plan.dataImpact)}]"
        }
        if (scriptMatrix.isNotBlank()) {
            warnings += "Available flash script variants: $scriptMatrix"
        }

        val unsupportedMidPlanReboot = selected.commands.dropLast(1)
            .filterIsInstance<PlanCommand.Reboot>()
            .filterNot { it.target?.equals("fastboot", ignoreCase = true) == true || it.target?.equals("fastbootd", ignoreCase = true) == true }
        if (unsupportedMidPlanReboot.isNotEmpty()) {
            blocked += "Selected script reboots before the end to a target other than fastbootd. Automatic reconnect/resume is supported only for fastboot reboot fastboot."
        }

        val extractionDir = File(File(workspaceDir, "roms"), "${safeBaseName(source.nameWithoutExtension)}-${sourceFingerprint(source)}")
        if (!extractionDir.exists() && !extractionDir.mkdirs()) {
            blocked += "Cannot create extraction directory: ${extractionDir.absolutePath}"
        }

        val flashRefs = selected.commands.mapNotNull { command ->
            when (command) {
                is PlanCommand.Flash -> normalizeArchivePath(command.imageRef)
                is PlanCommand.UpdateSuper -> normalizeArchivePath(command.imageRef)
                else -> null
            }
        }.distinct()

        val imageManifest = collectImageManifest(source)
        val imageIntegrityForPrepare = buildImageIntegrityReport(imageManifest)
        imageIntegrityForPrepare.splitSequenceWarnings.forEach { warning ->
            warnings += "Sparse/split image sequence warning: $warning"
        }
        val duplicateReferencedBasenames = imageIntegrityForPrepare.duplicateBasenames.filterKeys { basename ->
            flashRefs.any { ref -> ref.substringAfterLast('/').equals(basename, ignoreCase = true) }
        }
        duplicateReferencedBasenames.entries.take(10).forEach { (basename, entries) ->
            warnings += "Duplicate image basename used by selected script: $basename → ${entries.joinToString { it.archivePath }}. Exact archive path matching is preferred."
        }
        val referencedManifest = matchReferencedImages(imageManifest, flashRefs)
        val manifestMissingRefs = flashRefs.filterNot { it in referencedManifest.keys }
        if (manifestMissingRefs.isNotEmpty()) {
            warnings += "Referenced image entries not found during manifest scan: ${manifestMissingRefs.joinToString(", ")}"
        }
        referencedManifest.forEach { (ref, entry) ->
            if (entry.size == 0L) {
                warnings += "Referenced image is zero bytes in ROM manifest and will block after extraction: $ref"
            } else if (entry.size in 1 until MIN_EXPECTED_IMAGE_BYTES) {
                warnings += "Referenced image is suspiciously small: $ref (${entry.size} B)"
            }
        }

        val storagePreflight = buildStoragePreflight(
            source = source,
            plan = selected,
            flashRefs = flashRefs,
            referencedManifest = referencedManifest,
            workspaceDir = workspaceDir,
            extractionDir = extractionDir
        )
        warnings += storagePreflight.warnings
        blocked += storagePreflight.blockedReasons

        if (blocked.isEmpty()) {
            resetExtractionDirectory(extractionDir, onLog)
        }

        val extracted = if (blocked.isEmpty()) {
            extractReferencedImages(source, flashRefs, extractionDir, onLog)
        } else {
            emptyMap()
        }

        val actions = mutableListOf<PreparedAction>()
        selected.commands.forEach { command ->
            when (command) {
                is PlanCommand.Flash -> {
                    val ref = normalizeArchivePath(command.imageRef)
                    val imageFile = extracted[ref]
                    if (imageFile == null) {
                        blocked += "Image not found for partition ${command.partition}: ${command.imageRef}"
                    } else {
                        actions += PreparedAction.Flash(command.partition, imageFile)
                    }
                }
                is PlanCommand.Erase -> actions += PreparedAction.Erase(command.partition)
                is PlanCommand.WipeData -> actions += PreparedAction.WipeData(command.reason)
                is PlanCommand.SetActive -> actions += PreparedAction.SetActive(command.slot)
                is PlanCommand.UpdateSuper -> {
                    val ref = normalizeArchivePath(command.imageRef)
                    val imageFile = extracted[ref]
                    if (imageFile == null) {
                        blocked += "Image not found for update-super: ${command.imageRef}"
                    } else {
                        actions += PreparedAction.UpdateSuper(imageFile, command.wipe)
                    }
                }
                is PlanCommand.Reboot -> actions += PreparedAction.Reboot(command.target)
            }
        }

        actions.forEach { action ->
            val image = when (action) {
                is PreparedAction.Flash -> action.imageFile
                is PreparedAction.UpdateSuper -> action.imageFile
                else -> null
            }
            if (image != null) {
                when {
                    !image.exists() || !image.isFile -> blocked += "Extracted image is not readable: ${image.absolutePath}"
                    image.length() == 0L -> blocked += "Extracted image is zero bytes: ${image.name}"
                    image.length() in 1 until MIN_EXPECTED_IMAGE_BYTES -> warnings += "Extracted image is suspiciously small: ${image.name} (${image.length()} B)"
                }
            }
        }

        return PreparedPlan(
            source = source,
            mode = mode,
            plan = selected,
            extractionDir = extractionDir,
            actions = actions,
            blockedReasons = blocked.distinct(),
            warnings = warnings.distinct(),
            storagePreflight = storagePreflight
        )
    }

    private fun buildStoragePreflight(
        source: File,
        plan: ScriptPlan,
        flashRefs: List<String>,
        referencedManifest: Map<String, RomImageEntry>,
        workspaceDir: File,
        extractionDir: File
    ): StoragePreflight {
        val warnings = mutableListOf<String>()
        val blocked = mutableListOf<String>()
        val uniqueEntries = referencedManifest.values.distinctBy { it.archivePath.lowercase(Locale.US) }
        val selectedImageBytes = uniqueEntries.sumOf { it.size.coerceAtLeast(0L) }
        val estimatedTransferBytes = plan.commands.mapNotNull { command ->
            when (command) {
                is PlanCommand.Flash -> normalizeArchivePath(command.imageRef)
                is PlanCommand.UpdateSuper -> normalizeArchivePath(command.imageRef)
                else -> null
            }
        }.sumOf { ref -> referencedManifest[ref]?.size?.coerceAtLeast(0L) ?: 0L }
        val largestImageBytes = uniqueEntries.maxOfOrNull { it.size.coerceAtLeast(0L) } ?: 0L
        val requiresFastbootd = plan.commands.any { command ->
            when (command) {
                is PlanCommand.UpdateSuper -> true
                is PlanCommand.Reboot -> command.target?.equals("fastboot", ignoreCase = true) == true || command.target?.equals("fastbootd", ignoreCase = true) == true
                is PlanCommand.Flash -> isLogicalDynamicPartition(command.partition)
                else -> false
            }
        }
        val largeImages = uniqueEntries.filter { it.size >= LARGE_IMAGE_WARNING_BYTES }.sortedByDescending { it.size }
        if (largeImages.isNotEmpty()) {
            warnings += "Large image transfer detected: ${largeImages.take(3).joinToString { it.archivePath + " (" + formatBytes(it.size) + ")" }}. Keep OTG power stable and do not background the app."
        }
        if (requiresFastbootd) {
            warnings += "The selected plan requires fastbootd/userspace for dynamic partitions or update-super. NekoFlash will checkpoint before the transition."
        }

        val extractionRoot = extractionDir.parentFile ?: workspaceDir
        if (!extractionRoot.exists()) extractionRoot.mkdirs()
        val workspaceFreeBytes = extractionRoot.usableSpace.coerceAtLeast(0L)
        val requiredWorkspaceBytes = estimateWorkspaceBytes(selectedImageBytes)
        if (workspaceFreeBytes <= 0L) {
            warnings += "Workspace free space could not be determined for ${extractionRoot.absolutePath}."
        } else if (workspaceFreeBytes < requiredWorkspaceBytes) {
            blocked += "Not enough free space in workspace: free=${formatBytes(workspaceFreeBytes)}, required=${formatBytes(requiredWorkspaceBytes)}. Move the ROM/workspace to storage with more free space."
        } else if (workspaceFreeBytes < (requiredWorkspaceBytes + WORKSPACE_HEADROOM_BYTES)) {
            warnings += "Workspace free space is tight: free=${formatBytes(workspaceFreeBytes)}, recommended=${formatBytes(requiredWorkspaceBytes + WORKSPACE_HEADROOM_BYTES)}."
        }
        if (flashRefs.isEmpty()) {
            warnings += "No referenced images were found for storage preflight."
        }

        return StoragePreflight(
            sourceBytes = source.length().coerceAtLeast(0L),
            selectedImageBytes = selectedImageBytes,
            estimatedFastbootTransferBytes = estimatedTransferBytes,
            requiredWorkspaceBytes = requiredWorkspaceBytes,
            workspaceFreeBytes = workspaceFreeBytes,
            referencedImageCount = uniqueEntries.size,
            largestImageBytes = largestImageBytes,
            requiresFastbootd = requiresFastbootd,
            largeImages = largeImages,
            warnings = warnings.distinct(),
            blockedReasons = blocked.distinct()
        )
    }

    private fun estimateWorkspaceBytes(selectedImageBytes: Long): Long {
        val overhead = maxOf(WORKSPACE_HEADROOM_BYTES, selectedImageBytes / 10L)
        return selectedImageBytes.coerceAtLeast(0L) + overhead
    }

    private fun isLogicalDynamicPartition(partition: String): Boolean {
        val clean = partition.lowercase(Locale.US).removeSuffix("_a").removeSuffix("_b")
        return clean in LOGICAL_DYNAMIC_PARTITIONS
    }

    fun selectPlan(analysis: RomAnalysis, mode: FlashMode): ScriptPlan? {
        return analysis.plans.mapNotNull { plan ->
            val score = scriptSelectionScore(plan, mode) ?: return@mapNotNull null
            score to plan
        }.sortedWith(
            compareBy<Pair<Int, ScriptPlan>> { it.first }
                .thenBy { if (it.second.scriptName.lowercase(Locale.US).endsWith(".bat")) 0 else 1 }
                .thenBy { it.second.scriptName.length }
                .thenBy { it.second.scriptName.lowercase(Locale.US) }
        ).firstOrNull()?.second
    }

    private fun scriptSelectionScore(plan: ScriptPlan, mode: FlashMode): Int? {
        val name = plan.scriptName.substringAfterLast('/').lowercase(Locale.US)
        if (!name.contains("flash_all")) return null
        if (isLockScriptName(name) || plan.lockCommandDetected) return null

        val roleScore = when (mode) {
            FlashMode.CLEAN_ALL -> cleanAllScriptScore(name)
            FlashMode.SAVE_USER_DATA -> saveDataScriptScore(name)
        } ?: return null

        val dataRiskPenalty = if (mode == FlashMode.SAVE_USER_DATA && plan.dataImpact.hasDataLossRisk()) 1_000 else 0
        val blockedPenalty = if (plan.blockedReasons.isNotEmpty()) 100 else 0
        val shellPenalty = if (name.endsWith(".sh")) 10 else 0
        return roleScore + dataRiskPenalty + blockedPenalty + shellPenalty
    }

    private fun cleanAllScriptScore(name: String): Int? {
        if (hasSaveDataMarker(name)) return null
        if (name == "flash_all.bat" || name == "flash_all.sh") return 0
        if (name.startsWith("flash_all") && !isLockScriptName(name)) return 30
        return null
    }

    private fun saveDataScriptScore(name: String): Int? {
        if (name.contains("except_storage")) return 0
        if (name.contains("except_data_storage")) return 1
        if (name.contains("except_userdata")) return 2
        if (name.contains("except_data")) return 3
        if (name.contains("save_data") || name.contains("savedata")) return 4
        if (name.contains("no_wipe") || name.contains("nowipe")) return 5
        if (name.contains("preserve")) return 6
        if (name.contains("except")) return 20
        return null
    }

    private fun hasSaveDataMarker(name: String): Boolean {
        return name.contains("except") ||
            name.contains("save_data") ||
            name.contains("savedata") ||
            name.contains("no_wipe") ||
            name.contains("nowipe") ||
            name.contains("preserve")
    }

    private fun isLockScriptName(name: String): Boolean {
        val clean = name.substringAfterLast('/').lowercase(Locale.US)
        return clean.contains("flash_all_lock") ||
            clean.contains("_lock") ||
            clean.contains("-lock") ||
            Regex("(^|[_.-])lock([_.-]|$)").containsMatchIn(clean)
    }

    private fun scriptRoleLabel(scriptName: String, lockCommandDetected: Boolean, dataImpact: DataImpact): String {
        val name = scriptName.substringAfterLast('/').lowercase(Locale.US)
        return when {
            lockCommandDetected || isLockScriptName(name) -> "LOCK / BLOCKED"
            saveDataScriptScore(name) != null -> if (dataImpact.hasDataLossRisk()) {
                "SAVE_USER_DATA CANDIDATE / DATA WIPE RISK"
            } else {
                "SAVE_USER_DATA"
            }
            cleanAllScriptScore(name) != null -> if (dataImpact.hasDataLossRisk()) {
                "CLEAN_ALL / wipes data"
            } else {
                "CLEAN_ALL"
            }
            else -> "UNKNOWN"
        }
    }

    private fun parseScript(scriptName: String, text: String): ScriptPlan {
        val expectedProducts = extractExpectedProducts(text)
        val antiRollbackIndexes = extractAntiRollbackIndexes(text)

        val commands = mutableListOf<PlanCommand>()
        val warnings = mutableListOf<String>()
        val blocked = mutableListOf<String>()
        var lockDetected = false
        var wipeDetected = false
        var unsupportedFastbootLines = 0

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isBlank()) return@forEach
            val lower = line.lowercase(Locale.US)
            if (lower.startsWith("rem ") || lower.startsWith("::") || lower.startsWith("#")) return@forEach
            if (!lower.contains("fastboot")) return@forEach

            val commandPart = stripControlOperators(line)
            val tokens = tokenize(commandPart)
            val fastbootIndex = tokens.indexOfFirst { token ->
                val clean = token.trim('"', '\'').lowercase(Locale.US)
                clean == "fastboot" ||
                    clean == "fastboot.exe" ||
                    clean == "%fastboot%" ||
                    clean == "\$fastboot" ||
                    clean == "\${fastboot}" ||
                    clean.endsWith("/fastboot") ||
                    clean.endsWith("/fastboot.exe") ||
                    clean.endsWith("\\fastboot") ||
                    clean.endsWith("\\fastboot.exe")
            }
            if (fastbootIndex < 0) return@forEach

            val args = tokens.drop(fastbootIndex + 1)
                .filterNot { it == "%*" || it == "\$@" || it == "\$*" }
                .map { it.trim('"', '\'') }
                .filter { it.isNotBlank() }

            val parsed = parseFastbootArgs(args)
            if (parsed == null) {
                if (isLockLine(args)) lockDetected = true
                if (looksLikeFastbootCommand(args)) unsupportedFastbootLines++
                return@forEach
            }

            if (args.any { it == "-w" || it == "--wipe" } && parsed !is PlanCommand.WipeData) {
                val wipe = PlanCommand.WipeData("fastboot -w / --wipe before ${parsed.toDisplayText()}")
                commands += wipe
                wipeDetected = true
                warnings += "Explicit fastboot -w / --wipe flag detected before ${parsed.toDisplayText()}. NekoFlash converts it into guarded userdata/metadata/cache erases."
            }

            when (parsed) {
                is PlanCommand.Flash -> commands += parsed
                is PlanCommand.Erase -> {
                    commands += parsed
                    if (isDataPartition(parsed.partition)) wipeDetected = true
                }
                is PlanCommand.WipeData -> {
                    commands += parsed
                    wipeDetected = true
                    warnings += "Explicit ${parsed.reason} detected. NekoFlash converts it into guarded userdata/metadata/cache erases."
                }
                is PlanCommand.SetActive -> commands += parsed
                is PlanCommand.UpdateSuper -> {
                    commands += parsed
                    if (parsed.wipe) wipeDetected = true
                    warnings += "update-super detected. NekoFlash will execute it only after fastbootd/userspace is confirmed."
                }
                is PlanCommand.Reboot -> commands += parsed
            }
            if (isLockLine(args)) lockDetected = true
            if (args.any { it == "-w" || it == "--wipe" }) wipeDetected = true
        }

        val criticalDetected = commands.any { command ->
            command is PlanCommand.Flash && isCriticalFirmwarePartition(command.partition)
        }
        if (antiRollbackIndexes.isNotEmpty()) warnings += "Anti-rollback index detected in ROM script: ${antiRollbackIndexes.joinToString(", ")}. NekoFlash will compare it with getvar:anti before flashing."
        if (criticalDetected) warnings += "Critical firmware partitions detected. NekoFlash keeps product/unlocked/ARB guards enabled before flashing them."
        if (expectedProducts.isEmpty()) warnings += "Product guard was not detected in the script. The app will rely on fastboot diagnostics only."
        if (unsupportedFastbootLines > 0) blocked += "Unsupported fastboot lines detected: $unsupportedFastbootLines. Partial ROM flashing is blocked."
        if (lockDetected) blocked += "Bootloader lock command detected. Use flash_all, not flash_all_lock."
        if (commands.none { it is PlanCommand.Flash || it is PlanCommand.UpdateSuper }) {
            blocked += "No flash/update-super commands detected in script."
        }

        val dataImpact = buildDataImpact(commands)

        return ScriptPlan(
            scriptName,
            expectedProducts,
            commands,
            warnings.distinct(),
            blocked.distinct(),
            lockDetected,
            wipeDetected || dataImpact.hasDataLossRisk(),
            antiRollbackIndexes,
            criticalDetected,
            dataImpact
        )
    }


    private fun buildDataImpact(commands: List<PlanCommand>): DataImpact {
        val details = mutableListOf<String>()
        var wipesUserdata = false
        var wipesMetadata = false
        var wipesCache = false
        var updateSuperWipe = false
        var explicitFastbootWipe = false

        commands.forEach { command ->
            when (command) {
                is PlanCommand.Erase -> {
                    val clean = command.partition.lowercase(Locale.US).removeSuffix("_a").removeSuffix("_b")
                    when (clean) {
                        "userdata", "data" -> { wipesUserdata = true; details += "${command.toDisplayText()} removes user data" }
                        "metadata" -> { wipesMetadata = true; details += "${command.toDisplayText()} removes encryption metadata" }
                        "cache" -> { wipesCache = true; details += "${command.toDisplayText()} removes cache" }
                    }
                }
                is PlanCommand.WipeData -> {
                    explicitFastbootWipe = true
                    wipesUserdata = true
                    details += "${command.toDisplayText()} is an explicit wipe request from the ROM script"
                }
                is PlanCommand.UpdateSuper -> if (command.wipe) {
                    updateSuperWipe = true
                    details += "${command.toDisplayText()} requests update-super wipe"
                }
                else -> Unit
            }
        }
        return DataImpact(wipesUserdata, wipesMetadata, wipesCache, updateSuperWipe, explicitFastbootWipe, details.distinct())
    }

    fun dataImpactText(plan: ScriptPlan): String = plan.dataImpact.toDisplayText()

    private fun extractExpectedProducts(text: String): List<String> {
        val result = linkedSetOf<String>()
        val directPatterns = listOf(
            Regex("""product:\s*([A-Za-z0-9._-]+)""", RegexOption.IGNORE_CASE),
            Regex("""\^product:\s*([A-Za-z0-9._-]+)""", RegexOption.IGNORE_CASE)
        )
        text.lineSequence()
            .filter { it.contains("product", ignoreCase = true) }
            .forEach { line ->
                directPatterns.forEach { regex ->
                    regex.findAll(line).forEach { match ->
                        match.groupValues.getOrNull(1)?.trim()?.takeIf { token ->
                            token.isNotBlank() && !token.equals("product", ignoreCase = true)
                        }?.let { result += it }
                    }
                }
                if (result.isEmpty() && line.contains("product:", ignoreCase = true)) {
                    val productMatch = Regex("product:", RegexOption.IGNORE_CASE).find(line)
                    val afterProduct = if (productMatch != null) line.substring(productMatch.range.last + 1) else ""
                    val safeTail = afterProduct.substringBefore("||").substringBefore("&&").substringBefore("|")
                    Regex("[A-Za-z0-9._-]+")
                        .findAll(safeTail)
                        .map { it.value.trim() }
                        .filter { token ->
                            token.isNotBlank() &&
                                token != "*" &&
                                !token.equals("product", ignoreCase = true) &&
                                !token.equals("findstr", ignoreCase = true) &&
                                !token.equals("grep", ignoreCase = true) &&
                                !token.equals("exit", ignoreCase = true)
                        }
                        .take(2)
                        .forEach { result += it }
                }
            }
        return result.toList()
    }



    private fun extractAntiRollbackIndexes(text: String): List<Int> {
        val result = linkedSetOf<Int>()
        text.lineSequence()
            .filter { line ->
                line.contains("anti", ignoreCase = true) ||
                    line.contains("rollback", ignoreCase = true)
            }
            .forEach { line ->
                val patterns = listOf(
                    Regex("anti\\s*[:=]\\s*(\\d+)", RegexOption.IGNORE_CASE),
                    Regex("antirollback\\s*[:=]\\s*(\\d+)", RegexOption.IGNORE_CASE),
                    Regex("rollback[_ -]?index\\s*[:=]\\s*(\\d+)", RegexOption.IGNORE_CASE),
                    Regex("findstr\\s+[^\\n]*?anti:\\s*(\\d+)", RegexOption.IGNORE_CASE)
                )
                patterns.forEach { regex ->
                    regex.findAll(line).forEach { match ->
                        match.groupValues.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..99 }?.let { result += it }
                    }
                }
            }
        return result.toList().sorted()
    }

    private fun readArchiveAntiRollbackIndexes(source: File): List<Int> {
        val result = linkedSetOf<Int>()

        fun isCandidate(name: String): Boolean {
            val lower = name.lowercase(Locale.US).substringAfterLast('/')
            return lower.contains("anti") ||
                lower.contains("rollback") ||
                lower == "android-info.txt" ||
                lower == "misc_info.txt"
        }

        fun addText(text: String) {
            extractAntiRollbackIndexes(text).forEach { result += it }
        }

        when {
            source.isDirectory -> source.walkTopDown()
                .filter { it.isFile && isCandidate(it.name) && it.length() in 1..MAX_ANTI_ROLLBACK_TEXT_BYTES }
                .forEach { file -> runCatching { addText(file.readText()) } }
            isZip(source) -> ZipFile(source).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && isCandidate(it.name) && it.size in 1..MAX_ANTI_ROLLBACK_TEXT_BYTES }
                    .forEach { entry ->
                        zip.getInputStream(entry).use { input -> addText(input.readBytes().toString(Charsets.UTF_8)) }
                    }
            }
            isTarLike(source) -> forEachTarEntry(source) { name, size, _, input ->
                if (isCandidate(name) && size in 1..MAX_ANTI_ROLLBACK_TEXT_BYTES) {
                    addText(readExactly(input, size).toString(Charsets.UTF_8))
                    true
                } else {
                    false
                }
            }
        }
        return result.toList().sorted()
    }

    fun maxAntiRollbackIndex(plan: ScriptPlan): Int? = plan.antiRollbackIndexes.maxOrNull()

    private fun parseFastbootArgs(rawArgs: List<String>): PlanCommand? {
        val args = rawArgs
            .map { it.trim().trim('"', '\'') }
            .filter { it.isNotBlank() && it !in setOf("%*", "$@", "$*") }
            .toMutableList()
        if (args.isEmpty()) return null

        args.firstOrNull { it.startsWith("--set-active", ignoreCase = true) }?.let { token ->
            val slot = token.substringAfter("=", "").trim().removePrefix("_")
            if (slot.isNotBlank()) return PlanCommand.SetActive(slot)
        }

        val commandIndex = firstFastbootCommandIndex(args)
        if (commandIndex == null) {
            return if (args.any { it == "-w" || it == "--wipe" }) {
                PlanCommand.WipeData("fastboot -w / --wipe")
            } else {
                null
            }
        }
        val beforeCommand = args.take(commandIndex)
        if (beforeCommand.any { it == "-w" || it == "--wipe" }) {
            // Command-level -w is represented in the data-impact summary; standalone -w becomes WipeData.
        }

        val command = args[commandIndex].lowercase(Locale.US)
        val tail = args.drop(commandIndex + 1).toMutableList()
        stripKnownOptionsInPlace(tail)

        return when {
            command == "flash" -> {
                val cleanTail = tail.filterNot { isFastbootFlagWithoutValue(it) }
                if (cleanTail.size < 2) null else PlanCommand.Flash(
                    partition = normalizePartition(cleanTail[0]),
                    imageRef = normalizeScriptImageRef(cleanTail[1])
                )
            }
            command.startsWith("flash:") -> {
                // Example: fastboot flash:raw boot kernel ramdisk. Multi-file raw flashes are not safe to automate.
                null
            }
            command == "erase" || command == "format" -> {
                val cleanTail = tail.filterNot { isFastbootFlagWithoutValue(it) }
                if (cleanTail.isNotEmpty()) PlanCommand.Erase(normalizePartition(cleanTail[0])) else null
            }
            command == "set_active" || command == "set-active" -> {
                val slot = tail.firstOrNull()?.removePrefix("_")
                if (!slot.isNullOrBlank()) PlanCommand.SetActive(slot) else null
            }
            command == "update-super" -> {
                val imageRef = tail.firstOrNull { !isFastbootFlagWithoutValue(it) && !it.equals("wipe", ignoreCase = true) }
                if (imageRef.isNullOrBlank()) null else PlanCommand.UpdateSuper(
                    imageRef = normalizeScriptImageRef(imageRef),
                    wipe = tail.any { it.equals("wipe", ignoreCase = true) || it == "-w" || it == "--wipe" }
                )
            }
            command == "reboot" -> PlanCommand.Reboot(tail.firstOrNull()?.lowercase(Locale.US))
            command == "reboot-bootloader" -> PlanCommand.Reboot("bootloader")
            command == "reboot-recovery" -> PlanCommand.Reboot("recovery")
            else -> null
        }
    }

    private fun firstFastbootCommandIndex(args: List<String>): Int? {
        var index = 0
        while (index < args.size) {
            val token = args[index].lowercase(Locale.US)
            when {
                token in setOf("-s", "-S", "--slot") -> index += 2
                token.startsWith("--slot=") -> index += 1
                token in setOf("--disable-verity", "--disable-verification", "--force", "-w", "--wipe") -> index += 1
                token.startsWith("--") -> index += 1
                token in FASTBOOT_COMMANDS || token.startsWith("flash:") -> return index
                else -> index += 1
            }
        }
        return null
    }

    private fun stripKnownOptionsInPlace(args: MutableList<String>) {
        var index = 0
        while (index < args.size) {
            val token = args[index]
            val lower = token.lowercase(Locale.US)
            when {
                lower in setOf("-s", "-S", "--slot") -> repeat(minOf(2, args.size - index)) { args.removeAt(index) }
                lower.startsWith("--slot=") -> args.removeAt(index)
                lower == "%*" || lower == "$@" || lower == "$*" -> args.removeAt(index)
                else -> index++
            }
        }
    }

    private fun isFastbootFlagWithoutValue(token: String): Boolean {
        val lower = token.lowercase(Locale.US)
        return lower in setOf("--disable-verity", "--disable-verification", "--force", "-w", "--wipe") || lower.startsWith("--slot=")
    }

    private fun stripControlOperators(line: String): String {
        var result = line
        listOf("2>&1", "2>", "||", "&&", "|", ">").forEach { op ->
            val index = result.indexOf(op)
            if (index >= 0) result = result.substring(0, index)
        }
        return result.trim()
    }

    private fun tokenize(input: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaping = false
        for (ch in input) {
            when {
                escaping -> { current.append(ch); escaping = false }
                ch == '\\' -> current.append(ch)
                quote != null -> if (ch == quote) quote = null else current.append(ch)
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

    private fun readScripts(source: File): Map<String, String> {
        val scripts = linkedMapOf<String, String>()
        when {
            source.isDirectory -> {
                source.walkTopDown()
                    .filter { it.isFile && isFlashScriptName(it.name) && it.length() <= MAX_SCRIPT_BYTES }
                    .forEach { file -> scripts[normalizeArchivePath(file.relativeTo(source).path)] = file.readText() }
            }
            isZip(source) -> ZipFile(source).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && isFlashScriptName(it.name.substringAfterLast('/')) && it.size in 1..MAX_SCRIPT_BYTES }
                    .forEach { entry ->
                        zip.getInputStream(entry).use { input ->
                            scripts[normalizeArchivePath(entry.name)] = input.readBytes().toString(Charsets.UTF_8)
                        }
                    }
            }
            isTarLike(source) -> forEachTarEntry(source) { name, size, _, input ->
                if (isFlashScriptName(name.substringAfterLast('/')) && size in 1..MAX_SCRIPT_BYTES) {
                    val bytes = readExactly(input, size)
                    scripts[normalizeArchivePath(name)] = bytes.toString(Charsets.UTF_8)
                    true
                } else {
                    false
                }
            }
        }
        return scripts
    }

    private fun collectImageManifest(source: File): List<RomImageEntry> {
        val result = mutableListOf<RomImageEntry>()
        fun add(name: String, size: Long) {
            if (isImageLikeName(name)) {
                result += RomImageEntry(normalizeArchivePath(name), size)
            }
        }
        when {
            source.isDirectory -> source.walkTopDown()
                .filter { it.isFile }
                .forEach { file -> add(file.relativeTo(source).path, file.length()) }
            isZip(source) -> ZipFile(source).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory }
                    .forEach { entry -> add(entry.name, entry.size) }
            }
            isTarLike(source) -> forEachTarEntry(source) { name, size, _, _ -> add(name, size); false }
        }
        return result.sortedBy { it.archivePath.lowercase(Locale.US) }
    }

    private fun buildImageIntegrityReport(entries: List<RomImageEntry>): ImageIntegrityReport {
        val zero = entries.filter { it.size == 0L }
        val small = entries.filter { it.size in 1 until MIN_EXPECTED_IMAGE_BYTES }
        val duplicates = entries
            .groupBy { it.baseName }
            .filterValues { it.size > 1 }
            .toSortedMap()
        val splitGroups = entries
            .mapNotNull { entry -> splitImageInfo(entry.archivePath)?.let { info -> info.key to entry } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }
            .toSortedMap()
        val sparseChunks = entries.filter { splitImageInfo(it.archivePath) != null }
        val splitWarnings = buildSplitSequenceWarnings(entries)
        return ImageIntegrityReport(entries, zero, small, duplicates, sparseChunks, splitGroups, splitWarnings)
    }

    private fun matchReferencedImages(
        entries: List<RomImageEntry>,
        refs: List<String>
    ): Map<String, RomImageEntry> {
        val result = linkedMapOf<String, RomImageEntry>()
        refs.forEach { ref ->
            val normalizedRef = normalizeArchivePath(ref)
            val match = entries.firstOrNull { entry ->
                val normalized = normalizeArchivePath(entry.archivePath)
                normalized == normalizedRef ||
                    normalized.endsWith("/$normalizedRef") ||
                    normalized.endsWith("/images/${normalizedRef.substringAfterLast('/')}") ||
                    normalized.endsWith("/${normalizedRef.substringAfterLast('/')}")
            }
            if (match != null) result[ref] = match
        }
        return result
    }

    private fun isImageLikeName(name: String): Boolean {
        val lower = name.lowercase(Locale.US)
        return lower.endsWith(".img") ||
            lower.endsWith(".elf") ||
            lower.endsWith(".mbn") ||
            lower.endsWith(".bin") ||
            splitImageInfo(lower) != null
    }

    private data class SplitImageInfo(val key: String, val index: Int)

    private fun splitImageInfo(path: String): SplitImageInfo? {
        val normalized = normalizeArchivePath(path).lowercase(Locale.US)
        val patterns = listOf(
            Regex("^(.+\\.img_sparsechunk)\\.(\\d+)$"),
            Regex("^(.+\\.sparsechunk)\\.(\\d+)$"),
            Regex("^(.+\\.img)\\.(\\d+)$")
        )
        patterns.forEach { regex ->
            val match = regex.find(normalized) ?: return@forEach
            val index = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return@forEach
            return SplitImageInfo(match.groupValues[1], index)
        }
        return null
    }

    private fun buildSplitSequenceWarnings(entries: List<RomImageEntry>): List<String> {
        val warnings = mutableListOf<String>()
        val grouped = entries.mapNotNull { entry -> splitImageInfo(entry.archivePath)?.let { it to entry } }.groupBy { it.first.key }
        grouped.entries.sortedBy { it.key }.forEach { (key, pairs) ->
            val indexes = pairs.map { it.first.index }.sorted()
            if (indexes.isEmpty()) return@forEach
            if (indexes.first() != 0) warnings += "$key starts at chunk ${indexes.first()}, expected chunk 0"
            val duplicates = indexes.groupBy { it }.filterValues { it.size > 1 }.keys.sorted()
            if (duplicates.isNotEmpty()) warnings += "$key has duplicate chunk indexes: ${duplicates.joinToString(", ")}"
            val unique = indexes.toSet()
            val missing = (indexes.first()..indexes.last()).filterNot { it in unique }
            if (missing.isNotEmpty()) warnings += "$key missing chunk indexes: ${missing.take(20).joinToString(", ")}${if (missing.size > 20) "..." else ""}"
        }
        return warnings.distinct()
    }

    private fun extractReferencedImages(
        source: File,
        refs: List<String>,
        outputDir: File,
        onLog: (String) -> Unit
    ): Map<String, File> {
        val result = linkedMapOf<String, File>()
        val missing = refs.toMutableSet()
        if (refs.isEmpty()) return result

        fun wantedRef(entryName: String): String? {
            val normalized = normalizeArchivePath(entryName)
            return refs.firstOrNull { ref ->
                normalized == ref ||
                    normalized.endsWith("/$ref") ||
                    normalized.endsWith("/images/${ref.substringAfterLast('/')}") ||
                    normalized.endsWith("/${ref.substringAfterLast('/')}")
            }
        }

        fun outputFor(entryName: String, ref: String): File {
            val normalized = normalizeArchivePath(entryName).ifBlank { ref }
            val safeRelative = normalized.split('/').filter { it.isNotBlank() && it != "." && it != ".." }.joinToString(File.separator)
            return File(outputDir, safeRelative)
        }

        fun writeAtomically(out: File, expectedSize: Long, writer: (File) -> Unit) {
            out.parentFile?.mkdirs()
            val tmp = File(out.parentFile ?: outputDir, out.name + ".partial")
            if (tmp.exists()) tmp.delete()
            writer(tmp)
            if (!tmp.exists() || !tmp.isFile) {
                throw IllegalStateException("extraction temp file was not created: ${tmp.absolutePath}")
            }
            if (expectedSize >= 0L && tmp.length() != expectedSize) {
                val actual = tmp.length()
                tmp.delete()
                throw IllegalStateException("extracted size mismatch for ${out.name}: expected=${formatBytes(expectedSize)}, actual=${formatBytes(actual)}")
            }
            if (out.exists() && !out.delete()) {
                tmp.delete()
                throw IllegalStateException("cannot replace old extracted file: ${out.absolutePath}")
            }
            if (!tmp.renameTo(out)) {
                tmp.copyTo(out, overwrite = true)
                tmp.delete()
            }
        }

        when {
            source.isDirectory -> {
                val files = source.walkTopDown().filter { it.isFile }.toList()
                refs.forEach { ref ->
                    val found = files.firstOrNull { file -> wantedRef(file.relativeTo(source).path) == ref }
                    if (found != null) {
                        val out = outputFor(found.relativeTo(source).path, ref)
                        writeAtomically(out, found.length()) { tmp -> found.copyTo(tmp, overwrite = true) }
                        onLog("Extracted: ${out.name} (${formatBytes(out.length())})")
                        result[ref] = out
                        missing.remove(ref)
                    }
                }
            }
            isZip(source) -> ZipFile(source).use { zip ->
                zip.entries().asSequence().filter { !it.isDirectory }.forEach { entry ->
                    val ref = wantedRef(entry.name) ?: return@forEach
                    if (ref !in missing) return@forEach
                    val out = outputFor(entry.name, ref)
                    writeAtomically(out, entry.size) { tmp ->
                        zip.getInputStream(entry).use { input -> tmp.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) } }
                    }
                    onLog("Extracted: ${out.name} (${formatBytes(out.length())})")
                    result[ref] = out
                    missing.remove(ref)
                }
            }
            isTarLike(source) -> forEachTarEntry(source) { name, size, _, input ->
                val ref = wantedRef(name)
                if (ref != null && ref in missing) {
                    val out = outputFor(name, ref)
                    writeAtomically(out, size) { tmp ->
                        tmp.outputStream().use { output -> copyExactly(input, output, size) }
                    }
                    onLog("Extracted: ${out.name} (${formatBytes(out.length())})")
                    result[ref] = out
                    missing.remove(ref)
                    true
                } else {
                    false
                }
            }
        }
        return result
    }

    private fun resetExtractionDirectory(dir: File, onLog: (String) -> Unit) {
        if (dir.exists()) {
            dir.walkBottomUp()
                .filter { it != dir }
                .forEach { file ->
                    if (!file.delete()) {
                        throw IllegalStateException("cannot clean stale extraction file: ${file.absolutePath}")
                    }
                }
            onLog("Cleaned stale extraction directory: ${dir.name}")
        }
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("cannot create extraction directory: ${dir.absolutePath}")
        }
    }

    private fun forEachTarEntry(source: File, onEntry: (name: String, size: Long, type: Char, input: InputStream) -> Boolean) {
        val base = FileInputStream(source)
        val wrapped: InputStream = if (source.name.lowercase(Locale.US).endsWith(".gz") || source.name.lowercase(Locale.US).endsWith(".tgz")) {
            GZIPInputStream(BufferedInputStream(base))
        } else {
            BufferedInputStream(base)
        }
        wrapped.use { input ->
            val header = ByteArray(TAR_BLOCK_SIZE)
            while (true) {
                val read = readFullBlock(input, header)
                if (read == 0) break
                if (read < TAR_BLOCK_SIZE) break
                if (header.all { it.toInt() == 0 }) break

                val name = tarName(header)
                val size = parseTarSize(header)
                val type = header[156].toInt().toChar()
                val regular = type == '0' || type.code == 0
                val consumed = if (regular && name.isNotBlank()) onEntry(name, size, type, input) else false
                if (!consumed) skipExactly(input, size)
                val padding = (TAR_BLOCK_SIZE - (size % TAR_BLOCK_SIZE)) % TAR_BLOCK_SIZE
                if (padding > 0) skipExactly(input, padding)
            }
        }
    }

    private fun readFullBlock(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read <= 0) break
            total += read
        }
        return total
    }

    private fun readExactly(input: InputStream, size: Long): ByteArray {
        if (size > Int.MAX_VALUE) throw IllegalArgumentException("entry too large")
        val bytes = ByteArray(size.toInt())
        var offset = 0
        while (offset < bytes.size) {
            val read = input.read(bytes, offset, bytes.size - offset)
            if (read <= 0) throw IllegalStateException("unexpected EOF")
            offset += read
        }
        return bytes
    }

    private fun copyExactly(input: InputStream, output: java.io.OutputStream, size: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = size
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) throw IllegalStateException("unexpected EOF")
            output.write(buffer, 0, read)
            remaining -= read.toLong()
        }
    }

    private fun skipExactly(input: InputStream, size: Long) {
        var remaining = size
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read <= 0) throw IllegalStateException("unexpected EOF while skipping")
                remaining -= read.toLong()
            }
        }
    }

    private fun tarName(header: ByteArray): String {
        val name = zeroTerminated(header, 0, 100)
        val prefix = zeroTerminated(header, 345, 155)
        return if (prefix.isBlank()) name else "$prefix/$name"
    }

    private fun zeroTerminated(header: ByteArray, start: Int, length: Int): String {
        val end = (start until start + length).firstOrNull { header[it].toInt() == 0 } ?: (start + length)
        return header.copyOfRange(start, end).toString(Charsets.UTF_8).trim()
    }

    private fun parseTarSize(header: ByteArray): Long {
        val raw = zeroTerminated(header, 124, 12).trim()
        return raw.takeWhile { it in '0'..'7' }.ifBlank { "0" }.toLong(8)
    }

    private fun detectFormat(file: File): String = when {
        file.isDirectory -> "directory"
        isZip(file) -> "zip"
        file.name.lowercase(Locale.US).endsWith(".tgz") || file.name.lowercase(Locale.US).endsWith(".tar.gz") -> "tar.gz"
        file.name.lowercase(Locale.US).endsWith(".tar") -> "tar"
        else -> "unknown"
    }

    private fun isZip(file: File): Boolean = file.name.lowercase(Locale.US).endsWith(".zip")
    private fun isTarLike(file: File): Boolean {
        val lower = file.name.lowercase(Locale.US)
        return lower.endsWith(".tgz") || lower.endsWith(".tar.gz") || lower.endsWith(".tar")
    }

    private fun isFlashScriptName(name: String): Boolean {
        val lower = name.lowercase(Locale.US)
        return lower.contains("flash_all") && (lower.endsWith(".bat") || lower.endsWith(".sh"))
    }

    private fun normalizePartition(partition: String): String = partition.trim().trim('"', '\'').lowercase(Locale.US)

    private fun normalizeScriptImageRef(ref: String): String {
        var value = ref.trim().trim('"', '\'')
        value = value.replace("%~dp0", "", ignoreCase = true)
        value = value.replace("%cd%", "", ignoreCase = true)
        value = value.replace("%CURRENT_DIR%", "", ignoreCase = true)
        value = value.replace("%ANDROID_PRODUCT_OUT%", "", ignoreCase = true)
        value = value.replace("\${CURRENT_DIR}", "", ignoreCase = true)
        value = value.replace("\$CURRENT_DIR", "", ignoreCase = true)
        value = value.replace("\$PWD/", "", ignoreCase = true)
        value = value.replace("\\", "/")
        value = value.replace(Regex("%[A-Za-z0-9_]+%/?"), "")
        value = value.replace(Regex("\\$\\{?[A-Za-z0-9_]+}?/?"), "")
        value = value.removePrefix("./").removePrefix("/")
        while (value.contains("//")) value = value.replace("//", "/")
        return value
    }

    private fun normalizeArchivePath(path: String): String {
        var value = path.trim().trim('"', '\'').replace("\\", "/")
        value = value.replace("%~dp0", "", ignoreCase = true)
        value = value.removePrefix("./").removePrefix("/")
        while (value.contains("//")) value = value.replace("//", "/")
        return value
    }

    private fun safeBaseName(name: String): String = name
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .take(80)
        .ifBlank { "rom-${System.currentTimeMillis()}" }

    private fun sourceFingerprint(source: File): String {
        fun File.treeStats(): Pair<Long, Long> {
            if (isFile) return length() to lastModified()
            if (!isDirectory) return 0L to 0L
            var bytes = 0L
            var modified = lastModified()
            walkTopDown().filter { it.isFile }.forEach { file ->
                bytes += file.length().coerceAtLeast(0L)
                modified = maxOf(modified, file.lastModified())
            }
            return bytes to modified
        }
        val (bytes, modified) = source.treeStats()
        return "${bytes.toString(16)}-${modified.toString(16)}"
    }

    private fun isLockLine(args: List<String>): Boolean {
        val joined = args.joinToString(" ").lowercase(Locale.US)
        return joined.contains("flashing lock") || joined.contains("oem lock") || joined.contains("lock_critical") || joined.contains("lock critical")
    }

    private fun looksLikeFastbootCommand(args: List<String>): Boolean {
        val first = args.firstOrNull()?.lowercase(Locale.US) ?: return false
        return first !in setOf("getvar", "devices")
    }

    private fun isDataPartition(partition: String): Boolean {
        val clean = partition.lowercase(Locale.US).removeSuffix("_a").removeSuffix("_b")
        return clean in setOf("userdata", "data", "metadata")
    }

    private fun isCriticalFirmwarePartition(partition: String): Boolean {
        val clean = partition.lowercase(Locale.US).removeSuffix("_ab").removeSuffix("_a").removeSuffix("_b")
        return clean in CRITICAL_FIRMWARE_PARTITIONS
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes.toDouble() / 1024.0 / 1024.0
        return "%.2f MB".format(Locale.US, mb)
    }

    private val FASTBOOT_COMMANDS = setOf(
        "flash", "erase", "format", "set_active", "set-active", "update-super",
        "reboot", "reboot-bootloader", "reboot-recovery"
    )

    private val LOGICAL_DYNAMIC_PARTITIONS = setOf(
        "system", "system_ext", "vendor", "product", "odm",
        "vendor_dlkm", "odm_dlkm", "product_dlkm", "system_dlkm",
        "mi_ext", "cust", "my_product", "my_company", "my_region"
    )

    private val CRITICAL_FIRMWARE_PARTITIONS = setOf(
        "abl", "ablbak", "xbl", "xblbak", "xbl_config", "xbl_configbak",
        "tz", "tzbak", "hyp", "hypbak", "devcfg", "devcfgbak",
        "cmnlib", "cmnlibbak", "cmnlib64", "cmnlib64bak",
        "keymaster", "keymasterbak", "qupfw", "qupfwbak",
        "uefisecapp", "uefisecappbak", "rpm", "rpmbak",
        "modem", "bluetooth", "dsp", "aop", "aopbak", "multiimgoem", "multiimgqti"
    )

    private const val TAR_BLOCK_SIZE = 512
    private const val MAX_SCRIPT_BYTES = 1024L * 1024L
    private const val MAX_ANTI_ROLLBACK_TEXT_BYTES = 64L * 1024L
    private const val MIN_EXPECTED_IMAGE_BYTES = 512L
    private const val WORKSPACE_HEADROOM_BYTES = 512L * 1024L * 1024L
    private const val LARGE_IMAGE_WARNING_BYTES = 3L * 1024L * 1024L * 1024L
}
