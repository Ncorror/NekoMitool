# NekoFlash 2.5.6 — UI Polish

This release focuses on the Step 4 UI polish pass. It does not change Fastboot/ADB flashing logic.

## Added

- Labeled bottom navigation tabs instead of icon-only tabs.
- Route hint under the Classic Recovery Dashboard.
- Xiaomi Fastboot ROM workflow hint directly inside the Xiaomi section.
- Recovery / Bootloop workflow hint directly inside the Recovery section.
- Magisk / Root workflow hint directly inside the Magisk section.
- Files workflow hint directly inside the Files section.
- Reports workflow hint directly inside the Service/Reports section.

## Changed

- Main navigation height and text sizing adjusted for readable labels.
- README / BUILDING updated for 2.5.6.
- Version bumped to `2.5.6-nekoflash` / `versionCode 54`.

## Not changed

- Xiaomi ROM parser and flasher.
- fastbootd resume.
- update-super.
- ARB guard.
- manifest/sparse/split guards.
- storage/download preflight.
- Safety profiles and typed confirmations.
