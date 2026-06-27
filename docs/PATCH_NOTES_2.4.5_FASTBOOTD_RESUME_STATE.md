# NekoFlash 2.4.5 — Persistent fastbootd resume state

## Changed

- Added persistent Xiaomi Fastboot ROM resume state for the bootloader-fastboot to fastbootd transition.
- Added UI button `Resume last fastbootd step`.
- Added resume marker reports in `/sdcard/Download/NekoFlash/reports/xiaomi-rom-resume-state-*.txt`.
- Fresh Xiaomi ROM flash clears stale resume state before starting.
- Completed, timed-out, product-mismatched, or failed fastbootd-transition sessions clear the persisted resume state.

## Safety notes

- Resume does not bypass the product guard or bootloader/unlocked checks.
- Resume is intended only for continuing the same ROM flash after `fastboot reboot fastboot` and USB reconnection.
- Users should not use Resume for a different ROM, different device, or after manually flashing unrelated partitions.
