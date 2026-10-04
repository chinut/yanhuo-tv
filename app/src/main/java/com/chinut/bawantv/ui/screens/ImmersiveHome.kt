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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
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
/** 右上角齿轮（设置入口）。 */
private const val KEY_GEAR = "home:gear"
private const val KEY_DRAMA = "home:drama"
private const val KEY_SETTINGS = "home:settings"

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

    // 配置指纹：老电视模式 / 主源 / 自定义源地址 变了就重新预热。
    //
    // ⚠️ 原来这里是 `LaunchedEffect(Unit)` —— 只在首次组合时跑一次，
    // **之后永不重跑**。所以用户在设置里换了主源，频道列表一动不动
    // （用户实测：「切换下面的源……但实际上他什么都没有影响」）。
    //
    // `revision` 是 AppPrefs 里每次改设置都会 +1 的 StateFlow：
    // 读它能让这个 Composable 在配置变化时重组。
    // 再配合 configKey，就只在**真的影响直播源**时才重拉
    // （改音量/HUD 不会白重拉一次频道表）。
    //
    // 手机二维码调试页保存走的是 `AppPrefs.applyRemote()`，
    // 它结尾同样调 `touch()` → revision 变化 → 这里同样会重拉。
    val prefsRev by prefs.revision.collectAsState()
    val liveConfigKey = remember(prefsRev) {
        com.chinut.bawantv.ui.HomeWarmup.configKey()
    }

    LaunchedEffect(liveConfigKey) {
        // 配置变了 → 先作废旧结果，再重新预热。
        // （warmUp 内部也会自查 warmKey，这里是双保险。）
        com.chinut.bawantv.ui.HomeWarmup.reset()
        runCatching { com.chinut.bawantv.ui.HomeWarmup.warmUp(context) }

        // 开屏期间已经预热过了，直接用结果（省掉几百毫秒的解析）。
        // 万一没预热到（例如直接跳过了开屏），这里自己兜底算一次。
        val warmedGroups = com.chinut.bawantv.ui.HomeWarmup.groups()
        val warmedAll = com.chinut.bawantv.ui.HomeWarmup.allChannels()

        val all: List<LiveChannel> = warmedAll ?: run {
            // 兜底也要**走主源设置**，不能用内置表 ——
            // 否则开了老电视模式却没有预热时，首页又退回央视网（用户反馈过）。
            val groups = warmedGroups
                ?: runCatching {
                    LiveCatalog.load(
                        context = context,
                        customSourceUrl = prefs.liveSourceUrl,
                        preset = com.chinut.bawantv.live.LivePreset.of(prefs.livePreset),
                        oldTvMode = prefs.oldTvMode,
                    )
                }.getOrDefault(emptyList())
            // 跨分组去重的整表：点开后上下键能一路换到任何台。
            // 央视置顶 + 按频道号排序，见 LiveCatalog.flattenForZapping。
            LiveCatalog.flattenForZapping(groups)
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
            BrandHeader(onOpenSettings = onOpenSettings, manager = manager)

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

            // ---------- 右：影视 + 短剧（各占一半） ----------
            //
            // 设置块已搬到右上角齿轮（用户要求）。
            // 空出来的地方给影视和短剧，两块各占一半、都比原来大。
            Column(
                Modifier
                    .width(430.sdp)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(16.sdp),
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
private fun BrandHeader(
    onOpenSettings: () -> Unit,
    manager: com.chinut.bawantv.ui.theme.TvFocusManager?,
) {
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

        // 把右侧的「日期时间 + 齿轮」推到最右
        Spacer(Modifier.weight(1f))

        ClockText()
        Spacer(Modifier.width(r.dp(18)))
        GearButton(onOpenSettings, manager)
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

// ==================== 首页右侧三块（统一样式） ====================

/**
 * 右上角的日期 + 24 小时时间。
 *
 * 用户要求：「在右上角加入一个年月日 24小时时间」。
 *
 * 每分钟刷一次就够（秒级刷新会让整个首页每秒重组一次，
 * 老电视上纯属浪费）。用 `HH:mm` 强制 24 小时制，
 * **不受系统 12/24 小时设置影响** —— 用户要的就是 24 小时。
 */
@Composable
private fun ClockText() {
    val r = com.chinut.bawantv.ui.theme.LocalResponsive.current
    // 用一个会自己走的 state，避免整页每秒重组
    var now by remember { mutableStateOf(java.util.Calendar.getInstance()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = java.util.Calendar.getInstance()
            // 对齐到下一分钟，秒数不动
            kotlinx.coroutines.delay(20_000L)
        }
    }
    val dfD = remember { java.text.SimpleDateFormat("yyyy年M月d日", java.util.Locale.CHINA) }
    val dfT = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            dfD.format(now.time),
            color = Ink.TextTertiary,
            fontSize = r.sp(14),
        )
        Spacer(Modifier.width(r.dp(10)))
        Text(
            dfT.format(now.time),
            color = Color.White,
            fontSize = r.sp(22),
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * 右上角齿轮：设置入口。
 *
 * 用户要求把原来的设置大块去掉、换成一个齿轮放右上角。
 *
 * **遥控器可达性**：它仍然是可聚焦项（`tvFocusable`），
 * 并且和下面的影视块显式钉死上下关系（见 [MovieEntry] 的 onUp），
 * 否则设置就点不进去了。
 */
@Composable
private fun GearButton(
    onEnter: () -> Unit,
    manager: com.chinut.bawantv.ui.theme.TvFocusManager?,
) {
    val r = com.chinut.bawantv.ui.theme.LocalResponsive.current
    val focus = rememberTvFocusState()
    Box(
        Modifier
            .size(r.dp(46))
            .clip(RoundedCornerShape(r.dp(14)))
            .tvFocusable(
                focusState = focus,
                focusKey = KEY_GEAR,
                shape = RoundedCornerShape(r.dp(14)),
                focusedScale = 1.08f,
                borderWidth = 3.dp,
                baseBackground = Ink.Card,
                focusedBackground = Ink.AccentSoft,
                onClick = onEnter,
                // 齿轮下面就是影视块，显式钉死更稳
                onDown = { manager?.moveTo(KEY_MOVIE); true },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Settings,
            contentDescription = "设置",
            tint = if (focus.focused) Color.White else Ink.TextSecondary,
            modifier = Modifier.size(r.dp(24)),
        )
    }
}

/**
 * 首页入口卡的统一骨架。
 *
 * ## 为什么抽出来
 *
 * 原来影视 / 短剧 / 设置三块的 `Row + frostedGlass + shadow + focusBorder +
 * tvFocusable` 几乎逐字重复了三遍（约 240 行）。改一次要改三处，
 * 必然漏。现在合成一份。
 *
 * ## 视觉设计（用户要求"看起来像个影视 app"）
 *
 * 关键是**用内容填满**，而不是三个图标：
 *
 *     ┌──────────────────────────────┐
 *     │  ▓▓ ▓▓ ▓▓ ▓▓ ▓▓ ▓▓          │  ← 一排内容海报
 *     │  ░░░░░░░░░░░░░░░░░░░░░░░░░░  │  ← 从下往上渐变压暗
 *     │  [▣]  影视                   │  ← 图标徽章 + 文字压在下面
 *     │       影视库 240 部          │
 *     └──────────────────────────────┘
 *
 * 海报取不到时（首次安装 + 断网）自动退回纯渐变，不会露出空白。
 *
 * @param posters 铺背景的海报地址；空则用 [fallbackBrush] 兜底
 * @param accent  这块的主色（边框 / 阴影 / 图标）
 */
@Composable
private fun HomeEntryCard(
    modifier: Modifier,
    accent: Color,
    fallbackBrush: Brush,
    posters: List<String>,
    focusKey: String,
    /** 上键跳转目标（不填就走几何导航）。 */
    onUpKey: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String?,
    titleSize: androidx.compose.ui.unit.TextUnit,
    manager: com.chinut.bawantv.ui.theme.TvFocusManager?,
    onEnter: () -> Unit,
) {
    val focus = rememberTvFocusState()

    Box(
        modifier
            .clip(RoundedCornerShape(Dim.BigRadius))
            .frostedGlass(shape = RoundedCornerShape(Dim.BigRadius))
            .shadow(
                elevation = if (focus.focused) 28.sdp else 8.sdp,
                shape = RoundedCornerShape(Dim.BigRadius),
                spotColor = accent.copy(alpha = if (focus.focused) 0.70f else 0.16f),
            )
            .focusBorder(
                visible = focus.focused,
                cornerRadius = Dim.BigRadius,
                color = accent,
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
                onUp = onUpKey?.let { k -> { manager?.moveTo(k); true } },
            ),
    ) {
        // ---------- 背景：单张大海报轮播 或 渐变兜底 ----------
        Box(Modifier.matchParentSize().background(fallbackBrush))

        if (posters.isNotEmpty()) {
            // 每 11 秒换一张（淡入本身占 1.1 秒，间隔太短会显得一直在动）。
            //
            // ⚠️ key 里带 posters.size：列表后到时重新起算，
            // 否则 index 可能越界。
            var idx by remember(posters.size) { mutableIntStateOf(0) }
            LaunchedEffect(posters.size) {
                if (posters.size <= 1) return@LaunchedEffect
                while (true) {
                    kotlinx.coroutines.delay(11_000L)
                    idx = (idx + 1) % posters.size
                }
            }
            val url = posters.getOrNull(idx) ?: posters.first()

            // ---------- 交叉淡入 ----------
            //
            // ⚠️ 不能用 Crossfade：它内容 lambda 里的 `matchParentSize()`
            // 拿不到尺寸，实测海报完全不显示（只剩渐变）。
            // 所以自己搭：两个槽位交替 —— 同一时刻只挂两张图，
            // 比"每张都挂一遍"省内存，老电视上这点很实在。
            //
            // 上一版为了修显示问题把淡入也丢了 → 变成硬切（用户反馈
            // 「切换过于生硬了吧同志」）。这里补回来。
            val slotA = remember(posters.size) { mutableStateOf(true) }
            var layerA by remember(posters.size) { mutableStateOf(url) }
            var layerB by remember(posters.size) { mutableStateOf("") }

            LaunchedEffect(url) {
                if (layerA == url) return@LaunchedEffect
                // 写进"当前不可见"的槽位，再把可见位切过去 → 触发淡入
                if (slotA.value) layerB = url else layerA = url
                slotA.value = !slotA.value
            }

            val aAlpha by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (slotA.value) 1f else 0f,
                animationSpec = androidx.compose.animation.core.tween(1_100),
                label = "posterA",
            )
            val bAlpha by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (slotA.value) 0f else 1f,
                animationSpec = androidx.compose.animation.core.tween(1_100),
                label = "posterB",
            )

            Box(Modifier.matchParentSize()) {
                // 旧图（正在淡出）
                if (layerA.isNotBlank() && aAlpha > 0.01f) {
                    AsyncImage(
                        model = layerA,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = aAlpha
                                scaleX = 1.28f
                                scaleY = 1.28f
                                translationX = -46f
                                translationY = 30f
                            },
                    )
                }
                // 新图（正在淡入）
                if (layerB.isNotBlank() && bAlpha > 0.01f) {
                    AsyncImage(
                        model = layerB,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = bAlpha
                                scaleX = 1.28f
                                scaleY = 1.28f
                                translationX = -46f
                                translationY = 30f
                            },
                    )
                }
            }
            // 从下往上渐变压暗，保证文字在任何海报上都读得清
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        // 只在下半部分压暗（文字在下面），上半部分让海报露出来。
                        // 原来整块都压到 0.48~0.62，海报基本看不见 = 白铺了。
                        Brush.verticalGradient(
                            0.0f to Color.Black.copy(alpha = 0.10f),
                            0.40f to Color.Black.copy(alpha = 0.30f),
                            0.72f to Color.Black.copy(alpha = 0.72f),
                            1.0f to Color.Black.copy(alpha = 0.88f),
                        )
                    )
            )
        }

        // ---------- 前景：图标 + 文字 ----------
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 20.sdp, vertical = 16.sdp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(42.sdp)
                    .background(
                        // 半透明玻璃徽章 —— 比原来实心大色块含蓄，不抢海报
                        Brush.linearGradient(
                            listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0.22f)),
                        ),
                        RoundedCornerShape(13.sdp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.sdp),
                )
            }
            Spacer(Modifier.width(14.sdp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = Color.White,
                    fontSize = titleSize,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = Txt.Caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 影视入口：用片库海报铺背景。 */
@Composable
private fun MovieEntry(
    movies: List<UnifiedMovie>,
    modifier: Modifier,
    onEnter: () -> Unit,
    manager: com.chinut.bawantv.ui.theme.TvFocusManager?,
) {
    HomeEntryCard(
        modifier = modifier,
        accent = Ink.Pink,
        fallbackBrush = Brush.linearGradient(
            listOf(Ink.Pink.copy(alpha = 0.34f), Color.Transparent),
        ),
        posters = remember(movies) {
            movies.asSequence().map { it.poster }
                .filter { it.isNotBlank() }.take(6).toList()
        },
        focusKey = KEY_MOVIE,
        // 上键去右上角齿轮（设置入口）—— 几何导航也能到，
        // 但显式钉死更稳，避免"设置怎么也选不中"
        onUpKey = KEY_GEAR,
        icon = Icons.Default.Movie,
        title = "影视",
        subtitle = if (movies.isNotEmpty()) "影视库 ${movies.size} 部"
        else "电影 · 剧集 · 综艺",
        titleSize = Txt.Section,
        manager = manager,
        onEnter = onEnter,
    )
}

/** 短剧入口：用短剧海报铺背景。 */
@Composable
private fun ShortDramaEntry(
    modifier: Modifier,
    onEnter: () -> Unit,
    manager: com.chinut.bawantv.ui.theme.TvFocusManager?,
) {
    // 海报来自短剧的本地缓存（QimaoSource 落盘那份），不联网。
    //
    // ⚠️ 不能用 `remember { ... }` 只取一次：短剧列表是**开屏预热**
    // 时异步拉的，首页首次组合时缓存往往还没就绪 → 海报永远空。
    // 所以用 state + 轮询，拉到海报后自己刷新（最多试 12 次 ≈ 12 秒）。
    var posters by remember {
        mutableStateOf(
            runCatching { com.chinut.bawantv.unified.QimaoSource.posters(6) }
                .getOrDefault(emptyList()),
        )
    }
    LaunchedEffect(Unit) {
        // 自己没有就去拉一次（会落盘）—— 不依赖开屏预热那条链路，
        // 实测那条链路不可靠（没有 BawanWarmup 日志）。
        if (posters.isEmpty()) {
            runCatching { com.chinut.bawantv.unified.QimaoSource.refresh() }
            posters = runCatching { com.chinut.bawantv.unified.QimaoSource.posters(6) }
                .getOrDefault(emptyList())
        }
        // 再兜几秒（拉取慢时）
        var tries = 0
        while (posters.isEmpty() && tries < 15) {
            kotlinx.coroutines.delay(1_000)
            tries++
            posters = runCatching { com.chinut.bawantv.unified.QimaoSource.posters(6) }
                .getOrDefault(emptyList())
        }
    }
    HomeEntryCard(
        modifier = modifier,
        accent = Ink.Pink,
        fallbackBrush = Brush.linearGradient(
            listOf(Ink.Pink.copy(alpha = 0.34f), Color.Transparent),
        ),
        posters = posters,
        focusKey = KEY_DRAMA,
        icon = Icons.Default.SmartDisplay,
        title = "短剧",
        subtitle = "竖屏短剧 · 自动连播",
        titleSize = Txt.Section,
        manager = manager,
        onEnter = onEnter,
    )
}
