# v2.2.7 — Self-test UI status + reports folder

## Summary

This patch improves the support workflow after private/sanitized reports were added in v2.2.6.
It does not change ADB/Fastboot command execution logic.

## Added

- Visible self-test status block on the service page:
  - `SELF-TEST: NOT RUN`
  - `SELF-TEST: RUNNING`
  - `SELF-TEST: PASS`
  - `SELF-TEST: WARN/FAIL`
- `DeviceViewModel.SelfTestStatus` LiveData with:
  - result enum,
  - summary,
  - update timestamp,
  - TXT report path,
  - JSON report path.
- Button: `Открыть папку reports`.
- Terminal shortcuts:
  - `reports`
  - `open reports`
  - `open-reports`
  - `adb reports`
  - `fastboot reports`

## Reports folder opening

The app opens DocumentsUI with an initial tree URI for:

```text
/sdcard/AdbFastboot/reports
```

If DocumentsUI cannot be opened, the app copies the path to the clipboard and logs the error.

## Privacy

Report privacy behavior remains unchanged from v2.2.6: forum ZIP and self-test reports are sanitized by default.

## Version

```text
versionCode 30
versionName 2.2.7-self-test-ui
```
