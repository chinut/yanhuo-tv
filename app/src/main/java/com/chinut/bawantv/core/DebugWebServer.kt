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

    /**
     * 处理 APK 上传：从 multipart 里抠出文件、落到 `filesDir/updates/`、
     * 然后交给系统的安装界面。
     *
     * 为什么要手写 multipart 解析：这个服务是极简 HTTP，没有第三方库，
     * 而 APK 上传只有一个"文件"字段，抠边界就够。
     *
     * @return (是否成功, 给用户看的提示)
     */
    private fun handleApkUpload(
        ctx: Context,
        body: ByteArray,
    ): Pair<Boolean, String> {
        val context = ctx.applicationContext
        if (body.size < 1024) return false to "上传内容为空或过小"
        if (body.size > 200L * 1024 * 1024) return false to "文件太大（超过 200MB）"

        // 找 multipart 头里的 filename
        val head = String(body.copyOfRange(0, minOf(body.size, 4096)), Charsets.ISO_8859_1)
        val nameMatch = Regex("filename=\"([^\"]+)\"").find(head)
        val rawName = nameMatch?.groupValues?.get(1).orEmpty().ifBlank { "upload.apk" }
        if (!rawName.lowercase().endsWith(".apk")) {
            return false to "只接受 .apk 文件（收到：$rawName）"
        }

        // 抠出文件正文：空行之后、到结尾的 -- 之前
        val sep = byteArrayOf(13, 10, 13, 10)   // CRLFCRLF

        var start = -1
        var i = 0
        while (i <= body.size - 4) {
            if (body[i] == sep[0] && body[i + 1] == sep[1] &&
                body[i + 2] == sep[2] && body[i + 3] == sep[3]
            ) {
                start = i + 4
                break
            }
            i++
        }
        if (start <= 0) return false to "没找到文件内容（multipart 格式不对）"

        // 结尾的 CRLF + -- 要砍掉
        var end = body.size
        var k = body.size - 4
        while (k > start) {
            if (body[k] == 13.toByte() && body[k + 1] == 10.toByte() &&
                body[k + 2] == 45.toByte() && body[k + 3] == 45.toByte()
            ) {
                end = k
                break
            }
            k--
        }
        if (end <= start) return false to "文件内容为空"

        return runCatching {
            val dir = java.io.File(context.filesDir, "updates").apply { mkdirs() }
            // 固定文件名，避免上传目录堆积旧包
            val apk = java.io.File(dir, "uploaded.apk")
            apk.writeBytes(body.copyOfRange(start, end))
            log("收到安装包 ${apk.length() / 1048576} MB，交给系统安装器")

            // 复用更新模块的安装逻辑（它已经处理了各家电视的坑）
            com.chinut.bawantv.core.Updater.install(context, apk)
            true to "已收到 ${apk.length() / 1048576} MB，电视上应该弹出安装界面了"
        }.getOrElse { e ->
            log("安装包处理失败：${e.message}")
            false to "处理失败：${e.message?.take(80)}"
        }
    }

    private fun log(line: String) {
        val now = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA)
            .format(java.util.Date())
        _log.value = (listOf("[$now] $line") + _log.value).take(30)
        // ⚠️ 同时写 logcat。
        //
        // 原来只写内存那个 StateFlow —— 排查"服务起不来"时
        // logcat 里**什么都看不到**（我就因此多绕了一大圈）。
        android.util.Log.i("BawanDebugWeb", line)
    }

    /** 启动服务。已在运行则先停掉再按新端口启动。 */
    fun start(context: Context, port: Int) {
        stop()
        val app = context.applicationContext
        val sc = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = sc
        sc.launch {
            try {
                // ---------- 端口被占就自动往后试 ----------
                //
                // 实测踩的坑：bind 抛 EADDRINUSE 时，**界面上只显示
                // 「服务未运行」**，用户完全不知道是端口被占了，
                // 会以为是软件坏了 / WiFi 问题，然后反复点「启动」。
                //
                // 现在自动退让到 8900 / 8901 …，最多试 20 个。
                // 成功的端口会写回设置，二维码和地址都跟着变 ——
                // 用户什么都不用做。
                var ss: ServerSocket? = null
                var bound = port
                var lastErr: String? = null
                for (cand in port until port + 20) {
                    val s = ServerSocket()
                    // reuseAddress 能缓解 TIME_WAIT 造成的"假占用"，
                    // 但不能解决"真的有别的进程在用"，所以还要能退让。
                    s.reuseAddress = true
                    val ok = runCatching { s.bind(InetSocketAddress(cand)) }.isSuccess
                    if (ok) {
                        ss = s
                        bound = cand
                        break
                    }
                    runCatching { s.close() }
                    lastErr = "端口 $cand 被占用"
                    log("端口 $cand 被占用，往后试")
                }
                val server0 = ss
                if (server0 == null) {
                    _running.value = false
                    log("启动失败：8899~${port + 19} 都被占用（$lastErr）")
                    return@launch
                }
                server = server0
                _port.value = bound
                _running.value = true
                if (bound != port) {
                    // 写回设置，让界面和二维码显示真实端口
                    runCatching { BawanApp.prefs.debugPort = bound }
                    log("原端口 $port 被占用，已改用 $bound")
                } else {
                    log("服务已启动：${Qr.debugUrl(app, bound)}")
                }
                val ss2 = server0
                while (isActive && !ss2.isClosed) {
                    val client = try {
                        ss2.accept()
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
                // ⚠️ 上传 APK 时要把 soTimeout 调大（20MB 在慢网络上不止 15 秒）
                s.soTimeout = 60_000

                // ---------- 全程用字节流读请求 ----------
                //
                // 原来头部用 BufferedReader、body 再切回原始 InputStream ——
                // BufferedReader 会预读，头读完后body 的前一截还留在它自己的
                // 缓冲区里，从原始流读就**少了一截**，于是 multipart 解析失败、
                // 连接被关（实测上传 20MB 直接 Remote end closed）。
                //
                // 现在统一走字节：逐字节读到 CRLFCRLF 拿到头部，
                // 再按 Content-Length 精确读 body，二进制安全。
                val ins = s.getInputStream()

                /** 读到 CRLFCRLF 为止，返回头部文本（不含空行）。 */
                fun readHead(): String {
                    val sb = StringBuilder()
                    var last4 = 0
                    while (sb.length < 64 * 1024) {
                        val b = ins.read()
                        if (b < 0) break
                        sb.append(b.toChar())
                        // 检测 


                        last4 = (last4 shl 8) or b
                        if (last4 == 0x0D0A0D0A) break
                    }
                    return sb.toString()
                }

                val headText = readHead()
                if (headText.isEmpty()) return
                val headLines = headText.split("\r\n")
                val requestLine = headLines.firstOrNull().orEmpty()
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val rawPath = parts[1]

                var contentLength = 0
                var cookieToken = ""
                headLines.drop(1).forEach { line ->
                    val lower = line.lowercase()
                    when {
                        lower.startsWith("content-length:") ->
                            contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0

                        lower.startsWith("cookie:") ->
                            cookieToken = line.substringAfter(':').trim()
                    }
                }

                // 读取 body
                // ⚠️ 用**字节**读，不能只读成 String ——
                // APK 上传是二进制，转成 UTF-8 字符串会损坏内容。
                val bodyBytes: ByteArray = if (contentLength > 0) {
                    val buf = ByteArray(contentLength)
                    var read = 0
                    // ins 已在上面定义（同一个流，没有缓冲错位）
                    while (read < contentLength) {
                        val r = ins.read(buf, read, contentLength - read)
                        if (r <= 0) break
                        read += r
                    }
                    if (read == contentLength) buf else buf.copyOf(read)
                } else ByteArray(0)
                val body = String(bodyBytes, Charsets.UTF_8)

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

                    // ---------- 上传安装包（手机 → 电视 → 系统安装器）----------
                    //
                    // 用户嫌 U 盘来回插麻烦，而电视又没开 ADB，
                    // 所以给手机调试页加这个入口。
                    path == "/upload" && method == "POST" -> {
                        val r = handleApkUpload(context, bodyBytes)
                        respond(out, if (r.first) 200 else 400, r.second)
                    }

                    // ---------- 播放诊断 ----------
                    //
                    // 用户没法在电视上抓日志（没有文件管理器 / adb），
                    // 所以把诊断结果做成一个接口 + 手机页上的一张卡片。
                    path == "/api/diag" && method == "GET" ->
                        respond(out, 200, PlayDiag.report())

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
input[type=text],input[type=password],input[type=number],textarea,select{
 width:100%;background:#0e1222;border:1px solid var(--line);border-radius:11px;color:var(--txt);
 padding:11px 12px;font-size:15px;font-family:inherit}
/* select 在下拉箭头处留出空间，并按深色主题渲染 */
select{appearance:none;-webkit-appearance:none;
 background-image:linear-gradient(45deg,transparent 50%,var(--dim) 50%),linear-gradient(135deg,var(--dim) 50%,transparent 50%);
 background-position:calc(100% - 20px) 50%,calc(100% - 14px) 50%;
 background-size:6px 6px,6px 6px;background-repeat:no-repeat;padding-right:38px}
select option{background:#161b2e;color:var(--txt)}
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
 <h2>影视内容</h2>
 <p class="hint">影视内容来自<b>低端影视</b>，片库缓存在电视本地。
  它的剧集是直连地址，不需要任何"解析接口"。<br>
  要换域名请改下面的「低端影视域名」。</p>
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
 <div class="row"><span>老电视模式<br><small style="color:var(--dim)">用直连源替代网页播放，减轻老设备负担</small></span>
  <label class="switch"><input type="checkbox" name="old_tv" ${if (p.oldTvMode) "checked" else ""}><i class="slider"></i></label></div>
 <label>主源（老电视模式关闭时生效）</label>
 <select name="live_preset">
  <option value="Default" ${if (p.livePreset == "Default") "selected" else ""}>内置频道</option>
  <option value="OpenSource" ${if (p.livePreset == "OpenSource") "selected" else ""}>GitHub 源</option>
  <option value="Both" ${if (p.livePreset == "Both") "selected" else ""}>双源</option>
 </select>
 <label>直播画质</label>
 <select name="live_quality">
  <option value="0" ${if (p.liveQuality == 0) "selected" else ""}>自动</option>
  <option value="1" ${if (p.liveQuality == 1) "selected" else ""}>流畅</option>
  <option value="2" ${if (p.liveQuality == 2) "selected" else ""}>标清</option>
  <option value="3" ${if (p.liveQuality == 3) "selected" else ""}>高清</option>
  <option value="4" ${if (p.liveQuality == 4) "selected" else ""}>超清</option>
 </select>
 <div class="row"><span>显示内存浮层<br><small style="color:var(--dim)">看占用，排查卡顿用</small></span>
  <label class="switch"><input type="checkbox" name="mem_hud" ${if (p.showMemoryHud) "checked" else ""}><i class="slider"></i></label></div>
</div>

<div class="card">
 <h2>家长控制</h2>
 <p class="hint">打开后按密码进入设置；屏蔽词里的内容不会出现在片库里。屏蔽词/白名单用逗号分隔。</p>
 <div class="row"><span>启用家长控制</span>
  <label class="switch"><input type="checkbox" name="parental_on" ${if (p.parentalEnabled) "checked" else ""}><i class="slider"></i></label></div>
 <label>密码（4 位数字）</label>
 <input type="text" inputmode="numeric" name="parental_pin" value="${esc(p.parentalPin)}" placeholder="1234" maxlength="8">
 <label>屏蔽词</label>
 <input type="text" name="parental_block" value="${esc(p.parentalBlockedWords)}" placeholder="恐怖,暴力">
 <label>白名单（只允许这些）</label>
 <input type="text" name="parental_allow" value="${esc(p.parentalAllowedWords)}" placeholder="动画,少儿">
</div>

<div class="card">
 <h2>更新与调试</h2>
 <div class="row"><span>自动检查更新</span>
  <label class="switch"><input type="checkbox" name="auto_update" ${if (p.autoCheckUpdate) "checked" else ""}><i class="slider"></i></label></div>
 <label>调试服务端口</label>
 <input type="number" name="port" value="${p.debugPort}" min="1024" max="65535">
 <div class="row"><span>允许手机调试<br><small style="color:var(--dim)">关闭后不再自动启动局域网服务</small></span>
  <label class="switch"><input type="checkbox" name="debug_on" ${if (p.debugEnabled) "checked" else ""}><i class="slider"></i></label></div>
 <label>手机网页口令（留空则不需要口令）</label>
 <input type="password" name="debug_token" value="${esc(token)}" placeholder="设置后手机端需填写">
 <div class="row"><span>记录内存日志</span>
  <label class="switch"><input type="checkbox" name="mem_log" ${if (p.logMemory) "checked" else ""}><i class="slider"></i></label></div>
 <label>影视每页条数（12–50）</label>
 <input type="number" name="page_size" value="${p.ddysPageSize}" min="12" max="50">
</div>

<button type="submit">保存到电视</button>
</form>

<script>
// ---------- 勾选框必须转成 true / false ----------
//
// HTML 的 checkbox 不带 value 时，浏览器发的是 "on"，
// 而后端是 `it.toBoolean()` —— Kotlin 里 "on".toBoolean() === false。
// 结果就是**所有开关都不生效**（实测：勾上「老电视模式」保存后还是关的）。
//
// 这里在提交前把每个 checkbox 改写成明确的 true / false：
//   · 勾上   → value="true"
//   · 没勾上 → 也补一个 "false"（否则后端收不到这个 key，会保持原值）
document.querySelector('form').addEventListener('submit', function (ev) {
  ev.preventDefault();
  var fd = new FormData(ev.target);
  ev.target.querySelectorAll('input[type=checkbox]').forEach(function (cb) {
    fd.set(cb.name, cb.checked ? 'true' : 'false');
  });
  var btn = ev.target.querySelector('button[type=submit]');
  if (btn) { btn.disabled = true; btn.textContent = '正在保存…'; }
  fetch('/save', { method: 'POST', body: new URLSearchParams(fd) })
    .then(function (r) {
      if (btn) { btn.textContent = '已保存 ✓'; btn.disabled = false; }
      // 顶部闪一条提示，不整页跳转（手机上跳转会像卡住了）
      var t = document.createElement('div');
      t.textContent = r.ok ? '已保存到电视 ✓' : '保存失败，请重试';
      t.style.cssText = 'position:fixed;left:14px;right:14px;bottom:14px;padding:14px;'
        + 'border-radius:14px;text-align:center;font-size:15px;z-index:99;'
        + 'background:' + (r.ok ? '#1f6f4a' : '#7a2230') + ';color:#fff';
      document.body.appendChild(t);
      setTimeout(function () { t.remove(); }, 1800);
      if (btn) setTimeout(function () { btn.textContent = '保存到电视'; }, 1800);
    })
    .catch(function () {
      if (btn) { btn.textContent = '保存失败，重试'; btn.disabled = false; }
    });
});
</script>

<div class="card">
 <h2>上传安装包（不用插 U 盘）</h2>
 <p class="hint">
  手机选一个 APK，传到电视并直接调用系统安装界面。<br>
  电视上会弹出安装确认，按遥控器确定即可。
 </p>
 <input type="file" id="apk" accept=".apk,application/vnd.android.package-archive"
  style="width:100%;background:#0e1222;border:1px solid var(--line);
  border-radius:11px;color:var(--txt);padding:11px 12px;font-size:14px">
 <button type="button" onclick="uploadApk()" style="margin-top:10px">上传并安装</button>
 <pre id="upmsg" style="white-space:pre-wrap;font-size:13px;line-height:1.6;
  margin:10px 0 0;color:var(--dim)"></pre>
</div>

<script>
function uploadApk() {
  var f = document.getElementById('apk').files[0];
  var msg = document.getElementById('upmsg');
  if (!f) { msg.textContent = '先选一个 APK 文件'; return; }
  msg.textContent = '正在上传 ' + (f.size / 1048576).toFixed(1) + ' MB…（别关页面）';
  var fd = new FormData();
  fd.append('file', f);
  fetch('/upload', { method: 'POST', body: fd })
    .then(function (r) { return r.text(); })
    .then(function (t) { msg.textContent = t; })
    .catch(function (e) { msg.textContent = '上传失败：' + e; });
}
</script>

<div class="card">
 <h2>播放诊断（卡顿排查）</h2>
 <p class="hint">
  电视上不好抓日志，所以卡不卡、卡在哪，直接看这里。<br>
  先让电视播那个卡的台，等 1~2 分钟再刷新本页。
 </p>
 <pre id="diag" style="white-space:pre-wrap;font-size:13px;line-height:1.7;
  background:#0e1222;border:1px solid var(--line);border-radius:11px;
  padding:12px;margin:0;color:#eef2ff">读取中…</pre>
 <button type="button" onclick="loadDiag()" style="margin-top:10px">刷新诊断</button>
</div>

<script>
function loadDiag() {
  var el = document.getElementById('diag');
  el.textContent = '读取中…';
  fetch('/api/diag').then(function (r) { return r.text(); })
    .then(function (t) { el.textContent = t; })
    .catch(function () { el.textContent = '读取失败（服务没运行？）'; });
}
loadDiag();
setInterval(loadDiag, 10000);
</script>

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
