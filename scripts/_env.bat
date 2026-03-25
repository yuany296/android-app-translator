@echo off
setlocal
set "ADB_BIN=adb"
where adb >nul 2>&1
if errorlevel 1 (
  set "ADB_BIN=C:\Users\yuanying\AppData\Local\Android\Sdk\platform-tools\adb.exe"
)
if not exist "%ADB_BIN%" (
  echo [ERROR] adb not found. Checked PATH and C:\Users\yuanying\AppData\Local\Android\Sdk\platform-tools\adb.exe
  endlocal & exit /b 1
)
endlocal & set "ADB_BIN=%ADB_BIN%"
exit /b 0
