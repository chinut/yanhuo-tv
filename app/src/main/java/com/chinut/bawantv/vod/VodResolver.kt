package com.chinut.bawantv.vod

import android.content.Context
import com.chinut.bawantv.BawanApp
import com.chinut.bawantv.core.Net
import java.net.URLDecoder
import org.json.JSONObject

/**
 * 播放地址解析器。
 *
 * 站点给的「剧集地址」有三种形态，必须按顺序尝试：
 *  1. 就是 m3u8/mp4 直链 → 直接交给播放器
 *  2. 是一段 JSON 字符串（形如 `{"url":"...","header":{...}}`）→ 取里面真正的地址
 *  3. 是网页地址（腾讯/爱奇艺/优酷/芒果/B站等）→ 用订阅里的 parses 接口解析出真实流
 *
 * 解析接口契约（TVBox 生态通用，type = 1）：
 *   POST {parseUrl}   body: url=<剧集地址>
 *   返回 { "url": "真实地址", "header": {...}, "parse": 1, "jx": 1 }
 * 有的接口直接返回纯文本地址，这里都兼容。
 */
class VodResolver(
    private val parses: List<SubParse>,
    private val context: Context = BawanApp.ctx(),
) {

    /** 解析结果。 */
    data class Resolved(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        /** 命中/未命中的说明，展示在播放页上 */
        val via: String = "直链",
    )

    /** 常见解析接口的 flag 命名，用来把站点线路名映射到解析器。 */
    private val flagAliases = mapOf(
        "qq" to listOf("qq", "腾讯", "v.qq"),
        "qiyi" to listOf("qiyi", "iqiyi", "爱奇艺", "奇艺"),
        "youku" to listOf("youku", "优酷"),
        "mgtv" to listOf("mgtv", "芒果", "hnst"),
        "bilibili" to listOf("bilibili", "bili", "哔哩"),
        "wasu" to listOf("wasu", "华数"),
        "letv" to listOf("letv", "乐视"),
        "sohu" to listOf("sohu", "搜狐"),
        "pptv" to listOf("pptv", "聚力"),
        "xigua" to listOf("xigua", "西瓜"),
    )

    /**
     * 解析一个剧集地址。
     * @param episodeFlag 线路名（如「腾讯」「qq」），用于挑更匹配的解析接口，可为空
     */
    suspend fun resolve(rawUrl: String, episodeFlag: String = ""): Resolved? {
        val url = rawUrl.trim()
        if (url.isEmpty()) return null

        // ---------- 形态 2：内嵌 JSON ----------
        if (url.startsWith("{")) {
            runCatching {
                val o = JSONObject(url)
                val real = o.optString("url")
                if (real.isNotBlank()) {
                    val headers = o.optJSONObject("header")?.let { h ->
                        h.keys().asSequence().associateWith { k -> h.optString(k) }
                    }.orEmpty()
                    return Resolved(real, headers, "内置 JSON")
                }
            }
        }

        // ---------- 形态 1：直链 ----------
        if (looksLikeMedia(url)) return Resolved(url, emptyMap(), "直链")

        // ---------- 形态 3：走解析接口 ----------
        val ordered = orderParses(episodeFlag)
        for (p in ordered) {
            val got = tryParse(p, url) ?: continue
            return got
        }
        return null
    }

    private fun orderParses(flag: String): List<SubParse> {
        if (parses.isEmpty()) return emptyList()
        val key = flagAliases.entries.firstOrNull { (_, aliases) ->
            aliases.any { flag.contains(it, ignoreCase = true) }
        }?.key
        val usable = parses.filter { it.type == 1 && it.url.startsWith("http") }
        if (key == null) return usable
        return usable.sortedByDescending { p ->
            val hit = (p.ext?.flag ?: emptyList()).any { it.equals(key, ignoreCase = true) }
            if (hit) 1 else 0
        }
    }

    private suspend fun tryParse(p: SubParse, episodeUrl: String): Resolved? {
        val body = Net.postForm(
            p.url,
            mapOf("url" to episodeUrl),
            headers = mapOf(
                "Accept" to "application/json, text/plain, */*",
                "Referer" to runCatching { "https://" + java.net.URI(p.url).host + "/" }.getOrDefault(""),
            ),
        ) ?: return null
        val text = body.trim()
        if (text.isEmpty()) return null

        // 纯文本地址
        if (text.startsWith("http") && !text.contains("\"url\"")) {
            return Resolved(text, p.ext?.header.orEmpty(), "解析：${p.name}")
        }

        val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val real = o.optString("url").ifBlank { o.optString("data") }
        if (real.isBlank() || !real.startsWith("http")) return null
        val headers = p.ext?.header.orEmpty() + (o.optJSONObject("header")?.let { h ->
            h.keys().asSequence().associateWith { k -> h.optString(k) }
        }.orEmpty())
        return Resolved(real, headers, "解析：${p.name}")
    }

    companion object {
        /** 判断是否看起来像可直连的媒体地址。 */
        fun looksLikeMedia(url: String): Boolean {
            val u = url.lowercase()
            if (!u.startsWith("http")) return false
            if (u.contains(".m3u8")) return true
            if (u.contains(".mp4")) return true
            if (u.contains(".flv")) return true
            if (u.contains(".mkv")) return true
            if (u.contains(".ts") && !u.contains("/ts/")) return true
            if (u.contains("m3u8")) return true
            // 部分接口返回的地址没有扩展名，但带这些特征
            if (u.contains("/hls/") || u.contains("playlist")) return true
            return false
        }

        /** 反解 URL 编码（部分站点把地址二次编码了）。 */
        fun decodeOnce(s: String): String =
            runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
    }
}
