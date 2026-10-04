package com.chinut.bawantv.unified


/**
 * 影视板块的数据源抽象。
 *
 * # 为什么需要这一层
 *
 * [com.chinut.bawantv.ui.screens.UnifiedVideoScreen]（1345 行）原本直接绑死在
 * [LibraryStore] + [MovieAggregator] 上。
 *
 * 三个选择：
 *   · 把某个源硬编进 `UnifiedVideoScreen` → 那个文件会变成两套逻辑混在一起
 *   · 复制一份给短剧 → 多一份 1345 行的维护负担
 *   · **抽出数据源接口** → 界面不变，换数据源即可 ✅
 *
 * 选第三个。影视走 [DdysSource]，短剧走自己的源。
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

    /** 显示名，例如「低端影视」。 */
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
