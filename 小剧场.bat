@echo off
chcp 65001 >nul
cd /d "%~dp0"
title KidTv Launcher

where python >nul 2>nul
if errorlevel 1 goto NOPY

rem One entry point: open the launcher page and pick one of three modes.
rem The three original tools are unchanged and their own .bat files still work.
python tools\launcher.py %*
if errorlevel 1 (
  echo.
  echo   [START FAILED] If a dependency is missing, run:
  echo     python -m pip install -r tools\requirements.txt
  echo.
  pause
)
goto :EOF

:NOPY
echo Python not found. Please install Python 3.9+ and check "Add to PATH".
echo The three original entry points can still be used directly.
pause
