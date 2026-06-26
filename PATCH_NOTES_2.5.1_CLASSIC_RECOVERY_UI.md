# NekoFlash 2.5.1 — Classic Recovery UI

## Scope

This release adapts interface ideas from a classic OrangeFox/TWRP-style recovery theme without copying theme PNG assets or recovery-specific XML runtime code.

## Added

- Classic Recovery Dashboard on the Home page.
- Six large recovery-style workflow tiles:
  - Xiaomi ROM Wizard
  - Recovery / Bootloop
  - Magisk / Root
  - Files / Import
  - Terminal / Console
  - Reports / Service
- New project-owned vector icons:
  - `ic_nf_rom.xml`
  - `ic_nf_recovery.xml`
  - `ic_nf_magisk.xml`
  - `ic_nf_files.xml`
  - `ic_nf_terminal.xml`
  - `ic_nf_reports.xml`
- New EN/RU strings for the dashboard.
- MainActivity click handlers for the dashboard tiles.

## Kept unchanged

- Xiaomi Fastboot ROM guards.
- fastbootd resume logic.
- update-super logic.
- ARB guard.
- manifest / sparse / storage / max-download-size preflight.
- Recovery and Magisk helper actions.

## Notes

The uploaded classic recovery theme is not an Android Activity layout. Its XML and PNG assets were not copied into NekoFlash. This patch uses only UI concepts: large tiles, recovery-style dark/orange palette, persistent device status, and fast access to dangerous workflows.

## Validation

```text
strings parity: OK
version: 2.5.1-nekoflash
static project check: OK
```
