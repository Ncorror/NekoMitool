# ADB install-multiple patch notes — v2.1.6

## Что добавлено

- Поддержана терминальная команда:

```text
adb install-multiple [-r] [-d] [-g] <base.apk> <split1.apk> [split2.apk...]
```

- Установка split APK реализована через package-manager session API:

```text
pm install-create <options>
pm install-write -S <size> <sessionId> <splitName> <remotePath>
pm install-commit <sessionId>
```

- Все APK сначала передаются через ADB sync в `/data/local/tmp`.
- После установки временные APK удаляются.
- При ошибке записи или commit вызывается `pm install-abandon <sessionId>`.
- Для извлечения `sessionId` добавлен внутренний shell-result runner, который собирает stdout/stderr и exit-code.

## Ограничения

- `.apks` / `.xapk` контейнеры пока не распаковываются автоматически. Нужно передать уже извлечённые APK-файлы.
- Для legacy shell нет точного exit-code, но stdout захватывается и используется для извлечения session id.
- Порядок файлов задаёт пользователь. Желательно передавать `base.apk` первым.

## Проверка

- `AdbProtocol.kt`, `AdbKeyStore.kt`, `HashUtils.kt` проверены через `kotlinc` с USB-заглушками.
- XML-ресурсы проверены на парсинг.
- ZIP-архив проверяется через `unzip -t`.
