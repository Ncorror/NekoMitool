# v2.2.3 — Self-test / Smoke-test

Добавлен безопасный диагностический режим для проверки ADB/Fastboot без записи на устройство.

## Команды

В терминале доступны:

```text
self-test
selftest
smoke-test
doctor
adb self-test
fastboot self-test
```

Также добавлена кнопка **Self-test / Smoke-test** на сервисной странице.

Добавлен файл `SMOKE_TEST_CHECKLIST.md` для проверки APK на реальных устройствах.

## ADB self-test

Проверяет:

- ADB banner и список features;
- наличие `shell_v2`;
- базовый `adb shell`;
- чтение `getprop`;
- `id`;
- `pm path android`;
- корректность shell v2 exit-code через `exit 7`;
- ADB sync `STAT` для `/sdcard` и `/data/local/tmp`;
- путь сохранённого ADB public key.

Self-test не выполняет `install`, `push` с записью, `root`, `remount`, `reboot` и другие destructive/service операции.

## Fastboot self-test

Проверяет:

- `product`;
- `serialno`;
- `unlocked`;
- `max-download-size`;
- режим `bootloader fastboot` vs `fastbootd/userspace`;
- `super-partition-name` и `max-fetch-size` в fastbootd;
- пробный `is-logical:<partition>` для типовых разделов.

Self-test не выполняет `download`, `flash`, `erase`, `format`, `update-super`.

## Версия

- `versionCode 26`
- `versionName 2.2.3-self-test`
