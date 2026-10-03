package com.chinut.bawantv.ui.screens

import android.annotation.SuppressLint
import android.net.Uri
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.chinut.bawantv.BawanApp
import com.chinut.bawantv.core.Http
import com.chinut.bawantv.live.LiveCatalog
import com.chinut.bawantv.live.LiveChannel
import com.chinut.bawantv.live.RecentLive
import com.chinut.bawantv.live.TvWebPlayerView
import com.chinut.bawantv.ui.theme.AmbientBackdrop
import com.chinut.bawantv.ui.theme.Dim
import com.chinut.bawantv.ui.theme.FocusKeys
import com.chinut.bawantv.ui.theme.focusBorder
import com.chinut.bawantv.ui.theme.frostedGlass
import com.chinut.bawantv.ui.theme.Ink
import com.chinut.bawantv.ui.theme.LocalTvFocusManager
import com.chinut.bawantv.ui.theme.rememberTvFocusState
import com.chinut.bawantv.ui.theme.sdp
import com.chinut.bawantv.ui.theme.ssp
import com.chinut.bawantv.ui.theme.tvFocusable
import com.chinut.bawantv.ui.theme.Txt
import com.chinut.bawantv.unified.MovieAggregator
import com.chinut.bawantv.unified.UnifiedMovie

/**
 * 首页各块的稳定焦点 key。
 *
 * 为什么需要：几何导航在「两块等宽、一上一下」时会挑错目标 ——
 * 从直播块按「下」会跑到「设置」而不是直觉上的「影视」，
 * 因为设置块的水平中心和直播块更接近。有了稳定 key 才能用
 * onDown/onUp 把跳转关系钉死。
 */
private const val KEY_MOVIE = "home:movie"

/**
 * 首页（重做版）：**左侧大面积直播 + 右侧两个按钮**。
 *
 * ```
 * ┌─────────────────────────────────────┬──────────────┐
 * │                                     │  影视         │
 * │   直播画面（进 App 直接开播）        │  海报墙       │
 * │   占屏幕绝大部分                     │              │
 * │                                     ├──────────────┤
 * │                                     │  ⚙ 设置      │
 * └─────────────────────────────────────┴──────────────┘
 * ```
 *
 * ## 设计取舍
 *
 * 之前试过"四个象限"，问题是对称网格**没有主次** —— 四块一样大，
 * 眼睛不知道该看哪，而且切得很碎，谈不上设计感。
 *
 * 现在改成**不对称**：直播吃掉左边绝大部分（它是最常看的、也是最该先抓住眼球的），
 * 右侧一列只放两个入口。视觉重心明确，画面本身也成了首页的背景。
 *
 * 右侧「影视」按钮里铺**热门电影海报**，但海报**不可选中** ——
 * 它们只是视觉，让这个入口一眼就知道点进去有什么；整块按钮才是一个焦点。
 * 这样既好看，又不会让遥控器在几十张海报之间迷路。
 *
 * ## 直播这块
 *
 * 进 App 直接开始播（画面 + 声音），像老电视开机就是台；
 * 按确定进全屏，那里才有 ↑↓ 换台 / ←→ 换源。
 * 迷你播放器刻意**不做按键拦截**，否则首页就没法导航了。
 */
@Composable
fun ImmersiveHome(
    entryKey: Any,
    /** 进入直播板块（全屏直播 + 换台） */
    onOpenLive: (LiveChannel, List<LiveChannel>) -> Unit,
    /** 进入影视板块 */
    onOpenMovieWall: () -> Unit,
    /** 进入短剧板块 */
    onOpenShortDrama: () -> Unit,
    /** 进入设置 */
    onOpenSettings: () -> Unit,
) {
    val prefs = BawanApp.prefs
    val context = LocalContext.current
    val manager = LocalTvFocusManager.current

    var liveChannel by remember { mutableStateOf<LiveChannel?>(null) }
    var playlist by remember { mutableStateOf<List<LiveChannel>>(emptyList()) }

    // 海报墙：**先用上次缓存秒开**，再后台刷新替换。
    // 不这么做的话每次冷启动都要等「聚合接口 → 合并 → 下图」三步，
    // 那块会空好几秒，很难看。
    var movies by remember {
        mutableStateOf(
            runCatching { MovieAggregator.cachedHomeMovies() }.getOrDefault(emptyList())
        )
    }

    LaunchedEffect(Unit) {
        // 开屏期间已经预热过了，直接用结果（省掉几百毫秒的解析）。
        // 万一没预热到（例如直接跳过了开屏），这里自己兜底算一次。
        val warmedGroups = com.chinut.bawantv.ui.HomeWarmup.groups()
        val warmedAll = com.chinut.bawantv.ui.HomeWarmup.allChannels()

        val all: List<LiveChannel> = warmedAll ?: run {
            val groups = warmedGroups
                ?: runCatching { LiveCatalog.builtin(context) }.getOrDefault(emptyList())
            // 跨分组去重的整表：点开后上下键能一路换到任何台
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
            uniq.values.toList()
        }

        // 优先最近播放过的台，其次上次播放的台，最后第一个
        val recentUrl = RecentLive.list().firstOrNull()?.second
        liveChannel = all.firstOrNull { it.url == recentUrl }
            ?: all.firstOrNull { it.url == prefs.lastChannelUrl }
            ?: all.firstOrNull()
        playlist = all
    }

    // 影视按钮里的海报：影视只保留低端影视，所以直接读它的本地库。
    // 开屏期间的 HomeWarmup 通常已经把库拉好了，这里主要兜住
    // "跳过开屏直接进首页"的情况。
    LaunchedEffect(Unit) {
        if (movies.isNotEmpty()) return@LaunchedEffect
        val fresh = runCatching {
            val lib = com.chinut.bawantv.unified.LibraryStore.load()
            if (lib.isNotEmpty()) lib
            else com.chinut.bawantv.unified.LibraryStore.refresh(pagesPerType = 2)
        }.getOrDefault(emptyList())
            .filter { it.hasPoster }
            .take(9)
        if (fresh.isNotEmpty()) {
            movies = fresh
            MovieAggregator.cacheHomeMovies(fresh)
        }
    }

    AmbientBackdrop(Modifier.fillMaxSize(), accent = Ink.Accent, secondary = Ink.Pink) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(Dim.SafeH * 0.7f, Dim.SafeV * 0.8f),
        ) {
            // ---------- 品牌头：Logo + 文字 ----------
            BrandHeader()

            Spacer(Modifier.height(18.sdp))

            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(20.sdp),
            ) {
            // ---------- 左：直播画面，占绝大部分 ----------
            LiveHero(
                channel = liveChannel,
                focusKey = entryKey,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                onEnter = {
                    liveChannel?.let { onOpenLive(it, playlist.ifEmpty { listOf(it) }) }
                },
            )

            // ---------- 右：影视 + 短剧 + 设置 ----------
            //
            // 三块等分高度。原来只有影视 + 设置两块，设置独占 116dp；
            // 现在把设置压到三分之一，腾出来的空间给短剧。
            //
            // 注意间隔从 20dp 收到 14dp —— 三块比两块更需要省纵向空间，
            // 否则影视那块的可用高度会被挤得放不下海报。
            Column(
                Modifier
                    .width(400.sdp)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(14.sdp),
            ) {
                MovieEntry(
                    movies = movies,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onEnter = onOpenMovieWall,
                    manager = manager,
                )
                ShortDramaEntry(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onEnter = onOpenShortDrama,
                    manager = manager,
                )
                SettingsEntry(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onEnter = onOpenSettings,
                    manager = manager,
                )
            }
            }
        }
    }
}

/**
 * 品牌头：Logo + 「焰火TV」+ slogan。
 * 放在最上方而不是塞进某个卡片里，是因为首页要**第一眼就传达这是什么 App**，
 * 而不是让用户从一堆内容里猜。高度压得很低，不抢内容空间。
 */
@Composable
private fun BrandHeader() {
    val r = com.chinut.bawantv.ui.theme.LocalResponsive.current
    Row(
        Modifier.fillMaxWidth().height(r.dp(56)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Logo：优先用设计稿的图形；取不到就只显示文字，保证任何构建都有品牌
        val logo = remember { com.chinut.bawantv.BawanApp.logoRes }
        if (logo != 0) {
            Image(
                painter = androidx.compose.ui.res.painterResource(id = logo),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(r.dp(50))
                    .clip(RoundedCornerShape(r.dp(14))),
            )
            Spacer(Modifier.width(r.dp(14)))
        }
        Text(
            "焰火TV",
            color = Color.White,
            fontSize = r.sp(30),
            fontWeight = FontWeight.Black,
        )
        Spacer(Modifier.width(r.dp(14)))
        Text(
            "焰火随想  美好随现",
            color = Ink.AccentBright.copy(alpha = 0.72f),
            fontSize = r.sp(14),
            letterSpacing = r.sp(3),
        )
    }
}

@Composable
private fun LiveHero(
    channel: LiveChannel?,
    focusKey: Any,
    modifier: Modifier,
    onEnter: () -> Unit,
) {
    val focus = rememberTvFocusState()
    // 需要它来做「下 → 影视」的显式跳转（几何导航在这里会挑错目标）
    val manager = com.chinut.bawantv.ui.theme.LocalTvFocusManager.current

    Box(
        modifier
            .frostedGlass(shape = RoundedCornerShape(Dim.BigRadius), strong = true)
            .shadow(
                elevation = if (focus.focused) 30.sdp else 10.sdp,
                shape = RoundedCornerShape(Dim.BigRadius),
                spotColor = Ink.AccentBright.copy(alpha = if (focus.focused) 0.7f else 0.18f),
            )
            // 选中框：明确的**实心亮边**。
            // 之前只有光晕，在电视上认不出来（老人尤其看不出哪个被选中）。
            .focusBorder(
                visible = focus.focused,
                cornerRadius = Dim.BigRadius,
                color = Ink.AccentBright,
                width = 4.sdp,
            )
            .tvFocusable(
                focusState = focus,
                focusKey = focusKey,
                shape = RoundedCornerShape(Dim.BigRadius),
                focusedScale = 1.0f,
                glow = false,
                borderWidth = 0.dp,
                baseBackground = Color.Transparent,
                focusedBackground = Color.Transparent,
                onClick = onEnter,
                // 显式指定「下 → 影视」。
                // 纯几何导航在这里会跑到「设置」去：两块等宽时，
                // 设置块的水平中心和直播块更接近，于是它算出来更"近"。
                // 但用户直觉是"下面左边那块"，所以这里钉死。
                onDown = { manager?.moveTo(KEY_MOVIE); true },
                onLeft = { true /* 已在最左，吃掉事件 */ },
            ),
    ) {
        if (channel != null) {
            LiveMiniPlayer(
                channel = channel,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(Dim.BigRadius)),
            )
        } else {
            Box(Modifier.fillMaxSize().background(Color.Black))
        }

        // 底部渐变 + 台标：让文字永远压得住画面
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(190.sdp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))
                    )
                )
        )

        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(26.sdp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 一点红：正在直播的通用语言
                    Box(
                        Modifier
                            .size(9.sdp)
                            .background(Ink.Red, RoundedCornerShape(5.sdp))
                    )
                    Spacer(Modifier.width(8.sdp))
                    Text(
                        "正在直播",
                        color = Color.White,
                        fontSize = Txt.Label,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(8.sdp))
                Text(
                    channel?.name ?: "载入频道…",
                    color = Color.White,
                    fontSize = 42.ssp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.sdp))
                Text(
                    "按「确定」进入全屏 · 上下键换台",
                    color = Ink.TextTertiary,
                    fontSize = Txt.Caption,
                )
            }
        }
    }
}

/**
 * 首页用的迷你直播播放器。
 *
 * 刻意**不拦截按键**：主播放页那套全局 `setKeyInterceptor` 在首页会把方向键全吃掉，
 * 结果就是根本没法导航。
 */
@SuppressLint("UnsafeOptInUsageError")
@Composable
private fun LiveMiniPlayer(channel: LiveChannel, modifier: Modifier) {
    val context = LocalContext.current
    val isWeb = LiveCatalog.isWebPage(channel.url)

    if (isWeb) {
        var view by remember { mutableStateOf<TvWebPlayerView?>(null) }
        AndroidView(
            factory = { ctx ->
                TvWebPlayerView(ctx).also {
                    view = it
                    it.loadChannel(channel.url)
                }
            },
            update = { it.loadChannel(channel.url) },
            modifier = modifier,
        )
        DisposableEffect(channel.url) {
            onDispose {
                runCatching {
                    view?.stopLoading()
                    view?.loadUrl("about:blank")
                    view?.destroy()
                }
                view = null
            }
        }
    } else {
        val player = remember(channel.url) {
            ExoPlayer.Builder(context).build().apply { playWhenReady = true }
        }
        LaunchedEffect(channel.url) {
            runCatching {
                val factory = DefaultHttpDataSource.Factory()
                    .setUserAgent(Http.UA_DESKTOP)
                    .setConnectTimeoutMs(12_000)
                    .setReadTimeoutMs(20_000)
                    .setAllowCrossProtocolRedirects(true)
                val src = if (channel.url.contains("m3u8")) {
                    HlsMediaSource.Factory(factory)
                        .setAllowChunklessPreparation(true)
                        .createMediaSource(MediaItem.fromUri(Uri.parse(channel.url)))
                } else {
                    ProgressiveMediaSource.Factory(factory)
                        .createMediaSource(MediaItem.fromUri(Uri.parse(channel.url)))
                }
                player.setMediaSource(src)
                player.prepare()
                player.play()
            }
        }
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    this.player = player
                    keepScreenOn = true
                }
            },
            modifier = modifier,
        )
        DisposableEffect(channel.url) {
            onDispose {
                runCatching {
                    player.stop()
                    player.release()
                }
            }
        }
    }
}

// ==================== 右上：影视入口（海报仅作视觉） ====================

@Composable
private fun MovieEntry(
    movies: List<UnifiedMovie>,
    modifier: Modifier,
    onEnter: () -> Unit,
    manager: com.chinut.bawantv.ui.theme.TvFocusManager?,
) {
    val focus = rememberTvFocusState()

    Row(
        modifier
            .frostedGlass(shape = RoundedCornerShape(Dim.BigRadius))
            .shadow(
                elevation = if (focus.focused) 26.sdp else 8.sdp,
                shape = RoundedCornerShape(Dim.BigRadius),
                spotColor = Ink.Pink.copy(alpha = if (focus.focused) 0.65f else 0.16f),
            )
            // 用粉色区分「影视」这块的选中框，和直播的蓝色区分开
            .focusBorder(
                visible = focus.focused,
                cornerRadius = Dim.BigRadius,
                color = Ink.Pink,
                width = 4.sdp,
            )
            .tvFocusable(
                focusState = focus,
                // 给一个稳定的 key，让直播块的 onDown 能精确跳到这里
                focusKey = KEY_MOVIE,
                shape = RoundedCornerShape(Dim.BigRadius),
                focusedScale = 1.0f,
                glow = false,
                borderWidth = 0.dp,
                baseBackground = Color.Transparent,
                focusedBackground = Color.Transparent,
                onClick = onEnter,
                // 这里**不能**写 onLeft = moveTo(FocusKeys.nav("home"))：
                // 首页的导航栏早就删了，没有任何地方注册 "nav:home" 这个 key，
                // moveTo 找不到目标会直接返回 —— 按键被消费掉却什么也不发生，
                // 结果就是「从影视按左键回不到左边的直播」。
                // 留空即可，几何导航本来就能正确找到左边的直播块。
            )
            .padding(horizontal = 24.sdp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(52.sdp)
                .background(
                    Brush.linearGradient(
                        listOf(Ink.Pink.copy(alpha = 0.34f), Ink.Pink.copy(alpha = 0.12f))
                    ),
                    RoundedCornerShape(16.sdp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Movie,
                contentDescription = null,
                tint = Ink.Pink,
                modifier = Modifier.size(26.sdp),
            )
        }
        Spacer(Modifier.width(18.sdp))
        Column(Modifier.weight(1f)) {
            Text(
                "影视",
                color = Color.White,
                fontSize = Txt.Section,
                fontWeight = FontWeight.Bold,
            )
            Text(
                // 原来的副标题写死「聚合全部源的影视库」；现在把片库规模也带上，
                // 用户一眼知道里面有多少内容（而且这和影视页顶栏的文案一致）
                if (movies.isNotEmpty()) "影视库 ${movies.size} 部 · 电影 / 剧集 / 综艺"
                else "电影 · 电视剧 · 综艺",
                color = Ink.TextFaint,
                fontSize = Txt.Caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ==================== 右下：设置入口（只要一个按钮） ====================

@Composable
private fun ShortDramaEntry(
    modifier: Modifier,
    onEnter: () -> Unit,
    manager: com.chinut.bawantv.ui.theme.TvFocusManager?,
) {
    val focus = rememberTvFocusState()

    Row(
        modifier
            .frostedGlass(shape = RoundedCornerShape(Dim.BigRadius))
            .shadow(
                elevation = if (focus.focused) 26.sdp else 8.sdp,
                shape = RoundedCornerShape(Dim.BigRadius),
                spotColor = Ink.Pink.copy(alpha = if (focus.focused) 0.65f else 0.16f),
            )
            .focusBorder(
                visible = focus.focused,
                cornerRadius = Dim.BigRadius,
                color = Ink.Pink,
                width = 4.sdp,
            )
            .tvFocusable(
                focusState = focus,
                shape = RoundedCornerShape(Dim.BigRadius),
                focusedScale = 1.0f,
                glow = false,
                borderWidth = 0.dp,
                baseBackground = Color.Transparent,
                focusedBackground = Color.Transparent,
                onClick = onEnter,
            )
            .padding(horizontal = 24.sdp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(52.sdp)
                .background(
                    Brush.linearGradient(
                        listOf(Ink.Pink.copy(alpha = 0.34f), Ink.Pink.copy(alpha = 0.12f))
                    ),
                    RoundedCornerShape(16.sdp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.SmartDisplay,
                contentDescription = null,
                tint = Ink.Pink,
                modifier = Modifier.size(26.sdp),
            )
        }
        Spacer(Modifier.width(18.sdp))
        Column(Modifier.weight(1f)) {
            Text(
                "短剧",
                color = Color.White,
                fontSize = Txt.Section,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "竖屏短剧 · 一集接一集自动播",
                color = Ink.TextFaint,
                fontSize = Txt.Caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SettingsEntry(
    modifier: Modifier,
    onEnter: () -> Unit,
    manager: com.chinut.bawantv.ui.theme.TvFocusManager?,
) {
    val focus = rememberTvFocusState()

    Row(
        modifier
            .frostedGlass(shape = RoundedCornerShape(Dim.BigRadius))
            .shadow(
                elevation = if (focus.focused) 26.sdp else 8.sdp,
                shape = RoundedCornerShape(Dim.BigRadius),
                spotColor = Ink.Green.copy(alpha = if (focus.focused) 0.65f else 0.16f),
            )
            .focusBorder(
                visible = focus.focused,
                cornerRadius = Dim.BigRadius,
                color = Ink.Green,
                width = 4.sdp,
            )
            .tvFocusable(
                focusState = focus,
                shape = RoundedCornerShape(Dim.BigRadius),
                focusedScale = 1.0f,
                glow = false,
                borderWidth = 0.dp,
                baseBackground = Color.Transparent,
                focusedBackground = Color.Transparent,
                onClick = onEnter,
                // 同上：不要指向不存在的 "nav:home"，交给几何导航
            )
            .padding(horizontal = 24.sdp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(52.sdp)
                .background(
                    Brush.linearGradient(
                        listOf(Ink.Green.copy(alpha = 0.34f), Ink.Green.copy(alpha = 0.12f))
                    ),
                    RoundedCornerShape(16.sdp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Settings,
                contentDescription = null,
                tint = Ink.Green,
                modifier = Modifier.size(26.sdp),
            )
        }
        Spacer(Modifier.width(16.sdp))
        // 按用户要求：不要详细描述文字，只留名字
        Text(
            "设置",
            color = Color.White,
            fontSize = 26.ssp,
            fontWeight = FontWeight.Bold,
        )
    }
}
