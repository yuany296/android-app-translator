@echo off
setlocal EnableExtensions
cd /d "%~dp0\.."

rem ============================================================================
rem Backup debug app local data: files / shared_prefs / databases / no_backup.
rem
rem Why: connectedDebugAndroidTest lets Gradle/AGP uninstall the app after the
rem run, deleting files/ and shared_prefs/ with it. This script only backs up;
rem it never installs or uninstalls the app.
rem
rem Usage:
rem   scripts\backup_device_data.bat
rem   set ANDROID_SERIAL=xxxx && scripts\backup_device_data.bat
rem ============================================================================

call scripts\_env.bat || exit /b 1
call scripts\check_device.bat || exit /b 1

set "PKG=com.manga.translator.app"
"%ADB_BIN%" get-state 1>nul 2>nul
if errorlevel 1 (
  echo [ERROR] adb device is not ready. Check: scripts\check_device.bat
  exit /b 1
)

"%ADB_BIN%" shell pm path %PKG% 2>nul | findstr /C:"package:" >nul
if errorlevel 1 (
  echo [ERROR] %PKG% is not installed.
  exit /b 1
)

if not exist "logs\device-backups" mkdir "logs\device-backups"

for /f %%i in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set "TS=%%i"
set "OUT=%CD%\logs\device-backups\%PKG%-%TS%.tar"

set "DATA_DIRS=files shared_prefs"
"%ADB_BIN%" shell run-as %PKG% test -d databases 1>nul 2>nul
if not errorlevel 1 set "DATA_DIRS=%DATA_DIRS% databases"
"%ADB_BIN%" shell run-as %PKG% test -d no_backup 1>nul 2>nul
if not errorlevel 1 set "DATA_DIRS=%DATA_DIRS% no_backup"

echo [INFO] Backing up app data: %DATA_DIRS%
"%ADB_BIN%" exec-out run-as %PKG% tar -cf - %DATA_DIRS% > "%OUT%"
if errorlevel 1 goto backup_fail

for %%A in ("%OUT%") do set "SIZE=%%~zA"
if not defined SIZE goto backup_fail
if "%SIZE%"=="0" goto backup_fail

where tar >nul 2>nul
if not errorlevel 1 (
  tar -tf "%OUT%" >nul 2>&1
  if errorlevel 1 goto backup_fail
)

echo [OK] Backup: %OUT% (%SIZE% bytes)
echo [INFO] Restore: scripts\restore_device_data.bat "%OUT%"
endlocal
exit /b 0

:backup_fail
if exist "%OUT%" del /q "%OUT%"
echo [ERROR] Backup failed. Is the installed app a debuggable build (run-as available)?
exit /b 1
