package com.chinut.bawantv.live

import com.chinut.bawantv.core.Net
import com.chinut.bawantv.core.Http

import android.content.Context

/**
 * 直播源预设（A / B）与「老电视模式」。
 *
 * # 为什么要有这个概念
 *
 * 直播有**两个完全不同的来源**，各有优劣：
 *
 * | | A · 默认引擎 | B · 开源源 |
 * |---|---|---|
 * | 来源 | App 内置频道表 | `best-fan/iptv-sources`（每日自动检测更新） |
 * | 播放方式 | 央视/省市台网页 → **WebView**；内置直连源 → ExoPlayer | **全部直连 m3u8** → ExoPlayer |
 * | 老电视表现 | WebView 吃力（央视频前端很重） | **轻**（纯直连，无网页渲染） |
 * | 覆盖 | 央视 + 31 省市的 500+ 网页源 | CCTV-1~17 + 37 卫视 + 付费台 |
 * | 央视直连 | **没有**（央视 CDN 花屏加密） | **有**（社区转发） |
 *
 * 所以：
 * · **老电视** → 用 B。全是直连流，不跑 WebView，内存和 CPU 压力小得多。
 * · **普通设备** → 用 A。覆盖面广。
 * · **想要都试** → 用 AB，两边并行加载，在播放页自己挑主源。
 *
 * # 「老电视模式」是什么
 *
 * 它不是一个独立开关，而是**同时决定两件事**：
 *   1. 源预设默认用 B（轻）
 *   2. B 的源优先挑**低分辨率**那条（见 [OpenSourceCatalog] 里的排序）
 *
 * 这样对用户来说只有一个概念：这台电视老，就打开它。
 */
enum class LivePreset(
    /** 界面上显示的名字（给用户看的，必须说人话）。 */
    val label: String,
    /** 一行说明，讲清楚这个选项到底会给到什么。 */
    val hint: String,
) {
    /**
     * 内置频道：App 自带的频道表。
     *
     * 走电视台网页（WebView）+ 内置直连源，覆盖面广，但网页播放对老电视吃力。
     */
    Default(
        "内置频道",
        "App 自带频道表，台最多；央视等走网页播放，老电视可能吃力",
    ),

    /**
     * GitHub 源：best-fan/iptv-sources，每天自动检测更新。
     *
     * 全是直连 m3u8，不跑网页，所以轻。含 CCTV-1~17 + 卫视。
     */
    OpenSource(
        "GitHub 源",
        "每天自动同步的直连源（含 CCTV-1~17），轻快，推荐老电视",
    ),

    /**
     * 双源：两边都加载。
     *
     * 台最多，但要拉两份列表，启动慢一点、占内存多一些。
     */
    Both(
        "双源",
        "内置 + GitHub 一起加载，台最全；启动稍慢",
    );

    companion object {
        fun of(name: String?): LivePreset =
            entries.firstOrNull { it.name == name } ?: Default
    }
}

/**
 * 开源源（B）的拉取与解析。
 *
 * # 数据来源
 *
 * `https://github.com/best-fan/iptv-sources` —— 每天凌晨自动采集 + 有效性验证，
 * 所以**不需要我们自己测**，直接用它的结果，省掉电视上的 CPU 和内存开销。
 *
 * 用 `_status` 变体：`tvg-name` 里带 `[1080]` / `[720]` / `[576]` 分辨率标记，
 * 老电视据此挑低码率那条（实测 576 的有 124 条、1080 的 168 条）。
 *
 * # 为什么不落盘缓存
 *
 * 这份列表一次才 ~100KB，直接拉比管缓存简单；而且**每次都是最新的** ——
 * 直播源失效很快，用一天前的缓存反而更容易看不了。
 * 只在内存里留 10 分钟，够一次会话内反复换台用。
 */
object OpenSourceCatalog {

    private const val TAG = "BawanOpenSrc"

    private const val BASE =
        "https://raw.githubusercontent.com/best-fan/iptv-sources/main/"

    /** 镜像：raw.githubusercontent 国内常被墙，jsDelivr 兜底。 */
    private const val MIRROR =
        "https://cdn.jsdelivr.net/gh/best-fan/iptv-sources@main/"

    /** 用 `_status` 变体（带分辨率标记）。 */
    private val FILES = listOf(
        "cn_all_status.m3u8",
        "cn_cctv_status.m3u8",
        "cn_province_status.m3u8",
        "cn_pay_status.m3u8",
    )

    data class Snapshot(
        val groups: List<LiveGroup>,
        val from: String,
        val date: String = "",
    )

    /**
     * 拉取的整体时间预算。
     *
     * 12 秒：正常情况下 4 个文件 2~4 秒就拉完；12 秒还没完基本就是被墙了，
     * 再等下去只会让用户对着转圈。宁可退回内置表，也不能不给画面。
     */
    private const val TOTAL_BUDGET_MS = 12_000L

    /** 单个文件的超时（比全局默认的 12s/20s 收窄很多）。 */
    private const val ONE_TIMEOUT_S = 5L

    @Volatile private var cached: Snapshot? = null
    @Volatile private var cacheAt: Long = 0L
    private const val CACHE_MS = 10 * 60 * 1000L

    fun cachedOrNull(): Snapshot? =
        cached?.takeIf { System.currentTimeMillis() - cacheAt < CACHE_MS }

    /**
     * 拉取并解析；失败依次退到镜像。
     *
     * @param preferLowRes 老电视模式：同台的多条源按分辨率**从低到高**排
     * @return 全失败返回 null —— 调用方应退回内置表，而不是让频道变空
     */
    suspend fun load(context: Context, preferLowRes: Boolean = true): Snapshot? {
        cachedOrNull()?.let { return it }

        // ⚠️ 总时长预算。
        //
        // 实测踩坑：OkHttp 是 connect 12s + read 20s，而这个列表有 4 个文件。
        // 如果 GitHub 被墙，串行拉完最坏要 80 秒 —— 期间频道表是空的，
        // 用户看到永久「正在读取频道表…」，遥控器按什么都没反应（真实反馈）。
        //
        // 所以给整体加一个预算：超了就放弃，让调用方退回内置表。
        // 单个请求的超时在下面单独收窄（见 fetchOne）。
        val deadline = System.currentTimeMillis() + TOTAL_BUDGET_MS

        for (base in listOf(BASE, MIRROR)) {
            if (System.currentTimeMillis() > deadline) {
                android.util.Log.w(TAG, "拉取超出总时长预算，放弃")
                break
            }
            val groups = ArrayList<LiveGroup>()
            var date = ""
            for (f in FILES) {
                if (System.currentTimeMillis() > deadline) break
                val text = fetchOne(base + f) ?: continue
                if (date.isEmpty()) {
                    date = Regex("#DATE:\\s*(.+)").find(text)
                        ?.let { it.groupValues[1].trim() }.orEmpty()
                }
                val g = parseStatus(text) ?: continue
                if (g.channels.isNotEmpty()) groups.add(g)
            }
            if (groups.isNotEmpty()) {
                val snap = Snapshot(
                    groups = groups,
                    from = if (base == BASE) "GitHub" else "jsDelivr",
                    date = date,
                )
                cached = snap
                cacheAt = System.currentTimeMillis()
                android.util.Log.i(
                    TAG,
                    "开源源加载成功（${snap.from}，$date）：" +
                        "${groups.sumOf { it.channels.size }} 个地址 / " +
                        "${groups.sumOf { it.channels.size }} 条",
                )
                return snap
            }
        }
        android.util.Log.w(TAG, "开源源全部拉取失败（GitHub 和镜像都不通）")
        return null
    }

    /**
     * 拉一个文件，用**收窄过的超时**。
     *
     * 默认的 Http.client 是 connect 12s / read 20s —— 对"拉个小文本列表"
     * 来说太长了。这里单独建一个短超时的 client（复用连接池/拦截器）。
     */
    private suspend fun fetchOne(url: String): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val cli = com.chinut.bawantv.core.Http.client.newBuilder()
                    .connectTimeout(ONE_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(ONE_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val req = okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", com.chinut.bawantv.core.Http.UA_DESKTOP)
                    .build()
                cli.newCall(req).execute().use { r ->
                    if (r.isSuccessful) r.body?.string() else null
                }
            }.getOrNull()
        }

    /** 解析中间结构：台名 → 该台的所有 (地址, 分辨率)。 */
    private class Entry(val url: String, val res: Int)

    /**
     * 解析 `_status` 格式。
     *
     * ```
     * #EXTINF:-1 tvg-name="CCTV1[1080][S]" tvg-logo="..."
     * http://63.141.230.178:82/gslb/zbdq5.m3u8?id=cctv1hd
     * ```
     *
     * 也有不带 `tvg-name` 的条目，退回取逗号后的显示名。
     */

    /**
     * 台名归一化，用于**合并同一个台的不同写法**。
     *
     * 源文件里同一个台有多种写法，实测同时出现：
     *
     *     tvg-name="CCTV2"                       → "CCTV2"
     *     tvg-id="CCTV2.cn@SD",CCTV-2 (720p)     → "CCTV-2 (720p)"
     *
     * 不归一化的话，同一个 CCTV-2 会在频道墙上占两格（踩过）。
     *
     * 规则：
     *   · 去掉 [1080] / [S] / (720p) 这类标记
     *   · `CCTV-2` 和 `CCTV2` 统一成 `CCTV2`
     *   · 收掉多余空格
     *
     * ⚠️ 只用来做**合并的 key**，显示仍然用归一化后的名字 ——
     * 「CCTV-2 (720p)」这种名字对用户没有意义，反而显得乱。
     */
    private fun normalizeName(raw: String): String {
        var s = raw
        s = s.replace(Regex("\\[\\d{3,4}\\]"), "")      // [1080]
        s = s.replace(Regex("\\[[A-Za-z]\\]"), "")          // [S]
        s = s.replace(Regex("\\(\\s*\\d{3,4}[pi]?\\s*\\)"), "")  // (720p)
        s = s.replace(Regex("(?i)^(CCTV|CGTN)\\s*-\\s*"), "$1")  // CCTV-2 → CCTV2
        s = s.replace(Regex("\\s+"), " ")
        return s.trim()
    }

    private fun parseStatus(text: String): LiveGroup? {
        val byName = LinkedHashMap<String, MutableList<Entry>>()
        var name: String? = null
        var res = 0

        for (raw in text.split('\n')) {
            val s = raw.trim()
            if (s.startsWith("#EXTINF")) {
                val tn = Regex("tvg-name=\"([^\"]+)\"").find(s)?.let { it.groupValues[1] }
                val after = s.substringAfterLast(',', "").trim()
                val display = tn?.takeIf { it.isNotBlank() } ?: after
                res = Regex("\\[(\\d{3,4})\\]").find(display)
                    ?.let { it.groupValues[1].toIntOrNull() } ?: 0
                // 括号里的 (720p) / (1080p) 也是分辨率信息，顺手抓一下
                if (res == 0) {
                    res = Regex("\\((\\d{3,4})[pi]?\\)").find(display)
                        ?.let { it.groupValues[1].toIntOrNull() } ?: 0
                }
                name = normalizeName(display)
                    .ifBlank { null }
            } else if (s.isNotEmpty() && !s.startsWith("#")) {
                val n = name ?: continue
                if (!s.startsWith("http")) { name = null; continue }
                byName.getOrPut(n) { mutableListOf() }.add(Entry(s, res))
                name = null
            }
        }
        if (byName.isEmpty()) return null

        val channels = ArrayList<LiveChannel>(byName.size)
        byName.forEach { (n, list) ->
            // 同一个台有多条源：**分辨率低的排前面**（老电视优先用轻的）。
            // 0 表示没解析出分辨率，排最后 —— 宁可用已知的低清，也不赌未知的。
            val sorted = list.sortedWith(
                compareBy({ if (it.res == 0) Int.MAX_VALUE else it.res }, { it.url }),
            )
            val first = sorted.first()
            channels.add(
                LiveChannel(
                    name = n,
                    url = first.url,
                    group = "IPTV",
                    alternates = sorted.drop(1).map { it.url }.distinct(),
                ),
            )
        }
        return LiveGroup("开源源", channels)
    }
}
