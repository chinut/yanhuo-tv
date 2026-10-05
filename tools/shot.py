#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
远程截图（可靠版）

## 踩过的两个坑

### 1. Android 11 上必须带 `-d 0`

    adb shell screencap -p /sdcard/x.png          → 8159 字节，**全空**
    adb shell screencap -p -d 0 /sdcard/x.png     → 正常画面

第一个参数查了半天才定位到 —— 不加 `-d 0` 返回的是一个空缓冲区，
而且**不报错**，文件大小看着也"像"一张图（1920x1080 的纯黑 PNG 只有 8KB）。

### 2. 偶尔仍会拿到空帧

即使带 `-d 0`，偶尔（约 1/5）仍会拿到空白帧 —— 大概是和渲染抢帧。
所以这里**重试直到标准差 > 阈值**，而不是拿到文件就当成功。

### 3. `am start` 不会重启已在前台的 App

    Warning: Activity not started, intent has been delivered to
             currently running top-most instance.

这时如果 App 其实在后台，截图拿到的是别的界面。所以脚本会先确认前台。

## 用法

    python shot.py 192.168.31.101 out.png
    python shot.py 192.168.31.101 out.png --retries 6
"""
from __future__ import annotations

import argparse
import os
import subprocess
import sys
import tempfile
import time

ADB = os.environ.get('ADB', r'C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe')
REMOTE = '/sdcard/_shot_tmp.png'


def run(args, timeout=90):
    return subprocess.run(args, capture_output=True, timeout=timeout)


def variance_of(png_path: str) -> float:
    """返回像素标准差；读不了返回 -1。"""
    try:
        from PIL import Image
        import numpy as np
        im = Image.open(png_path).convert('RGB')
        return float(np.array(im).std())
    except Exception:
        return -1.0


def front_activity(serial: str) -> str:
    r = run([ADB, '-s', serial, 'shell', 'dumpsys', 'activity', 'activities'])
    for line in r.stdout.decode('utf-8', 'replace').splitlines():
        if 'ResumedActivity' in line:
            return line.strip()
    return ''


def ensure_app(serial: str, pkg: str, timeout: float = 50.0) -> bool:
    """确保 App 在前台；不在就启动并等它起来。"""
    if pkg in front_activity(serial):
        return True
    run([ADB, '-s', serial, 'shell', 'am',
         'start', '-n', '%s/%s.MainActivity' % (pkg, pkg)])
    t0 = time.time()
    while time.time() - t0 < timeout:
        time.sleep(2)
        if pkg in front_activity(serial):
            return True
    return False


def capture(serial: str, out: str, retries: int = 5, min_std: float = 3.0,
            settle: float = 1.2) -> bool:
    """
    截图，重试直到拿到有内容的帧。

    ⚠️ 关键是 `-d 0`（见文件头注释）。
    """
    for i in range(1, retries + 1):
        run([ADB, '-s', serial, 'shell', 'rm', '-f', REMOTE])
        # -d 0 是**必须**的
        run([ADB, '-s', serial, 'shell', 'screencap', '-p', '-d', '0', REMOTE])
        time.sleep(settle)
        tmp = out + '.tmp'
        run([ADB, '-s', serial, 'pull', REMOTE, tmp])
        if not os.path.exists(tmp):
            print('  第 %d 次：拉取失败' % i)
            continue
        std = variance_of(tmp)
        size = os.path.getsize(tmp)
        if std > min_std:
            os.replace(tmp, out)
            print('  ✅ 第 %d 次成功  标准差=%.1f  %d 字节' % (i, std, size))
            return True
        print('  第 %d 次：空帧（标准差 %.1f，%d 字节），重试…' % (i, std, size))
        os.remove(tmp)
        time.sleep(1.5)
    print('  ❌ %d 次都是空帧' % retries)
    return False


def main():
    ap = argparse.ArgumentParser(description='远程截图（自动重试直到拿到真画面）')
    ap.add_argument('host', help='电视 IP（可带 :端口）')
    ap.add_argument('out', help='输出 png')
    ap.add_argument('--retries', type=int, default=5)
    ap.add_argument('--pkg', default='com.chinut.bawantv')
    ap.add_argument('--no-ensure', action='store_true', help='不自动拉起 App')
    a = ap.parse_args()

    serial = a.host if ':' in a.host else a.host + ':5555'
    if not a.no_ensure:
        if ensure_app(serial, a.pkg):
            print('  App 在前台')
        else:
            print('  ⚠️ App 没起来，仍尝试截图')

    ok = capture(serial, a.out, a.retries)
    sys.exit(0 if ok else 1)


if __name__ == '__main__':
    main()
