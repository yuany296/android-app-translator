@echo off
setlocal
set "ADB_BIN="
for /f "delims=" %%i in ('where adb 2^>nul') do (
  set "ADB_BIN=%%i"
  goto :adb_found
)
set "ADB_BIN=C:\Users\yuanying\AppData\Local\Android\Sdk\platform-tools\adb.exe"
:adb_found
if not exist "%ADB_BIN%" (
  echo [ERROR] adb not found. Checked PATH and C:\Users\yuanying\AppData\Local\Android\Sdk\platform-tools\adb.exe
  endlocal & exit /b 1
)
endlocal & set "ADB_BIN=%ADB_BIN%"
exit /b 0
