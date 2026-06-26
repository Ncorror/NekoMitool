# ADB Sync patch notes — v2.1.4-adb-sync

## Цель

Добавить практическую файловую передачу через ADB без возврата к whitelist-командам:

- `adb push <local-file> <remote-path>`;
- `adb pull <remote-path> [local-file]`;
- базовый `adb install [-r] [-d] [-g] <local.apk>`.

## Что изменено

### 1. ADB sync stream

В `AdbProtocol.kt` добавлена работа с ADB stream `sync:`:

- открытие stream через `A_OPEN`;
- подтверждение `A_OKAY`;
- запись `A_WRTE` с ожиданием ACK;
- чтение ответных `A_WRTE` с обязательной отправкой `A_OKAY`;
- закрытие stream через `A_CLSE`.

### 2. `adb push`

Реализована последовательность ADB file-sync v1:

```text
SEND <remote-path>,<mode>
DATA <chunk>
...
DONE <mtime>
OKAY / FAIL
```

Особенности:

- локальный файл читается блоками по 64 KiB;
- логируется прогресс передачи;
- remote path валидируется на пустую строку и NUL;
- если команда введена как `adb push file /remote/dir/`, UI добавляет имя файла к remote path.

### 3. `adb pull`

Реализована последовательность:

```text
RECV <remote-path>
DATA <chunk>
...
DONE
```

Особенности:

- если локальный путь не указан, файл сохраняется в `/sdcard/AdbFastboot/<remote-name>`;
- запись идёт во временный `.part` файл;
- после `DONE` временный файл атомарно переименовывается в целевой.

### 4. `adb install`

Добавлен базовый install одного APK:

1. APK передаётся через `adb push` в `/data/local/tmp/aft-<timestamp>-<name>.apk`.
2. Затем выполняется:

```text
pm install <options> /data/local/tmp/aft-...apk
```

3. Временный APK удаляется командой `rm -f`.

Поддерживаются обычные параметры, которые пользователь передаёт до имени APK, например:

```text
adb install -r app.apk
adb install -r -d -g app.apk
```

## Ограничения

- `adb sync` каталогов целиком пока не реализован.
- `adb install-multiple`, split APK и streaming install service пока не реализованы.
- `adb pull` не делает предварительный `STAT`; размер файла заранее неизвестен, поэтому прогресс логируется по принятым байтам.
- Результат `pm install` отображается в логе shell-вывода; для точного API-level независимого exit-code в дальнейшем лучше добавить shell v2.

## Проверки

- XML-ресурсы парсятся без ошибок.
- `values` и `values-ru` синхронизированы по string-ключам.
- ZIP проверен через `unzip -t`.
- Kotlin-файлы проверены статически на структуру; полноценная APK-сборка в текущей среде невозможна без `gradle/wrapper/gradle-wrapper.jar`.
