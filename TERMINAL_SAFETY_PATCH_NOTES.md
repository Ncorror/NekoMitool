# Terminal safety patch v2.1.2

## Исправлено

1. **Старт USB-операций**
   - `DeviceViewModel.startOperation()` теперь захватывает `previousJob` до создания новой coroutine.
   - Убрана ошибка, при которой новая операция могла увидеть саму себя как `activeJob`, вызвать `cancelAndJoin()` и отменить собственное выполнение.

2. **Сброс отмены Fastboot-команд**
   - `FastbootProtocol.sendCommand()` теперь сбрасывает `cancelled=false` перед новой командой.
   - `FastbootProtocol.getVar()` теперь сбрасывает `cancelled=false` перед новым `getvar`.
   - После нажатия **Стоп** последующие `getvar`, `reboot`, raw-команды и диагностика не должны оставаться в старом состоянии отмены.

3. **Flash guard сохранён**
   - `fastboot flash <partition> <file>` блокируется при `getvar:unlocked = no`.
   - Raw-обход `flash:<partition>` также блокируется на уровне `sendCommand()`.
   - `downloadAndRun(file, "flash:...")` не передаёт файл, если загрузчик locked.

4. **Smart-confirm для destructive Fastboot-команд**
   - Добавлен диалог подтверждения для:
     - `fastboot erase <partition>` / `erase:<partition>`;
     - `fastboot format <partition>` / `format:<partition>`;
     - `fastboot set_active <a|b>`;
     - `fastboot flashing unlock/lock/unlock_critical/lock_critical`;
     - рискованных `fastboot oem ...` команд, похожих на unlock/lock/erase/wipe/format/reset.
   - Это не возврат whitelist. После ручного подтверждения команда отправляется как raw Fastboot-команда.

## Проверено в этой среде

- XML-ресурсы успешно парсятся.
- ZIP-архив проходит `unzip -t`.
- Kotlin-файлы проверены на синтаксические ошибки через `kotlinc`; полноценная Android-сборка не выполнена из-за отсутствия Android Gradle Plugin/SDK и `gradle-wrapper.jar` в исходном архиве.

## Остаётся следующим этапом

1. Добавить настоящий `gradle/wrapper/gradle-wrapper.jar` и выполнить `./gradlew assembleDebug`.
2. Реализовать полноценную ADB RSA-авторизацию.
3. Реализовать ADB sync: `push`, `pull`, `install`.
4. Добавить dry-run для очереди прошивки.
5. Отдельно обработать fastbootd/dynamic partitions.
