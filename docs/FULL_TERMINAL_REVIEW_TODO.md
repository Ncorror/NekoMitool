# Full terminal review / next improvements

## Проверено в этом патче

- `fastboot flash <partition> <file.img>` блокируется, если `getvar:unlocked` возвращает `no`.
- Raw-обход `flash:<partition>` блокируется в `FastbootProtocol.sendCommand()`.
- `downloadAndRun()` не передаёт файл в download-буфер, если последующая команда является `flash:*` и bootloader locked.
- UI-кнопки одиночной прошивки и очереди показывают блокирующий диалог, если в кэше диагностики уже есть `unlocked=no`.
- XML-ресурсы успешно распарсены.
- ZIP-архив проходит `unzip -t`.

## Ограничение проверки

Полная Gradle-сборка в текущей среде не выполнена: в исходном архиве нет `gradle/wrapper/gradle-wrapper.jar`, а системный Gradle не установлен.

## Что нужно доработать дальше

1. Добавить полноценный Gradle Wrapper (`gradle-wrapper.jar`) и CI-сборку APK.
2. Реализовать нормальную ADB-авторизацию RSA: генерация/хранение ключа, подпись AUTH TOKEN, отправка public key в формате ADB. **Сделано в v2.1.3, нужна проверка на реальном устройстве.**
3. Добавить ADB sync-протокол: `adb push`, `adb pull`, `adb install` через `pm install`/streaming. **Базовый push/pull/install одного APK сделан в v2.1.4, нужна проверка на реальном устройстве.**
4. Добавить отдельную обработку `fastbootd` и dynamic partitions: `super`, logical partitions, `resize-logical-partition`, `delete-logical-partition`.
5. Добавить smart-confirm для разрушительных команд `erase`, `format`, `flashing unlock`, `oem unlock`, `set_active`, но не превращать это в whitelist.
6. Добавить журнал с export/share: команда, время, режим, product, serial, unlocked, результат.
7. Добавить dry-run режим для очереди прошивки: проверить файлы, размеры, partition-name, slot, checksum без записи.
8. Добавить поддержку `.zip` fastboot factory image по манифесту/скрипту, но без слепого выполнения shell/bat.

## Дополнительно проверено в v2.1.3

- `AdbKeyStore.kt` компилируется отдельно через `kotlinc`.
- Тестовая генерация RSA-2048 ключа создаёт подпись длиной 256 байт и ADB public key payload с NUL-терминатором.
- Подпись проверена локально: после RSA/PKCS#1 раскрытия получается DigestInfo(SHA1) + исходный 20-байтный token.


## Дополнительно проверено в v2.1.4

- Добавлен ADB stream `sync:` с обработкой `A_OPEN/A_OKAY/A_WRTE/A_CLSE`.
- Реализованы sync-команды `SEND`, `DATA`, `DONE`, `RECV`, `OKAY`, `FAIL`.
- `adb push` и `adb pull` добавлены в терминальный парсер и ViewModel.
- `adb install` использует временный push в `/data/local/tmp` и `pm install`.
- Остаются следующие задачи: shell v2 для точного exit-code, `STAT` перед pull, `install-multiple`, recursive sync каталогов.


## v2.1.5 update

Shell v2 для one-shot `adb shell <command>` реализован. Осталось: интерактивный shell с stdin, install-multiple/split APK, ADB STAT для pull-прогресса, рекурсивный sync каталогов, fastbootd/dynamic partitions.

## v2.1.6 update

Done:
- `adb install-multiple` for split APK via package-manager sessions.
- Internal shell result collection for parsing `pm install-create` output.

Still recommended:
- `.apks` / `.xapk` archive import and automatic extraction. **Базово сделано в v2.1.7: APK extraction + install, XAPK OBB push при найденном package name. Нужна проверка на реальных APK Set/XAPK.**
- `adb install-multi-package` for atomic installation of several independent packages.
- Recursive directory push/pull.
- Fastbootd / dynamic partitions.

## v2.1.7 update

Done:
- `adb install <file.apks|file.xapk>` with ZIP container extraction.
- Automatic APK selection: `universal.apk`, split set with base, standalone fallback.
- Basic XAPK OBB push to `/sdcard/Android/obb/<package>/`.
- Zip-slip path guard for archive entries.

Still recommended:
- Device-spec based `.apks` split selection instead of heuristic selection.
- APKM support.
- Recursive directory push/pull.
- Interactive shell.
- Fastbootd / dynamic partitions.

## v2.1.8 update

Done:
- ADB sync `STAT` before `pull`.
- Exact file progress for `adb pull` based on remote size.
- Recursive `adb pull <remote-dir> [local-dir]` through shell `find` + sync `RECV` per file.
- Recursive `adb push <local-dir> <remote-dir>` through shell `mkdir -p` + sync `SEND` per file.

Still recommended:
- Sync v2 / `LSTAT2` support for 64-bit sizes and richer metadata.
- More robust recursive listing via NUL-delimited output for unusual filenames.
- Ctrl+C / interrupt packet for interactive shell.
- Fastbootd / dynamic partitions.


## v2.1.9 update

Done:
- Interactive `adb shell` session for `adb shell` without command.
- Shell v2 interactive mode through `shell,v2,pty:` when target declares `shell_v2`.
- Legacy `shell:` fallback for old targets/recovery environments.
- Stdin queue handled inside the same ADB loop to avoid concurrent USB readers.
- UI routing: while interactive shell is open, console input is sent to shell stdin; `adb shell-stop`, `adb shell-exit`, `:close`, `:exit` close the session.
- Import allow-list expanded to `.apk`, `.apks`, `.xapk`, `.obb`.

Still recommended:
- Add Ctrl+C / SIGINT support.
- Add resize-aware terminal UI and optional prompt/state indicator.
- Add session transcript export separate from global log.
- Fastbootd / dynamic partitions.


## v2.2.0 interactive shell controls

- Добавлено прерывание foreground-процесса в интерактивном `adb shell` через `:ctrl-c`, `:sigint`, `:interrupt`, `adb shell-ctrl-c`, `adb shell-interrupt`.
- Добавлен EOF через `:ctrl-d`, `:eof`, `adb shell-ctrl-d`, `adb shell-eof`.
- Следующий приоритет: fastbootd/dynamic partitions и поддержка logical partitions.

## v2.2.1 fastbootd/dynamic partitions review

Готово:

- Диагностика `is-userspace`, `super-partition-name`, `max-fetch-size`.
- Команды `logical-info` / `is-logical`.
- Управление logical partitions через wire-команды с двоеточиями.
- `update-super` через download + `update-super:<super>[:wipe]`.
- `fastboot fetch` с chunk-режимом и `.part` output.

Что ещё проверить на реальном устройстве:

- Pixel/GSIs: `fastboot reboot fastboot`, затем `logical-info system_a`, `fetch vendor_boot`.
- Поведение `update-super` на empty-super image, созданном lpmake.
- Разные ответы OEM на `getvar:is-userspace` и `max-fetch-size`.
- Нужно ли добавить отдельную UI-кнопку «Перейти в fastbootd» рядом с reboot bootloader/recovery.

## v2.2.2 — Build / CI status

- Добавлен fallback в `gradlew` / `gradlew.bat`: официальный wrapper jar используется при наличии, иначе запускается Gradle из `PATH`.
- GitHub Actions `build.yml` теперь ставит Gradle 8.4 и собирает APK даже до добавления `gradle-wrapper.jar`.
- Workflow `add-wrapper.yml` оставлен как правильный путь для добавления официального wrapper jar в репозиторий.
- Добавлены локальные сборочные скрипты и `scripts/check_project.py`.

Остаётся после v2.2.2:

1. Запустить workflow **«Установка Gradle Wrapper»** в GitHub и закоммитить официальный `gradle-wrapper.jar`.
2. Запустить `Build Android APK` в GitHub Actions и проверить итоговый APK на реальном устройстве.
3. После первой успешной сборки добавить smoke-test checklist для ADB/Fastboot сценариев.


## v2.2.3 self-test / smoke-test

Done:

- Добавлена команда `self-test` / `smoke-test` / `doctor` без префикса.
- Добавлены `adb self-test` и `fastboot self-test`.
- Добавлена кнопка `Self-test / Smoke-test` на сервисной странице.
- ADB self-test проверяет banner/features, shell_v2, shell, getprop, id, package manager, exit-code probe и sync STAT.
- Fastboot self-test проверяет безопасные getvar, режим fastbootd, max-download-size, max-fetch-size и guard-состояние для locked bootloader.

Still recommended:

- После сборки APK прогнать self-test на трёх режимах: обычный Android ADB, recovery ADB/sideload, bootloader fastboot/fastbootd.
- Добавить экспорт self-test отчёта отдельным файлом рядом с forum report.
- Добавить UI-индикатор результата self-test: pass/warn/fail.


## v2.2.4 — Self-test report export

- [x] Добавлена команда `self-test report` / `adb self-test report` / `fastboot self-test report`.
- [x] Добавлена кнопка `Self-test отчёт (.txt)` в сервисной вкладке.
- [x] Отчёт сохраняется в `/sdcard/AdbFastboot/reports/selftest-*.txt`.
- [x] В отчёт включаются app version, connection info, debug flag, ADB key dir, self-test log и полный visible-log snapshot.
- [x] Structured JSON report и ZIP-связка self-test + forum report реализованы в v2.2.5.


## v2.2.5 — Structured self-test and forum report bundle

- [x] `self-test report` создаёт пару файлов: `selftest-*.txt` и `selftest-*.json`.
- [x] Добавлен `self-test forum` / `self-test zip` / `adb self-test forum` / `fastboot self-test forum`.
- [x] Forum ZIP расширен файлами `manifest.txt`, `diagnostic-summary.json`, `adb-info.txt`.
- [x] В ZIP можно вложить self-test TXT/JSON как `self-test/selftest.txt` и `self-test/selftest.json`.
- [x] Добавлена кнопка `Self-test ZIP для форума`.

Still recommended:

- Добавить UI-индикатор результата self-test: PASS / WARN / FAIL.
- Добавить кнопку «Открыть папку reports» через системный DocumentsUI intent.


## v2.2.6 — Private / sanitized reports

Done:

- [x] Добавлен `ReportSanitizer.kt`.
- [x] Forum ZIP теперь `SANITIZED` по умолчанию.
- [x] `self-test report` пишет санитизированные TXT/JSON.
- [x] `diagnostic-summary.json` получил schema `forum-report.v3` и `privacyMode`.
- [x] `selftest.json` получил schema `selftest.v2` и `privacyMode`.
- [x] Raw `log.txt` больше не вкладывается как есть; в ZIP попадает текст после privacy-фильтра.
- [x] Исправлена строка locked-bootloader warning для `fastboot flash`.

Still recommended:

- Добавить отдельный переключатель/диалог «сырой отчёт для локальной отладки» только с явным подтверждением.
- Добавить UI-индикатор результата self-test: PASS / WARN / FAIL.
- Добавить кнопку «Открыть папку reports» через системный DocumentsUI intent.


## v2.2.7 — Self-test UI status and reports folder

Done:

- [x] Добавлен LiveData-статус `SelfTestStatus`: `NOT_RUN`, `RUNNING`, `PASS`, `WARN_FAIL`.
- [x] На сервисной странице добавлен видимый индикатор результата self-test.
- [x] `self-test report` показывает имена созданных TXT/JSON отчётов в статусе.
- [x] Добавлена кнопка `Открыть папку reports`.
- [x] Добавлены команды `reports`, `open reports`, `adb reports`, `fastboot reports`.
- [x] Открытие папки reports реализовано через DocumentsUI `ACTION_OPEN_DOCUMENT_TREE` с `EXTRA_INITIAL_URI`.

Still recommended:

- Добавить отдельный экран списка отчётов внутри приложения: имя, размер, дата, кнопки share/delete.
- Добавить опцию raw local report с двойным подтверждением, если понадобится для внутренней отладки.
- После появления APK проверить, что DocumentsUI корректно открывает `/sdcard/AdbFastboot/reports` на Android 10–14.
