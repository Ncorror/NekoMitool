# ADB Package Containers Patch Notes — v2.1.7-package-containers

## Что добавлено

- `adb install <file.apks>` теперь обрабатывает APK Set Archive как ZIP-контейнер.
- `adb install <file.xapk>` теперь обрабатывает XAPK как ZIP-контейнер.
- Из контейнера извлекаются APK-файлы во временную внутреннюю папку приложения.
- Выбор APK выполняется автоматически:
  1. если найден `universal.apk`, используется одиночная установка;
  2. если найден split-набор с base APK, используется `adb install-multiple`;
  3. если найден один standalone APK, используется одичная установка;
  4. если структура неоднозначна, приложение предупреждает и пробует установить все APK как split-набор.
- Для XAPK добавлена базовая поддержка OBB:
  - OBB-файлы ищутся внутри контейнера;
  - package name определяется из пути `Android/obb/<package>/...` или из `manifest.json` (`package_name`, `packageName`, `package`);
  - OBB отправляются в `/sdcard/Android/obb/<package>/` через `adb push` после успешной установки APK.
- Добавлена защита от zip-slip путей внутри контейнеров (`../`, абсолютные пути, NUL).
- Временные распакованные файлы удаляются после установки.

## Ограничения

- Автоматический выбор split APK не использует device-spec от bundletool. Если `.apks` содержит много standalone-вариантов, автоматически выбирается первый standalone APK с предупреждением.
- Для точной установки сложных `.apks` с несколькими ABI/density/language вариантами лучше распаковать архив и вызвать `adb install-multiple` вручную с нужным набором APK.
- XAPK OBB поддерживаются только если package name можно определить по пути или `manifest.json`.
- APKM, AAB и streaming install пока не реализованы.

## Версия

- `versionCode 20`
- `versionName 2.1.7-package-containers`
