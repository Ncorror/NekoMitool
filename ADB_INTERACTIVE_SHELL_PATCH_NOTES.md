# ADB Interactive Shell Patch Notes — v2.1.9

## Что добавлено

- `adb shell` без аргументов теперь открывает постоянную интерактивную shell-сессию.
- На target-устройствах с `shell_v2` используется сервис `shell,v2,pty:`.
- На старых/recovery-устройствах без `shell_v2` используется fallback `shell:`.
- Ввод из нижней строки терминала отправляется в открытую shell-сессию через очередь stdin.
- Очередь обрабатывается внутри одного ADB read/write loop, чтобы не было двух конкурентных потоков, читающих один USB endpoint.
- Закрытие сессии: `exit`, `adb shell-stop`, `adb shell-exit`, `:close`, `:exit` или кнопка **Стоп**.

## Ограничения

- Ctrl+C / SIGINT пока не реализован.
- Размер терминала/PTY не передаётся target-устройству.
- Для legacy `shell:` stdout/stderr и exit-code не разделяются.

## Версия

- `versionCode 22`
- `versionName 2.1.9-interactive-shell`
