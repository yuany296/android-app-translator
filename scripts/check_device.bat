@echo off
setlocal
cd /d %~dp0\..
call scripts\_env.bat || exit /b 1
"%ADB_BIN%" start-server >nul 2>&1
echo ==== adb devices -l ====
"%ADB_BIN%" devices -l
echo.
echo If status is unauthorized, follow docs\\LOCAL_DEBUG_GUIDE.md.
endlocal
