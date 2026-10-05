#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
实测各 CCTV 频道**每个候选源**的音频编码。

## 为什么做这个

用户反馈"没声音"。已确认根因：**这台电视没有 MP2 解码器**
（`findDecoderForFormat("audio/mpeg-L2")` → 无解码器），
所以音频是 MP2 的流一律静音。

用户选择了方案 A：「选源时优先 AAC」。但**这个方案成立的前提是
"确实存在 AAC 的备选源"** —— 如果所有源都是 MP2，做了也白做。

所以先用数据回答：**各台的源里，AAC / MP2 / 其它 各占多少。**

## 做法

1. 拉 `best-fan/iptv-sources` 的 `cn_cctv_status.m3u8`（App 运行时用的就是这个）
2. 按频道归并多个源
3. 每个源下载**第一个分片**的前几百 KB
4. 用 ffmpeg 读出音频编码
"""
import io
import os
import re
import subprocess
import sys
import urllib.parse
import urllib.request
from collections import defaultdict

import imageio_ffmpeg

UA = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
      '(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36')
BASE = 'https://raw.githubusercontent.com/best-fan/iptv-sources/main/'
EXE = imageio_ffmpeg.get_ffmpeg_exe()
TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), '_probe.ts')


def fetch(url: str, limit: int = 0, timeout: int = 20) -> bytes:
    r = urllib.request.Request(url)
    r.add_header('User-Agent', UA)
    with urllib.request.urlopen(r, timeout=timeout) as x:
        return x.read(limit) if limit else x.read()


def first_segment(stream_url: str) -> str | None:
    """从 m3u8 里取第一个分片的绝对地址。"""
    try:
        txt = fetch(stream_url, limit=40000).decode('utf-8', 'replace')
    except Exception:
        return None
    if '#EXT-X-STREAM-INF' in txt:
        lines = [l.strip() for l in txt.splitlines()]
        for i, l in enumerate(lines):
            if l.startswith('#EXT-X-STREAM-INF') and i + 1 < len(lines):
                stream_url = urllib.parse.urljoin(stream_url, lines[i + 1])
                break
        try:
            txt = fetch(stream_url, limit=40000).decode('utf-8', 'replace')
        except Exception:
            return None
    segs = [l.strip() for l in txt.splitlines()
            if l.strip() and not l.startswith('#')]
    return urllib.parse.urljoin(stream_url, segs[0]) if segs else None


def audio_codec(stream_url: str) -> str:
    """返回这个流的音频编码名（aac / mp2 / …），取不到返回 '?'。"""
    seg = first_segment(stream_url)
    if not seg:
        return '无分片'
    try:
        data = fetch(seg, limit=400_000, timeout=25)
    except Exception as e:
        return '取流失败'
    io.open(TMP, 'wb').write(data)
    r = subprocess.run([EXE, '-i', TMP], capture_output=True, timeout=90)
    err = (r.stderr or b'').decode('utf-8', 'replace')
    for line in err.split('\n'):
        if 'Audio:' not in line:
            continue
        m = re.search(r'Audio:\s*([A-Za-z0-9_]+)', line)
        return m.group(1).lower() if m else '?'
    return '无音频轨'


def parse_status(text: str) -> dict:
    """
    解析 `_status` 格式，按归一化频道名归并多源。

    ⚠️ 归一化必须**大小写不敏感**去标记：源文件里同时有 `CCTV1`
    和 `CCTV1[S]`，我第一版漏了大小写，结果把它们算成两个台。
    """
    byname = defaultdict(list)
    cur = None
    for line in text.splitlines():
        s = line.strip()
        if s.startswith('#EXTINF'):
            m = re.search(r'tvg-name="([^"]+)"', s)
            disp = (m.group(1) if m else s.rsplit(',', 1)[-1]).strip()
            n = re.sub(r'\[\d{3,4}\]|\[[A-Za-z]\]', '', disp)          # [1080] [S]
            n = re.sub(r'\(\s*\d{3,4}[pi]?\s*\)', '', n)                # (720p)
            n = re.sub(r'^(CCTV|CGTN)\s*-\s*', r'\1', n, flags=re.I)    # CCTV-1 → CCTV1
            cur = re.sub(r'\s+', ' ', n).strip()
        elif s.startswith('http') and cur:
            byname[cur].append(s)
            cur = None
    return byname


def main():
    print('拉取 cn_cctv_status.m3u8 …')
    text = fetch(BASE + 'cn_cctv_status.m3u8').decode('utf-8', 'replace')
    byname = parse_status(text)
    cctv = {k: v for k, v in byname.items() if re.match(r'^CCTV\s*\d', k, re.I)}
    print('CCTV 台数: %d（共 %d 个源）\n' % (
        len(cctv), sum(len(v) for v in cctv.values())))

    summary = defaultdict(int)
    rows = []
    for name in sorted(cctv, key=lambda x: (len(x), x)):
        codes = []
        for u in cctv[name]:
            c = audio_codec(u)
            codes.append(c)
            summary[c] += 1
        rows.append((name, codes))
        print('  %-14s %s' % (name, '  '.join(codes)))

    print('\n=== 音频编码汇总（所有源）===')
    for k, v in sorted(summary.items(), key=lambda x: -x[1]):
        print('  %-10s %d 个源' % (k, v))
    print()
    print('=== 各台是否有 AAC 可用 ===')
    good = 0
    for name, codes in rows:
        if any('aac' in c for c in codes):
            good += 1
    print('  %d / %d 台至少有一个 AAC 源' % (good, len(rows)))
    try:
        os.remove(TMP)
    except OSError:
        pass


if __name__ == '__main__':
    main()
