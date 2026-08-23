@echo off
setlocal
cd /d %~dp0\..
set APK=%CD%\app\build\outputs\apk\debug\app-debug.apk
call scripts\_env.bat || exit /b 1
call scripts\build_debug.bat || exit /b 1
call scripts\check_device.bat || exit /b 1
if /I "%FORCE_UNINSTALL%"=="1" (
  echo [WARN] FORCE_UNINSTALL=1, uninstalling app before install...
  "%ADB_BIN%" uninstall com.manga.translator.app >nul 2>&1
)
"%ADB_BIN%" install -r "%APK%" || exit /b 1
"%ADB_BIN%" shell monkey -p com.manga.translator.app -c android.intent.category.LAUNCHER 1 >nul 2>&1
echo [OK] Install(-r) and launch complete. (data preserved unless FORCE_UNINSTALL=1)
endlocal
