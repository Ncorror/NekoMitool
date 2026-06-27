# Build / CI patch notes — v2.2.2

Цель патча: сделать проект пригодным к воспроизводимой сборке APK даже в состоянии, когда в исходном архиве отсутствует `gradle/wrapper/gradle-wrapper.jar`.

## Изменения

- `versionCode` обновлён до `25`.
- `versionName` обновлён до `2.2.2-build-ci`.
- `gradlew` и `gradlew.bat` теперь используют официальный `gradle-wrapper.jar`, если он есть.
- Если `gradle-wrapper.jar` отсутствует, скрипты автоматически запускают установленный `gradle` из `PATH`.
- GitHub Actions `build.yml` теперь ставит Gradle 8.4 через `gradle/actions/setup-gradle` и может собрать APK даже до коммита wrapper jar.
- GitHub Actions больше не содержит устаревшие имена артефактов версии `2.1.2`.
- Добавлен `scripts/check_project.py` для статической проверки проекта без Android SDK.
- Добавлен `scripts/build-apk.sh` для локальной Linux/macOS-сборки.
- Добавлен `scripts/build-apk.bat` для локальной Windows-сборки.
- Обновлён workflow `add-wrapper.yml`: он генерирует официальный Gradle Wrapper и коммитит только реальные изменения.

## Ограничение

В этот архив всё ещё не добавлен бинарный `gradle-wrapper.jar`, потому что текущая среда не имеет доступа к `services.gradle.org`. После запуска workflow **«Установка Gradle Wrapper»** в GitHub файл будет сгенерирован официальным Gradle 8.4 и добавлен в репозиторий.

## Проверка

Локально выполнено:

```bash
python3 scripts/check_project.py
```

Проверка покрывает:

- XML-валидность ресурсов;
- синхронизацию `values/strings.xml` и `values-ru/strings.xml`;
- актуальность версии;
- наличие fallback в `gradlew`;
- наличие CI workflow для сборки и генерации wrapper.
