"""PyInstaller 运行时钩子：把打包进来的 ffmpeg 告诉 imageio_ffmpeg。

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
