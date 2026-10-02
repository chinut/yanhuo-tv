# BawanTV remote-control regression script.
# ASCII only on purpose: Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI.
param(
    [string]$Serial = "emulator-5554",
    [string]$Package = "com.chinut.bawantv.debug",
    [string]$OutDir = "docs\shots"
)
$ErrorActionPreference = "Continue"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Key([int]$c, [int]$waitMs = 1200) {
    & $adb -s $Serial shell input keyevent $c 2>&1 | Out-Null
    Start-Sleep -Milliseconds $waitMs
}
function Shot([string]$name) {
    & $adb -s $Serial shell screencap -p /sdcard/_s.png 2>&1 | Out-Null
    & $adb -s $Serial pull /sdcard/_s.png (Join-Path $OutDir "$name.png") 2>&1 | Out-Null
    Write-Host "  shot $name.png" -ForegroundColor DarkGray
}
function Screen {
    & $adb -s $Serial shell uiautomator dump /sdcard/_u.xml 2>&1 | Out-Null
    & $adb -s $Serial pull /sdcard/_u.xml "$env:TEMP\_u.xml" 2>&1 | Out-Null
    $x = [System.IO.File]::ReadAllText("$env:TEMP\_u.xml", [System.Text.Encoding]::UTF8)
    if ($x -match 'MARK:PLAYING') { return 'PLAYING' }
    elseif ($x -match 'MARK:LIVE') { return 'LIVE-GRID' }
    elseif ($x -match 'MARK:VOD') { return 'VOD-HUB' }
    elseif ($x -match 'MARK:SETTINGS') { return 'SETTINGS' }
    elseif ($x -match 'MARK:HOME') { return 'HOME' }
    else { return 'UNKNOWN' }
}

Write-Host "== cold start ==" -ForegroundColor Cyan
& $adb -s $Serial shell am force-stop $Package 2>&1 | Out-Null
& $adb -s $Serial shell am start -n "$Package/com.chinut.bawantv.MainActivity" 2>&1 | Out-Null
Start-Sleep -Milliseconds 900
Shot "00-splash"
Start-Sleep -Seconds 6
Write-Host "  screen: $(Screen)"

Write-Host "== block A: live ==" -ForegroundColor Cyan
Key 20; Key 23 2000
Write-Host "  after enter live: $(Screen)"
Key 22; Key 23 2500
Start-Sleep -Seconds 10
Write-Host "  after OK: $(Screen)"
Shot "30-live-playing"
Key 20 1500
Start-Sleep -Seconds 8
Write-Host "  after zap: $(Screen)"
Shot "31-live-zap"
Key 4 1500
Write-Host "  after back: $(Screen)"

Write-Host "== block C: ddys waterfall ==" -ForegroundColor Cyan
Key 4 1200
Key 20; Key 20; Key 23 2500
Write-Host "  vod hub: $(Screen)"
Key 22; Key 22 1200
Key 23 2500
Start-Sleep -Seconds 15
Write-Host "  waterfall: $(Screen)"
Shot "32-waterfall"

Write-Host "== block D: settings ==" -ForegroundColor Cyan
Key 4; Key 4 1200
Key 20; Key 20; Key 20; Key 23 2500
Start-Sleep -Seconds 3
Write-Host "  settings: $(Screen)"
Shot "33-settings"

Write-Host "done. screenshots in $OutDir" -ForegroundColor Green