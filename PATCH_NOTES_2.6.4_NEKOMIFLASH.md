# Patch notes 2.6.0–2.6.4 (NekoMiFlash rebrand + декомпозиция + фиксы сборки)

Сводка изменений этой серии.

## 2.6.0 — Переименование в NekoMiFlash
- `app_name` → «NekoMiFlash» (ru/en).
- Рабочая папка → `/sdcard/Download/NekoMiFlash` (+ logs/profiles/reports).
- Обновлены все UI-тексты, FileProvider scoped-пути, WakeLock-тег, ADB hostname.
- applicationId/пакет не тронуты — обновление ставится поверх старого.

## 2.6.1 — TabController (декомпозиция God-object, шаг 1)
- Логика переключения вкладок вынесена из MainActivity в новый `TabController.kt`.
- Состояние `selectedWindow`/`commandContext` переехало в контроллер; в MainActivity
  оставлены геттеры-обёртки для обратной совместимости.
- Цвета табов вынесены в именованные константы.
- Удалены освободившиеся импорты (`ColorStateList`, `MaterialButton`).

## 2.6.3 — Фикс линковки ресурсов (КРИТИЧНО для сборки)
- Строка `layout_help` имела значение `?  Help` / `?  Справка`. Ведущий `?`
  AAPT трактует как ссылку на атрибут темы (`?attr/...`) → сборка падала на
  `processDebugResources` с `resource attr/ Help not found`.
- Исправлено экранированием: `\?  Help` / `\?  Справка`.
- В `check_project.py` добавлена проверка `check_string_values_aapt_safe()` —
  ловит строки, начинающиеся с `?`/`@`, ДО сборки.

## 2.6.4 — Фикс CI-упаковки
- `set -o pipefail` в шаге сборки: раньше `gradle ... | tee` маскировал код
  возврата (tee всегда 0), из-за чего падение gradle проявлялось позже как
  `cp: cannot stat app-debug.apk`. Теперь сборка честно фейлится с причиной.
- Шаг упаковки ищет APK через `find` и пишет понятную диагностику вместо `cp`.
- Release-сборка временно убрана из CI до стабилизации debug.
- Имя артефакта исправлено на `NekoMiFlash`.

## Чистка
- 50 файлов истории разработки (`*_PATCH_NOTES.md`, `BUILD_ATTEMPT_*.txt` и др.)
  перенесены в `docs/`. В корне оставлены README, BUILDING, RELEASE_CHECKLIST,
  SMOKE_TEST_CHECKLIST.

## Проверка
```
strings parity: OK (295 keys)
string values AAPT-safe: OK
version: code=60, name=2.6.4-nekomiflash
static project check: OK
```

## Структура для заливки на GitHub
В корне репозитория должны лежать напрямую: `app/`, `build.gradle`,
`settings.gradle`, `gradle/`, `gradlew`, `.github/`, `scripts/`.
НЕ должно быть лишнего уровня вложенности (например `NekoMiFlash/app/...`).
