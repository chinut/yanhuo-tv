package com.chinut.bawantv.live

import android.content.Context

/**
 * 直连流地址缓存。
 *
 * ## 为什么需要它
 *
 * 央视这类站点不直接给 m3u8，必须先用 WebView 打开它的网页，
 * 从中**截获**播放器请求的那个 m3u8 地址，再交给 ExoPlayer 硬解。
 *
 * 但截获这件事成本极高：要起一个完整 Chromium（60~100MB 内存 + 持续 CPU），
 * 页面加载、JS 执行、播放器初始化，在电视上要十几秒。
 *
 * 而那个 m3u8 地址**基本是稳定的**（同一个频道长期不变）。
 * 所以把一个频道成功用过的直连地址记下来 —— 第二次打开该频道就可以
 * **完全跳过 WebView**，直接起播，几秒内出画面。
 *
 * 这对内存吃紧的电视尤其关键：省下的 Chromium 内存足够 ExoPlayer
 * 把缓冲做厚，卡顿会明显减少。
 *
 * ## 失效处理
 *
 * 直连地址是**可能过期**的（带签名参数、b 参数等）。所以：
 *   · 记时间戳，超过 [TTL_MS] 就先不用（放它走一次网页重新捕获）
 *   · 用缓存地址播放失败时，立刻 [forget]，下次重新捕获
 */
object StreamCache {

    /** 缓存有效期。央视的地址一般能用很久，但带签名的不一定 —— 取 6 小时偏保守。 */
    private const val TTL_MS = 6 * 60 * 60 * 1000L

    private const val SP = "live_stream_cache"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)

    private fun key(channelKey: String) = "s_" + channelKey.hashCode().toString(16)

    private fun stampKey(channelKey: String) = "t_" + channelKey.hashCode().toString(16)

    /** 取该频道缓存的直连地址；过期或没有则返回 null。 */
    fun get(ctx: Context, channelKey: String): String? {
        if (channelKey.isBlank()) return null
        val p = sp(ctx)
        val url = p.getString(key(channelKey), null)?.takeIf { it.isNotBlank() } ?: return null
        val at = p.getLong(stampKey(channelKey), 0L)
        if (at <= 0L || System.currentTimeMillis() - at > TTL_MS) {
            forget(ctx, channelKey)
            return null
        }
        return url
    }

    /** 记下这个频道可用的直连地址。 */
    fun put(ctx: Context, channelKey: String, streamUrl: String) {
        if (channelKey.isBlank() || streamUrl.isBlank()) return
        sp(ctx).edit()
            .putString(key(channelKey), streamUrl)
            .putLong(stampKey(channelKey), System.currentTimeMillis())
            .apply()
        android.util.Log.i("BawanLive", "已缓存直连地址 $channelKey -> ${streamUrl.take(90)}")
    }

    /** 忘掉该频道的缓存（地址失效时调用，下次会重新走网页截获）。 */
    fun forget(ctx: Context, channelKey: String) {
        if (channelKey.isBlank()) return
        sp(ctx).edit().remove(key(channelKey)).remove(stampKey(channelKey)).apply()
        android.util.Log.i("BawanLive", "已清除直连缓存 $channelKey")
    }

    /** 清空全部（设置里给用户一个"重来"的入口）。 */
    fun clearAll(ctx: Context) {
        sp(ctx).edit().clear().apply()
    }
}
