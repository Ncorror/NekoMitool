# NekoFlash 2.4.1 — Xiaomi Fastboot ROM hardening

Версия 2.4.1 усиливает первый модуль Xiaomi Fastboot ROM Flasher без добавления неофициальной Xiaomi Account-разблокировки.

## Изменения

- Версия обновлена до `versionCode 34`, `versionName 2.4.1-nekoflash`.
- Улучшен парсер реальных Xiaomi/Redmi/POCO `flash_all`-скриптов:
  - `%CURRENT_DIR%`;
  - `%ANDROID_PRODUCT_OUT%`;
  - `$CURRENT_DIR` / `${CURRENT_DIR}`;
  - `-S 256M`;
  - `--disable-verity`;
  - `--disable-verification`;
  - `--slot` / `--slot=...`;
  - `%fastboot%` / wrapper-переменные.
- Добавлено распознавание `update-super` как отдельного сценария, который пока блокируется для автоматического выполнения.
- Улучшена нормализация путей к образам внутри ROM.
- Исправлена обработка control-operator строк вида `2>&1 | findstr ...`.
- Анализ ROM теперь сохраняет отдельный TXT-отчёт в `reports/xiaomi-rom-analysis-*.txt`.

## Политика безопасности

Автоматически не выполняются:

- `flash_all_lock`;
- `fastboot flashing lock`;
- `fastboot oem lock`;
- `lock critical` / `lock_critical`;
- неизвестные fastboot-строки;
- `update-super` до отдельной реализации fastbootd-safe flow.

## Проверка

- `XiaomiFastbootRomManager.kt` компилируется через `kotlinc` как pure Kotlin-модуль.
- Тестовый ROM ZIP с `flash_all.bat`, `%CURRENT_DIR%`, `-S`, `--disable-verity`, `--disable-verification` корректно анализируется и строит план.
