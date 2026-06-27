# NekoFlash 2.5.3 — Operation Step Queue UI

## Added
- Operation Center now shows a compact step queue for active flashing plans.
- Flash Queue now publishes PENDING/RUNNING/OK/FAILED state per partition.
- Xiaomi Fastboot ROM flashing now publishes step state for bootloader, fastbootd, update-super and logical partition stages.
- Skipped fastbootd transition marker is displayed as SKIPPED instead of hiding the script step.

## Safety impact
- No fastboot/adb protocol behavior changed.
- This is UI/telemetry only: it makes failures and resume points easier to understand.
