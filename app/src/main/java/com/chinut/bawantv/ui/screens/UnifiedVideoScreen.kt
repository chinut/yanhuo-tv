package com.chinut.bawantv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.chinut.bawantv.core.ParentalControl
import com.chinut.bawantv.ui.SectionEmpty
import com.chinut.bawantv.ui.SectionLoading
import com.chinut.bawantv.ui.theme.Dim
import com.chinut.bawantv.ui.theme.FocusKeys
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import com.chinut.bawantv.ui.theme.focusBorder
import com.chinut.bawantv.unified.LibraryStore
import com.chinut.bawantv.ui.theme.Ink
import com.chinut.bawantv.ui.theme.LocalTvFocusManager
import com.chinut.bawantv.ui.theme.rememberTvFocusState
import com.chinut.bawantv.ui.theme.sdp
import com.chinut.bawantv.ui.theme.ssp
import com.chinut.bawantv.ui.theme.tvFocusable
import com.chinut.bawantv.ui.theme.Txt
import com.chinut.bawantv.unified.MovieAggregator
import com.chinut.bawantv.unified.UnifiedEpisode
import com.chinut.bawantv.unified.UnifiedMovie
import com.chinut.bawantv.unified.UnifiedSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 聚合层在独立的 unified 包里（领域模型），界面在这里引用它

/**
 * 影视板块（重做版）。
 *
 * ## 为什么重做
 *
 * 之前是「按源分家」：低端影视一个入口、TVBox 聚合另一个入口，
 * 而聚合页一屏只显示 3 张卡、翻页靠页码 —— 想看别的片非常别扭，
 * 而且同一部片在不同站点各出现一次，用户还得自己猜哪个源能播。
 *
 * 现在：
 *  1. **一个瀑布流**堆下所有源刮到的作品，**一直往下滚，自动续页**
 *  2. 同一部作品按标题+年份**合并成一条**，海报下面直接写「N 个源」
 *  3. 点进详情页，**所有源都摊开成播放按钮**，用户想看哪个源点哪个，
 *     不需要在播放器里手动切源
 */
@Composable
fun UnifiedVideoScreen(
    entryKey: Any,
    /** 首页推荐墙点进来的作品：进来直接开详情 */
    pendingMovie: UnifiedMovie? = null,
    onPendingConsumed: () -> Unit = {},
    /** 返回键：回首页 */
    onBack: (() -> Unit)? = null,
    /**
     * 播放失败回调：参数是**失败的那个源标识**。
     *
     * 存在的意义是让上层能自动换一个补充源继续播 ——
     * 用户点了低端影视的线路，它播不了时不该只弹错误让他自己找。
     */
    onPlaybackFailed: ((String) -> Unit)? = null,
    /** 起播：上抛主框架，在根布局层全屏渲染 */
    onPlay: (UnifiedMovie, UnifiedSource, List<UnifiedEpisode>, Int) -> Unit,
) {
    // 返回键回首页
    BackHandler(enabled = onBack != null) { onBack?.invoke() }

    val manager = LocalTvFocusManager.current
    val scope = rememberCoroutineScope()

    // ---------- 数据来源：**低端影视核心库** ----------
    //
    // 架构说明（本版重点）：
    //   低端影视 = 内容基础，片库整份缓存在本地 → 进页面**立刻有内容**；
    //   TVBox 站点 = 辅助补充，只在"某个源播不了"时才去搜同一个片名。
    //
    // 所以这里不再"每次进页面都联网重抓"，而是先读本地库即时展示，
    // 再在后台静默补货（刷新最新几页）。
    var movies by remember { mutableStateOf<List<UnifiedMovie>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    /** 后台补货进度：正在抓第几页 / 共几页。0 表示没在补货。 */
    var syncing by remember { mutableIntStateOf(0) }
    var syncDone by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var keyword by remember { mutableStateOf("") }
    /** 当前类型筛选（空串 = 全部）。 */
    var typeFilter by remember { mutableStateOf("") }
    /** 库里出现过的类型，用于筛选栏。 */
    var typeOptions by remember { mutableStateOf<List<String>>(emptyList()) }

    // 调试入口：--es dsh_route vod_search 时直接进搜索态。
    // LocalContext.current 必须在 composable 作用域里取，不能塞进 remember 的 lambda。
    val debugRoute = (androidx.compose.ui.platform.LocalContext.current as? android.app.Activity)
        ?.intent?.getStringExtra("dsh_route")
    var searchMode by remember { mutableStateOf(debugRoute == "vod_search") }
    var detailOf by remember { mutableStateOf<UnifiedMovie?>(null) }

    /** 未成年人保护：不合适的内容不进列表 */
    fun visible(list: List<UnifiedMovie>): List<UnifiedMovie> = list.filter {
        ParentalControl.allows(title = it.title, typeName = it.typeName, extra = it.remarks)
    }

    /** 把库里读到的原始列表套上筛选与去重。 */
    fun applyFilters(raw: List<UnifiedMovie>): List<UnifiedMovie> {
        val kw = keyword.trim()
        val base = if (kw.isBlank()) raw else LibraryStore.search(kw)
        val byType = if (typeFilter.isBlank()) base else base.filter { it.typeName.contains(typeFilter) }
        return visible(byType)
    }

    /**
     * 进页面：**先用本地库铺满**，再后台补货。
     *
     * 这样即便网络不通，用户也永远有东西看 —— 这是"有啥看啥"的前提。
     */
    suspend fun loadFromLibrary() {
        loading = true
        error = null
        val cached = runCatching { LibraryStore.load() }.getOrDefault(emptyList())
        if (cached.isNotEmpty()) {
            typeOptions = runCatching { LibraryStore.typeNames() }.getOrDefault(emptyList())
            movies = applyFilters(cached)
            loading = false          // 立刻出画面，不等网络
        }
        // 后台补货：一次抓多页，边抓边更新界面
        syncing = 1
        val fresh = runCatching {
            LibraryStore.refresh(pagesPerType = 5) { done, total, count ->
                syncDone = done
                android.util.Log.i("BawanLibrary", "补货 $done/$total 累计 $count 条")
            }
        }.getOrElse {
            error = it.message
            emptyList()
        }
        syncing = 0
        if (fresh.isNotEmpty()) {
            typeOptions = runCatching { LibraryStore.typeNames() }.getOrDefault(emptyList())
            movies = applyFilters(fresh)
        }
        loading = false
    }

    LaunchedEffect(Unit) { loadFromLibrary() }

    // 筛选条件变了就地重算，不用重新联网
    LaunchedEffect(keyword, typeFilter) {
        val cached = runCatching { LibraryStore.load() }.getOrDefault(emptyList())
        if (cached.isNotEmpty()) movies = applyFilters(cached)
    }

    // 首页直接点进来的作品
    LaunchedEffect(pendingMovie) {
        if (pendingMovie != null) {
            detailOf = pendingMovie
            onPendingConsumed()
        }
    }

    // ---------------- 搜索：完全接管整屏 ----------------
    //
    // 必须放在 loading / 网格之前 return，让搜索页独占屏幕。
    // 之前是塞在主 Column 里，结果顶栏压在上面、候选词列表还被挤出屏幕底部。
    // 搜索页自带返回键和输入框，本来就不需要外部再套一层。
    if (searchMode) {
        UnifiedSearchScreen(
            entryKey = "unified-search",
            onPickMovie = { m ->
                // 选中直接进详情，不用再搜一次（detailOf 一改就切到详情页）
                searchMode = false
                keyword = ""
                detailOf = m
            },
            onSearch = { kw ->
                // 选中「候选词」→ 就地按这个词筛选
                keyword = kw
                searchMode = false
            },
            onExit = {
                searchMode = false
                keyword = ""
            },
        )
        return
    }

    // ---------------- 详情 ----------------
    val detail = detailOf
    if (detail != null) {
        UnifiedDetailScreen(
            movie = detail,
            onBack = { detailOf = null },
            onPlay = onPlay,
            onPlaybackFailed = onPlaybackFailed,
        )
        return
    }

    if (loading && movies.isEmpty()) {
        SectionLoading("正在准备影视库…")
        return
    }

    val gridState = rememberLazyStaggeredGridState()

    // 加入搜索框的文本输入（遥控器字母键直接可用，
    // 不必每次都用屏幕软键盘一个个点）
    com.chinut.bawantv.ui.RegisterTextInput(
        onChar = { c -> if (c.isLetterOrDigit()) keyword = (keyword + c.lowercaseChar()).take(24) },
        onBackspace = { if (keyword.isNotEmpty()) keyword = keyword.dropLast(1) },
    )

    // 把瀑布流注册成"焦点导航的兜底滚动目标"：
    // 方向键在网格里找不到下一项时（屏幕外的项没有坐标），
    // 焦点系统会回调这里把列表滚一点，让新的项进入可视区。
    // 没有这一步，遥控器上下键在网格里完全不动 —— 这是之前最影响使用的问题。
    com.chinut.bawantv.ui.theme.RegisterViewportScroll { delta ->
        gridState.scrollBy(delta.toFloat())
    }

    Column(Modifier.fillMaxSize()) {
        // ---------- 顶栏 ----------
        //
        // 结构说明：低端影视是**基础库**，所以这里第一眼看到的是
        // "库里有什么"（数量），而不是各源的聚合状态。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("影视", color = Color.White, fontSize = Txt.Title, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(14.sdp))
            Text(
                if (syncing > 0) "低端影视库 ${movies.size} 部 · 补货中 ${syncDone}/20"
                else "低端影视库 ${movies.size} 部",
                color = if (syncing > 0) Ink.Amber else Ink.TextFaint,
                fontSize = Txt.Caption,
            )
            Spacer(Modifier.weight(1f))
            IconChip(Icons.Default.Search, "搜索") { searchMode = true }
            Spacer(Modifier.width(10.sdp))
            IconChip(Icons.Default.Refresh, "刷新片库") {
                scope.launch { loadFromLibrary() }
            }
        }

        Spacer(Modifier.height(12.sdp))

        // ---------- 类型筛选 ----------
        // 用库里真实出现过的分类，而不是写死一份 —— 片库更新后菜单自动跟着变
        if (typeOptions.isNotEmpty()) {
            TypeFilterRow(
                options = listOf("全部") + typeOptions,
                selected = if (typeFilter.isBlank()) "全部" else typeFilter,
                onSelect = { typeFilter = if (it == "全部") "" else it },
            )
            Spacer(Modifier.height(12.sdp))
        }

        if (movies.isEmpty()) {
            SectionEmpty(
                error?.let { "影视库准备失败：$it\n可按「刷新片库」重试" }
                    ?: "影视库还是空的\n按「刷新片库」从低端影视拉取片单"
            )
            return@Column
        }

        // ---------- 无限滚动海报墙 ----------
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Adaptive(minSize = 138.sdp),
            state = gridState,
            contentPadding = PaddingValues(bottom = 32.sdp, end = 8.sdp),
            horizontalArrangement = Arrangement.spacedBy(14.sdp),
            verticalItemSpacing = 16.sdp,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(movies, key = { it.id }) { m ->
                UnifiedCard(
                    movie = m,
                    focusKey = if (m == movies.firstOrNull()) entryKey else null,
                    // 不用 onLeft 覆盖：首页的导航栏已经没了，
                    // "nav:vod" 这个 key 没人注册，写了反而会把左键吃掉（踩过）
                    onLeft = null,
                    onClick = { detailOf = m },
                )
            }
        }
    }
}

/** 海报卡：片名 + 「N 个源」。 */
@Composable
private fun UnifiedCard(
    movie: UnifiedMovie,
    focusKey: Any?,
    onLeft: (() -> Unit)?,
    onClick: () -> Unit,
) {
    val focus = rememberTvFocusState()
    var broken by remember(movie.poster) { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .tvFocusable(
                focusState = focus,
                focusKey = focusKey,
                shape = RoundedCornerShape(Dim.CardRadius),
                focusedScale = 1.05f,
                borderWidth = 3.dp,
                baseBackground = Ink.Card,
                focusedBackground = Ink.CardStrong,
                onClick = onClick,
                onLeft = onLeft,
            )
            .padding(8.sdp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(Dim.ChipRadius))
                .background(Ink.Soft),
        ) {
            if (movie.hasPoster && !broken) {
                AsyncImage(
                    model = movie.poster,
                    contentDescription = movie.title,
                    contentScale = ContentScale.Crop,
                    onError = { broken = true },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Ink.Soft, Ink.Deep))),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        movie.title.take(1),
                        color = Ink.AccentBright.copy(alpha = 0.55f),
                        fontSize = 42.ssp,
                        fontWeight = FontWeight.Black,
                    )
                }
            }
            if (movie.score.isNotBlank()) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.sdp)
                        .background(Ink.Deep.copy(alpha = 0.78f), RoundedCornerShape(6.sdp))
                        .padding(horizontal = 6.sdp, vertical = 2.sdp)
                ) {
                    Text(movie.score, color = Ink.Amber, fontSize = Txt.Tiny)
                }
            }
            // 源的个数直接标在封面上：一眼知道这部片有几个地方能播
            if (movie.sourceCount > 1) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(5.sdp)
                        .background(Ink.Accent.copy(alpha = 0.9f), RoundedCornerShape(6.sdp))
                        .padding(horizontal = 6.sdp, vertical = 2.sdp)
                ) {
                    Text("${movie.sourceCount} 个源", color = Color.White, fontSize = Txt.Tiny)
                }
            }
        }
        Spacer(Modifier.height(7.sdp))
        Text(
            movie.title,
            color = if (focus.focused) Color.White else Ink.TextSecondary,
            fontSize = Txt.Label,
            fontWeight = if (focus.focused) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (movie.year.isNotBlank() || movie.typeName.isNotBlank()) {
            Text(
                listOf(movie.year, movie.typeName).filter { it.isNotBlank() }.joinToString(" · "),
                color = Ink.TextFaint,
                fontSize = Txt.Tiny,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ==================== 统一详情页 ====================

/**
 * 详情页：**把所有源摊开让用户挑**。
 *
 * 用户的心智是「我要看这部片」，不是「我要用哪个源」。
 * 所以这里不做"线路切换"这种需要用户试错的东西 —— 每个源就是一个播放按钮，
 * 写着源名、清晰度、有多少集。哪个能播点哪个，一目了然。
 */
@Composable
private fun UnifiedDetailScreen(
    movie: UnifiedMovie,
    onBack: () -> Unit,
    onPlay: (UnifiedMovie, UnifiedSource, List<UnifiedEpisode>, Int) -> Unit,
    /** 播放失败时上报「失败的源」，让上层自动换一个补充源。 */
    onPlaybackFailed: ((String) -> Unit)? = null,
) {
    var sources by remember(movie.id) { mutableStateOf(movie.sources) }
    var loading by remember(movie.id) { mutableStateOf(true) }
    var activeSource by remember(movie.id) { mutableIntStateOf(0) }
    /** 是否还在后台找补充源（TVBox）。 */
    var supplementing by remember(movie.id) { mutableStateOf(false) }

    // 剧集要到详情接口才有，进页面时并行补齐所有源。
    //
    // 源的组织规则（本版重点）：**低端影视永远排第一**，
    // TVBox 站只在"ddys 没有可播剧集"时才去搜同一个片名补充进来。
    // 这样详情页第一眼看到的、最容易点的，就是最靠谱的那条线路。
    LaunchedEffect(movie.id) {
        loading = true
        val withSupplement = com.chinut.bawantv.BawanApp.prefs.tvboxSupplement
        val full = runCatching {
            MovieAggregator.buildPlayableSources(movie, withSupplement = withSupplement)
        }.getOrDefault(movie.sources)
        sources = full
        // 默认停在第一个"有剧集"的源，避免打开就是空的
        activeSource = full.indexOfFirst { it.episodes.isNotEmpty() }.coerceAtLeast(0)
        loading = false
    }

    val current = sources.getOrNull(activeSource)

    Box(Modifier.fillMaxSize()) {
        // 背景用海报做模糊底，大屏质感
        if (movie.hasPoster) {
            AsyncImage(
                model = movie.poster,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Ink.Deep.copy(alpha = 0.95f),
                                Ink.Deep.copy(alpha = 0.88f),
                                Ink.Deep.copy(alpha = 0.96f),
                            )
                        )
                    )
            )
        }

        Row(Modifier.fillMaxSize().padding(Dim.SafeV)) {
            // ---------- 左：海报 + 信息 ----------
            Column(Modifier.width(252.sdp)) {
                Box(
                    Modifier
                        .width(252.sdp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(Dim.CardRadius))
                        .background(Ink.Soft),
                ) {
                    if (movie.hasPoster) {
                        AsyncImage(
                            model = movie.poster,
                            contentDescription = movie.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Spacer(Modifier.height(12.sdp))
                Text(
                    movie.title,
                    color = Color.White,
                    fontSize = 24.ssp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.sdp))
                Text(
                    listOf(movie.year, movie.area, movie.typeName)
                        .filter { it.isNotBlank() }.joinToString(" · "),
                    color = Ink.TextTertiary,
                    fontSize = Txt.Caption,
                )
                if (movie.score.isNotBlank()) {
                    Spacer(Modifier.height(4.sdp))
                    Text("评分 ${movie.score}", color = Ink.Amber, fontSize = Txt.Caption)
                }
            }

            Spacer(Modifier.width(26.sdp))

            // ---------- 右：源列表 + 剧集 ----------
            Column(Modifier.fillMaxSize()) {
                Text(
                    if (loading) "正在获取各源剧集…" else "可选播放源（${sources.size} 个）",
                    color = Color.White,
                    fontSize = Txt.Section,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.sdp))
                Text(
                    "不用手动切源：哪个能播就点哪个",
                    color = Ink.TextFaint,
                    fontSize = Txt.Caption,
                )
                Spacer(Modifier.height(12.sdp))

                // 源按钮一排排铺开
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 190.sdp),
                    horizontalArrangement = Arrangement.spacedBy(10.sdp),
                    verticalArrangement = Arrangement.spacedBy(10.sdp),
                    modifier = Modifier.height(if (sources.size > 4) 168.sdp else 88.sdp),
                ) {
                    items(sources.size) { i ->
                        val s = sources[i]
                        SourceButton(
                            source = s,
                            active = i == activeSource,
                            loading = loading && s.episodes.isEmpty(),
                            onClick = {
                                activeSource = i
                                // 电影只有一个"剧集"，直接起播最省事
                                if (s.episodes.size == 1) onPlay(movie, s, s.episodes, 0)
                            },
                        )
                    }
                }

                Spacer(Modifier.height(14.sdp))

                // 选中源的剧集
                val eps = current?.episodes.orEmpty()
                if (eps.isNotEmpty()) {
                    Text(
                        "剧集（${eps.size}）",
                        color = Ink.TextSecondary,
                        fontSize = Txt.Label,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.sdp))
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 112.sdp),
                        horizontalArrangement = Arrangement.spacedBy(8.sdp),
                        verticalArrangement = Arrangement.spacedBy(8.sdp),
                        contentPadding = PaddingValues(bottom = 24.sdp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(eps.size) { i ->
                            EpisodeButton(
                                name = eps[i].name,
                                onClick = { if (current != null) onPlay(movie, current, eps, i) },
                            )
                        }
                    }
                } else if (!loading) {
                    Text(
                        "这个源没有返回可播放的剧集，换一个源试试",
                        color = Ink.Amber,
                        fontSize = Txt.Label,
                    )
                }
            }
        }
    }
}

/** 一个源的播放按钮。 */
@Composable
private fun SourceButton(
    source: UnifiedSource,
    active: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
) {
    val f = rememberTvFocusState()
    Row(
        Modifier
            .fillMaxWidth()
            .height(74.sdp)
            .tvFocusable(
                focusState = f,
                shape = RoundedCornerShape(Dim.CardRadius),
                focusedScale = 1.04f,
                borderWidth = 3.dp,
                baseBackground = if (active) Ink.AccentSoft else Ink.Card,
                focusedBackground = Ink.CardStrong,
                onClick = onClick,
            )
            .padding(horizontal = 14.sdp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(34.sdp)
                .background(
                    if (active) Ink.Accent else Ink.CardStrong,
                    RoundedCornerShape(17.sdp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = null,
                tint = if (active) Color.White else Ink.AccentBright,
                modifier = Modifier.size(20.sdp),
            )
        }
        Spacer(Modifier.width(12.sdp))
        Column(Modifier.weight(1f)) {
            Text(
                source.name,
                color = if (f.focused) Color.White else Ink.TextSecondary,
                fontSize = Txt.Label,
                fontWeight = if (active || f.focused) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.sdp))
            Text(
                buildString {
                    if (loading) append("读取中…")
                    else if (source.episodeCount > 0) append("${source.episodeCount} 集")
                    else if (source.quality.isNotBlank()) append(source.quality)
                    else append("点击播放")
                    if (source.quality.isNotBlank() && source.episodeCount > 0) {
                        append("  ·  ").append(source.quality)
                    }
                },
                color = Ink.TextFaint,
                fontSize = Txt.Tiny,
                maxLines = 1,
            )
        }
    }
}

/** 一集。 */
@Composable
private fun EpisodeButton(name: String, onClick: () -> Unit) {
    val f = rememberTvFocusState()
    Box(
        Modifier
            .fillMaxWidth()
            .height(42.sdp)
            .tvFocusable(
                focusState = f,
                shape = RoundedCornerShape(10.sdp),
                focusedScale = 1.06f,
                borderWidth = 3.dp,
                baseBackground = Ink.Card,
                focusedBackground = Ink.AccentSoft,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name,
            color = if (f.focused) Color.White else Ink.TextSecondary,
            fontSize = Txt.Caption,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 6.sdp),
        )
    }
}

/** 顶栏小按钮（图标 + 文字）。 */
/**
 * 类型筛选栏。
 *
 * 选项来自**库里真实出现过的分类**（[LibraryStore.typeNames]），
 * 不是写死的 —— 片库内容变了菜单自动跟着变。
 */
@Composable
private fun TypeFilterRow(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val rowState = rememberLazyListState()
    LazyRow(
        state = rowState,
        horizontalArrangement = Arrangement.spacedBy(10.sdp),
        contentPadding = PaddingValues(end = 8.sdp),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.sdp),
    ) {
        items(options, key = { "tf:$it" }) { name ->
            val f = rememberTvFocusState()
            val on = name == selected
            Box(
                Modifier
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(14.sdp))
                    .background(
                        when {
                            on -> Ink.Accent.copy(alpha = 0.30f)
                            f.focused -> Color.White.copy(alpha = 0.14f)
                            else -> Color.White.copy(alpha = 0.06f)
                        }
                    )
                    .focusBorder(
                        visible = f.focused || on,
                        cornerRadius = 14.sdp,
                        color = if (f.focused) Ink.AccentBright else Ink.Accent,
                        width = if (f.focused) 3.sdp else 2.sdp,
                    )
                    .tvFocusable(
                        focusState = f,
                        shape = RoundedCornerShape(14.sdp),
                        focusedScale = 1.05f,
                        glow = false,
                        borderWidth = 0.sdp,
                        baseBackground = Color.Transparent,
                        focusedBackground = Color.Transparent,
                        onClick = { onSelect(name) },
                    )
                    .padding(horizontal = 18.sdp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    name,
                    color = if (on || f.focused) Color.White else Ink.TextSecondary,
                    fontSize = Txt.Caption.ssp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun IconChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val f = rememberTvFocusState()
    Row(
        Modifier
            .height(42.sdp)
            .tvFocusable(
                focusState = f,
                shape = RoundedCornerShape(Dim.ChipRadius),
                focusedScale = 1.06f,
                glow = false,
                borderWidth = 2.dp,
                baseBackground = Ink.Card,
                focusedBackground = Ink.CardStrong,
                onClick = onClick,
            )
            .padding(horizontal = 14.sdp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (f.focused) Color.White else Ink.TextSecondary,
            modifier = Modifier.size(18.sdp),
        )
        Spacer(Modifier.width(7.sdp))
        Text(label, color = Ink.TextSecondary, fontSize = Txt.Caption, maxLines = 1)
    }
}
