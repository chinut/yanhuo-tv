# 把扫描器打包成单文件 exe。
#
# ## 为什么用 PyInstaller
#
# 用户那边"装 Python + 装依赖"这一步很容易卡住（PATH 没勾、
# pip 慢、镜像源问题）。打成 exe 之后双击就能跑，最省事。
#
# ## 关键坑
#
# imageio-ffmpeg 在运行时才把 ffmpeg 二进制解压到临时目录。
# PyInstaller 打包后必须把那个二进制一起带上，否则 exe 一起来就报
# "找不到 ffmpeg"。做法：用 --add-binary 把 imageio_ffmpeg/binaries
# 整个目录塞进去，并让程序用 sys._MEIPASS 去取。
import os
import shutil
import subprocess
import sys

HERE = r'G:\android\AndroidTV\tools\iptv'
PY = r'C:\Users\Administrator\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe'

# ---------- 找 ffmpeg 二进制 ----------
import imageio_ffmpeg
ff = imageio_ffmpeg.get_ffmpeg_exe()
print('ffmpeg:', ff)
bindir = os.path.dirname(ff)
print('binaries 目录:', bindir)
for f in os.listdir(bindir):
    print('   ', f, '%.1f MB' % (os.path.getsize(os.path.join(bindir, f)) / 1e6))

# ---------- 生成 hook：让打包后的程序能找到 ffmpeg ----------
hook = '''"""PyInstaller 运行时钩子：把打包进来的 ffmpeg 告诉 imageio_ffmpeg。

为什么要这个：imageio-ffmpeg 默认去 site-packages 里找二进制，
但打包后那东西在 sys._MEIPASS 下，它找不到就会报错。
"""
import os
import sys


def _find():
    base = getattr(sys, '_MEIPASS', None)
    if not base:
        return None
    for root, _dirs, files in os.walk(base):
        for f in files:
            if f.startswith('ffmpeg-') and f.endswith('.exe'):
                return os.path.join(root, f)
    return None


_p = _find()
if _p:
    os.environ['IMAGEIO_FFMPEG_EXE'] = _p
    # 也直接改模块内部变量，双保险
    try:
        import imageio_ffmpeg
        imageio_ffmpeg._get_ffmpeg_exe = lambda: _p
    except Exception:
        pass
'''
with open(os.path.join(HERE, 'pyi_rth_ffmpeg.py'), 'w', encoding='utf-8') as f:
    f.write(hook)
print('已写 pyi_rth_ffmpeg.py')

# ---------- 打包 ----------
cmd = [
    PY, '-m', 'PyInstaller',
    '--noconfirm', '--clean',
    '--onefile',
    '--console',
    '--name', '焰火TV直播源扫描器',
    '--distpath', os.path.join(HERE, 'dist'),
    '--workpath', os.path.join(HERE, 'build_pyi'),
    '--specpath', HERE,
    '--hidden-import', 'PIL._tkinter_finder',
    '--runtime-hook', os.path.join(HERE, 'pyi_rth_ffmpeg.py'),
    '--add-binary', '%s%s.' % (ff, os.pathsep),
    os.path.join(HERE, 'iptv_scan.py'),
]
print()
print('开始打包（可能要几分钟）…')
r = subprocess.run(cmd, capture_output=True, text=True,
                   encoding='utf-8', errors='replace')
if r.returncode != 0:
    print('!! 打包失败')
    tail = (r.stderr or '')[-3000:]
    print(tail)
    sys.exit(1)
print('打包完成')

out = os.path.join(HERE, 'dist', '焰火TV直播源扫描器.exe')
if os.path.exists(out):
    print('产物: %s  %.1f MB' % (out, os.path.getsize(out) / 1e6))
else:
    print('!! 没找到产物，看 dist 目录：')
    d = os.path.join(HERE, 'dist')
    if os.path.isdir(d):
        for f in os.listdir(d):
            print('   ', f)
