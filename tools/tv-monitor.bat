@echo off
REM ============================================================
REM  Yanhuo TV - desktop monitor + remote control
REM  Usage:  tv-monitor.bat                (auto-detect TV)
REM          tv-monitor.bat 192.168.31.233 (specify TV)
REM
REM  NOTE: keep this file ASCII-only + CRLF. cmd.exe parses .bat
REM  byte by byte with the OEM codepage; UTF-8 Chinese breaks it.
REM ============================================================
set PY=C:\Users\Administrator\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe
set ADB=C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe

echo Checking ADB connection...
"%ADB%" connect %1:5555 >nul 2>&1
if "%~1"=="" "%ADB%" connect 192.168.31.233:5555 >nul 2>&1

echo Starting monitor window...
"%PY%" "%~dp0tv-monitor.py" %1
pause
