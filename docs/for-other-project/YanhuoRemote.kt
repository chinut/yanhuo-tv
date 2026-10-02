package com.example.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 焰火TV 网络遥控客户端（可直接拷进手机音乐 App 使用）。
 *
 * ============================ 给另一个工程的说明 ============================
 *
 * 这是一个**零依赖**（只用 JDK + org.json）的客户端，负责把手机上的操作
 * 变成 HTTP 请求发给电视上的「焰火TV」。
 *
 * 用法三步：
 *
 * ```kotlin
 * val tv = YanhuoRemote("192.168.1.23", 8899)   // 电视 IP 和端口
 * if (tv.ping()) {                              // 先探测在不在
 *     tv.key(YanhuoRemote.Key.DOWN)             // 当作遥控器按一下「下」
 *     tv.volumeDelta(+2)                        // 音量 +2
 *     val st = tv.status()                      // 读当前状态（音量/界面）
 * }
 * ```
 *
 * 所有方法都是挂起函数（内部切到 IO 线程），返回值不会抛异常，
 * 失败一律返回 null / false —— 手机端不需要到处 try/catch。
 *
 * 完整协议见 TV 项目里的 `docs/网络遥控协议.md`。
 * ==========================================================================
 */
class YanhuoRemote(
    /** 电视的局域网 IP。 */
    private val host: String,
    /** 端口。默认 8899，与电视上「设置 → 手机网页调试 → 调试端口」一致。 */
    private val port: Int = 8899,
    /**
     * 口令。电视上没设口令时留空即可。
     *
     * 建议 App 里让用户填一次并存起来：口令在电视的
     * 「设置 → 手机网页调试 → 手机口令」里设。
     */
    private val token: String = "",
    /** 单次请求超时（毫秒）。局域网下 3 秒足够；遥控要的是"立刻响应"。 */
    private val timeoutMs: Int = 3000,
) {

    /** 遥控器按键。值就是 Android KeyEvent 键码。 */
    enum class Key(val code: Int) {
        UP(19), DOWN(20), LEFT(21), RIGHT(22),
        OK(23), BACK(4), MENU(82),
        PLAY(126), PAUSE(127), PLAY_PAUSE(85), STOP(86),
        NEXT(87), PREV(88), FORWARD(90), REWIND(89),
        VOL_UP(24), VOL_DOWN(25), MUTE(164);

        /**
         * 协议里也有可读的键名（up/down/left/right/ok/back/...）。
         * 两种都能发，这里用键码，因为能覆盖所有冷门键。
         */
        val wire: String get() = "keyCode=$code"
    }

    /** 电视返回的状态。 */
    data class Status(
        /** 电视当前在哪个界面：home / vod / live / settings / playing。 */
        val screen: String,
        val volume: Int,
        val volumeMax: Int,
        val muted: Boolean,
        /** 电视端的时间戳（毫秒）。用它做事件轮询的游标。 */
        val now: Long,
    )

    /** 一次按键事件（电视上真实按下的，包含实体遥控器按的）。 */
    data class KeyEvent(val time: Long, val keyCode: Int)

    // ==================== 探测 ====================

    /**
     * 探测电视是否在线。
     *
     * 建议在"连接电视"页面调用；返回 true 才算连通。
     * 返回的协议版本号可以用来做兼容判断（当前为 1）。
     */
    suspend fun ping(): Boolean = get("/api/remote/ping") != null

    /** 探测并返回协议版本；不在线返回 0。 */
    suspend fun protocolVersion(): Int {
        val body = get("/api/remote/ping") ?: return 0
        return runCatching { JSONObject(body).optInt("protocol", 0) }.getOrDefault(0)
    }

    // ==================== 状态 ====================

    /** 读当前状态（音量、界面）。 */
    suspend fun status(): Status? {
        val body = get("/api/remote/status") ?: return null
        return runCatching {
            val o = JSONObject(body)
            Status(
                screen = o.optString("screen", "unknown"),
                volume = o.optInt("volume", 0),
                volumeMax = o.optInt("volumeMax", 15),
                muted = o.optBoolean("muted", false),
                now = o.optLong("now", 0L),
            )
        }.getOrNull()
    }

    // ==================== 按键 ====================

    /**
     * 按一下遥控器。
     *
     * @return 电视是否消费了这个键（true = 确实产生了动作）
     */
    suspend fun key(k: Key): Boolean = key(k.code)

    /**
     * 按任意 Android 键码。需要协议里没列举的冷门键时用这个。
     */
    suspend fun key(keyCode: Int): Boolean {
        val body = post("/api/remote/key", "keyCode=$keyCode") ?: return false
        return runCatching { JSONObject(body).optBoolean("consumed", false) }.getOrDefault(false)
    }

    /** 长按支持：先发 down、稍后发 up，中间电视会持续响应。 */
    suspend fun keyDown(keyCode: Int): Boolean = sendKey(keyCode, "down")

    suspend fun keyUp(keyCode: Int): Boolean = sendKey(keyCode, "up")

    private suspend fun sendKey(keyCode: Int, action: String): Boolean {
        val body = post("/api/remote/key", "keyCode=$keyCode&action=$action") ?: return false
        return runCatching { JSONObject(body).optBoolean("ok", false) }.getOrDefault(false)
    }

    /**
     * 发送一段文字（会进到电视上正在输入的输入框，比如搜索框）。
     *
     * @return 电视接受了几个字符
     */
    suspend fun text(text: String): Int {
        val enc = URLEncoder.encode(text, "UTF-8")
        val body = post("/api/remote/text", "text=$enc") ?: return 0
        return runCatching { JSONObject(body).optInt("accepted", 0) }.getOrDefault(0)
    }

    // ==================== 音量 ====================

    /** 相对调音量。delta 为正加、为负减。 */
    suspend fun volumeDelta(delta: Int): Status? {
        val body = post("/api/remote/volume", "delta=$delta") ?: return null
        return status()
    }

    /** 设绝对音量。value 会被电视钳制到 [0, volumeMax]。 */
    suspend fun setVolume(value: Int): Status? {
        post("/api/remote/volume", "value=$value")
        return status()
    }

    /** 静音 / 取消静音。 */
    suspend fun setMuted(muted: Boolean): Status? {
        post("/api/remote/mute", "muted=${if (muted) 1 else 0}")
        return status()
    }

    /** 音量 +1 / -1 的便捷写法，适合做「音量条」的加减按钮。 */
    suspend fun volumeUp(): Status? = volumeDelta(1)
    suspend fun volumeDown(): Status? = volumeDelta(-1)

    // ==================== 事件回显（可选） ====================

    /**
     * 拉取电视上**真实按下**的按键事件。
     *
     * 用途：用户在电视旁用实体遥控器操作时，手机界面也能跟着高亮/更新，
     * 不会出现"手机显示的还是旧状态"。
     *
     * @param since 上次拿到的时间戳（用上次返回的 [Pair.second] 或 Status.now）
     * @param waitMs 长轮询等待毫秒数。传 0 立即返回；传 15000 则最多挂 15 秒，
     *               有新事件立刻返回（省电、响应快）。建议在"遥控器页面可见时"用长轮询。
     * @return (事件列表, 下次该传的 since)
     */
    suspend fun pollEvents(since: Long, waitMs: Int = 0): Pair<List<KeyEvent>, Long> {
        val body = get("/api/remote/events?since=$since&wait=$waitMs") ?: return emptyList<KeyEvent>() to since
        return runCatching {
            val o = JSONObject(body)
            val arr = o.optJSONArray("events")
            val list = ArrayList<KeyEvent>(arr?.length() ?: 0)
            for (i in 0 until (arr?.length() ?: 0)) {
                val e = arr!!.optJSONObject(i) ?: continue
                list += KeyEvent(e.optLong("t"), e.optInt("keyCode"))
            }
            list to o.optLong("now", since)
        }.getOrDefault(emptyList<KeyEvent>() to since)
    }

    // ==================== HTTP 底层 ====================

    private suspend fun get(path: String): String? = withContext(Dispatchers.IO) {
        request(path, null)
    }

    private suspend fun post(path: String, form: String): String? = withContext(Dispatchers.IO) {
        request(path, form)
    }

    private fun request(path: String, form: String?): String? = runCatching {
        val full = buildString {
            append("http://").append(host).append(':').append(port).append(path)
            if (token.isNotBlank()) {
                append(if (path.contains('?')) '&' else '?')
                append("token=").append(URLEncoder.encode(token, "UTF-8"))
            }
        }
        val conn = (URL(full).openConnection() as HttpURLConnection).apply {
            requestMethod = if (form == null) "GET" else "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs + 2000
            useCaches = false
            if (form != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
        }
        if (form != null) {
            conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        conn.disconnect()
        if (code in 200..299) text else null
    }.getOrNull()
}
