package com.chinut.bawantv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chinut.bawantv.live.LiveChannel
import com.chinut.bawantv.live.LivePlayerScreen
import com.chinut.bawantv.ui.screens.LiveScreen
import com.chinut.bawantv.ui.screens.SettingsScreen
import com.chinut.bawantv.ui.screens.UnifiedVideoScreen
import com.chinut.bawantv.ui.screens.UpdateDialog
import com.chinut.bawantv.ui.theme.Dim
import com.chinut.bawantv.ui.theme.FocusKeys
import com.chinut.bawantv.ui.theme.Ink
import com.chinut.bawantv.ui.theme.ProvideTvFocus
import kotlinx.coroutines.launch
import com.chinut.bawantv.ui.theme.TvFocusScope
import com.chinut.bawantv.ui.theme.sdp
import com.chinut.bawantv.ui.theme.ssp
import com.chinut.bawantv.ui.theme.TvFocusManager
import com.chinut.bawantv.ui.theme.Txt
import com.chinut.bawantv.unified.UnifiedMovie
import com.chinut.bawantv.unified.UnifiedSource
import kotlinx.coroutines.delay

/**
 * App 主框架。
 *
 * 布局（TV 端固定横屏）：
 *   ┌──────────┬──────────────────────────────────────┐
 *   │ 左侧导航 │  内容区（各板块自己的界面）           │
 *   └──────────┴──────────────────────────────────────┘
 *
 * 焦点由 [TvFocusManager] 自绘管理（见该类的注释）：
 *  - 全局唯一实例由 MainActivity 创建并注入，因为按键要在 Activity 层截获
 *  - 导航栏用 `nav:<route>` 作为焦点 key，内容区用 `entry:<route>`
 *  - 切换板块后把焦点锚回导航栏当前项，用户再按「右」进内容区
 *
 * 直播播放是「全屏浮层」而不是新页面：老电视的体验是「一按就是台」。
 */
@Composable
fun BawanRoot(focusManager: TvFocusManager) {
    val context = LocalContext.current

    // 滚动作用域：给焦点导航当"兜底滚动"。没有它的话，
    // 懒加载列表里方向键找不到屏幕外的项就完全没反应（瀑布流滚不动就是这个原因）。
    val rootCoroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    val focusScope = remember(rootCoroutineScope) { TvFocusScope(rootCoroutineScope) }
    val scope = rootCoroutineScope

    // 调试入口：允许用 intent extra 直接落到某个板块，绕过遥控器按键。
    //   adb shell am start -n <pkg>/<activity> --es dsh_route vod
    // 之所以需要它：自动化回归时靠"盲按方向键"定位焦点非常不可靠
    // （经常落在搜索框或别的控件上），有这个就能精确到达任意界面。
    val debugRoute = (context as? android.app.Activity)
        ?.intent?.getStringExtra("dsh_route")
    android.util.Log.i("BawanRoute", "ctx=${context.javaClass.simpleName} route=$debugRoute")
    var section by remember {
        mutableStateOf(
            when (debugRoute) {
                "live" -> TopSection.Live
                "vod", "vod_search" -> TopSection.Vod
                "settings" -> TopSection.Settings
                else -> TopSection.Home
            }
        )
    }
    var livePlaying by remember { mutableStateOf<LiveChannel?>(null) }
    var livePlaylist by remember { mutableStateOf<List<LiveChannel>>(emptyList()) }
    var focusEpoch by remember { mutableIntStateOf(0) }

    /**
     * 从首页推荐墙直接点开的内容。
     *
     * 首页点一张海报时，需要「切到影视板块 + 立刻打开这部作品的详情」，
     * 所以把待打开的详情暂存在这里，由对应板块消费掉（消费后置空）。
     */
    /**
     * 影视板块（聚合后）待打开的作品。
     *
     * 首页点海报时把作品塞进来，切到影视板块后由统一界面直接开详情。
     */
    var pendingUnified by remember { mutableStateOf<com.chinut.bawantv.unified.UnifiedMovie?>(null) }

    /**
     * 从首页「继续观看」直接恢复播放。
     *
     * 观看记录里存了整条线路的剧集地址，所以这里能就地拼出播放请求、
     * **不需要重新搜索或重新进详情页**，点一下就直接接着看。
     * 播放页自己会按记录里的集数/进度续播（见 ui/player/WatchProgress.kt）。
     */
    var resumeVod by remember { mutableStateOf<com.chinut.bawantv.ui.screens.PlayingEpisode?>(null) }

    /**
     * 影视播放请求（板块 B / C 点剧集时上抛到这里）。
     *
     * 为什么放在主框架而不是详情页里：播放页要**铺满整屏**，而详情页画在影视板块的
     * 内容区里（外面还有左侧导航栏和 overscan 边距），套在里面只能是小窗口。
     * 而且**不能用 Popup/Dialog 来"逃出"布局** —— Popup 是独立 window，
     * 返回键会被送到那个窗口，BackHandler 收不到，表现就是"按返回退不出来"。
     * 所以统一由这里挂在根 Box 里渲染，和直播播放页同一套做法。
     */
    var playVod by remember { mutableStateOf<com.chinut.bawantv.ui.screens.PlayingEpisode?>(null) }
    /** 当前播放的作品（用于播放失败时自动换补充源）。 */
    var playMovie by remember { mutableStateOf<com.chinut.bawantv.unified.UnifiedMovie?>(null) }
    /** 防止自动换源递归触发。 */
    var autoSwitching by remember { mutableStateOf(false) }

    // 返回键：播放中先退出播放；否则回到首页（不要直接退出 App，
    // 否则在电视上误按一次返回就掉出应用，体验很差）
    BackHandler(enabled = true) {
        when {
            livePlaying != null -> {
                livePlaying = null
                livePlaylist = emptyList()
                focusEpoch++
            }

            section != TopSection.Home -> {
                section = TopSection.Home
                focusEpoch++
            }

            else -> Unit
        }
    }

    // 首页就不再"锚回导航栏"了 —— 已经没有导航栏，
    // 四个象限自己就是导航，焦点锚在左上角的直播块上即可。
    LaunchedEffect(section, focusEpoch) {
        repeat(8) { attempt ->
            val key = FocusKeys.entry(TopSection.Home.route)
            if (focusManager.keys.contains(key)) {
                focusManager.moveTo(key)
                return@LaunchedEffect
            }
            delay(50L * (attempt + 1))
        }
    }

    // 上报当前界面给网络遥控（手机端 /api/remote/status 会读它）
    androidx.compose.runtime.LaunchedEffect(section) {
        com.chinut.bawantv.core.RemoteBus.setScreen(section.route)
    }

    ProvideTvFocus(focusManager, focusScope) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(listOf(Ink.Deep, Ink.Base, Ink.Soft))
                )
        ) {
            // 隐藏状态标记：给自动化回归脚本读屏用
            Text(
                text = "MARK:" + section.route.uppercase(),
                color = androidx.compose.ui.graphics.Color.Transparent,
                fontSize = 1.ssp,
            )

            // 每个板块**直接占满整屏**（已去掉左侧导航栏）：
            // 电视上屏幕面积最宝贵，再挂一条竖栏属于重复 —— 四象限本身就是导航。
            Box(Modifier.fillMaxSize()) {
                Crossfade(
                    targetState = section,
                    animationSpec = tween(200),
                    label = "section",
                ) { target ->
                    when (target) {
                        TopSection.Home -> com.chinut.bawantv.ui.screens.ImmersiveHome(
                            entryKey = FocusKeys.entry(TopSection.Home.route),
                            // 左上的直播画面：确定键 → 进全屏直播（换台在那边）
                            onOpenLive = { ch, list ->
                                runCatching {
                                    com.chinut.bawantv.live.RecentLive.remember(ch.name, ch.url)
                                }
                                livePlaying = ch
                                livePlaylist = list
                            },
                            onOpenMovieWall = {
                                section = TopSection.Vod
                                focusEpoch++
                            },
                            onOpenSettings = {
                                section = TopSection.Settings
                                focusEpoch++
                            },
                        )

                            TopSection.Live -> LiveScreen(
                                entryKey = FocusKeys.entry(TopSection.Live.route),
                                onPlayingChanged = { ch, list ->
                                    livePlaying = ch
                                    livePlaylist = list
                                },
                                // 返回键回首页（首页就是那个大直播画面）
                                onBack = {
                                    section = TopSection.Home
                                    focusEpoch++
                                },
                            )

                            TopSection.Vod -> com.chinut.bawantv.ui.screens.UnifiedVideoScreen(
                                entryKey = FocusKeys.entry(TopSection.Vod.route),
                                pendingMovie = pendingUnified,
                                onPendingConsumed = { pendingUnified = null },
                                onBack = {
                                    section = TopSection.Home
                                    focusEpoch++
                                },
                                onPlay = { movie, source, eps, i ->
                                    // 把统一剧集映射成播放器认识的 VodEpisode。
                                    // flag 传**源标识**（ddys / siteKey），这样 ddys 走直连、
                                    // TVBox 站走 VodResolver 解析，两边都对。
                                    playVod = com.chinut.bawantv.ui.screens.PlayingEpisode(
                                        episodes = eps.map {
                                            com.chinut.bawantv.vod.VodEpisode(
                                                name = it.name,
                                                url = it.url,
                                            )
                                        },
                                        index = i,
                                        flag = source.id,
                                        title = movie.title,
                                        vodId = movie.id,
                                        poster = movie.poster,
                                        sourceLabel = source.name,
                                    )
                                    // 记下作品本身，供"播不了就自动换补充源"使用
                                    playMovie = movie
                                },
                                onPlaybackFailed = { failedFlag ->
                                    // ---------- 播放失败 → 自动切到下一个源 ----------
                                    //
                                    // 这是"低端影视为核心 + TVBox 补充"的最后一环：
                                    // 用户点了 ddys 那条线路，如果它播不了，不应该只是弹个
                                    // 错误让他自己去找别的源 —— 直接自动换成补充源继续放。
                                    val movie = playMovie ?: return@UnifiedVideoScreen
                                    if (autoSwitching) return@UnifiedVideoScreen
                                    autoSwitching = true
                                    scope.launch {
                                        val all = runCatching {
                                            com.chinut.bawantv.unified.MovieAggregator
                                                .buildPlayableSources(movie)
                                        }.getOrDefault(emptyList())
                                        // 挑一个"还没试过、且有剧集"的源
                                        val next = all.firstOrNull {
                                            it.id != failedFlag && it.episodes.isNotEmpty()
                                        }
                                        if (next != null) {
                                            playVod = com.chinut.bawantv.ui.screens.PlayingEpisode(
                                                episodes = next.episodes.map {
                                                    com.chinut.bawantv.vod.VodEpisode(
                                                        name = it.name,
                                                        url = it.url,
                                                    )
                                                },
                                                index = 0,
                                                flag = next.id,
                                                title = movie.title,
                                                vodId = movie.id,
                                                poster = movie.poster,
                                                sourceLabel = next.name,
                                            )
                                        }
                                        autoSwitching = false
                                    }
                                },
                            )

                            TopSection.Settings -> SettingsScreen(
                                entryKey = FocusKeys.entry(TopSection.Settings.route),
                            )
                        }
                    }
                }
            }

            // 启动后静默检查一次更新（3 秒后，避免和首屏抢网络）
            var pendingUpdate by remember { mutableStateOf<com.chinut.bawantv.core.UpdateInfo?>(null) }
            LaunchedEffect(Unit) {
                if (!prefs.autoCheckUpdate) return@LaunchedEffect
                delay(3000)
                val info = runCatching { com.chinut.bawantv.core.Updater.check(context) }.getOrNull()
                if (info != null && info.versionCode > prefs.skippedVersion) {
                    pendingUpdate = info
                }
            }

            pendingUpdate?.let { info ->
                UpdateDialog(
                    info = info,
                    onDismiss = {
                        prefs.skippedVersion = info.versionCode
                        pendingUpdate = null
                    },
                )
            }

            // ---------- 直播全屏播放浮层 ----------
            livePlaying?.let { channel ->
                Text(
                    text = "MARK:PLAYING",
                    color = androidx.compose.ui.graphics.Color.Transparent,
                    fontSize = 1.ssp,
                )
                LivePlayerScreen(
                    initialChannel = channel,
                    channels = livePlaylist,
                    onClose = {
                        livePlaying = null
                        livePlaylist = emptyList()
                        focusEpoch++
                    },
                )
            }

            // ---------- 从首页「继续观看」直接恢复的播放 ----------
            resumeVod?.let { req ->
                com.chinut.bawantv.ui.screens.VodPlayerScreen(
                    request = req,
                    onClose = { resumeVod = null },
                    onSwitchEpisode = { resumeVod = it },
                )
            }
            // ---------- 影视板块点剧集后的全屏播放 ----------
            playVod?.let { req ->
                com.chinut.bawantv.ui.screens.VodPlayerScreen(
                    request = req,
                    onClose = { playVod = null },
                    onSwitchEpisode = { playVod = it },
                )
            }
        }
    }

/** 板块加载态 */
@Composable
internal fun SectionLoading(text: String = "加载中…") {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Ink.Accent)
        Text(
            text,
            color = Ink.TextTertiary,
            fontSize = Txt.Caption,
            modifier = Modifier.padding(top = 76.sdp),
        )
    }
}

/** 板块空态 */
@Composable
internal fun SectionEmpty(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .clip(RoundedCornerShape(Dim.BigRadius))
                .background(Ink.Card)
                .padding(horizontal = 28.sdp, vertical = 22.sdp)
        ) {
            Text(
                text,
                color = Ink.TextTertiary,
                fontSize = Txt.Body,
                lineHeight = 26.ssp,
            )
        }
    }
}

/** 保留 FocusRequester 引用（部分屏幕仍在用 Compose 的焦点做局部行为） */
internal val unusedFocusRequester = FocusRequester::class
