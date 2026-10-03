@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM =====================================================================
REM  Short-drama app packet capture launcher
REM
REM  NOTE: keep this file ASCII-only outside of echo lines.
REM  cmd.exe parses .bat byte-by-byte using the OEM codepage, so UTF-8
REM  Chinese inside REM comments gets misinterpreted as commands
REM  (symptom: "'xx' is not recognized as an internal or external command").
REM =====================================================================

echo.
echo ========================================================================
echo   短剧 App 抓包工具
echo ========================================================================
echo.

set "SCRIPT=%~dp0shortdrama_capture.py"
set "PY="
set "MITM="

REM ---------- locate Python ----------
set "DSHPY=C:\Users\Administrator\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe"
if exist "%DSHPY%" set "PY=%DSHPY%"

if not defined PY (
    where python >nul 2>nul
    if not errorlevel 1 set "PY=python"
)
if not defined PY (
    where py >nul 2>nul
    if not errorlevel 1 set "PY=py"
)

if not defined PY (
    echo   找不到 Python。
    echo.
    echo   请先安装 Python 3： https://www.python.org/downloads/
    echo   安装时记得勾选 "Add Python to PATH"
    echo.
    pause
    exit /b 1
)

echo   Python : %PY%
echo.

REM ---------- ensure mitmproxy is installed ----------
"%PY%" -c "import mitmproxy" >nul 2>nul
if errorlevel 1 (
    echo   mitmproxy 未安装，现在自动安装（第一次可能要一两分钟）...
    echo.
    "%PY%" -m pip install --upgrade pip >nul 2>nul
    "%PY%" -m pip install mitmproxy
    if errorlevel 1 (
        echo.
        echo   安装失败。手动执行这一行再重试：
        echo       "%PY%" -m pip install mitmproxy
        echo.
        pause
        exit /b 1
    )
    echo.
    echo   安装完成。
    echo.
)

REM ---------- locate mitmdump.exe ----------
REM Prefer mitmdump over mitmproxy: headless, more stable for long captures,
REM and the interactive console UI garbles easily in the Windows terminal.
for %%D in ("%PY%") do set "PYDIR=%%~dpD"
if exist "%PYDIR%Scripts\mitmdump.exe" set "MITM=%PYDIR%Scripts\mitmdump.exe"

if not defined MITM (
    where mitmdump >nul 2>nul
    if not errorlevel 1 set "MITM=mitmdump"
)

if not defined MITM (
    echo   找不到 mitmdump。请手动执行：
    echo       "%PY%" -m pip install --force-reinstall mitmproxy
    echo.
    pause
    exit /b 1
)

echo   抓包程序: %MITM%
echo.

REM ---------- start capture ----------
REM The usage instructions are printed by the addon's running() hook;
REM do not echo them here or they will appear twice.
"%MITM%" -s "%SCRIPT%" --listen-port 8088

echo.
echo   抓包已停止。
echo   结果保存在： %~dp0..\captures\requests.log
echo.
echo   把那个文件发我，我就能分析出短剧接口。
echo.
pause
