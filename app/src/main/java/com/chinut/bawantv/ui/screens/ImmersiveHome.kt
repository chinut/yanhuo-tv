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
import com.chinut.bawantv.ui.theme.glassCard
import com.chinut.bawantv.ui.theme.focusRing
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

    // ---------- 背景：电影感自然风光 ----------
    //
    // 换掉原来的 `AmbientBackdrop`（4 团模糊色斑 + 无限漂移动画）。
    // 调研报告把那种做法列为 P1 反模式：紫蓝网格渐变是公认的"AI 味"标志，
    // 而且 Google 明文要求"不要调整用户不直接交互的背景元素"。
    //
    // 新背景是**静态**渐变，配色取自用户给的 5 张电影感风光参考图
    // （逐张取样：暗调 V≈0.3、低饱和 S≈0.3、最亮处在上中 22%）。
    // 详见 [com.chinut.bawantv.ui.theme.CinematicBackdrop]。
    com.chinut.bawantv.ui.theme.CinematicBackdrop(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                // 左右留白：批准稿是 127px 对称。
                //
                // 原来用 `Dim.SafeH * 0.7f`（48×0.7=33.6dp）再乘缩放系数，
                // 实测左边距偏小、右边卡还顶出了屏幕。
                // 这里给**明确**的横向边距，和纵向分开写，避免再被系数带偏。
                // 标定结果：120.sdp → 左边距 244px，即 **1.sdp ≈ 2px**。
                // 批准稿的边距是 127px，取 48.sdp ≈ 96px
                // （比批准稿略小，但换来更高的卡片 —— 卡片高度是更重要的观感）
                .padding(start = 48.sdp, end = 48.sdp, top = 8.sdp, bottom = 10.sdp),
        ) {
            // ---------- 品牌头：Logo + 文字 ----------
            BrandHeader(onOpenSettings = onOpenSettings, manager = manager)

            // ⚠️ 这里**不要**再插 Spacer。
            //
            // 批准稿里三条卡片占 996px（含头部所在的区域），
            // 头部是**叠在留白上**的。原来头部(115dp) + Spacer(18dp) 一共占了 266px，
            // 卡片只剩 821px 高 → 比批准稿矮 175px，整屏看起来"扁"。
            //
            // 收紧成：头部 52dp + 上下留白 27dp，
            // 卡片拿到约 996px，和批准稿一致。
            Spacer(Modifier.height(2.sdp))

            // ---------- 三条并排：1 大 + 2 窄 ----------
            //
            // 用户批准的布局（比例来自参考图，且**经 Google 官方网格校验通过**）：
            //   大卡 842px ÷ 2 = 421dp  ≈ 官方"2 卡片宽 412dp"
            //   边距 127px ÷ 2 = 63.5dp ≈ 官方建议 58–64dp
            //
            // ⚠️ 这个比例是**对的，别改**。调研报告的结论原文：
            //    「你的布局是可测量地正确的 —— 不要让人"修"它」
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(16.sdp),
            ) {
            // ---------- 左：直播画面，占绝大部分 ----------
            LiveHero(
                channel = liveChannel,
                focusKey = entryKey,
                modifier = Modifier.weight(2.216f).fillMaxHeight(),
                onEnter = {
                    liveChannel?.let { onOpenLive(it, playlist.ifEmpty { listOf(it) }) }
                },
            )

            // ---------- 中：影视 ----------
            //
            // ⚠️ 这里原来是一个 `Column { 影视 + 短剧 }` —— 两张卡**上下堆叠**。
            // 那是错的：批准稿是**三条并排等高**（1 大 + 2 窄竖条）。
            //
            // 堆叠的后果不只是"看起来不一样"，而是丢掉了**竖版**这个核心诉求：
            // 竖条比例 380/747 = 0.51（接近海报本身 2:3），
            // 所以海报能完整显示、人脸不切；
            // 而上下堆叠后每张只有约一半高度（~740×420 = 横条 1.76），
            // 又回到"海报被拦腰截断"的老问题。
            MovieEntry(
                movies = movies,
                // ⚠️ 用 **weight 表达比例**，不要写死 dp。
                //
                // 踩了两次同一个坑：
                //   · 写 `380.sdp` → 太宽，三条溢出屏幕，大卡被挤成窄条
                //   · 改 `190.sdp` → 又不对，因为 `.sdp` 会乘一个**缩放系数**
                //     （`scale = min(w/960, h/540)`，实测约 1.9），
                //     写死的 dp 值在不同机器上表现不一样
                //
                // 批准稿的比例是 大卡 : 窄卡 = 842 : 380 ≈ 2.216 : 1。
                // 用 weight 表达这个比值，跟缩放系数无关，任何分辨率都成立。
                modifier = Modifier.weight(1f).fillMaxHeight(),
                onEnter = onOpenMovieWall,
                manager = manager,
            )

            // ---------- 右：短剧 ----------
            ShortDramaEntry(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                onEnter = onOpenShortDrama,
                manager = manager,
            )
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
        Modifier.fillMaxWidth().height(r.dp(52)),
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

    // 大卡圆角 48dp —— Material 的 `CornerExtraLarge` 是 28dp，
    // 在 density 2.0 的 1080p 电视上就是 56px；这里取 48dp 略收一点更稳。
    val heroShape = RoundedCornerShape(48.dp)

    Box(
        modifier
            .glassCard(shape = heroShape, focused = focus.focused)
            // 选中框：**白色实边 + 黑色辉光**，这是 Google TV 公布的焦点默认值。
            // 之前是品牌蓝光晕 + 4dp 彩边 —— 在蓝色系海报上会"消失"，
            // 用户反复强调过焦点必须一眼看得见（家里有老人）。
            .focusRing(focused = focus.focused, shape = heroShape)
            .tvFocusable(
                focusState = focus,
                focusKey = focusKey,
                shape = heroShape,
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
                // 裁切圆角必须和外壳的 48dp 对齐，否则播放器会顶出圆角
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(48.dp)),
            )
        } else {
            Box(Modifier.fillMaxSize().background(Color.Black))
        }

        // ---------- 底部遮罩：保证台标文字压得住画面 ----------
        //
        // ## 为什么原来会"露字"
        //
        // 原来是 210dp 高、`[全透 → 30% → 82%]` 三段渐变，
        // **到卡片最底部也只有 82% 不透明** —— 而直播流自己带的字幕
        // 正好在画面下缘，于是从遮罩下面透出来，和我们的
        // 「正在直播 / 频道名 / 提示」叠在一起（用户反馈）。
        //
        // ## 改法：让渐变在**文字区上方**就到达全不透明
        //
        //     y=0%    全透（和上面的画面无缝接）
        //     y=18%   已很暗
        //     y=55%   全黑 ─┐
        //     y=100%  全黑 ─┘ 这一段整个盖住文字块
        //
        // 文字块（`.padding(26.sdp)`）的顶端大约在横幅的 12% 处，
        // 所以 55% 才开始全黑**足够** —— 而且留了余量：
        // 即使卡片再矮一点、文字相对位置再靠上，也还在全黑区里。
        //
        // 为什么不干脆做成"上方透明、下方纯色"两块：
        // 那样交界处会有一条**硬边**，在电视上很显眼。
        // 渐变到全黑既盖得严实，又没有硬边。
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(240.sdp)
                .background(
                    Brush.verticalGradient(
                        0.00f to Color.Transparent,
                        0.18f to Color.Black.copy(alpha = 0.45f),
                        0.40f to Color.Black.copy(alpha = 0.85f),
                        0.55f to Color.Black,
                        1.00f to Color.Black,
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
                // 首页预览用 **PlayerView（SurfaceView）**。
                //
                // # ⚠️ 这里有一个**实测出来的取舍**，别再"优化"回去
                //
                // 我曾经为了「首页能远程截图」把它改成 `TextureView`，
                // 因为 `SurfaceView` 是独立硬件层、`screencap` 抓不到。
                // 结果用户反馈**花屏**，A/B 实测（大卡区平均色 + 绿偏）：
                //
                //     TextureView  [3.9, 55.7, 3.8]   绿偏 +51.8   ← 花屏
                //     SurfaceView  [3.9,  3.8, 3.8]   绿偏  -0.0   ← 正常
                //
                // `TextureView` 在这台 Amlogic 电视上渲染绿屏。截图再方便，
                // 也不能拿"画面是坏的"去换。
                //
                // ## 代价：首页截不到图（已知且接受）
                //
                //    首页（有 SurfaceView 视频）  screencap → 8159 字节空帧（稳定）
                //    影视页（无视频）            screencap → 2.4 MB 正常
                //    全屏直播页                  screencap → 正常（它有别的合成路径）
                //
                // 所以**首页的远程截图能力是没有的**。要在首页看画面，
                // 用 `adb shell screenrecord`（它能抓到 SurfaceView 层）。
                //
                // 对电视 App 来说「画面正确」优先级远高于「我能远程截图」，
                // 所以这个取舍是明确的：**要画面，不要截图**。
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

    // 窄卡圆角 40dp（大卡是 48dp —— 官方说圆角**不随宽度等比缩放**，
    // 所以两者相近而不是成比例）
    val cardShape = RoundedCornerShape(40.dp)

    Box(
        modifier
            .glassCard(shape = cardShape, focused = focus.focused)
            .focusRing(focused = focus.focused, shape = cardShape)
            .tvFocusable(
                focusState = focus,
                focusKey = focusKey,
                shape = cardShape,
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
            // 每 3 分钟换一张。
            //
            // 用户反馈「海报切换太频繁了 几分钟切一次就行」——
            // 原来 11 秒一次，看电视时余光一直有东西在动，很吵。
            //
            // ⚠️ key 里带 posters.size：列表后到时重新起算，
            // 否则 index 可能越界。
            //
            // 起点**随机**：不然每次开机都从同一张开始，
            // 而 3 分钟才换一张，"每次开机看到的都是这张"会更明显。
            var idx by remember(posters.size) {
                mutableIntStateOf(if (posters.isEmpty()) 0 else (0 until posters.size).random())
            }
            LaunchedEffect(posters.size) {
                if (posters.size <= 1) return@LaunchedEffect
                while (true) {
                    kotlinx.coroutines.delay(3 * 60_000L)
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
                        // 取景偏上图：竖版海报塞进横条卡片，人脸在上三分之一，
                        // 取中间那条会把脸切掉（用户反馈过"只剩脖子和身子"）
                        alignment = androidx.compose.ui.BiasAlignment(0f, -0.36f),
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = aAlpha
                                // 轻微放大即可：Crop 本身已经放大过一次，
                                // 再乘大倍数会把窗口推出人脸区
                                scaleX = 1.15f
                                scaleY = 1.15f
                            },
                    )
                }
                // 新图（正在淡入）
                if (layerB.isNotBlank() && bAlpha > 0.01f) {
                    AsyncImage(
                        model = layerB,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alignment = androidx.compose.ui.BiasAlignment(0f, -0.36f),
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = bAlpha
                                scaleX = 1.15f
                                scaleY = 1.15f
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
