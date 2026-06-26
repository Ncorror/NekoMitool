# NekoFlash 2.5.5 — Safety Profiles

This release adds a safety-profile layer above the existing typed confirmations and flash preflight checks.

## Added

- New **Safety Profile** card on the Home screen.
- Three profiles:
  - **Novice**: diagnostics, reports, file import and analysis only. Flash/write operations are blocked or disabled.
  - **Standard**: guided flash actions are available, but the full terminal and high-risk actions remain locked.
  - **Expert**: full terminal becomes visible; high-risk actions still require a separate typed unlock.
- Separate **High-risk actions** gate for:
  - Xiaomi `clean all`;
  - `vbmeta`, `super`, `userdata`, `metadata` write paths;
  - active slot switching;
  - destructive Fastboot terminal commands such as `erase`, `format`, `update-super`, `oem lock/unlock`, `flashing lock/unlock`.
- Header profile indicator:
  - `SAFE` for Novice;
  - `STD` for Standard;
  - `EXP` for Expert.

## Safety behavior

- Novice mode blocks flash/write operations before file selection.
- Standard mode allows guided flashing, Magisk helper and save-data Xiaomi ROM mode, but keeps the full terminal hidden.
- Expert mode shows the terminal, but destructive commands remain blocked until the user enables high-risk actions by typing `EXPERT`.
- Existing typed confirmations remain active even after high-risk actions are unlocked.

## Files changed

- `app/src/main/java/ru/forum/adbfastboottool/MainActivity.kt`
- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values-ru/strings.xml`
- `app/build.gradle`

## Version

- `versionCode 53`
- `versionName 2.5.5-nekoflash`
