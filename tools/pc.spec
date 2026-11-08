# -*- mode: python ; coding: utf-8 -*-
"""小剧场 · PC 配置助手 单文件 exe 打包配置

用法（在项目根目录）：python -m PyInstaller --noconfirm --clean tools/pc.spec
"""
from pathlib import Path

ROOT = Path(SPECPATH).resolve().parent

a = Analysis(
    [str(ROOT / "tools" / "desktop.py")],
    pathex=[str(ROOT / "tools")],
    binaries=[],
    datas=[
        (str(ROOT / "tools" / "static"), "tools/static"),
        (str(ROOT / "examples" / "tv.json"), "examples"),
        (str(ROOT / "nas.example.json"), "."),
    ],
    hiddenimports=[
        "smb.SMBConnection",
        "qrcode",
        "qrcode.image.svg",
        "webview",  # 可选，没装也不会报错
    ],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=["tkinter", "matplotlib", "numpy", "PIL"],
    noarchive=False,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name="KidTvConfig-PC",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
