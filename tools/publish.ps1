<#
.SYNOPSIS
  本地一键发布：构建 release APK，并上传到 GitHub 的 Release。
（⚠️ 2026-10 起不再同步 Gitee —— 那边提示"上传内容违规"。
  GiteeToken 参数保留只为兼容，传了也不会用。）

.DESCRIPTION
  App 的「检查更新」会读取 Gitee 与 GitHub 的 latest release，
  并从 Release 说明里解析 versionCode: N，从附件里取 APK。
  所以这个脚本必须保证：
    1) Release 说明里包含 versionCode: <app/build.gradle.kts 里的值>
    2) 附件里有一个 .apk

.PARAMETER Tag
  版本标签，例如 v1.0.0（必须与 build.gradle.kts 里的 versionName 对应）

.PARAMETER GiteeToken
  Gitee 私人令牌（https://gitee.com/profile/personal_access_tokens）

.PARAMETER GitHubToken
  GitHub Personal Access Token（repo 权限）

.EXAMPLE
  .\tools\publish.ps1 -Tag v1.0.0 -GiteeToken xxx -GitHubToken yyy
#>
param(
    [Parameter(Mandatory = $true)][string]$Tag,
    [string]$GiteeToken = $env:GITEE_TOKEN,
    [string]$GitHubToken = $env:GITHUB_TOKEN,
    [string]$GiteeRepo = "chinut/yanhuo-tv",
    [string]$GitHubRepo = "chinut/yanhuo-tv",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root

$env:JAVA_HOME = "E:\Program Files\Android\Android Studio\jbr"

# ---------- 读版本号 ----------
$gradle = Get-Content "app\build.gradle.kts" -Raw
$versionCode = [regex]::Match($gradle, 'versionCode\s*=\s*(\d+)').Groups[1].Value
$versionName = [regex]::Match($gradle, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
Write-Host "版本：$versionName (versionCode $versionCode)" -ForegroundColor Cyan

# ---------- 构建 ----------
if (-not $SkipBuild) {
    Write-Host "构建 release APK..." -ForegroundColor Cyan
    & .\gradlew.bat :app:assembleRelease --console=plain
    if ($LASTEXITCODE -ne 0) { throw "构建失败" }
}

$apk = Get-ChildItem "app\build\outputs\apk\release\*.apk" | Select-Object -First 1
if (-not $apk) { throw "没有找到 release APK" }
$apkName = "yanhuoTV-$versionName.apk"
Write-Host "APK：$($apk.FullName)  ($([math]::Round($apk.Length/1MB,1)) MB)" -ForegroundColor Green

$notes = @"
焰火TV $versionName

versionCode: $versionCode
versionName: $versionName

### 更新内容
- 见提交记录

### 安装说明
1. 下载下方 APK
2. 电视/盒子用 U 盘或 ADB 安装
3. 首次安装需允许「安装未知来源应用」
"@

# ---------- Gitee ----------
if ($GiteeToken) {
    Write-Host "发布到 Gitee ($GiteeRepo)..." -ForegroundColor Cyan
    $body = @{
        tag_name    = $Tag
        name        = "焰火TV $versionName"
        body        = $notes
        target_commitish = "master"
    } | ConvertTo-Json -Depth 5

    $release = $null
    try {
        $release = Invoke-RestMethod -Method Post `
            -Uri "https://gitee.com/api/v5/repos/$GiteeRepo/releases" `
            -Body (@{ access_token = $GiteeToken; tag_name = $Tag; name = "焰火TV $versionName"; body = $notes; target_commitish = "master" }) `
            -ContentType "application/x-www-form-urlencoded"
    } catch {
        Write-Warning "创建 Release 失败（可能已存在）：$($_.Exception.Message)"
        $list = Invoke-RestMethod -Uri "https://gitee.com/api/v5/repos/$GiteeRepo/releases?access_token=$GiteeToken&per_page=20"
        $release = $list | Where-Object { $_.tag_name -eq $Tag } | Select-Object -First 1
    }

    if ($release) {
        Write-Host "上传附件..." -ForegroundColor Cyan
        & curl.exe -s -X POST "https://gitee.com/api/v5/repos/$GiteeRepo/releases/$($release.id)/attach_files" `
            -F "access_token=$GiteeToken" `
            -F "file=@$($apk.FullName)" | Out-Null
        Write-Host "Gitee 发布完成：$($release.html_url)" -ForegroundColor Green
    }
} else {
    Write-Warning "未提供 GiteeToken，跳过 Gitee"
}

# ---------- GitHub ----------
if ($GitHubToken) {
    Write-Host "发布到 GitHub ($GitHubRepo)..." -ForegroundColor Cyan
    $headers = @{
        Authorization = "Bearer $GitHubToken"
        Accept        = "application/vnd.github+json"
        "User-Agent"  = "yanhuoTV-publish"
    }
    $payload = @{ tag_name = $Tag; name = "焰火TV $versionName"; body = $notes; draft = $false; prerelease = $false } | ConvertTo-Json
    try {
        $rel = Invoke-RestMethod -Method Post -Uri "https://api.github.com/repos/$GitHubRepo/releases" -Headers $headers -Body $payload -ContentType "application/json"
    } catch {
        $rel = Invoke-RestMethod -Uri "https://api.github.com/repos/$GitHubRepo/releases/tags/$Tag" -Headers $headers
    }
    Write-Host "上传附件..." -ForegroundColor Cyan
    $uploadUrl = ($rel.upload_url -replace '\{.*\}', '') + "?name=$apkName"
    & curl.exe -s -X POST $uploadUrl -H "Authorization: Bearer $GitHubToken" -H "Content-Type: application/vnd.android.package-archive" --data-binary "@$($apk.FullName)" | Out-Null
    Write-Host "GitHub 发布完成：$($rel.html_url)" -ForegroundColor Green
} else {
    Write-Warning "未提供 GitHubToken，跳过 GitHub"
}

Write-Host "`n全部完成。App 内「设置 → 检查更新」即可看到新版本。" -ForegroundColor Green
