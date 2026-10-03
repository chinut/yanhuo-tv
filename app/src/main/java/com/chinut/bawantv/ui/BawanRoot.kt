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
fun BawanRoot(
    focusManager: TvFocusManager,
    /** 首页再按返回时回调（由 Activity 执行 finish 退出）。 */
    onExitRequested: (() -> Unit)? = null,
) {
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

    /** 调试用：进直播后是否自动起播第一个频道。 */
    var debugLivePlay by remember { mutableStateOf(false) }

    /** 调试用：进详情后是否自动起播第一集。 */
    var debugAutoPlay by remember { mutableStateOf(false) }

    /** 调试用：广播指定要自动播放的影片序号。 */
    var debugPlayIndex by remember { mutableIntStateOf(0) }

    /** 调试用：每次收到 vod_play 广播就 +1，够 LaunchedEffect 感知到"又触发了一次"。 */
    var debugPlayTrigger by remember { mutableIntStateOf(0) }

    /**
     * 待展示的更新信息。
     *
     * 提升到顶层（而不是放在内容树里）的原因：
     *  · 内容树在播放时会被整个摘掉，放里面 remember 的位置会变、状态被重置
     *  · 调试广播 `route=update` 也要能写它 —— 否则更新框没法单独调出来验证
     */
    var pendingUpdate by remember { mutableStateOf<com.chinut.bawantv.core.UpdateInfo?>(null) }

    /**
     * 从首页「继续观看」直接恢复播放。
     *
     * 观看记录里存了整条线路的剧集地址，所以这里能就地拼出播放请求、
     * **不需要重新搜索或重新进详情页**，点一下就直接接着看。
     * 播放页自己会按记录里的集数/进度续播（见 ui/player/WatchProgress.kt）。
     */
    var resumeVod by remember { mutableStateOf<com.chinut.bawantv.unified.PlayRequest?>(null) }

    /**
     * 影视播放请求（板块 B / C 点剧集时上抛到这里）。
     *
     * 为什么放在主框架而不是详情页里：播放页要**铺满整屏**，而详情页画在影视板块的
     * 内容区里（外面还有左侧导航栏和 overscan 边距），套在里面只能是小窗口。
     * 而且**不能用 Popup/Dialog 来"逃出"布局** —— Popup 是独立 window，
     * 返回键会被送到那个窗口，BackHandler 收不到，表现就是"按返回退不出来"。
     * 所以统一由这里挂在根 Box 里渲染，和直播播放页同一套做法。
     */
    var playVod by remember { mutableStateOf<com.chinut.bawantv.unified.PlayRequest?>(null) }

    /**
     * 影视板块当前是否停在「某一部片子的详情页」。
     *
     * 由 UnifiedVideoScreen 通过 onDetailChanged 上报。
     * 用途：让返回键在详情页先退回影视列表，而不是一路跳回首页。
     */
    var inVodDetail by remember { mutableStateOf(false) }

    /**
     * 影视当前正在看详情的作品。
     *
     * **放在根层而不是 UnifiedVideoScreen 里**，因为播放时本文件会把内容树
     * 整个摘掉（见下面那个 `if (livePlaying == null && ...)`）——
     * 树一摘，子组件里的 remember 全丢。之前的表现就是：
     * 从播放页返回，用户被扔回列表顶部，而不是回到刚才那部片的详情。
     */
    var vodDetail by remember { mutableStateOf<com.chinut.bawantv.unified.UnifiedMovie?>(null) }

    /**
     * 影视列表的滚动位置（首个可见项下标 + 偏移）。
     *
     * 同样是为了"从播放/详情返回后不要跳回最上面"。
     * 用户反馈："每次下滚一段时候返回一下就又到最上面了，这个很不合理"。
     */
    var vodGridIndex by remember { mutableIntStateOf(0) }
    var vodGridOffset by remember { mutableIntStateOf(0) }

    /** 返回影视列表时要恢复焦点的影片 id（空表示不恢复）。 */
    var vodFocusMovieId by remember { mutableStateOf("") }

    /** 影视板块每次重新挂载时用来恢复滚动位置的令牌。 */
    var vodRestoreToken by remember { mutableIntStateOf(0) }

    // 返回键：播放中先退出播放；否则回到首页（不要直接退出 App，
    // 否则在电视上误按一次返回就掉出应用，体验很差）
    BackHandler(enabled = true) {
        when {
            livePlaying != null -> {
                livePlaying = null
                livePlaylist = emptyList()
                focusEpoch++
            }

            playVod != null -> playVod = null
            resumeVod != null -> resumeVod = null

            // ---------- 影视详情页：先回影视列表，不要直接跳首页 ----------
            //
            // 用户反馈："在影视的单独标签页内点击返回应该是返回影视主界面"。
            //
            // 原来详情页的返回直接 section = Home，等于从"某部片子的详情"
            // 一路退到首页 —— 中间那层被跳过了，用户得重新进影视、重新找列表位置。
            // 现在先关掉详情，停在影视列表上。
            section == TopSection.Vod && inVodDetail -> inVodDetail = false

            section != TopSection.Home -> {
                section = TopSection.Home
                focusEpoch++
            }

            // 已经在首页了 → 退出 App。
            //
            // 之前这里是 `else -> Unit`，等于**把返回键吞掉**：
            // 用户在首页按返回没有任何反应，也就永远退不出 App（只能用遥控器的
            // HOME 键回桌面）。这在电视上尤其难受 —— 遥控器上最顺手的"退出"
            // 就是返回键。
            else -> onExitRequested?.invoke()
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

    // ---------- 调试：收到 vod_play 广播就自动播一部 ----------
    //
    // 走的是**真实业务路径**（读库 → 进详情 → 取源 → 起播），
    // 不是另写一条捷径，所以验证结果对真实使用是有意义的。
    androidx.compose.runtime.LaunchedEffect(debugPlayTrigger) {
        if (debugPlayTrigger == 0) return@LaunchedEffect
        val movies = runCatching {
            com.chinut.bawantv.unified.LibraryStore.load()
        }.getOrDefault(emptyList())
        val pick = movies.getOrNull(debugPlayIndex) ?: movies.firstOrNull()
        if (pick == null) {
            android.util.Log.w("BawanRoute", "vod_play：本地片库为空，无法自动播")
            return@LaunchedEffect
        }
        android.util.Log.i("BawanRoute", "vod_play：选中《${pick.title}》")
        pendingUnified = pick
    }

    // 上报当前界面给网络遥控（手机端 /api/remote/status 会读它）
    androidx.compose.runtime.LaunchedEffect(section) {
        com.chinut.bawantv.core.RemoteBus.setScreen(section.route)
    }

    // ---------- 调试用：广播跳转 ----------
    //
    // 为什么不用 `am start --es dsh_route ...`：
    // MainActivity 是 singleTop，Activity 已存在时 `am start` 会**复用实例**，
    // onCreate 不再执行 → extra 永远读不到（这就是"明明带了 --es，读出来是 null"）。
    //
    // 广播走 onNewIntent/动态注册，**每次都会送达**，所以自动化验证靠它定位界面。
    //
    //   adb shell am broadcast -a com.chinut.bawantv.DEBUG_ROUTE --es route vod
    androidx.compose.runtime.DisposableEffect(context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                val route = i?.getStringExtra("route").orEmpty()
                val target = when (route) {
                    "live" -> TopSection.Live
                    "vod", "vod_search" -> TopSection.Vod
                    "settings" -> TopSection.Settings
                    "home" -> TopSection.Home
                    else -> null
                }
                if (target != null) {
                    section = target
                    focusEpoch++
                    android.util.Log.i("BawanRoute", "广播跳转 → ${target.route}")
                } else if (route == "vod_play") {
                    // 调试捷径：直接进影视并**自动播第一部**。
                    //
                    // 为什么要这个：验证播放器的按键（暂停/拖动）时，
                    // 靠"分类栏 → 下 → 确定 → 下 → 确定"盲按非常不可靠，
                    // 经常落在搜索框或别的控件上。这个路由一步到位。
                    //
                    // 支持 --ei index N 指定第几部（默认 0）。
                    section = TopSection.Vod
                    debugPlayIndex = i?.getIntExtra("index", 0) ?: 0
                    debugPlayTrigger++
                    focusEpoch++
                    android.util.Log.i("BawanRoute", "广播跳转 → vod 自动播放 #$debugPlayIndex")
                } else if (route == "live_play") {
                    // 调试：直接进直播并**起播第一个频道**。
                    //
                    // 和 vod_autoplay 同理：验证播放页时要可靠抵达，
                    // 不能靠"下、下、确定"这种盲按（经常落错位置）。
                    section = TopSection.Live
                    debugLivePlay = true
                    debugPlayTrigger++
                    focusEpoch++
                    android.util.Log.i("BawanRoute", "广播跳转 → live 自动起播")
                } else if (route == "vod_autoplay") {
                    // 调试：进详情后**自动起播第一集**。
                    // 用来可靠地抵达播放页 —— 盲按方向键选剧集按钮经常失败，
                    // 而"播放页按键"正是要验证的东西。
                    section = TopSection.Vod
                    debugPlayIndex = i?.getIntExtra("index", 0) ?: 0
                    debugAutoPlay = true
                    debugPlayTrigger++
                    focusEpoch++
                    android.util.Log.i("BawanRoute", "广播跳转 → vod 自动起播 #$debugPlayIndex")
                } else if (route == "back") {
                    // 回到首页（方便脚本把状态复位）
                    section = TopSection.Home
                    focusEpoch++
                } else if (route == "update") {
                    // 调试：强制弹出更新框。
                    //
                    // 为什么要这条通道：更新框只在"真的有新版本且没被跳过"时出现，
                    // 否则根本调不出来 —— 而它里面那套遥控器交互（选按钮、下载、安装）
                    // 恰恰是最容易出问题、也最需要反复验证的地方。
                    // 靠"等下次发版碰运气"来测是不现实的。
                    //
                    // 指向的是一份**真实存在**的 APK，所以下载路径也能一并验证。
                    pendingUpdate = com.chinut.bawantv.core.UpdateInfo(
                        versionCode = 9999,
                        versionName = "0.0.0-debug",
                        notes = "这是调试用的假更新信息，用于验证更新框里的遥控器操作。",
                        apkSources = listOf(
                            com.chinut.bawantv.core.ApkSource(
                                name = "GitHub",
                                url = "https://github.com/chinut/yanhuo-tv/releases/download/" +
                                    "v1.0.25/yanhuo-tv-1.0.25.apk",
                                priority = 0,
                            ),
                        ),
                        apkSize = 20_910_536L,
                    )
                    android.util.Log.i("BawanRoute", "广播跳转 → 强制弹出更新框")
                }
            }
        }
        // 为什么必须是 **RECEIVER_EXPORTED**：
        //
        //  · Android 14 起强制显式声明 EXPORTED / NOT_EXPORTED，
        //    用裸 registerReceiver 会直接抛 SecurityException（踩过，App 秒崩）；
        //  · 这里要的又恰恰是 EXPORTED —— adb shell 的 UID 和应用不同，
        //    NOT_EXPORTED 会把脚本发来的广播丢掉，自动化就永远送不进来。
        //
        // 这个 action 只用于开发调试，且不加任何敏感数据，导出是可接受的。
        androidx.core.content.ContextCompat.registerReceiver(
            context,
            receiver,
            android.content.IntentFilter(DEBUG_ROUTE_ACTION),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    ProvideTvFocus(focusManager, focusScope) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(listOf(Ink.Deep, Ink.Base, Ink.Soft))
                )
        ) {
            // ---------- 播放中：把下面的界面整棵树摘掉 ----------
            //
            // 真机实测「直播卡成 PPT」，这是一大主因：
            // 直播播放器是**浮层**（见下方 livePlaying），而底下的首页/影视页
            // 之前一直在组合树里活着 —— 首页那个极光背景是
            // `rememberInfiniteTransition` 驱动的无限动画，
            // **每帧都在重组 + 重绘**，还有 Canvas 里 3 个大半径径向渐变。
            //
            // 电视 SoC 性能有限，一边解 1080p 视频一边全屏重绘渐变，
            // 直接就把 frame budget 吃光了。
            //
            // 现在播放一开始就把整棵树移除（`if` 直接不组合，不是隐藏），
            // 播放期间只保留播放器本身。
            if (livePlaying == null && resumeVod == null && playVod == null) {
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
                            onOpenShortDrama = {
                                section = TopSection.ShortDrama
                                focusEpoch++
                            },
                            onOpenSettings = {
                                section = TopSection.Settings
                                focusEpoch++
                            },
                        )

                            // ---------- 短剧 ----------
                            //
                            // 复用影视那套界面（海报墙 / 详情 / 剧集 / 播放），
                            // 只是数据源不同 —— 短剧和影视在交互上是同一件事，
                            // 没必要写两套 UI，也更省内存。
                            TopSection.ShortDrama -> com.chinut.bawantv.ui.screens
                                .ShortDramaScreen(
                                    entryKey = FocusKeys.entry(TopSection.ShortDrama.route),
                                )

                            TopSection.Live -> LiveScreen(
                                debugAutoPlayFirst = debugLivePlay,
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
                                    onDetailChanged = { inVodDetail = it },
                                    externalDetail = vodDetail,
                                    onExternalDetailChanged = {
                                        vodDetail = it
                                        inVodDetail = it != null
                                    },
                                    externalGridIndex = vodGridIndex,
                                    externalGridOffset = vodGridOffset,
                                    onExternalGridScroll = { i, o ->
                                        vodGridIndex = i
                                        vodGridOffset = o
                                    },
                                    externalFocusMovieId = vodFocusMovieId,
                                    onExternalFocusMovie = { vodFocusMovieId = it },
                                    debugAutoPlay = debugAutoPlay,
                                    onDebugAutoPlayConsumed = { debugAutoPlay = false },
                                onBack = {
                                    section = TopSection.Home
                                    focusEpoch++
                                },
                                onPlay = { movie, source, eps, i ->
                                    // 影视只保留低端影视，剧集地址是**直连 m3u8**，
                                    // 不需要任何解析接口 —— 拿到地址直接交给播放器。
                                    playVod = com.chinut.bawantv.unified.PlayRequest(
                                        episodes = eps.map {
                                            com.chinut.bawantv.unified.Episode(
                                                name = it.name,
                                                url = it.url,
                                            )
                                        },
                                        index = i,
                                        title = movie.title,
                                        vodId = movie.id,
                                        poster = movie.poster,
                                    )
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
            // 注意：这个状态和检查逻辑**必须放在「播放中不组合主界面」的 if 外面** ——
            // 放里面的话每次进出播放，`remember` 的位置就变了，状态会被重置，
            // 更新提示会反复弹或永远弹不出来。
            //
            // （pendingUpdate 本身已提升到本函数顶层，理由同上：调试广播也要能写它。）
            LaunchedEffect(Unit) {
                if (!prefs.autoCheckUpdate) return@LaunchedEffect
                delay(3000)
                val info = runCatching { com.chinut.bawantv.core.Updater.check(context) }.getOrNull()
                if (info != null && info.versionCode > prefs.skippedVersion) {
                    pendingUpdate = info
                }
            }

            // ---------- 更新提示弹窗 ----------
            // 放在被移除的那棵树**里面**：播放时不该弹更新框打断观看
            pendingUpdate?.let { info ->
                UpdateDialog(
                    info = info,
                    onDismiss = {
                        prefs.skippedVersion = info.versionCode
                        pendingUpdate = null
                    },
                )
            }
            }   // ← 关掉「播放中不组合主界面」的 if


            // ---------- 内存监控浮层（测试用） ----------
            //
            // 放在最上层，但它只是个 Box + 文字，**完全不参与焦点**
            // （详见 MemoryHud 注释：一旦可聚焦就会打乱几何导航的落点）。
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxSize(),
                contentAlignment = androidx.compose.ui.Alignment.TopEnd,
            ) {
                MemoryHud(enabled = prefs.showMemoryHud)
            }

            // ---------- 周期性内存日志 ----------
            //
            // 浮层只能看"此刻"，日志能事后拉出**完整时间线** ——
            // 这是定位"哪个操作把内存顶上去了"的关键。
            // 拉取： adb logcat -s BawanMem:I
            if (prefs.logMemory) {
                LaunchedEffect(Unit) {
                    while (true) {
                        runCatching {
                            val s = com.chinut.bawantv.core.MemProbe.sample(context)
                            android.util.Log.i("BawanMem", s.logLine("采样"))
                        }
                        kotlinx.coroutines.delay(com.chinut.bawantv.core.MemProbe.LOG_INTERVAL_MS)
                    }
                }
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

/**
 * 调试跳转广播的 action。
 *
 * 只用于自动化回归（开发和真机排查），不是给用户的功能。
 * 之所以用广播而不是 intent extra：MainActivity 是 singleTop，
 * Activity 已存在时 am start 会复用实例、onCreate 不再执行，
 * extra 读不到；广播每次都能送达。
 */
const val DEBUG_ROUTE_ACTION = "com.chinut.bawantv.DEBUG_ROUTE"
