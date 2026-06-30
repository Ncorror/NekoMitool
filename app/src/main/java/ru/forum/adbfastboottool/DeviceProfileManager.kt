package ru.forum.adbfastboottool

import org.json.JSONObject
import java.io.File

/**
 * Опциональные профили безопасности по устройствам (JSON в /sdcard/Download/NekoFlash/profiles/).
 *
 * FIX #13: findProfile теперь ищет сначала точное совпадение поля "product" внутри JSON
 * (primary key), а не по имени файла. Файл alioth_global.json с product=alioth
 * будет найден корректно, файл с другим product — нет.
 */
object DeviceProfileManager {

    data class DeviceProfile(
        val file: File,
        val product: String,
        val allowedPartitions: Set<String>,
        val notes: String?
    )

    fun ensureProfilesDirectory(workspacePath: File, onLog: (String) -> Unit) {
        val profilesDir = File(workspacePath, "profiles")
        if (!profilesDir.exists() && !profilesDir.mkdirs()) {
            onLog("⚠️ Не удалось создать папку профилей: ${profilesDir.absolutePath}")
            return
        }

        val sample = File(profilesDir, "sample-profile.json")
        if (!sample.exists()) {
            try {
                sample.writeText(
                    """
                    {
                      "product": "example_product",
                      "allowed_partitions": ["boot", "init_boot", "vendor_boot", "recovery", "dtbo", "vbmeta"],
                      "notes": "Скопируйте файл, переименуйте под модель и замените product на значение из getvar:product. vbmeta добавляйте только если инструкция по вашей модели явно требует."
                    }
                    """.trimIndent()
                )
                onLog("Создан пример профиля: /sdcard/Download/NekoFlash/profiles/sample-profile.json")
            } catch (e: Exception) {
                onLog("⚠️ Не удалось создать пример профиля: ${e.message}")
            }
        }
    }

    fun checkPartition(
        profilesDir: File?,
        product: String?,
        partition: String,
        onLog: (String) -> Unit
    ) {
        if (profilesDir == null || !profilesDir.exists()) {
            onLog("Профили устройств: папка profiles не найдена, проверка пропущена")
            return
        }
        if (product.isNullOrBlank()) {
            onLog("Профили устройств: product неизвестен, проверка пропущена")
            return
        }

        val profile = findProfile(profilesDir, product)
        if (profile == null) {
            onLog("Профиль для product=$product не найден")
            onLog("💡 Создайте /sdcard/Download/NekoFlash/profiles/$product.json с allowed_partitions")
            return
        }

        onLog("Профиль устройства: ${profile.file.name}")
        profile.notes?.takeIf { it.isNotBlank() }?.let { onLog("Профиль/заметка: $it") }

        if (profile.allowedPartitions.isEmpty()) {
            onLog("⚠️ В профиле нет allowed_partitions. Проверка раздела не выполнена.")
            return
        }

        if (partition.lowercase() in profile.allowedPartitions) {
            onLog("✅ Профиль: раздел $partition разрешён для product=$product")
        } else {
            onLog("⚠️ ВНИМАНИЕ: раздел $partition не указан в allowed_partitions профиля product=$product")
            onLog("⚠️ Прошивка не заблокирована. Проверьте файл и раздел вручную.")
        }
    }

    /**
     * FIX #13: PRIMARY KEY — поле "product" внутри JSON, не имя файла.
     *
     * Алгоритм:
     * 1. Читаем все JSON-файлы в папке profiles.
     * 2. Для каждого парсим поле "product".
     * 3. Ищем точное совпадение (case-insensitive) с запрошенным product.
     * 4. Если несколько файлов имеют одинаковый product — берём тот, имя которого
     *    совпадает с product (для удобства). Иначе первый по алфавиту.
     *
     * Ранняя реализация sortedByDescending по имени файла давала случайный результат
     * при alioth.json + alioth_global.json: оба проходили фильтр имени.
     */
    fun findProfile(profilesDir: File, product: String): DeviceProfile? {
        val normalizedProduct = product.trim().lowercase()

        val allProfiles = profilesDir
            .listFiles { f -> f.isFile && f.name.lowercase().endsWith(".json") }
            ?.sortedBy { it.name.lowercase() }
            ?.mapNotNull { parseProfile(it) }
            ?: return null

        // Точное совпадение по полю product (первичный ключ)
        val exactMatches = allProfiles.filter {
            it.product.trim().lowercase() == normalizedProduct
        }
        if (exactMatches.isEmpty()) return null

        // Среди совпадений предпочитаем файл с именем == product
        return exactMatches.firstOrNull {
            it.file.nameWithoutExtension.lowercase() == normalizedProduct
        } ?: exactMatches.first()
    }

    private fun parseProfile(file: File): DeviceProfile? {
        return try {
            val json = JSONObject(file.readText())
            val product = json.optString("product", "").trim()
            if (product.isBlank()) return null

            val array = json.optJSONArray("allowed_partitions")
            val partitions = mutableSetOf<String>()
            if (array != null) {
                for (i in 0 until array.length()) {
                    val value = array.optString(i, "").trim().lowercase()
                    if (value.isNotBlank()) partitions.add(value)
                }
            }
            DeviceProfile(
                file = file,
                product = product,
                allowedPartitions = partitions,
                notes = if (json.has("notes")) json.optString("notes") else null
            )
        } catch (_: Exception) {
            null
        }
    }
}
