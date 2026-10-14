param([string]$Port = 'COM3', [switch]$Upload, [string]$ArduinoCli = $env:ARDUINO_CLI, [string]$Esptool = $env:ESPTOOL)
$ErrorActionPreference = 'Stop'
$bellCli = $ArduinoCli
if (-not $bellCli) { $bellCli = (Get-Command arduino-cli -ErrorAction SilentlyContinue).Source }
if (-not $bellCli) { throw 'Arduino CLI not found. Add it to PATH or pass -ArduinoCli.' }
$bellTool = $Esptool
if ($Upload -and -not $bellTool) {
    $bellTool = (Get-Command esptool -ErrorAction SilentlyContinue).Source
    if (-not $bellTool) {
        $bellToolsRoot = Join-Path $env:LOCALAPPDATA 'Arduino15\packages\esp32\tools\esptool_py'
        $bellTool = Get-ChildItem -LiteralPath $bellToolsRoot -Filter esptool.exe -Recurse -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
    }
    if (-not $bellTool) { throw 'Esptool not found. Add it to PATH or pass -Esptool.' }
}
$bellBuild = Join-Path $env:TEMP 'bell-v5-build'
& python (Join-Path $PSScriptRoot 'prepare_audio.py')
if ($LASTEXITCODE -ne 0) { throw 'Audio preparation failed' }
& python (Join-Path $PSScriptRoot 'verify_audio.py')
if ($LASTEXITCODE -ne 0) { throw 'Audio verification failed' }
& python (Join-Path $PSScriptRoot 'verify_catalog.py')
if ($LASTEXITCODE -ne 0) { throw 'Track catalog verification failed' }
& $bellCli compile --fqbn 'esp32:esp32:esp32c3:CDCOnBoot=cdc' --build-path $bellBuild (Join-Path $PSScriptRoot 'firmware')
if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
$bellOutput = Join-Path $PSScriptRoot 'generated'
Copy-Item -LiteralPath (Join-Path $bellBuild 'firmware.ino.bin'),(Join-Path $bellBuild 'firmware.ino.bootloader.bin'),(Join-Path $bellBuild 'firmware.ino.partitions.bin') -Destination $bellOutput
if ((Get-Item -LiteralPath (Join-Path $bellOutput 'firmware.ino.bin')).Length -gt 0xF0000) { throw 'Application exceeds the actual 960 KiB partition' }
if ($Upload) {
    Write-Host 'Ordinary USB: battery MUST be disconnected before upload.'
    & $bellTool --chip esp32c3 --port $Port --baud 460800 write-flash --flash-size 4MB 0x0 (Join-Path $bellOutput 'firmware.ino.bootloader.bin') 0x8000 (Join-Path $bellOutput 'firmware.ino.partitions.bin') 0x10000 (Join-Path $bellOutput 'firmware.ino.bin') 0x100000 (Join-Path $bellOutput 'audio.bin')
    if ($LASTEXITCODE -ne 0) { throw 'Upload failed' }
}
