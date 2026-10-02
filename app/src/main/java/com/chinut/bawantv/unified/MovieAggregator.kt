package com.chinut.bawantv.unified

import com.chinut.bawantv.BawanApp
import com.chinut.bawantv.ddys.Ddys
import com.chinut.bawantv.ddys.DdysEpisode
import com.chinut.bawantv.ddys.DdysMovie
import com.chinut.bawantv.vod.VodDetail
import com.chinut.bawantv.vod.VodEpisode
import com.chinut.bawantv.vod.VodItem
import com.chinut.bawantv.vod.VodRepo
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 一个可播放的源（来自某个内容站）。
 *
 * `id` 用来回源取详情：TVBox 用 siteKey，ddys 固定 "ddys"。
 */
data class UnifiedSource(
    /** 源标识：TVBox 用 siteKey，ddys 固定 "ddys" */
    val id: String,
    /**
     * 回源取详情用的真实条目 id。
     *
     * TVBox 是 `vodId`，ddys 是 `slug`。
     * **必须在列表阶段就带上** —— 否则详情页要"重新拉一遍列表再按标题反查"，
     * 既慢（多一次全量请求）又不准（标题可能有细微差异匹配不上）。
     */
    val remoteId: String = "",
    val name: String,
    val quality: String = "",
    val episodeCount: Int = 0,
    /** 已经拿到的剧集（列表页通常拿不到，进详情页才补齐） */
    val episodes: List<UnifiedEpisode> = emptyList(),
)

/** 一集。 */
data class UnifiedEpisode(val name: String, val url: String)

/**
 * 聚合后的作品：**一个标题一条**，底下挂着所有源的来源。
 *
 * 这是整个影视板块的核心数据结构 —— 用户看到的是"这部片"，
 * 而不是"某个站点里恰好同名的条目"。点开详情页会把所有源摊开让他挑。
 */
data class UnifiedMovie(
    /** 展示用主键：归一化标题 + 年份 */
    val id: String,
    val title: String,
    val poster: String = "",
    val year: String = "",
    val typeName: String = "",
    val area: String = "",
    val score: String = "",
    val remarks: String = "",
    val sources: List<UnifiedSource> = emptyList(),
) {
    val hasPoster: Boolean get() = poster.startsWith("http")

    /** 有几个源能播 —— 详情页据此告诉用户"可选 N 个源" */
    val sourceCount: Int get() = sources.size
}

/**
 * 多源聚合刮削器。
 *
 * ## 为什么要聚合
 *
 * 之前影视板块是「按源分家」：低端影视一页、每个 TVBox 站点各一页。
 * 用户想看的片如果在 A 站没有、只有 B 站有，他就得自己猜、自己切源。
 * 这既不合理也不好用 —— 用户关心的是"有没有这部片"，
 * 而不是"它在哪个站"。
 *
 * 现在改成：**并行刮所有源 → 按作品合并 → 一个列表 → 详情页把源全摊开**。
 *
 * ## 合并策略
 *
 * 标题归一化后 + 年份相同即视为同一部作品：
 *  - 归一化：去掉空格/标点、去掉「国语/粤语/HD/4K/高清/完结/更新至X集」这类噪声
 *  - 年份：两边都有年份且不同 → 不合并（避免把翻拍版和新版混成一部）
 *  - 合并时**优先保留有海报的那条**，源信息全部累积进去
 *
 * ## 性能
 *
 * 所有源的首页并行拉取，谁慢都不阻塞别人；单源失败只丢它自己。
 * 结果带上 `nextPage` 供瀑布流继续往下翻。
 */
object MovieAggregator {

    /** 一页大致刮多少条（合并后）。 */
    const val PAGE_SIZE = 60

    // ==================== 首页海报墙的持久化缓存 ====================

    /**
     * 首页那个「影视」按钮里的海报墙，上次抓到的片单落盘。
     *
     * ## 为什么需要它
     *
     * 之前每次冷启动都要：**请求聚合接口 → 合并 → 下载海报**，
     * 三步都完成才能看到画面。实测首页那块会空好几秒，很难看。
     *
     * 现在：进界面**先用上次缓存即时渲染**（海报本身命中 Coil 的磁盘缓存，
     * 所以是立刻出来），同时在后台刷新；刷新回来再静默替换。
     * 用户看到的是「秒开」，代价只是可能短暂看到略旧的片单 —— 完全值得。
     *
     * 存的是精简过的字段（只保留标题/海报/id），因为首页只拿它做视觉。
     */
    private const val KEY_HOME_CACHE = "home_movie_cache"
    private const val MAX_CACHE_ITEMS = 12

    /** 读上次缓存的首页片单；没有则返回空。 */
    fun cachedHomeMovies(): List<UnifiedMovie> {
        val raw = runCatching { BawanApp.prefs.homeMovieCache }.getOrNull().orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val f = line.split('\u001F')
            if (f.size < 3) return@mapNotNull null
            UnifiedMovie(
                id = f[0],
                title = f[1],
                poster = f[2],
                year = f.getOrNull(3).orEmpty(),
                score = f.getOrNull(4).orEmpty(),
            )
        }.toList()
    }

    /** 把首页片单写进缓存（只留需要的字段，控制体积）。 */
    fun cacheHomeMovies(movies: List<UnifiedMovie>) {
        runCatching {
            BawanApp.prefs.homeMovieCache = movies
                .take(MAX_CACHE_ITEMS)
                .joinToString("\n") { m ->
                    listOf(m.id, m.title, m.poster, m.year, m.score).joinToString("\u001F")
                }
        }
    }

    /**
     * 预热海报：只让 Coil 去解码，不挂到界面上。
     *
     * 在开屏动画那两三秒里跑，等真正进首页时图已经在内存/磁盘缓存里了。
     */
    fun preloadPosters(context: android.content.Context, movies: List<UnifiedMovie>) {
        if (movies.isEmpty()) return
        runCatching {
            val loader = coil.Coil.imageLoader(context)
            movies.take(MAX_CACHE_ITEMS).forEach { m ->
                if (!m.hasPoster) return@forEach
                val req = coil.request.ImageRequest.Builder(context)
                    .data(m.poster)
                    .memoryCacheKey(m.poster)
                    .diskCacheKey(m.poster)
                    .build()
                loader.enqueue(req)
            }
        }
    }

    /**
     * 去掉标题里的噪声，用于判断"是不是同一部片"。
     *
     * 各站的命名习惯差别很大（「肖申克的救赎」「肖申克的救赎(国语)」
     * 「肖申克的救赎HD国语」），不归一化就合不到一起。
     */
    fun normalizeTitle(raw: String): String {
        var s = raw.trim()
        // 全角括号统一成半角，方便后面统一剔除
        s = s.replace('（', '(').replace('）', ')')
        // 去掉括号里的补充说明（(国语) / (2024) / (蓝光)…）
        s = s.replace(Regex("""\([^)]*\)"""), "")
        val noise = listOf(
            "国语", "粤语", "英语", "日语", "韩语", "双语", "原声",
            "高清", "标清", "超清", "蓝光", "bluray", "hd", "fhd", "4k", "8k", "hdr", "remux",
            "完结", "全", "集", "季", "更新至", "更新到", "已完结",
            "tc", "ts", "web-dl", "webdl", "bd", "dvd",
            "电影版", "剧场版", "加长版", "导演剪辑版", "未删减",
        )
        var changed = true
        while (changed) {
            changed = false
            for (n in noise) {
                val next = s.replace(n, "", ignoreCase = true)
                if (next != s) {
                    s = next
                    changed = true
                }
            }
        }
        // 去掉所有空白与常见分隔符
        s = s.replace(Regex("""[\s\-_·、,，.。:：!！?？'"“”‘’\[\]【】]"""), "")
        return s.lowercase()
    }

    private fun normalizeId(title: String, year: String): String =
        normalizeTitle(title) + "|" + year.trim()

    // ==================== 刮削 ====================

    /**
     * 刮一页：并行拉取所有源，合并后返回。
     *
     * @param page 页码（各源按自己的分页习惯取同一页）
     * @param keyword 非空则走搜索（搜索时不翻页）
     */
    suspend fun scrape(page: Int = 1, keyword: String? = null): List<UnifiedMovie> =
        withContext(Dispatchers.IO) {
            val prefs = BawanApp.prefs
            val sites = runCatching {
                VodRepo.loadSites(vodOnly = prefs.vodOnlySites)
            }.getOrDefault(emptyList())

            coroutineScope {
                val jobs = ArrayList<kotlinx.coroutines.Deferred<List<UnifiedMovie>>>()

                // 1) 低端影视（官方 JSON 接口）
                jobs += async {
                    runCatching {
                        val api = Ddys.api()
                        if (keyword.isNullOrBlank()) {
                            val (list, _) = api.latest(page = page, perPage = 30)
                            list.map { fromDdysItem(it) }
                        } else {
                            api.search(keyword).map { fromDdysItem(it) }
                        }
                    }.getOrDefault(emptyList())
                }

                // 2) 所有 TVBox 站点
                sites.forEach { site ->
                    jobs += async {
                        runCatching {
                            val pg = VodRepo.apiOf(site).list(
                                page = page,
                                keyword = keyword?.takeIf { it.isNotBlank() },
                            )
                            pg?.items.orEmpty().map { fromVod(it, site.key, site.name) }
                        }.getOrDefault(emptyList())
                    }
                }

                merge(jobs.awaitAll().flatten())
            }
        }

    fun fromDdysItem(m: DdysMovie): UnifiedMovie = UnifiedMovie(
        id = normalizeId(m.title, m.year),
        title = m.title,
        poster = m.poster,
        year = m.year,
        typeName = m.type + m.genres.joinToString(" ") { it },
        area = m.region,
        score = m.rating,
        remarks = if (m.isCompleted) "已完结" else "",
        sources = listOf(
            UnifiedSource(
                id = "ddys",
                remoteId = m.slug,
                name = "低端影视",
                quality = m.rating.takeIf { it.isNotBlank() }?.let { "评分 $it" } ?: "",
                episodeCount = m.onlineCount,
            )
        ),
    )

    private fun fromVod(v: VodItem, siteKey: String, siteName: String): UnifiedMovie = UnifiedMovie(
        id = normalizeId(v.name, v.year),
        title = v.name,
        poster = v.pic,
        year = v.year,
        typeName = v.typeName,
        area = v.area,
        score = v.score,
        remarks = v.remarks,
        sources = listOf(
            UnifiedSource(
                id = siteKey,
                remoteId = v.vodId,
                name = siteName.ifBlank { siteKey },
                quality = v.remarks,
            )
        ),
    )

    /**
     * 把多条记录按作品合并。
     *
     * 合并规则（顺序很重要）：
     *  1. 标题归一化后 + 年份一致 → 同一条
     *  2. 海报取**第一个非空的**（各站海报质量不一，有就用）
     *  3. 评分取最高分（有的站不返回评分）
     *  4. 源码/备注取第一条非空的
     *  5. 同 id 的源**去重**（同一部片在一个站可能返回多条不同清晰度）
     */
    fun merge(all: List<UnifiedMovie>): List<UnifiedMovie> {
        val map = LinkedHashMap<String, UnifiedMovie>()
        all.forEach { m ->
            if (m.title.isBlank()) return@forEach
            val key = if (m.year.isBlank()) m.id.substringBefore('|') else m.id
            val exist = map[key]
            if (exist == null) {
                map[key] = m
            } else {
                // 源去重：同一个源只留一条，剧集数取大的那个
                val merged = LinkedHashMap<String, UnifiedSource>()
                (exist.sources + m.sources).forEach { s ->
                    val old = merged[s.id]
                    merged[s.id] = when {
                        old == null -> s
                        s.episodeCount > old.episodeCount -> s
                        // 剧集数一样时，把缺失的 remoteId 补上
                        old.remoteId.isBlank() && s.remoteId.isNotBlank() -> old.copy(remoteId = s.remoteId)
                        else -> old
                    }
                }
                map[key] = exist.copy(
                    title = if (exist.title.length >= m.title.length) exist.title else m.title,
                    poster = exist.poster.ifBlank { m.poster },
                    year = exist.year.ifBlank { m.year },
                    typeName = exist.typeName.ifBlank { m.typeName },
                    area = exist.area.ifBlank { m.area },
                    score = maxScore(exist.score, m.score),
                    remarks = exist.remarks.ifBlank { m.remarks },
                    sources = merged.values.toList(),
                )
            }
        }
        return map.values.toList()
    }

    private fun maxScore(a: String, b: String): String {
        val x = a.toDoubleOrNull() ?: return b
        val y = b.toDoubleOrNull() ?: return a
        return if (x >= y) a else b
    }

    // ==================== 详情：把一个作品的所有源补齐 ====================

    /**
     * 拉取某部作品在**所有源**下的完整剧集列表。
     *
     * 列表页只有"有哪些源"，剧集要到详情接口才有，所以进详情页时并行补齐。
     * 单个源失败只丢它自己，其余照常展示。
     */
    suspend fun loadSources(movie: UnifiedMovie): List<UnifiedSource> =
        withContext(Dispatchers.IO) {
            coroutineScope {
                movie.sources.map { s ->
                    async {
                        runCatching {
                            when (s.id) {
                                "ddys" -> {
                                    val api = Ddys.api()
                                    // 优先用列表阶段就带下来的 slug；没有才退回搜索
                                    val slug = s.remoteId.ifBlank {
                                        runCatching { api.search(movie.title).firstOrNull()?.slug }
                                            .getOrNull().orEmpty()
                                    }
                                    val detail = slug.takeIf { it.isNotBlank() }?.let { api.detail(it) }
                                    val src = detail?.sources.orEmpty()
                                    val eps = src.flatMap { it.episodes }
                                        .map { UnifiedEpisode(it.label.ifBlank { "播放" }, it.url) }
                                    s.copy(
                                        remoteId = slug,
                                        episodes = eps,
                                        episodeCount = eps.size,
                                        name = if (src.isNotEmpty()) {
                                            "低端影视 · " + src.joinToString("/") { it.name }
                                        } else {
                                            "低端影视"
                                        },
                                    )
                                }

                                else -> {
                                    // 用列表阶段带下来的 vodId 直接取详情，不再反查列表
                                    if (s.remoteId.isBlank()) return@runCatching s
                                    val sites = runCatching {
                                        VodRepo.loadSites(vodOnly = BawanApp.prefs.vodOnlySites)
                                    }.getOrDefault(emptyList())
                                    val site = sites.firstOrNull { it.key == s.id } ?: return@runCatching s
                                    val detail: VodDetail? = VodRepo.apiOf(site).detail(s.remoteId)
                                    // 多线路时挑剧集最多的那条（通常就是主线路）
                                    val best = detail?.sources?.maxByOrNull { it.episodes.size }
                                    val eps = best?.episodes.orEmpty()
                                        .map { UnifiedEpisode(it.name, it.url) }
                                    s.copy(
                                        episodes = eps,
                                        episodeCount = eps.size,
                                        quality = best?.flag ?: s.quality,
                                        name = "${site.name} · ${best?.flag ?: "默认线路"}",
                                    )
                                }
                            }
                        }.getOrDefault(s)
                    }
                }.awaitAll()
            }
        }

    /**
     * 为一部作品**补充 TVBox 源**。
     *
     * ## 定位
     *
     * 低端影视是**内容基础**（有它的官方接口、直连播放、稳定），
     * 但总有它播不了或者没有的情况。这时用 TVBox 站去搜同一个片名，
     * 把搜到的线路当作**备用源**追加进来 —— 用户在详情页能看到多几个按钮，
     * 点哪个都行；播放失败时也会自动往这些源上切。
     *
     * ## 为什么按标题搜而不是按 id
     *
     * 各 TVBox 站的 vodId 体系完全不同，没有跨站通用 id，
     * 只能按片名匹配。好在 [Pinyin] 的归一化已经很可靠，误配率低。
     *
     * @param movie 目标作品（用它的标题去搜）
     * @param maxSites 最多查几个站。查太多会拖慢详情页打开速度，
     *                 默认 4 个已经够覆盖主流线路
     * @return 补充到的源（可能为空）。**不含** ddys 本身
     */
    suspend fun supplementSources(
        movie: UnifiedMovie,
        maxSites: Int = 4,
    ): List<UnifiedSource> = withContext(Dispatchers.IO) {
        val sites = runCatching {
            VodRepo.loadSites(vodOnly = BawanApp.prefs.vodOnlySites)
        }.getOrDefault(emptyList())
        if (sites.isEmpty()) return@withContext emptyList()

        // 用归一化后的标题比较，避免「片名(国语)」这类差异导致漏配
        val key = normalizeTitle(movie.title)

        coroutineScope {
            sites.take(maxSites).map { site ->
                async {
                    runCatching {
                        val pg = VodRepo.apiOf(site).list(page = 1, keyword = movie.title)
                        // 在结果里挑标题最接近的那条
                        val hit = pg?.items.orEmpty()
                            .firstOrNull { normalizeTitle(it.name) == key }
                            ?: pg?.items.orEmpty().firstOrNull()
                            ?: return@runCatching emptyList<UnifiedSource>()
                        listOf(
                            UnifiedSource(
                                id = site.key,
                                remoteId = hit.vodId,
                                name = site.name,
                                quality = hit.remarks.takeIf { it.isNotBlank() } ?: "备用",
                                episodeCount = 0,
                            )
                        )
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }
    }

    /**
     * 组装一部作品的可播放源：**低端影视永远排第一**，TVBox 作为补充跟在后。
     *
     * 排序很重要 —— 详情页是按顺序列按钮的，用户第一个看到、最容易点的
     * 应该是最靠谱的那条。
     */
    suspend fun buildPlayableSources(
        movie: UnifiedMovie,
        withSupplement: Boolean = true,
    ): List<UnifiedSource> {
        // 1) 先补齐自身源（ddys 取剧集 + 已有的 TVBox 源）
        val own = runCatching { loadSources(movie) }.getOrDefault(movie.sources)

        if (!withSupplement) {
            return own.sortedByDescending { it.id == "ddys" }
        }

        // 2) 补充源：只在"自己的源不够用"时才去查，避免每次开详情都联网
        val ddysSources = own.filter { it.id == "ddys" }
        val others = own.filter { it.id != "ddys" }
        val ddysPlayable = ddysSources.any { it.episodes.isNotEmpty() }

        val extra = if (others.isEmpty() && !ddysPlayable) {
            runCatching { supplementSources(movie) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        // 3) 排序：ddys 第一 → 已有源 → 补充源
        return (ddysSources + others + extra.filter { e -> others.none { it.id == e.id } })
            .sortedByDescending { it.id == "ddys" }
    }
}
