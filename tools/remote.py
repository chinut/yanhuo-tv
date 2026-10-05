#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
焰火TV 远程控制台（命令行）

App 内置了一个调试 Web 服务（默认 8899 端口），电脑/手机连同一个 WiFi 就能遥控它。
浏览器打开 `http://<电视IP>:8899` 是图形界面；这个脚本是**命令行版**，
方便脚本化、批量操作、以及在没有浏览器的环境里用。

## 用法

    python remote.py 192.168.31.101 status          # 看状态
    python remote.py 192.168.31.101 diag            # 播放诊断
    python remote.py 192.168.31.101 key down        # 按一下「下」
    python remote.py 192.168.31.101 key ok          # 按「确定」
    python remote.py 192.168.31.101 vol 20          # 设音量
    python remote.py 192.168.31.101 seq up ok down  # 连按一串
    python remote.py 192.168.31.101 watch           # 持续打印状态变化

## ⚠️ 踩过的坑

1. **音量参数是 `value=` 不是 `v=`**。写错**不报错**，只是静默不生效
   （接口总是返回 200 和当前状态）。

2. **`/api/remote/mute` 不带参数会「静音」**，不是「切换」：
       val want = (p["muted"] ?: q["muted"])?.let{...} ?: true
   省略 muted 就等于 `true`。要取消静音必须显式传 `muted=0`。

3. **键码用 Android 的标准 keycode**：19=上 20=下 21=左 22=右 23=确定
   4=返回 82=三横菜单 3=主页 24/25=音量±

4. **发键之后要等一下**再查状态 —— App 处理是异步的，立刻查会看到旧值。
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request

KEYS = {
    'up': 19, 'down': 20, 'left': 21, 'right': 22, 'ok': 23, 'enter': 23,
    'back': 4, 'menu': 82, 'home': 3,
    'volup': 24, 'voldown': 25,
    'play': 126, 'pause': 127, 'playpause': 85,
    '0': 7, '1': 8, '2': 9, '3': 10, '4': 11,
    '5': 12, '6': 13, '7': 14, '8': 15, '9': 16,
}


def http(base: str, path: str, timeout: float = 12.0):
    try:
        with urllib.request.urlopen(base + path, timeout=timeout) as x:
            return x.status, x.read().decode('utf-8', 'replace')
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', 'replace')[:200]
    except Exception as e:
        return None, '%s: %s' % (type(e).__name__, e)


def fmt_status(b: str) -> str:
    try:
        d = json.loads(b)
    except Exception:
        return b
    return ('屏幕=%-6s 音量=%s/%s%s' % (
        d.get('screen'), d.get('volume'), d.get('volumeMax'),
        '  🔇静音' if d.get('muted') else ''))


def main():
    ap = argparse.ArgumentParser(description='焰火TV 远程控制台')
    ap.add_argument('host', help='电视 IP，如 192.168.31.101')
    ap.add_argument('cmd', help='status / diag / key / seq / vol / mute / watch / screens')
    ap.add_argument('args', nargs='*')
    ap.add_argument('--port', type=int, default=8899)
    a = ap.parse_args()
    base = 'http://%s:%d' % (a.host, a.port)

    # 先探活，避免后面每条都超时
    st, b = http(base, '/api/remote/ping', timeout=6)
    if st != 200:
        print('❌ 连不上 %s' % base)
        print('   检查：① 电视和电脑在同一个 WiFi ② App 在前台运行 '
              '③ 设置里「手机调试」开着')
        sys.exit(1)

    if a.cmd == 'status':
        st, b = http(base, '/api/remote/status')
        print(fmt_status(b))

    elif a.cmd == 'diag':
        st, b = http(base, '/api/diag')
        print(b)

    elif a.cmd == 'state':
        st, b = http(base, '/api/state')
        try:
            d = json.loads(b)
            for k in sorted(d):
                print('  %-22s %s' % (k, d[k]))
        except Exception:
            print(b)

    elif a.cmd == 'key':
        names = a.args or ['ok']
        for n in names:
            code = KEYS.get(n.lower(), None)
            if code is None:
                if n.isdigit():
                    code = int(n)
                else:
                    print('  未知按键: %s（可用: %s）' % (n, ' '.join(sorted(KEYS))))
                    continue
            st, b = http(base, '/api/remote/key?code=%d' % code)
            print('  %-10s code=%-4d → %s' % (n, code, b[:100]))
            time.sleep(0.6)

    elif a.cmd == 'seq':
        for n in a.args:
            code = KEYS.get(n.lower())
            if code is None and n.isdigit():
                code = int(n)
            if code is None:
                print('  跳过未知按键:', n)
                continue
            http(base, '/api/remote/key?code=%d' % code)
            print('  → %s' % n)
            time.sleep(0.8)
        time.sleep(1.5)
        st, b = http(base, '/api/remote/status')
        print('  之后:', fmt_status(b))

    elif a.cmd == 'vol':
        if not a.args:
            st, b = http(base, '/api/remote/status')
            print(fmt_status(b)); return
        v = int(a.args[0])
        # ⚠️ 参数名是 value，不是 v
        st, b = http(base, '/api/remote/volume?value=%d' % v)
        time.sleep(1.0)
        st, b = http(base, '/api/remote/status')
        print('  →', fmt_status(b))

    elif a.cmd == 'mute':
        # ⚠️ 必须显式传 muted=0/1，省略会被当成 true（静音）
        want = a.args[0] if a.args else '1'
        norm = '1' if want in ('1', 'true', 'on') else '0'
        st, b = http(base, '/api/remote/mute?muted=%s' % norm)
        time.sleep(1.0)
        st, b = http(base, '/api/remote/status')
        print('  →', fmt_status(b))

    elif a.cmd == 'watch':
        last = None
        print('监听中（Ctrl+C 退出）…')
        while True:
            st, b = http(base, '/api/remote/status', timeout=6)
            if b != last:
                print('  %s  %s' % (time.strftime('%H:%M:%S'), fmt_status(b)))
                last = b
            time.sleep(1.0)

    else:
        print('未知命令:', a.cmd)
        print(__doc__)


if __name__ == '__main__':
    main()
