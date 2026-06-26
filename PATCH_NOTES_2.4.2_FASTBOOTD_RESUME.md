# NekoFlash 2.4.2 — Xiaomi Fastboot ROM fastbootd transition

## Goal

Xiaomi Fastboot ROM scripts for dynamic-partition devices often contain a two-stage flow:

1. bootloader fastboot stage for physical/bootloader partitions;
2. `fastboot reboot fastboot`;
3. userspace fastbootd stage for logical partitions such as `system`, `vendor`, `product`, `odm`, `system_ext`, `vendor_dlkm`.

Version 2.4.2 adds a controlled fastbootd transition and automatic resume for the Xiaomi Fastboot ROM Flasher.

## Added

- `PendingXiaomiRomFlash` resume state in `DeviceViewModel`.
- Detection of `fastboot reboot fastboot` / `reboot fastbootd` in prepared ROM plans.
- Bootloader-stage execution before fastbootd transition.
- Automatic `fastboot reboot fastboot` when the selected plan reaches the userspace fastbootd stage.
- USB reconnect resume after fastbootd is detected via `getvar:is-userspace=yes`.
- Resume from the exact action index after the fastbootd reboot action.
- Product guard re-check before resume.
- Timeout for pending fastbootd resume.
- Heuristic fastbootd requirement for logical/dynamic partitions when the ROM script does not contain an explicit `reboot fastboot` line.

## Safety policy

- Automatic resume is supported only for `fastboot reboot fastboot` / fastbootd.
- Intermediate reboots to other targets remain blocked.
- If the app is already in fastbootd but the ROM script contains a bootloader stage before fastbootd, flashing is blocked to avoid skipping critical pre-fastbootd steps.
- `flash_all_lock` and bootloader lock commands remain blocked.
- `update-super` is still parsed and reported, but automatic execution is still blocked until a dedicated safe implementation is added.
- Bootloader locked devices remain blocked from full ROM flashing.

## Files changed

- `DeviceViewModel.kt`
- `XiaomiFastbootRomManager.kt`
- `README.md`
- `BUILDING.md`
- `app/build.gradle`

## Version

- `versionCode 35`
- `versionName 2.4.2-nekoflash`
