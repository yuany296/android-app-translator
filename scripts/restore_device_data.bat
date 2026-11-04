@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0\.."

rem ============================================================================
rem Restore debug app data from a tar created by backup_device_data.bat.
rem
rem Usage:
rem   scripts\restore_device_data.bat "logs\device-backups\xxx.tar"
rem   scripts\restore_device_data.bat                 (latest backup)
rem   scripts\restore_device_data.bat "xxx.tar" --yes
rem
rem WARNING: restore overwrites same-name files. Run backup_device_data.bat
rem first if you want to keep the current data.
rem ============================================================================

set "PKG=com.manga.translator.app"
set "BACKUP="
set "ASSUME_YES=0"

:parse
if "%~1"=="" goto parsed
if /I "%~1"=="--yes" (
  set "ASSUME_YES=1"
  shift
  goto parse
)
if defined BACKUP (
  echo [ERROR] Too many arguments. Usage: scripts\restore_device_data.bat [backup.tar] [--yes]
  exit /b 1
)
set "BACKUP=%~1"
shift
goto parse
:parsed

call scripts\_env.bat || exit /b 1
call scripts\check_device.bat || exit /b 1

"%ADB_BIN%" get-state 1>nul 2>nul
if errorlevel 1 (
  echo [ERROR] adb device is not ready.
  exit /b 1
)

if not defined BACKUP (
  for /f "delims=" %%i in ('dir /b /a-d /o-d "logs\device-backups\*.tar" 2^>nul') do (
    set "BACKUP=%CD%\logs\device-backups\%%i"
    goto :backup_found
  )
)
:backup_found
if not defined BACKUP (
  echo [ERROR] No backup found. Run scripts\backup_device_data.bat first.
  exit /b 1
)
if not exist "%BACKUP%" (
  echo [ERROR] Backup not found: %BACKUP%
  exit /b 1
)

"%ADB_BIN%" shell pm path %PKG% 2>nul | findstr /C:"package:" >nul
if errorlevel 1 (
  echo [INFO] %PKG% is not installed; installing debug build first...
  call scripts\build_debug.bat || exit /b 1
  call scripts\install_debug.bat || exit /b 1
)

echo [WARN] This will overwrite current app data with:
echo        %BACKUP%
if "!ASSUME_YES!"=="0" (
  set /p "CONFIRM=Type RESTORE to continue: "
  if /I not "!CONFIRM!"=="RESTORE" (
    echo [INFO] Cancelled.
    exit /b 1
  )
)

echo [INFO] Stopping app...
"%ADB_BIN%" shell am force-stop %PKG% >nul 2>&1

echo [INFO] Pushing backup to device...
"%ADB_BIN%" push "%BACKUP%" /data/local/tmp/mt_restore.tar >nul
if errorlevel 1 (
  echo [ERROR] adb push failed.
  exit /b 1
)

echo [INFO] Extracting into app data dir...
"%ADB_BIN%" shell run-as %PKG% tar -xf /data/local/tmp/mt_restore.tar
if errorlevel 1 (
  "%ADB_BIN%" shell rm -f /data/local/tmp/mt_restore.tar >nul 2>&1
  echo [ERROR] Restore failed. Is the app a debuggable build?
  exit /b 1
)
"%ADB_BIN%" shell rm -f /data/local/tmp/mt_restore.tar >nul 2>&1

echo [OK] Restore complete: %BACKUP%
"%ADB_BIN%" shell monkey -p %PKG% -c android.intent.category.LAUNCHER 1 >nul 2>&1
endlocal
exit /b 0
