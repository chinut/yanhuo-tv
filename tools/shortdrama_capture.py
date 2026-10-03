"""
短剧 App 抓包工具 —— mitmproxy 插件。

由 tools\\shortdrama-capture.bat 启动，不要直接运行本文件。

## 为什么要抓包

那个短剧 App 是 Flutter 编译产物：Java 层只有 MainActivity 和 R.java，
业务逻辑全在 libapp.so（Dart 机器码）与 libduanju_core.so（Go 机器码）。
它的接口都需要 sign 签名，静态分析取不出来 —— 直接请求返回 404/403。

**但它跑起来时一定会把真实请求发出去。** 把那些请求抓下来，就能拿到
接口地址、参数、以及签名长什么样。这是唯一走得通的路。
"""
import json
import os
import re
import socket
import sys
import time

try:
    from mitmproxy import http
except ImportError:
    raise SystemExit(
        '缺少 mitmproxy。请运行 tools\\shortdrama-capture.bat（会自动安装）。'
    )

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OUT_DIR = os.path.join(ROOT, 'captures')
os.makedirs(OUT_DIR, exist_ok=True)

# 只记录和短剧相关的，避免被系统流量淹没
INTERESTING = re.compile(
    r'huangju|hongguo|duanju|playlet|drama|9ddm|qmplaylet|ygdj7|'
    r'lkkwip|xzyx168|kuaikaw|shytkjgs|whjzjx|ediayikma|999888456',
    re.I,
)
PATH_HINT = re.compile(r'/(api/)?(playlet|drama|chapter|search|home|detail|episode)', re.I)
SKIP_HOST = re.compile(
    r'(google|gstatic|android\.com|googleapis|firebase|crashlytics|'
    r'miui|xiaomi|mipay|doubleclick|umeng|bugly|sentry|facebook|'
    r'gvt1|gvt2|ggpht|msftncsi|connectivitycheck)',
    re.I,
)

HDR_HINT = re.compile(
    r'sign|token|auth|key|secret|device|version|app|os|user-agent|'
    r'referer|origin|x-|cookie',
    re.I,
)


def local_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(('8.8.8.8', 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return '(查不到，请在命令行运行 ipconfig 自己看)'


def print_instructions(out_path: str) -> None:
    """在 mitmdump 启动前打印操作指引。

    为什么不用 addon 的 running() 钩子：无头模式（mitmdump）下那个钩子
    不一定触发，实测确实没打印出来。直接在启动前打印最可靠。
    """
    ip = local_ip()
    line = '=' * 74
    print()
    print(line)
    print('  短剧 App 抓包已就绪')
    print(line)
    print()
    print('  【第 1 步】让手机 / 电视走这台电脑的代理')
    print('      手机：设置 → WLAN → 长按当前网络 → 修改网络 → 代理「手动」')
    print('            主机名 = %s' % ip)
    print('            端口   = 8088')
    print('      保存后手机可能短暂断网，是正常的。')
    print()
    print('  【第 2 步】给设备装证书（抓 HTTPS 必须，只需一次）')
    print('      设备浏览器打开： http://mitm.it')
    print('      按提示下载并安装 Android 证书')
    print()
    print('  【第 3 步】打开短剧 App，点开几部剧、进详情、开始播放')
    print('      多试几次，让接口尽量都发出来。')
    print()
    print('  抓到的内容实时显示在这里，同时保存到：')
    print('      %s' % out_path)
    print()
    print('  停止：按 Ctrl+C')
    print(line)
    print(flush=True)


def _pretty(body: bytes, limit: int = 2000) -> str:
    if not body:
        return '(空)'
    try:
        txt = body.decode('utf-8')
    except Exception:
        return '(二进制 %d 字节)' % len(body)
    try:
        return json.dumps(json.loads(txt), ensure_ascii=False, indent=2)[:limit]
    except Exception:
        return txt[:limit]


class Capture:
    def __init__(self):
        self.path = os.path.join(OUT_DIR, 'requests.log')
        self.f = open(self.path, 'a', encoding='utf-8')
        self.n = 0
        self.f.write('\n\n===== 抓包会话 %s =====\n'
                     % time.strftime('%Y-%m-%d %H:%M:%S'))

    # ---------- 判断是否关心这条请求 ----------
    @staticmethod
    def _hit(flow: http.HTTPFlow) -> bool:
        host = flow.request.pretty_host
        if SKIP_HOST.search(host):
            return False
        return bool(
            INTERESTING.search(host) or PATH_HINT.search(flow.request.path)
        )

    def request(self, flow: http.HTTPFlow):
        if not self._hit(flow):
            return
        self.n += 1
        n = self.n
        raw_path = flow.request.path

        head = '[%d] %s  %s%s' % (
            n, flow.request.method, flow.request.pretty_host,
            raw_path.split('?')[0],
        )
        hdrs = {k: v for k, v in flow.request.headers.items()}
        key_hdrs = {k: v for k, v in hdrs.items() if HDR_HINT.search(k)}

        body_txt = ''
        if flow.request.content:
            try:
                body_txt = flow.request.content.decode('utf-8')
            except Exception:
                body_txt = '(二进制 %d 字节)' % len(flow.request.content)

        print('\n' + '=' * 74, flush=True)
        print(head, flush=True)
        print('  完整路径: %s' % raw_path, flush=True)
        if key_hdrs:
            print('  关键请求头:', flush=True)
            for k in sorted(key_hdrs):
                print('      %s: %s' % (k, str(key_hdrs[k])[:170]), flush=True)
        if body_txt:
            print('  请求体  : %s' % body_txt[:700], flush=True)

        self.f.write('\n' + '=' * 74 + '\n')
        self.f.write(head + '\n')
        self.f.write('完整路径: %s\n' % raw_path)
        self.f.write('请求头: ' + json.dumps(hdrs, ensure_ascii=False, indent=2) + '\n')
        if body_txt:
            self.f.write('请求体: ' + body_txt + '\n')
        self.f.flush()

    def response(self, flow: http.HTTPFlow):
        if not self._hit(flow):
            return
        code = flow.response.status_code
        body = flow.response.content or b''
        pretty = _pretty(body)

        print('  -> HTTP %d（%d 字节）' % (code, len(body)), flush=True)
        print('  响应: %s' % pretty[:500].replace('\n', ' '), flush=True)

        self.f.write('响应: HTTP %d\n' % code)
        self.f.write('响应体: ' + pretty + '\n')
        self.f.flush()


# ---------- 模块加载时就打印指引 ----------
# addon 模块由 mitmdump 导入，所以这里执行等于"启动时打印"。
print_instructions(os.path.join(OUT_DIR, 'requests.log'))

addons = [Capture()]
