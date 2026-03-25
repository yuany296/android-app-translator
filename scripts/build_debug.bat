@echo off
setlocal
cd /d %~dp0\..
if not exist gradlew.bat (
  echo [ERROR] gradlew.bat not found.
  exit /b 1
)
call gradlew.bat :app:assembleDebug
if errorlevel 1 exit /b 1
echo [OK] APK: %CD%\app\build\outputs\apk\debug\app-debug.apk
endlocal
