# =====================================================================
#  在模拟器上验证遥控器操作 & 截图
#  用法：pwsh -File tools/tv-remote-check.ps1 [-Serial emulator-5554]
#  说明：keyevent 22/21/19/20/23 分别对应 右/左/上/下/确定
# =====================================================================
param(
    [string]$Serial = "emulator-5554",
    [string]$Package = "com.chinut.bawantv.debug",
    [string]$Apk = "app\build\outputs\apk\debug\app-debug.apk",
    [string]$OutDir = "docs\shots"
)

$ErrorActionPreference = "Continue"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe" }

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Key([int]$code, [int]$waitMs = 800) {
    & $adb -s $Serial shell input keyevent $code 2>&1 | Out-Null
    Start-Sleep -Milliseconds $waitMs
}

function Shot([string]$name) {
    & $adb -s $Serial shell screencap -p /sdcard/_shot.png 2>&1 | Out-Null
    & $adb -s $Serial pull /sdcard/_shot.png (Join-Path $OutDir "$name.png") 2>&1 | Out-Null
    Write-Host "  截图 $name.png" -ForegroundColor DarkGray
}

Write-Host "== 安装并冷启动 ==" -ForegroundColor Cyan
& $adb -s $Serial install -r $Apk | Select-Object -Last 1
& $adb -s $Serial shell am force-stop $Package
Start-Sleep -Milliseconds 500
# 冷启动：先停止 App 再启动，确保能拍到开屏动画
& $adb -s $Serial shell am start -n "$Package/com.chinut.bawantv.MainActivity" | Out-Null
Start-Sleep -Milliseconds 900
Shot "00-splash"
Start-Sleep -Seconds 4

Write-Host "== 首页 ==" -ForegroundColor Cyan
Shot "01-home"

Write-Host "== 导航栏：下 x3 到「设置」再回到「直播」 ==" -ForegroundColor Cyan
Key 20; Key 20; Key 20   # 下 下 下 → 设置
Shot "02-rail-settings"
Key 19; Key 19          # 上 上 → 直播
Key 23                  # 确定 → 进入直播板块
Start-Sleep -Seconds 8
Shot "03-live-entry"

Write-Host "== 进入频道墙 ==" -ForegroundColor Cyan
Key 22                  # 右 → 内容区第一个频道
Start-Sleep -Seconds 1
Shot "04-live-grid"

Write-Host "== 确定播放第一个频道 ==" -ForegroundColor Cyan
Key 23 1500
Start-Sleep -Seconds 12
Shot "05-live-playing"

Write-Host "== 播放中：下键换台 ==" -ForegroundColor Cyan
Key 20 1500
Start-Sleep -Seconds 4
Shot "06-live-zapping"

Write-Host "== 返回列表 ==" -ForegroundColor Cyan
Key 4 1200
Shot "07-back-to-list"

Write-Host "`n完成，截图在 $OutDir" -ForegroundColor Green
