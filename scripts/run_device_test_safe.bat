@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0\.."

rem ============================================================================
rem Run Android instrument tests WITHOUT uninstalling the main app or wiping
rem its local data.
rem
rem Why not connectedDebugAndroidTest:
rem   Gradle/AGP uninstalls the main app after that task. The uninstall goes
rem   through ddmlib, not a shell command, so permission.bash / "adb uninstall"
rem   deny rules do not catch it.
rem
rem Flow:
rem   1) build app + androidTest APK;
rem   2) backup app files/shared_prefs/databases;
rem   3) adb install -r main app + test APK (data preserved);
rem   4) adb shell am instrument;
rem   5) parse result. Main app is never uninstalled.
rem
rem Usage:
rem   scripts\run_device_test_safe.bat
rem   scripts\run_device_test_safe.bat --class com.manga.translator.app.MlKitConfidenceProbeTest
rem   scripts\run_device_test_safe.bat --class com.manga.translator.app.FooTest --method bar
rem   scripts\run_device_test_safe.bat --no-build --no-backup
rem
rem Multiple devices: set ANDROID_SERIAL=xxxx first.
rem ============================================================================

set "TEST_CLASS="
set "TEST_METHOD="
set "SKIP_BUILD=0"
set "SKIP_BACKUP=0"

:parse
if "%~1"=="" goto parsed
set "ARG=%~1"
if /I "!ARG!"=="--help" goto help
if /I "!ARG!"=="-h" goto help
if /I "!ARG!"=="--class" (
  if "%~2"=="" (echo [ERROR] --class needs a value. & exit /b 1)
  set "TEST_CLASS=%~2"
  shift & shift
  goto parse
)
if /I "!ARG:~0,8!"=="--class=" (
  set "TEST_CLASS=!ARG:~8!"
  shift
  goto parse
)
if /I "!ARG!"=="--method" (
  if "%~2"=="" (echo [ERROR] --method needs a value. & exit /b 1)
  set "TEST_METHOD=%~2"
  shift & shift
  goto parse
)
if /I "!ARG:~0,9!"=="--method=" (
  set "TEST_METHOD=!ARG:~9!"
  shift
  goto parse
)
if /I "!ARG!"=="--no-build" (
  set "SKIP_BUILD=1"
  shift
  goto parse
)
if /I "!ARG!"=="--no-backup" (
  set "SKIP_BACKUP=1"
  shift
  goto parse
)
echo [ERROR] Unknown argument: !ARG!
goto help_exit
:parsed

if defined TEST_METHOD if not defined TEST_CLASS (
  echo [ERROR] --method requires --class.
  exit /b 1
)

call scripts\_env.bat || exit /b 1
call scripts\check_device.bat || exit /b 1
"%ADB_BIN%" get-state 1>nul 2>nul
if errorlevel 1 (
  echo [ERROR] adb device is not ready. Check: scripts\check_device.bat
  echo [INFO] If multiple devices are connected, set ANDROID_SERIAL first.
  exit /b 1
)

set "PKG=com.manga.translator.app"
set "APP_APK=%CD%\app\build\outputs\apk\debug\app-debug.apk"
set "TEST_APK=%CD%\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"

if "%SKIP_BUILD%"=="0" (
  echo [INFO] Building app + androidTest APK...
  call gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
  if errorlevel 1 exit /b 1
) else (
  echo [WARN] --no-build: using existing APKs.
)

if not exist "%APP_APK%" (
  echo [ERROR] Missing: %APP_APK%
  exit /b 1
)
if not exist "%TEST_APK%" (
  echo [ERROR] Missing: %TEST_APK%
  echo [INFO] Run without --no-build, or: gradlew.bat :app:assembleDebugAndroidTest
  exit /b 1
)

if "%SKIP_BACKUP%"=="0" (
  "%ADB_BIN%" shell pm path %PKG% 2>nul | findstr /C:"package:" >nul
  if errorlevel 1 (
    echo [INFO] %PKG% is not installed yet; nothing to back up.
  ) else (
    echo [INFO] Backing up app data before touching APK installs...
    call scripts\backup_device_data.bat
    if errorlevel 1 (
      echo [ERROR] Backup failed; refusing to continue.
      echo [INFO] If you accept the risk, rerun with --no-backup.
      exit /b 1
    )
  )
) else (
  echo [WARN] --no-backup: current app data will NOT be backed up.
)

echo [INFO] Installing main app with -r (app data preserved)...
"%ADB_BIN%" install -r "%APP_APK%"
if errorlevel 1 (
  echo [ERROR] Main APK install failed.
  echo [INFO] Do NOT uninstall the app unless you have a backup.
  exit /b 1
)

echo [INFO] Installing test APK with -r (main app untouched)...
"%ADB_BIN%" install -r "%TEST_APK%"
if errorlevel 1 (
  echo [ERROR] Test APK install failed.
  echo [INFO] Only com.manga.translator.app.test is safe to uninstall.
  exit /b 1
)

if not exist "logs\device-tests" mkdir "logs\device-tests"
for /f %%i in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss-fff"') do set "TS=%%i"
set "OUT=%CD%\logs\device-tests\device-test-%TS%.txt"

set "RUNNER=com.manga.translator.app.test/androidx.test.runner.AndroidJUnitRunner"
set "INSTR_ARGS=-w -r"
if defined TEST_CLASS (
  if defined TEST_METHOD (
    set "INSTR_ARGS=!INSTR_ARGS! -e class %TEST_CLASS%#%TEST_METHOD%"
  ) else (
    set "INSTR_ARGS=!INSTR_ARGS! -e class %TEST_CLASS%"
  )
)

echo [INFO] Running: adb shell am instrument !INSTR_ARGS! %RUNNER%
"%ADB_BIN%" shell am instrument !INSTR_ARGS! %RUNNER% > "%OUT%" 2>&1
set "RC=%ERRORLEVEL%"
type "%OUT%"

set "FAILED=0"
if not "%RC%"=="0" set "FAILED=1"
findstr /C:"FAILURES!!!" "%OUT%" >nul && set "FAILED=1"
findstr /C:"INSTRUMENTATION_FAILED" "%OUT%" >nul && set "FAILED=1"
findstr /C:"OK (" "%OUT%" >nul || set "FAILED=1"

if "%FAILED%"=="1" (
  echo.
  echo [ERROR] Device test failed. Log: %OUT%
  echo [INFO] Main app is still installed; its data was backed up before the run.
  exit /b 1
)

echo.
echo [OK] Device test passed. Log: %OUT%
echo [OK] Main app kept installed; no app-data uninstall happened.
echo [INFO] Test APK left installed: com.manga.translator.app.test
endlocal
exit /b 0

:help
echo Usage:
echo   scripts\run_device_test_safe.bat [--class CLASS] [--method METHOD] [--no-build] [--no-backup]
echo.
echo Examples:
echo   scripts\run_device_test_safe.bat --class com.manga.translator.app.MlKitConfidenceProbeTest
echo   scripts\run_device_test_safe.bat --class com.manga.translator.app.FooTest --method bar
echo   set ANDROID_SERIAL=xxxx ^&^& scripts\run_device_test_safe.bat
endlocal
exit /b 0

:help_exit
endlocal
exit /b 1
