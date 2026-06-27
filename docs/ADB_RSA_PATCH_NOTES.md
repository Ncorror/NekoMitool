# ADB RSA patch notes — v2.1.3

## Цель

Сделать ADB-терминал работоспособным в обычном Android-режиме с USB debugging, а не только в recovery/sideload-сценариях.

## Что изменено

1. Добавлен `AdbKeyStore.kt`.
   - Генерирует RSA-2048 ключ приложения.
   - Хранит приватный ключ во внутренней папке приложения.
   - Формирует ADB-compatible public key: `base64(android_pubkey) + comment + NUL`.
   - Подписывает ADB AUTH TOKEN без повторного SHA-1-хэширования: используется DigestInfo(SHA1) + 20-байтный token, затем RSA/PKCS#1 v1.5.

2. Переписана обработка `A_AUTH` в `AdbProtocol.kt`.
   - При первом TOKEN приложение пробует `AUTH_SIGNATURE`.
   - Если устройство ключ не знает, приложение отправляет `AUTH_RSAPUBLICKEY`.
   - После отправки public key приложение ждёт подтверждения `Allow USB debugging` до 60 секунд.

3. `DeviceViewModel` теперь передаёт в `AdbProtocol` внутреннюю папку ключей приложения.

4. Версия обновлена до `2.1.3-adb-rsa`, `versionCode 16`.

## Ограничения

- Интерактивный shell без команды (`adb shell`) пока открывает service, но приложение не реализует полноценный двусторонний stdin/PTY. Практический режим сейчас — одноразовые команды: `adb shell getprop`, `adb shell ls /sdcard`, `adb logcat -d` и т.п.
- `adb push/pull/install` всё ещё требуют отдельной реализации ADB sync-протокола.
- Нужна проверка на реальном target-устройстве: в этой среде нет USB/Android target и отсутствует `gradle-wrapper.jar` для полноценной APK-сборки.
