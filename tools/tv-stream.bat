@echo off
REM ============================================================
REM  Yanhuo TV - desktop MONITOR via screenrecord streaming
REM  Smooth (~12 fps capture, plays at real-time cadence)
REM  Usage:  tv-stream.bat               (auto-detect)
REM          tv-stream.bat 192.168.31.233
REM
REM  NOTE: ASCII-only + CRLF. cmd.exe parses .bat byte by byte
REM  with the OEM codepage; UTF-8 Chinese in a .bat breaks it.
REM ============================================================
set PY=C:\Users\Administrator\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe
set ADB=C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe

echo Checking ADB...
"%ADB%" connect %1:5555 >nul 2>&1
if "%~1"=="" "%ADB%" connect 192.168.31.233:5555 >nul 2>&1

echo Starting streaming monitor...
"%PY%" "%~dp0tv-stream.py" %1
pause
