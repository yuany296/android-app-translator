@echo off
setlocal
cd /d %~dp0\..
if not exist logs mkdir logs
for /f %%i in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set TS=%%i
set OUT=%CD%\logs\translator-logcat-%TS%.txt
set SUMMARY=%CD%\logs\translator-logcat-%TS%-summary.txt
call scripts\_env.bat || exit /b 1
call scripts\check_device.bat || exit /b 1
"%ADB_BIN%" logcat -d > "%OUT%"
powershell -NoProfile -Command "$src='%OUT%';$dst='%SUMMARY%';$lines=Get-Content -Path $src -ErrorAction SilentlyContinue;$timeout=($lines|Select-String -Pattern 'capture_timeout_final').Count;$cached=($lines|Select-String -Pattern 'stage=use_cached_frame hit=true').Count;$hard=($lines|Select-String -Pattern 'stage=hard_recreate done').Count;$wait=($lines|Select-String -Pattern 'stage=wait_frame').Count;$values=@();$lines|Select-String -Pattern '\] screenshot_done: (\d+)ms'|ForEach-Object{if($_.Matches.Count -gt 0){$values+=[int]$_.Matches[0].Groups[1].Value}};$p95='NA';if($values.Count -gt 0){$sorted=$values|Sort-Object;$idx=[Math]::Ceiling($sorted.Count*0.95)-1;if($idx -lt 0){$idx=0};$p95=$sorted[[int]$idx]};$nl=[Environment]::NewLine;$summary='capture_timeout_final='+$timeout+$nl+'use_cached_frame='+$cached+$nl+'hard_recreate='+$hard+$nl+'wait_frame_logs='+$wait+$nl+'screenshot_done_p95_ms='+$p95;Set-Content -Path $dst -Encoding UTF8 -Value $summary"
echo [OK] Log exported: %OUT%
echo [OK] Summary exported: %SUMMARY%
endlocal
