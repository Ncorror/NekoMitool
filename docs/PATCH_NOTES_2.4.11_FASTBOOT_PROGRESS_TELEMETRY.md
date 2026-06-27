# NekoFlash 2.4.11 — Fastboot transfer progress telemetry

## Summary

This patch improves long Xiaomi Fastboot ROM flashing sessions by adding richer transfer telemetry and more actionable fastboot error hints.

## Changes

- Version bumped to `versionCode 44`, `versionName 2.4.11-nekoflash`.
- `FastbootProtocol` now logs fastboot download progress every ~5% and at least every 5 seconds during long transfers.
- Transfer logs now include:
  - bytes sent / total bytes,
  - current window speed,
  - average speed,
  - estimated time remaining.
- Large `flash` and `download + update-super` operations now warn users not to background the app or disconnect OTG/USB.
- Fastboot `FAIL` packets now receive a best-effort explanation for common cases:
  - locked bootloader,
  - permission denied / not allowed,
  - unknown or missing partition,
  - file too large / max-download-size,
  - sparse image problems,
  - vbmeta / verification / signature problems,
  - unsupported commands,
  - dynamic-partition allocation/storage errors,
  - USB timeout.
- Xiaomi ROM flash session reports now include elapsed time per progress entry and transfer size for `flash` / `update-super` actions.
- Session reports now include an operational note about keeping the app in foreground and OTG power stable.

## Safety policy

This patch does not add Xiaomi account unlock logic, does not bypass Xiaomi wait time, and does not loosen existing lock/wipe/product/ARB guards.
