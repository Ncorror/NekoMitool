# NekoFlash 2.5.4 — Typed Safety Confirmations

This release hardens destructive actions in the Classic Recovery UI.

## Added

- Typed safety gate for destructive Fastboot terminal commands.
- Typed safety gate for `download + destructive command` actions.
- Typed safety gate for critical partition flashing:
  - `FLASH` for boot/init_boot/vendor_boot/recovery.
  - `VBMETA` for vbmeta-family partitions.
- Typed safety gate for Xiaomi Fastboot ROM flashing:
  - `CLEAN ALL` for clean-all/wipe mode.
  - `FLASH` for save-data mode.
- Typed safety gate for active slot switching:
  - `SLOT`.
- Typed safety gate for flash queue execution:
  - `FLASH QUEUE` normally.
  - `VBMETA` if the queue includes vbmeta.

## Fixed

- Removed an accidental duplicate `guessPartitionFromFileName()` declaration line in `MainActivity.kt`.

## Notes

This release does not change the Xiaomi ROM flashing backend. It only adds a stronger confirmation layer before high-risk operations.
