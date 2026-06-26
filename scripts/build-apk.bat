@echo off
setlocal
cd /d "%~dp0\.."

python scripts\check_project.py
if errorlevel 1 goto fail

if exist gradle\wrapper\gradle-wrapper.jar (
  call gradlew.bat assembleDebug
  goto after_build
)

where gradle >NUL 2>NUL
if errorlevel 1 (
  echo ERROR: neither gradle\wrapper\gradle-wrapper.jar nor Gradle in PATH was found.
  echo Run the GitHub Actions workflow "Установка Gradle Wrapper" or install Gradle 8.4+ locally.
  goto fail
)

gradle assembleDebug

:after_build
if errorlevel 1 goto fail
if exist app\build\outputs\apk\debug\app-debug.apk (
  if not exist forum-build mkdir forum-build
  copy /Y app\build\outputs\apk\debug\app-debug.apk forum-build\NekoFlash-debug.apk >NUL
  echo Built: forum-build\NekoFlash-debug.apk
)
exit /b 0

:fail
exit /b 1
