@echo off
title tv.json 编辑器 - 使用期间请勿关闭此窗口
cd /d "%~dp0"
echo.
echo   ============================================================
echo      tv.json Web 编辑器
echo   ============================================================
echo      浏览器会自动打开编辑页面。
echo      使用期间请保留此窗口；关闭窗口（或按 Ctrl+C）即停止服务。
echo      重复双击桌面图标不会冲突，只会再次打开页面。
echo   ============================================================
echo.
chcp 65001 >nul
python tools\tvjson_simple.py %*
if errorlevel 1 (
  echo.
  echo   [START FAILED] Please install Python and pysmb:
  echo     python -m pip install pysmb
  echo.
  pause
)
