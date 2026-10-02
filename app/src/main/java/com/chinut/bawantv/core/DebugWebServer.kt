package com.chinut.bawantv.core

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import com.chinut.bawantv.BawanApp
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob

/**
 * 手机网页调试服务（板块 D 的「扫码改配置」）。
 *
 * 电视上打字是反人类的，所以手机连同一个 WiFi、扫码打开一个网页就能改所有设置：
 * 订阅地址、低熵影视域名、直播源、UA、各类开关。
 *
 * 实现刻意保持极简：一个 ServerSocket + 手写 HTTP 解析，不引入任何 Web 框架，
 * 不依赖通知/前台服务，只在设置页打开时运行。
 */
object DebugWebServer {

    private const val TAG = "DebugWeb"

    private var scope: CoroutineScope? = null
    private var server: ServerSocket? = null
    private var acceptJob: Job? = null

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _port = MutableStateFlow(8899)
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private fun log(line: String) {
        val now = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA)
            .format(java.util.Date())
        _log.value = (listOf("[$now] $line") + _log.value).take(30)
    }

    /** 启动服务。已在运行则先停掉再按新端口启动。 */
    fun start(context: Context, port: Int) {
        stop()
        val app = context.applicationContext
        val sc = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = sc
        sc.launch {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port))
                server = ss
                _port.value = port
                _running.value = true
                log("服务已启动：${Qr.debugUrl(app, port)}")
                while (isActive && !ss.isClosed) {
                    val client = try {
                        ss.accept()
                    } catch (e: Exception) {
                        break
                    }
                    launch { handle(app, client) }
                }
            } catch (e: Exception) {
                _running.value = false
                log("启动失败：${e.message}")
            }
        }
    }

    fun stop() {
        runCatching { acceptJob?.cancel() }
        runCatching { server?.close() }
        runCatching { scope?.cancel() }
        acceptJob = null
        server = null
        scope = null
        _running.value = false
    }

    // ==================== HTTP 处理 ====================

    private fun handle(context: Context, socket: Socket) {
        socket.use { s ->
            runCatching {
                s.soTimeout = 15_000
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val rawPath = parts[1]

                // 读取请求头（拿到 Content-Length 和 Cookie）
                var contentLength = 0
                var cookieToken = ""
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val lower = line.lowercase()
                    when {
                        lower.startsWith("content-length:") ->
                            contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0

                        lower.startsWith("cookie:") ->
                            cookieToken = line.substringAfter(':').trim()
                    }
                }

                // 读取 body
                val body = if (contentLength > 0) {
                    val buf = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val r = reader.read(buf, read, contentLength - read)
                        if (r <= 0) break
                        read += r
                    }
                    String(buf, 0, read)
                } else ""

                val path = rawPath.substringBefore('?')
                val query = rawPath.substringAfter('?', "")
                val out = s.getOutputStream()

                when {
                    path == "/" && method == "GET" ->
                        respond(out, 200, pageHome(context, query, cookieToken, null))
                    path == "/save" && method == "POST" -> {
                        val params = parseForm(body)
                        val token = params["token"].orEmpty()
                        if (token.isNotBlank()) {
                            BawanApp.prefs.debugToken = token
                        }
                        val expected = BawanApp.prefs.debugToken
                        if (expected.isNotBlank() && token != expected) {
                            respond(out, 200, pageHome(context, "", cookieToken, "口令不正确，修改未生效"))
                        } else {
                            BawanApp.prefs.applyRemote(params)
                            VodRepoBridge.invalidate()
                            log("已通过手机网页更新设置")
                            respond(
                                out, 200,
                                pageHome(context, "?ok=1", cookieToken, null)
                            )
                        }
                    }

                    path == "/api/state" && method == "GET" ->
                        respond(out, 200, jsonState(), "application/json; charset=utf-8")

                    // ==================== 网络遥控 API ====================
                    //
                    // 供手机 App（例如音乐 APP 里的"遥控器"页面）调用。
                    // 协议刻意做得极简：纯 HTTP + JSON，不需要 WebSocket，
                    // 因为手机端已经会发 HTTP 请求了，加长连接会平白增加复杂度。
                    //
                    // 鉴权：与网页调试共用同一个口令（prefs.debugToken）。
                    // 口令为空时**不校验**（家庭局域网，默认无口令，方便直接用）。
                    path == "/api/remote/ping" && method == "GET" ->
                        respond(
                            out, 200,
                            """{"ok":true,"app":"焰火TV","protocol":1,"screen":"${RemoteBus.screen()}"}""",
                            "application/json; charset=utf-8",
                        )

                    path == "/api/remote/status" && method == "GET" ->
                        respond(out, 200, remoteStatus(context), "application/json; charset=utf-8")

                    path == "/api/remote/key" -> {
                        if (!tokenOk(query, body)) {
                            respond(out, 403, """{"ok":false,"error":"token"}""", JSON_UTF8)
                        } else {
                            val p = parseForm(body)
                            // 键码可以直接给 keyCode（Android 键码），
                            // 也可以用 key 传语义名，两者都支持 —— 手机端写哪个都行
                            val code = p["keyCode"]?.toIntOrNull()
                                ?: keyNameToCode(p["key"].orEmpty())
                            val action = if (p["action"] == "up") KeyEvent.ACTION_UP
                            else KeyEvent.ACTION_DOWN
                            val consumed = if (code != 0) RemoteBus.injectKey(code, action) else false
                            respond(
                                out, 200,
                                """{"ok":${code != 0},"keyCode":$code,"consumed":$consumed}""",
                                JSON_UTF8,
                            )
                        }
                    }

                    path == "/api/remote/text" -> {
                        if (!tokenOk(query, body)) {
                            respond(out, 403, """{"ok":false,"error":"token"}""", JSON_UTF8)
                        } else {
                            val p = parseForm(body)
                            val text = p["text"].orEmpty()
                            var n = 0
                            text.forEach { ch ->
                                if (com.chinut.bawantv.ui.TextInputSink.dispatch(ch)) n++
                            }
                            respond(out, 200, """{"ok":true,"accepted":$n}""", JSON_UTF8)
                        }
                    }

                    path == "/api/remote/volume" -> {
                        if (!tokenOk(query, body)) {
                            respond(out, 403, """{"ok":false,"error":"token"}""", JSON_UTF8)
                        } else {
                            val p = parseForm(body)
                            val q = parseQuery(query)
                            val value = (p["value"] ?: q["value"])?.toIntOrNull()
                            val delta = (p["delta"] ?: q["delta"])?.toIntOrNull() ?: 0
                            if (value != null || delta != 0) {
                                RemoteBus.setVolume(context, value, delta)
                            }
                            respond(out, 200, remoteStatus(context), JSON_UTF8)
                        }
                    }

                    path == "/api/remote/mute" -> {
                        if (!tokenOk(query, body)) {
                            respond(out, 403, """{"ok":false,"error":"token"}""", JSON_UTF8)
                        } else {
                            val p = parseForm(body)
                            val q = parseQuery(query)
                            val want = (p["muted"] ?: q["muted"])?.let {
                                it == "1" || it.equals("true", true)
                            } ?: true
                            RemoteBus.setMuted(context, want)
                            respond(out, 200, remoteStatus(context), JSON_UTF8)
                        }
                    }

                    // 长轮询：手机端想知道"电视上刚才按了什么"（比如用户又拿起了实体遥控器）
                    path == "/api/remote/events" && method == "GET" -> {
                        val q = parseQuery(query)
                        val since = q["since"]?.toLongOrNull() ?: 0L
                        val waitMs = (q["wait"]?.toIntOrNull() ?: 0).coerceIn(0, 20_000)
                        val deadline = System.currentTimeMillis() + waitMs
                        // 有等待时间就轮询到有事件或超时为止（长轮询）
                        var ev = RemoteBus.eventsSince(since)
                        while (ev.isEmpty() && waitMs > 0 && System.currentTimeMillis() < deadline) {
                            Thread.sleep(120)
                            ev = RemoteBus.eventsSince(since)
                        }
                        val arr = ev.joinToString(",") { """{"t":${it.first},"keyCode":${it.second}}""" }
                        respond(
                            out, 200,
                            """{"ok":true,"now":${System.currentTimeMillis()},"events":[$arr]}""",
                            JSON_UTF8,
                        )
                    }

                    path == "/favicon.ico" -> respond(out, 204, "")

                    else -> respond(out, 404, "not found")
                }
            }.onFailure { log("请求异常：${it.message}") }
        }
    }

    private fun respond(
        out: OutputStream,
        code: Int,        body: String,
        contentType: String = "text/html; charset=utf-8",
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $code ${if (code == 200) "OK" else "ERROR"}\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun parseForm(body: String): Map<String, String> {
        if (body.isBlank()) return emptyMap()
        return body.split('&').mapNotNull { pair ->
            val i = pair.indexOf('=')
            if (i <= 0) null
            else URLDecoder.decode(pair.substring(0, i), "UTF-8") to
                URLDecoder.decode(pair.substring(i + 1), "UTF-8")
        }.toMap()
    }

    // ==================== 遥控 API 的辅助 ====================

    private const val JSON_UTF8 = "application/json; charset=utf-8"

    /** 解析 query string（遥控 API 允许把参数放 query 里，手机端更省事）。 */
    private fun parseQuery(query: String): Map<String, String> = parseForm(query)

    /**
     * 校验口令。
     *
     * 与网页调试共用 `prefs.debugToken`。**口令为空时不校验** ——
     * 家用局域网默认就没有口令，强制鉴权会让"扫码即用"变得很麻烦。
     * 用户如果在意安全，在设置里设一个口令即可。
     */
    private fun tokenOk(query: String, body: String): Boolean {
        val expected = runCatching { BawanApp.prefs.debugToken }.getOrDefault("")
        if (expected.isBlank()) return true
        val q = parseQuery(query)
        val p = parseForm(body)
        val given = q["token"] ?: p["token"] ?: return false
        return given == expected
    }

    /**
     * 把语义键名转成 Android 键码。
     *
     * 手机端可以写 `"key":"up"` 这种可读形式，也可以直接给数字键码。
     * 之所以两种都支持：前者写起来直观，后者能覆盖所有冷门键
     * （不必在协议里穷举）。
     */
    private fun keyNameToCode(name: String): Int = when (name.lowercase()) {
        "up" -> KeyEvent.KEYCODE_DPAD_UP
        "down" -> KeyEvent.KEYCODE_DPAD_DOWN
        "left" -> KeyEvent.KEYCODE_DPAD_LEFT
        "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
        "ok", "enter", "center", "select" -> KeyEvent.KEYCODE_DPAD_CENTER
        "back" -> KeyEvent.KEYCODE_BACK
        "home" -> KeyEvent.KEYCODE_HOME
        "menu" -> KeyEvent.KEYCODE_MENU
        "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
        "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
        "playpause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
        "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
        "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        "forward" -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
        "rewind" -> KeyEvent.KEYCODE_MEDIA_REWIND
        "volup" -> KeyEvent.KEYCODE_VOLUME_UP
        "voldown" -> KeyEvent.KEYCODE_VOLUME_DOWN
        "mute" -> KeyEvent.KEYCODE_VOLUME_MUTE
        "del", "backspace" -> KeyEvent.KEYCODE_DEL
        else -> 0
    }

    /** 遥控状态快照：手机端靠它渲染"现在电视在干嘛"。 */
    private fun remoteStatus(context: Context): String {
        val (cur, max, muted) = RemoteBus.volumeState(context)
        return buildString {
            append("""{"ok":true""")
            append(""","screen":"${RemoteBus.screen()}"""")
            append(""","volume":$cur,"volumeMax":$max,"muted":$muted""")
            append(""","now":${System.currentTimeMillis()}""")
            append("}")
        }
    }

    private fun jsonState(): String = runCatching {
        val sp = BawanApp.prefs.snapshot()
        val sb = StringBuilder("{")
        var first = true
        sp.forEach { (k, v) ->
            if (!first) sb.append(',')
            first = false
            sb.append('"').append(k).append("\":\"")
                .append(v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"))
                .append('"')
        }
        sb.append('}')
        sb.toString()
    }.getOrDefault("{}")

    // ==================== 手机端页面 ====================

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun pageHome(
        context: Context,
        query: String,
        cookieToken: String,
        error: String?,
    ): String {
        val p = BawanApp.prefs
        val ok = query.contains("ok=1")
        val token = if (p.debugToken.isNotBlank()) p.debugToken else ""
        val subUrls = p.subscriptionUrls
        val version = com.chinut.bawantv.BuildConfig.VERSION_NAME
        val ip = Qr.lanIp(context)

        return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<title>焰火TV · 手机调试</title>
<style>
:root{--bg:#0b0d18;--card:#161b2e;--line:#252c47;--accent:#5aa9ff;--txt:#eef2ff;--dim:#8b95b5}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
body{margin:0;background:linear-gradient(160deg,#1a1240,#0b0d18 55%);color:var(--txt);
 font-family:-apple-system,BlinkMacSystemFont,"PingFang SC","Microsoft YaHei",sans-serif;
 padding:18px 14px 90px;font-size:16px;line-height:1.55}
h1{font-size:22px;margin:4px 0 2px;letter-spacing:1px}
.sub{color:var(--dim);font-size:13px;margin-bottom:16px}
.card{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:14px;margin-bottom:14px}
.card h2{font-size:15px;margin:0 0 4px;color:var(--accent)}
.hint{color:var(--dim);font-size:12.5px;margin:0 0 10px}
label{display:block;font-size:13px;color:var(--dim);margin:10px 0 5px}
input[type=text],input[type=password],input[type=number],textarea{
 width:100%;background:#0e1222;border:1px solid var(--line);border-radius:11px;color:var(--txt);
 padding:11px 12px;font-size:15px;font-family:inherit}
textarea{min-height:96px;resize:vertical;line-height:1.5}
.row{display:flex;align-items:center;justify-content:space-between;padding:11px 0;border-bottom:1px solid var(--line)}
.row:last-child{border-bottom:none}
.row span{font-size:14.5px}
.switch{position:relative;width:50px;height:29px;flex:0 0 auto}
.switch input{opacity:0;width:100%;height:100%;margin:0;position:absolute;z-index:2}
.slider{position:absolute;inset:0;background:#2b3350;border-radius:999px;transition:.2s}
.slider:before{content:"";position:absolute;width:23px;height:23px;left:3px;top:3px;background:#fff;border-radius:50%;transition:.2s}
.switch input:checked + .slider{background:var(--accent)}
.switch input:checked + .slider:before{transform:translateX(21px)}
button{width:100%;padding:15px;border:none;border-radius:13px;font-size:17px;font-weight:700;
 background:linear-gradient(100deg,#5aa9ff,#8ec5ff);color:#06122b;margin-top:6px}
button.ghost{background:#232a45;color:var(--txt);font-weight:500;font-size:15px}
.ok{background:#123a29;border:1px solid #1f7a52;color:#7cf0b4;padding:11px 13px;border-radius:11px;
 font-size:14px;margin-bottom:14px}
.err{background:#3a1218;border:1px solid #7a1f2f;color:#ff9aa8;padding:11px 13px;border-radius:11px;
 font-size:14px;margin-bottom:14px}
.foot{color:var(--dim);font-size:12px;text-align:center;margin-top:8px}
.pill{display:inline-block;background:#1d2540;color:var(--accent);border-radius:999px;
 padding:3px 10px;font-size:12px;margin-right:6px}
</style>
</head>
<body>
<h1>焰火TV 手机调试</h1>
<div class="sub">
 <span class="pill">v$version</span><span class="pill">$ip</span>
 在同一 WiFi 下用手机改电视上的设置，保存后电视端立即生效。
</div>
${if (ok) """<div class="ok">✅ 保存成功，电视端已生效</div>""" else ""}
${if (error != null) """<div class="err">⚠️ ${esc(error)}</div>""" else ""}

<form method="POST" action="/save">
<div class="card">
 <h2>影视订阅（板块 B）</h2>
 <p class="hint">TVBox 接口地址，一行一个，按顺序尝试。支持 GitHub / Gitee 上的 raw 链接。</p>
 <textarea name="sub_urls" placeholder="https://raw.githubusercontent.com/xxx/xxx/main/config.json">${esc(subUrls)}</textarea>
 <div class="row"><span>只显示影视类站点</span>
  <label class="switch"><input type="checkbox" name="vod_only" ${if (p.vodOnlySites) "checked" else ""}><i class="slider"></i></label></div>
</div>

<div class="card">
 <h2>低端影视域名（板块 C）</h2>
 <p class="hint">网站换域名时改这里。例如 ddys.app / ddys.io / ddys.mov</p>
 <label>当前域名</label>
 <input type="text" name="domain" value="${esc(p.domain)}" placeholder="ddys.app">
 <div class="row"><span>自动探测可用域名</span>
  <label class="switch"><input type="checkbox" name="auto_domain" ${if (p.autoDomain) "checked" else ""}><i class="slider"></i></label></div>
 <div class="row"><span>网页兜底（过 Cloudflare 验证）</span>
  <label class="switch"><input type="checkbox" name="web_fallback" ${if (p.webFallbackEnabled) "checked" else ""}><i class="slider"></i></label></div>
 <label>自定义 User-Agent（留空用内置）</label>
 <input type="text" name="user_agent" value="${esc(p.userAgent)}" placeholder="Mozilla/5.0 ...">
</div>

<div class="card">
 <h2>直播源（板块 A）</h2>
 <p class="hint">留空使用内置央视频道表。也可填自己的 m3u/txt 直播源地址。</p>
 <input type="text" name="live_source" value="${esc(p.liveSourceUrl)}" placeholder="https://... .m3u">
 <div class="row"><span>开机自动进入上次频道</span>
  <label class="switch"><input type="checkbox" name="auto_last_channel" ${if (p.autoPlayLastChannel) "checked" else ""}><i class="slider"></i></label></div>
 <div class="row"><span>硬件解码（花屏时关掉）</span>
  <label class="switch"><input type="checkbox" name="hw_decode" ${if (p.hardwareDecode) "checked" else ""}><i class="slider"></i></label></div>
 <div class="row"><span>换台显示台标浮层</span>
  <label class="switch"><input type="checkbox" name="show_hud" ${if (p.showChannelHud) "checked" else ""}><i class="slider"></i></label></div>
</div>

<div class="card">
 <h2>更新与调试</h2>
 <div class="row"><span>自动检查更新</span>
  <label class="switch"><input type="checkbox" name="auto_update" ${if (p.autoCheckUpdate) "checked" else ""}><i class="slider"></i></label></div>
 <label>调试服务端口</label>
 <input type="number" name="port" value="${p.debugPort}" min="1024" max="65535">
 <label>手机网页口令（留空则不需要口令）</label>
 <input type="password" name="debug_token" value="${esc(token)}" placeholder="设置后手机端需填写">
</div>

<button type="submit">保存到电视</button>
</form>

<div class="card">
 <h2>说明</h2>
 <p class="hint">
  令牌（debug_token）就是上面那个口令，改完保存后下次打开本页需要重新输入。<br>
  订阅接口与影视资源均来自第三方公开接口，仅限个人学习研究使用。
 </p>
</div>

<div class="foot">焰火TV · 手机调试模式 · ${esc(ip)}:${p.debugPort}</div>
</body>
</html>
        """.trimIndent()
    }
}

/** 避免 core 包直接依赖 vod 包 */
private object VodRepoBridge {
    fun invalidate() {
        runCatching { com.chinut.bawantv.vod.VodRepo.invalidate() }
    }
}
