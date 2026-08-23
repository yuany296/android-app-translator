@echo off
setlocal
cd /d %~dp0\..
if not exist logs mkdir logs
for /f %%i in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set TS=%%i
set OUT=%CD%\logs\regression-smoke-%TS%.txt
call scripts\_env.bat || exit /b 1
call scripts\build_debug.bat || exit /b 1
call scripts\install_debug.bat || exit /b 1
"%ADB_BIN%" logcat -c
powershell -NoProfile -Command "Start-Sleep -Seconds 2" >nul
"%ADB_BIN%" shell am start -n com.manga.translator.app/.MainActivity >nul 2>&1
powershell -NoProfile -Command "Start-Sleep -Seconds 3" >nul
"%ADB_BIN%" logcat -d | findstr /R "TranslationPerf CaptureForegroundSvc" > "%OUT%"
echo [OK] Regression smoke script executed: %OUT%
endlocal
