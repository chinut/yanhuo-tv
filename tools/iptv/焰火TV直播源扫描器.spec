# -*- mode: python ; coding: utf-8 -*-


a = Analysis(
    ['G:/android/AndroidTV/tools/iptv/iptv_scan.py'],
    pathex=[],
    binaries=[('C:/Users/Administrator/.dsh/dsh-runtimes/dsh-primary-runtime/dependencies/python/Lib/site-packages/imageio_ffmpeg/binaries/ffmpeg-win-x86_64-v7.1.exe', '.')],
    datas=[],
    hiddenimports=['PIL._tkinter_finder'],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=['G:/android/AndroidTV/tools/iptv/pyi_rth_ffmpeg.py'],
    excludes=[],
    noarchive=False,
    optimize=0,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name='焰火TV直播源扫描器',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=True,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
