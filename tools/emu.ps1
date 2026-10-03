<#
.SYNOPSIS
    启动（或重启）用于自动化验证的 Android TV 模拟器。

.DESCRIPTION
    模拟器偶尔会自己挂掉 —— 表现为 `adb devices` 空列表，
    而且 `Get-Process qemu*` 也查不到进程。这时候**不用重启电脑**，
    重新拉起模拟器即可。

    这个脚本把"启动 + 等上线 + 等系统启动完成"打包成一条命令，
    省得每次手敲一长串参数。

.PARAMETER Force
    先杀掉已有的模拟器进程再启动（用于"卡死但进程还在"的情况）。

.PARAMETER SkipWait
    只发出启动命令，不等就绪。

.EXAMPLE
    .\tools\emu.ps1
    .\tools\emu.ps1 -Force
#>
[CmdletBinding()]
param(
    [switch]$Force,
    [switch]$SkipWait
)

$ErrorActionPreference = 'Continue'
$adb = "C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$emu = "C:\Users\Administrator\AppData\Local\Android\Sdk\emulator\emulator.exe"
$avd = "BawanTV_TV"

function Get-OnlineDevice {
    $dev = & $adb devices 2>&1 | Select-String -Pattern "^emulator-\d+\s+device$"
    if ($dev) { return $true }
    return $false
}

Write-Host "焰火TV 模拟器" -ForegroundColor Cyan
Write-Host ("=" * 56)

# ---------- 先看是不是已经活着 ----------
& $adb start-server 2>&1 | Out-Null
if ((Get-OnlineDevice) -and -not $Force) {
    Write-Host "  模拟器已在运行，无需启动。" -ForegroundColor Green
    & $adb devices 2>&1 | Select-String -Pattern "emulator-"
    exit 0
}

# ---------- 清理残留 ----------
if ($Force) {
    Write-Host "  -Force：清理已有模拟器进程…"
    Get-Process -Name "qemu*", "emulator*" -ErrorAction SilentlyContinue |
        Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 3
}

# ---------- 启动 ----------
if (-not (Test-Path $emu)) {
    Write-Error "找不到模拟器可执行文件：$emu"
    exit 1
}
Write-Host "  启动 AVD：$avd"
# -no-snapshot：每次干净启动（避免坏掉的快照导致启动即崩）
# -no-boot-anim：省掉开机动画，启动更快
Start-Process -FilePath $emu -ArgumentList "-avd", $avd, "-no-snapshot", "-no-boot-anim" `
    -WindowStyle Minimized

if ($SkipWait) {
    Write-Host "  已发出启动命令（-SkipWait，不等待）。" -ForegroundColor Yellow
    exit 0
}

# ---------- 等上线 ----------
Write-Host -NoNewline "  等待设备上线"
$online = $false
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Seconds 3
    Write-Host -NoNewline "."
    if (Get-OnlineDevice) { $online = $true; break }
}
Write-Host ""
if (-not $online) {
    Write-Host "  超时：模拟器没上线。" -ForegroundColor Red
    Write-Host "  可以试试：.\tools\emu.ps1 -Force" -ForegroundColor Yellow
    Write-Host "  或者直接打开 Android Studio 的 Device Manager 手动启动。" -ForegroundColor Yellow
    exit 1
}
Write-Host "  设备已上线。" -ForegroundColor Green

# ---------- 等系统启动完成 ----------
Write-Host -NoNewline "  等待系统启动完成"
for ($i = 0; $i -lt 60; $i++) {
    $b = (& $adb shell getprop sys.boot_completed 2>&1) -join ''
    if ($b -match '1') { break }
    Start-Sleep -Seconds 3
    Write-Host -NoNewline "."
}
Write-Host ""

$boot = (& $adb shell getprop sys.boot_completed 2>&1) -join ''
if ($boot -match '1') {
    Write-Host "  就绪，可以装包/验证了。" -ForegroundColor Green
    Write-Host ""
    Write-Host "  下一步（可选）：" -ForegroundColor DarkGray
    Write-Host "    adb install -r app\build\outputs\apk\debug\app-debug.apk" -ForegroundColor DarkGray
} else {
    Write-Host "  设备在线但系统还没启动完，再等一会儿。" -ForegroundColor Yellow
}
