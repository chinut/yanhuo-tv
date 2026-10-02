package com.chinut.bawantv.core

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.Cache
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/**
 * 全局 OkHttp。
 *
 * - 内置内存 CookieJar：Cloudflare 验证过一次（cf_clearance）之后，后续请求自动带上
 * - 缓存所有域名共享，减少重复请求
 * - 统一的 UA / 默认请求头
 */
object Http {

    const val UA_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    const val UA_MOBILE =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    /** TV 盒子常见 UA：部分直播源只对 TV 端放行 */
    const val UA_TV =
        "Mozilla/5.0 (Linux; Android 9; SHIELD Android TV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val cookieStore = ConcurrentHashMap<String, MutableMap<String, Cookie>>()

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val host = url.host
            val jar = cookieStore.getOrPut(host) { ConcurrentHashMap() }
            cookies.forEach { jar[it.name] = it }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            val out = ArrayList<Cookie>()
            // 主域名与父域名都试一遍
            val hosts = buildList {
                add(url.host)
                val parts = url.host.split('.')
                if (parts.size > 2) add(parts.takeLast(2).joinToString("."))
            }
            hosts.forEach { h ->
                cookieStore[h]?.values?.forEach { c ->
                    if (c.expiresAt > now) out.add(c)
                }
            }
            return out
        }
    }

    @Volatile
    private var instance: OkHttpClient? = null

    fun init(context: Context) {
        if (instance != null) return
        synchronized(this) {
            if (instance != null) return
            val cacheDir = File(context.cacheDir, "http")
            instance = OkHttpClient.Builder()
                .cookieJar(cookieJar)
                .cache(Cache(cacheDir, 64L * 1024 * 1024))
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .build()
        }
    }

    val client: OkHttpClient
        get() = instance ?: throw IllegalStateException("Http.init() 未调用")

    /** 清空所有 Cookie（调试用） */
    fun clearCookies() = cookieStore.clear()

    /** 当前持有的 Cookie（调试页展示） */
    fun cookieSummary(): String = cookieStore.entries.joinToString("\n") { (host, jar) ->
        "$host: " + jar.keys.joinToString(", ")
    }
}
