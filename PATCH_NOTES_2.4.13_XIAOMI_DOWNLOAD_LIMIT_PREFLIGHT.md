# NekoFlash 2.4.13 — Xiaomi Download Limit Preflight

## Summary

This release adds an explicit preflight guard for Fastboot download limits before running Xiaomi Fastboot ROM flashing.

## Added

- New `FASTBOOT DOWNLOAD LIMIT PREFLIGHT` block before Xiaomi ROM flashing.
- Compares every planned transfer action against:
  - device-reported `getvar:max-download-size`;
  - NekoFlash single-download implementation limit (`0xffffffff`, approximately 4 GiB).
- Writes the same preflight block into `xiaomi-rom-flash-session-*.txt`.
- Blocks the ROM flash before the first write command if any planned `flash` or `update-super` payload is larger than the supported limit.

## Why this matters

Large Xiaomi Fastboot ROM packages can contain very large `super.img`, `system.img`, `vendor.img`, or sparse/split images. Without an early preflight, a flash session may start normally and then fail during a later download with `max-download-size` or `data too large` errors.

NekoFlash now detects that condition before executing destructive write steps.

## Behavior

If the device reports a usable `max-download-size` and every planned payload fits, the log shows:

```text
=== FASTBOOT DOWNLOAD LIMIT PREFLIGHT ===
Result: OK — all planned transfers are within the reported download limit.
```

If a payload is too large, the session is blocked before flashing:

```text
Blocked:
  - step 12 flash super ← super.img is 5.20 GB, larger than device max-download-size 4.00 GB
```

If the device does not report `max-download-size`, NekoFlash warns and relies on runtime protocol checks.

## Version

- `versionCode`: 46
- `versionName`: `2.4.13-nekoflash`
