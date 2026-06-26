# NekoFlash 2.4.4 — Xiaomi ROM dry-run and checkpoints

## Added

- Added dry-run sections to Xiaomi Fastboot ROM analysis reports.
- Added flash session reports: `reports/xiaomi-rom-flash-session-*.txt`.
- The session report records selected ROM, mode, script, resume index, diagnostics, full action plan and per-step progress.
- Progress states: `START`, `OK`, `FAILED`, `WAIT_FASTBOOTD`, `COMPLETED`.
- Fastboot diagnostics are recorded in a sanitized compact form: product, slot, unlocked, secure, is-userspace and super partition name.

## Changed

- Xiaomi product guard extraction now uses the more robust `extractExpectedProducts()` parser.
- Fastboot script parsing now accepts `%fastboot%`, `$fastboot` and `${fastboot}` wrappers used by real Xiaomi scripts.
- Static checks verify the new dry-run/checkpoint hooks.

## Safety

- No Xiaomi Account logic is added.
- Bootloader lock commands remain blocked.
- Product mismatch remains blocked.
- update-super still requires confirmed fastbootd/userspace.
