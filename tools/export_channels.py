#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
导出直播频道清单（含实测健康检查），供人工优化。

## 为什么要做这个

内置的 `cctv.m3u` 有 536 个条目，但里面混着三类东西：

    1. CCTV 自有 CDN 直连   https://piccpndali.v.myalicdn.com/…m3u8
    2. 网页播放页（不是流） https://tv.cctv.com/live/cctv1/
    3. 央视频网页           https://www.yangshipin.cn/tv/home?pid=…

第 2、3 类**不是流地址** —— App 要把网页塞进 WebView 再抽真实地址，
在老电视上慢且容易失败。用户说的"主源里特别多 CCTV"，多半就是这些。

所以导出时：
    · 按**流类型**分类（直连 m3u8 / flv / mp4 / 网页 / 未知）
    · 每条流**实测一次**（拉 master.m3u8，看返回码、能否解析出分片）
    · 输出 CSV + Markdown，可读、可排序、可直接拿去改 m3u

## 用法

    python export_channels.py                       # 导出内置源 + 健康检查
    python export_channels.py --no-probe            # 只导出不测（快）
    python export_channels.py --in 某个.m3u --out 结果目录
"""
from __future__ import annotations

import argparse
import csv
import io
import os
import re
import socket
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter, OrderedDict
from concurrent.futures import ThreadPoolExecutor

UA = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
      '(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36')

HERE = os.path.dirname(os.path.abspath(__file__))
# tools/ → 项目根 → app/src/main/assets/live
ASSETS = os.path.normpath(
    os.path.join(HERE, '..', 'app', 'src', 'main', 'assets', 'live'))


# ============================ M3U 解析 ============================

def parse_m3u(path: str) -> list[dict]:
    """
    解析 m3u。支持 `#EXTINF:-1 group-title="X",名称` 这种形式。

    ⚠️ 文件是 **UTF-8**，必须显式指定编码 —— 用系统默认（GBK）会乱码。
    """
    out = []
    lines = io.open(path, encoding='utf-8', errors='replace').read().splitlines()
    pending = None
    for ln in lines:
        s = ln.strip()
        if not s:
            continue
        if s.startswith('#EXTINF'):
            group = ''
            m = re.search(r'group-title="([^"]*)"', s)
            if m:
                group = m.group(1)
            # 名称在最后一个逗号之后
            name = s.rsplit(',', 1)[-1].strip()
            pending = {'group': group, 'name': name}
        elif s.startswith('#'):
            continue
        else:
            if pending is not None:
                pending['url'] = s
                out.append(pending)
                pending = None
            else:
                out.append({'group': '', 'name': '', 'url': s})
    return out


def stream_kind(url: str) -> str:
    """判断这是不是**真的流地址**。"""
    u = url.lower()
    if u.endswith('.m3u8') or '.m3u8?' in u:
        return 'm3u8'
    if u.endswith('.flv') or '.flv?' in u:
        return 'flv'
    if u.endswith('.mp4') or '.mp4?' in u:
        return 'mp4'
    if u.endswith('.ts') or '.ts?' in u:
        return 'ts'
    if '/live/' in u and ('cctv.com' in u or 'yangshipin' in u):
        return '网页(央视)'
    if 'yangshipin' in u or 'cctv.com' in u:
        return '网页'
    if u.startswith('http'):
        return '未知'
    return '无效'


def host_of(url: str) -> str:
    try:
        return urllib.parse.urlparse(url).hostname or ''
    except Exception:
        return ''


# ============================ 健康检查 ============================

def probe(url: str, timeout: float = 8.0) -> dict:
    """
    实测一条流。

    对 m3u8 会**再拉一层**看有没有分片 —— 只返回 200 不够，
    有的地址返回 200 但内容是错误页。

    ⚠️ 用 `read()` 而不是 `read(4096)`：某些响应体在 4096 字节处
    切断了多字节 UTF-8 字符，`decode` 会抛 UnicodeEncodeError 把
    一条本来正常的流判成失败（实测 44 条被误判）。
    另外把"是 HTML 页面"单独识别出来 —— 这是这个项目最想知道的分类。
    """
    r = {'http': None, 'ok': False, 'note': '', 'segments': 0, 'variants': 0}
    if not url.startswith('http'):
        r['note'] = '非 http'
        return r
    try:
        req = urllib.request.Request(url)
        req.add_header('User-Agent', UA)
        req.add_header('Accept', '*/*')
        # 央视自有 CDN 需要 Referer
        h = host_of(url)
        if 'myalicdn' in h or 'cctv' in h:
            req.add_header('Referer', 'https://tv.cctv.com/')
        with urllib.request.urlopen(req, timeout=timeout) as x:
            r['http'] = x.status
            raw = x.read()
        head = raw.decode('utf-8', 'replace')[:4096]
        low = head.lower()

        if '#EXTM3U' in head:
            r['ok'] = True
            r['variants'] = head.count('#EXT-X-STREAM-INF')
            r['segments'] = head.count('#EXTINF')
            if r['variants']:
                r['note'] = 'master，%d 档' % r['variants']
            elif r['segments']:
                r['note'] = 'media，%d 分片' % r['segments']
            else:
                r['ok'] = False
                r['note'] = '播放列表为空'
        elif 'flv' in low[:64] and raw[1:4] == b'FLV':
            r['ok'] = True
            r['note'] = 'FLV 流'
        elif low.lstrip()[:9] in ('<!doctype', '<html', '<?xml'):
            # 网页 —— 不是流。这是最关键的一类：
            # App 必须用 WebView 打开再抽真实地址，老电视上慢且易断。
            r['ok'] = False
            r['kind_hint'] = '网页'
            t = re.search(r'<title[^>]*>([^<]{0,60})', head, re.I)
            r['note'] = 'HTML 页面' + ('：%s' % t.group(1).strip() if t else '')
        else:
            r['note'] = '非流内容: ' + head[:36].replace('\n', ' ').strip()
    except urllib.error.HTTPError as e:
        r['http'] = e.code
        r['note'] = 'HTTP %s' % e.code
    except socket.timeout:
        r['note'] = '超时'
    except Exception as e:
        r['note'] = type(e).__name__
    return r


def probe_all(items: list[dict], workers: int = 24) -> None:
    """就地给每条补上健康字段。"""
    todo = [it for it in items if it.get('kind') in ('m3u8', 'flv', 'mp4', 'ts', '未知')]
    print('  实测 %d 条（并发 %d）…' % (len(todo), workers))
    done = [0]

    def work(it):
        it['probe'] = probe(it['url'])
        done[0] += 1
        if done[0] % 40 == 0:
            print('    %d/%d' % (done[0], len(todo)))

    with ThreadPoolExecutor(max_workers=workers) as ex:
        list(ex.map(work, todo))
    for it in items:
        it.setdefault('probe', {'ok': False, 'note': '未测（网页类）'})
        # 实测发现是 HTML 页面的，**改判类型** ——
        # 这比按 URL 猜准得多（很多页面地址长得像流地址）
        p = it.get('probe') or {}
        if p.get('kind_hint') == '网页':
            it['kind'] = it.get('kind_declared') or '网页(实测)'
            p['note'] = p.get('note', '')


# ============================ 输出 ============================

def write_csv(items, path):
    cols = ['name', 'group', 'kind', 'host', 'http', 'ok', 'note',
            'variants', 'segments', 'url']
    with io.open(path, 'w', encoding='utf-8-sig', newline='') as f:
        w = csv.writer(f)
        w.writerow(cols)
        for it in items:
            p = it.get('probe') or {}
            w.writerow([
                it['name'], it.get('group', ''), it.get('kind', ''),
                host_of(it['url']), p.get('http', ''), '是' if p.get('ok') else '否',
                p.get('note', ''), p.get('variants', ''), p.get('segments', ''),
                it['url'],
            ])


def write_md(items, path, title):
    """按类型分组写一份人看的报告。"""
    kinds = OrderedDict()
    for it in items:
        kinds.setdefault(it.get('kind', '未知'), []).append(it)

    L = []
    L.append('# %s' % title)
    L.append('')
    L.append('生成时间：%s' % time.strftime('%Y-%m-%d %H:%M:%S'))
    L.append('')
    L.append('## 总览')
    L.append('')
    L.append('| 类型 | 条数 | 实测可用 | 说明 |')
    L.append('|---|---|---|---|')
    desc = {
        'm3u8': 'HLS 直播流，App 可直连',
        'flv': 'FLV 流，可直连',
        'mp4': 'MP4，可直连',
        'ts': 'TS 分片',
        '网页(央视)': '**网页地址，不是流** —— 要 WebView 抽真实地址，老电视慢',
        '网页': '**网页地址，不是流**',
        '未知': '无法判断类型',
        '无效': '不是 http 地址',
    }
    for k, v in sorted(kinds.items(), key=lambda x: -len(x[1])):
        ok = sum(1 for x in v if (x.get('probe') or {}).get('ok'))
        L.append('| %s | %d | %d | %s |' % (k, len(v), ok, desc.get(k, '')))
    L.append('')

    for k, v in sorted(kinds.items(), key=lambda x: -len(x[1])):
        L.append('## %s（%d 条）' % (k, len(v)))
        L.append('')
        L.append('| 频道 | 主机 | HTTP | 可用 | 说明 |')
        L.append('|---|---|---|---|---|')
        for it in v:
            p = it.get('probe') or {}
            L.append('| %s | %s | %s | %s | %s |' % (
                it['name'] or '(无名)', host_of(it['url']) or '-',
                p.get('http') or '-', '✅' if p.get('ok') else '❌',
                p.get('note', '')))
        L.append('')

    io.open(path, 'w', encoding='utf-8').write('\n'.join(L))


# ============================ 主流程 ============================

def main():
    ap = argparse.ArgumentParser(description='导出直播频道清单 + 健康检查')
    ap.add_argument('--in', dest='inputs', nargs='*',
                    help='m3u 文件（默认：内置 cctv.m3u + iptv.m3u）')
    ap.add_argument('--out', default=os.path.join(HERE, 'out'))
    ap.add_argument('--no-probe', action='store_true', help='不做健康检查')
    a = ap.parse_args()

    os.makedirs(a.out, exist_ok=True)

    files = a.inputs or [
        os.path.join(ASSETS, 'cctv.m3u'),
        os.path.join(ASSETS, 'iptv.m3u'),
    ]

    grand = []
    for f in files:
        if not os.path.exists(f):
            print('  ! 不存在:', f)
            continue
        items = parse_m3u(f)
        for it in items:
            it['kind'] = stream_kind(it['url'])
            it['src'] = os.path.basename(f)
        print('%s：%d 条' % (os.path.basename(f), len(items)))

        if not a.no_probe:
            probe_all(items)

        base = os.path.splitext(os.path.basename(f))[0]
        write_csv(items, os.path.join(a.out, base + '.csv'))
        write_md(items, os.path.join(a.out, base + '.md'),
                 '频道清单 · %s' % os.path.basename(f))

        # 控制台摘要
        c = Counter(x['kind'] for x in items)
        print('   类型分布:', dict(c))
        if not a.no_probe:
            net = [x for x in items if x['kind'] in ('m3u8', 'flv', 'mp4', 'ts', '未知')]
            ok = sum(1 for x in net if x['probe']['ok'])
            print('   实测：%d/%d 可用（%.0f%%）' % (
                ok, len(net), 100.0 * ok / len(net) if net else 0))
        print()

        grand += items

    # 合并全量
    write_csv(grand, os.path.join(a.out, 'ALL.csv'))
    write_md(grand, os.path.join(a.out, 'ALL.md'), '全部频道清单（合并）')
    print('  输出目录:', a.out)


if __name__ == '__main__':
    main()
