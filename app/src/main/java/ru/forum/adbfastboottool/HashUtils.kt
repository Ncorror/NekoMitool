package ru.forum.adbfastboottool

import java.io.File
import java.security.MessageDigest

/**
 * Проверка контрольных сумм.
 * FIX #6: SHA-256 и MD5 считаются за ОДИН проход файла через DigestInputStream,
 * а не двумя отдельными проходами. Для файла 200 MB экономит ~10-20 сек.
 */
object HashUtils {
    private const val BUFFER_SIZE = 1024 * 1024

    data class FileHashes(val sha256: String, val md5: String)

    /** Считает SHA-256 и MD5 за один проход. */
    fun calculateHashes(file: File): FileHashes {
        val sha256 = MessageDigest.getInstance("SHA-256")
        val md5    = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(BUFFER_SIZE)
        file.inputStream().use { stream ->
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                sha256.update(buffer, 0, read)
                md5.update(buffer, 0, read)
            }
        }
        return FileHashes(
            sha256 = sha256.digest().joinToString("") { "%02x".format(it) },
            md5    = md5.digest().joinToString("") { "%02x".format(it) }
        )
    }

    /** Обратная совместимость — один алгоритм. */
    fun calculateDigest(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        file.inputStream().use { stream ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Проверяет файл по sidecar-файлам .sha256 и .md5.
     * Считает оба хэша за один проход, затем сверяет с sidecar-файлами.
     */
    fun verifyFileWithSidecars(file: File, onLog: (String) -> Unit): Boolean {
        if (!file.exists() || !file.isFile || !file.canRead()) {
            onLog("❌ ОШИБКА: файл недоступен: ${file.name}")
            return false
        }
        if (file.length() <= 0L) {
            onLog("❌ ОШИБКА: файл пустой: ${file.name}")
            return false
        }

        val sha256Sidecar = findSidecar(file, "sha256")
        val md5Sidecar    = findSidecar(file, "md5")

        // Если нет ни одного sidecar — пропускаем проверку
        if (sha256Sidecar == null && md5Sidecar == null) {
            onLog("ℹ️ Файлы .sha256 и .md5 не найдены. Проверка контрольных сумм пропущена.")
            return true
        }

        onLog("=== ПРОВЕРКА КОНТРОЛЬНЫХ СУММ ===")
        onLog("Файл: ${file.name}")
        onLog("Вычисление SHA-256 и MD5 (один проход)...")

        val hashes = calculateHashes(file)
        onLog("SHA-256: ${hashes.sha256}")
        onLog("MD5: ${hashes.md5}")

        if (sha256Sidecar != null) {
            if (!verifySidecar(sha256Sidecar, "sha256", 64, hashes.sha256, onLog)) return false
        } else {
            onLog("ℹ️ Файл .sha256 не найден. Проверка SHA-256 пропущена.")
        }

        if (md5Sidecar != null) {
            if (!verifySidecar(md5Sidecar, "md5", 32, hashes.md5, onLog)) return false
        } else {
            onLog("ℹ️ Файл .md5 не найден. Проверка MD5 пропущена.")
        }

        return true
    }

    private fun verifySidecar(
        sidecar: File,
        extension: String,
        hashLength: Int,
        actual: String,
        onLog: (String) -> Unit
    ): Boolean {
        val expected = extractHash(sidecar.readText(), hashLength)
        if (expected == null) {
            onLog("❌ ОШИБКА: не удалось прочитать хэш из ${sidecar.name}")
            return false
        }
        return if (actual.equals(expected, ignoreCase = true)) {
            onLog("✅ ${extension.uppercase()} совпадает: ${sidecar.name}")
            true
        } else {
            onLog("❌ ОШИБКА: ${extension.uppercase()} не совпадает!")
            onLog("Ожидалось: $expected")
            onLog("Получено:  $actual")
            false
        }
    }

    private fun findSidecar(file: File, extension: String): File? {
        val parent = file.parentFile ?: return null
        val candidates = listOf(
            File(file.absolutePath + ".$extension"),
            File(parent, file.nameWithoutExtension + ".$extension")
        )
        return candidates.distinctBy { it.absolutePath }
            .firstOrNull { it.exists() && it.isFile && it.canRead() }
    }

    private fun extractHash(text: String, length: Int): String? =
        Regex("[A-Fa-f0-9]{$length}").find(text)?.value
}
