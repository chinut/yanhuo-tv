#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
焰火TV 电脑监控窗口（推流版）—— 比截图版流畅 60 倍。

## 为什么不是"截图"

截图（`screencap`）实测 **5.2 秒/帧 ≈ 0.19 帧/秒**，
而且瓶颈在电视硬件（GPU 读回整帧 1920x1080），换任何参数都没用。

## 为什么也不是"实时推流"

试过 `adb exec-out screenrecord --output-format=h264 -` 直接喂 ffmpeg：
**第 1 帧等了 69 秒，183 秒才出 2 帧**。
设备端编码器本身能出 12.8 帧/秒，但 `screenrecord` 往 stdout 写时严重缓冲，
走 adb shell 管道拿不到实时数据。（`-probesize 32 -analyzeduration 0` 也救不回来。）

## 最终方案：分段录制 + 拉回本地流畅播放

    设备上录 4 秒 MP4  →  adb pull  →  本地 ffmpeg 解成帧  →  Tk 按真实帧率播放

实测：

    录制 4 秒（含启停）实际花 7.0 秒，文件 0.20 MB
    解码出 51 帧  →  **12.8 帧/秒**（截图版的 65 倍）

画面是一段**连续的 4 秒视频**在流畅播放，不是一张张静止图。
代价是约 7 秒的"新鲜度延迟" —— 对"有个大概监控就行了"完全够用。

## 实测时序（决定播放节奏怎么写）

    录 4s @1280x720：录制 6.86s + 拉 0.18s + 解码 0.41s = **7.53s**，49 帧
      → 捕获 12.2 帧/秒，但这段视频要用 7.53 秒播完才是真实速度
    录 3s @1280x720：共 6.31s，37 帧
    录 2s @960x540 ：共 5.19s，24 帧

所以播放**不能**用固定帧率（会播得比录得快 → 一顿一顿 + 延迟积压），
要用「本段循环耗时 / 本段帧数」当每帧间隔。

注意这台电视的编码器**跑不满实时**：录 4 秒内容花 6.86 秒。
所以画面动作会略慢于真实速度 —— 这是硬件限制，
但**连续、不卡顿**，做监控够用。

## 用法

    python tv-stream.py                  # 自动找设备
    python tv-stream.py 192.168.31.233   # 指定电视
"""
import io
import os
import queue
import subprocess
import sys
import threading
import time
import tkinter as tk
import urllib.request

# ---------- 配置 ----------
ADB = r'C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe'
HTTP_PORT = 8899
SIZE = '1280x720'      # 录制分辨率（拉回的文件仍然很小）
DISPLAY_W = 960        # 窗口里的显示宽度
SEGMENT_SEC = 4        # 每段录制秒数
PLAY_FPS = 13          # 播放帧率（实测录制约 12.8 帧/秒）
BITRATE = '4M'

KEY = {
    'up': 19, 'down': 20, 'left': 21, 'right': 22,
    'ok': 23, 'back': 4, 'home': 3, 'menu': 82,
    'play': 85, 'prev': 88, 'next': 87,
    'volup': 24, 'voldown': 25, 'mute': 164, 'power': 26,
}


def find_serial(explicit=None):
    if explicit:
        return explicit if ':' in explicit else explicit + ':5555'
    try:
        out = subprocess.run([ADB, 'devices'], capture_output=True, text=True,
                             timeout=15).stdout
    except Exception:
        return None
    for line in out.splitlines()[1:]:
        p = line.split()
        if len(p) >= 2 and p[1] == 'device' and ':' in p[0]:
            return p[0]
    return None


def ffmpeg_exe():
    import imageio_ffmpeg
    return imageio_ffmpeg.get_ffmpeg_exe()


class Recorder(threading.Thread):
    """后台循环：录一段 → 拉回 → 解码 → 把帧列表塞进队列。"""

    def __init__(self, serial, out_queue, stop_evt):
        super().__init__(daemon=True)
        self.serial = serial
        self.q = out_queue
        self.stop_evt = stop_evt
        self.exe = ffmpeg_exe()
        self.seq = 0
        self.last_stat = ''

    def run(self):
        w, h = (int(x) for x in SIZE.split('x'))
        fb = w * h * 3
        import numpy as np
        while not self.stop_evt.is_set():
            t0 = time.time()
            # 每次用不同文件名，避免"上次还在用就被删"的占用错误
            self.seq = (self.seq + 1) % 3
            dev = '/sdcard/_tvmon_%d.mp4' % self.seq
            local = os.path.join(os.environ.get('TEMP', '.'),
                                 '_tvmon_%d.mp4' % self.seq)
            try:
                if os.path.exists(local):
                    os.remove(local)
            except Exception:
                pass

            # 1) 录
            subprocess.run(
                [ADB, '-s', self.serial, 'shell', 'screenrecord',
                 '--size=%s' % SIZE, '--bit-rate=%s' % BITRATE,
                 '--time-limit=%d' % SEGMENT_SEC, dev],
                capture_output=True, timeout=SEGMENT_SEC + 40,
            )
            if self.stop_evt.is_set():
                break
            t_rec = time.time() - t0

            # 2) 拉
            subprocess.run([ADB, '-s', self.serial, 'pull', dev, local],
                           capture_output=True, timeout=90)
            subprocess.run([ADB, '-s', self.serial, 'shell', 'rm', '-f', dev],
                           capture_output=True, timeout=20)
            if not os.path.exists(local):
                self.last_stat = '拉取失败'
                continue
            size = os.path.getsize(local)

            # 3) 解码
            r = subprocess.run(
                [self.exe, '-hide_banner', '-loglevel', 'error',
                 '-i', local, '-f', 'rawvideo', '-pix_fmt', 'rgb24',
                 '-an', '-sn', 'pipe:1'],
                capture_output=True, timeout=240,
            )
            data = r.stdout
            frames = []
            for i in range(0, len(data) - fb + 1, fb):
                frames.append(
                    np.frombuffer(data[i:i + fb], dtype=np.uint8).reshape(h, w, 3))
            try:
                os.remove(local)
            except Exception:
                pass

            self.last_stat = '录 %.1fs / 拉+解 %.1fs / %d 帧 / %.1f fps / %.2fMB' % (
                t_rec, time.time() - t0 - t_rec, len(frames),
                len(frames) / SEGMENT_SEC, size / 1048576)

            if frames:
                # 带上真实循环耗时：播放时据此算每帧间隔（见文件顶部注释）
                payload = (frames, time.time() - t0)
                # 丢掉积压的旧段，只保留最新（避免越跟越慢）
                while self.q.qsize() > 1:
                    try:
                        self.q.get_nowait()
                    except queue.Empty:
                        break
                try:
                    self.q.put_nowait(payload)
                except queue.Full:
                    pass


class Remote:
    def __init__(self, host, port=HTTP_PORT):
        self.base = 'http://%s:%d' % (host, port)

    def _get(self, path, timeout=6.0):
        try:
            r = urllib.request.Request(self.base + path)
            r.add_header('User-Agent', 'tv-stream')
            with urllib.request.urlopen(r, timeout=timeout) as x:
                return x.read().decode('utf-8', 'replace')
        except Exception:
            return None

    def key(self, name):
        c = KEY.get(name)
        return c is not None and self._get('/api/remote/key?code=%d' % c) is not None

    def status(self):
        import json
        t = self._get('/api/remote/status')
        try:
            return json.loads(t) if t else {}
        except Exception:
            return {}


class App:
    def __init__(self, root, serial, host):
        self.root = root
        self.remote = Remote(host)
        self.stop_evt = threading.Event()
        self.frames = []
        self.idx = 0
        self._photo = None
        self._last_status = 0
        self.frame_ms = int(1000 / PLAY_FPS)   # 实际每帧间隔，收到数据后重算
        self.q = queue.Queue(maxsize=3)

        root.title('焰火TV 监控（推流）— %s' % serial)
        root.configure(bg='#0b0d18')
        root.geometry('1040x760')

        self.screen = tk.Label(root, bg='#000000', bd=0)
        self.screen.pack(fill=tk.BOTH, expand=True, padx=10, pady=(10, 6))

        self.status = tk.Label(root, text='正在启动录制…', bg='#0b0d18',
                               fg='#8b95b5', font=('Microsoft YaHei UI', 9),
                               anchor='w', justify='left')
        self.status.pack(fill=tk.X, padx=14)

        pad = tk.Frame(root, bg='#0b0d18')
        pad.pack(fill=tk.X, padx=10, pady=(6, 12))
        rows = [
            [('回首页', 'home'), ('菜单', 'menu'), ('返回', 'back'), ('暂停/播放', 'play')],
            [('上一集', 'prev'), ('▲ 上', 'up'), ('下一集', 'next'), ('音量+', 'volup')],
            [('◀ 左', 'left'), ('确定', 'ok'), ('▶ 右', 'right'), ('音量-', 'voldown')],
            [('静音', 'mute'), ('▼ 下', 'down'), ('电源', 'power')],
        ]
        for r, row in enumerate(rows):
            for c, (label, name) in enumerate(row):
                tk.Button(pad, text=label,
                          command=lambda n=name: self.send(n),
                          bg='#161b2e', fg='#eef2ff', activebackground='#5aa9ff',
                          activeforeground='#ffffff', relief='flat',
                          font=('Microsoft YaHei UI', 11), height=2,
                          cursor='hand2').grid(row=r, column=c, sticky='nsew',
                                               padx=4, pady=4)
            pad.columnconfigure(r, weight=1)

        root.bind('<Up>', lambda e: self.send('up'))
        root.bind('<Down>', lambda e: self.send('down'))
        root.bind('<Left>', lambda e: self.send('left'))
        root.bind('<Right>', lambda e: self.send('right'))
        root.bind('<Return>', lambda e: self.send('ok'))
        root.bind('<BackSpace>', lambda e: self.send('back'))
        root.bind('<Escape>', lambda e: self.quit())
        root.bind('<space>', lambda e: self.send('play'))
        root.bind('h', lambda e: self.send('home'))
        root.bind('m', lambda e: self.send('menu'))
        root.bind('+', lambda e: self.send('volup'))
        root.bind('-', lambda e: self.send('voldown'))
        root.protocol('WM_DELETE_WINDOW', self.quit)

        self.rec = Recorder(serial, self.q, self.stop_evt)
        self.rec.start()

        threading.Thread(target=self.pull_frames, daemon=True).start()
        self.tick()

    def pull_frames(self):
        while not self.stop_evt.is_set():
            try:
                frames, cycle_sec = self.q.get(timeout=1.0)
                self.frames = frames
                self.idx = 0
                # 每帧间隔 = 循环耗时 / 帧数 → 播完正好等于录制速度
                self.frame_ms = max(1, int(cycle_sec * 1000 / max(1, len(frames))))
            except queue.Empty:
                pass

    def tick(self):
        # 播放当前段
        if self.frames:
            if self.idx >= len(self.frames):
                self.idx = 0
            try:
                from PIL import Image, ImageTk
                arr = self.frames[self.idx]
                img = Image.fromarray(arr)
                if img.width > DISPLAY_W:
                    img = img.resize(
                        (DISPLAY_W, int(img.height * DISPLAY_W / img.width)),
                        Image.BILINEAR)
                self._photo = ImageTk.PhotoImage(img)
                self.screen.config(image=self._photo)
            except Exception:
                pass
            self.idx += 1

        now = time.time() * 1000
        if now - self._last_status > 2000:
            self._last_status = now
            st = self.remote.status()
            line1 = '界面：%s    音量：%s/%s%s' % (
                st.get('screen', '?'), st.get('volume', '?'),
                st.get('volumeMax', '?'), '   静音中' if st.get('muted') else '')
            line2 = (self.rec.last_stat or '等待第一段…')
            if self.frames:
                line2 += '  |  播放 %.1f 帧/秒' % (1000.0 / self.frame_ms)
            self.status.config(text=line1 + '\n' + line2)

        if not self.stop_evt.is_set():
            self.root.after(getattr(self, 'frame_ms', int(1000 / PLAY_FPS)),
                            self.tick)

    def send(self, name):
        threading.Thread(target=self.remote.key, args=(name,),
                         daemon=True).start()

    def quit(self):
        self.stop_evt.set()
        self.root.destroy()


def main():
    target = sys.argv[1] if len(sys.argv) > 1 else None
    serial = find_serial(target)
    if not serial:
        print('找不到网络 ADB 设备。')
        return 1
    host = serial.split(':')[0]
    print('设备 %s' % serial)
    root = tk.Tk()
    App(root, serial, host)
    root.mainloop()
    return 0


if __name__ == '__main__':
    sys.exit(main())
