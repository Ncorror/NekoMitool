# NekoFlash 2.4.6 — Xiaomi anti-rollback guard

## Added

- Added Fastboot diagnostics for `getvar:anti` with `getvar:antirollback` fallback.
- Added Xiaomi Fastboot ROM anti-rollback extraction from:
  - `flash_all*.bat` / `flash_all*.sh` lines containing `anti:` / `antirollback:` / rollback index metadata;
  - small ROM metadata files such as `android-info.txt`, `misc_info.txt`, `anti*` and `*rollback*`.
- Added pre-flash ARB guard:
  - if the device reports a higher rollback index than the ROM, the Xiaomi ROM flash is blocked;
  - if only one side reports ARB, NekoFlash logs a warning and keeps existing product/unlocked guards active.
- Added ARB data to dry-run analysis reports and flash-session diagnostics.
- Added critical firmware partition detection for Xiaomi ROM plans.

## Safety policy

NekoFlash does not bypass anti-rollback. It only reads reported indexes and blocks obvious downgrade/rollback plans. If ARB cannot be determined, it logs the limitation instead of pretending the ROM is safe.
