# Patch notes — 2.0-forum

## Main focus

Version 2.0-forum is a permission hardening and safety release. It does not add dangerous fastboot commands. It improves runtime safety around flashing, sideload, file analysis, and user trust for forum distribution.

## Added

1. Overlay protection without requesting draw-over-apps access:
   - added `android.permission.HIDE_OVERLAY_WINDOWS`;
   - `MainActivity` calls `window.setHideOverlayWindows(true)` on Android 12+;
   - `SYSTEM_ALERT_WINDOW` is intentionally not requested.

2. WakeLock for long operations:
   - added `android.permission.WAKE_LOCK`;
   - `DeviceViewModel` acquires a partial WakeLock only while an operation is active;
   - WakeLock is released after success, failure, cancellation, or disconnect;
   - a 6-hour safety timeout is used to avoid an orphaned WakeLock.

3. New diagnostics UI action:
   - added `btnPermissions`;
   - the Diagnostics page now has **Permissions and safety**;
   - the dialog explains storage, notifications, battery optimization, overlay protection, WakeLock, and intentionally absent permissions.

4. Localized permission strings:
   - English/default strings updated;
   - Russian strings updated.

## Preserved

The app still does not request:

- `SYSTEM_ALERT_WINDOW`;
- `INTERNET`;
- contacts;
- SMS;
- microphone;
- camera;
- phone;
- location.

Foreground service remains `dataSync`. `connectedDevice` was not enabled in this release to avoid Android 14+ foreground-service prerequisite issues during file-only operations such as file inspection and report creation.

## Version

- `versionCode 11`
- `versionName "2.0-forum"`
- GitHub Actions APK name: `AdbFastbootTool-v2.0-forum-debug.apk`

## 2.5.4 — Typed Safety Confirmations
- Added typed safety gates for Xiaomi ROM flashing, destructive Fastboot commands, critical partition flashing, slot switching, and flash queue execution.
- Fixed duplicate declaration line in `MainActivity.kt`.
## 2.5.6 — UI Polish

- Added labeled recovery-style bottom tabs.
- Added inline workflow hints for Xiaomi ROM, Recovery, Magisk, Files, and Reports.
- Kept flashing logic unchanged.

