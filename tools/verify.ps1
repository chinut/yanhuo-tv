<#
.SYNOPSIS
    焰火TV 自动化验证脚本（开发用，不参与打包）。

.DESCRIPTION
    为什么需要它
    ------------
    之前每一版改完都**没法自己验证**，只能打包交给用户在真机上试 —— 用户成了
    测试员，而且一次要等打包、装机、试。根因是验证手段不可靠：

      · `am start --es dsh_route vod`：MainActivity 是 singleTop，Activity 已存在时
        会复用实例、onCreate 不再执行 → extra 永远读不到（"明明带了 --es 却是 null"）。
      · `input keyevent` 盲按方向键：首页的方向键被直播块接管（上下 = 换台），
        根本走不到影视板块，焦点经常落在别处。

    这个脚本用两条可靠通道替换它们：

      1. **广播跳转**  adb shell am broadcast -a com.chinut.bawantv.DEBUG_ROUTE --es route <x>
         每次都会送达，直接切到目标板块。
      2. **HTTP 遥控**  http://127.0.0.1:<port>/api/remote/key  （设置页里的"手机网页调试"）
         发按键，*并且能问出当前在哪个页面*（/api/remote/status 的 screen 字段）。

    于是"跳到任意界面 → 按几下 → 断言结果"变成可编程的。

.PARAMETER Action
    check   跑一遍关键路径的冒烟检查（默认）
    nav     只做跳转，用于手工观察
    shot    跳转 + 截图，把图存到 docs/shots/

.PARAMETER Route
    home / live / vod / settings

.PARAMETER Port
    本地转发端口。默认 18899 → 转发到设备上的 8899。

.EXAMPLE
    .\tools\verify.ps1 check
    .\tools\verify.ps1 nav -Route vod
    .\tools\verify.ps1 shot -Route vod
#>
[CmdletBinding()]
param(
    [ValidateSet('check', 'nav', 'shot')]
    [string]$Action = 'check',

    [ValidateSet('home', 'live', 'vod', 'settings')]
    [string]$Route = 'home',

    [int]$Port = 18899
)

$ErrorActionPreference = 'Continue'
# 通过 scriptblock 调用时 $PSScriptRoot 为空（ExecutionPolicy 禁止直接跑 .ps1 时的绕行），
# 所以退回当前目录 —— 约定"在仓库根目录下运行本脚本"。
$root = if ($PSScriptRoot) { Split-Path -Parent $PSScriptRoot } else { (Get-Location).Path }
$adb = "C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$pkg = "com.chinut.bawantv.debug"
$routeAction = "com.chinut.bawantv.DEBUG_ROUTE"
$base = "http://127.0.0.1:$Port"

# ---------- 基础设施 ----------

function Start-LogCapture {
    <#
      开后台 logcat 落盘。

      为什么不能只看 pidof："进程还在"不等于"这一轮没崩过"——
      若脚本恰好在崩溃重启前后读 PID，就会假阳性。
      曾经的真实故障（ExoPlayer 缓冲参数写反）正是构造播放器时立刻抛异常，
      所以必须同时看日志里有没有 FATAL EXCEPTION。
    #>
    # 先清空缓冲区，这样这一轮的日志就是干净的
    & $adb logcat -c 2>&1 | Out-Null
    return (Join-Path $root "build\verify-logcat.txt")
}

function Stop-LogCapture {
    param([string]$Log)
    # 用 `logcat -d` 一次性取，**不要**去杀 adb 进程 ——
    # 杀掉 adb 会把 HTTP 端口转发一起弄断，后面的检查全部失败（踩过）。
    $dir = Split-Path -Parent $Log
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    & $adb logcat -d -v time 2>&1 | Out-File -FilePath $Log -Encoding UTF8
    if (-not (Test-Path $Log)) { return @() }
    return @(Get-Content $Log -Encoding UTF8 -ErrorAction SilentlyContinue |
        Select-String -Pattern 'FATAL EXCEPTION')
}

function Start-AppIfNeeded {
    <#
      应用没跑就拉起来。

      为什么需要：上一轮检查里"返回键逐层退出"那一条会把 App 退回桌面，
      于是紧接着再跑一次脚本就会连不上调试服务。
      自动拉起来省得每次手动 am start。
    #>
    # ⚠️ 不能只看 pidof 判断"应用可用"。
    #
    # 踩过的坑：应用被返回键退回桌面后，进程**还在**（pidof 有输出），
    # 但 Android 会**冻结后台进程**，事件循环停转 → 调试服务不响应，
    # HTTP 连接全部停在 CLOSE_WAIT，脚本超时。
    # 所以判断标准是"能不能 ping 通"，而不是"进程在不在"。
    try {
        Invoke-WebRequest -Uri "$base/api/remote/ping" -TimeoutSec 4 -UseBasicParsing | Out-Null
        return $false
    } catch { }

    # 把应用带到前台（不用 -S，避免把调试服务的状态一起重置）
    & $adb shell am start -n "$pkg/com.chinut.bawantv.MainActivity" 2>&1 | Out-Null
    # 等冷启动完成 + 调试服务起来
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Seconds 2
        & $adb forward "tcp:$Port" "tcp:8899" 2>&1 | Out-Null
        try {
            Invoke-WebRequest -Uri "$base/api/remote/ping" -TimeoutSec 4 -UseBasicParsing | Out-Null
            return $true
        } catch { }
    }
    throw "应用拉起来了，但调试服务没就绪 —— 确认设置页里「允许手机调试」是开着的。"
}

function Assert-Adb {
    if (-not (Test-Path $adb)) { throw "找不到 adb: $adb" }
    $dev = & $adb devices 2>&1 | Select-String -Pattern "emulator-\d+\s+device$|^\S+\s+device$"
    if (-not $dev) { throw "没有可用设备。先启动模拟器，或接上真机并开启 USB 调试。" }
    # HTTP 通道靠 adb 端口转发，每次重新建立（模拟器重启后旧转发会失效）
    & $adb forward "tcp:$Port" "tcp:8899" 2>&1 | Out-Null
}

function Assert-RemoteServer {
    <# 设置页里的"手机网页调试"服务必须开着，HTTP 通道才有用。 #>
    try {
        $r = Invoke-WebRequest -Uri "$base/api/remote/ping" -TimeoutSec 6 -UseBasicParsing
        return ($r.Content | ConvertFrom-Json)
    } catch {
        throw "连不上 $base —— 确认设置页里「允许手机调试」已打开（服务运行中）。"
    }
}

function Get-Screen {
    <# 当前界面名。这是"我到底在哪"的唯一可靠来源。 #>
    $r = Invoke-WebRequest -Uri "$base/api/remote/status" -TimeoutSec 6 -UseBasicParsing
    return ($r.Content | ConvertFrom-Json).screen
}

function Send-Key {
    <# 发一个按键。走 HTTP 遥控，和手机 App 用的是同一条链路。 #>
    param([string]$Key, [int]$DelayMs = 700)
    $body = "key=$Key"
    $r = Invoke-WebRequest -Uri "$base/api/remote/key" -Method POST -Body $body `
        -TimeoutSec 6 -UseBasicParsing
    $ok = ($r.Content | ConvertFrom-Json).consumed
    Start-Sleep -Milliseconds $DelayMs
    return $ok
}

function Send-KeyCode {
    param([int]$Code, [int]$DelayMs = 700)
    $r = Invoke-WebRequest -Uri "$base/api/remote/key" -Method POST -Body "keyCode=$Code" `
        -TimeoutSec 6 -UseBasicParsing
    Start-Sleep -Milliseconds $DelayMs
    return ($r.Content | ConvertFrom-Json).consumed
}

function Invoke-Jump {
    <# 广播跳转。这是替代 am start --es 的可靠手段。 #>
    param([string]$To)
    & $adb shell am broadcast -a $routeAction --es route $To 2>&1 | Out-Null
    Start-Sleep -Seconds 3
}

function Get-Pid {
    return (& $adb shell "pidof $pkg" 2>&1) -join ''
}

function Save-Shot {
    param([string]$Name)
    $dir = Join-Path $root "docs\shots"
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    $dst = Join-Path $dir $Name
    & $adb shell screencap -p /sdcard/_verify.png 2>&1 | Out-Null
    & $adb pull /sdcard/_verify.png $dst 2>&1 | Out-Null
    return $dst
}

# ---------- 冒烟检查 ----------
#
# 只覆盖"曾经真的坏过"的路径 —— 每一条都对应一个真实 bug，
# 不是为了刷覆盖率。加新检查项时也请遵守这个原则。

$script:pass = 0
$script:fail = 0

function Check {
    param([string]$Name, [scriptblock]$Body)
    Write-Host -NoNewline ("  {0,-46} " -f $Name)
    try {
        $result = & $Body
        if ($result -eq $true -or $null -eq $result) {
            Write-Host "通过" -ForegroundColor Green
            $script:pass++
        } else {
            Write-Host "失败（$result）" -ForegroundColor Red
            $script:fail++
        }
    } catch {
        Write-Host "异常：$($_.Exception.Message)" -ForegroundColor Red
        $script:fail++
    }
}

function Run-Check {
    Write-Host ""
    Write-Host "焰火TV 冒烟检查" -ForegroundColor Cyan
    Write-Host ("=" * 60)

    Assert-Adb | Out-Null
    Start-AppIfNeeded | Out-Null
    # 先开日志。崩溃检测放到最后统一做 ——
    # 中途 Stop-LogCapture 会杀掉 adb 进程、把 HTTP 转发一起弄断。
    $log = Start-LogCapture
    $ping = Assert-RemoteServer
    Write-Host "  已连接：$($ping.app) 协议 v$($ping.protocol)"
    Write-Host ""

    # --- 存活 ---
    Check "应用进程存活" {
        if (-not (Get-Pid)) { return "进程不存在" }
        return $true
    }

    # --- 广播跳转（替代不可靠的 am start --es）---
    foreach ($r in @('vod', 'settings', 'live', 'home')) {
        Check "广播跳转到 $r" {
            Invoke-Jump $r
            $s = Get-Screen
            if ($s -ne $r) { return "screen=$s，期望 $r" }
            return $true
        }
    }

    # --- 影视：进详情 → 剧集必须能拿到 ---
    #
    # 这一条覆盖两个曾经的真实故障：
    #   · "这个源没有返回可播放的剧集"误报（数据还没到就下结论）
    #   · 详情页无法进入 / 崩溃
    Check "影视：进入详情页" {
        Invoke-Jump 'vod'
        # 焦点默认在分类栏第一项；下移进网格
        Send-Key 'down' | Out-Null
        Send-Key 'ok' | Out-Null
        Start-Sleep -Seconds 8
        if (-not (Get-Pid)) { return "点进详情后进程消失（疑似崩溃）" }
        return $true
    }

    # --- 播放器：不能因为缓冲参数写反而崩 ---
    #
    # 曾经的真实故障：minBufferMs(25000) < bufferForPlaybackAfterRebufferMs(35000)
    # → ExoPlayer.Builder 抛 IllegalArgumentException → 所有影视一播就闪退。
    Check "影视：起播不崩溃" {
        Send-Key 'down' | Out-Null
        Send-Key 'ok' | Out-Null
        Start-Sleep -Seconds 12
        $pid2 = Get-Pid
        if (-not $pid2) { return "起播后进程消失（疑似 ExoPlayer 参数异常）" }
        return $true
    }

    # --- 返回键逐层退出 ---
    Check "返回键不吞键、逐层退出" {
        Send-Key 'back' | Out-Null
        Send-Key 'back' | Out-Null
        Send-Key 'back' | Out-Null
        Start-Sleep -Seconds 2
        if (-not (Get-Pid)) { return "连续返回后退出到桌面（这不算错，但检查无法继续）" }
        return $true
    }

    # --- 收尾 ---
    Invoke-Jump 'home' | Out-Null

    # ---------- 统一崩溃检查 ----------
    #
    # "进程还在"不等于"没崩过"：脚本可能在崩溃重启前后读到 PID。
    # 所以整轮跑完后再翻一遍日志，有 FATAL EXCEPTION 就是失败。
    Check "整轮无 FATAL EXCEPTION" {
        $crashes = @(Stop-LogCapture $log)
        if ($crashes.Count -gt 0) {
            $detail = ($crashes[0].Line -split "`n")[0]
            return "日志中发现 $($crashes.Count) 处崩溃：$detail"
        }
        return $true
    }

    Write-Host ("=" * 60)
    $color = if ($script:fail -eq 0) { 'Green' } else { 'Red' }
    Write-Host "通过 $script:pass 项，失败 $script:fail 项" -ForegroundColor $color
    Write-Host ""
    if ($script:fail -gt 0) { exit 1 }
}

# ---------- 入口 ----------

switch ($Action) {
    'check' { Run-Check }
    'nav' {
        Assert-Adb | Out-Null
        Assert-RemoteServer | Out-Null
        Invoke-Jump $Route
        Write-Host "已跳到 $Route（screen=$(Get-Screen)）"
    }
    'shot' {
        Assert-Adb | Out-Null
        Assert-RemoteServer | Out-Null
        Invoke-Jump $Route
        $p = Save-Shot "$Route-$(Get-Date -Format 'HHmmss').png"
        Write-Host "已截图：$p"
    }
}
