# GitHub / Gitee Release 说明：清洗成纯技术内容
#
# 背景：之前的 release 说明里混进了对话口吻（"我"、"你反馈"、"抱歉"等），
# 这两个地址是给别人看的技术发布页，不该出现这些。
#
# 这个脚本把每个版本的说明重写为「技术内容 + 升级变动」，
# 然后 PATCH 回 GitHub、PUT 回 Gitee。
import ctypes
import json
import sys
import time
import urllib.error
import urllib.request
from ctypes import wintypes

REPO = 'chinut/yanhuo-tv'


# ---------------- 读 Windows 凭据管理器 ----------------
class CREDENTIAL(ctypes.Structure):
    _fields_ = [
        ('Flags', wintypes.DWORD),
        ('Type', wintypes.DWORD),
        ('TargetName', wintypes.LPWSTR),
        ('Comment', wintypes.LPWSTR),
        ('LastWritten', wintypes.FILETIME),
        ('CredentialBlobSize', wintypes.DWORD),
        ('CredentialBlob', ctypes.POINTER(ctypes.c_byte)),
        ('Persist', wintypes.DWORD),
        ('AttributeCount', wintypes.DWORD),
        ('Attributes', ctypes.c_void_p),
        ('TargetAlias', wintypes.LPWSTR),
        ('UserName', wintypes.LPWSTR),
    ]


def read_cred(target):
    a = ctypes.windll.advapi32
    p = ctypes.POINTER(CREDENTIAL)()
    if not a.CredReadW(target, 1, 0, ctypes.byref(p)):
        return None
    c = p.contents
    b = ctypes.string_at(c.CredentialBlob, c.CredentialBlobSize)
    a.CredFree(p)
    return b.decode('utf-16-le')


def http(method, url, headers=None, data=None, timeout=90, retries=3):
    last = None
    for attempt in range(retries):
        try:
            body = json.dumps(data).encode('utf-8') if data is not None else None
            r = urllib.request.Request(url, data=body, method=method)
            r.add_header('User-Agent', 'yanhuo-release')
            for k, v in (headers or {}).items():
                r.add_header(k, v)
            with urllib.request.urlopen(r, timeout=timeout) as resp:
                raw = resp.read().decode('utf-8', 'replace')
                return resp.status, (json.loads(raw) if raw.strip() else {})
        except urllib.error.HTTPError as e:
            raw = ''
            try:
                raw = e.read().decode('utf-8', 'replace')
            except Exception:
                pass
            last = (e.code, raw[:300])
            if e.code in (200, 201):
                return e.code, {}
            # 4xx 通常是参数/权限问题，重试无意义
            if 400 <= e.code < 500:
                return last
        except Exception as e:
            last = (-1, '%s: %s' % (type(e).__name__, e))
        time.sleep(2 * (attempt + 1))
    return last if last else (-1, 'unknown')


# ---------------- 每个版本的技术说明 ----------------
NOTES = {}

NOTES['v1.1.3'] = """## 修复

### 设置页：入口占位项占用确定键

**问题**：设置页所有开关选中后按确定无响应。

**原因**：设置页第一张卡片用 `entryFocusable` 注册了一个只有 key 的占位项，
既没有 `onActivate`，`boundsProvider` 又永远返回 `Rect.Zero`。
而 `TvFocusManager.register()` 里有「焦点为空或失效就 moveTo 新项」的逻辑，
导致进入设置页时该占位项抢到焦点：

- 按确定 → `onActivate` 为 null → `activate()` 返回 false
- 按方向键 → 几何导航跳过空矩形 → 焦点卡死

实测日志：
```
确定键未激活任何项：focused=entry:settings
```

**修复**：给占位项加 `onActivate`，转发给容器里离卡片顶部最近的可操作项。
新增 `TvFocusManager.firstActivatableAfter(exclude)` 实现该选择。

**选择逻辑的两次返工**（记录备查）：
1. 按 `r.top` 最小 → 选中页面最底部的按钮
2. 用 `dy = r.top - anchor.bottom` 且 `dy < 0` 时加分 →
   设置页那张卡很高（top=128，bottom=800），把控件全包在里面，
   dy 全是负数，越负惩罚越小，结果仍选到最靠下的项
3. 最终：按 `r.top - anchor.top` 排序，卡片内部的控件自然最优先
"""

NOTES['v1.1.4'] = """## 修复

### TV 键盘 / 家长密码 / 二维码面板改用应用内浮层

**问题**：TV 键盘按返回键无法关闭。

**原因**：Compose 的 `Dialog` 会开启**独立窗口接管按键**，
`MainActivity.dispatchKeyEvent` 收不到事件 ——
而本应用整套遥控器操作（方向键 / 确定键 / 返回键）全靠该 `dispatchKeyEvent`
转发给自定义的 `TvFocusManager`。按键进不来，弹窗内控件永远无法操作。

更新框早前已修复过同类问题，但另外三个 Dialog 未处理：

| 弹窗 | 症状 |
|---|---|
| TV 键盘 | 按返回关不掉 |
| 家长密码 | 收不到按键 |
| 二维码面板 | 收不到按键 |

**修复**：三个全部改为 `Box` 浮层。

同时调整 `SettingsScreen` 结构：将浮层从 `LazyColumn` 的 item 内
移到其外层。原因是浮层需 `fillMaxSize` 铺满整屏，
留在 item 内时拿到的是该 item 的边界，会被滚动容器裁成窄条。
现在根节点是 `Box`，`LazyColumn` 与三个浮层为兄弟节点。

**结果**：应用内已无任何 Compose `Dialog`。
"""

NOTES['v1.1.5'] = """## 修复

### 直播：老电视无法播放央视

**问题**：老设备看央视直播加载不出来。

**原因（两个问题叠加）**：

**1. 内置频道表 552 条中只有 2 条是真 m3u8**

其余全部是网页地址（`tv.cctv.com/live/xxx`、`yangshipin.cn/tv/home`），
都需要 WebView。老设备的 WebView 无法渲染央视频的现代前端，
这 550 条对其无效。

**2. 源记忆跳过了直连地址**

频道表中直连已排第一位，但「源记忆」记录的是旧版本的网页源，
下标指向了网页候选。实测日志：
```
主地址=...myqcloud...m3u8
选中  =tv.cctv.com/live/cctv13/
useWeb=true
```

**修复**：

1. 抓取央视自有 CDN 的直连 m3u8（腾讯云直播 / kcdnvip / bdydns / volcfcdn），
   覆盖 16 个频道，全部实测 HTTP 200
2. 直连地址插入频道表每个频道的**首位**，网页地址保留为兜底
3. 初始源选择改为**直连优先**：
   - 有直连候选 → 使用直连
   - 记忆的源本身是直连且在列表 → 尊重该记忆
   - 记忆的是网页源 → 忽略，不覆盖直连

**实测**：
```
BawanSrc: 频道=CCTV-13 新闻
          选中=https://ldncctvwbcdbd.a.bdydns.com/.../index.m3u8?BR=td
          useWeb=false
```
无任何 `TvWebPlayer` 日志，WebView 未被创建。

**新增工具**：`tools/fetch_cctv_urls.py`
"""

NOTES['v1.1.6'] = """## 新增

### 直播频道表：18 个频道 × 501 个直连源

覆盖全部 18 个央视频道，每个频道 31~33 个源，覆盖 7 台 CDN
（`volcfcdn` / `wscdns` / `myqcloud` / `bdydns` / `kcdnvip`）。

**清晰度档位**：

| 形态 | 数量 | 说明 |
|---|---|---|
| 480P playlist | 30 | 最省，低端设备首选 |
| BR=md (500k) | 75 | |
| BR=hd (800k) | 75 | |
| 720P playlist | 30 | |
| BR=ud (1.5M) | 75 | |
| BR=td (2M) | 75 | |
| 1080P playlist | 24 | |
| b=200- (master) | 117 | 自适应 |

频道表内每个频道的源按「最省 → 最高」排序，并**交错排列不同 CDN**，
使前几个源即覆盖多台 CDN。

**网页源（`tv.cctv.com` / `yangshipin.cn`）已全部移除**：
直连已足够，且低端设备无法运行 WebView。

## 修复

### 源列表可打开但无法选中

**原因**：`QualityPanel` 是一个**不可滚动的 `Column`**，一次绘制所有源。

- 此前每频道 3 个源 → 正好显示得下
- 现在 33 个源 → 面板高度超出屏幕，**屏外项既不可见也无法选中**

**修复**：

1. 列表改为 `LazyColumn` + 最大高度 300dp，可滚动
2. **光标移动时自动滚入可见范围**（遥控器无法拖动滚动条）
3. `←→` 在面板内也可移动光标（此前这两键被「切源」逻辑拦截，按下无响应）

**实测**：按三横键 → 面板显示「线路 6/7/8/9」，光标在线路 6；
下移 5 次 → 光标正常移动、列表自动滚动。
"""

NOTES['v1.1.7'] = """## 修复

### 视频渲染改用 TextureView

Media3 的 `PlayerView` 默认使用 `SurfaceView`（独立图层，由 SurfaceFlinger
单独合成）。在「缩放 / 裁剪 / 位于 Compose 布局内」等场景下，
部分定制 ROM 容易合成出错。`TextureView` 走普通 View 绘制流程，
与 UI 一同合成。

使用 Media3 公开 API：
- `setVideoTextureView(...)`
- `setEnableComposeSurfaceSyncWorkaround(true)`

**修复过程中发现的问题**：首次调用写在 `this.player = player` **之前**，
日志报「player 还没就绪，TextureView 未绑定」，实际未生效。
已改到赋值之后。

## 已知问题

### 模拟器视频花屏（非应用问题）

模拟器的 H.264 解码器无法输出到渲染表面：

```
[c2.goldfish.h264.decoder#309]
    setOutputSurface -- failed to set consumer usage (6/BAD_INDEX)
    query failed after returning 21 values (BAD_INDEX)
    Query output surface allocator returned 0 params => BAD_INDEX (6)
```

**判定为模拟器问题的依据**：

1. 模拟器上只有 `c2.goldfish.h264.decoder`（goldfish 为模拟器虚拟硬件平台），
   真机使用 `c2.android.avc.decoder` 或厂商硬解
2. `OMX.google.h264.decoder` 存在但不支持 surface 输出
3. 切换 GPU 模式（auto → host）无效，报同样的 `BAD_INDEX`
4. 同样的花屏在网页源播放路径（`TvWebPlayerView`）上也出现过 ——
   两条代码路径唯一共同点即该解码器

流本身正常：`ExoPlayer state=READY playing=true buf=12s video=854x480 err=none`。

详见 `docs/模拟器花屏-排查结论.md`。
"""


def main():
    only = sys.argv[1:] or sorted(NOTES.keys())
    gh_token = read_cred('git:https://github.com')
    gt_token = read_cred('git:https://gitee.com')
    print('GitHub 凭据: %s' % ('有' if gh_token else '没有'))
    print('Gitee  凭据: %s' % ('有' if gt_token else '没有'))
    print()

    for tag in only:
        body = NOTES.get(tag)
        if not body:
            print('  跳过 %s（没有预置说明）' % tag)
            continue
        print('=== %s ===' % tag)

        # ---- GitHub：PATCH release ----
        if gh_token:
            hh = {'Authorization': 'token ' + gh_token,
                  'Content-Type': 'application/json'}
            st, rel = http('GET',
                           'https://api.github.com/repos/%s/releases/tags/%s'
                           % (REPO, tag), hh)
            if isinstance(rel, dict) and rel.get('id'):
                st2, _ = http('PATCH',
                              'https://api.github.com/repos/%s/releases/%s'
                              % (REPO, rel['id']), hh,
                              {'body': body, 'name': '焰火TV ' + tag})
                print('  GitHub: %s' % ('已更新' if st2 in (200, 201)
                                        else '失败 %s' % st2))
            else:
                print('  GitHub: 找不到该 release（%s）' % st)

        # ---- Gitee：PATCH release ----
        if gt_token:
            st, rls = http('GET',
                           'https://gitee.com/api/v5/repos/%s/releases'
                           '?access_token=%s&per_page=100' % (REPO, gt_token))
            rid = None
            if isinstance(rls, list):
                for r in rls:
                    if r.get('tag_name') == tag:
                        rid = r.get('id')
                        break
            if rid:
                st2, _ = http('PATCH',
                              'https://gitee.com/api/v5/repos/%s/releases/%s'
                              % (REPO, rid),
                              {'Content-Type': 'application/json'},
                              {'access_token': gt_token, 'body': body,
                               'name': '焰火TV ' + tag, 'tag_name': tag})
                print('  Gitee : %s' % ('已更新' if st2 in (200, 201)
                                        else '失败 %s' % st2))
            else:
                print('  Gitee : 找不到该 release（%s）' % st)
        print()


if __name__ == '__main__':
    main()
