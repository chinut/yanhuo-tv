"""
IPTV 源扫描器 —— 给朋友在家里电脑上跑，把结果发回来。

# 为什么需要一个"外人友好"的工具

IPTV 源是**运营商 + 地区**限定的：
电信的源在移动宽带上多半不通，广东的源在北京可能不通。
所以"在我电脑上测可用"毫无意义 —— **必须在朋友家那条宽带上测**。

这个脚本就是干这个的。设计目标是**没有任何技术背景的人也能跑起来**。

# 判据（这是整个工具的核心）

我前面几轮踩过的坑，全在这里纠正了：

| 错误判据 | 后果 |
|---|---|
| HTTP 200 就算可用 | 央视加密源也能取到，但永远花屏 |
| ffmpeg 能解码就算可用 | 音频源、灰帧也能通过 |
| 解出一帧就算可用 | **灰色空帧也能通过**（真实踩过） |

**唯一有效的判据**（已在大批真实数据上验证）：

1. 有视频轨道
2. 解出 3 帧
3. **std > 10** —— 不是纯色/灰屏
4. **帧差 > 1.5** —— 画面在动（直播一定是动的）

区分度很大：央视加密源 std 0.0~1.7、帧差 0.00；
正常 IPTV 源 std 23~60、帧差 2~47。

# 用法

    run.bat            （双击，自动装依赖并运行）

或者：

    python iptv_scan.py --list "你的播放列表.m3u"
    python iptv_scan.py                       （用内置的公开源列表）
"""
import argparse
import concurrent.futures as cf
import io
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.request
from collections import defaultdict

# ---------------- 依赖检查（给朋友看的友好提示）----------------

try:
    import imageio_ffmpeg
except ImportError:
    print()
    print('=' * 70)
    print('  缺少组件，请先运行（双击 run.bat 会自动装）：')
    print('      pip install imageio-ffmpeg pillow')
    print('=' * 70)
    sys.exit(1)

try:
    from PIL import Image, ImageChops, ImageStat
except ImportError:
    print()
    print('  缺少 Pillow，请运行： pip install pillow')
    sys.exit(1)

FFMPEG = imageio_ffmpeg.get_ffmpeg_exe()
UA = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
      '(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36')
TMP = tempfile.gettempdir()
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, 'iptv_result.json')

# ---------------- 内置的公开源列表 ----------------
# 这些都是社区维护的，但**可用性因地区/运营商而异** —— 所以必须实测。
BUILTIN_LISTS = [
    ('vbskycn/iptv',
     'https://raw.githubusercontent.com/vbskycn/iptv/master/tv/iptv4.m3u'),
    ('vbskycn/iptv (jsDelivr 镜像)',
     'https://cdn.jsdelivr.net/gh/vbskycn/iptv@master/tv/iptv4.m3u'),
    ('iptv-org 中国',
     'https://iptv-org.github.io/iptv/countries/cn.m3u'),
    ('YanG-1989/m3u',
     'https://raw.githubusercontent.com/YanG-1989/m3u/main/Gather.m3u'),
    ('Kimentanm/aptv',
     'https://raw.githubusercontent.com/Kimentanm/aptv/master/m3u/iptv.m3u'),
    ('best-fan/iptv-sources',
     'https://raw.githubusercontent.com/best-fan/iptv-sources/main/cn_all.m3u'),
    ('zhangbin0301/iptv2025',
     'https://raw.githubusercontent.com/zhangbin0301/iptv2025/main/iptv.m3u'),
    ('jiandantv/IPTV2025',
     'https://raw.githubusercontent.com/jiandantv/IPTV2025/main/iptv4.m3u'),
    # 下面这些是常见的"本地运营商"源列表，命中率取决于地区
    ('常用 IPTV 汇总 A',
     'https://raw.githubusercontent.com/imDazui/Tvlist-awesome-m3u-m3u8/'
     'master/m3u/%E5%A4%AE%E8%A7%86%E5%8F%B0.m3u'),
]

# 判据阈值（和我的实测数据对齐）
MIN_STD = 10.0        # 画面必须有细节（纯色图 std≈0）
MIN_DIFF = 1.5        # 帧间必须有变化（静帧/灰屏 ≈0）


def log(msg):
    print(msg, flush=True)


def fetch_text(url, timeout=25):
    req = urllib.request.Request(url)
    req.add_header('User-Agent', UA)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.read().decode('utf-8', 'replace')
    except Exception:
        return ''


def parse_m3u(text):
    """解析 m3u：返回 [(频道名, 地址)]。同时支持带属性的 EXTINF。"""
    out, name = [], None
    for line in text.split('\n'):
        s = line.strip()
        if s.startswith('#EXTINF'):
            m = re.search(r',(.+)$', s)
            name = m.group(1).strip() if m else '未命名'
        elif s and not s.startswith('#') and name:
            out.append((name, s))
            name = None
    return out


def verify(url, timeout=25):
    """
    三重验证：有视频轨道 + 不灰 + 画面在动。

    返回 dict（可用）或 None（不可用）。
    """
    tag = abs(hash(url)) % 10 ** 9
    out = os.path.join(TMP, 'iptv_%d_%%d.jpg' % tag)
    cmd = [
        FFMPEG, '-hide_banner', '-loglevel', 'error',
        '-user_agent', UA,
        '-rw_timeout', str(timeout * 1_000_000),
        '-ss', '4',            # 跳过开头（有些流开头是空画面）
        '-i', url,
        '-frames:v', '3',      # 解 3 帧
        '-q:v', '4', '-y', out,
    ]
    try:
        subprocess.run(cmd, capture_output=True, timeout=timeout + 20)
    except Exception:
        return None

    frames = [os.path.join(TMP, 'iptv_%d_%d.jpg' % (tag, i))
              for i in (1, 2, 3)]
    frames = [f for f in frames
              if os.path.exists(f) and os.path.getsize(f) > 0]
    try:
        if len(frames) < 2:
            return None
        imgs = [Image.open(f).convert('L') for f in frames]
        std = ImageStat.Stat(imgs[0]).stddev[0]
        diffs = [ImageStat.Stat(ImageChops.difference(a, b)).mean[0]
                 for a, b in zip(imgs, imgs[1:])]
        max_diff = max(diffs)
        if std > MIN_STD and max_diff > MIN_DIFF:
            return {
                'std': round(std, 1),
                'diff': round(max_diff, 2),
                'size': max(os.path.getsize(f) for f in frames),
            }
        return None
    finally:
        for f in frames:
            try:
                os.remove(f)
            except Exception:
                pass


def fmt_name(n):
    """把频道名规整一下，去掉码率标注之类，方便归类。"""
    s = re.sub(r'\s*[\(\（][^)\）]*[)\）]\s*', ' ', n)
    s = re.sub(r'\s+', ' ', s).strip()
    return s


def main():
    ap = argparse.ArgumentParser(
        description='IPTV 源扫描器 —— 测出你家网络下真正能看的源')
    ap.add_argument('--list', action='append', default=[],
                    help='你自己的 m3u 文件或网址（可重复指定）')
    ap.add_argument('--filter', default='',
                    help='只测名字含这个词的频道，例如 --filter CCTV')
    ap.add_argument('--max-per-channel', type=int, default=6,
                    help='每个频道最多测几个地址（默认 6）')
    ap.add_argument('--jobs', type=int, default=6,
                    help='并发数（默认 6；网络差就调小）')
    args = ap.parse_args()

    log('=' * 70)
    log('  IPTV 源扫描器')
    log('=' * 70)
    log('')
    log('  它做的事：把直播源逐个用播放器真解一遍，')
    log('  只保留**真的能出画面**的（不是"地址能打开"就算）。')
    log('')
    log('  耗时取决于源的数量，通常 10~40 分钟。')
    log('  屏幕会一直有进度输出，不是卡住了。')
    log('')

    # ---------- 收集候选 ----------
    sources = list(args.list) + [u for _, u in BUILTIN_LISTS]
    labels = {}
    for lab, u in BUILTIN_LISTS:
        labels[u] = lab

    cands = defaultdict(list)
    for url in sources:
        if os.path.exists(url):
            text = io.open(url, encoding='utf-8', errors='replace').read()
            lab = os.path.basename(url)
        else:
            text = fetch_text(url)
            lab = labels.get(url, url[:40])
        if not text:
            log('  [跳过] %s（取不到）' % lab)
            continue
        items = parse_m3u(text)
        kept = 0
        for n, u in items:
            if '.m3u8' not in u and '.flv' not in u and '.ts' not in u:
                continue
            nm = fmt_name(n)
            if args.filter and args.filter.lower() not in nm.lower():
                continue
            if u not in cands[nm]:
                cands[nm].append(u)
                kept += 1
        log('  [读取] %-32s 共 %4d 条，收下 %d 条' % (lab, len(items), kept))

    if not cands:
        log('')
        log('  ❌ 没有拿到任何候选地址。')
        log('     如果全部显示"取不到"，可能是网络访问 GitHub 受限。')
        log('     解决办法：用 --list 指定你自己下载的 m3u 文件。')
        return 1

    total = sum(min(len(v), args.max_per_channel) for v in cands.values())
    log('')
    log('  候选频道 %d 个，准备测试 %d 个地址'
        % (len(cands), total))
    log('')

    # ---------- 逐个验证 ----------
    tasks = []
    for n, urls in cands.items():
        for u in urls[:args.max_per_channel]:
            tasks.append((n, u))

    good = defaultdict(list)
    done = 0
    t0 = time.time()
    with cf.ThreadPoolExecutor(max_workers=args.jobs) as ex:
        futs = {ex.submit(verify, u): (n, u) for n, u in tasks}
        for f in cf.as_completed(futs):
            n, u = futs[f]
            done += 1
            try:
                r = f.result()
            except Exception:
                r = None
            if r:
                good[n].append((u, r))
                log('  ✅ %-26s std=%-6s 帧差=%-6s %s'
                    % (n[:26], r['std'], r['diff'], u[:58]))
            if done % 25 == 0:
                el = time.time() - t0
                left = el / done * (len(tasks) - done)
                log('  …… %d/%d  已找到 %d 个可用  预计还要 %.0f 分钟'
                    % (done, len(tasks), sum(len(v) for v in good.values()),
                       left / 60))

    # ---------- 输出 ----------
    log('')
    log('=' * 70)
    log('  扫描完成')
    log('=' * 70)
    log('')
    log('  可用频道 %d 个，可用地址 %d 条'
        % (len(good), sum(len(v) for v in good.values())))
    log('')
    if good:
        log('  有这些频道能看：')
        for n in sorted(good, key=lambda x: (-len(good[x]), x)):
            log('    %-30s %d 个源' % (n[:30], len(good[n])))
    else:
        log('  ⚠️ 一个可用的都没有。')
        log('     可能原因：')
        log('       · 公开源列表在你这条宽带上都不通（很常见）')
        log('       · 需要你自己运营商的源，请用 --list 指定')

    # JSON（给我分析用，包含完整信息）
    result = {
        'scanned_at': time.strftime('%Y-%m-%d %H:%M:%S'),
        'total_tested': len(tasks),
        'total_ok': sum(len(v) for v in good.values()),
        'channels': {
            n: [{'url': u, **r} for u, r in v] for n, v in good.items()
        },
    }
    with io.open(OUT, 'w', encoding='utf-8') as fp:
        json.dump(result, fp, ensure_ascii=False, indent=1)

    # m3u（可直接填进 App 的「自定义直播源地址」）
    m3u_path = os.path.join(HERE, 'iptv_可用源.m3u')
    with io.open(m3u_path, 'w', encoding='utf-8', newline='\n') as fp:
        fp.write('#EXTM3U\n')
        for n in sorted(good):
            # 同一频道按码率/大小从低到高（老电视先用省资源的）
            for u, r in sorted(good[n], key=lambda x: x[1].get('size', 0)):
                fp.write('#EXTINF:-1 group-title="IPTV",%s\n' % n)
                fp.write(u + '\n')

    log('')
    log('  生成了两个文件（在脚本同一个文件夹里）：')
    log('     iptv_result.json    ← 把这个发给我')
    log('     iptv_可用源.m3u     ← 也可以直接填进 App 试')
    log('')
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        log('')
        log('  已手动中断。')
        sys.exit(1)
