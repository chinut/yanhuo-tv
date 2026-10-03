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
