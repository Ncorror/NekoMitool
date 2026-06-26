# Patch notes 2.3.1-nekoflash

## Что изменено

- Версия приложения поднята до `2.3.1-nekoflash`, `versionCode` — до `32`.
- `build.yml` больше не игнорирует ошибки статической проверки: удалён `|| true` после `scripts/check_project.py`.
- Debug APK и CI-артефакты переименованы в формат `NekoFlash-<version>-debug.apk`.
- `README.md` приведён к актуальной рабочей папке `/sdcard/Download/NekoFlash`.
- `FileProvider` ограничен папками `Download/NekoFlash/logs/` и `Download/NekoFlash/reports/` вместо полного external storage.
- Исправлен путь DocumentsUI для кнопки открытия отчётов: `primary:Download/NekoFlash/reports`.
- Видимые `android:text` и `android:hint` из `activity_main.xml` вынесены в ресурсы `strings.xml` / `values-ru/strings.xml`.
- ADB CNXN host name заменён с `AdbFastbootTool` на `NekoFlash`.
- `scripts/check_project.py` усилен: проверяет версию `2.3.x-nekoflash`, область FileProvider и отсутствие hardcoded `android:text`/`android:hint` в layout.

## Проверка

Выполнено:

```text
python3 scripts/check_project.py
```

Результат:

```text
strings parity: OK (298 keys)
version: code=32, name=2.3.1-nekoflash
WARNING: gradle-wrapper.jar is absent; CI/local build will use Gradle from PATH until the wrapper workflow is run
static project check: OK
```

## Ограничение

APK в контейнере не собирался, потому что в исходном архиве отсутствует `gradle/wrapper/gradle-wrapper.jar`, а системный Gradle не установлен. Проект по-прежнему рассчитан на один из вариантов:

1. GitHub Actions `Build Android APK`, где Gradle 8.4 ставится через `gradle/actions/setup-gradle`;
2. локальный Gradle 8.4+;
3. запуск workflow **Установка Gradle Wrapper** для добавления официального `gradle-wrapper.jar`.
