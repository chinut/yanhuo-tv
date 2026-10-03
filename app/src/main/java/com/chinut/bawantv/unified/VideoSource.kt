package com.chinut.bawantv.unified

import com.chinut.bawantv.hongguo.Hongguo

/**
 * 影视板块的数据源抽象。
 *
 * # 为什么需要这一层
 *
 * [com.chinut.bawantv.ui.screens.UnifiedVideoScreen]（1345 行）原本直接绑死在
 * [LibraryStore] + [MovieAggregator] 上。短剧要复用它，但数据来自红果。
 *
 * 三个选择：
 *   · 把红果硬编进 `UnifiedVideoScreen` → 那个文件会变成两套逻辑混在一起
 *   · 复制一份给短剧 → 多一份 1345 行的维护负担
 *   · **抽出数据源接口** → 界面不变，换数据源即可 ✅
 *
 * 选第三个。影视走 [DdysSource]，短剧走 [HongguoSource]。
 *
 * # 列表加载："先用本地库铺满，再后台补货"
 *
 * 影视原本的 [loadFromLibrary] 就是这个思路，抽到这里时保留了：
 * `cached()` 先给一份能立刻显示的数据（本地库 / 空），
 * 然后 [refresh] 联网补货并回调更新。
 *
 * 这样网络不通时用户也永远有东西看 —— 这是"有啥看啥"的前提。
 */
interface VideoSource {

    /** 数据源标识，用于日志与状态判断。 */
    val id: String

    /** 显示名，例如「低端影视」「红果短剧」。 */
    val displayName: String

    /**
     * 立刻可用的数据（本地缓存 / 库）。空表示没有。
     *
     * 注意：这里**不该联网**。它的作用是让界面第一帧就有东西。
     */
    suspend fun cached(): List<UnifiedMovie>

    /**
     * 联网补货。
     *
     * @param onProgress 进度回调 (已完成, 总数, 累计条数)，用于界面上的"补货中"提示
     */
    suspend fun refresh(
        onProgress: (Int, Int, Int) -> Unit = { _, _, _ -> },
    ): List<UnifiedMovie>

    /** 可选的分类名列表（影视按类型筛选用；短剧返回空）。 */
    suspend fun typeNames(): List<String> = emptyList()

    /**
     * 补齐一部作品的剧集。
     *
     * 影视（低端影视）在这里把 m3u8 地址全部取回来；
     * 短剧（红果）只取**免费集**的地址 —— 其余的站点返回 404，是正常的付费墙。
     */
    suspend fun loadSources(movie: UnifiedMovie): List<UnifiedSource>
}

// ==================== 低端影视 ====================

/**
 * 低端影视数据源。行为与原 `UnifiedVideoScreen` 内联的逻辑完全一致，
 * 只是搬到了这个接口后面（先本地库铺满，再联网补货）。
 */
object DdysSource : VideoSource {
    override val id = "ddys"
    override val displayName = "低端影视"

    override suspend fun cached(): List<UnifiedMovie> =
        runCatching { LibraryStore.load() }.getOrDefault(emptyList())

    override suspend fun refresh(
        onProgress: (Int, Int, Int) -> Unit,
    ): List<UnifiedMovie> =
        LibraryStore.refresh(pagesPerType = 5, onProgress = { d, t, n -> onProgress(d, t, n) })

    override suspend fun typeNames(): List<String> =
        runCatching { LibraryStore.typeNames() }.getOrDefault(emptyList())

    override suspend fun loadSources(movie: UnifiedMovie): List<UnifiedSource> =
        MovieAggregator.loadSources(movie)
}

// ==================== 红果短剧 ====================

/**
 * 红果短剧数据源。
 *
 * # 与影视最大的不同：剧集地址不能一次性取全
 *
 * 红果的播放地址在**每一集的播放页**里，而且只有免费集可取
 * （详情页的 `accessible_episode_cnt` 说明免费几集，实测是 3；
 * 超出的播放页返回 404 —— 这是正常的付费墙，不是故障）。
 *
 * 所以策略是：
 *   · 保留**全部分集**（[Hongguo.Detail.vids]），这样用户能看到"共 72 集"
 *   · 只为**免费的那几集**去取地址
 *   · 未取到地址的集，[Episode.url] 留空 —— 播放器遇到空地址给友好提示
 *
 * 这样详情页只需 1 次请求 + N 次（N = 免费集数，实测 3），
 * 而不是 72 次。对老电视很重要。
 */
object HongguoSource : VideoSource {
    override val id = "hongguo"
    override val displayName = "红果短剧"

    private const val TAG = "BawanHGSource"

    /** 免费集之外，最多再尝试取几集（防止某天免费集数变大导致请求暴涨）。 */
    private const val MAX_RESOLVE = 8

    override suspend fun cached(): List<UnifiedMovie> = emptyList()

    override suspend fun refresh(
        onProgress: (Int, Int, Int) -> Unit,
    ): List<UnifiedMovie> {
        val out = ArrayList<UnifiedMovie>()
        val cats = Hongguo.CATEGORIES
        cats.forEachIndexed { i, cat ->
            runCatching {
                // 每个分类抓前两页，够铺一屏了（一页 24 部）
                val page1 = Hongguo.list(cat, 1)
                val page2 = runCatching { Hongguo.list(cat, 2) }.getOrDefault(emptyList())
                (page1 + page2).forEach { out.add(fromBrief(it, cat.name)) }
            }.onFailure {
                android.util.Log.w(TAG, "抓 ${cat.name} 失败：${it.message}")
            }
            onProgress(i + 1, cats.size, out.size)
        }
        android.util.Log.i(TAG, "红果列表：${out.size} 部")
        return out
    }

    /** 列表项 → 统一模型。 */
    fun fromBrief(b: Hongguo.Brief, categoryName: String): UnifiedMovie = UnifiedMovie(
        id = normalizeId(b.seriesId),
        title = b.title,
        poster = b.cover,
        year = "",
        typeName = (listOf(categoryName) + b.tags).joinToString(" "),
        area = "",
        score = "",
        remarks = if (b.episodeCount > 0) "全${b.episodeCount}集" else "",
        sources = listOf(
            UnifiedSource(
                id = id,
                remoteId = b.seriesId,
                name = displayName,
                quality = if (b.episodeCount > 0) "${b.episodeCount} 集" else "",
                episodeCount = b.episodeCount,
            )
        ),
    )

    /**
     * 红果的 id 就是 series_id，直接用作稳定 id。
     *
     * 为什么不像低端影视那样用"片名+年份"：红果的 series_id 本来就是稳定唯一的，
     * 而片名会重复（实测首页有「重生！丑小鸭逆袭成了万人迷第三季/第四季」这种）。
     */
    fun normalizeId(seriesId: String): String = "hg:$seriesId"

    override suspend fun loadSources(movie: UnifiedMovie): List<UnifiedSource> =
        movie.sources.map { s ->
            if (s.id != id) return@map s
            val seriesId = s.remoteId.ifBlank { movie.id.removePrefix("hg:") }
            val detail = Hongguo.detail(seriesId)
            if (detail == null) {
                android.util.Log.w(TAG, "取详情失败《${movie.title}》(seriesId=$seriesId)")
                return@map s
            }

            // 只为免费集取地址；其余留空但**保留集数**，让用户看到完整进度
            val limit = minOf(detail.vids.size, maxOf(detail.freeCount, 0), MAX_RESOLVE)
            val eps = detail.vids.mapIndexed { i, vid ->
                if (i < limit) {
                    val u = runCatching { Hongguo.streamUrl(seriesId, vid) }.getOrNull()
                    Episode(name = "第 ${i + 1} 集", url = u.orEmpty())
                } else {
                    Episode(name = "第 ${i + 1} 集", url = "")
                }
            }
            val playable = eps.count { it.url.isNotBlank() }
            android.util.Log.i(
                TAG,
                "《${movie.title}》：共 ${eps.size} 集，已取到地址 $playable 集" +
                    "（免费 ${detail.freeCount}）",
            )

            s.copy(
                remoteId = seriesId,
                episodes = eps,
                episodeCount = eps.size,
                name = "$displayName · 共 ${eps.size} 集",
                quality = if (playable > 0) "可看 $playable 集" else "暂无免费集",
            )
        }
}
