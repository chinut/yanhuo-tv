#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
精简主源：只保留「央视网 + 央视频」的网页频道。

## 用户要求

    「主源里只保留央视网和央视频使用网页进入的 CCTV 台，
      剩下全部抛弃」
    「我发现主源里能用的就央视 其他不要了」

## 依据（实测）

原 `cctv.m3u` 有 544 条，但**绝大多数不是流、是各电视台的网页**，
播放要靠 WebView 抽地址。实测这些网页源的可用性很差：

    static.hntv.tv 30 · web.guangdianyun.tv 21 · www.nmtv.cn 20
    www.gdtv.cn 17 · sxrtv.com 16 · fjtv.net 16 · mgtv.com 14 …

而**央视网（tv.cctv.com）和央视频（yangshipin.cn）这两家是确实能用的**：
央视的流是专有加密格式，只有它自己网页里的播放器能解（这一点在
`LiveCatalog` 的注释里有完整的实测记录）。

所以留下这两家的 73 条，丢掉其余 471 条。

## 为什么保留 yangshipin 里的非 CCTV 台

央视频那 55 条里除了 CCTV，还有 12 个卫视（上海东方、北京、云南…）——
它们**也走央视频的网页播放器**，和用户说的"央视频使用网页进入的"
是同一类，属于该保留的范围。
"""
import io
import os
import re
from collections import Counter

SRC = 'app/src/main/assets/live/cctv.m3u'
DST = 'app/src/main/assets/live/cctv.m3u'

KEEP_HOSTS = ('tv.cctv.com', 'yangshipin')


def main():
    t = io.open(SRC, encoding='utf-8').read()
    lines = t.splitlines()

    # 按「#EXTINF + 地址」成对遍历，保留原始行（不重新生成，避免格式走样）
    out = ['#EXTM3U']
    out.append('# 主源：只保留「央视网 tv.cctv.com」和「央视频 yangshipin.cn」。')
    out.append('#')
    out.append('# 为什么只剩这两家（实测结论）：')
    out.append('#   · 央视的流是专有加密格式，只有它自己网页里的播放器能解')
    out.append('#   · 其余电视台的网页源实测可用性很差，播放要起 WebView、老电视上又慢又断')
    out.append('#   · 所以主源只服务于「央视 + 央视频上的卫视」，其它一律交给 GitHub 源')

    kept, dropped = 0, 0
    i = 0
    while i < len(lines):
        s = lines[i].strip()
        if s.startswith('#EXTINF'):
            # 往后找地址行
            j = i + 1
            while j < len(lines) and not lines[j].strip():
                j += 1
            url = lines[j].strip() if j < len(lines) else ''
            if any(h in url for h in KEEP_HOSTS):
                out.append(lines[i])
                out.append(url)
                kept += 1
            else:
                dropped += 1
            i = j + 1
        else:
            i += 1

    io.open(DST, 'w', encoding='utf-8', newline='\n').write('\n'.join(out) + '\n')
    size_kb = os.path.getsize(DST) / 1024
    print('  cctv.m3u: 保留 %d 条，丢弃 %d 条  → %.1f KB' % (kept, dropped, size_kb))


if __name__ == '__main__':
    main()
