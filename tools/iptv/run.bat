@echo off
chcp 65001 >nul
setlocal

REM =====================================================================
REM YanhuoTV IPTV source scanner launcher
REM
REM NOTE: keep this file ASCII-only inside REM lines.
REM cmd.exe parses .bat with the OEM codepage, so UTF-8 Chinese inside
REM REM comments gets executed as commands. Also MUST use CRLF endings.
REM =====================================================================

cd /d "%~dp0"
set "EXE=%~dp0焰火TV直播源扫描器.exe"

echo.
echo ======================================================================
echo   焰火TV 直播源扫描器
echo ======================================================================
echo.
echo   它会把每个直播源真的用播放器解一遍，验证：
echo      画面有内容（不是黑屏/纯色）
echo      画面在动（直播一定是动的）
echo.
echo   只保留通过验证的，生成「直播源.m3u」给你导进电视。
echo.
echo   耗时通常 15~50 分钟，屏幕会一直滚动进度 - 不是卡住了。
echo.
echo   建议在看电视的时候别跑，它会占一部分带宽。
echo.
echo ======================================================================
echo.

if not exist "%EXE%" (
    echo   找不到「焰火TV直播源扫描器.exe」
    echo   请确认它和本文件在同一个文件夹里。
    echo.
    pause
    exit /b 1
)

"%EXE%" %*

echo.
echo ======================================================================
echo   扫描结束
echo ======================================================================
echo.
echo   生成的文件（在本文件夹里）：
echo.
echo     直播源.m3u
echo        把这个拷到 U 盘，插到电视上
echo        电视上：设置 - 直播源 - 选择文件 - 选中它
echo.
echo     扫描结果.json
echo        想让我帮你看结果就发这个
echo.
echo ======================================================================
echo.
pause
