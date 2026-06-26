# ADB & Fastboot Tool — Portable OTG Flasher

## NekoFlash 2.5.6 — UI Polish

- Adds readable bottom navigation labels and inline workflow hints for Xiaomi ROM, Recovery, Magisk, Files, and Reports.
- This is a UI-only polish pass; flashing logic is unchanged.

Android-приложение для прошивки и диагностики другого Android-устройства через USB OTG. Назначение проекта — форумная утилита для сценария «без ПК»: один Android-смартфон работает как USB-host, второй телефон подключается как target-устройство в Fastboot или Recovery / ADB Sideload.

Проект не ориентирован на Google Play Market. Для удобства чтения файлов прошивки используется папка `/sdcard/Download/NekoFlash` и разрешение доступа ко всем файлам.



## NekoFlash 2.5.1 — Classic Recovery UI

- Added a Classic Recovery Dashboard inspired by recovery-style UX patterns.
- Added large workflow tiles for Xiaomi ROM, Recovery/Bootloop, Magisk/Root, Files, Console, and Reports.
- Added own vector icons instead of copying third-party recovery theme PNG assets.
- Kept the existing Xiaomi ROM flashing safety logic unchanged.


## Изменения в версии 2.5.1-nekoflash

### 2.5.0 — Release readiness, Xiaomi Flash Wizard, Recovery tools, Magisk helper

- Добавлен Gradle Wrapper bootstrap `gradle/wrapper/gradle-wrapper.jar`, чтобы исходник можно было запускать через `./gradlew` из архива.
- Добавлен `scripts/build-release.sh`, который собирает debug/release-unsigned APK и SHA-256 checksums в `forum-build/`.
- Добавлена кнопка Xiaomi Flash Wizard с пошаговым подтверждением перед прошивкой ROM.
- Добавлены Recovery / Bootloop tools: проверка слота, смена active slot, reboot fastbootd/bootloader, прошивка `boot`, `init_boot`, `vendor_boot`, `vbmeta`.
- Добавлен Magisk / Root helper для patched `boot`/`init_boot` с теми же preflight-проверками.



## Изменения в версии 2.4.14-nekoflash

### 2.4.14 — Xiaomi script selection guard

- Добавлена явная классификация Xiaomi `flash_all`-скриптов: `CLEAN_ALL`, `SAVE_USER_DATA`, `LOCK / BLOCKED`, `UNKNOWN`.
- Режим clean all теперь не выбирает `except`, `save_data`, `no_wipe`, `nowipe` и `preserve`-варианты.
- Режим save data теперь предпочитает безопасные варианты `flash_all_except_storage`, `flash_all_except_data_storage`, `flash_all_except_userdata`, `flash_all_except_data`, `flash_all_save_data`, `flash_all_no_wipe`.
- `flash_all_lock` и lock-похожие скрипты исключаются ещё на этапе выбора режима.
- В подготовленном плане теперь выводится список всех найденных script variants с ролью каждого скрипта.
- Если save-data скрипт всё равно содержит `fastboot -w`, `erase userdata`, `erase metadata` или `update-super ... wipe`, прошивка блокируется как раньше.



## Изменения в версии 2.4.12-nekoflash

### 2.4.12 — Xiaomi ROM atomic extraction guard

- Распаковка образов Xiaomi Fastboot ROM теперь выполняется атомарно: сначала во временный `.partial`-файл, затем проверка размера и только после этого замена финального файла.
- Перед извлечением выбранных образов рабочая папка конкретного ROM очищается от старых файлов, чтобы не использовать stale `.img` после предыдущей попытки.
- Если размер извлечённого файла не совпадает с размером entry в архиве, подготовка прошивки прерывается до fastboot-команд.
- Это снижает риск частичной прошивки из-за старой/повреждённой распаковки, особенно при повторном запуске после сбоя.

## Изменения в версии 2.4.11-nekoflash

### 2.4.11 — Fastboot transfer progress telemetry

- Во время больших `download`/`flash`/`update-super` операций теперь логируется прогресс примерно каждые 5% и минимум раз в 5 секунд на длинных передачах.
- Прогресс показывает отправлено/всего, текущую скорость, среднюю скорость и ETA.
- Ошибки fastboot `FAIL` теперь получают понятную расшифровку: locked bootloader, unknown partition, max-download-size, sparse/vbmeta/signature, unsupported command, dynamic partitions/storage и USB timeout.
- Xiaomi ROM flash session report теперь пишет elapsed time на каждом шаге и размер передачи для `flash`/`update-super`.
- Перед передачей больших образов NekoFlash явно предупреждает не сворачивать приложение и не отключать OTG/кабель.

## Изменения в версии 2.4.10-nekoflash



### 2.4.10 — Xiaomi ROM storage/transfer preflight

- Перед реальной прошивкой Xiaomi Fastboot ROM добавлен блок `STORAGE / TRANSFER PREFLIGHT`.
- NekoFlash оценивает размер выбранных образов, общий объём fastboot-передачи, самый большой образ, необходимость fastbootd и свободное место в рабочей папке.
- Старт прошивки блокируется, если рабочей папке не хватает места для извлечённых образов с запасом.
- Для больших образов добавляются предупреждения: не отключать OTG, не сворачивать приложение и обеспечить питание.

### 2.4.9 — Xiaomi sparse/split image guard

- Xiaomi Fastboot ROM manifest теперь распознаёт split/sparse-образы: `*.img_sparsechunk.N`, `*.sparsechunk.N`, `*.img.N`.
- Анализ ROM показывает количество sparse/split chunks и группы split-образов.
- Добавлены предупреждения о пропусках, дублях и нестандартном старте chunk-последовательности.
- Рабочая папка распаковки теперь получает fingerprint ROM по размеру и времени изменения, чтобы не смешивать файлы разных ROM с одинаковым именем.
- При подготовке прошивки NekoFlash предупреждает о duplicate basename для образов, используемых выбранным `flash_all`-скриптом.

### 2.4.7 — Xiaomi ROM image manifest guard

- Xiaomi Fastboot ROM Flasher теперь строит manifest образов ROM до прошивки.
- Проверяются image-like entries: `.img`, `.elf`, `.mbn`, `.bin`.
- В dry-run отчёт добавлены zero-byte images, suspicious small images и duplicate image basenames.
- Образы, на которые ссылается выбранный `flash_all`-скрипт, сверяются с manifest ROM до извлечения.
- Прошивка блокируется, если referenced image имеет размер `0 B` или извлечённый образ оказался пустым/нечитаемым.
- Маленькие образы меньше `512 B` не блокируются автоматически, потому что некоторые `vbmeta*.img` могут быть маленькими, но NekoFlash выводит отдельное предупреждение.

## Изменения в версии 2.4.6-nekoflash

### 2.4.6 — Xiaomi ROM anti-rollback guard

- `FastbootProtocol` теперь собирает `getvar:anti` / `getvar:antirollback` в fastboot-диагностике.
- Xiaomi Fastboot ROM analysis извлекает anti-rollback index из `flash_all`-скриптов и небольших metadata-файлов ROM: `android-info.txt`, `misc_info.txt`, `anti*`, `*rollback*`.
- Перед прошивкой Xiaomi ROM NekoFlash сравнивает device anti index и ROM anti index и блокирует rollback при `device anti > ROM anti`.
- Dry-run и session reports теперь содержат anti-rollback информацию.
- ROM plans дополнительно отмечают critical firmware partitions: `xbl`, `abl`, `tz`, `modem`, `dsp`, `aop` и другие boot-chain / firmware-разделы.

## Изменения в версии 2.4.5-nekoflash

### 2.4.5 — persistent fastbootd resume

- Добавлено постоянное сохранение состояния Xiaomi ROM прошивки при переходе `bootloader fastboot → fastbootd`.
- Если приложение закрылось или USB-разрешение потерялось после `fastboot reboot fastboot`, пользователь может нажать **Resume last fastbootd step** и продолжить с сохранённого шага.
- Resume-состояние хранит ROM-файл, рабочую папку, режим прошивки, индекс продолжения, ожидаемый `product`, причину перехода и путь к marker/report-файлу.
- В `/sdcard/Download/NekoFlash/reports/` создаётся `xiaomi-rom-resume-state-*.txt`, который помогает понять, какую прошивку и с какого шага можно продолжать.
- После успешного завершения ROM-прошивки сохранённое resume-состояние очищается. При product mismatch, timeout или ошибке команды перехода состояние также сбрасывается.
- Добавлена кнопка **↩ Продолжить прошлый fastbootd-шаг** в блок Xiaomi Fastboot ROM.

## Изменения в версии 2.4.4-nekoflash

### 2.4.4 — Xiaomi ROM dry-run and session checkpoints

- Xiaomi Fastboot ROM analysis report now includes explicit dry-run plans for clean all and save data modes.
- Each real Xiaomi ROM flashing run creates `reports/xiaomi-rom-flash-session-*.txt`.
- The session report records ROM name, selected script, resume point, initial fastboot diagnostics, full action plan, per-step START/OK/FAILED states, and fastbootd waiting state.
- This is intended for recovery diagnostics: after a failed long flash, the user can see the exact last completed action before retrying or asking for help.

## Изменения в версии 2.4.3-nekoflash

### 2.4.3 — безопасное update-super в fastbootd

- Xiaomi Fastboot ROM Flasher теперь выполняет `update-super` автоматически, но только после подтверждения `getvar:is-userspace=yes`.
- Для `update-super` приложение извлекает super/super-empty образ, выполняет `download:<size>`, затем отправляет `update-super:<superPartition>[:wipe]`.
- Имя super-раздела берётся из `getvar:super-partition-name`, при отсутствии значения используется `super`.
- Если устройство не в fastbootd/userspace, `update-super` не запускается: сначала выполняется переход через `fastboot reboot fastboot` и resume.
- `update-super wipe` учитывается как wipe-сценарий: режим save data блокируется, если скрипт требует wipe.

## Изменения в версии 2.4.2-nekoflash

### 2.4.2 — fastbootd-переход для Xiaomi Fastboot ROM

- Xiaomi Fastboot ROM Flasher теперь умеет выполнять `fastboot reboot fastboot` при переходе ROM-скрипта из bootloader fastboot в userspace fastbootd.
- Если в выбранном `flash_all` найден `reboot fastboot`, приложение выполняет bootloader-стадию, переводит устройство в fastbootd, ждёт переподключения USB и автоматически продолжает с fastbootd-стадии.
- Если явной команды `reboot fastboot` нет, но в плане есть dynamic/logical partitions (`system`, `vendor`, `product`, `odm`, `system_ext`, `vendor_dlkm` и т.п.), приложение переводит устройство в fastbootd перед их прошивкой.
- Автопродолжение ограничено по времени и дополнительно проверяет `getvar:product`, чтобы не продолжить прошивку на другом устройстве.
- Промежуточные reboot-команды в другие режимы по-прежнему блокируются; автоматический resume поддерживается только для fastbootd.
- `update-super` в этой версии ещё блокировался; начиная с 2.4.3 выполняется через безопасный fastbootd-шаг.

## Изменения в версии 2.4.1-nekoflash

### 2.4.1 — усиление Xiaomi Fastboot ROM Flasher

- Улучшен парсер реальных Xiaomi `flash_all`-скриптов: `%CURRENT_DIR%`, `%ANDROID_PRODUCT_OUT%`, `-S`, `--disable-verity`, `--disable-verification`, `--slot`.
- Добавлен отдельный TXT-отчёт анализа Fastboot ROM в `/sdcard/Download/NekoFlash/reports/`.
- `update-super` распознаётся в плане прошивки; начиная с 2.4.3 выполняется только после fastbootd-проверки.
- Усилена защита от ложного запуска ROM с неизвестными/неподдерживаемыми fastboot-строками.


- Добавлен модуль **Xiaomi Fastboot ROM** для Xiaomi/Redmi/POCO Fastboot ROM `.zip`, `.tgz`, `.tar`, `.tar.gz`.
- Модуль анализирует `flash_all*.bat/.sh`, выбирает безопасный сценарий `flash_all` или `flash_all_except_storage`, строит план команд и показывает его в лог.
- Сценарии `flash_all_lock` и любые команды блокировки загрузчика (`flashing lock`, `oem lock`, `lock critical`) жёстко блокируются.
- Перед прошивкой выполняется сверка `getvar:product` с product guard из ROM-скрипта. При несовпадении прошивка отменяется.
- Добавлен импорт `.tgz`, `.tar`, `.tar.gz` через системный выбор файла.
- Для ROM-прошивки приложение извлекает только образы, которые реально упомянуты в выбранном `flash_all`-скрипте.
- Если скрипт содержит неизвестные Fastboot-строки или промежуточный reboot не в fastbootd, автоматическая прошивка блокируется, чтобы не получить частичную прошивку.
- Полная прошивка ROM выполняется только при Fastboot-соединении и `unlocked=yes`.

## Изменения в версии 2.3.1-nekoflash

- Исправлена документация: рабочая папка везде приведена к `/sdcard/Download/NekoFlash`.
- GitHub Actions теперь останавливает сборку при ошибке `scripts/check_project.py`, а не игнорирует её.
- FileProvider больше не открывает весь external storage; наружу выдаются только `logs/` и `reports/`.
- Исправлен стартовый URI кнопки **Открыть папку reports**: теперь DocumentsUI открывает `Download/NekoFlash/reports`.
- Видимые подписи из `activity_main.xml` вынесены в `strings.xml` / `values-ru/strings.xml`.
- Артефакты сборки переименованы в `NekoFlash-<version>-debug.apk`.

## Изменения в версии 2.2.7-self-test-ui

- Добавлен видимый UI-индикатор результата self-test: `NOT RUN`, `RUNNING`, `PASS`, `WARN/FAIL`.
- `DeviceViewModel` теперь хранит `SelfTestStatus` в LiveData: результат, краткое резюме, время обновления и имена созданных TXT/JSON отчётов.
- После `self-test`, `self-test report` и `self-test forum` статус обновляется автоматически.
- На сервисной странице добавлена кнопка **Открыть папку reports**.
- Добавлены команды терминала: `reports`, `open reports`, `open-reports`, `adb reports`, `fastboot reports`.
- Открытие папки реализовано через системный DocumentsUI с начальным URI `/sdcard/Download/NekoFlash/reports`; если DocumentsUI недоступен, путь копируется в буфер.
- Отчёты остаются санитизированными по умолчанию; логика ADB/Fastboot терминала не менялась.

## Изменения в версии 2.2.6-private-reports

- Forum ZIP и self-test отчёты теперь санитизируются по умолчанию перед сохранением/отправкой.
- Добавлен `ReportSanitizer.kt`: маскирует серийники, host-модель/производителя, app-private пути, путь к ADB key, user-storage пути и длинные hex-идентификаторы в логах.
- Forum schema обновлена до `ru.forum.adbfastboottool.forum-report.v3`, self-test schema — до `ru.forum.adbfastboottool.selftest.v2`.
- В JSON добавлено поле `privacyMode: sanitized`.
- `log.txt`, `visible-log.txt`, `self-test/selftest.txt`, `self-test/selftest.json` и profile JSON внутри ZIP проходят через privacy-фильтр.
- Технически полезные данные сохраняются: VID/PID, USB endpoints, ADB features, `shell_v2`, `unlocked`, `is-userspace`, slot/dynamic partition info, размеры download/fetch.
- Исправлена устаревшая строка предупреждения: `fastboot flash` действительно блокируется при `getvar:unlocked = no`, а не просто предупреждает.

## Изменения в версии 2.2.5-structured-reports

- `self-test report` теперь создаёт два файла: человекочитаемый `selftest-*.txt` и структурированный `selftest-*.json`.
- Добавлены команды единого ZIP для форума: `self-test forum`, `self-test zip`, `self-test bundle`, `adb self-test forum`, `fastboot self-test forum`.
- На сервисной странице добавлена кнопка **Self-test ZIP для форума**.
- Forum ZIP теперь содержит `manifest.txt`, `diagnostic-summary.json`, `adb-info.txt`, `fastboot-info.txt`, `usb-info.txt`, `visible-log.txt`, основной `log.txt`, профили устройства и, при запуске через self-test forum, вложения `self-test/selftest.txt` и `self-test/selftest.json`.
- В JSON фиксируются версия приложения, состояние подключения, ADB banner/features, поддержка `shell_v2`, Fastboot diagnostics, debug flag и tail видимого лога.
- Логика self-test остаётся read-only: отчёт не выполняет `flash`, `download`, `erase`, `format`, `install`, `push`, `root`, `remount` или `reboot`.

## Изменения в версии 2.2.4-self-test-report

- Добавлен экспорт self-test отчёта в отдельный `.txt` файл.
- Новые команды: `self-test report`, `self-test export`, `adb self-test report`, `fastboot self-test report`.
- На сервисной странице добавлена кнопка **Self-test отчёт (.txt)**.
- Отчёты сохраняются в `/sdcard/Download/NekoFlash/reports/selftest-*.txt`.
- Отчёт содержит app version, connection info, debug flag, ADB key dir, profiles dir, строки self-test и полный снимок видимого лога.
- После создания отчёта интерфейс предлагает поделиться файлом или скопировать путь.
- Сама проверка остаётся read-only: не выполняются `flash`, `download`, `erase`, `format`, `install`, `push`, `root`, `remount` или `reboot`.

## Изменения в версии 2.2.3-self-test

- Добавлен безопасный `self-test` / `selftest` / `smoke-test` / `doctor` без префикса.
- Добавлены контекстные команды `adb self-test` и `fastboot self-test`.
- На сервисной странице добавлена кнопка **Self-test / Smoke-test**.
- ADB self-test проверяет banner/features, `shell_v2`, базовый shell, `getprop`, `id`, `pm path android`, shell v2 exit-code и sync `STAT` для `/sdcard` и `/data/local/tmp`.
- Fastboot self-test выполняет только read-only проверки: `getvar`, режим bootloader/fastbootd, `max-download-size`, `max-fetch-size`, `is-logical` для типовых разделов.
- Self-test не выполняет `flash`, `download`, `erase`, `format`, `install`, `push` с записью, `root`, `remount` или `reboot`.
- Добавлен `SMOKE_TEST_CHECKLIST.md` для проверки APK на реальных устройствах.


## Изменения в версии 2.2.1-fastbootd-dynamic

- Добавлена диагностика fastbootd через `getvar:is-userspace`: приложение различает `bootloader fastboot` и `userspace fastbootd`.
- В Fastboot-диагностику добавлены `super-partition-name` и `max-fetch-size`.
- Добавлены команды терминала для dynamic/logical partitions: `logical-info`, `is-logical`, `create-logical-partition`, `delete-logical-partition`, `resize-logical-partition`.
- Desktop-синтаксис logical-команд преобразуется во внутренний wire-формат Fastboot protocol: `create-logical-partition:<name>:<size>`, `delete-logical-partition:<name>`, `resize-logical-partition:<name>:<size>`.
- Добавлена команда `fastboot update-super <super.img> [wipe] [superPartition]` через `download:<size>` + `update-super:<superPartition>[:wipe]`.
- Добавлена команда `fastboot fetch <partition> [out.img]` с chunk-загрузкой по `max-fetch-size`, `.part`-файлом и прогрессом.
- `fastboot flash` остаётся жёстко заблокированным при `getvar:unlocked = no`; новые destructive-команды logical partitions требуют отдельного подтверждения.

## Full terminal v2.1.9

В этой сборке снят строгий whitelist ручных команд. Экспертная консоль принимает `fastboot ...` и `adb ...` команды. Файловые Fastboot-команды (`flash`, `boot`) выполняются через настоящий USB download-поток, а не отправляются одной строкой в bootloader. Для обычного Android-режима добавлена RSA-авторизация ADB: приложение хранит собственный RSA-2048 ключ, подписывает AUTH TOKEN и при необходимости отправляет public key для системного запроса `Allow USB debugging`. В версии 2.1.4 добавлен ADB sync-протокол для `adb push`, `adb pull` и базового `adb install` через временную загрузку APK в `/data/local/tmp`. В версии 2.1.5 добавлен ADB Shell v2: если target-устройство заявляет `shell_v2`, `adb shell` получает раздельные stdout/stderr и реальный exit-code. В версии 2.1.6 добавлен `adb install-multiple` для split APK через package-manager session API. В версии 2.1.7 добавлена обработка `.apks/.xapk` контейнеров. В версии 2.1.8 добавлен `adb stat` перед `pull`, точный прогресс скачивания и рекурсивные `adb push/pull` каталогов. В версии 2.1.9 добавлен интерактивный `adb shell` с постоянной shell-сессией и stdin-очередью. В версии 2.2.0 добавлены управляющие команды интерактивного shell: Ctrl+C/SIGINT и Ctrl+D/EOF без закрытия всей сессии. `fastboot flash` жёстко блокируется приложением, если `getvar:unlocked` возвращает `no`; остальные команды полного терминала не переводятся обратно на whitelist.


## Важное предупреждение

Прошивка разделов `boot`, `init_boot`, `vendor_boot`, `recovery`, `dtbo` может привести к bootloop или hard brick, если выбран файл от другой модели, другого слота или другой версии прошивки.

Все действия выполняются на риск пользователя. Приложение не разблокирует загрузчик и не обходит защиту производителя.

## Требования

1. Host-устройство: Android-смартфон с поддержкой USB OTG.
2. Target-устройство: Android-смартфон в режиме Fastboot или Recovery / ADB Sideload.
3. Кабель Type-C → Type-C или OTG-переходник.
4. Для прошивки через Fastboot загрузчик target-устройства должен быть заранее разблокирован официальным способом.
5. Файлы `.img`, `.zip`, `.tgz`, `.tar`, `.tar.gz`, `.bin`, `.apk`, `.apks`, `.xapk`, `.obb` можно положить вручную в `/sdcard/Download/NekoFlash` или импортировать через кнопку **Импорт**.
6. При наличии контрольных сумм положите рядом `.sha256` или `.md5`, например `boot.img.sha256` или `rom.zip.md5`. Их тоже можно импортировать.
7. Для дополнительных предупреждений можно использовать профили устройств в `/sdcard/Download/NekoFlash/profiles/`.
8. Для долгой прошивки рекомендуется отключить оптимизацию батареи для приложения через кнопку **Батарея**.


## Разрешения и безопасность v2.0

В версии `2.0-forum` добавлено усиление безопасности разрешений:

- разрешение `SYSTEM_ALERT_WINDOW` / «поверх всех окон» **не используется**;
- добавлено `HIDE_OVERLAY_WINDOWS`: на Android 12+ приложение блокирует чужие overlay-окна поверх своих чувствительных экранов;
- добавлено `WAKE_LOCK`: CPU удерживается активным только во время прошивки `.img`, ADB Sideload, анализа или диагностики и освобождается после завершения;
- добавлена кнопка **Разрешения и безопасность** на странице **Диагностика**;
- в приложении показано, какие разрешения выданы, какие необязательны и какие не запрашиваются;
- `INTERNET`, контакты, SMS, камера, микрофон, геолокация и телефонные разрешения не запрашиваются.

Текущие основные разрешения:

| Разрешение | Зачем нужно |
|---|---|
| `MANAGE_EXTERNAL_STORAGE` | Чтение и запись файлов прошивки в `/sdcard/Download/NekoFlash`. |
| `POST_NOTIFICATIONS` | Foreground-уведомление во время операции на Android 13+. |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` | Удержание долгой операции передачи файла как foreground-задачи. |
| `WAKE_LOCK` | Не дать CPU уснуть во время прошивки или sideload. |
| `HIDE_OVERLAY_WINDOWS` | Не дать другим приложениям перекрыть окно утилиты на Android 12+. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Открывает системный экран отключения ограничений батареи по кнопке **Батарея**. |

## Что реально реализовано в этой версии

### Интерфейс

- новая страничная навигация: **Главная**, **Fastboot**, **ADB Sideload**, **Файлы**, **Диагностика**, **Отчёты**, **Инструкция**;
- главный экран с мастерами: прошивка .IMG, ADB Sideload .ZIP, импорт и анализ файлов;
- отдельная нижняя консоль с полноценным ADB/Fastboot-терминалом и историей;
- крупные предупреждения на страницах Fastboot/ADB;
- логика старых кнопок сохранена, но разложена по понятным сценариям.

### Fastboot

Поддерживается:

- автоматическое определение Fastboot-устройства по USB;
- выбор устройства из списка, если через OTG/USB-хаб найдено несколько ADB/Fastboot-устройств;
- автосканирование USB каждые 3 секунды через кнопку **Авто**;
- локальный статус подключения через `status` или `devices`;
- `getvar all` / `getvar:all`;
- `getvar product`;
- `getvar current-slot`;
- `getvar unlocked`;
- `getvar secure`;
- `getvar max-download-size`;
- `reboot`;
- `reboot bootloader` / `reboot-bootloader`;
- `reboot recovery` / `reboot-recovery`;
- безопасная кнопочная прошивка `.img` через правильную цепочку Fastboot-протокола: `download:<size>` → передача данных → `flash:<partition>`;
- диагностика перед прошивкой: `product`, `current-slot`, `unlocked`, `secure`, `max-download-size`; значения `getvar` корректно извлекаются и из `INFO...`-ответов bootloader'а;
- кэш Fastboot-диагностики на 5 минут и ручное обновление через кнопку **Данные**;
- проверка размера `.img` по `max-download-size`;
- SHA-256/MD5 проверка `.img` по sidecar-файлам, если они есть;
- проверка профиля устройства из `/sdcard/Download/NekoFlash/profiles/*.json`, если профиль найден;
- foreground-уведомление во время долгой операции прошивки;
- подробный debug-лог USB/Fastboot через кнопку **Debug**;
- таймаут USB permission 30 секунд, если Android не вернул ответ на запрос доступа;
- защита от overlay-окон через `HIDE_OVERLAY_WINDOWS` на Android 12+;
- WakeLock на время операций, чтобы CPU не засыпал во время передачи файла.

Разрешенные разделы для кнопочной прошивки:

- `boot`
- `init_boot`
- `vendor_boot`
- `recovery`
- `dtbo`
- `vbmeta` — только при явной инструкции для конкретной модели/прошивки.

Ручной ввод `fastboot flash ...` включён в экспертном терминале. CLI-синтаксис преобразуется в USB Fastboot-протокол: `fastboot flash <partition> <file.img>` выполняет `download:<size>` → передачу файла → `flash:<partition>`. Если `getvar:unlocked` возвращает `no`, приложение останавливает именно `fastboot flash` до передачи файла. Относительные пути к файлам ищутся в `/sdcard/Download/NekoFlash`.

Destructive-команды `erase`, `format`, `set_active`, `flashing unlock/lock` и потенциально опасные `oem ...` команды требуют отдельного ручного подтверждения в диалоге. Это не whitelist: после подтверждения команда отправляется в протокольный слой как введена пользователем.

### ADB Sideload

Поддерживается:

- автоматическое определение ADB-интерфейса;
- отправка `.zip` через Recovery / ADB Sideload;
- SHA-256/MD5 проверка `.zip`, если рядом лежит `.sha256` или `.md5`;
- вывод SHA-256 и MD5 в лог, если sidecar-файлы не найдены;
- foreground-уведомление во время ADB Sideload.

### Файлы и логи

Поддерживается:

- импорт `.img`, `.zip`, `.tgz`, `.tar`, `.tar.gz`, `.bin`, `.apk`, `.apks`, `.xapk`, `.obb`, `.sha256`, `.md5` через системный выбор файла `ACTION_OPEN_DOCUMENT`;
- копирование выбранного файла в `/sdcard/Download/NekoFlash`;
- автоматическое переименование при совпадении имени, например `boot-1.img`;
- отправка лога как `.txt`-файла через Android share sheet и `FileProvider`;
- кнопка **Батарея** для открытия отключения оптимизации батареи;
- анализ файлов прошивки через кнопку **Анализ**: boot/vendor_boot/vbmeta/sparse/DTBO/ZIP, SHA-256, MD5 и sidecar-проверка;
- ZIP-отчёт для форума через кнопку **Отчёт**: `log.txt`, `usb-info.txt`, `fastboot-info.txt`, `app-info.txt`, `files.txt`, `file-analysis.txt`, `profiles/*.json`.

Команды `adb shell`, `adb exec`, `adb reboot`, `adb logcat`, `adb root/remount/tcpip/usb` и raw ADB service доступны из терминала. На устройствах с feature `shell_v2` shell-команды запускаются через `shell,v2,raw:<command>`: stdout и stderr логируются отдельно, а `exit` packet используется как реальный код завершения. Если `shell_v2` не заявлен, используется fallback на legacy `shell:<command>` без точного exit-code. Также доступны `adb push <local> <remote>`, `adb pull <remote> [local]`, базовый `adb install [-r] [-d] [-g] <local.apk>` и `adb install-multiple [-r] [-d] [-g] <base.apk> <split1.apk> ...`. `adb install` реализован практичным способом: APK сначала передаётся через ADB sync в `/data/local/tmp`, затем на target-устройстве вызывается `pm install`; на Shell v2-устройствах результат оценивается по реальному exit-code. `adb install-multiple` загружает все APK во временные файлы, создаёт package-manager session через `pm install-create`, добавляет split-файлы через `pm install-write` и завершает установку через `pm install-commit`.

## Профили устройств

Начиная с `1.4-forum`, приложение создает папку:

```text
/sdcard/Download/NekoFlash/profiles/
```

В ней автоматически появляется пример `sample-profile.json`.

Пример профиля:

```json
{
  "product": "alioth",
  "allowed_partitions": ["boot", "vendor_boot", "dtbo", "recovery"],
  "notes": "Для Magisk обычно прошивать boot.img. Проверяйте слот и версию прошивки."
}
```

Во время `.img` прошивки приложение сравнивает `getvar:product` с профилями. Если профиль найден и выбранный раздел не входит в `allowed_partitions`, приложение выводит предупреждение в лог. В версии `1.6-forum` профиль является предупреждением, а не жесткой блокировкой.

## Что пока не реализовано / ограничено

- `fastboot update` и `flashall` как пакетная логика desktop-fastboot; используйте отдельные `fastboot flash <partition> <file>`.
- `fastbootd` и полноценное управление dynamic partitions: resize/delete/create logical partitions, сценарии `super.img`.
- Полный `adb sync` каталогов пока не реализован; реализованы отдельные `adb push`, `adb pull`, `adb install` одного APK, `adb install-multiple` для split APK, а также базовая установка `.apks`/`.xapk` контейнеров через `adb install <file.apks|file.xapk>`.
- Gradle Wrapper jar отсутствует в исходном архиве; для локальной сборки нужен `gradle/wrapper/gradle-wrapper.jar` или установленный Gradle/Android Studio.

В версии full-terminal жёсткий whitelist не включается обратно. Команды отправляются, если они поддержаны протокольным слоем приложения и загрузчиком/ADB-daemon целевого устройства. Исключение: `fastboot flash` блокируется приложением при `unlocked=no`, а destructive Fastboot-команды требуют ручного подтверждения.

## Как пользоваться

1. Установите APK на host-смартфон.
2. При первом запуске выдайте доступ ко всем файлам. Это нужно для чтения `/sdcard/Download/NekoFlash`.
3. Создайте папку `/sdcard/Download/NekoFlash` и положите туда нужные `.img` или `.zip`, либо нажмите **Импорт** и выберите файл через системный проводник.
4. Если есть контрольные суммы, положите рядом `.sha256` или `.md5` или импортируйте их через **Импорт**.
5. Для долгих операций нажмите **Батарея** и отключите оптимизацию батареи для приложения.
6. Переведите target-смартфон в Fastboot или Recovery / ADB Sideload.
7. Подключите target-смартфон к host-смартфону по USB OTG. Используйте data-кабель: некоторые кабели только заряжают и не передают данные.
8. Нажмите **Поиск**. Если найдено несколько устройств, выберите нужное.
9. Выдайте приложению USB-доступ. Если ответа нет 30 секунд, переподключите OTG и нажмите **Поиск** ещё раз.
10. Используйте кнопки приложения:
   - **Прошить .IMG** — для Fastboot-образов;
   - **Getvar all** — для `getvar:all`;
   - **EN/RU** — выбор языка интерфейса;
   - **Reboot** — для перезагрузки;
   - **ADB Sideload** — для `.zip` в recovery;
   - **Импорт** — копирование файлов прошивки и checksum-файлов в рабочую папку;
   - **Анализ** — проверка типа файла, сигнатуры, хэшей и подсказки раздела до прошивки;
   - **Инструкция** — короткая памятка прямо в приложении;
   - **Лог** — копирование пути или отправка `.txt`-лога файлом;
   - **Авто** — автосканирование USB без постоянных ручных проверок;
   - **Debug** — подробные USB/Fastboot строки в лог;
   - **Данные** — обновить Fastboot-диагностику;
   - **Отчёт** — создать ZIP-отчёт для форума.
11. После операции лог можно взять из `/sdcard/Download/NekoFlash/logs/`, отчёты — из `/sdcard/Download/NekoFlash/reports/`.



## Изменения в версии 2.2.0-interactive-shell-controls

- Добавлены управляющие команды интерактивного `adb shell`:
  - `:ctrl-c`, `:sigint`, `:interrupt`;
  - `adb shell-ctrl-c`, `adb shell-interrupt`;
  - `:ctrl-d`, `:eof`, `adb shell-ctrl-d`, `adb shell-eof`.
- Ctrl+C отправляет в stdin интерактивной shell-сессии байт `0x03`, то есть SIGINT для foreground-процесса в PTY.
- Ctrl+D отправляет байт `0x04`, то есть EOF.
- Управляющие байты проходят через существующую stdin-очередь, поэтому UI не пишет в USB endpoint параллельно с reader loop.
- `exit`, `adb shell-stop`, `adb shell-exit`, `:close`, `:exit` по-прежнему закрывают интерактивную shell-сессию.
- Версия повышена до `2.2.0-interactive-shell-controls`, `versionCode 23`.

## Изменения в версии 2.1.9-interactive-shell

- `adb shell` без команды теперь открывает постоянную интерактивную shell-сессию, а не одноразовый service dump.
- Для устройств с `shell_v2` используется `shell,v2,pty:`: stdout/stderr читаются пакетами shell protocol, exit packet завершает сессию.
- Для устройств без `shell_v2` оставлен legacy fallback через `shell:` с raw stdout.
- Ввод пользователя отправляется в открытую сессию через stdin-очередь внутри одного ADB loop, без параллельного чтения USB из UI-потока.
- Пока интерактивный shell открыт, нижняя строка ввода работает как stdin shell. Закрытие: `exit`, `adb shell-stop`, `adb shell-exit`, `:close`, `:exit` или кнопка **Стоп**. Начиная с версии 2.2.0 доступны `:ctrl-c`/`:interrupt` для SIGINT и `:ctrl-d`/`:eof` для EOF.
- Импорт файлов расширен для APK/APKS/XAPK/OBB, чтобы установочные контейнеры можно было загружать через кнопку **Импорт**.
- Версия повышена до `2.1.9-interactive-shell`, `versionCode 22`.

## Изменения в версии 2.1.8-adb-stat-recursive

- Перед `adb pull` выполняется ADB sync `STAT`, чтобы определить тип remote path, размер файла и режим доступа.
- Для файлов `adb pull` теперь показывает точный процент по размеру из `STAT`, а не только количество принятых байт.
- `adb pull <remote-dir> [local-dir]` теперь поддерживает каталоги: список файлов строится через `find`, локальная структура папок восстанавливается, каждый файл скачивается через sync `RECV`.
- `adb push <local-dir> <remote-dir>` теперь поддерживает локальные каталоги: remote-папки создаются через `mkdir -p`, файлы отправляются рекурсивно через sync `SEND`.
- `adb push <local-file> <remote-dir/>` сохранил старое поведение: имя локального файла добавляется к remote-пути.
- Добавлены служебные функции `STAT`, безопасная сборка remote-путей и разбор секций `find` для рекурсивного `pull`.
- Версия повышена до `2.1.8-adb-stat-recursive`, `versionCode 21`.

## Изменения в версии 2.1.7-package-containers

- `adb install <file.apks>` и `adb install <file.xapk>` теперь распаковывают контейнеры во временную внутреннюю папку приложения.
- Автоматический выбор APK: `universal.apk` → одиночная установка; split-набор с base APK → `install-multiple`; один standalone APK → одиночная установка; неоднозначная структура → предупреждение и попытка split-установки.
- Для XAPK добавлена базовая поддержка OBB: файлы `.obb` отправляются в `/sdcard/Android/obb/<package>/`, если package name найден в пути `Android/obb/<package>/...` или в `manifest.json`.
- Добавлена защита от zip-slip путей внутри контейнеров.
- Временные распакованные файлы удаляются после завершения операции.
- Ограничение: device-spec bundletool не применяется; если `.apks` содержит несколько standalone-вариантов, автоматически выбирается первый standalone APK с предупреждением.
- Версия повышена до `2.1.7-package-containers`, `versionCode 20`.

## Изменения в версии 2.1.6-install-multiple

- Добавлена команда `adb install-multiple [-r] [-d] [-g] <base.apk> <split1.apk> [split2.apk...]`.
- Установка split APK реализована через package-manager session API: `pm install-create` → `pm install-write` для каждого APK → `pm install-commit`.
- Все APK предварительно передаются через ADB sync в `/data/local/tmp`, после установки временные файлы удаляются.
- При ошибке `install-write` или `install-commit` приложение вызывает `pm install-abandon <session>`.
- Shell runner теперь умеет возвращать внутренний `ShellResult` с stdout/stderr/exit-code; это нужно для извлечения install session id из вывода `pm install-create`.
- Для legacy shell оставлен fallback с захватом stdout, но без точного exit-code.
- Версия повышена до `2.1.6-install-multiple`, `versionCode 19`.


## Изменения в версии 2.1.5-adb-shell-v2

- При ADB-подключении читается CNXN banner и список `features=` target-устройства.
- Если feature `shell_v2` доступна, `adb shell <command>` выполняется через `shell,v2,raw:<command>`.
- Реализован разбор Shell v2 packets: `stdout`, `stderr`, `exit`.
- В лог выводится `=== ADB SHELL EXIT: <code> ===`; ненулевой код считается ошибкой операции.
- Для one-shot shell-команд отправляется `CloseStdin`, чтобы команда не ждала ввода.
- `adb install` после временной загрузки APK теперь использует shell runner, поэтому на Shell v2-устройствах корректно учитывается exit-code `pm install`.
- Если Shell v2 недоступен или stream не открылся, приложение автоматически переходит на legacy `shell:`.
- Версия повышена до `2.1.5-adb-shell-v2`, `versionCode 18`.

## Изменения в версии 2.1.4-adb-sync

- Добавлен ADB sync-протокол поверх USB ADB stream `sync:`.
- Реализовано `adb push <local-file> <remote-path>` с передачей файла блоками `SEND/DATA/DONE` и отображением прогресса.
- Реализовано `adb pull <remote-path> [local-file]` через `RECV/DATA/DONE`; если локальный путь не указан, файл сохраняется в `/sdcard/Download/NekoFlash/`.
- Реализован базовый `adb install [-r] [-d] [-g] <local.apk>`: APK временно загружается в `/data/local/tmp`, после чего выполняется `pm install`; временный файл удаляется shell-командой.
- Для `adb push file /remote/dir/` приложение автоматически добавляет имя локального файла к remote-пути.
- Версия повышена до `2.1.4-adb-sync`, `versionCode 17`.

## Изменения в версии 2.1.3-adb-rsa

- Добавлена полноценная ADB RSA-авторизация для обычного Android-режима с включённой USB-отладкой.
- Приложение создаёт и хранит постоянный RSA-2048 host-key во внутренней папке приложения. Приватный ключ не кладётся в `/sdcard/Download/NekoFlash`.
- При ADB AUTH приложение сначала подписывает 20-байтный TOKEN сохранённым ключом. Если устройство ключ ещё не знает, отправляется ADB public key и ожидается системное подтверждение `Allow USB debugging`.
- Public key пишется во внутренний файл `adbkey.pub` для диагностики.
- Старый пустой `AUTH_RSAPUBLICKEY` удалён: он не мог корректно авторизовать обычное Android-устройство.
- Версия повышена до `2.1.3-adb-rsa`, `versionCode 16`.

## Изменения в версии 2.1.2-full-terminal-safety

- Исправлен запуск долгих USB-операций: новая операция больше не может случайно отменить саму себя через `activeJob.cancelAndJoin()`.
- `sendCommand()` и `getVar()` сбрасывают флаг отмены перед новым Fastboot-запросом; после нажатия **Стоп** последующие диагностические команды не остаются в состоянии `cancelled=true`.
- `fastboot flash` по-прежнему жёстко блокируется при `getvar:unlocked = no`.
- Добавлено подтверждение destructive-команд: `erase`, `format`, `set_active`, `flashing unlock/lock`, рискованные `oem ...` и raw-формы вроде `erase:userdata`.
- README приведён в соответствие с full-terminal реализацией: `erase/format/oem/flashing` больше не описаны как полностью нереализованные команды.
- Версия повышена до `2.1.2-full-terminal-safety`, `versionCode 15`.

## Изменения в версии 2.0-forum

- Не добавлено разрешение `SYSTEM_ALERT_WINDOW`; приложение не запрашивает доступ «поверх всех окон».
- Добавлено `HIDE_OVERLAY_WINDOWS`: на Android 12+ чужие overlay-окна блокируются поверх окна утилиты.
- Добавлено `WAKE_LOCK`: CPU удерживается активным только на время операции прошивки, sideload, анализа или диагностики.
- WakeLock освобождается после завершения, ошибки, отмены или отключения устройства; также задан аварийный таймаут 6 часов.
- На странице **Диагностика** добавлена кнопка **Разрешения и безопасность**.
- Добавлен диалог со статусом доступа ко всем файлам, уведомлений, оптимизации батареи и overlay-защиты.
- В README добавлена таблица разрешений и пояснение, зачем каждое разрешение нужно.
- ForegroundService оставлен в типе `dataSync`; `connectedDevice` не включался, чтобы не получить ошибки Android 14+ при файловых операциях без USB-соединения.
- Версия повышена до `2.0-forum`, `versionCode 11`.

## Изменения в версии 1.9-forum

- Добавлена многоязычность: русский и английский язык.
- Добавлены ресурсы `values/strings.xml` и `values-ru/strings.xml`.
- Добавлена кнопка `EN/RU` для ручного выбора языка внутри приложения.
- Поддерживается режим `Как в системе`, `Русский`, `English`.
- Добавлен `locales_config.xml` для Android per-app language settings.
- Переведены основные страницы интерфейса: Главная, Fastboot, ADB Sideload, Файлы, Диагностика, Отчёты, Инструкция.
- Переведены ключевые диалоги: прошивка, sideload, лог, отчёт, помощь, батарея, выбор USB-устройства и выбор файла.
- Переведены основные предупреждения по безопасному whitelist-команд.
- Версия повышена до `1.9-forum`, `versionCode 10`.

## Изменения в версии 1.8-forum

- Полностью переработан основной экран: приложение стало мастером с отдельными страницами.
- Добавлены страницы: **Главная**, **Fastboot**, **ADB Sideload**, **Файлы**, **Диагностика**, **Отчёты**, **Инструкция**.
- На главной странице появились крупные сценарии: прошить .IMG, выполнить ADB Sideload .ZIP, подготовить файлы.
- Старые функции сохранены, но разнесены по смысловым разделам, чтобы не перегружать пользователя.
- Нижняя консоль оставлена отдельно: безопасный whitelist-команд, история вверх/вниз и цветной лог.
- Добавлены подсказки прямо на страницах: какие операции безопасны, что проверять перед прошивкой, когда использовать отчёт для форума.
- GitHub Actions workflow обновлён под `NekoFlash-v1.8-forum-debug.apk`.
- Версия повышена до `1.8-forum`, `versionCode 9`.

## Изменения в версии 1.7-forum

- Добавлен `ImageInspector.kt` для анализа файлов прошивки без модификации содержимого.
- Кнопка **Анализ** показывает тип файла, размер, сигнатуру, SHA-256, MD5 и состояние `.sha256/.md5` sidecar-файлов.
- Распознаются Android boot image (`ANDROID!`), `vendor_boot` (`VNDRBOOT`), `vbmeta` (`AVB0`), Android sparse image, DTBO и ZIP/OTA-пакеты.
- Для `boot/recovery/init_boot/vendor_boot/dtbo/vbmeta` выводится подсказка раздела, но `vbmeta` остаётся только в режиме анализа и не добавляется в кнопочную прошивку.
- Для ZIP проверяется наличие `payload.bin`, `META-INF/com/google/android/` и `android-info.txt`.
- В ZIP-отчёт для форума добавлен `file-analysis.txt` с кратким анализом файлов из `/sdcard/Download/NekoFlash`.
- Импорт дополнительно разрешает `.bin` для диагностики `payload.bin` и других служебных файлов.
- GitHub Actions workflow обновлён под `NekoFlash-v1.7-forum-debug.apk`.
- Версия повышена до `1.7-forum`, `versionCode 8`.

## Изменения в версии 1.6-forum

- Добавлен выбор USB-устройства, если найдено несколько совместимых ADB/Fastboot-устройств.
- Добавлен автопоиск USB каждые 3 секунды через кнопку **Авто**.
- Добавлен переключатель **Debug** для подробного USB/Fastboot-лога.
- Добавлена кнопка **Отчёт**, которая создаёт ZIP для форума в `/sdcard/Download/NekoFlash/reports/`.
- В отчёт попадают `log.txt`, `visible-log.txt`, `usb-info.txt`, `fastboot-info.txt`, `app-info.txt`, `files.txt` и профили устройств.
- Добавлен кэш Fastboot-диагностики на 5 минут.
- Добавлена кнопка **Данные** для принудительного обновления `product/current-slot/unlocked/secure/max-download-size`.
- Инструкция дополнена блоком про OTG, USB-C ↔ USB-C, microUSB OTG и data-кабели.
- GitHub Actions workflow обновлён под `NekoFlash-v1.6-forum-debug.apk`.
- Версия повышена до `1.6-forum`, `versionCode 7`.

## Изменения в версии 1.5-forum

- Добавлен USB permission timeout: если Android не вернул ответ за 30 секунд, приложение пишет понятную ошибку.
- Добавлена кнопка **Импорт** для выбора `.img`, `.zip`, `.sha256`, `.md5` через системный проводник.
- Импортированный файл копируется в `/sdcard/Download/NekoFlash`; при совпадении имени создается вариант `name-1.ext`.
- Добавлен `FileProvider` и `res/xml/file_paths.xml`.
- Кнопка **Лог** теперь отправляет лог как `.txt`-файл, а не только как текст.
- Добавлена кнопка **Батарея** и разрешение `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
- Обновлен GitHub Actions workflow: APK переименовывается в `NekoFlash-v1.5-forum-debug.apk`, рядом создается `checksums-sha256.txt`.
- `namespace`, Kotlin package и путь исходников приведены к `ru.forum.adbfastboottool`.
- Версия повышена до `1.5-forum`, `versionCode 6`.

## Изменения в версии 1.4-forum

- Добавлен `ForegroundService` с уведомлением во время Fastboot/ADB операций.
- Добавлены разрешения для foreground data sync service на Android 14+.
- Добавлена папка профилей `/sdcard/Download/NekoFlash/profiles/`.
- Автоматически создается пример `sample-profile.json`.
- Перед `.img` прошивкой профиль проверяется по `getvar:product`.
- Если выбранный раздел не входит в `allowed_partitions`, приложение предупреждает в лог.
- В интерфейс добавлена кнопка **Инструкция**.
- В интерфейс добавлена кнопка **Лог** для копирования пути и отправки текста лога.
- Экран режимов стал понятнее: Fastboot и ADB Sideload разведены по вкладкам.
- Версия повышена до `1.4-forum`, `versionCode 5`.

## Изменения в версии 1.3-forum

- Перед `.img` прошивкой автоматически выполняется диагностика Fastboot:
  - `getvar:product`;
  - `getvar:current-slot`;
  - `getvar:unlocked`;
  - `getvar:secure`;
  - `getvar:max-download-size`.
- Добавлена проверка размера `.img` по `max-download-size`.
- Если файл больше лимита загрузчика, прошивка отменяется до передачи данных.
- Добавлена SHA-256 и MD5 проверка для `.img` и `.zip`.
- Поддерживаются файлы контрольных сумм:
  - `file.img.sha256` / `file.img.md5`;
  - `file.sha256` / `file.md5`.
- Если `.sha256` или `.md5` найден и хэш не совпадает, операция отменяется.
- Если sidecar-файла нет, SHA-256 и MD5 просто выводятся в лог.
- Логи автоматически сохраняются в `/sdcard/Download/NekoFlash/logs/`.
- Fastboot-ответы `OKAY`, `FAIL`, `INFO`, `TEXT`, `DATA` обрабатываются понятнее.

## Изменения в версии 1.2-forum

- Заблокирован ручной `fastboot flash ...`, чтобы не отправлять CLI-команду вместо низкоуровневого Fastboot-протокола.
- Добавлена нормализация команд `getvar all`, `reboot bootloader`, `reboot recovery`.
- `devices` теперь работает как локальный статус подключения, а не отправляется в bootloader.
- Добавлены обработчики кнопок «Копировать путь», «История вверх», «История вниз».
- Добавлена обработка отключения USB-устройства.
- Добавлено подтверждение перед прошивкой `.img` и перед ADB Sideload.
- На время операции включается `KEEP_SCREEN_ON`, чтобы экран host-устройства не гас.
- README приведен к фактическим возможностям проекта.
- `applicationId` изменен на `ru.forum.adbfastboottool`.


## Forum check fix — v2.0.1-forum-check

- Исправлен разбор `fastboot getvar`: значения теперь берутся из `INFOproduct: ...` / `INFOcurrent-slot: ...` / `INFOunlocked: ...`, а не только из финального `OKAY`.
- Добавлена внутренняя блокировка прошивки в `FastbootProtocol`, если `getvar:unlocked` вернул `no`. Это защита на уровне протокола, а не только UI.
- Приведён в соответствие экран очереди и безопасный whitelist: `vbmeta` теперь либо осознанно прошивается после предупреждения, либо блокируется профилем/проверками, но не ломается из-за скрытого несоответствия UI и протокола.

### Flash guard в полном терминале

Полный ADB/Fastboot-терминал не использует общий whitelist команд, но `fastboot flash` защищён отдельно. Если предзапрос `getvar:unlocked` возвращает `no`, приложение останавливает прошивку до `download:<size>` и до `flash:<partition>`. Это действует как для кнопок прошивки, так и для терминальных форм `fastboot flash <partition> <file.img>` / raw `flash:<partition>`.

## v2.2.2 — сборка APK и CI

Добавлен build/CI-патч:

- `gradlew` / `gradlew.bat` используют официальный `gradle-wrapper.jar`, если он присутствует;
- если wrapper jar отсутствует, скрипты автоматически используют установленный Gradle из `PATH`;
- GitHub Actions `Build Android APK` теперь может собрать debug APK через Gradle 8.4 fallback;
- workflow **«Установка Gradle Wrapper»** генерирует официальный wrapper jar и коммитит его в репозиторий;
- добавлены `scripts/check_project.py`, `scripts/build-apk.sh`, `scripts/build-apk.bat`;
- версия проекта: `versionCode 25`, `versionName 2.2.2-build-ci`.

Локальная сборка:

```bash
./scripts/build-apk.sh
```

В среде без установленного Gradle и без `gradle-wrapper.jar` сборка APK невозможна; сначала нужно установить Gradle 8.4+ или запустить workflow генерации wrapper.


## v2.2.3 — Self-test / Smoke-test

Добавлен безопасный диагностический режим без записи на устройство.

Запуск из терминала:

```text
self-test
smoke-test
doctor
adb self-test
fastboot self-test
```

Запуск из интерфейса: сервисная страница → **Self-test / Smoke-test**.

ADB self-test проверяет handshake/banner, `shell_v2`, базовый shell, `getprop`, `id`, `pm path android`, shell v2 exit-code и sync `STAT` для `/sdcard` / `/data/local/tmp`. Fastboot self-test читает безопасные `getvar`, определяет bootloader fastboot vs fastbootd, проверяет `max-download-size`, `super-partition-name`, `max-fetch-size` и пробные `is-logical`.

Self-test не запускает `flash`, `erase`, `format`, `update-super`, `install`, `push` с записью, `root`, `remount` или reboot-команды.

Версия проекта: `versionCode 30`, `versionName 2.2.7-self-test-ui`.


### NekoFlash 2.4.4 — Xiaomi ROM dry-run and session checkpoints

- Xiaomi Fastboot ROM analysis report now includes explicit dry-run plans for clean all and save data modes.
- Each real Xiaomi ROM flashing run creates `reports/xiaomi-rom-flash-session-*.txt`.
- The session report records ROM name, selected script, resume point, initial fastboot diagnostics, full action plan, per-step START/OK/FAILED states, and fastbootd waiting state.
- This is intended for recovery diagnostics: after a failed long flash, the user can see the exact last completed action before retrying or asking for help.

### 2.4.6 — Xiaomi ROM anti-rollback guard

- `FastbootProtocol` now collects `getvar:anti` / `getvar:antirollback` in diagnostics.
- Xiaomi Fastboot ROM analysis extracts anti-rollback indexes from `flash_all` scripts and small ROM metadata files such as `android-info.txt`, `misc_info.txt`, `anti*` and `*rollback*` text files.
- Before flashing a Xiaomi ROM, NekoFlash compares device anti index with ROM anti index and blocks rollback when `device anti > ROM anti`.
- Dry-run and session reports now include anti-rollback information.
- ROM plans also flag critical firmware partitions such as `xbl`, `abl`, `tz`, `modem`, `dsp`, `aop` so users can see when boot-chain firmware is being written.


## 2.4.9 — Xiaomi sparse/split image guard

- Xiaomi ROM image manifest now includes sparse/split chunks such as `*.img_sparsechunk.N`, `*.sparsechunk.N` and `*.img.N`.
- The report lists split image groups and sequence warnings for missing, duplicated or non-zero-start chunks.
- Extraction workspace names now include a source fingerprint, preventing stale extracted files from a different ROM with the same archive name.
- Selected script preparation warns when duplicate image basenames are referenced and exact archive path matching is required.

## 2.4.8 — Xiaomi ROM Data Impact Guard

- Xiaomi Fastboot ROM analysis now includes a dedicated DATA IMPACT SUMMARY for every supported flash_all plan.
- `fastboot -w` / `--wipe` is detected explicitly instead of being treated as an opaque fastboot line.
- Save-data mode is blocked whenever the selected script has userdata, metadata, cache, update-super wipe, or explicit fastboot wipe risk.
- During clean-all flashing, standalone `fastboot -w` is converted into guarded protocol actions: erase userdata, then erase metadata/cache only if those partitions are reported by fastboot.
- Session and dry-run reports now make the deletion risk visible before any write command starts.


## NekoFlash 2.4.13 — Xiaomi Download Limit Preflight

Xiaomi Fastboot ROM flashing now performs an explicit Fastboot download limit preflight before write commands begin. The app compares every planned `flash` and `update-super` payload with the device-reported `getvar:max-download-size` and with the NekoFlash single-download implementation limit. If a planned image is too large, flashing is blocked before destructive steps start, and the session report records the exact step and image that exceeded the limit.

## NekoFlash 2.5.0 — release readiness, Xiaomi Flash Wizard, recovery tools and Magisk helper

This version adds a practical release-oriented layer on top of the Xiaomi Fastboot ROM flasher:

- Gradle Wrapper bootstrap is now present in `gradle/wrapper/gradle-wrapper.jar`, so `./gradlew` can be started from a clean source archive.
- `scripts/build-release.sh` builds debug and unsigned release APK artifacts into `forum-build/` and writes SHA-256 checksums.
- Xiaomi Fastboot ROM now has a guided Wizard entry point in addition to the direct analyze/flash buttons.
- Recovery / bootloop tools were added for slot check, active slot switch, reboot to bootloader, reboot to fastbootd, and flashing stock `boot`, `init_boot`, `vendor_boot`, `vbmeta` images with existing preflight checks.
- Magisk / root helper was added for flashing patched `boot` or `init_boot` images with the same preflight path.

Important limitation: NekoFlash does not generate Xiaomi unlock tokens and does not implement a Mi Unlock clone. Bootloader must already be unlocked before write operations.

### Building APKs

```bash
python3 scripts/check_project.py
./gradlew assembleDebug
./gradlew assembleRelease
# or
scripts/build-release.sh
```

Artifacts:

```text
forum-build/NekoFlash-<version>-debug.apk
forum-build/NekoFlash-<version>-release-unsigned.apk
forum-build/checksums-sha256.txt
```

The release APK is unsigned unless you configure local signing in your own Gradle environment.

## NekoFlash 2.5.2 — Operation Center UI

The Classic Recovery dashboard now includes an Operation Center card:

- shows whether the app is idle, running, completed, failed, or in warning state;
- displays the latest meaningful log event;
- gives direct access to Console, Reports, Log actions, Forum ZIP creation, and operation cancel;
- disables Cancel while no operation is active.

This is a UI/monitoring release only. Flashing logic and safety guards are unchanged.


## 2.5.4 Operation Step Queue UI

Operation Center now displays a compact per-step queue for Flash Queue and Xiaomi Fastboot ROM operations with PENDING/RUNNING/OK/FAILED/SKIPPED states.


## NekoFlash 2.5.4 — Typed Safety Confirmations

Dangerous actions now use a typed safety gate instead of a simple positive button. Xiaomi clean-all requires `CLEAN ALL`, save-data ROM flashing requires `FLASH`, critical partition flashing requires `FLASH` or `VBMETA`, slot switching requires `SLOT`, and destructive raw Fastboot commands require command-specific phrases such as `WIPE`, `LOCK`, `SUPER`, or `CONFIRM`.

## NekoFlash 2.5.5 — Safety Profiles

Version 2.5.5 adds a safety-profile layer:

- **Novice** — diagnostics, reports, import and analysis only; flash/write actions are blocked.
- **Standard** — guided flash actions are available; full terminal and high-risk actions remain locked.
- **Expert** — full terminal is visible, but high-risk actions require an additional typed `EXPERT` unlock.

High-risk actions include Xiaomi `clean all`, `vbmeta/super/userdata/metadata` write paths, active slot switching and destructive Fastboot terminal commands. Existing typed confirmations remain active after unlocking high-risk actions.
