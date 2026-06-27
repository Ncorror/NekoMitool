# v2.2.4 — Self-test report export

## Added

- Added a dedicated self-test report export operation.
- New terminal commands:
  - `self-test report`
  - `self-test export`
  - `selftest report`
  - `smoke-test report`
  - `doctor report`
  - `adb self-test report`
  - `fastboot self-test report`
- Added a service-page button: `Self-test отчёт (.txt)`.
- The report is saved to:
  - `/sdcard/AdbFastboot/reports/selftest-YYYY-MM-DD-HH-mm-ss.txt`
- The report contains:
  - app version;
  - connection/device info;
  - debug logging state;
  - ADB key directory path;
  - profile directory path;
  - captured self-test lines;
  - a full visible log snapshot.
- After report creation, the UI offers:
  - share file;
  - copy report path;
  - close dialog.

## Safety

The report operation reuses the existing read-only self-test. It does not run `flash`, `erase`, `format`, `download`, `install`, `push`, `root`, `remount` or `reboot`.

## Version

- `versionCode 27`
- `versionName 2.2.4-self-test-report`
