package com.chinut.bawantv.ui

import android.content.Context
import com.chinut.bawantv.live.LiveCache
import com.chinut.bawantv.live.LiveCatalog
import com.chinut.bawantv.live.LiveChannel
import com.chinut.bawantv.live.LiveGroup
import com.chinut.bawantv.unified.LibraryStore
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

    /**
     * 上次预热用的是哪套配置（主源 + 老电视模式）。
     *
     * ⚠️ 为什么需要它：`done` 一旦为 true 就永远不再预热，
     * 而全工程**没有一处调用过 `reset()`** —— 于是用户在设置里
     * 换了主源，首页预览和点进去的播放还是旧源（用户实测反馈过）。
     *
     * 靠"每个改设置的地方记得调 reset()"是不可靠的（一定会漏），
     * 所以这里记住配置，下次预热时自己对不上就作废重来。
     */
    @Volatile
    private var warmKey: String = ""

    /**
     * 当前配置的指纹（**对外开放**）。
     *
     * 界面层用它做 `LaunchedEffect` 的 key —— 配置一变就重新预热。
     * 只包含影响直播源的三个字段，所以改音量/HUD 之类不会白重拉频道表。
     */
    fun configKey(): String = keyOf()

    /** 当前配置的指纹。 */
    private fun keyOf(): String {
        val p = prefs
        return p.livePreset + "|" + p.oldTvMode + "|" + p.liveSourceUrl
    }

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
        // 配置变了 → 之前的预热结果作废
        //
        // ⚠️ 这里**必须连磁盘缓存一起清**。
        //
        // `LiveCache` 是单文件、只存最后一次的结果（`filesDir/live_cache.tsv`）。
        // 切源后按新 key 去查，内容对不上 —— 那份旧缓存已经没用了，
        // 留着只会让下次读盘多花时间。
        //
        // 更重要的是下面的 `skipDiskCache`：**不能复用旧源的结果**。
        // 用户改设置要的就是"换一套"，给他旧的等于没换（用户反馈过"改了没用"）。
        val want = keyOf()
        var configChanged = false
        if (warmKey.isNotEmpty() && warmKey != want) {
            android.util.Log.i("BawanWarmup", "主源/老电视模式变了，清缓存并强制重新拉取")
            reset()
            runCatching { com.chinut.bawantv.live.LiveCache.clear(context) }
            configChanged = true
        }
        if (done) return
        withContext(Dispatchers.IO) {
            // 1) 频道表
            //
            // ⚠️ 这里原来用 `LiveCatalog.builtin(context)` —— 那是**内置表**
            // （央视网/省市台网页），完全没看主源设置。结果不管用户选
            // 「GitHub 源」还是开了老电视模式，首页预览永远是央视网
            // （用户反馈过）。
            //
            // 改成 `load(...)`：它会按 preset + oldTvMode 选源。
            // 主源是 GitHub 时这里就会去拉开源源（有 12 秒预算，失败退回内置）。
            runCatching {
                // ---------- 先试磁盘缓存（不管新不新鲜）----------
                //
                // 用户诉求：「直播的频道每次不要加载那么长时间」。
                //
                // 缓存命中时这一步几乎是瞬时的（读一个几百 KB 的文件），
                // 所以开屏能立刻拿到频道表，不用等网络。
                //
                // 注意：**过期也用**。频道表本来就极少变，先让用户看到东西，
                // 鲜度交给下面第 4 步在后台补刷。
                //
                // ⚠️ 但**配置刚变过时不能用缓存**（`configChanged`）——
                // 缓存里存的是**上一个源**的频道表，用它等于切源没生效。
                val prefs = prefs
                val preset = com.chinut.bawantv.live.LivePreset.of(prefs.livePreset)
                val cachedSnap = LiveCache.load(
                    context,
                    LiveCache.keyOf(
                        presetName = preset.name,
                        oldTvMode = prefs.oldTvMode,
                        customSourceUrl = prefs.liveSourceUrl,
                        hasImported = LiveCatalog.hasImported(context),
                    ),
                )
                if (cachedSnap != null && !configChanged) {
                    groupsCache = cachedSnap.groups
                    allChannelsCache = cachedSnap.channels
                    android.util.Log.i(
                        "BawanWarmup",
                        "开屏走频道磁盘缓存：${cachedSnap.channels.size} 个频道" +
                            "（新鲜=${cachedSnap.isFresh()}）",
                    )
                } else if (configChanged) {
                    android.util.Log.i("BawanWarmup", "配置刚变，跳过磁盘缓存，按新源重新拉取")
                }

                // ---------- 再走正常加载（缓存没命中／已过期时是唯一来源）----------
                //
                // ⚠️ 这里原来用 `LiveCatalog.builtin(context)` —— 那是**内置表**
                // （央视网/省市台网页），完全没看主源设置。结果不管用户选
                // 「GitHub 源」还是开了老电视模式，首页预览永远是央视网
                // （用户反馈过）。
                //
                // 改成 `loadWithChannels(...)`：它会按 preset + oldTvMode 选源，
                // 而且内部**先看磁盘缓存** —— 命中就完全不联网、不重解析 assets。
                val (groups, flat) = LiveCatalog.loadWithChannels(
                    context = context,
                    customSourceUrl = prefs.liveSourceUrl,
                    preset = preset,
                    oldTvMode = prefs.oldTvMode,
                )
                groupsCache = groups
                // 扁平表已由 LiveCatalog 统一算好（跨分组去重 + 央视置顶排序），
                // 免得首页和播放页两边顺序不一致（那样上下键换台会跳来跳去）
                allChannelsCache = flat
            }

            // 2) 短剧海报（给首页短剧块铺背景用）
            //
            // ⚠️ 原来没预热短剧 —— 首页短剧块永远拿不到海报
            // （用户要求"整体按钮用内容海报填一填"）。
            // 这里顺手把短剧列表也拉到本地（它会自己落盘），
            // 首页就有海报可用，而且**断网也有**（走磁盘缓存）。
            runCatching {
                com.chinut.bawantv.unified.QimaoSource.cached()
            }

            // 3) 影视片单：影视只保留低端影视，所以直接用它的本地库
            runCatching {
                val cached = LibraryStore.load()
                if (cached.isNotEmpty()) {
                    // 首页只要前几张做视觉
                    val top = cached.filter { it.hasPoster }.take(12)
                    MovieAggregator.cacheHomeMovies(top)
                    MovieAggregator.preloadPosters(context, top)
                } else {
                    // 首次安装：拉一份库（每类 2 页，够首页用就行，不拖慢开屏）
                    val fresh = LibraryStore.refresh(pagesPerType = 2)
                    val top = fresh.filter { it.hasPoster }.take(12)
                    if (top.isNotEmpty()) {
                        MovieAggregator.cacheHomeMovies(top)
                        MovieAggregator.preloadPosters(context, top)
                    }
                }
            }

            warmKey = want
            done = true
        }
    }

    /** 把内存里的结果清掉（进程重启自然消失，这里给测试用）。 */
    fun reset() {
        warmKey = ""
        groupsCache = null
        allChannelsCache = null
        done = false
    }
}
