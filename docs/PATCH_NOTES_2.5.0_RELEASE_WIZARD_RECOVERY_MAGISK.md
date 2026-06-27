# NekoFlash 2.5.0 — Release readiness, Xiaomi Flash Wizard, Recovery tools, Magisk helper

## Version

- `versionCode 48`
- `versionName 2.5.0-nekoflash`

## Build & release readiness

- Added `gradle/wrapper/gradle-wrapper.jar` bootstrap so the project can start via `./gradlew` from source archives.
- Added `scripts/build-release.sh`.
- Updated GitHub Actions to build debug and unsigned release APKs.
- Added `RELEASE_CHECKLIST.md`.
- Updated `BUILDING.md` and `README.md`.

## Xiaomi Flash Wizard

- Added Wizard button to the Xiaomi Fastboot ROM section.
- Wizard explains the flow before flashing: script analysis, product/unlocked/ARB checks, storage/download preflight, data impact, safe plan execution.
- Direct analyze/clean/save/resume buttons remain available.

## Recovery / bootloop tools

Added UI actions:

- Check slot.
- Switch active slot.
- Reboot fastbootd.
- Reboot bootloader.
- Flash stock `boot.img`.
- Flash stock `init_boot.img`.
- Flash stock `vendor_boot.img`.
- Flash `vbmeta.img` / patched vbmeta image.

All image flashing still goes through the existing preflight and double-confirm path.

## Magisk / root helper

Added UI actions:

- Auto flash patched image, guessing `boot`, `init_boot`, or `vendor_boot` from filename.
- Flash patched `boot` explicitly.
- Flash patched `init_boot` explicitly.
- Show Magisk guide.

## Safety notes

- Bootloader must be unlocked for write commands.
- NekoFlash does not implement a Xiaomi Mi Unlock clone.
- No Xiaomi account credentials, cookies, service tokens, or private unlock APIs are used.
- `vbmeta --disable-verity/--disable-verification` desktop flags are not emulated; use an already prepared/patched vbmeta image if needed.
