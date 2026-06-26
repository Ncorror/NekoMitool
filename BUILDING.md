# Сборка проекта

## NekoFlash 2.5.6 — UI Polish

- Adds readable bottom navigation labels and inline workflow hints for Xiaomi ROM, Recovery, Magisk, Files, and Reports.
- This is a UI-only polish pass; flashing logic is unchanged.

## Важно про Gradle Wrapper

В исходном архиве есть `gradlew`, `gradlew.bat` и `gradle/wrapper/gradle-wrapper.properties`, но нет `gradle/wrapper/gradle-wrapper.jar`.
Без этого файла команда `./gradlew assembleDebug` завершится ошибкой:

```text
Could not find or load main class org.gradle.wrapper.GradleWrapperMain
```

## Вариант 1: Android Studio

1. Откройте папку проекта в Android Studio.
2. Дождитесь Gradle Sync.
3. Соберите `app` через **Build → Build APK(s)**.

## Вариант 2: GitHub Actions

1. Загрузите проект в GitHub-репозиторий.
2. Запустите workflow **Установка Gradle Wrapper** вручную.
3. После коммита `gradle-wrapper.jar` запустите workflow **Build Android APK**.
4. Готовый debug APK будет в artifacts.

## Вариант 3: локальный Gradle

Если Gradle 8.4 установлен локально:

```bash
gradle wrapper --gradle-version 8.4
./gradlew assembleDebug
```

## Текущая конфигурация

- Android Gradle Plugin: 8.2.2
- Kotlin Android plugin: 1.9.22
- Gradle distribution: 8.4
- JDK: 17
- compileSdk: 34
- minSdk: 26
- targetSdk: 34
- versionName: 2.4.12-nekoflash

## Быстрая сборка после патча v2.2.2

### Linux / macOS

```bash
chmod +x scripts/build-apk.sh
./scripts/build-apk.sh
```

### Windows

```bat
scripts\build-apk.bat
```

Скрипты сначала запускают лёгкую статическую проверку проекта, затем выбирают способ сборки:

1. если есть `gradle/wrapper/gradle-wrapper.jar` — используется официальный Gradle Wrapper;
2. если jar отсутствует, но установлен Gradle 8.4+ — используется Gradle из `PATH`;
3. если нет ни wrapper jar, ни Gradle в `PATH`, сборка останавливается с понятной инструкцией.

### GitHub Actions

Для первичной сборки достаточно workflow `Build Android APK`: он устанавливает Gradle 8.4 через `gradle/actions/setup-gradle` и работает даже без `gradle-wrapper.jar`.

Для добавления официального wrapper jar в репозиторий запустите вручную workflow **«Установка Gradle Wrapper»**. Он выполнит:

```bash
gradle wrapper --gradle-version 8.4 --distribution-type bin
```

и закоммитит `gradle/wrapper/gradle-wrapper.jar`, если файл появился или изменился.



### NekoFlash 2.4.5 — persistent fastbootd resume

- Xiaomi Fastboot ROM Flasher now persists the fastbootd resume point in SharedPreferences and a report marker file.
- If the app/process is killed during the USB reconnect after `fastboot reboot fastboot`, reconnect the device in fastbootd and use the Resume button.
- The marker file is written to `Download/NekoFlash/reports/xiaomi-rom-resume-state-*.txt`.

### NekoFlash 2.4.4 — Xiaomi ROM dry-run and session checkpoints

- Xiaomi Fastboot ROM analysis report now includes explicit dry-run plans for clean all and save data modes.
- Each real Xiaomi ROM flashing run creates `reports/xiaomi-rom-flash-session-*.txt`.
- The session report records ROM name, selected script, resume point, initial fastboot diagnostics, full action plan, per-step START/OK/FAILED states, and fastbootd waiting state.
- This is intended for recovery diagnostics: after a failed long flash, the user can see the exact last completed action before retrying or asking for help.


### 2.4.7 — Xiaomi ROM image manifest guard

- Xiaomi Fastboot ROM Flasher builds an image manifest for `.img`, `.elf`, `.mbn`, `.bin` entries.
- Dry-run reports include zero-byte images, suspicious small images and duplicate image basenames.
- Referenced images from the selected `flash_all` script are checked against the ROM manifest before extraction.
- Zero-byte referenced/extracted images block flashing; very small images generate warnings.

### 2.4.6 — Xiaomi ROM anti-rollback guard

- `FastbootProtocol` now collects `getvar:anti` / `getvar:antirollback` in diagnostics.
- Xiaomi Fastboot ROM analysis extracts anti-rollback indexes from `flash_all` scripts and small ROM metadata files such as `android-info.txt`, `misc_info.txt`, `anti*` and `*rollback*` text files.
- Before flashing a Xiaomi ROM, NekoFlash compares device anti index with ROM anti index and blocks rollback when `device anti > ROM anti`.
- Dry-run and session reports now include anti-rollback information.
- ROM plans also flag critical firmware partitions such as `xbl`, `abl`, `tz`, `modem`, `dsp`, `aop` so users can see when boot-chain firmware is being written.


## 2.4.9 — Xiaomi sparse/split image guard

- Xiaomi ROM manifest scan recognises `*.img_sparsechunk.N`, `*.sparsechunk.N` and `*.img.N`.
- Split image groups and chunk sequence warnings are included in analysis reports.
- ROM extraction folders include a source fingerprint to prevent stale-file reuse across archives with the same basename.

## 2.4.8 — Xiaomi ROM Data Impact Guard

- Xiaomi Fastboot ROM analysis now includes a dedicated DATA IMPACT SUMMARY for every supported flash_all plan.
- `fastboot -w` / `--wipe` is detected explicitly instead of being treated as an opaque fastboot line.
- Save-data mode is blocked whenever the selected script has userdata, metadata, cache, update-super wipe, or explicit fastboot wipe risk.
- During clean-all flashing, standalone `fastboot -w` is converted into guarded protocol actions: erase userdata, then erase metadata/cache only if those partitions are reported by fastboot.
- Session and dry-run reports now make the deletion risk visible before any write command starts.


## 2.4.11 — Fastboot transfer progress telemetry

- Добавлена расширенная телеметрия передачи fastboot download payload: прогресс, скорость, средняя скорость и ETA.
- Добавлены best-effort расшифровки частых fastboot FAIL-ошибок.
- Xiaomi ROM session reports получили elapsed time и transfer size для больших действий.

## 2.4.10 — Xiaomi ROM storage/transfer preflight

- Added storage/transfer preflight for Xiaomi Fastboot ROM flashing.
- The selected flash plan now estimates extracted image bytes, fastboot transfer bytes, largest image size, fastbootd requirement and workspace free space.
- Flashing is blocked when the workspace cannot hold extracted referenced images plus safety headroom.
- Large image transfers now produce explicit warnings in the generated plan/session report.


## 2.4.12 — Atomic extraction guard

При подготовке Xiaomi Fastboot ROM выбранные образы извлекаются атомарно: `.partial` → проверка размера → финальный файл. Перед извлечением рабочая папка ROM очищается от старых файлов, поэтому повторная попытка не должна использовать stale-образы от предыдущей распаковки.


## NekoFlash 2.4.14 — Xiaomi Script Selection Guard

Xiaomi Fastboot ROM mode selection is now stricter. The parser labels each `flash_all` script as clean-all, save-data, lock/blocked or unknown, prefers known save-data variants, excludes lock scripts before selection, and records the full script variant matrix in the prepared plan. Save-data mode remains blocked if the selected script contains any detected wipe operation.



## NekoFlash 2.4.13 — Xiaomi Download Limit Preflight

Xiaomi Fastboot ROM flashing now performs an explicit Fastboot download limit preflight before write commands begin. The app compares every planned `flash` and `update-super` payload with the device-reported `getvar:max-download-size` and with the NekoFlash single-download implementation limit. If a planned image is too large, flashing is blocked before destructive steps start, and the session report records the exact step and image that exceeded the limit.

## 2.5.0 build notes

The source archive now includes a small Gradle Wrapper bootstrap jar at:

```text
gradle/wrapper/gradle-wrapper.jar
```

It reads `gradle-wrapper.properties`, downloads Gradle 8.4 into the user Gradle cache, extracts it, and delegates to the downloaded Gradle launcher. For production repository use, you may replace it with the official Gradle wrapper jar by running:

```bash
./gradlew wrapper --gradle-version 8.4 --distribution-type bin
```

Build commands:

```bash
python3 scripts/check_project.py
./gradlew assembleDebug
./gradlew assembleRelease
scripts/build-release.sh
```

APK output after `scripts/build-release.sh`:

```text
forum-build/NekoFlash-2.5.1-nekoflash-debug.apk
forum-build/NekoFlash-2.5.1-nekoflash-release-unsigned.apk
forum-build/checksums-sha256.txt
```

The container used to prepare this archive does not include Android SDK/Gradle caches, so APK generation must be performed on a workstation with Android SDK or in GitHub Actions.

## NekoFlash 2.5.1 — Classic Recovery UI

- Added a Classic Recovery Dashboard inspired by recovery-style UX patterns.
- Added large workflow tiles for Xiaomi ROM, Recovery/Bootloop, Magisk/Root, Files, Console, and Reports.
- Added own vector icons instead of copying third-party recovery theme PNG assets.
- Kept the existing Xiaomi ROM flashing safety logic unchanged.


## 2.5.2 notes

Version 2.5.2 adds the Operation Center UI layer. Build commands are unchanged:

```bash
python3 scripts/check_project.py
./gradlew assembleDebug
./gradlew assembleRelease
```


## 2.5.4 Operation Step Queue UI

Operation Center now displays a compact per-step queue for Flash Queue and Xiaomi Fastboot ROM operations with PENDING/RUNNING/OK/FAILED/SKIPPED states.


## NekoFlash 2.5.4 — Typed Safety Confirmations

Dangerous actions now use a typed safety gate instead of a simple positive button. Xiaomi clean-all requires `CLEAN ALL`, save-data ROM flashing requires `FLASH`, critical partition flashing requires `FLASH` or `VBMETA`, slot switching requires `SLOT`, and destructive raw Fastboot commands require command-specific phrases such as `WIPE`, `LOCK`, `SUPER`, or `CONFIRM`.

## NekoFlash 2.5.5 — Safety Profiles

The 2.5.5 source tree adds the Safety Profile UI and high-risk action gate. Before building:

```bash
python3 scripts/check_project.py
./gradlew assembleDebug
```

Expected static check output:

```text
strings parity: OK (399 keys)
version: code=53, name=2.5.5-nekoflash
static project check: OK
```
