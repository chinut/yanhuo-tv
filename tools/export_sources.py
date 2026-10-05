#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
导出两个来源的频道清单。

    A. **主源** = 土拨鼠大屏浏览器内置的频道表
         resources/assets/tv-web-seed/config/tv.yml    ← 主体（104KB）
         resources/assets/tv-web-seed/js/cctv/tv2.json ← 央视频道表

    B. **GitHub 源** = 焰火TV 订阅的开源 IPTV 列表
         app/src/main/assets/live/iptv.m3u

## 主源的结构（实测）

`tv.yml` 虽然叫 .yml，**内容是 JSON**：

    {"province": [
        {"tag":"sdtv","name":"山东","vods":[
            {"name":"山东卫视","url":"..."},
            ...
        ]},
        ...
    ]}

URL 有四种形态，**能不能直连差别很大**：

| 形态 | 例子 | 说明 |
|---|---|---|
| 直连 m3u8 | `live.html?url=https://…/index.m3u8` | ✅ 去掉 `live.html?url=` 就是真流 |
| 分享页 + base64 | `live_com.html?script=js/tv/iapp/live.js&url=https://…/share/dHZsLTE3Ny02.html` | ⚠️ 要跑那个 js 才能抽出真实地址 |
| 网页 | `https://www.ahtv.cn/folder9000/…` | ⚠️ 要 WebView |
| 央视频道 | `https://tv.cctv.com/live/cctv1/` | ⚠️ 要 WebView |

所以导出时**按形态分类**，用户一眼就能看出哪些能直接用、哪些要点进去抽。

## 用法

    python export_sources.py                     # 两个源都导出
    python export_sources.py --only main         # 只导主源
    python export_sources.py --only github       # 只导 GitHub 源
    python export_sources.py --out 输出目录
"""
from __future__ import annotations

import argparse
import csv
import io
import json
import os
import re
import sys
import urllib.parse
from collections import Counter, OrderedDict

HERE = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.normpath(os.path.join(HERE, '..'))

# 土拨鼠包的位置（可用 --tuber 覆盖）
DEFAULT_TUBER = r'G:\百度下载神器\土拨鼠大屏浏览器20260623'

# GitHub 源（焰火TV 内置）
GITHUB_M3U = os.path.join(PROJ, 'app', 'src', 'main', 'assets', 'live', 'iptv.m3u')


# ============================ 形态判定 ============================

def kind_of(url: str) -> str:
    """
    判断这条地址**能不能直接播**。

    这是整份清单最有价值的分类 —— 决定要不要走 WebView。
    """
    u = url.strip()
    if not u:
        return '空'
    # live.html?url=<真流>  → 把真流抠出来就是直连
    m = re.search(r'[?&]url=(https?://[^&\s]+)', u)
    if m:
        inner = urllib.parse.unquote(m.group(1))
        if re.search(r'\.(m3u8|flv|mp4|ts)(\?|$)', inner, re.I):
            return '直连流(内嵌)'
    if re.search(r'\.(m3u8|flv|mp4|ts)(\?|$)', u, re.I):
        return '直连流'
    # 分享页 + base64 参数
    if 'share/' in u and re.search(r'share/[A-Za-z0-9_\-=]{8,}', u):
        return '分享页(base64)'
    if 'live_com.html' in u or 'live.html' in u:
        return '抽流页(js)'
    if re.search(r'cctv\.com|yangshipin', u):
        return '央视网页'
    if u.startswith('http'):
        return '网页'
    return '其它'


def resolve_direct(url: str) -> str:
    """能直接抠出真流的，返回真流；否则原样返回。"""
    m = re.search(r'[?&]url=(https?://[^&\s]+)', url)
    if m:
        inner = urllib.parse.unquote(m.group(1))
        if re.search(r'\.(m3u8|flv|mp4|ts)(\?|$)', inner, re.I):
            return inner
    return url


# ============================ A. 主源 ============================

def parse_simple_yaml(text: str):
    """
    极简 YAML 解析器 —— 只为 `tv.yml` 这种结构写，**不引入 pyyaml 依赖**。

    ## 为什么自己写

    环境里没有 pyyaml，而这个文件的结构固定得可笑：

        data:
          - tag: cctv
            name: 央视
            vods:
              - name: CCTV-13 新闻
                url: https://tv.cctv.com/live/cctv13/

    只有三种东西：**`key:` 开新层**、**`- key: value` 开列表项**、**`key: value` 塞字段**。
    没有锚点、没有多行字符串、没有引号转义。为这点结构装 yaml 库不值得。

    ## 实现思路（第一版写错了，这里记一下）

    用**缩进栈**维护「当前容器」和「当前项」：

        stack 元素 = (缩进, 所属字典, 所属列表)
        遇到 `- `   → 在所属列表里 append 一个新 dict，成为当前项
        遇到 `k: v` → 写进当前项
        遇到 `k:`   → 在当前项里占位成列表，压栈

    第一版我搞混了 "当前项" 和 "父容器"，只解析出 1 条。
    现在把「所属字典」和「所属列表」分开记，逻辑就直了。

    返回 dict。
    """
    root: dict = {}
    # 每一项：(缩进, dict 容器, list 容器或 None)
    # 新键写进 dict 容器；新列表项 append 到 list 容器
    stack = [(-1, root, None)]
    lines = text.splitlines()

    for raw in lines:
        if not raw.strip() or raw.lstrip().startswith('#'):
            continue
        indent = len(raw) - len(raw.lstrip(' '))
        s = raw.strip()

        # 回退：弹出所有缩进 >= 当前行的层
        while len(stack) > 1 and indent <= stack[-1][0]:
            stack.pop()
        _, cur_dict, cur_list = stack[-1]

        if s.startswith('- '):
            body = s[2:].strip()
            if cur_list is None:
                continue                      # 结构异常，跳过
            item: dict = {}
            cur_list.append(item)
            if ':' in body:
                k, v = body.split(':', 1)
                k, v = k.strip(), v.strip()
                if v == '':
                    item[k] = []
                    stack.append((indent, item, item[k]))
                else:
                    item[k] = _scalar(v)
            else:
                # `- 纯值`（少见）
                cur_list[-1] = _scalar(body)
                continue
            stack.append((indent, item, None))
            continue

        if ':' in s:
            k, v = s.split(':', 1)
            k, v = k.strip(), v.strip()
            if v == '':
                new_list: list = []
                cur_dict[k] = new_list
                stack.append((indent, cur_dict, new_list))
            else:
                cur_dict[k] = _scalar(v)

    return root


def _scalar(v: str):
    """把 YAML 标量转成合适的 Python 类型。"""
    if len(v) >= 2 and v[0] == v[-1] and v[0] in '"\'':
        return v[1:-1]
    low = v.lower()
    if low in ('true', 'yes'):
        return True
    if low in ('false', 'no'):
        return False
    if low in ('null', '~', ''):
        return None
    if re.fullmatch(r'-?\d+', v):
        return int(v)
    return v


def load_main(tuber_dir: str) -> list[dict]:
    """
    解析土拨鼠的 tv.yml + tv2.json。

    ⚠️ `tv.yml` 是 **YAML**（不是 JSON，虽然早期版本看起来像）。
    环境里没有 pyyaml，所以用上面的 `parse_simple_yaml`。
    """
    out = []

    # ---- tv.yml（主体，按省份分组）----
    p = os.path.join(tuber_dir, 'resources', 'assets', 'tv-web-seed', 'config', 'tv.yml')
    if os.path.exists(p):
        raw = io.open(p, encoding='utf-8', errors='replace').read()
        groups = None
        # 先试 JSON（老的包可能是 JSON）
        st = raw.lstrip()
        if st.startswith('{') or st.startswith('['):
            try:
                d = json.loads(raw)
                groups = d.get('data') if isinstance(d, dict) else d
            except Exception:
                groups = None
        if groups is None:
            d = parse_simple_yaml(raw)
            groups = d.get('data') if isinstance(d, dict) else None
        if not isinstance(groups, list):
            print('  ! tv.yml 解析不出 data 列表')
            groups = []
        n0 = len(out)
        for g in groups:
            if not isinstance(g, dict):
                continue
            prov = g.get('name') or g.get('tag') or ''
            tag = g.get('tag') or ''
            for v in (g.get('vods') or []):
                if not isinstance(v, dict):
                    continue
                u = str(v.get('url') or '').strip()
                out.append({
                    'source': '主源',
                    'section': 'tv.yml',
                    'province': prov,
                    'tag': tag,
                    'name': str(v.get('name') or '').strip(),
                    'url': u,
                    'kind': kind_of(u),
                    'direct': resolve_direct(u),
                })
        print('  tv.yml      %d 条（%d 个分组）' % (len(out) - n0, len(groups)))
    else:
        print('  ! 找不到 tv.yml:', p)

    # ---- tv2.json（央视频道表）----
    p2 = os.path.join(tuber_dir, 'resources', 'assets', 'tv-web-seed', 'js', 'cctv', 'tv2.json')
    n0 = len(out)
    if os.path.exists(p2):
        try:
            d2 = json.loads(io.open(p2, encoding='utf-8', errors='replace').read())
        except Exception as e:
            print('  ! tv2.json 解析失败: %s' % e)
            d2 = None
        if d2 is not None:
            def walk(o, prov=''):
                if isinstance(o, dict):
                    for k, v in o.items():
                        if k in ('name', 'url') and isinstance(v, str):
                            continue
                        walk(v, o.get('name') or prov)
                    if 'url' in o and 'name' in o:
                        u = str(o['url']).strip()
                        out.append({
                            'source': '主源',
                            'section': '央视',
                            'province': prov,
                            'tag': 'cctv',
                            'name': str(o['name']).strip(),
                            'url': u,
                            'kind': kind_of(u),
                            'direct': resolve_direct(u),
                        })
                elif isinstance(o, list):
                    for x in o:
                        walk(x, prov)
            walk(d2)
            print('  tv2.json    %d 条' % (len(out) - n0))
    else:
        print('  ! 找不到 tv2.json:', p2)

    return out


# ============================ B. GitHub 源 ============================

def load_github(path: str) -> list[dict]:
    """解析 iptv.m3u（标准 m3u，UTF-8）。"""
    out = []
    if not os.path.exists(path):
        print('  ! 找不到:', path)
        return out
    lines = io.open(path, encoding='utf-8', errors='replace').read().splitlines()
    pend = None
    for ln in lines:
        s = ln.strip()
        if not s:
            continue
        if s.startswith('#EXTINF'):
            g = ''
            m = re.search(r'group-title="([^"]*)"', s)
            if m:
                g = m.group(1)
            pend = {'group': g, 'name': s.rsplit(',', 1)[-1].strip()}
        elif s.startswith('#'):
            continue
        else:
            u = s
            out.append({
                'source': 'GitHub源',
                'section': 'IPTV',
                'province': (pend or {}).get('group', ''),
                'tag': '',
                'name': (pend or {}).get('name', ''),
                'url': u,
                'kind': kind_of(u),
                'direct': resolve_direct(u),
            })
            pend = None
    print('  iptv.m3u    %d 条' % len(out))
    return out


# ============================ 输出 ============================

COLS = ['source', 'section', 'province', 'name', 'kind', 'url', 'direct']


def write_csv(items: list[dict], path: str):
    with io.open(path, 'w', encoding='utf-8-sig', newline='') as f:
        w = csv.DictWriter(f, fieldnames=COLS, extrasaction='ignore')
        w.writeheader()
        for it in items:
            w.writerow(it)


def write_md(items: list[dict], path: str, title: str):
    L = [f'# {title}', '']
    L.append('共 **%d** 条' % len(items))
    L.append('')

    # 按形态汇总（最重要的一张表）
    L.append('## 按「能不能直接播」分类')
    L.append('')
    L.append('| 形态 | 条数 | 说明 |')
    L.append('|---|---|---|')
    explain = {
        '直连流': '✅ 本身就是要给播放器的地址',
        '直连流(内嵌)': '✅ `live.html?url=…m3u8` —— 把 `url=` 后面的抠出来就是真流',
        '分享页(base64)': '⚠️ 要加载那个 js（`js/tv/iapp/live.js`）才能抽出真实地址',
        '抽流页(js)': '⚠️ 同一类，需要对应省的 detail.js',
        '央视网页': '⚠️ `tv.cctv.com` / 央视频，要 WebView',
        '网页': '⚠️ 普通网页播放页',
        '其它': '? 无法判断',
        '空': '✗ 没有地址',
    }
    c = Counter(x['kind'] for x in items)
    for k, v in c.most_common():
        L.append('| %s | %d | %s |' % (k, v, explain.get(k, '')))
    L.append('')

    # 直连的单独列出来（这是用户最想要的）
    direct = [x for x in items if x['kind'] in ('直连流', '直连流(内嵌)')]
    L.append('## ✅ 可以直接用的（%d 条）' % len(direct))
    L.append('')
    if direct:
        L.append('| 频道 | 来源 | 分组 | 真流地址 |')
        L.append('|---|---|---|---|')
        for x in direct:
            L.append('| %s | %s | %s | `%s` |' % (
                x['name'] or '-', x['source'], x['province'] or x['section'],
                x['direct'][:88]))
    else:
        L.append('（没有）')
    L.append('')

    # 按来源/分组统计
    L.append('## 按分组统计')
    L.append('')
    L.append('| 来源 | 分组 | 条数 | 其中直连 |')
    L.append('|---|---|---|---|')
    groups = OrderedDict()
    for x in items:
        key = (x['source'], x['province'] or x['section'] or '(未分组)')
        groups.setdefault(key, []).append(x)
    for (src, grp), v in sorted(groups.items(), key=lambda kv: (kv[0][0], -len(kv[1]))):
        d = sum(1 for y in v if y['kind'] in ('直连流', '直连流(内嵌)'))
        L.append('| %s | %s | %d | %d |' % (src, grp, len(v), d))
    L.append('')

    # 全量明细（分组列出）
    L.append('## 全部明细')
    L.append('')
    for (src, grp), v in sorted(groups.items(), key=lambda kv: (kv[0][0], kv[0][1])):
        L.append('### %s · %s（%d 条）' % (src, grp, len(v)))
        L.append('')
        L.append('| 频道 | 形态 | 地址 |')
        L.append('|---|---|---|')
        for x in v:
            L.append('| %s | %s | `%s` |' % (x['name'] or '-', x['kind'], x['url'][:96]))
        L.append('')

    io.open(path, 'w', encoding='utf-8').write('\n'.join(L))


def main():
    ap = argparse.ArgumentParser(description='导出主源 + GitHub 源的频道清单')
    ap.add_argument('--tuber', default=DEFAULT_TUBER, help='土拨鼠包目录')
    ap.add_argument('--out', default=os.path.join(HERE, 'out'))
    ap.add_argument('--only', choices=['main', 'github', 'both'], default='both')
    a = ap.parse_args()

    os.makedirs(a.out, exist_ok=True)
    main_items, gh_items = [], []

    if a.only in ('main', 'both'):
        print('A. 主源（土拨鼠内置）')
        print('   目录:', a.tuber)
        if not os.path.isdir(a.tuber):
            print('   ! 目录不存在')
        else:
            main_items = load_main(a.tuber)
            write_csv(main_items, os.path.join(a.out, '主源.csv'))
            write_md(main_items, os.path.join(a.out, '主源.md'),
                     '主源频道清单（土拨鼠内置 tv.yml）')
        print()

    if a.only in ('github', 'both'):
        print('B. GitHub 源（焰火TV 订阅）')
        gh_items = load_github(GITHUB_M3U)
        write_csv(gh_items, os.path.join(a.out, 'GitHub源.csv'))
        write_md(gh_items, os.path.join(a.out, 'GitHub源.md'),
                 'GitHub 源频道清单（iptv.m3u）')
        print()

    allitems = main_items + gh_items
    if allitems:
        write_csv(allitems, os.path.join(a.out, '两源合并.csv'))
        write_md(allitems, os.path.join(a.out, '两源合并.md'), '两个来源合并清单')

    print('输出目录:', a.out)
    print()
    print('汇总:')
    for label, items in (('主源', main_items), ('GitHub源', gh_items)):
        if not items:
            continue
        c = Counter(x['kind'] for x in items)
        d = c.get('直连流', 0) + c.get('直连流(内嵌)', 0)
        print('  %-10s 共 %4d 条，其中可直连 %3d 条（%.0f%%）' % (
            label, len(items), d, 100.0 * d / len(items)))


if __name__ == '__main__':
    main()
