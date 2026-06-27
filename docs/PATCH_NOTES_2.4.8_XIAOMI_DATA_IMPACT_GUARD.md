# NekoFlash 2.4.8 — Xiaomi ROM Data Impact Guard

## Summary

This patch hardens Xiaomi Fastboot ROM flashing around data-wipe behavior. Real Xiaomi scripts may use explicit `erase userdata`, `erase metadata`, `update-super ... wipe`, or the host-side shortcut `fastboot -w`. NekoFlash now summarizes these risks before flashing and handles standalone `fastboot -w` deliberately.

## Changes

- Added `DataImpact` to `XiaomiFastbootRomManager.ScriptPlan`.
- Added `PlanCommand.WipeData` and `PreparedAction.WipeData`.
- Added explicit parser support for standalone `fastboot -w` / `fastboot --wipe`.
- Added dry-run `DATA IMPACT SUMMARY` blocks.
- Save-data mode now blocks when any data-loss risk is detected.
- Clean-all mode converts standalone `fastboot -w` into guarded protocol erase operations.
- `erase:metadata` and `erase:cache` are attempted only when `partition-size:<name>` is reported.

## Safety policy

- `flash_all_lock` remains blocked.
- Product mismatch remains blocked.
- ARB downgrade remains blocked.
- `update-super` still requires confirmed fastbootd/userspace.
- `fastboot -w` is never passed as a raw protocol command, because it is a desktop fastboot client shortcut, not a bootloader command.
