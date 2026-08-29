# =============================================================================
# Download PaddleOCR local ONNX models into APK assets (offline, no runtime
# download needed). ASCII-only so it parses fine under Windows PowerShell 5.1.
#
# Usage (run on a machine that can reach the internet):
#     powershell -ExecutionPolicy Bypass -File scripts\download-paddle-models.ps1
#
# Places 4 ONNX models (det + zh/ja/ko rec) into
#     core-translate\src\main\assets\paddle_models\
# Next time you run ./gradlew assembleDebug they are packed into the APK.
#
# Sources (same as PaddleModelCatalog in code):
#   - det / rec(zh): Baidu BOS tar (reachable in CN), extract inference.onnx then rename.
#   - rec(ja/ko)   : HuggingFace via hf-mirror mirror.
#
# Idempotent: an existing file > 1MB is skipped.
# =============================================================================
$ErrorActionPreference = "Stop"

$assetDir = Join-Path $PSScriptRoot "..\core-translate\src\main\assets\paddle_models"
New-Item -ItemType Directory -Force -Path $assetDir | Out-Null

# Optional local proxy (e.g. Clash default 7890). Leave $null for direct.
$Proxy = $null
$portOpen = $false
try {
    $portOpen = Test-NetConnection -ComputerName 127.0.0.1 -Port 7890 -WarningAction SilentlyContinue -InformationLevel Quiet
} catch { $portOpen = $false }
if ($portOpen) {
    $Proxy = "http://127.0.0.1:7890"
    Write-Host "[info] local proxy 127.0.0.1:7890 detected, using it."
}

function Invoke-Download {
    param([string]$Url, [string]$Out)
    Write-Host "[down] $Url"
    if ($Proxy) {
        & curl.exe -sS -L --proxy $Proxy --max-time 600 -o $Out $Url
    } else {
        & curl.exe -sS -L --max-time 600 -o $Out $Url
    }
    if ($LASTEXITCODE -ne 0) { throw "curl failed: $Url (exit=$LASTEXITCODE)" }
    $len = (Get-Item $Out).Length
    if ($len -lt 1000000) { throw "file too small ($len bytes) - download incomplete: $Url" }
    return $len
}

# Extract the first .onnx entry from a tar (mirrors downloadTarAndExtract in Kotlin).
# Redirect via cmd.exe so the binary is NOT piped through PowerShell text encoding.
function Expand-TarOnnx {
    param([string]$Tar, [string]$Out)
    $tmp = "$Tar.tmp"
    if (Test-Path $tmp) { Remove-Item $tmp -Force }
    $entry = (& tar.exe -tf $Tar | Where-Object { $_ -like "*.onnx" } | Select-Object -First 1)
    if (-not $entry) { throw "no .onnx found in tar: $Tar" }
    & cmd.exe /c "tar -xOf `"$Tar`" `"$entry`" > `"$tmp`""
    if ($LASTEXITCODE -ne 0) { throw "tar extract failed: $Tar" }
    $len = (Get-Item $tmp).Length
    if ($len -lt 1000000) { throw "extracted onnx too small ($len bytes): $Tar" }
    Move-Item -Force $tmp $Out
    return $len
}

function File-Missing {
    param([string]$Path)
    if (-not (Test-Path $Path)) { return $true }
    return ((Get-Item $Path).Length -lt 1000000)
}

$BOS = "https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0"
$HF  = "https://hf-mirror.com/PaddleOcrNet/PaddleOcrNet-models/resolve/main"

# --- det (BOS tar -> PP-OCRv5_mobile_det.onnx) ---
$detOut = Join-Path $assetDir "PP-OCRv5_mobile_det.onnx"
if (File-Missing $detOut) {
    $tar = Join-Path $env:TEMP "PP-OCRv5_mobile_det_onnx.tar"
    Invoke-Download "$BOS/PP-OCRv5_mobile_det_onnx.tar" $tar
    Expand-TarOnnx $tar $detOut
    Remove-Item $tar -Force -ErrorAction SilentlyContinue
    Write-Host ("[ok ] det -> " + $detOut + " (" + (Get-Item $detOut).Length + " bytes)")
} else { Write-Host "[skip] det already present" }

# --- rec zh (BOS tar -> PP-OCRv5_mobile_rec.onnx) ---
$reczhOut = Join-Path $assetDir "PP-OCRv5_mobile_rec.onnx"
if (File-Missing $reczhOut) {
    $tar = Join-Path $env:TEMP "PP-OCRv5_mobile_rec_onnx.tar"
    Invoke-Download "$BOS/PP-OCRv5_mobile_rec_onnx.tar" $tar
    Expand-TarOnnx $tar $reczhOut
    Remove-Item $tar -Force -ErrorAction SilentlyContinue
    Write-Host ("[ok ] rec(zh) -> " + $reczhOut + " (" + (Get-Item $reczhOut).Length + " bytes)")
} else { Write-Host "[skip] rec(zh) already present" }

# --- rec ja (HF direct -> japan_PP-OCRv5_mobile_rec.onnx) ---
$recjaOut = Join-Path $assetDir "japan_PP-OCRv5_mobile_rec.onnx"
if (File-Missing $recjaOut) {
    Invoke-Download "$HF/japan_PP-OCRv5_mobile_rec.onnx" $recjaOut
    Write-Host ("[ok ] rec(ja) -> " + $recjaOut + " (" + (Get-Item $recjaOut).Length + " bytes)")
} else { Write-Host "[skip] rec(ja) already present" }

# --- rec ko (HF direct -> korean_PP-OCRv5_mobile_rec.onnx) ---
$reckoOut = Join-Path $assetDir "korean_PP-OCRv5_mobile_rec.onnx"
if (File-Missing $reckoOut) {
    Invoke-Download "$HF/korean_PP-OCRv5_mobile_rec.onnx" $reckoOut
    Write-Host ("[ok ] rec(ko) -> " + $reckoOut + " (" + (Get-Item $reckoOut).Length + " bytes)")
} else { Write-Host "[skip] rec(ko) already present" }

Write-Host ""
Write-Host "======================================================"
Write-Host "Done. assets dir contents:"
Get-ChildItem $assetDir | Select-Object Name, Length | Format-Table -AutoSize
Write-Host "Rebuild with: ./gradlew assembleDebug"
Write-Host "======================================================"
