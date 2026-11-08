@echo off
chcp 65001 >nul
cd /d "%~dp0"
title 小剧场 · PC 配置助手

where python >nul 2>nul
if errorlevel 1 goto NOPY

rem 打包版优先：双击即用，不需要 Python 源码和依赖
if exist "dist\KidTvConfig-PC.exe" (
  start "" "dist\KidTvConfig-PC.exe" %*
  goto :EOF
)

rem 源码运行：首次自动装依赖（pysmb / qrcode）
python -c "import smb, qrcode" >nul 2>nul
if errorlevel 1 (
  echo 首次运行，正在安装依赖，请稍候...
  python -m pip install -r tools\requirements.txt
)

python tools\desktop.py %*
if errorlevel 1 pause
goto :EOF

:NOPY
echo 没找到 Python。请先安装 Python 3.9+（勾选 Add to PATH），
echo 或直接运行 tools\build_pc_exe.bat 生成独立 exe。
pause
