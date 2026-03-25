@echo off
setlocal
cd /d %~dp0\..
if not exist logs mkdir logs
for /f %%i in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set TS=%%i
set OUT=%CD%\logs\translator-logcat-%TS%.txt
call scripts\_env.bat || exit /b 1
call scripts\check_device.bat || exit /b 1
"%ADB_BIN%" logcat -d > "%OUT%"
echo [OK] Log exported: %OUT%
endlocal
