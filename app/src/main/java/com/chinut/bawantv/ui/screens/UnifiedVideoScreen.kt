package com.chinut.bawantv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.CircularProgressIndicator
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
import com.chinut.bawantv.ui.theme.registerViewportScroll
import com.chinut.bawantv.unified.LibraryStore
import com.chinut.bawantv.ui.theme.Ink
import com.chinut.bawantv.ui.theme.LocalTvFocusManager
import com.chinut.bawantv.ui.theme.rememberTvFocusState
import com.chinut.bawantv.ui.theme.sdp
import com.chinut.bawantv.ui.theme.ssp
import com.chinut.bawantv.ui.theme.tvFocusable
import com.chinut.bawantv.ui.theme.Txt
import com.chinut.bawantv.unified.MovieAggregator
import com.chinut.bawantv.unified.Episode
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
    /**
     * 详情页开/关时上报，让主框架的返回键能"先退详情、再退板块"。
     */
    onDetailChanged: (Boolean) -> Unit = {},
    /**
     * 当前正在看详情的作品。**由主框架持有**（不放在本文件里）。
     *
     * 为什么必须提升上去：播放时 BawanRoot 会把整个内容树摘掉 ——
     * 那是为了修直播卡顿，主界面不能和播放器抢 CPU。但树一摘，
     * 本文件里的 remember 状态就全没了：返回时"正在看哪部片"变成 null，
     * 用户就看到**从播放页莫名其妙跳回列表**（而不是回到那部片的详情）。
     */
    externalDetail: UnifiedMovie? = null,
    onExternalDetailChanged: (UnifiedMovie?) -> Unit = {},
    /** 网格滚动位置（首个可见项 + 偏移），由主框架持有以便返回后恢复。 */
    externalGridIndex: Int = 0,
    externalGridOffset: Int = 0,
    onExternalGridScroll: (Int, Int) -> Unit = { _, _ -> },
    /**
     * 返回本页时要恢复焦点的影片 id。
     *
     * 为什么需要：播放/详情会把本页整棵树卸掉，重挂时焦点会落到"第一个
     * 可聚焦项"上 —— 用户看到的就是"选中框跳到了一部不知道哪里的电影"。
     */
    externalFocusMovieId: String = "",
    onExternalFocusMovie: (String) -> Unit = {},
    /** 调试：详情页数据就绪后自动起播第一集（用于自动化验证播放页）。 */
    debugAutoPlay: Boolean = false,
    /** 调试：自动起播只做一次，做完通知上层复位。 */
    onDebugAutoPlayConsumed: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    /** 起播：上抛主框架，在根布局层全屏渲染 */
    onPlay: (UnifiedMovie, UnifiedSource, List<Episode>, Int) -> Unit,
) {

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

    /** 分类切换面板是否打开（遥控器三横键呼出）。 */
    var typePanel by remember { mutableStateOf(false) }

    /** 面板里的光标位置。 */
    var typeCursor by remember { mutableIntStateOf(0) }

    /**
     * 面板里列出的条目。
     *
     * 第一项固定是「搜索」—— 头部的搜索按钮被去掉了，
     * 不留个入口的话搜索功能就没法用了。
     */
    /**
     * 海报卡片焦点 key 的前缀。
     *
     * 用「前缀 + 影片 id」作为稳定 key，才能把焦点存下来再恢复；
     * 原来非首张卡片用的是 `remember { Any() }`，每次重挂都是新对象。
     */
    val cardKeyPrefix = "vod:card:"

    val typePanelItems = remember(typeOptions) {
        listOf("搜索") + listOf("全部") + typeOptions
    }

    // 调试入口：--es dsh_route vod_search 时直接进搜索态。
    // LocalContext.current 必须在 composable 作用域里取，不能塞进 remember 的 lambda。
    val debugRoute = (androidx.compose.ui.platform.LocalContext.current as? android.app.Activity)
        ?.intent?.getStringExtra("dsh_route")
    var searchMode by remember { mutableStateOf(debugRoute == "vod_search") }
    // 外部（主框架）给了就用它，否则用本地 state —— 这样两种调用方式都能工作
    var localDetail by remember { mutableStateOf<UnifiedMovie?>(null) }
    val detailOf: UnifiedMovie? = externalDetail ?: localDetail
    val setDetailOf: (UnifiedMovie?) -> Unit = { m ->
        onExternalDetailChanged(m)
        localDetail = m
    }

    // 返回键：详情页打开时先关详情（回影视列表），否则回首页。
    // 用户要求：不要在详情页一路跳回首页。
    BackHandler(enabled = detailOf != null || onBack != null) {
        if (detailOf != null) setDetailOf(null) else onBack?.invoke()
    }

    // 上报"当前是否在详情页"，供主框架决定返回键行为
    LaunchedEffect(detailOf) { onDetailChanged(detailOf != null) }

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
            setDetailOf(pendingMovie)
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
                setDetailOf(m)
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
            onBack = { setDetailOf(null) },
            debugAutoPlay = debugAutoPlay,
            onDebugAutoPlayConsumed = onDebugAutoPlayConsumed,
            onPlay = onPlay,
        )
        return
    }

    if (loading && movies.isEmpty()) {
        SectionLoading("正在准备影视库…")
        return
    }

    val gridState = rememberLazyStaggeredGridState(
        initialFirstVisibleItemIndex = externalGridIndex,
        initialFirstVisibleItemScrollOffset = externalGridOffset,
    )

    // 滚动位置上报（节流：只在值真的变了才写回主框架，避免每帧重组）
    androidx.compose.runtime.LaunchedEffect(gridState) {
        snapshotFlow {
            gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
        }.collect { (i, o) -> onExternalGridScroll(i, o) }
    }


    // ---------- 三横键：切换分类 ----------
    //
    // 用户的设计：分类不再用光标去选（那样会和瀑布流抢焦点），
    // 而是**独立于焦点系统的一个面板**。屏幕提示文字也这么写。
    //
    // 面板第一项是「搜索」—— 因为头部的搜索按钮被去掉了，
    // 不留入口的话搜索功能就没法用了。
    androidx.compose.runtime.DisposableEffect(manager, typePanel) {
        manager?.setRawKeyInterceptor { code ->
            if (code == android.view.KeyEvent.KEYCODE_MENU) {
                typePanel = !typePanel
                if (typePanel) {
                    // 落在「搜索」上（第一项）。当前分类有「使用中」标记，
                    // 用户一眼能看到自己在哪一类；按一下下键就到分类列表。
                    typeCursor = 0
                }
                true
            } else {
                false
            }
        }
        onDispose { manager?.setRawKeyInterceptor(null) }
    }

    // 面板打开时接管方向键与确定键（不要影响瀑布流）
    androidx.compose.runtime.DisposableEffect(manager, typePanel) {
        if (!typePanel) return@DisposableEffect onDispose { }
        manager?.setKeyInterceptor { dir ->
            when (dir) {
                com.chinut.bawantv.ui.theme.Direction.Up -> {
                    typeCursor = (typeCursor - 1).coerceAtLeast(0); true
                }

                com.chinut.bawantv.ui.theme.Direction.Down -> {
                    typeCursor = (typeCursor + 1).coerceAtMost(typePanelItems.size - 1); true
                }

                com.chinut.bawantv.ui.theme.Direction.Left,
                com.chinut.bawantv.ui.theme.Direction.Right,
                -> true
            }
        }
        manager?.setConfirmInterceptor {
            val picked = typePanelItems.getOrNull(typeCursor)
            when {
                // 「搜索」是**动作**，不是分类。
                // 之前它落到下面的 else，typeFilter 被设成 "搜索"、
                // 列表被过滤成空 —— 界面显示"影视库还是空的"，看着像数据丢了。
                picked == null -> Unit
                picked == "搜索" -> searchMode = true
                picked == "全部" -> {
                    typeFilter = ""
                    scope.launch { gridState.scrollToItem(0) }
                }
                else -> {
                    typeFilter = picked
                    scope.launch { gridState.scrollToItem(0) }
                }
            }
            typePanel = false
            true
        }
        onDispose {
            manager?.setKeyInterceptor(null)
            manager?.setConfirmInterceptor(null)
        }
    }

    // 加入搜索框的文本输入（遥控器字母键直接可用，
    // 不必每次都用屏幕软键盘一个个点）
    com.chinut.bawantv.ui.RegisterTextInput(
        onChar = { c -> if (c.isLetterOrDigit()) keyword = (keyword + c.lowercaseChar()).take(24) },
        onBackspace = { if (keyword.isNotEmpty()) keyword = keyword.dropLast(1) },
    )

    // 把瀑布流注册成"焦点导航的兜底滚动目标"：
    // 方向键在网格里找不到下一项时（屏幕外的项没有坐标），
    // 焦点系统会回调把列表滚一点，让新的项进入可视区。
    // 注册成 Modifier 形式，让焦点系统能知道这个容器的范围 ——
    // 否则「在分类栏按右键」会把网格滚下去（真机反馈的 bug）。
    // ---------- 记录当前焦点属于哪部影片 ----------
    //
    // 播放/详情会把本页整棵树卸掉，重挂时焦点会落到"第一个可聚焦项"上 ——
    // 用户看到的就是"选中框跳到了一部不知道哪里的电影"。
    // 所以离开前把焦点存到主框架，回来时再恢复。
    androidx.compose.runtime.LaunchedEffect(manager) {
        if (manager == null) return@LaunchedEffect
        snapshotFlow { manager.focusedKey }
            .collect { key ->
                val s = key?.toString().orEmpty()
                if (s.startsWith(cardKeyPrefix)) {
                    onExternalFocusMovie(s.removePrefix(cardKeyPrefix))
                }
            }
    }

    // ---------- 重新挂载后恢复焦点 ----------
    //
    // 瀑布流是懒加载的，目标项可能还没被组合出来，所以要重试几次。
    // 恢复成功就不再打扰用户；一直失败就退回第一项，至少保证有焦点。
    // ⚠️ 依赖里**不能**放 externalFocusMovieId。
    //
    // 踩过的坑：记录焦点时我们会往主框架写这个值，而它又是这个 effect 的依赖 ——
    // 于是每记录一次焦点就把恢复过程打断一次，恢复永远做不完
    // （日志表现：反复打印"准备恢复焦点"，但从不打印"已恢复"）。
    //
    // 现在只在**这一轮挂载**开始时取一次目标：
    // 用 remember(movies.size) 固定住初值，effect 只跟着 movies.size 走。
    val restoreTarget = remember(movies.size) {
        externalFocusMovieId.takeIf { it.isNotBlank() }?.let { cardKeyPrefix + it }
    }
    androidx.compose.runtime.LaunchedEffect(movies.size) {
        if (movies.isEmpty()) return@LaunchedEffect
        val target = restoreTarget ?: return@LaunchedEffect
        repeat(25) {
            kotlinx.coroutines.delay(120)
            if (manager?.focusedKey?.toString() == target) return@LaunchedEffect
            if (manager?.keys?.contains(target) == true) {
                manager.moveTo(target)
                return@LaunchedEffect
            }
        }
        android.util.Log.w("BawanVod", "恢复焦点失败（$target），退回第一项")
        manager?.moveTo(entryKey)
    }

    val gridScrollModifier = Modifier.registerViewportScroll { delta ->
        gridState.scrollBy(delta.toFloat())
    }

    // ⚠️ 必须留安全边距。
    //
    // 用户反馈："整体影视界面感觉像往左上角移动了，最左侧一列的瀑布流图片
    // 部分已经出了屏幕，『影视』两个字紧紧贴在屏幕边缘"。
    //
    // 原因就是这里原来是裸的 fillMaxSize()，一点边距都没有；
    // 电视普遍还有 overscan（边缘会被切掉几像素），于是标题贴边、首列被裁。
    // 首页用的是 SafeH*0.7 / SafeV*0.8，这里保持一致。
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = Dim.SafeH * 0.7f, vertical = Dim.SafeV * 0.8f),
    ) {
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
            // ---------- 这里原来有「搜索」「刷新片库」两个按钮 ----------
            //
            // 用户要求去掉，原因很实在：**在电视上它们只会添乱**。
            // 焦点在瀑布流里活动时，按上键会被几何导航带到这两个按钮上，
            // 于是出现"一会儿在分类里、一会儿跑到刷新、一会儿又回瀑布流"的乱跳。
            //
            // 现在影视页**只有瀑布流一个可聚焦区域**，方向键再也不会跑出去。
            // 刷新仍然是自动的（进页面就在后台补货），不需要按钮。
            Text(
                "按遥控器「三横键」切换分类",
                color = Ink.TextFaint,
                fontSize = Txt.Caption,
            )
        }

        Spacer(Modifier.height(12.sdp))

        // ---------- 当前分类（**不可聚焦**，只是一行状态） ----------
        //
        // 用户明确要求："分类栏直接是按设置键（三横键）切换，不让光标进入分类栏里"。
        //
        // 这样影视页就只剩瀑布流一个可聚焦区域，上下键的范围是确定的，
        // 不会再出现"光标在几个区域之间乱跳"。
        Text(
            if (typeFilter.isBlank()) "分类：全部" else "分类：$typeFilter",
            color = Ink.AccentBright,
            fontSize = Txt.Label,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(12.sdp))


        if (movies.isEmpty()) {
            SectionEmpty(
                error?.let { "影视库准备失败：$it\n可按「刷新片库」重试" }
                    ?: "影视库还是空的\n按「刷新片库」从低端影视拉取片单"
            )
            return@Column
        }

        // ---------- 分类切换面板（三横键呼出） ----------
        //
        // 单独画在最上层、**不参与焦点系统** ——
        // 这正是不让光标"跑进分类栏"的实现方式。
        // 用两列排布，分类多的时候也不会超出屏幕。
        AnimatedVisibility(
            visible = typePanel,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(140)),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Dim.CardRadius))
                    .background(Ink.Deep.copy(alpha = 0.97f))
                    .focusBorder(
                        visible = true,
                        cornerRadius = Dim.CardRadius,
                        color = Ink.AccentBright,
                        width = 2.sdp,
                    )
                    .padding(18.sdp),
            ) {
                Text(
                    "切换分类",
                    color = Color.White,
                    fontSize = Txt.Section,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.sdp))
                Text(
                    "上下选择 · 「确定」切换 · 「三横键」关闭",
                    color = Ink.TextFaint,
                    fontSize = Txt.Tiny,
                )
                Spacer(Modifier.height(12.sdp))

                val perCol = (typePanelItems.size + 1) / 2
                Row(horizontalArrangement = Arrangement.spacedBy(10.sdp)) {
                    listOf(0, 1).forEach { col ->
                        Column(Modifier.weight(1f)) {
                            val from = col * perCol
                            val to = (from + perCol).coerceAtMost(typePanelItems.size)
                            for (i in from until to) {
                                val name = typePanelItems[i]
                                val isCur = i == typeCursor
                                val isActive = (name == "全部" && typeFilter.isBlank()) ||
                                    (name != "全部" && name == typeFilter)
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 6.sdp)
                                        .clip(RoundedCornerShape(10.sdp))
                                        .background(
                                            when {
                                                isCur -> Ink.Accent.copy(alpha = 0.35f)
                                                isActive -> Color.White.copy(alpha = 0.10f)
                                                else -> Color.Transparent
                                            }
                                        )
                                        .focusBorder(
                                            visible = isCur,
                                            cornerRadius = 10.sdp,
                                            color = Ink.AccentBright,
                                            width = 2.sdp,
                                        )
                                        .padding(horizontal = 12.sdp, vertical = 9.sdp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        name,
                                        color = if (isCur || isActive) Color.White else Ink.TextSecondary,
                                        fontSize = Txt.Label,
                                        fontWeight = if (isCur) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (isActive) {
                                        Text("使用中", color = Ink.Green, fontSize = Txt.Tiny)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }


        // ---------- 无限滚动海报墙 ----------
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Adaptive(minSize = 138.sdp),
            state = gridState,
            contentPadding = PaddingValues(bottom = 32.sdp, end = 8.sdp),
            horizontalArrangement = Arrangement.spacedBy(14.sdp),
            verticalItemSpacing = 16.sdp,
            modifier = Modifier.fillMaxSize().then(gridScrollModifier),
        ) {
            items(movies, key = { it.id }) { m ->
                UnifiedCard(
                    movie = m,
                    // 用**影片 id** 作为焦点 key（原来除第一张外都是 null，
                    // 于是 tvFocusable 内部用 remember{Any()} 生成 —— 那种 key
                    // 每次重挂都是新对象，焦点没法恢复）。
                    focusKey = "vod:card:${m.id}",
                    // 不用 onLeft 覆盖：首页的导航栏已经没了，
                    // "nav:vod" 这个 key 没人注册，写了反而会把左键吃掉（踩过）
                    onLeft = null,
                    // ---------- 向上：先滚回画面，别直接跳到分类栏 ----------
                    //
                    // 用户反馈："向下卷动瀑布流看了很久后再向上卷动，
                    // 选择框会直接跳到分类里，而不是继续向上卷动画面"。
                    //
                    // 根因：焦点在第 1 行时，向上找最近的可聚焦项就是**分类栏**
                    // （几何上确实离得最近），所以焦点一下跳上去了。
                    //
                    // 修法：只要网格**还能往上滚**（firstVisibleItemIndex > 0 或
                    // 偏移量不为 0），就先把网格滚回去、焦点留在原地。
                    // 只有在网格已经滚到顶时才真的把焦点交给上面的分类栏。
                    onUp = {
                        // 向上键：**只在"上面还有未显示的内容"时才滚动**，
                        // 已经滚到顶就把事件交回几何导航，让焦点自然往上走一行。
                        //
                        // 这里踩过两次坑，都值得记下来：
                        //
                        //   1. 最初没有覆盖 → 焦点在第一行按上，几何导航会跳到
                        //      页面最上方的其它控件（分类栏/刷新按钮），用户说"乱跳"。
                        //   2. 后来写成"无条件滚动 + return true" → 焦点**再也无法
                        //      向上移动**，下到第二行就回不了第一行；
                        //      而向下没有覆盖处理、走的几何导航，于是"上不去、下得来"，
                        //      行为不对称，非常怪。
                        //
                        // 正确做法是区分这两种情形，并且允许返回 false 交回导航 ——
                        // 为此把方向处理器的返回类型从 Unit 改成了 Boolean。
                        val gridAtTop = gridState.firstVisibleItemIndex == 0 &&
                            gridState.firstVisibleItemScrollOffset == 0
                        if (gridAtTop) {
                            // 已经在最上面：交回几何导航。
                            // 几何导航会去找"上方最近的可聚焦项"——因为分类栏和
                            // 右上角按钮都已经不可聚焦了，所以它只会走到上一行；
                            // 真到第一行时上方无项可去，焦点就停住。
                            false
                        } else {
                            scope.launch { gridState.scrollBy(-GRID_SCROLL_STEP) }
                            true
                        }
                    },
                    onClick = { setDetailOf(m) },
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
    onLeft: (() -> Boolean)?,
    onUp: (() -> Boolean)? = null,
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
                onUp = onUp,
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
    onPlay: (UnifiedMovie, UnifiedSource, List<Episode>, Int) -> Unit,
    /** 调试：数据就绪后自动起播第一集（自动化验证播放页用）。 */
    debugAutoPlay: Boolean = false,
    onDebugAutoPlayConsumed: () -> Unit = {},
) {
    var sources by remember(movie.id) { mutableStateOf(movie.sources) }
    var loading by remember(movie.id) { mutableStateOf(true) }
    var activeSource by remember(movie.id) { mutableIntStateOf(0) }

    /**
     * "剧集确实为空"是否已经确认。
     *
     * 拉取是两次请求（详情 + sources），源列表可能先到、剧集稍后补上。
     * 在这个标志变 true 之前，空列表只显示"正在获取"，不报错。
     * 详见下方剧集区的注释。
     */
    var emptySettled by remember(movie.id, activeSource) { mutableStateOf(false) }

    // 调试自动起播：等剧集到手后点第一集。
    // 只在自动化验证时开启（debugAutoPlay），正常使用完全不受影响。
    LaunchedEffect(debugAutoPlay, sources, loading) {
        if (!debugAutoPlay || loading) return@LaunchedEffect
        val src = sources.getOrNull(activeSource) ?: return@LaunchedEffect
        val eps = src.episodes
        if (eps.isEmpty()) return@LaunchedEffect
        android.util.Log.i("BawanRoute", "debug 自动起播：${movie.title} 第1集")
        onPlay(movie, src, eps, 0)
        onDebugAutoPlayConsumed()
    }

    // 剧集要到详情接口才有，进页面时补齐。
    //
    // 影视现在只有低端影视一个源，所以这里简单直接：
    // 拿它的官方接口取剧集列表（**直连 m3u8，不需要任何解析接口**）。
    //
    // ⚠️ 必须**重试**，这是用户反馈的"进来说没有源、过一会再进来又有了"的根因：
    // 之前只试一次，遇到一次超时/抖动就把结果当最终结论，界面显示"没有剧集"。
    // 而重进一次网络好了，就又有内容了 —— 用户看到的就是随机行为。
    //
    // 现在按 1s / 2s / 4s 退避重试，最多 4 次；只要有一次拿到剧集就停。
    LaunchedEffect(movie.id) {
        loading = true
        var best = movie.sources
        val delays = listOf(0L, 1_000L, 2_000L, 4_000L)
        for ((attempt, waitMs) in delays.withIndex()) {
            if (waitMs > 0) kotlinx.coroutines.delay(waitMs)
            val got = runCatching { MovieAggregator.loadSources(movie) }.getOrNull()
            if (got != null && got.any { it.episodes.isNotEmpty() }) {
                best = got
                android.util.Log.i(
                    "BawanVod",
                    "取源成功（第 ${attempt + 1} 次尝试）《${movie.title}》",
                )
                break
            }
            if (got != null) best = got
            android.util.Log.w(
                "BawanVod",
                "取源第 ${attempt + 1} 次未拿到剧集《${movie.title}》，准备重试",
            )
        }
        sources = best
        // 默认停在第一个"有剧集"的源，避免打开就是空的
        activeSource = best.indexOfFirst { it.episodes.isNotEmpty() }.coerceAtLeast(0)
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
                                // 电影只有一个"剧集"，直接起播最省事。
                                // **必须判非空**：源刚显示出来、剧集还在拉的时候，
                                // 点下去会拿一个空列表去起播，播放器那边会闪退。
                                if (s.episodes.size == 1) {
                                    onPlay(movie, s, s.episodes, 0)
                                }
                            },
                        )
                    }
                }

                Spacer(Modifier.height(14.sdp))

                // 选中源的剧集
                val eps = current?.episodes.orEmpty()
                if (eps.isNotEmpty()) {
                    Text(
                        // 电影只有一条时叫"线路"更准确 —— 它本来就是播放源，
                        // 不是"第几集"
                        if (eps.size == 1) "播放线路（1 条）" else "剧集 / 线路（${eps.size}）",
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
                            // 命名规则（用户反馈"4 个按钮都叫播放，看不出区别"）：
                            //
                            // ddys 接口对**电影**给的剧集 label 是空串，于是全都落到
                            // 兜底的"播放"上，一排按钮长得一模一样 —— 用户根本不知道该点哪个。
                            //
                            // 实际上这时候"剧集"就是**不同的线路**（播放源 1/2/3/4）。
                            // 所以：只有一条时叫"立即播放"；多条时按线路编号命名，
                            // 接口给了有意义的名字（剧集类常是"第1集/国语/粤语"）就优先用它。
                            val raw = eps[i].name
                            val label = when {
                                raw.isNotBlank() && raw != "播放" -> raw
                                eps.size == 1 -> "立即播放"
                                else -> "线路 ${i + 1}"
                            }
                            EpisodeButton(
                                name = label,
                                onClick = { if (current != null) onPlay(movie, current, eps, i) },
                            )
                        }
                    }
                } else if (loading) {
                    // 还在拉取 → 明确说"正在获取"，不要报错
                    Text(
                        "正在获取剧集…",
                        color = Ink.TextTertiary,
                        fontSize = Txt.Label,
                    )
                } else if (!emptySettled) {
                    // 关键：**不要一发现列表为空就报错**。
                    //
                    // 用户反馈："经常显示『这个源没有返回可播放的剧集』，
                    // 有的时候就等一会就又有了 —— 既然没有你列出来干啥"。
                    //
                    // 原因是这里在数据到达前就下了结论：详情接口和 sources 接口
                    // 是两次请求，源先回来、剧集稍后补上，中间那个空档就弹了错误文案。
                    // 现在先等一小会儿，剧集到了就正常显示，真的没有才说没有。
                    LaunchedEffect(current?.id, loading) {
                        // loading 还是 true 说明剧集可能马上到，别急着判定为空
                        if (loading) return@LaunchedEffect
                        delay(1800)
                        emptySettled = true
                    }
                    Text(
                        "正在获取剧集…",
                        color = Ink.TextTertiary,
                        fontSize = Txt.Label,
                    )
                } else {
                    Text(
                        "这个源确实没有可播放的剧集，按「←」回上一页换一部试试",
                        color = Ink.Amber,
                        fontSize = Txt.Label,
                    )
                }
            }
        }
    }
}

/**
 * 一个源的按钮。
 *
 * ⚠️ 这里有个容易被误解的地方，值得写清楚：
 *
 * 源是**并行加载**的，所以详情页刚打开时会出现"某个源读取中"的状态。
 * 这种卡片以前画得和按钮一模一样（播放图标 + 卡片底色 + 可聚焦，
 * 聚焦时还有亮边框），用户就会去按它 —— 按了没反应，以为是坏了。
 *
 * 现在读取中的卡片：
 *   · **不可聚焦**（遥控器直接跳过）
 *   · 播放图标换成转圈
 *   · 底色压暗、文字改成「正在读取…」
 *
 * 读完之后它才恢复成真正可点的按钮。
 */
@Composable
private fun SourceButton(
    source: UnifiedSource,
    active: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
) {
    val f = rememberTvFocusState()

    // 读取中 = 还不能点。既不可聚焦，也不显示成按钮的样子。
    val clickable = !loading

    val bg = when {
        loading -> Ink.Card.copy(alpha = 0.45f)
        active -> Ink.AccentSoft
        else -> Ink.Card
    }
    val titleColor = when {
        loading -> Ink.TextFaint
        f.focused -> Color.White
        else -> Ink.TextSecondary
    }

    Row(
        Modifier
            .fillMaxWidth()
            .height(74.sdp)
            .tvFocusable(
                focusState = f,
                enabled = clickable,
                shape = RoundedCornerShape(Dim.CardRadius),
                focusedScale = 1.04f,
                borderWidth = 3.dp,
                baseBackground = bg,
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
                    when {
                        loading -> Ink.CardStrong.copy(alpha = 0.5f)
                        active -> Ink.Accent
                        else -> Ink.CardStrong
                    },
                    RoundedCornerShape(17.sdp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                // 转圈表示"在等"，而不是"可以点"
                CircularProgressIndicator(
                    modifier = Modifier.size(18.sdp),
                    color = Ink.TextFaint,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = if (active) Color.White else Ink.AccentBright,
                    modifier = Modifier.size(20.sdp),
                )
            }
        }
        Spacer(Modifier.width(12.sdp))
        Column(Modifier.weight(1f)) {
            Text(
                source.name,
                color = titleColor,
                fontSize = Txt.Label,
                fontWeight = if (!loading && (active || f.focused)) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.sdp))
            Text(
                buildString {
                    if (loading) append("正在读取…")
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

    // 类别栏也注册滚动。注册成 Modifier 形式后，焦点系统能分辨
    // "焦点在类别栏里"还是"在下面的网格里"，从而只滚对应的那个容器。
    val rowScrollModifier = Modifier.registerViewportScroll { delta ->
        rowState.scrollBy(delta.toFloat())
    }

    LazyRow(
        modifier = rowScrollModifier
            .fillMaxWidth()
            .height(48.sdp),
        state = rowState,
        horizontalArrangement = Arrangement.spacedBy(10.sdp),
        contentPadding = PaddingValues(end = 8.sdp),
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

/**
 * 「向上滚回画面」每次滚动的像素量。
 *
 * 取得比一屏小、比一行大一些：按一下能明显回退一段，
 * 又不会一下冲过头（用户是在找刚才看过的位置）。
 */
private const val GRID_SCROLL_STEP = 320f


