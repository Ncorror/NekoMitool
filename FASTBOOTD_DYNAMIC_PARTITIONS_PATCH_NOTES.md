# v2.2.1 — Fastbootd / Dynamic partitions

## Что добавлено

### Диагностика fastbootd

Fastboot precheck теперь дополнительно читает:

- `getvar:is-userspace` — определение режима `bootloader fastboot` / `userspace fastbootd`;
- `getvar:super-partition-name` — имя super-раздела;
- `getvar:max-fetch-size` — максимальный chunk для `fastboot fetch`.

Эти поля выводятся в лог и частично отображаются в карточке устройства.

### Logical partitions

Добавлена поддержка desktop-синтаксиса терминала:

```text
fastboot is-logical <partition>
fastboot logical-info <partition>
fastboot create-logical-partition <partition> <size>
fastboot delete-logical-partition <partition>
fastboot resize-logical-partition <partition> <size>
```

Внутри приложения команды управления logical partitions отправляются в wire-формате Fastboot protocol:

```text
create-logical-partition:<partition>:<size>
delete-logical-partition:<partition>
resize-logical-partition:<partition>:<size>
```

Для размеров поддерживаются байты, `K`, `M`, `G` и `0x...`.

### update-super

Добавлена команда:

```text
fastboot update-super <super.img> [wipe] [superPartition]
```

Команда выполняет:

1. `download:<size>`;
2. передачу файла;
3. `update-super:<superPartition>` или `update-super:<superPartition>:wipe`.

Если `superPartition` не указан, приложение берёт `getvar:super-partition-name`, а если переменная недоступна — использует `super`.

### fastboot fetch

Добавлена команда:

```text
fastboot fetch <partition> [out.img]
```

Реализация:

- читает `partition-size:<partition>`;
- читает `max-fetch-size`;
- если доступны оба значения, скачивает partition chunk-ами через `fetch:<partition>:<offset>:<size>`;
- если размер заранее неизвестен, использует `fetch:<partition>`;
- пишет во временный `.part` файл и переименовывает его только после успешного завершения.

На большинстве устройств AOSP `fetch` реально полезен прежде всего для `vendor_boot` и может требовать `fastbootd`, unlocked/debuggable-состояние.

## Безопасность

- `fastboot flash` по-прежнему жёстко блокируется при `getvar:unlocked = no`.
- Команды `create/delete/resize-logical-partition` и `update-super` требуют отдельного подтверждения.
- Это не whitelist: экспертные raw-команды остаются доступны, но destructive-команды проходят через confirm.

## Версия

- `versionCode 24`
- `versionName 2.2.1-fastbootd-dynamic`
