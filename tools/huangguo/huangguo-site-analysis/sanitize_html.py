#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
HTML 样本脱敏：**保留结构，抹掉文字**。

## 为什么

`samples/*.html` 是原始页面 dump —— 对分析结构很有用（DOM 层级、CSS class、
`data-*` 属性都在），但里面当然也有页面正文（成人的剧名、简介）。

分析**站点结构**完全不需要正文。所以这里把所有**可见文本节点**替换成
等长占位符，同时**原样保留**：

    · 全部标签和嵌套层级
    · 全部 class 名（分析样式体系的关键）
    · 全部 src / href / data-* 属性（结构分析的关键）
    · 文本长度（用等长占位符，能看出各区块文字多长）

## 保留哪些"文字"

`<script>` / `<style>` 里的内容**不动** —— 它们是代码不是正文，
而且正是分析要看的（播放器初始化、CSP nonce 等）。
但 script 里嵌入的 JSON 正文（如剧名）会被一起保留，
所以**最后还会再扫一遍敏感词并报告**。

## 正文里的 URL 也保留

文本节点里若含 URL（如简介里的链接），替换后会失真 —— 这类节点
只替换其中的中文部分，保留 ASCII 结构。

用法：
    python sanitize_html.py samples/list.html samples/list.sanitized.html
    python sanitize_html.py --all          # 就地处理 samples/ 下全部 .html
"""
import argparse
import glob
import io
import os
import re
import sys

# 占位符字符池（只含 ASCII，避免引入新的中文内容）
PLACE = 'X'

# 不需要处理的标签：内容是代码不是正文
KEEP_RAW = ('script', 'style', 'noscript', 'code', 'pre')


def mask_text(s: str, keep_ascii: bool = True) -> str:
    """
    把一个文本节点替换成占位符。

    keep_ascii=True 时保留 ASCII 部分（URL、数字、英文标签），
    只替换 CJK 和其余非 ASCII —— 这样简介里的链接不失真。
    """
    out = []
    for ch in s:
        if ch.isspace():
            out.append(ch)
        elif keep_ascii and ord(ch) < 128:
            out.append(ch)          # 保留 ASCII
        else:
            out.append(PLACE)       # 中文等 → 占位符
    return ''.join(out)


def mask_attr(tag: str) -> str:
    """
    给标签的属性值脱敏（只抹中文，保留 ASCII）。

    ## 为什么需要

    剧名不只出现在文本节点里，还会出现在：
        <img alt="剧名">
        <meta name="description" content="《剧名》是…">
        <meta property="og:title" content="剧名">

    只处理文本节点会漏掉这些。这里把每个 `name="value"` 的 **value** 里的
    非 ASCII 字符换掉，ASCII 部分（URL、class、id、数字）原样保留 ——
    所以 `src="/uploads/content/series/217/cover.webp?v=1"` 不受影响。
    """
    def repl(m):
        val = mask_text(m.group(2))
        return '%s="%s"' % (m.group(1), val)
    # 匹配 name="value"，value 里可能有中文
    return re.sub(r'([a-zA-Z_:][-a-zA-Z0-9_:.]*)="([^"]*)"', repl, tag)


def sanitize(html: str) -> str:
    """
    按标签切分，对"标签之外"的文本做脱敏。

    ⚠️ 不能用正则直接替换全部文本 —— 会把 `<script>` 里的代码也砸掉，
    而那些正是要保留的（播放器逻辑、CSP nonce）。
    所以这里手写一个极简分词器：扫到 `<script...>` 就跳到对应的 `</script>`。
    """
    out = []
    i = 0
    n = len(html)
    while i < n:
        lt = html.find('<', i)
        if lt < 0:
            out.append(mask_text(html[i:]))
            break
        # `<` 之前的都是文本
        if lt > i:
            out.append(mask_text(html[i:lt]))

        gt = html.find('>', lt)
        if gt < 0:
            out.append(mask_text(html[lt:]))    # 来不及闭合，原样收尾
            break

        tag = html[lt:gt + 1]
        out.append(mask_attr(tag))

        # 判断是不是需要"整段保留"的标签
        m = re.match(r'<\s*([a-zA-Z0-9]+)', tag)
        name = m.group(1).lower() if m else ''
        if name in KEEP_RAW and not tag.endswith('/>'):
            close = re.search(r'</\s*%s\s*>' % re.escape(name), html[gt + 1:], re.I)
            if close:
                end = gt + 1 + close.end()
                raw = html[gt + 1:end]
                # script/style 内容整体保留（是代码），但里面的
                # application/json 块常嵌正文 —— 对 JSON 块做一次脱敏
                if name == 'script' and 'application/json' in tag:
                    raw = mask_json_block(raw)
                out.append(raw)
                i = end
                continue
        i = gt + 1
    return ''.join(out)


def mask_json_block(s: str) -> str:
    """
    抹掉内嵌 JSON 里字符串值的非 ASCII 部分。

    只动 `"key": "中文值"` 这种 —— 数字、布尔、结构全保留。
    """
    def repl(m):
        return '%s: "%s"' % (m.group(1), mask_text(m.group(2)))
    return re.sub(r'("(?:[A-Za-z_][\w-]*)")\s*:\s*"([^"]*)"', repl, s)


SENSITIVE = ['好好操', '嫖客', '淫乱', '口爆', '同欢', '囚操', '人妻', '乱伦', 'NTR', '奶', '裸']


def scan(path: str) -> list:
    t = io.open(path, encoding='utf-8', errors='replace').read()
    return [w for w in SENSITIVE if w in t]


def main():
    ap = argparse.ArgumentParser(description='HTML 样本脱敏（保留结构，抹掉正文）')
    ap.add_argument('files', nargs='*')
    ap.add_argument('--all', action='store_true', help='处理 samples/ 下全部 .html（就地覆盖）')
    ap.add_argument('--inplace', action='store_true')
    a = ap.parse_args()

    here = os.path.dirname(os.path.abspath(__file__))
    targets = []
    if a.all:
        targets = [(p, p) for p in sorted(glob.glob(os.path.join(here, 'samples', '*.html')))]
    else:
        for f in a.files:
            out = f if a.inplace else re.sub(r'\.html$', '.sanitized.html', f)
            targets.append((f, out))

    if not targets:
        print('  没指定文件。用 --all 或给出文件名。')
        return

    for src, dst in targets:
        if not os.path.exists(src):
            print('  ! 不存在:', src)
            continue
        html = io.open(src, encoding='utf-8', errors='replace').read()
        clean = sanitize(html)
        io.open(dst, 'w', encoding='utf-8').write(clean)

        hits = scan(dst)
        print('  %-22s %7d → %7d 字节   %s' % (
            os.path.basename(dst), len(html), len(clean),
            ('✅ 无残留' if not hits else '⚠️ script/JSON 里仍有: %s' % hits)))
        print('       结构保留检查: <article %d 个, class= %d 处, data- %d 处' % (
            clean.count('<article'), clean.count('class='), clean.count('data-')))


if __name__ == '__main__':
    main()
