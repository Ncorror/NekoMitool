# NekoFlash 2.4.3 — update-super fastbootd support

## Что добавлено

- Xiaomi Fastboot ROM Flasher теперь умеет выполнять `update-super` из `flash_all` / `fastboot-info`-подобных сценариев.
- `update-super` выполняется только после подтверждения `getvar:is-userspace=yes`, то есть в fastbootd/userspace.
- Перед выполнением приложение извлекает referenced super/super-empty image, передаёт его через `download:<size>` и затем отправляет команду `update-super:<superPartition>[:wipe]`.
- Имя super-раздела берётся из `getvar:super-partition-name`, fallback — `super`.
- Если план содержит `update-super`, но устройство ещё в bootloader fastboot, приложение переводит устройство в fastbootd и продолжает с нужного шага после переподключения.

## Защита

- Bootloader lock / flash_all_lock по-прежнему блокируются.
- Product mismatch по-прежнему блокируется.
- `update-super wipe` считается wipe-сценарием и блокирует режим save data.
- `update-super` не выполняется, если `is-userspace=yes` не подтверждён.

## Ограничения

- Поддерживается стандартный fastboot protocol command `update-super:<superPartition>[:wipe]`.
- Если конкретный OEM требует нестандартный vendor fastboot-клиент, операция может вернуть FAIL, и лог покажет текст загрузчика/fastbootd.
