# ADB Shell v2 patch notes — v2.1.5

## Что добавлено

- Добавлена поддержка ADB Shell v2 protocol для `adb shell <command>`.
- При подключении ADB теперь читается CNXN banner и извлекается список `features=`.
- Если target-устройство заявляет `shell_v2`, shell-команды запускаются через service:

```text
shell,v2,raw:<command>
```

- Вывод Shell v2 разбирается как отдельные пакеты:
  - `stdout` — обычный вывод;
  - `stderr` — ошибки, логируются с префиксом `stderr:`;
  - `exit` — код завершения команды.
- Для one-shot shell-команд приложение отправляет `CloseStdin`, чтобы команды не зависали в ожидании ввода.
- Если `shell_v2` не заявлен или открыть v2-stream не удалось, выполняется fallback на legacy `shell:<command>`.
- `adb install` теперь использует `runShellCommand()` после временного `adb push`, поэтому на устройствах с `shell_v2` результат `pm install` оценивается по реальному exit-code.

## Что изменено в маршрутизации терминала

- `adb shell ...` больше не отправляется напрямую как legacy `shell:` service.
- Команды без явного `shell`, которые фактически являются shell-командами (`pm`, `cmd`, `settings`, `getprop`, `dumpsys`, `logcat`, `ls`, `cat`, `sh`, `su` и др.), тоже идут через новый shell runner.
- `adb raw <service>` / `adb service <service>` оставлены без изменений: это прямой доступ к ADB service для экспертов.

## Ограничения

- Полноценный интерактивный shell с вводом с клавиатуры пока не реализован; пустой `adb shell` открывается legacy-режимом.
- Shell v2 используется только если target adbd объявил feature `shell_v2` в CNXN banner.
- Для старых recovery/minadbd возможен только legacy shell/service без exit-code.

## Проверки

- `AdbProtocol.kt`, `AdbKeyStore.kt`, `HashUtils.kt` компилируются через `kotlinc` с USB-заглушками.
- XML-ресурсы валидны.
- `values` и `values-ru` синхронизированы по строковым ключам.
- ZIP-архив проходит `unzip -t`.

## Версия

- `versionCode 18`
- `versionName "2.1.5-adb-shell-v2"`
