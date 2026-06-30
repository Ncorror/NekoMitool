# Источники и идеи NekoMiFlash — черновик для атрибуции

Этот документ — рабочий черновик для будущего LICENSE/NOTICE.
Здесь собрано всё, что использовано из внешних источников: спецификации,
протоколы, референсы интерфейса. Автор проекта: Ncorror.

ВАЖНО: это черновик для памяти, не финальная юридическая атрибуция.
Перед публикацией лицензии — свериться со специалистом.

## 1. Протоколы и спецификации (технические источники)

### ADB-протокол — AOSP (Android Open Source Project)
- Файл: `AdbProtocol.kt`, `AdbKeyStore.kt`
- Что использовано: команды протокола ADB (A_CNXN, A_OPEN, A_OKAY, A_CLSE,
  A_WRTE, A_AUTH), типы авторизации (AUTH_TOKEN/SIGNATURE/RSAPUBLICKEY),
  схема RSA-подписи токена (adb_auth_sign → RSA_sign NID_sha1).
- Источник: AOSP `system/core/adb` — публичная спецификация протокола.
- Лицензия AOSP: Apache License 2.0.
- В коде уже есть прямая ссылка-комментарий на `adb_auth_sign()` (AdbKeyStore.kt:64).
- АТРИБУЦИЯ НУЖНА: да — протокол и схема подписи взяты из AOSP.

### Fastboot-протокол — AOSP
- Файл: `FastbootProtocol.kt`
- Что использовано: команды fastboot (getvar, flash, reboot), формат
  OKAY/FAIL/INFO/DATA-ответов, getvar-переменные (slot-count, current-slot,
  max-download-size, unlocked, secure и т.д.).
- Источник: AOSP fastboot protocol (`bootloader/legacy/fastboot_protocol.txt`).
- Лицензия: Apache License 2.0.
- АТРИБУЦИЯ НУЖНА: да.

### Android Verified Boot (AVB) / vbmeta — AOSP avbtool
- Файл: `ImageInspector.kt`, vbmeta-off в `MainActivity.kt`
- Что использовано: формат заголовка vbmeta (магия AVB0), расположение
  флагов verity/verification (offset 120), логика disable-verity/
  disable-verification (значения флагов 0x1 / 0x2 / 0x3).
- Источник: AOSP `external/avb` (avbtool, libavb) — спецификация AVB.
- Лицензия: Apache License 2.0 / BSD.
- АТРИБУЦИЯ НУЖНА: да — патч AVB-заголовка основан на спецификации avbtool.

### Форматы образов — публичные спецификации
- Файл: `ImageInspector.kt`
- Что использовано: магические сигнатуры boot.img (ANDROID!), vendor_boot
  (VNDRBOOT), Android sparse image (0xED26FF3A), ELF и др.
- Источник: AOSP bootimg format, sparse image format — публичные форматы.
- АТРИБУЦИЯ НУЖНА: желательно (форматы публичные, но истоки в AOSP).

## 2. Идеи интерфейса (UX-референсы)

### Xiaomi Flash Master — референс
- Где: диалог прошивки (зелёная иконка recovery), выбор слота A/B.
- Что взято: ИДЕЯ выделять recovery ярко-зелёным в диалоге выбора
  (по аналогии с утилитой Xiaomi Flash Master). Только идея/вдохновение,
  не код и не графика.
- АТРИБУЦИЯ: упомянуть как источник вдохновения (inspiration), не более.

### OrangeFox Recovery — ранний UI-референс
- Где: ранние версии дизайна (журнал упоминает "OrangeFox style").
- Статус: позже заменён на собственный монохромный neko-стиль.
  В текущей версии прямых заимствований не осталось.
- АТРИБУЦИЯ: исторически упоминалось, в текущем коде следов нет.

## 3. Сторонние библиотеки (зависимости)

Все — стандартные, с открытыми лицензиями (указываются в NOTICE автоматически):
- androidx.* (core-ktx, appcompat, activity-ktx, lifecycle, palette) — Apache 2.0
- com.google.android.material — Apache 2.0
- kotlinx-coroutines-android — Apache 2.0

АТРИБУЦИЯ: стандартная, через NOTICE-файл со списком зависимостей.

## 4. Изображения (айдентика)

### Иконка приложения
- Создана: с помощью ChatGPT (OpenAI), скомпонована из двух изображений,
  доработана автором (Ncorror).
- Права: OpenAI передаёт права на output пользователю (ToS). Творческий
  вклад автора — компоновка и доработка.
- Статус: можно использовать коммерчески, пометить © 2026 Ncorror.

### Welcome-экран (фоновое изображение)
- Создано: с помощью ChatGPT (OpenAI) с нуля, долгая ручная доработка/подбор.
- Права: аналогично иконке.
- Статус: © 2026 Ncorror (с пометкой "создано с использованием ИИ").

## 5. Статус проверки (выполнено)

- [x] Проверка на заимствование чужого кода — выполнена grep-поиском по
      начальной копии (v2.2.7) и текущей версии. Следов termux-api,
      MiTool, vendor-adb-patched НЕ найдено: нет license-файлов, нет
      упоминаний, нет характерных механизмов (TermuxApiReceiver, сокеты,
      encryptData и т.п.). Заимствованы только идеи.
- [x] USB Host API / OTG — собственная реализация на нативном Android API
      (UsbManager / UsbDeviceConnection / bulkTransfer), не из примеров
      сторонних проектов.
- [x] RSA-ключи ADB — собственная реализация на базе спецификации AOSP
      adb_auth_sign(), чужой готовый класс не использован.
- [x] Лицензии источников уточнены: termux-api — GPLv3 (только идея, кода
      нет → обязательств нет), vendor-adb-patched — Apache 2.0,
      MiTool (offici5l) — Apache 2.0 (подтверждено на странице репозитория).
- [x] Картинки: ChatGPT (OpenAI) + ручная доработка. ToS OpenAI передаёт
      права на output пользователю. Отмечены как © 2026 Ncorror.

## Итоговое решение (принято)

Проект выпущен под **Apache License 2.0** (файл LICENSE в корне).
Атрибуция оформлена в файле **NOTICE**:
- AOSP (ADB/Fastboot/AVB протоколы) — Apache 2.0, обязательная атрибуция;
- Acknowledgements (идеи): termux-adb, MiTool, Xiaomi Flash Master;
- библиотеки androidx / material / kotlin — Apache 2.0;
- изображения © 2026 Ncorror — все права защищены, НЕ под Apache 2.0,
  переиспользование не разрешено (при форке заменять своими).

Поскольку GPLv3-код (termux-api) НЕ копировался, проект не связан
обязательством GPLv3 и свободно выпускается под Apache 2.0.

## Предварительный вывод

Основной технический фундамент (ADB/Fastboot/AVB протоколы) — из AOSP под
Apache 2.0. Это значит:
- Можно свободно использовать (Apache 2.0 разрешает).
- Нужно сохранить уведомление об авторских правах AOSP и указать изменения.
- Логично и проект выпустить под Apache 2.0 (совместимо).

Идеи UI (Xiaomi Flash Master) — только вдохновение, достаточно упоминания.
Изображения — ChatGPT + доработка Ncorror, права чистые.
