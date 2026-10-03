"""
自动抓取央视各频道的直播直连地址。

## 背景

央视的直播流可以直连，不需要 WebView、不需要签名：

    https://ldncctvwbcdtxy.liveplay.myqcloud.com/ldncctvwbcd/cdrmldcctv13_1.m3u8
    → 腾讯云直播（央视自己的 CDN），无防盗链，master playlist 5 档清晰度

但**每个频道的地址不同**（主机名和路径都不一样），
而且地址是页面里的 JS 动态取回来的 —— 所以静态爬 HTML 拿不到。

## 这个脚本做什么

用真实浏览器（Playwright + 你已装的 Edge）逐个打开央视各频道页面，
**监听网络请求**，把 m3u8 的地址截下来。

这跟手工 F12 是同一件事，只是自动化了 —— 一次跑完 17 个频道，
以后央视换地址重跑一遍就行。

## 用法

    python tools\fetch_cctv_urls.py

结果写到 cctv_urls.json，同时打印在屏幕上。
"""
import json
import os
import re
import sys
import time

try:
    from playwright.sync_api import sync_playwright
except ImportError:
    print('缺少 playwright。请先运行：')
    print('    python -m pip install playwright')
    sys.exit(1)

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OUT = os.path.join(ROOT, 'cctv_urls.json')

# 要抓的频道。键是央视网的频道 slug，值是给用户看的名字。
CHANNELS = [
    ('cctv1', 'CCTV-1 综合'),
    ('cctv2', 'CCTV-2 财经'),
    ('cctv3', 'CCTV-3 综艺'),
    ('cctv4', 'CCTV-4 中文国际'),
    ('cctv5', 'CCTV-5 体育'),
    ('cctv5plus', 'CCTV-5+ 体育赛事'),
    ('cctv6', 'CCTV-6 电影'),
    ('cctv7', 'CCTV-7 国防军事'),
    ('cctv8', 'CCTV-8 电视剧'),
    ('cctv9', 'CCTV-9 纪录'),
    ('cctv10', 'CCTV-10 科教'),
    ('cctv11', 'CCTV-11 戏曲'),
    ('cctv12', 'CCTV-12 社会与法'),
    ('cctv13', 'CCTV-13 新闻'),
    ('cctv14', 'CCTV-14 少儿'),
    ('cctv15', 'CCTV-15 音乐'),
    ('cctv16', 'CCTV-16 奥林匹克'),
    ('cctv17', 'CCTV-17 农业农村'),
]

# 只要这些域名的 m3u8 —— 央视自己的 CDN，不是海外转发
GOOD_HOST = re.compile(
    r'(myqcloud\.com|kcdnvip\.com|bdydns\.com|wscdns\.com|cntv\.cn|'
    r'cctv\.com|ksycdn|qcloud)',
    re.I,
)
# 明确排除（广告 / 统计 / 第三方）
BAD_HOST = re.compile(r'(doubleclick|google|baidu|umeng|sentry|admaster)', re.I)


def _slug_to_ch(slug: str) -> str:
    """'cctv5plus' → '5+'；'cctv13' → '13'"""
    n = slug.replace('cctv', '')
    return n.replace('plus', '+')


def _channel_in_url(u: str):
    """从地址里推断频道号。推不出来返回 None。"""
    # 形如 cdrmldcctv13 / cdrmldcctv5plus / cctv5plus/1080p
    m = re.search(r'cdrmldcctv(\d+)(plus)?', u, re.I)
    if m:
        return m.group(1) + ('+' if m.group(2) else '')
    m = re.search(r'/cctv(\d+)(plus)?/', u, re.I)
    if m:
        return m.group(1) + ('+' if m.group(2) else '')
    return None


def _has_own_channel(urls, slug: str) -> bool:
    """截到的地址里，有没有一个确实是当前频道的流。"""
    want = _slug_to_ch(slug)
    for u in urls:
        got = _channel_in_url(u)
        if got == want:
            return True
    return False


def is_media_url(u: str) -> bool:
    if '.m3u8' not in u.lower():
        return False
    if BAD_HOST.search(u):
        return False
    return True


def fetch_one(page, slug: str, name: str) -> dict:
    """打开一个频道页，截获它的 m3u8 请求。"""
    found = []
    errors = []

    def on_request(req):
        u = req.url
        if is_media_url(u):
            found.append(u)

    page.on('request', on_request)

    url = 'https://tv.cctv.com/live/%s/' % slug
    result = {'slug': slug, 'name': name, 'page': url,
              'urls': [], 'good': None, 'error': None}
    try:
        print('  [%s] 打开 %s' % (name, url))
        page.goto(url, timeout=45000, wait_until='domcontentloaded')

        # 等播放器起播。
        #
        # ⚠️ 踩过的坑：一开始等 30 秒，结果 CCTV-9/14 抓到了 **cctv13** 的地址
        # —— 央视的播放器是懒加载的，切频道后要过一会儿才真正换流。
        # 所以这里必须等到"截到的地址里频道号和当前频道对得上"才算成功。
        deadline = time.time() + 45
        while time.time() < deadline:
            page.wait_for_timeout(1000)
            if found and _has_own_channel(found, slug):
                # 已截到本频道自己的流，再多等 3 秒收集其它档位
                page.wait_for_timeout(3000)
                break

        # 去重
        uniq = sorted(set(found))
        result['urls'] = uniq

        # 挑"最好的"：必须是央视 CDN 的，而且**频道号要对得上**
        want = _slug_to_ch(slug)
        best = None
        for u in uniq:
            if not GOOD_HOST.search(u):
                continue
            got = _channel_in_url(u)
            if got is not None and got != want:
                continue                     # 别的频道，跳过
            if re.search(r'/index\.m3u8', u):   # master playlist 最好
                best = u
                break
            if best is None:
                best = u
        # 一个本频道的都没有 → 明确报出来，不要静默给错地址
        if best is None:
            others = sorted({_channel_in_url(u) for u in uniq
                             if _channel_in_url(u)})
            result['error'] = ('没截到本频道的流（截到的是 %s）'
                               % (','.join(others) if others else '无'))
        result['good'] = best
    except Exception as e:
        result['error'] = '%s: %s' % (type(e).__name__, str(e)[:120])
    finally:
        page.remove_listener('request', on_request)

    return result


def main():
    print('=' * 74)
    print('  央视直播地址抓取')
    print('=' * 74)
    print()
    print('  用真实浏览器打开每个频道页，截获它的 m3u8 请求。')
    print('  浏览器窗口会打开又关掉，属正常现象 —— 不要手动干预。')
    print('  共 %d 个频道，预计 3~6 分钟。' % len(CHANNELS))
    print()

    results = []
    with sync_playwright() as p:
        # 用系统已装的 Edge，避免再下载一个 Chromium（几百 MB）
        browser = None
        for kwargs in (
            {'channel': 'msedge'},
            {'channel': 'chrome'},
            {},                      # 退回到 playwright 自带的 chromium
        ):
            try:
                browser = p.chromium.launch(headless=True, **kwargs)
                print('  浏览器: %s' % (kwargs.get('channel') or 'playwright chromium'))
                break
            except Exception as e:
                print('  启动失败 %s: %s' % (kwargs, str(e)[:80]))
        if browser is None:
            print('  找不到可用浏览器。请先装 Edge/Chrome，或运行：')
            print('      python -m playwright install chromium')
            sys.exit(1)

        ctx = browser.new_context(
            user_agent=('Mozilla/5.0 (Windows NT 10.0; Win64; x64) '
                        'AppleWebKit/537.36 (KHTML, like Gecko) '
                        'Chrome/131.0.0.0 Safari/537.36'),
            viewport={'width': 1280, 'height': 720},
            locale='zh-CN',
        )
        for slug, name in CHANNELS:
            page = ctx.new_page()
            try:
                results.append(fetch_one(page, slug, name))
            finally:
                page.close()

        ctx.close()
        browser.close()

    # ---------- 汇总 ----------
    print()
    print('=' * 74)
    print('  结果')
    print('=' * 74)
    ok = [r for r in results if r['good']]
    for r in results:
        if r['good']:
            print('  ✅ %-20s %s' % (r['name'], r['good']))
        else:
            print('  ❌ %-20s %s' % (r['name'], r['error'] or '未找到'))
            for u in r['urls'][:3]:
                print('        （截到但不是理想地址）%s' % u[:100])

    print()
    print('  成功 %d / %d' % (len(ok), len(results)))

    with open(OUT, 'w', encoding='utf-8') as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    print()
    print('  已保存到： %s' % OUT)
    print('  把这个文件发我，我把它内置进 App。')


if __name__ == '__main__':
    main()
