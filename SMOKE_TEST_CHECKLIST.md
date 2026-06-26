# Smoke-test checklist for v2.2.3

Run these checks on a real device after building the debug APK.

## 1. Local app check

1. Open the app.
2. Grant storage/USB permissions when requested.
3. Open **Service**.
4. Tap **Self-test / Smoke-test** with no USB device connected.

Expected:

- App logs local version, log path, ADB key directory, profiles directory.
- App reports that no ADB/Fastboot device is connected.
- App does not crash.

## 2. ADB normal Android mode

Device state: Android booted, USB debugging enabled.

Commands:

```text
adb self-test
adb shell echo ok
adb shell
:ctrl-c
:ctrl-d
adb shell-stop
adb push local_file /sdcard/Download/
adb pull /sdcard/Download/local_file
```

Expected:

- ADB RSA prompt appears on the target device if the host key is new.
- Self-test prints banner/features and shell status.
- If `shell_v2` is present, exit-code probe returns `exit=7`.
- `STAT /sdcard` and `/data/local/tmp` succeed on a normal Android system.
- Interactive shell accepts commands and Ctrl+C does not close the app.

## 3. ADB recovery / sideload mode

Device state: recovery with ADB enabled.

Commands:

```text
adb self-test
adb shell id
```

Expected:

- Self-test may warn that package manager and `/sdcard` are unavailable.
- Shell may be legacy and lack precise exit-code.
- Warnings are acceptable; app must not crash.

## 4. Fastboot bootloader mode

Device state: bootloader fastboot.

Commands:

```text
fastboot self-test
fastboot getvar all
fastboot reboot bootloader
```

Expected:

- Self-test reads safe getvars.
- If `unlocked=no`, log states that `fastboot flash` is blocked by app guard.
- No `download`, `flash`, `erase`, `format`, or `update-super` happens.

## 5. Fastbootd / userspace mode

Device state: userspace fastbootd.

Commands:

```text
fastboot self-test
fastboot logical-info system_a
fastboot fetch vendor_boot vendor_boot-fetch.img
```

Expected:

- `is-userspace=yes` appears if supported by target.
- `super-partition-name` and `max-fetch-size` are logged if the OEM exposes them.
- `logical-info` reads partition metadata.
- `fetch` writes a `.part` file first, then renames on success.

## 6. Flash guard regression

Device state: bootloader locked (`getvar:unlocked = no`).

Command:

```text
fastboot flash boot boot.img
```

Expected:

- App refuses before `download:<size>`.
- Log contains the locked-bootloader guard message.
- No file bytes are sent to the target.

## 7. Destructive confirmation regression

Commands:

```text
fastboot erase userdata
fastboot format userdata
fastboot flashing unlock
fastboot delete-logical-partition system_a
```

Expected:

- Every destructive command opens a confirmation dialog.
- Cancelling the dialog sends nothing to the device.

## v2.2.5 — Проверка structured reports

1. Подключить устройство в ADB или Fastboot.
2. Выполнить `self-test report`.
   - Проверить наличие пары файлов в `/sdcard/AdbFastboot/reports/`:
     - `selftest-*.txt`;
     - `selftest-*.json`.
   - Открыть JSON и проверить, что он начинается со schema `ru.forum.adbfastboottool.selftest.v2`.
3. Выполнить `self-test forum` или нажать **Self-test ZIP для форума (safe)**.
   - Проверить, что ZIP содержит:
     - `manifest.txt`;
     - `diagnostic-summary.json`;
     - `adb-info.txt`;
     - `fastboot-info.txt`;
     - `usb-info.txt`;
     - `self-test/selftest.txt`;
     - `self-test/selftest.json`.
4. Убедиться, что во время этих проверок устройство не перезагружается, файлы на target не записываются, а destructive Fastboot-команды не вызываются.

## v2.2.6 privacy report regression

1. Run `self-test report`.
2. Open `selftest-*.txt` and `selftest-*.json` in `/sdcard/AdbFastboot/reports/`.
3. Confirm that the files contain `privacyMode: sanitized`.
4. Confirm that `serialno`, ADB public key path, `/data/user/0/...`, `/data/data/...`, `/sdcard/...` subpaths and host model/manufacturer are redacted.
5. Run `self-test forum`.
6. Inspect the ZIP entries `diagnostic-summary.json`, `visible-log.txt`, `log.txt`, `self-test/selftest.txt`, `self-test/selftest.json`.
7. Confirm that no raw fastboot serial number or host private path remains.
8. Confirm that VID/PID, interface numbers, endpoints, ADB features and Fastboot flags are still present.

## v2.2.7 self-test UI regression

1. Open the service tab before running self-test.
2. Confirm the status block shows `SELF-TEST: NOT RUN`.
3. Run `self-test` and confirm the status changes to `RUNNING`, then `PASS` or `WARN/FAIL`.
4. Run `self-test report` and confirm the status displays the created TXT/JSON report file names.
5. Press `Открыть папку reports` and confirm Android DocumentsUI opens near `/sdcard/AdbFastboot/reports`.
6. Run terminal shortcuts: `reports`, `open reports`, `adb reports`, `fastboot reports`.
7. Confirm privacy behavior did not regress: generated reports still contain `privacyMode: sanitized`.
