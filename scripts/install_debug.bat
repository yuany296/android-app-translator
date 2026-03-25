@echo off
setlocal
cd /d %~dp0\..
set APK=%CD%\app\build\outputs\apk\debug\app-debug.apk
if not exist "%APK%" (
  echo [INFO] APK not found, building first...
  call gradlew.bat :app:assembleDebug || exit /b 1
)
call scripts\_env.bat || exit /b 1
call scripts\check_device.bat || exit /b 1
"%ADB_BIN%" install -r "%APK%"
if errorlevel 1 exit /b 1
echo [OK] Install success: %APK%
endlocal
