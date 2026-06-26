# NekoFlash 2.4.10 — Xiaomi storage/transfer preflight

## Added

- Added `StoragePreflight` for Xiaomi Fastboot ROM plans.
- The preflight estimates:
  - source ROM size;
  - referenced image count;
  - selected image bytes;
  - estimated fastboot transfer bytes;
  - largest referenced image;
  - workspace free space;
  - required workspace free space with headroom;
  - fastbootd/userspace requirement.

## Safety

- Xiaomi ROM flashing is blocked when the workspace has less free space than the selected extracted images plus safety headroom.
- Large images over 3 GiB are highlighted in warnings.
- Dynamic/logical partition plans explicitly warn that fastbootd/userspace is required.

## Files changed

- `XiaomiFastbootRomManager.kt`
- `README.md`
- `BUILDING.md`
- `app/build.gradle`
- `scripts/check_project.py`
