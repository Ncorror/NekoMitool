# v2.2.5 — Structured self-test and forum report bundle

## Что изменено

- `self-test report` теперь создаёт два файла рядом:
  - `selftest-YYYY-MM-DD-HH-mm-ss.txt`;
  - `selftest-YYYY-MM-DD-HH-mm-ss.json`.
- Добавлены команды:
  - `self-test forum`;
  - `self-test zip`;
  - `self-test bundle`;
  - `self-test full`;
  - `adb self-test forum`;
  - `fastboot self-test forum`.
- Добавлена кнопка на сервисной странице: `Self-test ZIP для форума`.
- Forum ZIP теперь содержит:
  - `manifest.txt`;
  - `diagnostic-summary.json`;
  - `adb-info.txt`;
  - `fastboot-info.txt`;
  - `usb-info.txt`;
  - `files.txt`;
  - `file-analysis.txt`;
  - `visible-log.txt`;
  - `log.txt`, если текущий лог-файл существует;
  - `profiles/*.json`, если профили есть;
  - `self-test/selftest.txt` и `self-test/selftest.json`, если ZIP создан через self-test forum.

## JSON-состав

`selftest-*.json` и `diagnostic-summary.json` используют dependency-free JSON formatter без `org.json`, чтобы lightweight host-checks могли работать без Android runtime.

В JSON включаются:

- schema id;
- версия приложения;
- состояние подключения;
- ADB banner/features/shell_v2/public key path;
- Fastboot diagnostics: product, slot, unlocked, userspace, super, download/fetch limits;
- debug logging flag;
- self-test log;
- tail/snapshot видимого лога.

## Безопасность

Self-test и отчётные команды остаются read-only. Они не выполняют:

- `flash`;
- `download`;
- `erase`;
- `format`;
- `install`;
- `push`;
- `root`;
- `remount`;
- `reboot`.

## Версия

- `versionCode 28`
- `versionName 2.2.5-structured-reports`
