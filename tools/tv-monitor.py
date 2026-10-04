#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
焰火TV 电脑监控窗口 —— 在电脑上一边看画面，一边用键盘/按钮遥控电视。

## 为什么做这个

用户原话：「提供一个电脑上的电视监控窗口最好能够控制
            我不想跑来跑去看屏幕上显示的啥」

## 两条数据通道

1. **画面**：`adb exec-out screencap -p`
2. **遥控**：App 自带的 HTTP 遥控接口 `http://<电视IP>:8899/api/remote/*`

为什么用 ADB 截图而不是让 App 自己截：
· SurfaceView 里的视频画面 `View.draw()` **抓不到**（独立图层），
  而 `screencap` 是系统级合成结果，连视频画面一起抓得到
· ADB 本来就连着，不需要额外改 App

## ⚠️ 帧率的硬限制（实测，别再怀疑自己）

    adb 纯往返           0.07 秒   ← 网络没问题
    电视「拍一张」        2.61 秒   ← **瓶颈在这里**
      -g 图形层            2.61 秒
      -a 图形+视频         2.96 秒
      -p PNG              3.67 秒
      -v 只视频层          0.68 秒   ← 快 4 倍，但拍不到界面（只有视频画面）

慢在**老电视 GPU 读回整帧 1920x1080 帧缓冲**，和网络、和本程序都无关。
**上限就是约 0.4 帧/秒。**

想要真正实时（30 帧）请用 `scrcpy` —— 它是把设备屏幕**编码成视频流**，
走的是完全不同的通道，不受这个限制。本窗口的定位是
**"能遥控 + 能看到画面"**，不是"实时投屏"。

本程序在这个限制内做了三件事让它尽量跟手：
1. 按过键之后**取消等待、立刻抓一帧**（这一下最影响手感）
2. 连续操作时用最快节奏
3. 状态条显示**实际帧率**（让你知道当前速度，而不是"感觉慢但不知道为什么"）

## 踩过的两个坑（都写在这里，免得以后重踩）

### 坑 1：小米电视的 `screencap` 会在 PNG 前面多输出一行

    文件头: b'argc: 2 \n\x89PNG\r\n\x1a'

那 9 个字节是 toybox 的调试残留，直接把 PNG 弄坏，
PIL 报 `UnidentifiedImageError`。
**必须从 `\\x89PNG` 开始截取。**

### 坑 2：Windows 上 `exec-out` 走管道会被做 CRLF 转换

`subprocess.run(..., capture_output=True)` 拿到的是文本模式管道，
`\\n` 会被改成 `\\r\\n`，二进制就毁了。
**改成让 adb 直接重定向写文件**（`cmd /c adb ... > out.png`）绕开管道。

## 用法

    python tv-monitor.py                 # 自动找第一台网络设备
    python tv-monitor.py 192.168.31.233  # 指定电视

窗口里：上方是电视画面（自动刷新），下方是遥控按键。
也可以直接用键盘：

    方向键 → 电视上下左右      回车 → 确定        退格 → 返回
    h → 回首页                空格 → 暂停/播放   m → 菜单
    + / - → 音量              Esc → 关闭本窗口
"""
import io
import os
import queue
import subprocess
import sys
import tempfile
import threading
import time
import tkinter as tk
import urllib.request

# ---------- 可配置 ----------
ADB = r'C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe'
HTTP_PORT = 8899          # App 调试服务端口（被占时 App 会自动往后换）
SHOT_INTERVAL_MS = 150    # 每帧之间的**额外**等待（抓一帧本身就要 2.6 秒）
FAST_AFTER_KEY_MS = 4000  # 按过键之后这段时间内用最快节奏
SHOT_WIDTH = 960          # 显示宽度（缩放省电脑 CPU）
STATUS_EVERY_MS = 3000    # 状态刷新间隔

# ---------- Android KeyCode ----------
KEY = {
    'up': 19, 'down': 20, 'left': 21, 'right': 22,
    'ok': 23, 'back': 4, 'home': 3, 'menu': 82,
    'play': 85, 'prev': 88, 'next': 87,
    'volup': 24, 'voldown': 25, 'mute': 164, 'power': 26,
}


# 抓帧临时文件的轮换序号（避免同名冲突，见 grab_png 注释）
_SHOT_SEQ = 0


def find_serial(explicit=None):
    """找一台网络 ADB 设备。"""
    if explicit:
        return explicit if ':' in explicit else explicit + ':5555'
    try:
        out = subprocess.run([ADB, 'devices'], capture_output=True, text=True,
                             timeout=15).stdout
    except Exception:
        return None
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == 'device' and ':' in parts[0]:
            return parts[0]
    return None


def grab_png(serial, timeout=25):
    """
    抓一帧画面，返回 PNG 字节；失败返回 None。

    两个坑见文件顶部注释：要剥掉 toybox 的前缀、要绕开文本管道。
    """
    # ⚠️ 每次用**不同的文件名**。
    #
    # 踩过的坑：原来固定用 `_tvmon_shot.png`，于是
    #   · 抓帧线程正在用这个文件时，下一次抓帧要先删它 →
    #     Windows 报 PermissionError（文件被占用）→ 抓帧整个失败
    #   · 两个进程同时跑（比如我在旁边跑测试脚本）也会互相踩
    # 实测症状：`grab_png` 一直返回 None、监控窗口画面不更新。
    #
    # 用序号轮换 + 抓完就删，就没有冲突了。
    global _SHOT_SEQ
    _SHOT_SEQ = (_SHOT_SEQ + 1) % 4
    tmp = os.path.join(tempfile.gettempdir(), '_tvmon_shot_%d.png' % _SHOT_SEQ)

    try:
        # 用 cmd 重定向，避免 Python 文本管道做 CRLF 转换
        cmd = '"%s" -s %s exec-out screencap -p > "%s"' % (ADB, serial, tmp)
        subprocess.run(cmd, shell=True, capture_output=True, timeout=timeout)
        if not os.path.exists(tmp):
            return None
        raw = open(tmp, 'rb').read()
    except Exception:
        return None
    finally:
        # 抓完就删，别留垃圾；删不掉也不影响（下次用另一个名字）
        try:
            os.remove(tmp)
        except Exception:
            pass

    # 坑 1：剥掉 PNG 之前的前缀（实测是 `argc: 2 \n`）
    i = raw.find(b'\x89PNG')
    if i < 0:
        return None
    png = raw[i:]
    # 完整性校验：PNG 必须以 IEND 结束（宽松一点，避免误杀）
    if b'IEND' not in png[-64:]:
        return None
    return png


class Remote:
    """App 的 HTTP 遥控接口。"""

    def __init__(self, host, port=HTTP_PORT):
        self.base = 'http://%s:%d' % (host, port)

    def _get(self, path, timeout=6.0):
        try:
            req = urllib.request.Request(self.base + path)
            req.add_header('User-Agent', 'tv-monitor')
            with urllib.request.urlopen(req, timeout=timeout) as x:
                return x.read().decode('utf-8', 'replace')
        except Exception:
            return None

    def key(self, name):
        code = KEY.get(name)
        if code is None:
            return False
        return self._get('/api/remote/key?code=%d' % code) is not None

    def status(self):
        import json
        t = self._get('/api/remote/status')
        if not t:
            return {}
        try:
            return json.loads(t)
        except Exception:
            return {}


class MonitorApp:
    def __init__(self, root, serial, host):
        self.root = root
        self.serial = serial
        self.remote = Remote(host)
        self.shots = queue.Queue(maxsize=2)
        self.running = True
        self._photo = None
        self._last_status = 0
        # 刚按过键的时间戳 —— 按完立刻抓一帧，最影响"跟手感"
        self._last_key_at = 0.0
        # 实际帧率统计
        self._frame_times = []
        self._shot_now = threading.Event()

        root.title('焰火TV 监控 — %s' % serial)
        root.configure(bg='#0b0d18')
        root.geometry('1100x780')

        self.screen = tk.Label(root, bg='#000000', bd=0)
        self.screen.pack(fill=tk.BOTH, expand=True, padx=10, pady=(10, 6))

        self.status = tk.Label(root, text='启动中…', bg='#0b0d18', fg='#8b95b5',
                               font=('Microsoft YaHei UI', 10), anchor='w')
        self.status.pack(fill=tk.X, padx=14)

        pad = tk.Frame(root, bg='#0b0d18')
        pad.pack(fill=tk.X, padx=10, pady=(6, 12))
        rows = [
            [('回首页', 'home'), ('菜单', 'menu'), ('返回', 'back'), ('暂停/播放', 'play')],
            [('上一集', 'prev'), ('▲ 上', 'up'), ('下一集', 'next'), ('音量+', 'volup')],
            [('◀ 左', 'left'), ('确定', 'ok'), ('▶ 右', 'right'), ('音量-', 'voldown')],
            [('静音', 'mute'), ('▼ 下', 'down'), ('电源', 'power'), ('立即刷新', 'shot')],
        ]
        for r, row in enumerate(rows):
            for c, (label, name) in enumerate(row):
                tk.Button(
                    pad, text=label,
                    command=lambda n=name: self.on_button(n),
                    bg='#161b2e', fg='#eef2ff', activebackground='#5aa9ff',
                    activeforeground='#ffffff', relief='flat',
                    font=('Microsoft YaHei UI', 11), height=2, cursor='hand2',
                ).grid(row=r, column=c, sticky='nsew', padx=4, pady=4)
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

        threading.Thread(target=self.grab_loop, daemon=True).start()
        self.pump()

    # ---------- 截图线程 ----------
    def grab_loop(self):
        fails = 0
        while self.running:
            t0 = time.time()
            png = grab_png(self.serial)
            if png is None:
                fails += 1
                if fails == 3:
                    try:
                        self.shots.put_nowait(
                            ('err', '截屏失败 —— 电视的 ADB 调试可能断了（重新 adb connect）'))
                    except queue.Full:
                        pass
            else:
                fails = 0
                # 帧率统计（用最近 6 帧）
                self._frame_times.append(time.time())
                if len(self._frame_times) > 6:
                    self._frame_times.pop(0)
                try:
                    self.shots.put_nowait(('img', png))
                except queue.Full:
                    pass

            # 按过键 → 别等，立刻抓下一帧（这是"跟手"的关键）
            since_key = time.time() - self._last_key_at
            extra = 0.0
            if since_key * 1000 > FAST_AFTER_KEY_MS:
                extra = SHOT_INTERVAL_MS / 1000.0

            # 等 extra；期间如果用户按了键，_shot_now 会被置位，立刻醒
            self._shot_now.wait(timeout=extra)
            self._shot_now.clear()

    # ---------- 主线程刷新 ----------
    def pump(self):
        try:
            kind, payload = self.shots.get_nowait()
            if kind == 'img':
                self.show(payload)
            else:
                self.status.config(text=payload, fg='#ff7b7b')
        except queue.Empty:
            pass

        now = time.time() * 1000
        if now - self._last_status > STATUS_EVERY_MS:
            self._last_status = now
            st = self.remote.status()
            if st:
                fps = 0.0
                if len(self._frame_times) >= 2:
                    span = self._frame_times[-1] - self._frame_times[0]
                    if span > 0:
                        fps = (len(self._frame_times) - 1) / span
                self.status.config(
                    text='界面：%s    音量：%s/%s%s    %.1f 帧/秒    '
                         '（键盘方向键可直接遥控）' % (
                             st.get('screen', '?'), st.get('volume', '?'),
                             st.get('volumeMax', '?'),
                             '   静音中' if st.get('muted') else '', fps,
                         ), fg='#8b95b5')
            else:
                self.status.config(
                    text='遥控接口连不上 —— 电视上「设置 → 手机网页调试」要开着',
                    fg='#ff7b7b')

        if self.running:
            self.root.after(120, self.pump)

    def show(self, png):
        try:
            from PIL import Image, ImageTk
            img = Image.open(io.BytesIO(png))
            img.load()
            if img.width > SHOT_WIDTH:
                h = int(img.height * SHOT_WIDTH / img.width)
                img = img.resize((SHOT_WIDTH, h), Image.BILINEAR)
            self._photo = ImageTk.PhotoImage(img)
            self.screen.config(image=self._photo)
        except Exception as e:
            self.status.config(text='解码画面失败：%s' % str(e)[:60], fg='#ff7b7b')

    # ---------- 操作 ----------
    def send(self, name):
        # 记下按键时间：抓帧循环会据此取消等待、立刻抓一帧
        self._last_key_at = time.time()
        self._shot_now.set()

        def do():
            self.remote.key(name)
            # 再置一次：等电视把界面画完（约 250ms）后马上取新画面
            time.sleep(0.25)
            self._shot_now.set()
        threading.Thread(target=do, daemon=True).start()

    def on_button(self, name):
        if name == 'shot':
            def once():
                png = grab_png(self.serial)
                if png:
                    try:
                        self.shots.put_nowait(('img', png))
                    except queue.Full:
                        pass
            threading.Thread(target=once, daemon=True).start()
        else:
            self.send(name)

    def quit(self):
        self.running = False
        self.root.destroy()


def main():
    target = sys.argv[1] if len(sys.argv) > 1 else None
    serial = find_serial(target)
    if not serial:
        print('找不到网络 ADB 设备。')
        print('  1) 电视上「开发者选项 → 网络调试」打开')
        print('  2) 电脑上 adb connect <电视IP>:5555 并在电视上允许授权')
        print('  3) 再运行：python tv-monitor.py 192.168.31.233')
        return 1
    host = serial.split(':')[0]
    print('设备 %s' % serial)
    print('遥控接口 http://%s:%d' % (host, HTTP_PORT))
    root = tk.Tk()
    MonitorApp(root, serial, host)
    root.mainloop()
    return 0


if __name__ == '__main__':
    sys.exit(main())
