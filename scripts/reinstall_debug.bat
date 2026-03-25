@echo off
setlocal
cd /d %~dp0\..
set APK=%CD%\app\build\outputs\apk\debug\app-debug.apk
call scripts\_env.bat || exit /b 1
call scripts\build_debug.bat || exit /b 1
call scripts\check_device.bat || exit /b 1
"%ADB_BIN%" uninstall com.manga.translator.app >nul 2>&1
"%ADB_BIN%" install -r "%APK%" || exit /b 1
"%ADB_BIN%" shell monkey -p com.manga.translator.app -c android.intent.category.LAUNCHER 1 >nul 2>&1
echo [OK] Reinstall and launch complete.
endlocal
