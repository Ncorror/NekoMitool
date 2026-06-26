# NekoFlash Release Checklist

## Build readiness

- [ ] `python3 scripts/check_project.py` passes.
- [ ] `./gradlew --version` works from a clean checkout.
- [ ] `./gradlew assembleDebug` creates `app-debug.apk`.
- [ ] `./gradlew assembleRelease` creates `app-release-unsigned.apk`.
- [ ] `scripts/build-release.sh` creates `forum-build/` artifacts and SHA-256 checksums.

## APK install smoke test

- [ ] Install debug APK on the Android host phone.
- [ ] Grant All files access for `/sdcard/Download/NekoFlash`.
- [ ] Grant USB permission for ADB and Fastboot devices.
- [ ] Check that logs and reports are created only under `Download/NekoFlash/logs` and `Download/NekoFlash/reports`.

## Xiaomi Fastboot ROM smoke test

- [ ] Import a real Xiaomi/Redmi/POCO Fastboot ROM `.tgz`/`.zip`.
- [ ] Run Analyze ROM.
- [ ] Check script roles: clean all, save data, lock blocked.
- [ ] Check product guard.
- [ ] Check ARB guard.
- [ ] Check storage/download preflight.
- [ ] Check dry-run report.
- [ ] Do not run real flash until the dry-run report is reviewed.

## Recovery tools smoke test

- [ ] Check slot summary.
- [ ] Switch active slot only on an unlocked test device.
- [ ] Reboot bootloader.
- [ ] Reboot fastbootd.
- [ ] Flash stock boot/init_boot/vendor_boot only with verified images.

## Magisk helper smoke test

- [ ] Import patched boot/init_boot image.
- [ ] Let auto-detect choose partition from filename.
- [ ] Confirm partition and file before flashing.
- [ ] Verify boot result on a test device only.

## Release packaging

- [ ] Rename APKs as `NekoFlash-<version>-debug.apk` and `NekoFlash-<version>-release-unsigned.apk`.
- [ ] Generate SHA-256 checksums.
- [ ] Attach `PATCH_NOTES_<version>.md`.
- [ ] Mention that release APK is unsigned unless a local signing config is provided.
