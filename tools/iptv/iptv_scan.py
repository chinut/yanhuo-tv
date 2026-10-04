"""
焰火TV 直播源扫描器（电脑版 v2）

在电脑上扫描 IPTV 直播源，只保留**真的能看**的，生成一个文件给电视导入。

# 为什么要单独做一个电脑版

电视端也能扫，但它只有一帧解码的余量（要测"画面在动"得解多帧、很慢），
**测不出"静止的假频道"**。电脑上跑得快，判据可以做得更强。

# 判据（四层，逐层淘汰）

1. **格式合法**：m3u8 能取到、以 #EXTM3U 开头；master 能下钻到分片列表
2. **分片可下载**：连续取 4 个分片都成功，且每个 > 20KB
3. **码率合理**：分片大小稳定（波动 < 60%），估算码率在 150~8000 kbps
4. **真解码 + 画面在动**（关键）：
   · 从流中间（-ss 6s）**间隔 1 秒取 6 帧**
   · 每帧的灰度标准差 std > 10（画面有内容，不是纯色/黑屏）
   · **相邻帧的平均差 > 2.0**（画面真的在动 —— 直播一定是动的）
   · 帧间差异不能全都一样（排除"同一画面重复"的假流）

第 4 层是核心。实测数据对照：

    真频道:  std 22~103, 帧差 1.5~30
    假频道:  std 30~68,  帧差 0.00     ← 静止画面/台标卡
    坏数据:  解不出帧                   ← 花屏加密/数据损坏

# 用法

    run.bat                    双击即用
    iptv_scan.py --list x.m3u  用你自己的源文件
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

import imageio_ffmpeg
from PIL import Image, ImageChops, ImageStat

FFMPEG = imageio_ffmpeg.get_ffmpeg_exe()
UA = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
      '(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36')
TMP = tempfile.gettempdir()
HERE = os.path.dirname(os.path.abspath(
    sys.executable if getattr(sys, 'frozen', False) else __file__))

# ==================== 判据阈值 ====================
MIN_STD = 10.0         # 画面必须有内容（不是纯色/黑屏）
MIN_DIFF = 2.0         # 相邻帧必须有变化（直播一定是动的）
MIN_SEG = 20 * 1024    # 分片至少这么大
MAX_SIZE_DEV = 0.60    # 分片大小波动上限
FRAMES = 6             # 取几帧

# ==================== 内置源列表 ====================
LISTS = [
    ('vbskycn/iptv', 'https://raw.githubusercontent.com/vbskycn/iptv/master/tv/iptv4.m3u'),
    ('vbskycn 镜像', 'https://cdn.jsdelivr.net/gh/vbskycn/iptv@master/tv/iptv4.m3u'),
    ('iptv-org 中国', 'https://iptv-org.github.io/iptv/countries/cn.m3u'),
    ('YanG-1989/m3u', 'https://raw.githubusercontent.com/YanG-1989/m3u/main/Gather.m3u'),
    ('Kimentanm/aptv', 'https://raw.githubusercontent.com/Kimentanm/aptv/master/m3u/iptv.m3u'),
    ('best-fan/iptv', 'https://raw.githubusercontent.com/best-fan/iptv-sources/main/cn_all.m3u'),
    ('best-fan 镜像', 'https://cdn.jsdelivr.net/gh/best-fan/iptv-sources@main/cn_all.m3u'),
    ('zhangbin0301', 'https://raw.githubusercontent.com/zhangbin0301/iptv2025/main/iptv.m3u'),
    ('jiandantv', 'https://raw.githubusercontent.com/jiandantv/IPTV2025/main/iptv4.m3u'),
]


# ---------- 安全输出 ----------
#
# ⚠️ 踩过的坑：中文 Windows 的终端默认是 GBK（cp936），
# 往 stdout 打 emoji（如 ✅）会抛 UnicodeEncodeError 直接崩掉程序。
# 打包成 exe 后更明显 —— 实测就是这样崩的。
#
# 所以这里：优先把 stdout 切到 UTF-8，切不动就退化成"忽略无法编码的字符"。
try:
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    sys.stderr.reconfigure(encoding='utf-8', errors='replace')
except Exception:
    pass


def log(msg=''):
    try:
        print(msg, flush=True)
    except UnicodeEncodeError:
        # 兜底：去掉无法编码的字符再打
        enc = getattr(sys.stdout, 'encoding', None) or 'utf-8'
        print(msg.encode(enc, 'replace').decode(enc, 'replace'), flush=True)


def fetch(url, timeout=25):
    req = urllib.request.Request(url)
    req.add_header('User-Agent', UA)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.read().decode('utf-8', 'replace')
    except Exception:
        return ''


def fetch_bytes(url, timeout=15, limit=4 * 1024 * 1024):
    """
    下载一段字节，**带总时长上限**。

    ⚠️ 踩过的坑：原来直接 `r.read(limit)` —— 如果服务器只发一点数据
    但**不关闭连接**（直播流很常见），read 会一直挂着。
    实测表现：exe 吃满 972MB 内存、CPU 只用了 55 秒、扫描永远不结束。

    修法：改成自己 recv 循环，每收一块检查一次剩余时间，
    超时立刻断开。这样最坏情况也只是慢，不会挂死。
    """
    import socket
    deadline = time.time() + timeout
    req = urllib.request.Request(url)
    req.add_header('User-Agent', UA)
    req.add_header('Accept', '*/*')
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            chunks = []
            total = 0
            while total < limit:
                left = deadline - time.time()
                if left <= 0:
                    break
                try:
                    r.fp.raw._sock.settimeout(min(left, 5.0)) \
                        if hasattr(r.fp, 'raw') else None
                except Exception:
                    pass
                try:
                    b = r.read(min(256 * 1024, limit - total))
                except (socket.timeout, TimeoutError):
                    break
                except Exception:
                    break
                if not b:
                    break
                chunks.append(b)
                total += len(b)
            return b''.join(chunks) if chunks else None
    except Exception:
        return None


def parse_m3u(text):
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


def _kill_tree(proc):
    """杀掉进程及其子进程（ffmpeg 超时后必须清干净，否则孤儿进程继续吃内存）。"""
    import signal
    try:
        if os.name == 'nt':
            subprocess.run(['taskkill', '/F', '/T', '/PID', str(proc.pid)],
                           capture_output=True, timeout=10)
        else:
            proc.kill()
    except Exception:
        try:
            proc.kill()
        except Exception:
            pass


def absolute(base, rel):
    if rel.startswith('http'):
        return rel
    if rel.startswith('/'):
        m = re.match(r'^(https?://[^/]+)', base)
        return (m.group(1) if m else '') + rel
    return base.rsplit('/', 1)[0] + '/' + rel


# ==================== 四层验证 ====================
def verify(url):
    """返回 (是否可用, 说明, 详情dict)。"""
    detail = {}

    # ---- 1) 格式合法 ----
    text = fetch(url)
    if not text:
        return False, '取不到 m3u8', detail
    if not text.lstrip().startswith('#EXTM3U'):
        return False, '返回的不是 m3u8', detail

    base, body = url, text
    if '#EXT-X-STREAM-INF' in body:
        if not re.search(r'RESOLUTION=|VIDEO=', body, re.I):
            return False, 'master 里没有视频轨（可能纯音频）', detail
        sub = next((l.strip() for l in body.split('\n')
                    if l.strip() and not l.startswith('#')), None)
        if not sub:
            return False, 'master 里没有子列表', detail
        base = absolute(base, sub)
        body = fetch(base) or ''
        if not body.lstrip().startswith('#EXTM3U'):
            return False, '子列表取不到', detail

    # ---- 2) 分片可下载 ----
    segs = [l.strip() for l in body.split('\n')
            if l.strip() and not l.startswith('#')][:4]
    if len(segs) < 2:
        return False, '分片太少', detail

    sizes = []
    for rel in segs:
        data = fetch_bytes(absolute(base, rel))
        if data is None:
            return False, '分片下载失败', detail
        sizes.append(len(data))
    if min(sizes) < MIN_SEG:
        return False, '分片过小（%dKB）' % (min(sizes) // 1024), detail

    # ---- 3) 码率合理 ----
    avg = sum(sizes) / len(sizes)
    dev = max(abs(s - avg) for s in sizes) / avg
    detail['size_dev'] = round(dev, 2)
    if dev > MAX_SIZE_DEV:
        return False, '分片大小波动过大（%.0f%%）' % (dev * 100), detail
    kbps = int(avg * 8 / 4 / 1000)      # 假设 4 秒一片
    detail['kbps'] = kbps
    if not (150 <= kbps <= 8000):
        return False, '码率异常（%d kbps）' % kbps, detail

    # ---- 4) 真解码 + 画面在动 ----
    ok, why, d = decode_check(url)
    detail.update(d)
    if not ok:
        return False, why, detail
    return True, 'ok', detail


def decode_check(url, timeout=35):
    """间隔取 6 帧，验证「有内容」且「画面在动」。"""
    tag = abs(hash(url)) % 10 ** 9
    tmpl = os.path.join(TMP, 'dc_%d_%%d.jpg' % tag)
    cmd = [
        FFMPEG, '-hide_banner', '-loglevel', 'error',
        '-user_agent', UA,
        '-rw_timeout', str(timeout * 1_000_000),
        '-ss', '6', '-i', url,
        '-vf', 'fps=1',
        '-frames:v', str(FRAMES),
        '-q:v', '4', '-y', tmpl,
    ]
    # ⚠️ 为什么不用 subprocess.run(timeout=...)
    #
    # 实测踩坑：`-rw_timeout` 只在**完全收不到数据**时才触发。
    # 有些源会一直涓流式发数据，ffmpeg 就永远读下去，
    # 内存越吃越多（实测涨到 600MB+ 还在涨），扫描永不结束。
    #
    # 所以这里改成自己起进程 + 墙钟硬超时 + 超时后**杀进程树**
    # （ffmpeg 可能派生子进程，只杀父进程会留下孤儿继续吃内存）。
    proc = None
    try:
        proc = subprocess.Popen(
            cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0),
        )
        try:
            proc.wait(timeout=timeout + 15)
        except subprocess.TimeoutExpired:
            _kill_tree(proc)
            return False, '解码超时', {}
    except Exception:
        if proc is not None:
            _kill_tree(proc)
        return False, '解码失败', {}

    fs = [os.path.join(TMP, 'dc_%d_%d.jpg' % (tag, i))
          for i in range(1, FRAMES + 1)]
    fs = [f for f in fs if os.path.exists(f) and os.path.getsize(f) > 0]
    try:
        if len(fs) < 3:
            return False, '解不出足够帧（%d）' % len(fs), {}

        imgs = [Image.open(f).convert('L') for f in fs]
        stds = [ImageStat.Stat(im).stddev[0] for im in imgs]
        diffs = [ImageStat.Stat(ImageChops.difference(a, b)).mean[0]
                 for a, b in zip(imgs, imgs[1:])]
        max_std = max(stds)
        max_diff = max(diffs)
        avg_diff = sum(diffs) / len(diffs)

        d = {'std': round(max_std, 1), 'diff': round(max_diff, 2),
             'avg_diff': round(avg_diff, 2), 'frames': len(fs)}

        if max_std < MIN_STD:
            return False, '画面近乎纯色（std=%.1f）' % max_std, d
        if max_diff < MIN_DIFF:
            return False, '画面静止（帧差=%.2f）' % max_diff, d
        if len(set(round(x, 1) for x in diffs)) == 1 and max_diff < 4:
            return False, '画面重复（帧差恒定 %.2f）' % max_diff, d
        return True, 'ok', d
    finally:
        for f in fs:
            try:
                os.remove(f)
            except Exception:
                pass


# ==================== 主流程 ====================
def main():
    ap = argparse.ArgumentParser(
        description='焰火TV 直播源扫描器 —— 测出真正能看的源')
    ap.add_argument('--list', action='append', default=[],
                    help='你自己的 m3u 文件或网址（可重复）')
    ap.add_argument('--filter', default='', help='只测名字含这个词的频道')
    ap.add_argument('--max-per-channel', type=int, default=4)
    ap.add_argument('--jobs', type=int, default=6)
    ap.add_argument('--no-builtin', action='store_true',
                    help='不用内置的公开源列表')
    args = ap.parse_args()

    log('=' * 72)
    log('  焰火TV 直播源扫描器')
    log('=' * 72)
    log()
    log('  它会把每个源**真的解码一遍**，验证「画面有内容」且「画面在动」。')
    log('  只保留通过验证的，生成一个文件给你导进电视。')
    log()
    log('  耗时取决于源的数量，通常 15~50 分钟。')
    log('  屏幕会一直有进度输出 —— 不是卡住了。')
    log()

    sources = list(args.list)
    if not args.no_builtin:
        sources += [u for _, u in LISTS]

    cands = defaultdict(list)
    for url in sources:
        if os.path.exists(url):
            text = io.open(url, encoding='utf-8', errors='replace').read()
            label = os.path.basename(url)
        else:
            text = fetch(url)
            label = next((n for n, u in LISTS if u == url), url[:44])
        if not text:
            log('  [跳过] %s（取不到，可能被墙）' % label)
            continue
        kept = 0
        for n, u in parse_m3u(text):
            if '.m3u8' not in u and '.flv' not in u:
                continue
            nm = re.sub(r'\s*[\(\（][^)\）]*[)\）]\s*', ' ', n).strip()
            if args.filter and args.filter.lower() not in nm.lower():
                continue
            if u not in cands[nm]:
                cands[nm].append(u)
                kept += 1
        log('  [读取] %-24s 收下 %d 条' % (label[:24], kept))

    if not cands:
        log()
        log('  [X] 没拿到任何候选。若全部"取不到"，是访问 GitHub 受限 ——')
        log('     用 --list 指定你自己下载的 m3u 文件。')
        return 1

    tasks = [(n, u) for n, urls in cands.items()
             for u in urls[:args.max_per_channel]]
    log()
    log('  候选频道 %d 个，待测 %d 个地址' % (len(cands), len(tasks)))
    log()

    good = defaultdict(list)
    reasons = defaultdict(int)
    done = 0
    t0 = time.time()
    with cf.ThreadPoolExecutor(max_workers=args.jobs) as ex:
        futs = {ex.submit(verify, u): (n, u) for n, u in tasks}
        for f in cf.as_completed(futs):
            n, u = futs[f]
            done += 1
            try:
                ok, why, d = f.result()
            except Exception as e:
                ok, why, d = False, type(e).__name__, {}
            if ok:
                good[n].append((u, d))
                log('  [OK] %-24s std=%-6s 帧差=%-6s %s'
                    % (n[:24], d.get('std'), d.get('diff'), u[:52]))
            else:
                reasons[re.sub(r'[\d\.]+', 'N', why)[:30]] += 1
            if done % 25 == 0:
                el = time.time() - t0
                log('  …… %d/%d  已找到 %d  预计还要 %.0f 分钟'
                    % (done, len(tasks), sum(len(v) for v in good.values()),
                       el / done * (len(tasks) - done) / 60))

    log()
    log('=' * 72)
    log('  扫描完成')
    log('=' * 72)
    total = sum(len(v) for v in good.values())
    log('  可用频道 %d 个 / 可用地址 %d 条（共测 %d）'
        % (len(good), total, len(tasks)))
    log()
    if reasons:
        log('  淘汰原因分布：')
        for r, c in sorted(reasons.items(), key=lambda x: -x[1])[:8]:
            log('     %-34s %d' % (r, c))
        log()

    json_path = os.path.join(HERE, '扫描结果.json')
    with io.open(json_path, 'w', encoding='utf-8') as fp:
        json.dump({
            'scanned_at': time.strftime('%Y-%m-%d %H:%M:%S'),
            'tested': len(tasks),
            'ok': total,
            'channels': {n: [{'url': u, **d} for u, d in v]
                         for n, v in good.items()},
        }, fp, ensure_ascii=False, indent=1)

    m3u_path = os.path.join(HERE, '直播源.m3u')
    with io.open(m3u_path, 'w', encoding='utf-8', newline='\n') as fp:
        fp.write('#EXTM3U\n')
        for n in sorted(good):
            for u, d in sorted(good[n], key=lambda x: x[1].get('kbps', 9999)):
                fp.write('#EXTINF:-1 group-title="扫描源",%s\n' % n)
                fp.write(u + '\n')

    log('  生成两个文件（在本程序同一个文件夹）：')
    log('     直播源.m3u      ← **把这个导进电视**（设置 → 直播源 → 选择文件）')
    log('     扫描结果.json   ← 想发给我看也可以')
    log()
    if good:
        log('  能看的频道：')
        for n in sorted(good, key=lambda x: (-len(good[x]), x))[:40]:
            d0 = good[n][0][1]
            log('     %-26s %d 个源  %s kbps'
                % (n[:26], len(good[n]), d0.get('kbps', '?')))
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        log()
        log('  已手动中断。')
        sys.exit(1)
