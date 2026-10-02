# 焰火TV 网络遥控协议 v1

> **给手机 App 开发者**：看这一份就够了。
> 把同目录下的 `YanhuoRemote.kt` 拷进你的工程即可直接用，不必自己实现协议。

---

## 这是什么

电视上的「焰火TV」在局域网里开了一个 HTTP 服务。
手机 App 通过它**当遥控器用**：上下左右、确定、返回、音量、媒体键、文字输入，
还能读回电视的当前状态。

**不需要配对、不需要投屏协议、不需要 WebSocket。** 纯 HTTP + JSON。

---

## 接入三步

### 1. 找到电视

同一个 WiFi 下，电视的 IP 就是它的局域网地址。

App 里的做法建议：
- 让用户手输 IP（写在设置里，最简单可靠）
- 或者复用你已有的局域网扫描能力

端口默认 **8899**（电视的「设置 → 手机网页调试 → 调试端口」可改）。

### 2. 拿口令（可选）

电视「设置 → 手机网页调试 → 手机口令」。
**默认是空的** —— 也就是不校验，直接能用。用户若在意安全才需要设。

带口令时，所有请求加 `?token=xxx`（POST 时为表单字段 `token=xxx`）。

### 3. 调用

```kotlin
val tv = YanhuoRemote(host = "192.168.1.23", port = 8899, token = "")

if (tv.ping()) {                      // 探测在不在
    tv.key(YanhuoRemote.Key.DOWN)     // 按「下」
    tv.key(YanhuoRemote.Key.OK)       // 按「确定」
    tv.volumeDelta(+2)                // 音量 +2
    tv.status()?.let { println("音量 ${it.volume}/${it.volumeMax}，界面 ${it.screen}") }
}
```

---

## HTTP 接口

所有响应都是 JSON，UTF-8。失败时 HTTP 状态码非 2xx，或 `ok=false`。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/remote/ping` | 探测在线。返回 `{"ok":true,"app":"焰火TV","protocol":1,"screen":"home"}` |
| GET | `/api/remote/status` | 当前状态（音量/界面/时间戳） |
| POST | `/api/remote/key` | 注入按键。参数 `keyCode`（数字）或 `key`（可读名），可选 `action=down\|up` |
| POST | `/api/remote/text` | 发送文字到当前输入框。参数 `text` |
| POST | `/api/remote/volume` | 调音量。参数 `value`（绝对值）或 `delta`（相对值） |
| POST | `/api/remote/mute` | 静音开关。参数 `muted=0\|1` |
| GET | `/api/remote/events` | 拉取**电视上真实按下**的按键。参数 `since`（毫秒时间戳）、`wait`（长轮询毫秒，0~20000） |

参数**既可以放 query，也可以放 POST 表单体**，两种都支持。

### 语义键名

`key` 参数支持这些可读名（也可直接传数字键码）：

| 名字 | 键码 | | 名字 | 键码 |
|---|---|---|---|---|
| `up` | 19 | | `play` | 126 |
| `down` | 20 | | `pause` | 127 |
| `left` | 21 | | `playpause` | 85 |
| `right` | 22 | | `stop` | 86 |
| `ok` / `enter` / `center` | 23 | | `next` | 87 |
| `back` | 4 | | `prev` | 88 |
| `menu` | 82 | | `forward` | 90 |
| `volup` | 24 | | `rewind` | 89 |
| `voldown` | 25 | | `mute` | 164 |
| `del` / `backspace` | 67 | | | |

### 响应示例

```json
// GET /api/remote/status
{"ok":true,"screen":"home","volume":8,"volumeMax":15,"muted":false,"now":1790944351009}

// POST /api/remote/key  (key=ok)
{"ok":true,"keyCode":23,"consumed":true}

// GET /api/remote/events?since=0
{"ok":true,"now":1790944352106,
 "events":[{"t":1790944351049,"keyCode":20},{"t":1790944351110,"keyCode":23}]}
```

---

## 设计要点（接入时会关心）

### 1. 传的是**键码**，不是"上/下"这种语义动作

这是刻意的。电视端的按键统一由 Activity 分发，**每个界面有自己的语义**：

- 首页：方向键移动焦点
- 播放页：↑↓ 换台、←→ 调进度
- 搜索页：字母键直接输入

如果协议只暴露"上一项/下一项"，那每加一个界面 TV 端都要改协议、手机端也要跟着改。
传键码则**天然继承全部行为** —— 电视上按 ↓ 会怎样，手机上发 ↓ 就完全一样。

**所以：手机端只需要做一个"虚拟遥控器"的按键布局，不用理解任何界面逻辑。**

### 2. 音量走**系统媒体音量**，不是 App 内部音量

和电视遥控器上的音量键是同一个。好处是手机显示的数字永远和电视一致，
不会出现"手机调到 5、电视实际是 12"。

调音量时电视**不弹系统音量条**（那个浮层会挡住画面，而且用户是拿手机在调，
不需要电视再提示一次）。

### 3. `now` 字段怎么用

`status` 和 `events` 都返回 `now`（电视端毫秒时间戳）。
把它原样回传给 `events?since=`，就能拿到增量事件，不会重复。

### 4. 长轮询而不是 WebSocket

`events?wait=15000` 会挂起最多 15 秒，有新按键立刻返回。

选长轮询的原因：手机端已经会发 HTTP，加 WebSocket 要多一套连接管理、
重连逻辑、心跳。长轮询用一个 `HttpURLConnection` 就够了，
对"遥控器"这种低频场景完全够用。

**建议**：只在遥控器页面可见时开长轮询；退到后台就停，省电。

### 5. 实体遥控器也能回显

`events` 返回的是**电视端所有按键**，包括用户拿实体遥控器按的。
所以手机界面能跟着更新，不会出现"手机显示的还是旧状态"。

---

## 客户端参考实现

同目录的 **`YanhuoRemote.kt`**：零依赖（JDK + `org.json`），
所有方法都是挂起函数、失败返回 null/false 不抛异常。

直接拷进你的工程，改一下 `package` 即可。

如果不用 Kotlin 协程，把 `withContext(Dispatchers.IO)` 去掉、
在子线程里调用即可，其余逻辑不变。

---

## 常见问题

**Q：`ping` 通但按键没反应？**
`consumed:false` 说明电视收到了但当前界面不处理这个键
（例如在首页按「播放」）。这是正常的。

**Q：能控制音量但界面不响应方向键？**
检查是不是在播放全屏页 —— 那里方向键有自己的语义（换台/调进度）。

**Q：电视 IP 会变吗？**
会（DHCP）。建议在电视上设静态 IP，或让用户重填。

**Q：需要电视端开启什么吗？**
「设置 → 允许手机调试」开着即可（**默认就是开的**）。
关掉后整个局域网服务会停，遥控和手机网页调试都不可用。
