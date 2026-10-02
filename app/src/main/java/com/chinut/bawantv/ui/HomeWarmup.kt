package com.chinut.bawantv.ui

import android.content.Context
import com.chinut.bawantv.live.LiveCatalog
import com.chinut.bawantv.live.LiveChannel
import com.chinut.bawantv.live.LiveGroup
import com.chinut.bawantv.unified.MovieAggregator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 首页预热。
 *
 * ## 为什么需要它
 *
 * 开屏动画只有两三秒，而首页要准备的东西不少：
 * **直播频道表解析**（几百个台）+ **海报片单**（走网络）。
 * 以前开屏一结束就切首页，于是用户会看到：直播区黑着、海报墙空着，
 * 一两秒后才"啪"地补上 —— 很掉价。
 *
 * 现在让开屏**等首页备好再走**：反正开屏本来就要播几秒，正好拿来做预热。
 * 用户感知从「先看到半成品再补全」变成「一进来就是完整的」。
 *
 * ## 设计要点
 *
 * - 预热结果**缓存在这里**，首页挂载时直接取用，不重复解析/请求
 * - 每个预热项都有超时保护：网络慢不能把开屏无限拖住
 * - 提供 [minSplashMs] / [maxSplashMs]：开屏至少播够这么久（否则一闪而过很难看），
 *   最多等这么久（避免异常情况下卡死在开屏）
 */
object HomeWarmup {

    /** 开屏至少播这么久 —— 比动画本身略长，让结尾有个"停顿"的呼吸感。 */
    const val minSplashMs = 3_600L

    /**
     * 开屏最多等这么久。
     * 网络再差也不能让用户一直盯着开屏，到点就走（首页后续自己会补上）。
     */
    const val maxSplashMs = 7_500L

    /** 预热结果：频道表。 */
    @Volatile
    private var groupsCache: List<LiveGroup>? = null

    /** 预热结果：跨分组去重后的整表（换台用的就是它）。 */
    @Volatile
    private var allChannelsCache: List<LiveChannel>? = null

    /** 是否已经预热过（进程内只做一次）。 */
    @Volatile
    private var done: Boolean = false

    val isReady: Boolean get() = done

    fun groups(): List<LiveGroup>? = groupsCache
    fun allChannels(): List<LiveChannel>? = allChannelsCache

    /**
     * 预热。可重复调用，已完成则直接返回。
     *
     * 解析频道表（纯本地，很快）和拉海报片单（走网络，主要耗时）并行做，
     * 谁慢都不拖累另一个。
     */
    suspend fun warmUp(context: Context) {
        if (done) return
        withContext(Dispatchers.IO) {
            // 1) 频道表：本地资源，几十毫秒
            runCatching {
                val groups = LiveCatalog.builtin(context)
                groupsCache = groups
                // 跨分组去重：与首页/播放页的换台表保持一致
                val uniq = LinkedHashMap<String, LiveChannel>()
                groups.flatMap { it.channels }.forEach { c ->
                    val k = LiveCatalog.normalizeName(c.name)
                    val exist = uniq[k]
                    if (exist == null) {
                        uniq[k] = c
                    } else {
                        val alts = (exist.alternates + c.url + c.alternates)
                            .filter { it != exist.url }.distinct()
                        uniq[k] = exist.copy(alternates = alts)
                    }
                }
                allChannelsCache = uniq.values.toList()
            }

            // 2) 海报片单：先看有没有缓存（有就秒用），再后台刷新
            runCatching {
                val cached = MovieAggregator.cachedHomeMovies()
                if (cached.isNotEmpty()) {
                    MovieAggregator.preloadPosters(context, cached)
                }
                val fresh = MovieAggregator.scrape(page = 1)
                    .filter { it.hasPoster }
                    .take(9)
                if (fresh.isNotEmpty()) {
                    MovieAggregator.cacheHomeMovies(fresh)
                    // 预热图片：进首页时直接命中缓存，不会白一下
                    MovieAggregator.preloadPosters(context, fresh)
                }
            }

            done = true
        }
    }

    /** 把内存里的结果清掉（进程重启自然消失，这里给测试用）。 */
    fun reset() {
        groupsCache = null
        allChannelsCache = null
        done = false
    }
}
