# NekoFlash 2.4.0 — Xiaomi Fastboot ROM Flasher

## Цель

Версия 2.4.0 добавляет безопасный сценарий прошивки Xiaomi/Redmi/POCO Fastboot ROM без встроенной Xiaomi Account-разблокировки.

## Что добавлено

- Новый UI-блок **Xiaomi Fastboot ROM** на вкладке Fastboot.
- Импорт и анализ Fastboot ROM в форматах:
  - `.zip`
  - `.tgz`
  - `.tar`
  - `.tar.gz`
- Новый файл `XiaomiFastbootRomManager.kt`:
  - чтение `flash_all*.bat/.sh` из ZIP/TAR/TGZ;
  - парсинг команд `fastboot flash`, `erase`, `format`, `set_active`, `reboot`;
  - извлечение product guard из строк вида `product: *codename`;
  - выбор сценариев `flash_all` и `flash_all_except_storage`;
  - блокировка `flash_all_lock` и команд `flashing lock` / `oem lock` / `lock critical`;
  - извлечение только тех образов, которые реально указаны в выбранном скрипте.
- Новые действия в `DeviceViewModel`:
  - `analyzeXiaomiFastbootRom(...)`;
  - `runXiaomiFastbootRom(...)`.
- Новые кнопки в `activity_main.xml`:
  - анализ Fastboot ROM;
  - прошивка `clean all`;
  - прошивка `save user data`.
- Добавлены RU/EN строки интерфейса.
- Обновлена версия:
  - `versionCode 33`
  - `versionName 2.4.0-nekoflash`

## Защитные ограничения

- Полная ROM-прошивка блокируется при `getvar:unlocked = no`.
- Если product ROM не совпадает с `getvar:product`, прошивка отменяется.
- Если выбранный скрипт содержит bootloader lock, прошивка отменяется.
- Если в скрипте есть неизвестные Fastboot-команды, прошивка отменяется, чтобы не выполнять частичную прошивку.
- Если в скрипте есть reboot до конца плана, прошивка отменяется: автоматический reconnect/resume пока не реализован.

## Что не добавлялось

- Xiaomi Account login.
- Получение `serviceToken`, `passToken`, `ssecurity`.
- Приватные Xiaomi unlock API.
- Обход ожидания Xiaomi unlock.
- Автоматическая прошивка скриптов `flash_all_lock`.

## Проверка

```text
strings parity: OK (313 keys)
version: code=33, name=2.4.0-nekoflash
static project check: OK
```

`gradle-wrapper.jar` по-прежнему отсутствует в исходном архиве, поэтому локальная сборка через `./gradlew` требует предварительного восстановления wrapper или системный Gradle.
