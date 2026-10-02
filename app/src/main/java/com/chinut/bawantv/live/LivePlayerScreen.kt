package com.chinut.bawantv.live

import android.content.Context
import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.ui.PlayerView
import com.chinut.bawantv.BawanApp
import com.chinut.bawantv.ui.theme.Dim
import com.chinut.bawantv.ui.theme.Ink
import com.chinut.bawantv.ui.theme.sdp
import com.chinut.bawantv.ui.theme.ssp
import com.chinut.bawantv.ui.theme.Txt
import kotlinx.coroutines.delay

/** 日志标记 */
private const val TAG_LIVE = "BawanLive"

/** 一个源稳定播放多久才算「可用」，值得记进源记忆。 */
private const val GOOD_SOURCE_MS = 8_000L

/**
 * 信息浮层自动收起的时间。
 * 4 秒是权衡：太短看不清台名，太长会一直挡着画面。
 */
private const val HUD_AUTO_HIDE_MS = 4_000L

/**
 * 直播播放页（板块 A 的核心）。
 *
 * 严格按「老电视 + 遥控器」的逻辑设计，播放时屏幕上没有任何焦点控件，
 * 所有操作都由遥控器按键直接触发：
 *
 *   ↑ / ↓   换台（上一个 / 下一个频道，跨分组自动跳过）
 *   ← / →   音量 -5 / +5
 *   确定键  呼出/隐藏 频道信息浮层
 *   返回键  退出播放，回到频道列表
 *
 * 换台是「秒切」：常驻一个 ExoPlayer 实例，换台只替换 MediaItem、保留解码器，
 * 不做 page 重建，所以切台没有黑屏、没有重新缓冲整个页面。
 */
@UnstableApi
@Composable
fun LivePlayerScreen(
    initialChannel: LiveChannel,
    /** 频道总表（用于上下换台）。为空时只能在当前频道内换源。 */
    channels: List<LiveChannel> = emptyList(),
    onClose: () -> Unit = {},
) {
    val context = LocalContext.current
    val prefs = BawanApp.prefs

    val playlist = remember(channels, initialChannel) {
        val base = if (channels.isEmpty()) listOf(initialChannel) else channels
        // 保证当前频道一定在列表里
        if (base.any { it.url == initialChannel.url }) base else listOf(initialChannel) + base
    }

    var index by remember {
        mutableIntStateOf(playlist.indexOfFirst { it.url == initialChannel.url }.coerceAtLeast(0))
    }
    /** 频道在当前分组里的稳定标识（用于「记住能播的源」）。 */
    fun channelKeyOf(ch: LiveChannel): String = LiveCatalog.normalizeName(ch.name)

    /**
     * 初始源下标：优先用**上次这个台播成功过的那个源**。
     *
     * 这样用户常看的台不会再经历「先黑屏几秒再自动跳源」；
     * 那个源要是哪天失效了，健康探测会照常往后切，并把新的可用源记下来。
     */
    var sourceIndex by remember {
        val list = LiveCatalog.candidatesOf(initialChannel)
        val remembered = prefs.rememberedSource(channelKeyOf(initialChannel))
        val at = if (remembered != null) list.indexOf(remembered) else -1
        mutableIntStateOf(if (at >= 0) at else 0)
    }
    var showing by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf<String?>(null) }
    var retryToken by remember { mutableLongStateOf(0L) }

    /**
     * 自动隐藏信息浮层的触发计数器。
     *
     * 之前的实现有两个毛病，导致浮层"经常挂屏幕上不消失"：
     *  1. 自动隐藏的 `delay` 写在**切流协程**里，换台/切源会把它取消，计时就废了
     *  2. 「确定」键只做 `showing = !showing`，手动打开后**没有任何地方会关它** ——
     *     按一次确定，浮层就永久留在屏幕上
     *
     * 现在改成：不管浮层是被谁打开的（换台、切源、手动确定、点列表），
     * 统一把 [hudTimeoutToken] 加一；由下面那个独立的 LaunchedEffect 负责超时收起。
     * 这样「谁打开」和「什么时候关」彻底解耦，不会再漏。
     */
    var hudTimeoutToken by remember { mutableLongStateOf(0L) }

    val current = playlist.getOrNull(index) ?: initialChannel

    /** 当前频道全部候选地址（顺序即优先级，见 LiveCatalog.candidatesOf）。 */
    fun candidatesOf(channel: LiveChannel): List<String> = LiveCatalog.candidatesOf(channel)

    /** 网页路线：当前该加载哪个地址（网页地址本身 + 备用源）。 */
    val webUrl = remember(current.url, sourceIndex) {
        val list = candidatesOf(current)
        list.getOrNull(sourceIndex.coerceIn(0, (list.size - 1).coerceAtLeast(0))) ?: current.url
    }

    /** 当前频道是否要走「浏览器引擎」路线。 */
    val useWeb = remember(webUrl) { LiveCatalog.isWebPage(webUrl) }

    /** 网页路线的 WebView 实例。 */
    var webView by remember { mutableStateOf<TvWebPlayerView?>(null) }

    // 网页路线：进页面时给一段加载提示，之后交给网页自己
    //
    // 60 秒是"提示"的寿命，不是"判失败"的期限：
    // 到点只是把转圈提示收掉，让画面（如果已经在播）干净地露出来，
    // 绝不再弹错误页。
    LaunchedEffect(webUrl, useWeb) {
        if (useWeb) {
            buffering = true
            failed = null
            delay(60_000)
            buffering = false
        }
    }

    // 频道/源变化时，把 WebView 切到新地址并开始观察是否出画面
    LaunchedEffect(webUrl, useWeb) {
        val view = webView ?: return@LaunchedEffect
        if (!useWeb) return@LaunchedEffect
        android.util.Log.i(TAG_LIVE, "web load $webUrl")
        view.loadChannel(webUrl)
        // 只在"确认出画面"时收加载提示。**不在超时时判失败** ——
        // 电视台的网页播放器经常要等很久，误判会弹出黑幕挡住正常播放的画面。
        view.startHealthWatch {
            view.post { buffering = false }
        }
    }

    // ---------- 源记忆：确认这个源真的出画面了，就记下来下次优先用 ----------
    //
    // 计时从「切到这个源」开始；中途换台/换源/失败都会重置，
    // 所以只有真正稳定播放了一小会儿的源才会被记住 —— 避免把
    // 「闪一下就断」的源误记成可用源。
    var goodSourceMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(current.url, sourceIndex) { goodSourceMs = 0L }
    LaunchedEffect(current.url, sourceIndex, buffering, failed) {
        while (buffering || failed != null) {
            delay(500)
        }
        while (goodSourceMs < GOOD_SOURCE_MS) {
            delay(500)
            goodSourceMs += 500
        }
        prefs.rememberSource(channelKeyOf(current), webUrl)
        android.util.Log.i(TAG_LIVE, "记住可用源 ${current.name} -> $webUrl")
    }

    // 离开播放页时销毁 WebView，避免后台继续播放/占内存
    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                webView?.stopLoading()
                webView?.loadUrl("about:blank")
                webView?.destroy()
            }
            webView = null
        }
    }

    // ---------------- ExoPlayer ----------------
    val player = remember {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 1500,
                /* maxBufferMs = */ 20_000,
                /* bufferForPlaybackMs = */ 800,
                /* bufferForPlaybackAfterRebufferMs = */ 1500,
            )
            .build()
        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .apply {
                playWhenReady = true
                repeatMode = Player.REPEAT_MODE_OFF
            }
    }

    /** 生成某个频道对应的 MediaSource（按域名补 Referer / UA）。 */
    fun sourceFor(channel: LiveChannel, urlOverride: String? = null): MediaSource {
        val url = urlOverride ?: channel.url
        val headers = LiveCatalog.headersFor(url)
        val factory = DefaultHttpDataSource.Factory()
            .setUserAgent(headers["User-Agent"] ?: com.chinut.bawantv.core.Http.UA_MOBILE)
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(
                headers.filterKeys { it != "User-Agent" }
            )
        return HlsMediaSource.Factory(factory)
            .setAllowChunklessPreparation(true)
            .createMediaSource(MediaItem.fromUri(Uri.parse(url)))
    }

    // 频道 / 重试令牌变化 → 切流
    LaunchedEffect(index, retryToken) {
        val ch = playlist.getOrNull(index) ?: return@LaunchedEffect
        // 当前源可能不是直连流（例如电视台网页），先归一化：
        // 取「可直接播放的地址」，优先备用源里的直连流
        val candidates = candidatesOf(ch)
        val target = candidates.getOrNull(sourceIndex.coerceIn(0, (candidates.size - 1).coerceAtLeast(0)))
        if (target == null) {
            buffering = false
            failed = "该频道没有可直接播放的地址"
            return@LaunchedEffect
        }
        buffering = true
        failed = null
        android.util.Log.i("BawanLive", "tune ${ch.name} -> $target (source ${sourceIndex + 1}/${candidates.size})")
        runCatching {
            player.setMediaSource(sourceFor(ch, target))
            player.prepare()
            player.play()
        }.onFailure { failed = it.message }
        prefs.lastChannelUrl = ch.url
        // 台标浮层：换台时自动弹一下（是否弹由用户设置决定），
        // 收起交给下面的超时触发器，这里不再自己 delay
        if (prefs.showChannelHud) {
            showing = true
            hudTimeoutToken++
        }
    }

    /**
     * 信息浮层自动收起。
     *
     * 只要浮层是可见的，就在这个独立协程里倒计时；任何"重新显示"的动作
     * （换台、切源、按确定、点频道列表）都会把 token 加一，
     * 从而**重启**这个计时 —— 也就是常说的"再给你几秒"。
     *
     * 单独抽出来的意义：它不再挂在切流协程上，所以换台不会把它取消掉。
     */
    LaunchedEffect(hudTimeoutToken, showing) {
        if (!showing) return@LaunchedEffect
        delay(HUD_AUTO_HIDE_MS)
        // 倒计时结束后如果 token 没再变（说明这段时间没有新的操作），就收起
        showing = false
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                // 真正播起来了 → 把之前的失败提示清掉。
                // 之前不这么做，导致"已经恢复正常播放了，黑幕还挂在那里"。
                if (state == Player.STATE_READY) {
                    failed = null
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // 当前源播不出来 → 先自动试下一个备用源；
                // 全都试过了才提示，而且提示是**半透明小面板**，
                // 不再用全屏黑幕（黑幕会把"其实已经播起来"的画面挡掉）。
                buffering = false
                val ch = playlist.getOrNull(index)
                val total = ch?.let { candidatesOf(it).size } ?: 0
                if (sourceIndex < total - 1) {
                    sourceIndex++
                    retryToken++
                } else {
                    failed = "该频道暂时无法播放（${error.errorCodeName}）\n可按「← →」换一个源试试"
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            runCatching {
                player.stop()
                player.release()
            }
        }
    }

    // 音量交给系统：**不再由 App 强制设置系统音量**。
    //
    // 原来这里每次进播放页都执行 setStreamVolume(100%)，导致「一重启 App 音量就被拉满」，
    // 而且会弹出系统音量面板盖住画面。遥控器音量键本来就由系统处理，
    // App 里不放音量条、也不占用音量键。

    /** 某频道上次成功播放的源在候选列表里的下标；没有记录或已失效则返回 0。 */
    fun rememberedIndexOf(ch: LiveChannel): Int {
        val remembered = prefs.rememberedSource(channelKeyOf(ch)) ?: return 0
        val at = LiveCatalog.candidatesOf(ch).indexOf(remembered)
        return if (at >= 0) at else 0
    }

    fun tune(delta: Int) {
        if (playlist.size <= 1) return
        index = (index + delta + playlist.size) % playlist.size
        // 换台后回到「这个台自己记住的源」（没有记录才从头开始）
        sourceIndex = rememberedIndexOf(current)
    }

    fun tuneTo(target: Int) {
        if (target !in playlist.indices || target == index) return
        index = target
        sourceIndex = rememberedIndexOf(current)
    }

    /**
     * 循环切换当前频道的播放源。
     *
     * 一个台往往有多条来源（例如 CCTV-13 同时有央视网网页与央视频网页，
     * CCTV-1 在央视网播不出时要靠央视频）。左右键就在这些源之间轮换，
     * 换完会短暂浮出台标条，让人看得见"现在用的是第几个源"。
     */
    fun cycleSource(delta: Int) {
        val list = candidatesOf(current)
        if (list.size <= 1) {
            // 只有一个源也给出反馈，避免用户以为按键没响应
            showing = true
            hudTimeoutToken++
            return
        }
        sourceIndex = ((sourceIndex + delta) % list.size + list.size) % list.size
        showing = true
        hudTimeoutToken++
        android.util.Log.i(
            TAG_LIVE,
            "切源 ${current.name} -> ${sourceIndex + 1}/${list.size}  ${list[sourceIndex]}",
        )
    }

    /** 源的显示名：方便浮层上直接看出当前是哪条来源。 */
    fun sourceLabel(url: String): String = when {
        url.contains("cctv.com") -> "央视网"
        url.contains("yangshipin") -> "央视频"
        url.contains("1905.com") -> "1905"
        url.endsWith(".m3u8") || url.endsWith(".flv") -> "直连流"
        else -> "网页源"
    }

    // 网页路线不需要 ExoPlayer，避免它去拉一个注定失败的流（日志噪音 + 白耗流量）
    LaunchedEffect(useWeb) {
        if (useWeb) runCatching { player.stop() }
    }

    // 播放页是「纯按键界面」：没有可聚焦项，方向键/确定键由这里接管。
    // 注册到焦点管理器，避免按键落到没人处理（否则播放中上下键没反应）。
    //
    // 按键约定（按遥控器直觉来）：
    //   ↑ ↓  换台         ← →  切换这个台的播放源
    //   确定  呼出/收起台标条+频道列表        返回  退出播放
    val focusManager = com.chinut.bawantv.ui.theme.LocalTvFocusManager.current
    DisposableEffect(focusManager, playlist.size) {
        focusManager?.setKeyInterceptor { dir ->
            when (dir) {
                com.chinut.bawantv.ui.theme.Direction.Up -> {
                    tune(-1); true
                }

                com.chinut.bawantv.ui.theme.Direction.Down -> {
                    tune(1); true
                }

                com.chinut.bawantv.ui.theme.Direction.Left -> {
                    cycleSource(-1); true
                }

                com.chinut.bawantv.ui.theme.Direction.Right -> {
                    cycleSource(1); true
                }
            }
        }
        focusManager?.setConfirmInterceptor {
            // 打开时重启自动收起计时；关闭时不需要
            showing = !showing
            if (showing) hudTimeoutToken++
            true
        }
        onDispose {
            focusManager?.setKeyInterceptor(null)
            focusManager?.setConfirmInterceptor(null)
        }
    }

    BackHandler { onClose() }

    // ---------------- 遥控器按键 ----------------
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionUp -> {
                        tune(-1); true
                    }

                    Key.DirectionDown -> {
                        tune(1); true
                    }

                    Key.DirectionLeft -> {
                        cycleSource(-1); true
                    }

                    Key.DirectionRight -> {
                        cycleSource(1); true
                    }

                    Key.Enter, Key.DirectionCenter, Key.NumPadEnter -> {
                        showing = !showing
                        if (showing) hudTimeoutToken++
                        true
                    }

                    Key.MediaPlayPause -> {
                        if (player.isPlaying) player.pause() else player.play()
                        true
                    }

                    else -> false
                }
            }
    ) {
        // ---------- 画面 ----------
        //
        // 两条播放路线，按频道地址自动选择：
        //  · 直连流（m3u8/flv）→ ExoPlayer，可自定义 Referer，解决防盗链
        //  · 电视台网页（央视网、各省台直播页）→ WebView，让网页自己的播放器去解码
        //    （央视那类加密专有流只有它们的网页播放器能解，交给 ExoPlayer 必然报
        //     PARSING_MANIFEST_MALFORMED）。注入脚本会把页面其余元素隐藏，只留视频铺满。
        if (useWeb) {
            AndroidView(
                factory = { ctx ->
                    TvWebPlayerView(ctx).also { view ->
                        webView = view
                        view.loadChannel(webUrl)
                    }
                },
                update = { view ->
                    // 无条件同步（loadChannel 内部有 url 相同就跳过的判断）。
                    // 之前写成 `if (view.url != webUrl)` 会漏掉「首次 factory 里 load 还没生效」
                    // 的那一次，导致切台后 WebView 仍停在旧页面。
                    view.loadChannel(webUrl)
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        this.player = player
                        keepScreenOn = true
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // ---------- 加载中 ----------
        if (buffering && failed == null && !useWeb) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Ink.Accent, strokeWidth = 3.sdp)
                    Spacer(Modifier.height(14.sdp))
                    Text(
                        "${current.name}  正在接入…",
                        color = Ink.TextSecondary,
                        fontSize = Txt.Label,
                    )
                }
            }
        }

        // ---------- 失败提示 ----------
        failed?.let { msg ->
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Ink.Scrim),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("无法播放「${current.name}」", color = Color.White, fontSize = Txt.Section)
                    Spacer(Modifier.height(8.sdp))
                    Text(msg, color = Ink.TextTertiary, fontSize = Txt.Label)
                    Spacer(Modifier.height(20.sdp))
                    // 用方向键图形代替文字提示，和播放界面保持一致
                    com.chinut.bawantv.ui.DpadHint(
                        up = "换个台",
                        down = "换个台",
                        left = if (current.alternates.isNotEmpty()) "换个源" else "没有备用源",
                        right = if (current.alternates.isNotEmpty()) "换个源" else "",
                        center = "重试",
                        compact = true,
                    )
                }
            }
        }

        // ---------- 左下角：台标信息条（重绘版）----------
        //
        // 设计目标：原来是「底部整条全景浮层 + 右侧一个独立小卡片」，两块各画各的、
        // 视觉上很割裂。现在统一成一套语言：
        //   · 左下角一张圆角玻璃卡片，只放「台号 + 台名 + 分组/源」和方向键图示
        //   · 右侧一张同风格的长卡片作为频道列表，两者都用同样的底色/圆角/内边距
        //   · 都从屏幕边缘滑入，动画时长一致，读起来像一个整体
        AnimatedVisibility(
            visible = showing,
            enter = fadeIn(tween(140)) + slideInHorizontally(tween(200)) { -it / 4 },
            exit = fadeOut(tween(200)) + slideOutHorizontally(tween(200)) { -it / 4 },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = Dim.SafeH * 0.8f, bottom = Dim.SafeV * 2.2f),
        ) {
            ChannelCard(
                channel = current,
                position = index + 1,
                total = playlist.size,
                sourceIndex = sourceIndex,
                sourceName = sourceLabel(webUrl),
                alternates = current.alternates.size,
                buffering = buffering,
            )
        }

        // ---------- 右侧频道列表（全频道、可循环）----------
        AnimatedVisibility(
            visible = showing && playlist.size > 1,
            enter = fadeIn(tween(140)) + slideInHorizontally(tween(200)) { it },
            exit = fadeOut(tween(200)) + slideOutHorizontally(tween(200)) { it },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(end = Dim.SafeH * 0.8f, top = Dim.SafeV, bottom = Dim.SafeV),
        ) {
            ChannelPeek(
                channels = playlist,
                currentIndex = index,
                onPick = { tuneTo(it) },
            )
        }
    }
}

// ==================== 台标卡片 ====================

/**
 * 左下角的台标信息卡。
 *
 * 和右侧频道列表用同一套视觉（圆角、玻璃底、内边距），所以整屏看起来是一套东西，
 * 而不是「两个各自为政的浮层」。
 *
 * 提示只留遥控器方向键图形 —— 文字提示（确定/返回）在电视上没有价值：
 * 遥控器上本来就有这些键，写出来只是占地方。
 */
@Composable
private fun ChannelCard(
    channel: LiveChannel,
    position: Int,
    total: Int,
    sourceIndex: Int,
    sourceName: String,
    alternates: Int,
    buffering: Boolean,
) {
    Column(
        Modifier
            .width(430.sdp)
            .clip(RoundedCornerShape(Dim.BigRadius))
            .background(Ink.Deep.copy(alpha = 0.82f))
            .border(
                1.dp,
                Ink.TextTertiary.copy(alpha = 0.22f),
                RoundedCornerShape(Dim.BigRadius),
            )
            .padding(horizontal = 20.sdp, vertical = 16.sdp),
    ) {
        // ---------- 台号 + 台名 ----------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$position",
                color = Ink.AccentBright,
                fontSize = 46.ssp,
                fontWeight = FontWeight.Black,
            )
            Spacer(Modifier.width(12.sdp))
            Column(Modifier.weight(1f)) {
                Text(
                    channel.name,
                    color = Color.White,
                    fontSize = 26.ssp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.sdp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        channel.group,
                        color = Ink.AccentBright.copy(alpha = 0.9f),
                        fontSize = Txt.Label,
                    )
                    Text(
                        "  ·  $position/$total",
                        color = Ink.TextTertiary,
                        fontSize = Txt.Label,
                    )
                    if (alternates > 0) {
                        Text(
                            "  ·  $sourceName ${sourceIndex + 1}/${alternates + 1}",
                            color = Ink.Amber,
                            fontSize = Txt.Label,
                        )
                    }
                }
            }
            if (buffering) {
                BufferingDot(0.9f)
            }
        }

        Spacer(Modifier.height(12.sdp))

        // ---------- 只留遥控器方向键图形 ----------
        com.chinut.bawantv.ui.DpadHint(
            up = "上个台",
            down = "下个台",
            left = if (alternates > 0) "上个源" else "只有一个源",
            right = if (alternates > 0) "下个源" else "",
            center = "确定",
            compact = true,
            // 卡片里已经有底色了，这里不要再叠一层背景，否则像"卡片里嵌卡片"
            transparent = true,
        )
    }
}

@Composable
private fun HintKey(key: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .clip(RoundedCornerShape(6.sdp))
                .background(Ink.CardStrong)
                .padding(horizontal = 8.sdp, vertical = 3.sdp)
        ) {
            Text(key, color = Color.White, fontSize = Txt.Caption, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.width(6.sdp))
        Text(label, color = Ink.TextTertiary, fontSize = Txt.Caption)
    }
}

// ==================== 右侧频道预览 ====================

/**
 * 右侧频道列表。
 *
 * 与左下角台标卡片同风格（同样的玻璃底 + 圆角 + 细边框），整屏读起来是一套界面。
 *
 * 关键行为：**换台在整张表里循环** —— 列表来自当前播放列表（通常是整个分组，
 * 例如「央视 27 台」），走到头会自动绕回第一个，就像老电视一直按上下键那样。
 * 当前频道滚动到可视区中间偏上，往下翻的时候能提前看到后面几个台。
 */
@Composable
private fun ChannelPeek(
    channels: List<LiveChannel>,
    currentIndex: Int,
    onPick: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(currentIndex) {
        // 目标位置放在可视区靠上 1/3 处，而不是正中：
        // 往下换台时用户更关心"接下来是什么"，留更多下方余量。
        runCatching { listState.animateScrollToItem((currentIndex - 2).coerceAtLeast(0)) }
    }
    Column(
        Modifier
            .width(300.sdp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(Dim.BigRadius))
            .background(Ink.Deep.copy(alpha = 0.80f))
            .border(
                1.dp,
                Ink.TextTertiary.copy(alpha = 0.22f),
                RoundedCornerShape(Dim.BigRadius),
            )
            .padding(vertical = 12.sdp),
    ) {
        // 列表头：当前第几个 / 共几个
        Row(
            Modifier.padding(horizontal = 16.sdp, vertical = 4.sdp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "全部频道",
                color = Color.White,
                fontSize = Txt.Label,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${currentIndex + 1} / ${channels.size}",
                color = Ink.AccentBright,
                fontSize = Txt.Caption,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(6.sdp))
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 8.sdp, vertical = 4.sdp),
            verticalArrangement = Arrangement.spacedBy(2.sdp),
            modifier = Modifier.fillMaxHeight(),
        ) {
            itemsIndexed(channels) { i, ch ->
                val active = i == currentIndex
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(9.sdp))
                        .background(
                            if (active) {
                                Brush.horizontalGradient(
                                    listOf(Ink.AccentSoft, Ink.AccentSoft.copy(alpha = 0.25f))
                                )
                            } else {
                                Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                            }
                        )
                        .padding(horizontal = 10.sdp, vertical = 7.sdp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 当前频道左侧加一条高亮竖线，比只改底色更容易一眼定位
                    Box(
                        Modifier
                            .width(3.sdp)
                            .height(16.sdp)
                            .background(
                                if (active) Ink.AccentBright else Color.Transparent,
                                RoundedCornerShape(2.sdp),
                            )
                    )
                    Spacer(Modifier.width(8.sdp))
                    Text(
                        "${i + 1}",
                        color = if (active) Ink.AccentBright else Ink.TextFaint,
                        fontSize = Txt.Caption,
                        modifier = Modifier.width(30.sdp),
                        textAlign = TextAlign.End,
                    )
                    Spacer(Modifier.width(8.sdp))
                    Text(
                        ch.name,
                        color = if (active) Color.White else Ink.TextSecondary,
                        fontSize = Txt.Caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/** 缓冲指示点（HUD 上的小圆点）。 */
@Composable
internal fun BufferingDot(progress: Float) {
    val a by animateFloatAsState(progress, tween(300), label = "dot")
    Box(
        Modifier
            .size(8.sdp)
            .clip(RoundedCornerShape(4.sdp))
            .background(Ink.Accent.copy(alpha = 0.4f + 0.6f * a))
    )
}
