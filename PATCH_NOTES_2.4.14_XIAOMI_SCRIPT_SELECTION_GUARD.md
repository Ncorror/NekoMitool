# NekoFlash 2.4.14 — Xiaomi Script Selection Guard

This patch hardens Xiaomi Fastboot ROM script selection before flashing.

## Added

- Script role labeling in ROM analysis and prepared flash plans:
  - `CLEAN_ALL / wipes data`
  - `SAVE_USER_DATA`
  - `SAVE_USER_DATA CANDIDATE / DATA WIPE RISK`
  - `LOCK / BLOCKED`
  - `UNKNOWN`
- Safer selection logic for Xiaomi flash scripts.
- Preference for known save-data scripts:
  - `flash_all_except_storage.*`
  - `flash_all_except_data_storage.*`
  - `flash_all_except_userdata.*`
  - `flash_all_except_data.*`
  - `flash_all_save_data.*`
  - `flash_all_no_wipe.*`
- Explicit listing of available flash script variants in the prepared plan warnings.

## Hardened

- `flash_all_lock.*` and lock-like script names are excluded before mode selection.
- Clean-all mode no longer selects `except`, `save_data`, `no_wipe`, `nowipe` or `preserve` variants.
- Save-data mode prefers scripts without detected data-loss impact when multiple candidates exist.
- Save-data mode still blocks if the selected script contains `erase userdata`, `erase metadata`, `fastboot -w`, `--wipe`, or `update-super ... wipe`.

## Why

Real Xiaomi ROM packages may contain multiple script variants. Previous selection relied mainly on whether the name contained `except`. This patch makes the mode selection stricter and easier to audit in generated reports.
