@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM =====================================================================
REM  IPTV source scanner launcher
REM
REM  NOTE: keep this file ASCII-only outside of echo lines.
REM  cmd.exe parses .bat using the OEM codepage; UTF-8 Chinese inside
REM  REM comments gets executed as commands.
REM  Also MUST use CRLF line endings or cmd mis-parses lines.
REM =====================================================================

set "SCRIPT=%~dp0iptv_scan.py"
set "PY="

echo.
echo ======================================================================
echo   IPTV 源扫描器
echo ======================================================================
echo.
echo   它会：把你家的直播源逐个用播放器真解一遍，
echo         只保留**真的能出画面**的，过滤掉打不开和花屏的。
echo.
echo   第一次运行会自动安装需要的组件（约 30MB，一两分钟）。
echo.
echo ======================================================================
echo.

if not exist "%SCRIPT%" (
    echo   找不到 iptv_scan.py
    echo   请确认它和本文件在同一个文件夹里。
    echo.
    pause
    exit /b 1
)

REM ---------- 找 Python ----------
where python >nul 2>nul
if not errorlevel 1 set "PY=python"

if not defined PY (
    where py >nul 2>nul
    if not errorlevel 1 set "PY=py"
)

if not defined PY (
    echo   没有找到 Python。
    echo.
    echo   请先安装 Python（免费）：
    echo       https://www.python.org/downloads/
    echo.
    echo   安装时**一定要勾选** "Add Python to PATH" 这个选项，
    echo   然后重新双击本文件。
    echo.
    pause
    exit /b 1
)

echo   Python: %PY%
echo.

REM ---------- 装依赖 ----------
"%PY%" -c "import imageio_ffmpeg, PIL" >nul 2>nul
if errorlevel 1 (
    echo   正在安装组件，请稍等（一两分钟）……
    echo.
    "%PY%" -m pip install --upgrade pip >nul 2>nul
    "%PY%" -m pip install imageio-ffmpeg pillow
    if errorlevel 1 (
        echo.
        echo   安装失败。请手动运行下面这行，然后重试：
        echo       %PY% -m pip install imageio-ffmpeg pillow
        echo.
        pause
        exit /b 1
    )
    echo.
    echo   组件装好了。
    echo.
)

REM ---------- 跑 ----------
"%PY%" "%SCRIPT%" %*

echo.
echo ======================================================================
echo   扫描结束
echo ======================================================================
echo.
echo   结果文件（在本文件夹里）：
echo      iptv_result.json    ^<- 把这个发给我
echo      iptv_可用源.m3u     ^<- 也可以直接填进 App 试
echo.
pause
