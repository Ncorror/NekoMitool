# ADB interactive shell controls — v2.2.0

## Цель

Добавить управляемое прерывание процесса внутри уже открытого интерактивного `adb shell`, не закрывая всю ADB-сессию.

## Новые команды в интерактивном shell

Когда открыт `adb shell` без команды, нижняя строка ввода теперь принимает служебные команды:

- `:ctrl-c`
- `:sigint`
- `:interrupt`
- `adb shell-ctrl-c`
- `adb shell-interrupt`

Эти команды отправляют в shell stdin байт `0x03` — стандартный Ctrl+C / SIGINT для PTY. Это позволяет остановить зависший `logcat`, `top`, `ping`, `cat`, `tail -f`, `su`-команду или другой foreground-процесс без закрытия всей интерактивной shell-сессии.

Также добавлены EOF-команды:

- `:ctrl-d`
- `:eof`
- `adb shell-ctrl-d`
- `adb shell-eof`

Они отправляют байт `0x04` — Ctrl+D / EOF.

## Реализация

- В `AdbProtocol` добавлены методы:
  - `sendInteractiveShellInterrupt()`
  - `sendInteractiveShellEof()`
  - общий приватный `queueInteractiveShellBytes()`.
- Для Shell v2 команды уходят как packet `SHELL_ID_STDIN` с payload `0x03` или `0x04`.
- Для legacy shell те же байты уходят в raw ADB stream.
- UI не пишет в USB endpoint напрямую: управляющие байты кладутся в уже существующую stdin-очередь интерактивной shell-сессии.

## Что не изменилось

- `adb shell <command>` остаётся one-shot командой.
- `adb shell` без аргументов по-прежнему открывает постоянную интерактивную сессию.
- `exit`, `adb shell-stop`, `adb shell-exit`, `:close`, `:exit` по-прежнему закрывают shell-сессию.
- Кнопка **Стоп** по-прежнему отменяет текущую операцию.

## Версия

- `versionCode 23`
- `versionName 2.2.0-interactive-shell-controls`
