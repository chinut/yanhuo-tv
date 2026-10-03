<#
.SYNOPSIS
    发布焰火TV 新版本到 GitHub + Gitee。

.DESCRIPTION
    一条命令完成：读版本号 → 打包 → 建 tag → 建 Release → 传 APK。

    ## 为什么正文必须写 versionCode

    应用检查更新时是这样解析版本的（见 core/Updater.kt）：

        val code = Regex("""versionCode[:\s=]+(\d+)""").find(notes)?.groupValues?.get(1)
            ?: tag.filter { it.isDigit() }.toIntOrNull()
            ?: 0

    也就是说：**先读正文里的 versionCode，读不到才退回"从 tag 里抠数字"**。
    而 tag 是 v1.0.22，抠出来的数字是 "1022" —— 和真实的 versionCode=23
    完全对不上，升级判断会彻底错乱。

    所以本脚本强制在正文顶部写入：
        versionCode: 23
        versionName: 1.0.22

    ## 已知限制：Gitee 的 APK 要手动传

    Gitee 的附件接口（attach_files）对 API 令牌返回 405，v8 也是 302/404，
    试过 5 条路径都不通。所以 Gitee 的 Release 建好后，需要到网页端
    手动把 APK 拖进附件区 —— 否则应用优先读 Gitee 时会报
    「找到了新版本，但 Release 里没有 APK 附件」。

.PARAMETER VersionCode
    versionCode（整数，每次发版 +1）。必须在 app/build.gradle.kts 里同步。

.PARAMETER VersionName
    versionName，如 1.0.23。

.PARAMETER Notes
    本次更新说明（markdown）。会附在正文里。

.EXAMPLE
    .\tools\release.ps1 -VersionCode 24 -VersionName 1.0.23 -Notes "- 修了 xxx"
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][int]$VersionCode,
    [Parameter(Mandatory = $true)][string]$VersionName,
    [string]$Notes = ''
)

$ErrorActionPreference = 'Continue'
$root = if ($PSScriptRoot) { Split-Path -Parent $PSScriptRoot } else { (Get-Location).Path }
Set-Location $root

$repo = 'chinut/yanhuo-tv'
$tag = "v$VersionName"
$apkName = "yanhuo-tv-$VersionName.apk"
$apkPath = Join-Path $root $apkName

Write-Host "焰火TV 发布 $tag (versionCode=$VersionCode)" -ForegroundColor Cyan
Write-Host ('=' * 58)

# ---------- 1) 校验 gradle 里的版本号一致 ----------
$gradle = Get-Content 'app\build.gradle.kts' -Raw -Encoding UTF8
if ($gradle -notmatch "versionCode\s*=\s*$VersionCode") {
    Write-Host "  !! app/build.gradle.kts 里的 versionCode 不是 $VersionCode" -ForegroundColor Red
    Write-Host "     先改那里，再发版（两边必须一致，否则应用判断会错）" -ForegroundColor Yellow
    exit 1
}
if ($gradle -notmatch [regex]::Escape("versionName = `"$VersionName`"")) {
    Write-Host "  !! app/build.gradle.kts 里的 versionName 不是 $VersionName" -ForegroundColor Red
    exit 1
}
Write-Host '  版本号校验通过'

# ---------- 1b) versionCode 必须递增 ----------
#
# ## 版本号命名规则（重要，别再弄错）
#
#   versionCode —— **只增不减的计数器**，每次发版 +1（35, 36, 37…）。
#                  应用就是靠它判断"有没有新版本"。跳回去会导致
#                  "永远提示已最新"或者反复弹更新框。
#
#   versionName —— 给人和市场看的名字。功能分档时改它：
#                  1.0.x  第一代（直播 + 影视）
#                  1.1.x  加入短剧/短视频后，**从 1.1.1 重新起头**
#
# 两者**互相独立**：versionName 可以从 1.0.34 跳到 1.1.1，
# 但 versionCode 必须接着数（35 → 36）。
#
# 上次发布的 versionCode 从**当前提交的父提交**里读。
#
# 为什么不从上一个 git tag 读：本脚本要求"先提交再发布"，
# 而 tag 是发布时才打的 —— 如果先打了 tag 再跑脚本，
# 读到的就是本次自己（36 → 36），会把自己挡住。父提交才是真正的"上一版"。
$prevCode = 0
$prevName = ''
$prevGradle = (git show 'HEAD~1:app/build.gradle.kts' 2>$null) -join "`n"
if ($prevGradle -match 'versionCode\s*=\s*(\d+)') { $prevCode = [int]$Matches[1] }
if ($prevGradle -match 'versionName\s*=\s*"([^"]+)"') { $prevName = $Matches[1] }
if ($prevCode -gt 0) {
    Write-Host "  上次发布：$prevName (code $prevCode)  →  本次：$VersionName (code $VersionCode)"
} else {
    Write-Host "  读不到上一版（可能是首次提交），跳过递增校验"
}

if ($prevCode -gt 0 -and $VersionCode -le $prevCode) {
    Write-Host "  !! versionCode $VersionCode 没有大于上一版的 $prevCode" -ForegroundColor Red
    Write-Host '     versionCode 只能递增 —— 应用靠它判断升级，调小会让更新检测失效' -ForegroundColor Yellow
    exit 1
}

# ---------- 2) 打包 ----------
Write-Host '  构建 release…'
$env:JAVA_HOME = 'E:\Program Files\Android\Android Studio\jbr'
& .\gradlew.bat :app:assembleRelease --console=plain 2>&1 |
    Select-String -Pattern '^e: |BUILD FAILED' | ForEach-Object { Write-Host "    $_" -ForegroundColor Red }
if (-not (Test-Path 'app\build\outputs\apk\release\app-release.apk')) {
    Write-Host '  !! 构建失败，没有产物' -ForegroundColor Red
    exit 1
}
Copy-Item 'app\build\outputs\apk\release\app-release.apk' $apkPath -Force
$sha = (Get-FileHash $apkPath -Algorithm SHA256).Hash
$sizeMb = [math]::Round((Get-Item $apkPath).Length / 1MB, 1)
Write-Host "  产物: $apkName  $sizeMb MB"
Write-Host "  SHA256: $sha"

# ---------- 3) 交给 Python 发出去 ----------
# PowerShell 传中文进 JSON 会损坏（实测 GitHub 上变成 "??TV"），
# 而且上传 20MB 二进制会被重置 —— 所以这一步用 Python + curl。
$env:PYTHONIOENCODING = 'utf-8'
$py = 'C:\Users\Administrator\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe'
$publisher = Join-Path $root 'tools\publish_release.py'

& $py $publisher --repo $repo --tag $tag --apk $apkPath `
    --name "焰火TV $tag" --code $VersionCode --version-name $VersionName `
    --notes $Notes --sha $sha

Write-Host ''
Write-Host '提醒：Gitee 的 APK 附件需要到网页端手动上传：' -ForegroundColor Yellow
Write-Host "  https://gitee.com/$repo/releases/tag/$tag" -ForegroundColor Yellow
