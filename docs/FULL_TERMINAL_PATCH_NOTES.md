# Full terminal patch notes

Изменения для сборки `2.1.1-full-terminal-flash-guard`:

1. Снят строгий whitelist ручных Fastboot-команд.
2. `fastboot flash <partition> <file.img>` теперь работает из терминала: приложение само выполняет `download:<size>` → передачу файла → `flash:<partition>`.
3. `fastboot boot <file.img>` работает через `download:<size>` → `boot`.
4. `fastboot erase/format/getvar/set_active/reboot/oem/flashing/...` отправляются в загрузчик как протокольные команды или raw-команды.
5. `fastboot flash` снова имеет жёсткую остановку при `getvar:unlocked = no`; остальные диагностические и сервисные команды полного терминала не блокируются.
6. Добавлен ADB terminal через ADB services:
   - `adb shell <command>` → `shell:<command>`;
   - `adb exec <command>` → `exec:<command>`;
   - `adb reboot [target]` → `reboot:<target>`;
   - `adb root`, `adb unroot`, `adb remount`, `adb tcpip`, `adb usb`, `adb disable-verity`, `adb enable-verity`;
   - `adb raw <service>` / `adb service <service>` для прямого ADB service.
7. `adb push/pull/sync/install` через локальную передачу файлов пока не эмулируются, потому что требуют отдельной реализации ADB sync-протокола.
8. Экспертный ввод включён по умолчанию для новых установок.
9. Обновлены русские и английские строки интерфейса, README и versionName/versionCode.

Проверка в этой среде: XML-файлы успешно распарсены. Полная Gradle-сборка не выполнена, потому что в исходном архиве отсутствует `gradle/wrapper/gradle-wrapper.jar`, а системный Gradle в контейнере не установлен.
## Patch 2.1.1 — flash guard

- Восстановлена жёсткая остановка именно для `fastboot flash`, если `getvar:unlocked` возвращает `no`.
- Защита поставлена на уровне `FastbootProtocol.flashPartition()` и `FastbootProtocol.sendCommand()`, поэтому её нельзя обойти через raw-команду `flash:<partition>`.
- Для `downloadAndRun()` добавлена ранняя проверка: если команда после download является `flash:*`, файл не передаётся в download-буфер при locked bootloader.
- Остальной полный терминал сохранён: `getvar`, `reboot`, `boot`, `erase`, `format`, `oem`, `flashing`, ADB shell/service остаются доступными как raw/expert-команды.

