#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成**脱敏**的数据形状样本。

## 为什么

原始导出里带的是成人内容的剧名和简介正文。分析站点**结构**不需要这些正文 ——
只需要字段名、类型、长度、嵌套形状。所以这里把正文替换成等长占位符，
让你能照着建表，但包里不留内容。

## 保留什么

· 字段名、类型、嵌套结构
· 字符长度（等长占位符，方便判断建表时 VARCHAR 开多长）
· 数值型字段的真实值（content_id、bandwidth、resolution —— 这些是技术参数不是内容）
· URL **路径形状**（但 slug 替换成假值，避免成为可直接访问的入口）

## 替换什么

· 剧名 → 「示例剧名 001」
· 简介 → 「《示例剧名》是示例平台的连续剧，由@示例出品，标签A、标签B。」（同长度）
· 标签 → 「标签A」「标签B」…
· slug → 假 slug（按原长度生成）
· 集标题 → 「示例剧名 001 · 第N集」
"""
import io
import json
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(os.path.dirname(HERE), 'data-demo', 'series.json')
OUT = os.path.join(HERE, 'data-samples')

# 标签位置 → 脱敏名
TAG_MAP = {}
TAG_LETTERS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'


def fake_slug(real: str, salt: int) -> str:
    """生成同长度的假 slug（保持字母数字、可读性无关）。"""
    n = len(real)
    base = 'abcdefghijklmnopqrstuvwxyz0123456789'
    return ''.join(base[(salt * 7 + i * 11) % len(base)] for i in range(n))


def fake_tag(real: str) -> str:
    if real not in TAG_MAP:
        TAG_MAP[real] = '标签%s' % TAG_LETTERS[len(TAG_MAP) % 26]
    return TAG_MAP[real]


def sanitize_series(s: dict, idx: int) -> dict:
    real_title = s.get('title') or ''
    ph_title = '示例剧名 %03d' % (idx + 1)

    # 简介：保持**同样长度**，这样你能看出该字段大概多长
    real_desc = s.get('description') or ''
    ph_desc = ('《%s》是示例平台的连续剧，由@示例出品，标签%s。'
               % (ph_title,
                  '、'.join(fake_tag(t) for t in (s.get('tags') or [])) or '示例'))
    if len(real_desc) > len(ph_desc):
        ph_desc += '（内容略）' * max(0, (len(real_desc) - len(ph_desc)) // 5)

    slug = fake_slug(s.get('slug', ''), idx + 1)

    eps = []
    for e in (s.get('episodes') or []):
        ep = dict(e)
        ep['slug'] = fake_slug(e.get('slug', ''), idx + 101)
        ep['title'] = '%s · 第%s集' % (ph_title, e.get('episode_no') or '?')
        # URL 保留**路径形状**，但换成假 slug
        if ep.get('url'):
            ep['url'] = re.sub(r'/video/\w+$', '/video/%s' % ep['slug'], ep['url'])
        if ep.get('series_slug'):
            ep['series_slug'] = slug
        # ⚠️ `hls` 是**可直接访问的播放入口** —— 真实 content_id 必须换成占位符，
        # 否则这个包就等于一份可点的播放清单。路径形状保留，值脱敏。
        if ep.get('url'):
            ep['url'] = re.sub(r'/video/[\w-]+$', '/video/%s' % ep['slug'], ep['url'])
        if ep.get('hls'):
            ep['hls'] = re.sub(r'/video/\d+/', '/video/<content_id>/', ep['hls'])
        if ep.get('poster'):
            # 封面：数字 ID 也换成占位符，?v= 版本号同样处理
            ep['poster'] = re.sub(r'/series/\d+/', '/series/<series_id>/', ep['poster'])
            ep['poster'] = re.sub(r'\?v=\d+', '?v=<版本号>', ep['poster'])
        if ep.get('content_id'):
            ep['content_id'] = '<content_id>'
        vars_ = []
        for v in (e.get('variants') or []):
            vv = dict(v)
            if vv.get('url'):
                vv['url'] = re.sub(r'/video/\d+/', '/video/<content_id>/', vv['url'])
                vv['url'] = re.sub(r'\?n=[^&]*', '?n=<签名>', vv['url'])
            vars_.append(vv)
        if vars_:
            ep['variants'] = vars_
        eps.append(ep)

    out = dict(s)
    out['slug'] = slug
    out['title'] = ph_title
    out['description'] = ph_desc
    out['tags'] = [fake_tag(t) for t in (s.get('tags') or [])]
    if out.get('url'):
        out['url'] = re.sub(r'/series/\w+$', '/series/%s' % slug, out['url'])
    if out.get('cover'):
        out['cover'] = re.sub(r'\?v=\d+', '?v=<版本号>', out['cover'])
    out['episodes'] = eps
    return out


def main():
    os.makedirs(OUT, exist_ok=True)
    src = json.load(io.open(SRC, encoding='utf-8'))
    print('  读入 %d 部' % len(src))

    clean = [sanitize_series(s, i) for i, s in enumerate(src)]

    # 全量（脱敏后）
    with io.open(os.path.join(OUT, 'series.schema.json'), 'w', encoding='utf-8') as f:
        json.dump(clean, f, ensure_ascii=False, indent=2)

    # 单部（看结构最方便）
    with io.open(os.path.join(OUT, 'one-series.json'), 'w', encoding='utf-8') as f:
        json.dump(clean[0], f, ensure_ascii=False, indent=2)

    # 字段类型表
    fields = {}
    def walk(o, prefix=''):
        if isinstance(o, dict):
            for k, v in o.items():
                p = prefix + '.' + k if prefix else k
                if isinstance(v, list):
                    fields.setdefault(p, 'array<%s>' % (type(v[0]).__name__ if v else 'any'))
                    if v:
                        walk(v[0], p + '[]')
                elif isinstance(v, dict):
                    fields.setdefault(p, 'object')
                    walk(v, p)
                else:
                    fields.setdefault(p, type(v).__name__)
    for s in clean[:5]:
        walk(s)

    with io.open(os.path.join(OUT, 'field-types.txt'), 'w', encoding='utf-8') as f:
        f.write('字段类型（从脱敏样本推断）\n')
        f.write('=' * 50 + '\n')
        for k in sorted(fields):
            f.write('%-42s %s\n' % (k, fields[k]))

    # 索引（保留真实统计，这些不是内容）
    idx = {
        'source': 'https://huangguo.video',
        'sample_pages_scraped': 3,
        'series_in_sample': len(clean),
        'episodes_in_sample': sum(len(s['episodes']) for s in clean),
        'episodes_with_stream': sum(
            1 for s in clean for e in s['episodes'] if e.get('hls')),
        'note': '剧名/简介/标签正文已替换为占位符，slug 已换成假值；'
                'URL 形状、content_id、码率参数保留',
    }
    with io.open(os.path.join(OUT, 'index.json'), 'w', encoding='utf-8') as f:
        json.dump(idx, f, ensure_ascii=False, indent=2)

    print('  写出：')
    for n in ('series.schema.json', 'one-series.json', 'field-types.txt', 'index.json'):
        p = os.path.join(OUT, n)
        print('    %-24s %d 字节' % (n, os.path.getsize(p)))


if __name__ == '__main__':
    main()
