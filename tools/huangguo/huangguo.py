#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
黄果剧场（huangguo.video）抓取工具
=================================

给网站项目用的**可用工具包** —— 抓列表、详情、分集、播放地址，输出 JSON / CSV。

## 站点结构（实测，2026-10）

    列表页   https://huangguo.video/videos?page=N
               每页约 20 部剧，链接形如 /series/{slug}
               slug 是**短字母数字**（如 czv81qsm），不是数字 ID

    详情页   https://huangguo.video/series/{slug}
               <h1> 是剧名
               <meta name="description"> 里有简介、出品方、标签
               分集链接形如 /video/{slug}

    播放页   https://huangguo.video/video/{slug}
               播放器元素上直接带地址（**这是关键**）：
                   data-content-id="1164"
                   data-hls="/uploads/content/video/1164/master.m3u8"
                   data-poster="/uploads/content/series/217/cover.webp"

    播放流   {base}{data-hls}
               标准 HLS，master 里含 3 档：
                   480p  270x480    BANDWIDTH 417606
                   720p  404x720    BANDWIDTH 648003
                   1080p 608x1080   BANDWIDTH 1085123
               子播放列表带签名（`?n=<时间戳>.<hash>`）—— **有时效，别长期缓存**

## 用法

    python huangguo.py list  --pages 5              # 抓列表（前 5 页）
    python huangguo.py series czv81qsm              # 抓一部剧（含全部分集）
    python huangguo.py export --pages 20 --out data # 批量导出 JSON + CSV
    python huangguo.py check                        # 自检：确认站点结构没变

## 输出结构

    data/
      series.json      全部剧（含分集和播放地址）
      series.csv       同上，表格版（每行一集）
      index.json       索引摘要（抓取时间、数量、来源）

## 注意

· **播放地址有时效**：子播放列表带签名，建议每次播放前重新取详情
· **请控制频率**：默认每次请求间隔 0.8 秒，别把人家站点打挂
· **页面可能改版**：`check` 子命令用来快速确认选择器还有效
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = 'https://huangguo.video'
UA = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
      '(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36')

DELAY = 0.8          # 每次请求间隔（秒）
TIMEOUT = 30


# ============================ 抓取 ============================

def fetch(path: str, retries: int = 3) -> str | None:
    """取一个页面。path 可以是相对路径或完整 URL。"""
    url = path if path.startswith('http') else BASE + path
    for i in range(retries):
        try:
            req = urllib.request.Request(url)
            req.add_header('User-Agent', UA)
            req.add_header('Referer', BASE + '/')
            req.add_header('Accept-Language', 'zh-CN,zh;q=0.9')
            with urllib.request.urlopen(req, timeout=TIMEOUT) as x:
                return x.read().decode('utf-8', 'replace')
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            if i == retries - 1:
                print('  ! %s → HTTP %s' % (url, e.code), file=sys.stderr)
                return None
        except Exception as e:
            if i == retries - 1:
                print('  ! %s → %s' % (url, e), file=sys.stderr)
                return None
        time.sleep(1.5 * (i + 1))
    return None


def text_of(html: str, *pats: str, default: str = '') -> str:
    for p in pats:
        m = re.search(p, html, re.S)
        if m:
            v = re.sub(r'\s+', ' ', m.group(1)).strip()
            if v:
                return v
    return default


# ============================ 解析 ============================

def parse_list(html: str) -> list[dict]:
    """
    列表页 → 剧集条目。

    同时收 `/series/{slug}` 和 `/video/{slug}` 两种链接 ——
    实测列表页两种都有（series 是剧、video 是单集入口），
    但**只有 series 能拿到整部剧**，所以以 series 为主。
    """
    out = []
    seen = set()
    for slug in re.findall(r'href="/series/([a-z0-9]+)"', html):
        if slug in seen:
            continue
        seen.add(slug)
        out.append({'slug': slug, 'url': '%s/series/%s' % (BASE, slug)})
    return out


def parse_series(html: str, slug: str) -> dict:
    """详情页 → 剧信息 + 分集链接。"""
    title = text_of(html, r'<h1[^>]*>([^<]{1,120})</h1>',
                    r'property="og:title"\s+content="([^"]+)"',
                    r'<title>([^<]*)</title>')
    title = re.sub(r'\s*[·|]\s*黄果剧场\s*$', '', title).strip()

    desc = text_of(html, r'<meta name="description" content="([^"]{0,500})"')

    # 标签：简介里通常写「标签A、标签B。」
    tags = []
    m = re.search(r'标签([^。]{1,80})', desc)
    if m:
        tags = [t.strip() for t in re.split(r'[、,，/]', m.group(1)) if t.strip()]

    # 封面：优先 og:image，其次页面里的 cover
    cover = text_of(html, r'property="og:image"\s+content="([^"]+)"',
                    r'(https?://[^"\']*?/cover\.(?:webp|jpg|jpeg|png)[^"\']*)')
    if cover.startswith('/'):
        cover = BASE + cover

    # 分集：/video/{slug}
    eps = []
    seen = set()
    for vs in re.findall(r'href="/video/([a-z0-9]+)"', html):
        if vs in seen:
            continue
        seen.add(vs)
        eps.append({'slug': vs})

    # 集数文字（有的页面写「全72集」）
    total = None
    m = re.search(r'全\s*(\d+)\s*集', html)
    if m:
        total = int(m.group(1))

    return {
        'slug': slug,
        'url': '%s/series/%s' % (BASE, slug),
        'title': title,
        'cover': cover,
        'description': desc,
        'tags': tags,
        'episode_count_listed': len(eps),
        'episode_count_declared': total,
        'episodes': eps,
    }


def parse_video(html: str, slug: str) -> dict:
    """
    播放页 → 播放地址。

    **关键**：地址在播放器元素的 data-* 属性里，不用解析 JS：
        data-content-id="1164"
        data-hls="/uploads/content/video/1164/master.m3u8"
    """
    def attr(name):
        m = re.search(r'data-%s="([^"]*)"' % name, html)
        return m.group(1) if m else ''

    hls = attr('hls')
    if hls and hls.startswith('/'):
        hls = BASE + hls

    poster = attr('poster')
    if poster and poster.startswith('/'):
        poster = BASE + poster

    title = text_of(html, r'<h1[^>]*>([^<]{1,120})</h1>', r'<title>([^<]*)</title>')
    title = re.sub(r'\s*[·|]\s*黄果剧场\s*$', '', title).strip()

    # 集号：标题里通常带「第N集」
    epno = None
    m = re.search(r'第\s*(\d+)\s*集', title)
    if m:
        epno = int(m.group(1))

    # 所属剧（播放页一般有回链）
    series_slug = ''
    m = re.search(r'href="/series/([a-z0-9]+)"', html)
    if m:
        series_slug = m.group(1)

    return {
        'slug': slug,
        'url': '%s/video/%s' % (BASE, slug),
        'title': title,
        'episode_no': epno,
        'content_id': attr('content-id'),
        'hls': hls,
        'poster': poster,
        'series_slug': series_slug,
    }


def parse_master(m3u8_text: str, master_url: str) -> list[dict]:
    """
    解析 master.m3u8 的码率档位。

    ⚠️ 子播放列表的地址是**相对**的，要按 master 的目录拼回去。
    """
    out = []
    lines = [l.strip() for l in m3u8_text.splitlines() if l.strip()]
    for i, l in enumerate(lines):
        if not l.startswith('#EXT-X-STREAM-INF'):
            continue
        info = {}
        for k, v in re.findall(r'([A-Z\-]+)=("[^"]*"|[^,]*)', l):
            info[k] = v.strip('"')
        nxt = lines[i + 1] if i + 1 < len(lines) else ''
        if nxt and not nxt.startswith('#'):
            out.append({
                'name': info.get('NAME', ''),
                'resolution': info.get('RESOLUTION', ''),
                'bandwidth': int(info.get('BANDWIDTH') or 0),
                'url': urllib.parse.urljoin(master_url, nxt),
            })
    return out


# ============================ 命令 ============================

def cmd_list(a):
    total = 0
    for page in range(1, a.pages + 1):
        html = fetch('/videos?page=%d' % page)
        if not html:
            print('  第 %d 页取不到，停止' % page)
            break
        items = parse_list(html)
        total += len(items)
        print('  第 %-3d 页  %d 部   例: %s' % (
            page, len(items), ', '.join(x['slug'] for x in items[:3])))
        time.sleep(DELAY)
    print('  共 %d 条' % total)


def cmd_series(a):
    html = fetch('/series/%s' % a.slug)
    if not html:
        print('  取不到'); return
    s = parse_series(html, a.slug)
    print(json.dumps(s, ensure_ascii=False, indent=2)[:2000])
    if a.episodes and s['episodes']:
        print('\n  抓前 %d 集播放地址…' % min(a.episodes, len(s['episodes'])))
        for e in s['episodes'][:a.episodes]:
            vh = fetch('/video/%s' % e['slug'])
            if vh:
                e.update(parse_video(vh, e['slug']))
            time.sleep(DELAY)
        print(json.dumps(s['episodes'], ensure_ascii=False, indent=2)[:3000])


def scrape_series(slug: str, with_episodes: bool, max_eps: int = 0,
                  verbose: bool = True) -> dict:
    html = fetch('/series/%s' % slug)
    if not html:
        return {'slug': slug, 'error': 'series page failed'}
    s = parse_series(html, slug)
    if verbose:
        print('    %s  (%d 集)' % (s['title'] or slug, s['episode_count_listed']))
    time.sleep(DELAY)

    if with_episodes:
        eps = s['episodes']
        if max_eps:
            eps = eps[:max_eps]
        for i, e in enumerate(eps, 1):
            vh = fetch('/video/%s' % e['slug'])
            if vh:
                got = parse_video(vh, e['slug'])
                e.update({k: v for k, v in got.items() if k not in ('slug', 'url')})
            time.sleep(DELAY)
        if verbose and eps and eps[0].get('hls'):
            # 顺手取一次码率档位作为样例
            try:
                mm = fetch(eps[0]['hls'].replace(BASE, ''))
                if mm:
                    eps[0]['variants'] = parse_master(mm, eps[0]['hls'])
            except Exception:
                pass
    return s


def cmd_export(a):
    os.makedirs(a.out, exist_ok=True)
    all_series = []

    # 1) 列表页拿 slug
    slugs, seen = [], set()
    for page in range(1, a.pages + 1):
        html = fetch('/videos?page=%d' % page)
        if not html:
            print('  第 %d 页取不到，停止' % page)
            break
        items = parse_list(html)
        new = [x['slug'] for x in items if x['slug'] not in seen]
        seen.update(new)
        slugs += new
        print('  列表 第 %-3d 页  +%d（累计 %d）' % (page, len(new), len(slugs)))
        time.sleep(DELAY)
        if not new:
            break

    # 2) 逐部抓详情
    print('\n  抓详情（%d 部）…' % len(slugs))
    for i, slug in enumerate(slugs, 1):
        print('  [%d/%d]' % (i, len(slugs)), end='')
        s = scrape_series(slug, with_episodes=True, max_eps=a.max_eps)
        all_series.append(s)
        # 边抓边落盘，中断也不丢
        with open(os.path.join(a.out, 'series.json'), 'w', encoding='utf-8') as f:
            json.dump(all_series, f, ensure_ascii=False, indent=2)

    write_csv(all_series, os.path.join(a.out, 'series.csv'))

    index = {
        'source': BASE,
        'scraped_at': time.strftime('%Y-%m-%d %H:%M:%S'),
        'series_count': len(all_series),
        'episode_count': sum(len(x.get('episodes') or []) for x in all_series),
        'with_stream': sum(
            1 for x in all_series for e in (x.get('episodes') or []) if e.get('hls')),
        'note': '播放地址带签名、有时效，使用前请重新取详情',
    }
    with open(os.path.join(a.out, 'index.json'), 'w', encoding='utf-8') as f:
        json.dump(index, f, ensure_ascii=False, indent=2)

    print('\n  完成 →', a.out)
    print('    series.json  %d 部' % index['series_count'])
    print('    series.csv   %d 集' % index['episode_count'])
    print('    index.json')


def write_csv(series_list, path):
    cols = ['series_slug', 'title', 'tags', 'cover', 'episode_no',
            'episode_slug', 'content_id', 'hls', 'poster']
    n = 0
    with open(path, 'w', encoding='utf-8-sig', newline='') as f:
        w = csv.writer(f)
        w.writerow(cols)
        for s in series_list:
            for e in (s.get('episodes') or []):
                w.writerow([
                    s.get('slug', ''), s.get('title', ''),
                    '|'.join(s.get('tags') or []), s.get('cover', ''),
                    e.get('episode_no', ''), e.get('slug', ''),
                    e.get('content_id', ''), e.get('hls', ''), e.get('poster', ''),
                ])
                n += 1
    return n


def cmd_check(a):
    """
    自检：确认站点结构没变。

    页面改版会让所有正则失效 —— 这个命令用来**快速判断是不是站点变了**
    还是自己的代码有问题。
    """
    print('自检 huangguo.video')
    print('- 列表页 /videos')
    h = fetch('/videos')
    if not h:
        print('  ❌ 取不到 —— 站点可能挂了，或者被墙/限流'); return
    items = parse_list(h)
    print('  页面 %d 字节，解析出 %d 部' % (len(h), len(items)))
    print('  %s' % ('✅ 正常' if items else '❌ 解析不出条目 —— 站点结构可能变了'))
    if not items:
        return
    slug = items[0]['slug']
    time.sleep(DELAY)

    print('- 详情页 /series/%s' % slug)
    sh = fetch('/series/%s' % slug)
    if not sh:
        print('  ❌ 取不到'); return
    s = parse_series(sh, slug)
    print('  剧名: %r   分集: %d 个' % (s['title'], len(s['episodes'])))
    print('  %s' % ('✅ 正常' if s['title'] and s['episodes'] else '❌ 解析异常'))
    if not s['episodes']:
        return
    time.sleep(DELAY)

    epslug = s['episodes'][0]['slug']
    print('- 播放页 /video/%s' % epslug)
    vh = fetch('/video/%s' % epslug)
    if not vh:
        print('  ❌ 取不到'); return
    v = parse_video(vh, epslug)
    print('  content_id=%s' % v['content_id'])
    print('  hls=%s' % (v['hls'] or '(空)'))
    ok = bool(v['content_id'] and v['hls'])
    print('  %s' % ('✅ 正常' if ok else '❌ 拿不到播放地址 —— data-hls 属性可能改名了'))
    if ok:
        time.sleep(DELAY)
        print('- 播放流 master.m3u8')
        mm = fetch(v['hls'].replace(BASE, ''))
        vs = parse_master(mm or '', v['hls'])
        print('  码率档位 %d 个: %s' % (
            len(vs), ', '.join(x['name'] or x['resolution'] for x in vs)))
        print('  %s' % ('✅ 正常' if vs else '❌ 解析不出档位'))
    print()
    print('全部正常 ✅' if ok else '有异常 ❌')


def main():
    ap = argparse.ArgumentParser(
        description='黄果剧场（huangguo.video）抓取工具',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__.split('## 用法')[1].split('## 输出')[0] if '## 用法' in __doc__ else '')
    sub = ap.add_subparsers(dest='cmd', required=True)

    p = sub.add_parser('list', help='抓列表（只看 slug）')
    p.add_argument('--pages', type=int, default=3)
    p.set_defaults(fn=cmd_list)

    p = sub.add_parser('series', help='抓一部剧')
    p.add_argument('slug')
    p.add_argument('--episodes', type=int, default=0, help='抓前 N 集的播放地址')
    p.set_defaults(fn=cmd_series)

    p = sub.add_parser('export', help='批量导出 JSON + CSV')
    p.add_argument('--pages', type=int, default=5, help='抓多少页列表')
    p.add_argument('--max-eps', type=int, default=0, help='每部最多抓几集（0=全部）')
    p.add_argument('--out', default='data', help='输出目录')
    p.set_defaults(fn=cmd_export)

    p = sub.add_parser('check', help='自检站点结构')
    p.set_defaults(fn=cmd_check)

    a = ap.parse_args()
    a.fn(a)


if __name__ == '__main__':
    main()
