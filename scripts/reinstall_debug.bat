@echo off
setlocal
cd /d %~dp0\..
set APK=%CD%\app\build\outputs\apk\debug\app-debug.apk
call scripts\_env.bat || exit /b 1
call scripts\build_debug.bat || exit /b 1
call scripts\check_device.bat || exit /b 1

if /I "%FORCE_UNINSTALL%"=="1" goto force_uninstall
goto install

:force_uninstall
echo [WARN] FORCE_UNINSTALL=1: backing up app data before uninstall...
call scripts\backup_device_data.bat || exit /b 1
if /I "%ALLOW_DATA_LOSS%"=="1" goto do_uninstall
echo [ERROR] FORCE_UNINSTALL=1 also requires ALLOW_DATA_LOSS=1.
echo [INFO] Backup is at logs\device-backups\; restore with scripts\restore_device_data.bat.
exit /b 1

:do_uninstall
echo [WARN] Uninstalling app: data WILL be deleted after the backup.
"%ADB_BIN%" uninstall com.manga.translator.app >nul 2>&1
goto install

:install
"%ADB_BIN%" install -r "%APK%" || exit /b 1
"%ADB_BIN%" shell monkey -p com.manga.translator.app -c android.intent.category.LAUNCHER 1 >nul 2>&1
echo [OK] Install(-r) and launch complete. (data preserved unless FORCE_UNINSTALL=1)
endlocal
