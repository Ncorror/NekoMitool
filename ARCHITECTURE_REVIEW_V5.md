# Архитектурное ревью и предложения по развитию — v5

## 1. Текущее состояние (аудит)

Кодовая база зрелая: 13 Kotlin-файлов, ~7 400 строк. Реализованы полноценные ADB (с RSA-handshake, shell v2, sync, install-multiple) и Fastboot (включая fastbootd, logical partitions, fetch) стеки поверх USB Host API. Сильные стороны:

- `DeviceViewModel` уже сериализует USB-операции через `operationGeneration` (AtomicLong) и единый `startOperation`, что исключает параллельные записи в один endpoint.
- WakeLock берётся на время операции, ForegroundService поднимается корректно.
- Контрольные суммы (`HashUtils.verifyFileWithSidecars`) считаются за один проход, проверка `max-download-size` перед download есть.
- Отчёты санитизируются (`ReportSanitizer`) — пути, серийники, ключи вычищаются.

Слабые места, которые устраняются в этом релизе:

1. **Нет онбординга и единой точки выдачи разрешений** — `MANAGE_EXTERNAL_STORAGE` запрашивался прямо из `MainActivity.onCreate`, без объяснения и без гейта.
2. **Нет проверки заряда батареи** перед прошивкой. Прошивка `boot`/`recovery` при 5% заряда хоста = кирпич.
3. **Нет двойного подтверждения** на критических разделах в одном месте (логика разбросана по диалогам).
4. **UI главного экрана плотный, но статус-индикаторы текстовые**, а не «лампочки».
5. **Цвета захардкожены** в layout/коде, тема не извлекается из брендового изображения.

## 2. Что сделано в v5

| Область | Изменение |
|---|---|
| Onboarding | Новый `WelcomeActivity` с полноэкранным `bg_welcome.png` (`centerCrop`), кастомным чекбоксом через `selector`, гейтом «чекбокс заблокирован пока не выданы все разрешения» |
| Dynamic color | `ThemePalette` извлекает Primary/Secondary/Background из `bg_welcome.png` через Palette API и кеширует в `SharedPreferences` |
| Foolproof | `PreflightValidator` — единая проверка перед прошивкой: заряд хоста ≥ порога, заряд цели (через ADB `dumpsys battery`, если доступен), слот A/B, соответствие имени файла разделу, контрольные суммы |
| Double-check | `confirmCriticalFlash()` — двойной диалог для `boot`/`recovery`/`init_boot`/`vbmeta` |
| Стабильность | `UsbConnectionWatcher` — реакция на обрыв (DETACHED), сохранение стейта, попытка восстановления |
| Энергия | Авто-снижение яркости окна на время длинной записи (`DimController`) |
| CI/CD | Обновлённый `build.yml` — JDK 17, кеш Gradle, сборка + загрузка APK с SHA-256 |

## 3. Предложения по дальнейшему развитию

### 3.1. Интеграция нативного fastboot для ARM
Сейчас весь Fastboot-протокол реализован на Kotlin поверх USB bulk transfer. Это переносимо, но повторяет работу, которую AOSP `fastboot` уже делает идеально. Вариант: упаковать prebuilt `fastboot` (arm64-v8a) в `assets/`, распаковывать в `filesDir`, `chmod 700`, и проксировать команды через `ProcessBuilder`. USB при этом отдаётся бинарю через `libusb`+`/dev/bus/usb` (нужен root или `UsbManager.openDevice` → fd). Плюс: поддержка `flashall`, `update zip`, `fastboot boot`, sparse-образов «из коробки». Минус: ABI-зависимость, проблемы с SELinux на части устройств.

### 3.2. Парсинг payload.bin (A/B OTA)
crDroid/LineageOS поставляют `payload.bin` внутри OTA-zip. Сейчас прошить из него раздел нельзя. Стоит добавить `PayloadParser`: читать `payload_metadata`, извлекать `partition_operations` для нужного раздела, применять `REPLACE`/`REPLACE_XZ`/`REPLACE_BZ` (распаковка XZ через `org.tukaani:xz`), собирать чистый образ раздела в кеш и прошивать его обычным `flashPartition`. Это убирает необходимость вручную распаковывать `boot.img` для рута Magisk на POCO F7 (onyx) и подобных.

### 3.3. Оптимизация чанков USB
Сейчас download шлёт фиксированными блоками. Реальная пропускная зависит от `bMaxPacketSize` endpoint-а и от того, выровнен ли буфер. Рекомендую:
- читать `endpoint.maxPacketSize` и слать кратными ему чанками (обычно 512 на HS, 1024 на SS);
- держать 2–3 буфера в кольце и заполнять следующий, пока текущий уходит в `bulkTransfer` (двойная буферизация через `Channel`);
- для крупных образов (>256 МБ) измерять и логировать MB/s, чтобы на форуме сравнивать кабели/хосты.

### 3.4. Sparse-образы (chunked ext4)
Большие `super.img`/`system.img` от вендора — sparse. Нативный fastboot их понимает, наша реализация — нет. Стоит добавить `SparseImageReader`, который на лету разворачивает sparse-чанки в raw-поток во время download, без распаковки на диск (экономит место на хосте).

### 3.5. Shizuku вместо MANAGE_EXTERNAL_STORAGE
Для доступа к файлам без «доступа ко всем файлам» можно интегрировать Shizuku (`moe.shizuku`): тогда чтение образов идёт через привилегированный сервис, а не через широкий storage-permission. Это аккуратнее для пользователей, которым неприятен MANAGE_EXTERNAL_STORAGE.

### 3.6. Профили устройств с онлайн-обновлением
`DeviceProfileManager` читает локальные JSON. Можно добавить опциональную подгрузку профилей с форумного git-репозитория (raw URL), чтобы `allowed_partitions` для новых кодовых имён (onyx и т.п.) приходили без обновления APK.

### 3.7. Полноценный Compose-слой
XML-разметка работает, но дальше её удобнее переписать на Jetpack Compose + Material 3 dynamic color. Тогда извлечённая из картинки палитра прокидывается в `ColorScheme` напрямую, а плотный «инженерный» UI собирается из переиспользуемых `@Composable` (StatusPill, LogConsole, PartitionGrid).
