@echo off
chcp 65001 >nul
cd /d "%~dp0.."
title 打包小剧场 PC 配置助手

where python >nul 2>nul
if errorlevel 1 goto NOPY

echo 1/3 检查依赖...
python -m pip install -r tools\requirements.txt pyinstaller

echo 2/3 打包成单文件 exe（约 1-2 分钟）...
python -m PyInstaller --noconfirm --clean tools\pc.spec
if errorlevel 1 goto FAIL

echo.
echo 3/3 完成：dist\KidTvConfig-PC.exe
echo 双击根目录的 KidTvConfig-PC.bat 即可启动，exe 也可以单独拷到别的电脑用。
echo 配置和访问码会保存在 exe 同目录，不会写进 exe 内部。
echo.
pause
goto :EOF

:FAIL
echo 打包失败，请把上面的报错发给我。
pause
exit /b 1

:NOPY
echo 没找到 Python，请先安装 Python 3.9+ 并勾选 Add to PATH。
pause
exit /b 1
