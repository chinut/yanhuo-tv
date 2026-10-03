"""
自动抓取央视各频道的直播直连地址（第二版）。

## 为什么有第二版

第一版的问题是**没耐心**：每个频道只打开一次、等 45 秒拿不到就走人。
用户手动在浏览器里是**反复刷新直到播放器真的切到那个频道**，
所以他能抓到 CCTV-9 / CCTV-14，我的脚本抓不到（拿到的是 cctv13 的流）。

实测证据：用户给的
    CCTV-9   https://ldocctvwbcdcnc.v.wscdns.com/ldocctvwbcd/cdrmldcctv9_1_720P/playlist.m3u8?wsApp=HLS
    CCTV-14  https://ldocctvwbcdbd.a.bdydns.com/ldocctvwbcd/cdrmldcctv14_1/index.m3u8?BR=td
都是可用的（实测 HTTP 200 且有分片）。

第二版的做法：
  · 每个频道**最多试 N 轮**，每轮重新加载页面（等价于用户手动刷新）
  · 每轮里**持续轮询**，一旦截到"频道号对得上"的流就成功
  · 记录所有形态（`index.m3u8?BR=` / `_720P/playlist.m3u8` / ...）

## 用法

    python tools\\fetch_cctv_urls.py                  # 抓全部 18 个
    python tools\\fetch_cctv_urls.py cctv9 cctv14     # 只抓指定的
"""
import json
import os
import re
import sys
import time

try:
    from playwright.sync_api import sync_playwright
except ImportError:
    print('缺少 playwright。请先运行： python -m pip install playwright')
    sys.exit(1)

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OUT = os.path.join(ROOT, 'cctv_urls.json')

CHANNELS = [
    ('cctv1', 'CCTV-1 综合'), ('cctv2', 'CCTV-2 财经'),
    ('cctv3', 'CCTV-3 综艺'), ('cctv4', 'CCTV-4 中文国际'),
    ('cctv5', 'CCTV-5 体育'), ('cctv5plus', 'CCTV-5+ 体育赛事'),
    ('cctv6', 'CCTV-6 电影'), ('cctv7', 'CCTV-7 国防军事'),
    ('cctv8', 'CCTV-8 电视剧'), ('cctv9', 'CCTV-9 纪录'),
    ('cctv10', 'CCTV-10 科教'), ('cctv11', 'CCTV-11 戏曲'),
    ('cctv12', 'CCTV-12 社会与法'), ('cctv13', 'CCTV-13 新闻'),
    ('cctv14', 'CCTV-14 少儿'), ('cctv15', 'CCTV-15 音乐'),
    ('cctv16', 'CCTV-16 奥林匹克'), ('cctv17', 'CCTV-17 农业农村'),
]

# 央视自己的直播 CDN。**排除**海外转发（那些国内用不了）。
GOOD_HOST = re.compile(
    r'(myqcloud\.com|kcdnvip\.com|bdydns\.com|volcfcdn\.com|wscdns\.com|'
    r'cntv\.cn|qcloud)', re.I)
BAD_HOST = re.compile(r'(doubleclick|google|baidu|umeng|sentry|admaster|'
                      r'data\.cctv\.com)', re.I)

ROUNDS = 3          # 每个频道最多试几轮
ROUND_WAIT_S = 40   # 每轮最多等多久


def slug_to_ch(slug: str) -> str:
    return slug.replace('cctv', '').replace('plus', '+')


def channel_in_url(u: str):
    """从地址里推频道号。推不出来返回 None。"""
    m = re.search(r'cdrmldcctv(\d+)(plus)?', u, re.I)
    if m:
        return m.group(1) + ('+' if m.group(2) else '')
    m = re.search(r'[/_]cctv(\d+)(plus)?[/_]', u, re.I)
    if m:
        return m.group(1) + ('+' if m.group(2) else '')
    m = re.search(r'cctv(\d+)(plus)?', u, re.I)
    if m:
        return m.group(1) + ('+' if m.group(2) else '')
    return None


def is_media(u: str) -> bool:
    if '.m3u8' not in u.lower():
        return False
    if BAD_HOST.search(u):
        return False
    return bool(GOOD_HOST.search(u))


def fetch_one(ctx, slug: str, name: str) -> dict:
    """反复尝试，直到截到"频道号对得上"的流。"""
    want = slug_to_ch(slug)
    url = 'https://tv.cctv.com/live/%s/' % slug
    all_urls = []
    result = {'slug': slug, 'name': name, 'page': url,
              'urls': [], 'good': None, 'error': None, 'rounds': 0}

    for rnd in range(1, ROUNDS + 1):
        result['rounds'] = rnd
        page = ctx.new_page()
        seen = []

        def on_req(req, seen=seen):
            u = req.url
            if is_media(u):
                seen.append(u)

        page.on('request', on_req)
        try:
            # 每轮换一个查询串，避免命中旧频道的缓存
            page.goto('%s?r=%d' % (url, int(time.time() * 1000) % 100000),
                      timeout=45000, wait_until='domcontentloaded')

            deadline = time.time() + ROUND_WAIT_S
            while time.time() < deadline:
                page.wait_for_timeout(1000)
                if any(channel_in_url(u) == want for u in seen):
                    page.wait_for_timeout(2500)   # 多收一会儿其它档位
                    break
        except Exception as e:
            result['error'] = '%s: %s' % (type(e).__name__, str(e)[:90])
        finally:
            try:
                page.remove_listener('request', on_req)
            except Exception:
                pass
            page.close()

        all_urls.extend(seen)
        mine = [u for u in set(all_urls) if channel_in_url(u) == want]
        if mine:
            mine.sort(key=lambda u: (0 if '/index.m3u8' in u else 1, len(u)))
            result['good'] = mine[0]
            result['urls'] = sorted(set(all_urls))
            print('    ✅ 第 %d 轮抓到 %s' % (rnd, mine[0][:96]))
            return result
        print('    …第 %d 轮没抓到本频道的流，重试' % rnd)

    result['urls'] = sorted(set(all_urls))
    got = sorted({channel_in_url(u) for u in all_urls if channel_in_url(u)})
    result['error'] = '试了 %d 轮都没抓到本频道（截到的是 %s）' % (
        ROUNDS, ','.join(got) or '无')
    return result


def main():
    argv = [a.lower() for a in sys.argv[1:]]
    todo = [c for c in CHANNELS if not argv or c[0] in argv]

    print('=' * 74)
    print('  央视直播地址抓取（第二版：耐心重试）')
    print('=' * 74)
    print('  频道 %d 个，每个最多试 %d 轮、每轮等 %d 秒'
          % (len(todo), ROUNDS, ROUND_WAIT_S))
    print()

    results = []
    with sync_playwright() as p:
        browser = None
        for kw in ({'channel': 'msedge'}, {'channel': 'chrome'}, {}):
            try:
                browser = p.chromium.launch(headless=True, **kw)
                print('  浏览器: %s\n' % (kw.get('channel') or 'chromium'))
                break
            except Exception:
                continue
        if browser is None:
            print('  没有可用浏览器，请先装 Edge/Chrome')
            sys.exit(1)

        ctx = browser.new_context(
            user_agent=('Mozilla/5.0 (Windows NT 10.0; Win64; x64) '
                        'AppleWebKit/537.36 (KHTML, like Gecko) '
                        'Chrome/131.0.0.0 Safari/537.36'),
            viewport={'width': 1280, 'height': 720},
            locale='zh-CN',
        )
        for slug, name in todo:
            print('  [%s] %s' % (name, 'https://tv.cctv.com/live/%s/' % slug))
            results.append(fetch_one(ctx, slug, name))
        ctx.close()
        browser.close()

    print()
    print('=' * 74)
    print('  结果')
    print('=' * 74)
    ok = 0
    for r in results:
        if r['good']:
            ok += 1
            print('  ✅ %-20s %s' % (r['name'], r['good']))
        else:
            print('  ❌ %-20s %s' % (r['name'], r['error']))
    print()
    print('  成功 %d / %d' % (ok, len(results)))

    # 合并进已有结果（方便分次抓）
    merged = {}
    if os.path.exists(OUT):
        try:
            for r in json.load(open(OUT, encoding='utf-8')):
                merged[r['slug']] = r
        except Exception:
            pass
    for r in results:
        merged[r['slug']] = r
    json.dump(list(merged.values()), open(OUT, 'w', encoding='utf-8'),
              ensure_ascii=False, indent=2)
    print('  已保存: %s' % OUT)


if __name__ == '__main__':
    main()
