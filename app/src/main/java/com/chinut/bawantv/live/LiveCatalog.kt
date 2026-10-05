package com.chinut.bawantv.live

import kotlinx.coroutines.async

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.chinut.bawantv.core.Http
import com.chinut.bawantv.core.Net
import java.net.URLDecoder

/** 一个频道。 */
data class LiveChannel(
    val name: String,
    val url: String,
    val group: String = "其他",
    val logo: String = "",
    /** 同名频道的备用源（换源用） */
    val alternates: List<String> = emptyList(),
)

/** 一个频道分组。 */
data class LiveGroup(
    val name: String,
    val channels: List<LiveChannel>,
)

/**
 * 直播源解析与频道目录。
 *
 * 支持两种格式：
 *  1. 内置 m3u（assets/live/cctv.m3u，由音乐库项目的 cctv/tv.json 转换而来）
 *  2. 用户自定义直播源：m3u / m3u8 播放列表，或「台名,地址」每行一条的 txt
 *
 * 老电视体验：同名频道在多个分组里出现时，自动合并成「备用源」，
 * 当前源播不出来直接按一下换源键就切下一个地址。
 */
object LiveCatalog {

    private const val TAG = "BawanLiveCatalog"

    private const val BUILTIN_ASSET = "live/cctv.m3u"

    @Volatile
    private var cachedBuiltin: List<LiveGroup>? = null

    /**
     * 读取内置频道表。
     *
     * 频道表里有相当一部分是「电视台网页」（例如 `tv.cctv.com/live/cctv13/`），
     * 这些地址交给 ExoPlayer 必然报 `PARSING_MANIFEST_MALFORMED`。
     * 所以加载后做一次「补源」：给每个网页类频道挂上长期可用的直连 HLS 备用源。
     */
    fun builtin(context: Context): List<LiveGroup> {
        cachedBuiltin?.let { return it }
        return synchronized(this) {
            cachedBuiltin ?: run {
                // 只读 cctv.m3u —— 它现在**只含「央视网 + 央视频」两类网页频道**。
                //
                // ⚠️ 这里原来还会并入 `iptv.m3u` / `iptv_verified.m3u` 两个直连源文件。
                // 用户要求「主源里只保留央视网和央视频使用网页进入的台，剩下全部抛弃」
                // 之后，那两个文件就**不该再混进主源**了：
                //   · 直连源属于「GitHub 源」那一侧（而且那边是运行时实时拉的，更新得多）
                //   · 混在一起会让「主源」这个名字名不副实 —— 用户选主源时
                //     以为自己选的是"央视网页"，结果列表里一堆直连台
                val text = runCatching {
                    context.assets.open(BUILTIN_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
                }.getOrDefault("")
                val groups = parse(text).ifEmpty { fallbackCctv() }
                cachedBuiltin = groups
                groups
            }
        }
    }

    /*
     * ==================== 这里曾经尝试"直连流优先"，已撤销 ====================
     *
     * 起因是真机反馈：老电视看直播"卡到无法观看，大部分时间停在央视频的
     * 加载占位图上"。当时的判断是"让 WebView 跑央视频整站太重"，于是改成
     * 直接把流地址交给 ExoPlayer 硬解。
     *
     * **这个判断是错的，而且我犯了一个具体的错误：**
     *
     * 我给这些地址做的验证只有一条 —— "返回的文本以 #EXTM3U 开头"。
     * 但那个 CDN 上放的其实是**纯音频流**：
     *
     *     https://piccpndali.v.myalicdn.com/audio/cctv1_2.m3u8
     *                                          ^^^^^ 路径里就写着 audio
     *     #EXTINF:10.006,
     *     cctv1_audio/1790960938_14541823.ts      ← 分片名也带 audio
     *
     * 结果用户看到的是**只有声音、没有画面**。
     * 教训：验证媒体流必须确认**有没有视频轨**（看分片路径 / CODECS），
     * 光看"是不是合法播放列表"远远不够。
     *
     * 之后我又逐个实测了其他公开的央视直连源（电信/百视通/ivi/山东移动等），
     * **全部返回 502（早已下线）**。也就是说：
     *
     *   · 没有可用的央视直连视频流；
     *   · 央视的流是专有加密格式，只能由台站自己的网页播放器解。
     *
     * 所以现在**不再硬塞任何直连地址**，网页路线是唯一可行方案。
     * 它的性能问题不用改路线来解决，而是靠：
     *   · TvWebPlayerView 的强力起播 + 元素清理脚本
     *   · 直播页的预加载（出画面才显示 WebView，用户只看到转圈）
     *   · 连续失败时自动换下一个源
     */

    // ==================== 用户导入的本地 m3u ====================
    //
    // 为什么要有这个：用户的 IPTV 源往往是**文件**（运营商给的、
    // 或朋友发来的），让他在电视上用遥控器敲一个长网址不现实。
    //
    // 文件复制到 App 私有目录，而不是直接用 content:// URI ——
    // 因为 SAF 给的临时授权在重启后可能失效，那样用户会发现
    // "昨天还好好的源今天没了"。

    /** 导入文件的存放位置（App 私有目录）。 */
    fun importedFile(context: Context): java.io.File =
        java.io.File(context.filesDir, "custom_live.m3u")

    fun hasImported(context: Context): Boolean =
        importedFile(context).let { it.exists() && it.length() > 0 }

    /**
     * 把用户选中的文件复制进来。
     *
     * @return 成功时返回频道条数；失败返回 -1
     */
    fun importFrom(context: Context, uri: android.net.Uri): Int {
        return try {
            val text = context.contentResolver.openInputStream(uri)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: return -1
            if (text.isBlank()) return -1
            // 先解析一遍，确认真的是频道表（避免用户选错文件后
            // 频道列表整个变空，还找不到原因）
            val groups = parse(text)
            val n = groups.sumOf { it.channels.size }
            if (n == 0) {
                android.util.Log.w(TAG, "导入失败：文件里没解析出频道")
                return -1
            }
            importedFile(context).writeText(text, Charsets.UTF_8)
            android.util.Log.i(TAG, "导入成功：$n 个频道 → ${importedFile(context).name}")
            n
        } catch (e: Exception) {
            android.util.Log.w(TAG, "导入异常：${e.javaClass.simpleName} ${e.message}")
            -1
        }
    }

    fun clearImported(context: Context) {
        runCatching { importedFile(context).delete() }
    }

    /**
     * 最终频道表。
     *
     * # 组装顺序（前面的盖后面的）
     *
     *   1. **用户导入的文件** —— 最贴身，永远最优先
     *   2. 按 [LivePreset] 选出来的引擎
     *      · [LivePreset.Default]    → 内置频道表（A）
     *      · [LivePreset.OpenSource] → best-fan 开源源（B）
     *      · [LivePreset.Both]       → A 和 B 并行，两边都放进去
     *   3. 自定义网址
     *
     * # 为什么要区分 A / B
     *
     * A 里很多是**电视台网页**，播放要走 WebView —— 老电视跑不动。
     * B 全是**直连 m3u8**，ExoPlayer 直接解，负载低得多。
     * 所以老电视模式开的时候默认走 B（见 [LivePreset] 的说明）。
     */
    suspend fun load(
        context: Context,
        customSourceUrl: String,
        preset: LivePreset = LivePreset.Default,
        oldTvMode: Boolean = false,
    ): List<LiveGroup> =
        loadWithChannels(context, customSourceUrl, preset, oldTvMode).first

    /**
     * 一次把**分组**和**扁平表**都给出来（首页两个都要用）。
     *
     * 顺便带**磁盘缓存** —— 这让频道冷启动从
     * 「每次重解析 544 个频道 + 每次去拉 GitHub 超时 12 秒」
     * 变成「读一个几百 KB 的文件」。
     *
     * 用户原话：「通常来说用户安装完 app 且首次设置完成后基本不会动设置，
     * 那么我希望直播的频道每次不要加载那么长时间，
     * 有没有什么机制可以让用户的频道可以加载得快一点」
     *
     * @param forceRefresh 跳过缓存直接重算（设置页"重新加载"用）
     * @return (分组, 扁平表)
     */
    suspend fun loadWithChannels(
        context: Context,
        customSourceUrl: String,
        preset: LivePreset = LivePreset.Default,
        oldTvMode: Boolean = false,
        forceRefresh: Boolean = false,
    ): Pair<List<LiveGroup>, List<LiveChannel>> {
        // ---------- 缓存指纹 ----------
        //
        // 必须把影响结果的**每个**输入都算进去：开关老电视模式、
        // 改自定义网址、有没有导入本地源。少算一个就会出现"设置改了但列表没变"
        // （这个 bug 用户反馈过 —— 见 HomeWarmup 里那段注释）。
        //
        // ⚠️ `preset` 已经**不再**参与引擎选择了（规则由 oldTvMode 唯一决定，
        // 见下面那段长注释）。但它仍然要进缓存键吗？**不要** ——
        // 它既然不影响输出，放进去只会让同一份数据在缓存里存多份
        // （老用户 prefs 里可能还残留 "Both"/"OpenSource"）。
        // 唯一的作用是让缓存失效得更频繁，没有好处。
        val cacheKey = LiveCache.keyOf(
            oldTvMode = oldTvMode,
            customSourceUrl = customSourceUrl,
            hasImported = hasImported(context),
        )

        // ---------- 统一收口：落盘 + 算扁平表 + 返回 ----------
        //
        // load() 里有 5 个返回点，一个个改必然漏，
        // 所以全部走这个局部函数。
        //
        // @param cache 是否允许落盘。**退回兜底源时必须为 false**（见下）。
        fun done(
            groups: List<LiveGroup>,
            cache: Boolean = true,
        ): Pair<List<LiveGroup>, List<LiveChannel>> {
            val flat = flattenForZapping(groups)
            if (cache) {
                // 落盘放后台：这是 IO，用户没必要等它（订阅里报错也不影响这次返回）
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching { LiveCache.save(context, groups, flat, cacheKey) }
                }
            } else {
                android.util.Log.w(
                    TAG,
                    "本次结果是**兜底数据**（不是目标源），不落盘 —— " +
                        "否则下次启动会直接读这份假数据",
                )
            }
            return groups to flat
        }

        // ---------- 1) 先看磁盘缓存 ----------
        if (!forceRefresh) {
            val snap = LiveCache.load(context, cacheKey)
            if (snap != null) {
                val fresh = snap.isFresh()
                android.util.Log.i(
                    TAG,
                    "频道表走磁盘缓存：${snap.groups.size} 组 / ${snap.channels.size} 个频道" +
                        "（新鲜=$fresh）",
                )
                // ---------- 过期就**后台补刷**（stale-while-revalidate）----------
                //
                // ⚠️ 原来这里过期了也直接把旧数据返回，**什么都不做** ——
                // 结果 GitHub 订阅源最多 6 小时（`LiveCache.TTL_MS`）才更新一次，
                // 而且过期后也不会自己刷，非得等用户清缓存。
                //
                // 用户明确要求：「确保 github 源的电视节目会自动更新订阅，
                // 不用升级软件就能自动更新」。
                //
                // 做法：这次先用旧数据（保证秒开，不卡首屏），
                // 同时在后台按新配置重新拉一遍并落盘 —— 下次进 App 就是新的。
                //
                // 为什么不"过期就阻塞等新数据"：老电视上拉 4 个列表最坏 12 秒，
                // 用户对着转圈等 12 秒是更糟的体验（这个坑踩过）。
                if (!fresh) {
                    android.util.Log.i(TAG, "缓存已过期 → 后台补刷（不阻塞本次）")
                    CoroutineScope(Dispatchers.IO).launch {
                        runCatching {
                            // forceRefresh=true 才会跳过缓存真的去拉
                            loadWithChannels(
                                context = context,
                                customSourceUrl = customSourceUrl,
                                preset = preset,
                                oldTvMode = oldTvMode,
                                forceRefresh = true,
                            )
                            android.util.Log.i(TAG, "后台补刷完成")
                        }.onFailure {
                            android.util.Log.w(TAG, "后台补刷失败（保留旧数据）：${it.message}")
                        }
                    }
                }
                return snap.groups to snap.channels
            }
        } else {
            android.util.Log.i(TAG, "强制刷新，跳过频道缓存")
        }

        // ---------- 2) 缓存没命中：走原来的老路 ----------
        val builtin = builtin(context)

        // ---------- 先算出"引擎"部分 ----------
        //
        // AB 时并行拉 —— 串行会让首屏等两倍时间。
        // 开源源失败就只留内置，**绝不让频道表变空**（那用户就什么都看不了了）。
        // 老电视模式 = 强制 GitHub 源。
        //
        // 为什么不靠"改 prefs.livePreset"来实现：那样用户关掉开关后
        // 就找不回原来选的主源了。这里**运行时覆盖**，设置值保持不动，
        // 关掉开关自然恢复。
        //
        // 用户原话：「开启老电视模式了就只用 iptv 源就好了啊」。
        // ---------- 引擎选择：由「老电视模式」唯一决定 ----------
        //
        // ## 用户定义的规则（原话，带例子）
        //
        //     「比如主源有4个频道，github有4个频道
        //       老电视模式开启时  直播列表里应该有4个频道都是来自于github，顺序为1234
        //       关闭老电视模式后  直播列表里应该有8个频道，顺序为 主源频道1234 github频道1234」
        //
        // 也就是：
        //   · 老电视模式 **开** → 只加载 GitHub 源（直连、轻，不用 WebView）
        //   · 老电视模式 **关** → 两个都加载，**主源（央视网/央视频网页）在前**
        //
        // ## 为什么不再看 `preset`
        //
        // 原来这里是 `if (oldTvMode) OpenSource else preset`，而 `preset` 是设置里
        // 那个「主源」切换项。用户已要求**移除那个切换项** —— 既然只有一个可选行为，
        // 就不该再读一个可能残留旧值的设置（老用户 prefs 里可能还存着
        // "Both"/"OpenSource"，会和新规则打架）。
        //
        // ## 顺序为什么改成「主源在前」
        //
        // 用户要求「主源频道放入源列表且**置顶**」。
        // 原来 Both 分支是 `b + a`（直连源在前），理由是"直连更省资源"——
        // 那是旧取舍，现在按用户要求把主源放最上面。
        // 这次用的是**兜底数据**吗？
        //
        // ## 为什么必须区分（这是个实测出来的真 bug）
        //
        // 用户电视（192.168.31.233，小米电视4 / Android 6）上
        // **GitHub 连不通**（DNS 能解析但 TCP 被拦），于是
        // `openSourceGroups` 返回 null，代码退回内置表。
        //
        // 但退回的结果**被当成正常结果落盘了**，而且 key 是老电视模式的 key：
        //
        //     BawanOpenSrc: 开源源全部拉取失败（GitHub 和镜像都不通）
        //     BawanLiveCache: 频道表已落盘：24 组 / 55 个频道   ← 内置表！
        //
        // 下一次启动直接命中这份缓存 —— **老电视模式开着，列表却是主源的频道**。
        // 用户看到的就是"设置对了但列表不对"。
        //
        // 修法：兜底数据**不落盘**。这样每次启动都会重新尝试拉 GitHub 源
        // （万一网络恢复了就拿到真数据），而不是被一份假缓存永久顶住。
        var usedFallback = false

        val engine: List<LiveGroup> = if (oldTvMode) {
            // 老电视：只要 GitHub 源
            val open = openSourceGroups(context, oldTvMode)
            if (open == null) {
                usedFallback = true
                android.util.Log.w(
                    TAG,
                    "老电视模式：GitHub 源拉取失败，本次退回内置表（**不落盘**）",
                )
            }
            open ?: builtin
        } else {
            // 普通：主源在前，GitHub 源在后
            kotlinx.coroutines.coroutineScope {
                val dMain = async<List<LiveGroup>> { builtin }
                val dOpen = async<List<LiveGroup>?> { openSourceGroups(context, oldTvMode) }
                val main = dMain.await()
                val open = dOpen.await()
                if (open == null) main else main + open
            }
        }

        // 1) 本地导入的文件
        if (hasImported(context)) {
            val text = runCatching {
                importedFile(context).readText(Charsets.UTF_8)
            }.getOrNull()
            if (!text.isNullOrBlank()) {
                val custom = parse(text)
                if (custom.isNotEmpty()) {
                    val merged = ArrayList<LiveGroup>(custom.size + engine.size)
                    custom.forEach { g ->
                        merged.add(g.copy(name = "我的源 · ${g.name}"))
                    }
                    merged.addAll(engine)
                    android.util.Log.i(
                        TAG,
                        "用本地导入的源：${custom.sumOf { it.channels.size }} 个频道",
                    )
                    return done(mergeAlternates(merged))
                }
            }
            android.util.Log.w(TAG, "本地导入的文件解析不出频道，退回内置")
        }

        // 2) 自定义网址
        val url = customSourceUrl.trim()
        if (url.isEmpty()) return done(engine, cache = !usedFallback)

        val text = Net.get(url, ua = Http.UA_MOBILE) ?: return done(engine, cache = !usedFallback)
        val custom = parse(text)
        if (custom.isEmpty()) return done(engine, cache = !usedFallback)

        val merged = ArrayList<LiveGroup>(custom.size + engine.size)
        custom.forEach { g ->
            merged.add(g.copy(name = "自定义 · ${g.name}"))
        }
        merged.addAll(engine)
        return done(mergeAlternates(merged))
    }

    /**
     * 解析 m3u / 纯文本频道表。
     */

    /**
     * 拉开源源（B）。失败返回 null，调用方退回内置表。
     *
     * 把"失败"和"空"分开：失败 → null（退回内置）；
     * 拉到了但一个台都没有 → 也当失败（不然频道表会空）。
     */
    private suspend fun openSourceGroups(
        context: Context,
        oldTvMode: Boolean,
    ): List<LiveGroup>? {
        val snap = OpenSourceCatalog.load(context, preferLowRes = oldTvMode) ?: return null
        val groups = snap.groups.filter { it.channels.isNotEmpty() }
        if (groups.isEmpty()) return null
        // 分组名上标一下来源，用户看得出现在用的是哪个引擎
        return groups.map { it.copy(name = "开源源 · ${it.name}") }
    }

    fun parse(text: String): List<LiveGroup> {
        if (text.isBlank()) return emptyList()
        return if (text.contains("#EXTINF", ignoreCase = true)) parseM3u(text) else parsePlain(text)
    }

    private fun parseM3u(text: String): List<LiveGroup> {
        val out = LinkedHashMap<String, MutableList<LiveChannel>>()
        var pendingName: String? = null
        var pendingGroup = "默认"
        var pendingLogo = ""

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.isEmpty() -> Unit

                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    // #EXTINF:-1 tvg-logo="..." group-title="央视",CCTV-1 综合
                    val comma = line.lastIndexOf(',')
                    pendingName = if (comma >= 0) line.substring(comma + 1).trim() else null
                    pendingGroup = attr(line, "group-title") ?: attr(line, "tvg-group") ?: "默认"
                    pendingLogo = attr(line, "tvg-logo") ?: ""
                }

                line.startsWith("#") -> Unit

                else -> {
                    val name = pendingName?.takeIf { it.isNotBlank() }
                        ?: line.substringAfterLast('/').substringBefore('?').ifBlank { "未知频道" }
                    if (line.startsWith("http", ignoreCase = true)) {
                        val direct = directStreamOf(line) ?: line
                        out.getOrPut(pendingGroup) { mutableListOf() }
                            .add(LiveChannel(name = name, url = direct, group = pendingGroup, logo = pendingLogo))
                    }
                    pendingName = null
                    pendingLogo = ""
                }
            }
        }
        return out.entries
            .filter { it.value.isNotEmpty() }
            .map { LiveGroup(it.key, it.value) }
            .let(::mergeAlternates)
    }

    /** 「台名,地址」纯文本格式（老式 TV 源常见）。 */
    private fun parsePlain(text: String): List<LiveGroup> {
        val out = LinkedHashMap<String, MutableList<LiveChannel>>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val sep = line.indexOf(',')
            if (sep <= 0) return@forEach
            val name = line.substring(0, sep).trim()
            val addr = line.substring(sep + 1).trim()
            if (!addr.startsWith("http", ignoreCase = true)) return@forEach
            val group = when {
                name.contains("CCTV") || name.contains("央视") -> "央视"
                name.contains("卫视") -> "卫视"
                else -> "默认"
            }
            out.getOrPut(group) { mutableListOf() }
                .add(LiveChannel(name = name, url = directStreamOf(addr) ?: addr, group = group))
        }
        return out.entries.map { LiveGroup(it.key, it.value) }.let(::mergeAlternates)
    }

    private fun attr(line: String, key: String): String? {
        val re = Regex("""$key\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
        return re.find(line)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * 同名频道合并备用源。
     * 归一化名称（去掉空格、-、·、频道号后缀等），把同名但地址不同的合成一个频道。
     */
    fun mergeAlternates(groups: List<LiveGroup>): List<LiveGroup> {
        data class Acc(val channel: LiveChannel, val alts: LinkedHashSet<String>)

        val seen = HashMap<String, Acc>()
        groups.forEach { g ->
            g.channels.forEach { ch ->
                val key = normalizeName(ch.name)
                val acc = seen[key]
                if (acc == null) {
                    seen[key] = Acc(ch, linkedSetOf())
                } else {
                    if (!acc.alts.contains(ch.url) && acc.channel.url != ch.url) acc.alts.add(ch.url)
                }
            }
        }
        // 回填 alternates
        val finalChannels = seen.mapValues { (_, acc) ->
            acc.channel.copy(alternates = acc.alts.toList())
        }
        return groups.map { g ->
            g.copy(channels = g.channels.mapNotNull { finalChannels[normalizeName(it.name)] })
        }.filter { it.channels.isNotEmpty() }
    }

    /**
     * 并表用的频道 key。
     *
     * 和 [normalizeName] 的区别：**央视台只认频道号**。
     *
     * 实测踩的坑：`CCTV1` 和 `CCTV-1 综合` 归一化后是 `cctv1` 和 `cctv1综合`，
     * 两个 key 不一样 → **同一个 CCTV-1 在换台表里出现两次**，上下键要走过两遍。
     *
     *     cctv1 / cctv1综合            → cctv1
     *     cctv5 / cctv5体育 / cctv5plus → cctv5（"+"归一化时已被去掉）
     *     cctv4k / cctv8k              → cctv4k / cctv8k（**独立频道，不能并进 cctv4/8**）
     *
     * 为什么不直接改 [normalizeName]：那个还被「记住台用哪个源」
     * （StreamCache / SourceDoctor）用着，改成只认编号会让 4K 台和普通台串味。
     */
    fun channelKeyOf(name: String): String {
        val s = stripResolution(normalizeName(name))
        if (!s.startsWith("cctv")) return s

        // 1) 真 4K / 8K：`cctv4k…` / `cctv8k…`
        //
        // 这里要求 k 后面**还有内容**：
        //   `CCTV-8K HD` → `cctv8k`   （真 8K）✅
        //   `CCTV-4 K`   → `cctv4k`   （**监控版**，k 后面没东西）
        // 两者归一化后一样，只能靠"k 后面还有没有字符"区分。
        Regex("^cctv([48])k(.)").find(s)?.let {
            return "cctv" + it.groupValues[1] + "k"
        }
        // 2) `CCTV4K` / `CCTV8K` 这种不带后缀的
        Regex("^cctv([48])k$").find(s)?.let {
            return "cctv" + it.groupValues[1] + "k"
        }
        // 3) 监控版后缀：纯编号后面紧跟一个 k（`CCTV-4 K` / `CCTV-8 K`）
        Regex("^cctv(\\d+)k$").find(s)?.let {
            return "cctv" + it.groupValues[1]
        }
        // 4) `CCTV-5+`（"+"在 normalizeName 里被去掉，剩下 plus）
        Regex("^cctv(\\d+)plus").find(s)?.let {
            return "cctv" + it.groupValues[1]
        }
        // 5) 其余按频道号
        Regex("^cctv(\\d+)").find(s)?.let { return "cctv" + it.groupValues[1] }
        // 6) 非编号的 CCTV 台（风云剧场 / Storm Music / 怀旧剧场 …）
        //    也归央视区（不然它们会掉到地方台后面，破坏"置顶一定是 CCTV"）
        return s
    }

    /**
     * 剥掉分辨率 / 清晰度标记。
     *
     * 踩过的坑：`CCTV-9 (576i)` 归一化后是 `cctv9576i`，
     * 匹配不上 `^cctv(\d+)` → 落不到央视区、**没有置顶**。
     * 同类还有 `CCTV-10 HD` → `cctv10hd`。
     *
     * 顺序要紧：先剥"数字+i/p"（576i / 1080p），再剥纯字母（hd / sd / s）。
     * 注意**不能**在这里剥 4k/8k —— 那是频道身份，不是清晰度标记。
     */
    private fun stripResolution(s: String): String = s
        .replace(Regex("(\\d{3,4})(i|p)?$"), "")
        .replace(Regex("(hd|sd|s)" + "$"), "")
        .replace(Regex("(hd|sd)(?=[^a-z])"), "")

    /**
     * 央视台的排序权重。
     *
     * 返回 null 表示"不是央视台"。
     *
     * 为什么要自己算权重：频道名归一化之后是 `CCTV1` / `CCTV10`，
     * 直接按字符串排会得到 **CCTV1, CCTV10, CCTV11, …, CCTV2, CCTV3** ——
     * CCTV-1 后面跟着 CCTV-10，完全不符合电视台的顺序。
     *
     * 规则：
     *   · `CCTV-5+` 排在 5 和 6 之间 → 用 5.5 当权重
     *   · `CCTV-4K` / `CCTV-8K` 排在 CCTV-17 之后
     *   · CGTN 各语种排在 CCTV 之后
     */
    private fun cctvRank(name: String): Double? {
        // ⚠️ 必须用 channelKeyOf 的结果来判断，不能自己再写一遍正则。
        //
        // 踩过的坑：原来这里直接对名字跑 `^CCTV(\d+)`，
        // 但 `CCTV-14 HD (1080p)` 归一化后是 `cctv14hd1080p`，
        // 与 `^CCTV` 不匹配 → 落到兜底的 91.0 → **排到 CCTV-17 后面去了**。
        // 而 channelKeyOf 已经把这类都归成了 cctv14，两者不一致就会出现这种错位。
        val k = channelKeyOf(name)
        // 4K / 8K 贴着自己编号排（CCTV-4K 紧跟 CCTV-4），
        // 而不是丢到最后 —— 用户找 4K 台时不会先翻到列表底部
        if (k.startsWith("cctv4k")) return 4.5
        if (k.startsWith("cctv8k")) return 8.5
        Regex("^cctv(\\d+)").find(k)?.let {
            return it.groupValues[1].toDouble()
        }
        // 非编号的 CCTV 台（风云剧场 / Storm Music / 怀旧剧场 …）
        // **也算央视**，排在编号台之后、地方台之前 ——
        // 这样"置顶的一定是 CCTV"才成立（用户要求）。
        if (k.startsWith("cctv")) return 91.0
        // CGTN 是中国国际电视台，也算央视系，排在 CCTV 之后
        if (k.startsWith("cgtn")) return 92.0
        return null
    }

    /**
     * 把分组表拍平成「换台表」：跨组去重 → **央视置顶并按频道号排序**。
     *
     * 原来 HomeWarmup 和 ImmersiveHome 各自写了一遍去重逻辑，
     * 现在统一到这里，免得两边顺序不一致（那样上下键换台会跳来跳去）。
     *
     * 用户要求：「将左右中央电视台放在列表最前面 按照中央电视台本身的需要排序」。
     */
    fun flattenForZapping(groups: List<LiveGroup>): List<LiveChannel> {
        val uniq = LinkedHashMap<String, LiveChannel>()
        groups.flatMap { it.channels }.forEach { c ->
            // ⚠️ 用 channelKeyOf（央视只认频道号），不能用 normalizeName ——
            // 后者会让 "CCTV1" 和 "CCTV-1 综合" 变成两个台（同名台重复出现）。
            val k = channelKeyOf(c.name)
            val exist = uniq[k]
            if (exist == null) {
                uniq[k] = c
            } else {
                val alts = (exist.alternates + c.url + c.alternates)
                    .filter { it != exist.url }.distinct()
                // 保留**信息更全的名字**：`CCTV-1 综合` 比 `CCTV1` 好，
                // 用户一眼知道是哪个台。
                // 注意这一步必须在**并表时**做 —— 排序在并表之后，
                // 那时名字已经定下来了，光排序改不了保留的是哪个。
                val better = c.name.length > exist.name.length
                val keepName = if (better) c.name else exist.name
                val keepLogo = if (better) c.logo else exist.logo
                val allUrls = (listOf(exist.url) + alts).distinct()
                uniq[k] = exist.copy(
                    name = keepName,
                    logo = keepLogo,
                    alternates = allUrls.filter { it != exist.url },
                )
            }
        }
        // 央视在前（按频道号），其余保持原分组顺序
        val (cctv, rest) = uniq.values.partition { cctvRank(it.name) != null }
        // 数字优先；同号的**名字长的在前** —— 因为长名字信息更全
        // （`CCTV-1 综合` 比 `CCTV1` 好，用户一眼知道是哪个台）。
        // 并表时保留的是第一条的名字，所以这里排好序等于"挑最好的名字"。
        // 最后再按名字兜底，保证顺序稳定（不会每次启动都不一样）。
        val ordered = cctv.sortedWith(
            compareBy(
                { cctvRank(it.name) ?: Double.MAX_VALUE },
                { -it.name.length },
                { it.name },
            ),
        ) + rest
        return ordered
    }

    /** 频道名归一化，用于同名匹配：CCTV-1 综合 / CCTV1综合 → cctv1。 */
    // ==================== 频道分类 ====================
    //
    // 用户要求直播分成「全部 / 央视 / 地方台 / IPTV」四类：
    //   · 全部   —— 所有频道（含 IPTV 扫到的），保持现在的样子
    //   · 央视   —— 只放央视系（CCTV-x / CGTN / 央视频）
    //   · 地方台 —— 其他（省市县台、卫视）
    //   · IPTV   —— 扫描/导入得来的直连源
    //
    // 分类顺序就是界面上 tab 的顺序，别随便调。

    enum class Category(val label: String) {
        All("全部"),
        Cctv("央视"),
        Local("地方台"),
        Iptv("IPTV"),
    }

    /** 央视系的判定：CCTV / CGTN / 央视频 / 央视 前缀。 */
    private val CCTV_RE = Regex(
        "^(CCTV|CGTN|央视频|央视|中国国际)",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 判断一个频道属于哪一类（不含「全部」—— 那是所有类的并集）。
     *
     * 优先看它来自哪个分组：扫描/导入来的分组名带「扫描源 / 我的源 / 自定义」
     * 前缀（见 [load]），这些直接归 IPTV，不管台名是什么。
     * 剩下的按台名判定央视还是地方台。
     */
    fun categoryOf(channel: LiveChannel): Category {
        // 分组名里带这些词 → 是扫描/导入得来的直连源
        //
        // ⚠️ 用 contains 而不是 startsWith：分组名可能是
        // 「扫描源 · IPTV」（中间有点和空格），也可能只叫「IPTV」。
        val g = channel.group
        if (g.contains("扫描") || g.contains("我的源") ||
            g.contains("自定义") || g.startsWith("IPTV", ignoreCase = true)
        ) {
            return Category.Iptv
        }
        return if (CCTV_RE.containsMatchIn(channel.name.trim())) {
            Category.Cctv
        } else {
            Category.Local
        }
    }

    /**
     * 把频道表按分类切开。
     *
     * 「全部」保持原样（跨分组合并、按台名去重），
     * 这也是播放时上下键换台的顺序 —— 不能变，否则老用户的习惯就断了。
     */
    fun splitByCategory(all: List<LiveChannel>): Map<Category, List<LiveChannel>> {
        val cctv = all.filter { categoryOf(it) == Category.Cctv }
        val iptv = all.filter { categoryOf(it) == Category.Iptv }
        val local = all.filter { categoryOf(it) == Category.Local }
        return linkedMapOf(
            Category.All to all,
            Category.Cctv to cctv,
            Category.Local to local,
            Category.Iptv to iptv,
        )
    }

    fun normalizeName(name: String): String = name
        .lowercase()
        .replace(Regex("""[\s\-_·、,，.。:：()（）\[\]【】]"""), "")
        .replace("高清", "")
        .replace("标清", "")
        .replace("超清", "")
        .replace("频道", "")
        .trim()

    /**
     * 从「包装页」里取出真实流地址。
     * 音乐库项目的 tv.json 里大量频道是这种形式：
     *   https://xxx/tv-web/live.html?url=<真实流地址>
     * 这类地址依赖已下线的域名做跳转，必须把内层地址抽出来直接播放。
     */
    fun directStreamOf(raw: String): String? {
        var cur = raw.trim()
        var depth = 0
        while (depth < 3) {
            val inner = unwrapOnce(cur) ?: break
            if (inner == cur) break
            cur = inner
            depth++
        }
        return cur
    }

    private fun unwrapOnce(url: String): String? {
        val idx = url.indexOf("url=", ignoreCase = true)
        if (idx < 0) return null
        if (!(url.contains("live.html") || url.contains("live.js") || url.contains("/tv-web/"))) return null
        val value = url.substring(idx + 4).substringBefore('&').trim()
        if (value.isEmpty()) return null
        return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }

    /** 是否是可交给 ExoPlayer 直连的媒体流。 */
    fun isDirectStream(url: String): Boolean {
        val u = url.lowercase()
        if (!u.startsWith("http")) return false
        if (u.contains("live.html") || u.contains("live.js")) return false
        return u.contains(".m3u8") || u.contains(".flv") || u.contains(".mp4") || u.contains("m3u8")
    }

    /** 是不是「需要浏览器引擎打开」的电视台网页（央视网、各省台直播页等）。 */
    fun isWebPage(url: String): Boolean = !isDirectStream(url)

    /**
     * 一个频道的全部候选播放地址，**顺序即优先级**。
     *
     * 关键规则：**频道自身的地址永远排第一**。
     * 央视/省台的地址是电视台网页，而这类流只有电视台自己的网页播放器能解，
     * 所以必须先走「浏览器引擎打开网页」这条路线；直连备用源排在后面，
     * 只有网页路线放不出画面（健康探测超时）时才轮到它们。
     *
     * （踩过的坑：曾经把「不是直连流」的地址直接过滤掉，结果网页路线被降级成
     *  直连流，画面直接黑屏。）
     *
     * 另外会剔除**已确认下线**的备用源（例如北邮那批 m3u8 已 502），
     * 免得用户按「← →」切源时轮到一个死源。
     */
    fun candidatesOf(channel: LiveChannel): List<String> {
        val out = ArrayList<String>(channel.alternates.size + 1)
        if (!isDeadSource(channel.url) && !isAudioOnlyStream(channel.url)) out.add(channel.url)
            channel.alternates.forEach { if (it !in out && !isDeadSource(it) && !isAudioOnlyStream(it)) out.add(it) }
        return out.ifEmpty { listOf(channel.url) }
    }

    /** 已知下线的源；实测返回 502，且没有替代域名。 */
    private val DEAD_HOSTS = listOf("ivi.bupt.edu.cn")

    fun isDeadSource(url: String): Boolean = DEAD_HOSTS.any { url.contains(it) }

    /**
     * 已知的**纯音频**流，不能当视频源用。
     *
     * 血的教训：我加过一批"央视直连流"，验证方式只有"返回文本以 #EXTM3U 开头"，
     * 结果它们其实是音频流（路径 `/audio/`、分片 `cctv1_audio/xxx.ts`），
     * 用户看到的是**只有声音没有画面**。
     *
     * 所以凡是路径里带 `audio` 的一律排除 —— 宁可退回网页路线，
     * 也不要给用户一个"能播但没画面"的源。
     */
    private val AUDIO_ONLY_HINTS = listOf("/audio/", "_audio/", "audio-only")

    /** 这个地址是不是已知的纯音频流。 */
    fun isAudioOnlyStream(url: String): Boolean =
        AUDIO_ONLY_HINTS.any { url.lowercase().contains(it) }

    /**
     * 网页播放时按站点挑 UA。
     *
     * 实测结论（央视站）：
     * - 桌面 UA 下页面里的播放器能正常解析出真实 m3u8；用移动 UA 时页面会回
     *   「请移至移动端观看」或者拿到不带 CORS 头的流地址，导致 CDN 拒绝跨域请求；
     * - 央视频（yangshipin）同样只认桌面端，移动 UA 会弹「前往央视频客户端」。
     *
     * 所以默认统一用桌面 UA；只有明确需要移动端页面时才用移动 UA。
     */
    fun webUserAgentFor(pageUrl: String): String {
        val host = runCatching { java.net.URI(pageUrl).host ?: "" }.getOrDefault("").lowercase()
        return when {
            host.contains("yangshipin") -> UA_DESKTOP
            host.contains("cctv") -> UA_DESKTOP
            else -> UA_DESKTOP
        }
    }

    private const val UA_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private const val UA_MOBILE =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    /**
     * 播放某个流时该带的请求头。
     * 国内直播流普遍做防盗链：UA 不对或没有 Referer 就 403。
     * 这里按域名给一套经验值（来自音乐库项目实测结论）。
     */
    fun headersFor(streamUrl: String): Map<String, String> {
        val host = runCatching { java.net.URI(streamUrl).host ?: "" }.getOrDefault("").lowercase()
        val ua = if (host.contains("yangshipin")) Http.UA_DESKTOP else Http.UA_MOBILE
        val referer = when {
            host.endsWith("gstv.com.cn") -> "https://www.gstv.com.cn/"
            host.contains("kankanlive") -> "https://www.kankanews.com/"
            host.contains("hyrtv") -> "https://www.hyrtv.cn/"
            // 央视自有直连流 CDN（CCTV 各频道的硬解源）。必带 Referer，否则被拒。
            host.contains("myalicdn") || host.contains("alicdn") -> "https://tv.cctv.com/"
            host.contains("cctv") -> "https://tv.cctv.com/"
            host.contains("cctvpic") -> "https://tv.cctv.com/"
            host.contains("yangshipin") -> "https://www.yangshipin.cn/"
            host.contains("sctv") -> "https://www.sctv.com/"
            host.contains("jxgdw") || host.contains("jxntv") -> "https://www.jxntv.cn/"
            host.contains("dztv") -> "https://iapp.dztv.tv/"
            host.contains("chinamcache") -> "https://www.zzjy.gov.cn/"
            host.contains("qq.com") -> "https://v.qq.com/"
            host.contains("migu") -> "https://www.miguvideo.com/"
            else -> ""
        }
        return buildMap {
            put("User-Agent", ua)
            if (referer.isNotEmpty()) put("Referer", referer)
        }
    }

    /**
     * 内置兜底频道表（assets 读取失败时才用）。
     *
     * 这里用的是**电视台自己的网页直播页**，和主频道表同一套地址。
     *
     * 以前这里拿的是一批第三方 HLS 直连地址，但那个域名已经下线 ——
     * 也就是说"兜底表"本身是坏的，真出问题时反而播不了。
     * 现在统一用官方网页地址：走 [TvWebPlayerView] 浏览器引擎播放，
     * 和正常频道的路径完全一致，不会再出现"兜底源全是死的"这种事。
     */
    private fun fallbackCctv(): List<LiveGroup> = listOf(
        LiveGroup(
            "央视",
            listOf(
                "CCTV-1 综合" to "https://tv.cctv.com/live/cctv1/",
                "CCTV-2 财经" to "https://tv.cctv.com/live/cctv2/",
                "CCTV-3 综艺" to "https://tv.cctv.com/live/cctv3/",
                "CCTV-4 中文国际" to "https://tv.cctv.com/live/cctv4/",
                "CCTV-5 体育" to "https://tv.cctv.com/live/cctv5/",
                "CCTV-6 电影" to "https://tv.cctv.com/live/cctv6/",
                "CCTV-7 国防军事" to "https://tv.cctv.com/live/cctv7/",
                "CCTV-8 电视剧" to "https://tv.cctv.com/live/cctv8/",
                "CCTV-9 纪录" to "https://tv.cctv.com/live/cctv9/",
                "CCTV-10 科教" to "https://tv.cctv.com/live/cctv10/",
                "CCTV-11 戏曲" to "https://tv.cctv.com/live/cctv11/",
                "CCTV-12 社会与法" to "https://tv.cctv.com/live/cctv12/",
                "CCTV-13 新闻" to "https://tv.cctv.com/live/cctv13/",
                "CCTV-14 少儿" to "https://tv.cctv.com/live/cctv14/",
                "CCTV-15 音乐" to "https://tv.cctv.com/live/cctv15/",
                "CCTV-17 农业农村" to "https://tv.cctv.com/live/cctv17/",
            ).map { (name, url) ->
                LiveChannel(name = name, url = url, group = "央视")
            }
        )
    )
}
