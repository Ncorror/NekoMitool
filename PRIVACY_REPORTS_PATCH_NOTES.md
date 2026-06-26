# v2.2.6 — Private / sanitized reports

Цель патча — сделать forum/self-test отчёты безопаснее для публикации в открытой теме форума.

## Что изменено

- Forum ZIP теперь создаётся в режиме `SANITIZED` по умолчанию.
- `self-test report` тоже сохраняет санитизированные `selftest-*.txt` и `selftest-*.json`.
- Добавлен `ReportSanitizer.kt` — единая точка маскирования отчётов.
- Schema forum report обновлена до `ru.forum.adbfastboottool.forum-report.v3`.
- Schema self-test report обновлена до `ru.forum.adbfastboottool.selftest.v2`.
- В `diagnostic-summary.json` и `selftest.json` добавлено поле `privacyMode`.

## Что маскируется

- Fastboot/ADB serial number.
- `ro.serialno`, `ro.boot.serialno`, строки `serialno:` и похожие serial-поля в логах.
- Host Android manufacturer/model/device/release.
- Абсолютные app-private пути `/data/user/0/...`, `/data/data/...`.
- Путь к ADB public key / ADB key dir.
- Пользовательские storage-пути вида `/sdcard/...`, `/storage/emulated/0/...`, `/data/media/0/...`.
- USB device path `/dev/bus/usb/...`.
- Длинные hex-идентификаторы в логах.

## Что намеренно сохраняется

- VID/PID USB-устройства.
- Interface/endpoints USB.
- ADB features, включая `shell_v2`.
- Fastboot flags: `unlocked`, `secure`, `is-userspace`.
- Slot/dynamic partition information.
- Размеры `max-download-size`, `max-fetch-size`.
- Имена файлов в workspace и profiles, потому что без них сложнее разбирать прошивочный сценарий.

## Совместимость

Функции ADB/Fastboot не изменялись. Патч затрагивает только экспорт отчётов и строки предупреждения о locked bootloader для `fastboot flash`.
