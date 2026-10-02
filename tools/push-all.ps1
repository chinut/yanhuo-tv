# ============================================================
#  焰火TV —— 一键推送到 GitHub + Gitee
#
#  前置条件：两个平台各建一个**空仓库**（不要勾选初始化 README / .gitignore）：
#      GitHub : https://github.com/new           仓库名 yanhuo-tv
#      Gitee  : https://gitee.com/projects/new   仓库名 yanhuo-tv
#
#  用法：
#      .\tools\push-all.ps1
#
#  首次推送时 git 会弹窗要账号密码：
#      GitHub 密码位置填 Personal Access Token（不是登录密码）
#      Gitee  密码位置填私人令牌
#  填过一次后 Windows 凭据管理器会记住，以后不用再填。
# ============================================================

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root

$branch = "main"

Write-Host ""
Write-Host "=== 焰火TV 推送到双平台 ===" -ForegroundColor Cyan
Write-Host ""

# ---------- 检查是否是 git 仓库 ----------
if (-not (Test-Path ".git")) {
    Write-Host "✗ 当前目录不是 git 仓库" -ForegroundColor Red
    exit 1
}

# ---------- 确认分支名 ----------
$current = (git rev-parse --abbrev-ref HEAD 2>&1).Trim()
if ($current -ne $branch) {
    Write-Host "当前分支是 $current，重命名为 $branch ..." -ForegroundColor Yellow
    git branch -M $branch
}

# ---------- 检查有无未提交改动 ----------
$dirty = git status --porcelain 2>&1
if ($dirty) {
    Write-Host "⚠ 有未提交的改动：" -ForegroundColor Yellow
    $dirty | Select-Object -First 10 | ForEach-Object { Write-Host "    $_" }
    Write-Host ""
    $ans = Read-Host "要先提交这些改动吗？(y/N)"
    if ($ans -eq "y") {
        git add -A
        $msg = Read-Host "提交说明（直接回车用默认）"
        if (-not $msg) { $msg = "chore: 更新" }
        git commit -m $msg
    }
}

# ---------- 推送 ----------
function Push-One($name, $remote) {
    Write-Host ""
    Write-Host "--- 推送到 $name ($remote) ---" -ForegroundColor Cyan
    $url = git remote get-url $remote 2>&1
    if (-not $url -or $LASTEXITCODE -ne 0) {
        Write-Host "✗ 远程 $remote 未配置" -ForegroundColor Red
        return $false
    }
    Write-Host "    地址: $url"

    # 先探测仓库是否已创建，避免推一半失败
    $env:GIT_TERMINAL_PROMPT = "0"
    $probe = git ls-remote --heads $remote 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Host "✗ 连不上或仓库不存在。请先在 $name 上创建空仓库 yanhuo-tv" -ForegroundColor Red
        Write-Host "    （错误信息：$($probe | Select-Object -First 1)）" -ForegroundColor DarkGray
        $env:GIT_TERMINAL_PROMPT = "1"
        return $false
    }
    $env:GIT_TERMINAL_PROMPT = "1"

    git push -u $remote $branch 2>&1 | ForEach-Object { Write-Host "    $_" }
    if ($LASTEXITCODE -eq 0) {
        Write-Host "✓ $name 推送成功" -ForegroundColor Green
        return $true
    } else {
        Write-Host "✗ $name 推送失败" -ForegroundColor Red
        return $false
    }
}

$okGithub = Push-One "GitHub" "origin"
$okGitee = Push-One "Gitee" "gitee"

Write-Host ""
Write-Host "=== 结果 ===" -ForegroundColor Cyan
Write-Host ("  GitHub : " + $(if ($okGithub) { "✓ 成功" } else { "✗ 失败" }))
Write-Host ("  Gitee  : " + $(if ($okGitee) { "✓ 成功" } else { "✗ 失败" }))
Write-Host ""

if ($okGithub -or $okGitee) {
    Write-Host "下一步：打 tag 触发自动构建发布 Release" -ForegroundColor Yellow
    Write-Host "    .\tools\publish.ps1 -Tag v1.0.0 -GiteeToken <token> -GitHubToken <token>" -ForegroundColor Gray
    Write-Host ""
    Write-Host "  （App 内的「检查更新」读取 Gitee 优先 / GitHub 回退的 latest release）" -ForegroundColor DarkGray
}
