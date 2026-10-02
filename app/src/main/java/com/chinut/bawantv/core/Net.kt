package com.chinut.bawantv.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * 极简网络工具：统一 UA、超时、异常吞掉后返回 null，让调用方少写 try/catch。
 */
object Net {

    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        ua: String = Http.UA_DESKTOP,
    ): String? = request(url, headers, ua, null)

    suspend fun getBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        ua: String = Http.UA_DESKTOP,
    ): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val req = build(url, headers, ua, null)
            Http.client.newCall(req).execute().use { r ->
                if (r.isSuccessful) r.body?.bytes() else null
            }
        }.getOrNull()
    }

    suspend fun postForm(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        ua: String = Http.UA_DESKTOP,
    ): String? {
        val body = form.entries.joinToString("&") { (k, v) ->
            "${enc(k)}=${enc(v)}"
        }
        return request(url, headers, ua, body)
    }

    suspend fun postJson(
        url: String,
        json: String,
        headers: Map<String, String> = emptyMap(),
        ua: String = Http.UA_DESKTOP,
    ): String? = request(url, headers + ("Content-Type" to "application/json"), ua, json)

    private suspend fun request(
        url: String,
        headers: Map<String, String>,
        ua: String,
        body: String?,
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val req = build(url, headers, ua, body)
            Http.client.newCall(req).execute().use { r: Response ->
                val text = r.body?.string()
                if (r.isSuccessful) text else null
            }
        }.getOrNull()
    }

    private fun build(
        url: String,
        headers: Map<String, String>,
        ua: String,
        body: String?,
    ): Request {
        val b = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
            )
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        headers.forEach { (k, v) -> b.header(k, v) }
        if (body != null) {
            b.post(body.toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaType()))
        }
        return b.build()
    }

    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")

    /** 从 HTML 里抓取 <meta property="og:image"> 之类的值 */
    fun metaContent(html: String, property: String): String? {
        val re = Regex(
            """<meta[^>]+(?:property|name)=["']${Regex.escape(property)}["'][^>]*content=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        re.find(html)?.let { return it.groupValues[1] }
        val re2 = Regex(
            """<meta[^>]+content=["']([^"']+)["'][^>]*(?:property|name)=["']${Regex.escape(property)}["']""",
            RegexOption.IGNORE_CASE
        )
        return re2.find(html)?.groupValues?.get(1)
    }
}
