package com.chinut.bawantv.live

import android.content.Context
import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.heightIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
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
    /** 从菜单里直接选台（比按键换台跳得远）。 */
    onChannelChange: (LiveChannel) -> Unit = {},
    /** 换了主源要重新拉频道表，交回上层做。 */
    onReloadCatalog: () -> Unit = {},
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

    // ---------- 切换菜单（三横键）的状态 ----------
    //
    // 面板分两段：上面分类、下面该分类的频道列表 + 本频道的源。
    // 用 menuSeg 记住光标在哪一段（0=分类行，1=列表）。
    var menuCategory by remember {
        mutableStateOf(com.chinut.bawantv.live.LiveCatalog.categoryOf(initialChannel))
    }
    var menuSeg by remember { mutableIntStateOf(1) }
    var menuCatCursor by remember {
        mutableIntStateOf(
            com.chinut.bawantv.live.LiveCatalog.Category.entries
                .indexOf(com.chinut.bawantv.live.LiveCatalog.categoryOf(initialChannel))
                .coerceAtLeast(0)
        )
    }
    /** 菜单里光标选中的频道下标（针对 menuCategory 过滤后的列表）。 */
    var menuChannelCursor by remember { mutableIntStateOf(-1) }

    /** 当前分类下可换的台。 */
    val menuChannels: List<LiveChannel> = remember(channels, menuCategory) {
        if (menuCategory == com.chinut.bawantv.live.LiveCatalog.Category.All) {
            channels
        } else {
            channels.filter {
                com.chinut.bawantv.live.LiveCatalog.categoryOf(it) == menuCategory
            }
        }
    }
    /** 直连地址缓存用的 key：频道 + 源下标（不同源的地址不一样）。 */
    fun cacheKeyOf(ch: LiveChannel, srcIndex: Int): String =
        LiveCatalog.normalizeName(ch.name) + "#" + srcIndex

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

        // ---------- 优先直连，源记忆只在它自己也是直连时才认 ----------
        //
        // 踩过的坑（用户反馈"进了直连地址却还走网页"）：
        // 频道表里直连地址已经排在第一位了，但"源记忆"记的是**老版本的网页源**
        // （以前只有网页源能用），于是它把下标指到了网页那个候选上，
        // 直连地址明明排在前面却被跳过。
        //
        // 现在的规则：
        //   · 有直连候选 → 用它（老电视唯一跑得动的路线）
        //   · 记忆的源**本身是直连**且还在列表里 → 尊重它（用户手选的）
        //   · 记忆的是网页源 → 忽略，不要拿它覆盖直连
        val directAt = list.indexOfFirst { LiveCatalog.isDirectStream(it) }
        val rememberedAt =
            if (remembered != null && LiveCatalog.isDirectStream(remembered))
                list.indexOf(remembered)
            else -1

        val start = when {
            rememberedAt >= 0 -> rememberedAt       // 用户选过的直连
            directAt >= 0 -> directAt               // 有直连就用直连
            else -> 0                               // 只有网页源，退回去
        }
        mutableIntStateOf(start)
    }
    var showing by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(true) }

    /** 清晰度/线路选择面板是否打开（遥控器三横键呼出）。 */
    var qualityPanel by remember { mutableStateOf(false) }

    /** 面板里的光标位置。 */
    var qualityCursor by remember { mutableIntStateOf(0) }

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

    /**
     * 网页路线"等太久了"。
     *
     * 和 buffering 的区别：buffering 是"还在接入"，这个是"接入时间已经不正常了"。
     * 用来把提示从"正在接入…"升级成"按左右键换个源试试" ——
     * 用户至少知道**自己能做点什么**，而不是干等一张占位图。
     */
    var webSlow by remember(webUrl) { mutableStateOf(false) }

    /**
     * 网页播放器**真的出过画面**（不是"页面加载完了"）。
     *
     * 和 webFirstFrame 的区别很关键：
     *   · webFirstFrame —— "可以放用户看网页了"，兜底超时也会把它置 true
     *   · sawRealFrame  —— 注入脚本确认 <video> 真的在播
     *
     * 只有后者能证明"这台电视放得出来"。用前者判断会把
     * "页面加载完但视频起不来"误当成成功 —— 那正是老电视上的表现。
     */
    var sawRealFrame by remember(webUrl) { mutableStateOf(false) }

    /**
     * 网页播放器是否已经**真的出画面**。
     *
     * 用户要求：“后台先去加载，前台看到是转圈，等加载好了能播放了再输出给用户看”。
     *
     * 之前是让用户直接盯着央视频的加载占位图发呆（真机反馈“大部分时间都在显示那张图”），
     * 体验很差。现在没出画面之前 WebView 是 INVISIBLE 的，用户只看到我们的转圈。
     */
    var webFirstFrame by remember(webUrl) { mutableStateOf(false) }

    /** 是否已经在走 ExoPlayer 硬解（此时网页要藏起来，避免两路同时渲染）。 */
    var nativeActive by remember(webUrl) { mutableStateOf(false) }

    /**
     * 站点进入全屏后交给我们的那个视频 View。
     *
     * 非空时说明正在"接管式全屏" —— 页面的其余部分不参与渲染，
     * 只有这一个 View 铺满屏幕。这正是参考项目（土拨鼠浏览器）的做法，
     * 比"注入 CSS 强撑 video 元素"省得多。
     */
    var siteFullscreenView by remember(webUrl) { mutableStateOf<android.view.View?>(null) }

    /**
     * 该频道是否已经有可用的**直连地址**缓存。
     *
     * 有的话就完全不需要 WebView 了 —— 直接把这个地址交给 ExoPlayer。
     * 这是电视上"能不能流畅播"的关键：Chromium 在电视上要吃掉
     * 60~100MB 内存和大量 CPU，把它省掉，ExoPlayer 才有资源把缓冲做厚。
     *
     * 第一次打开某个频道仍然要走网页截获（没有别的办法拿到地址），
     * 截获成功后就永久记下来，从第二次起就是秒开。
     */
    val cachedStreamUrl = remember(current.url, sourceIndex) {
        StreamCache.get(context, cacheKeyOf(current, sourceIndex))
    }

    /** 当前频道是否要走「浏览器引擎」路线。有直连缓存就不用走。 */
    val useWeb = remember(webUrl, cachedStreamUrl) {
        cachedStreamUrl == null && LiveCatalog.isWebPage(webUrl)
    }


    /** 网页路线的 WebView 实例。 */
    var webView by remember { mutableStateOf<TvWebPlayerView?>(null) }

    /**
     * WebView 是否已经释放掉了。
     *
     * 硬解播稳之后我们就销毁 WebView（省下 60~100MB 内存和大量 CPU），
     * 但要保证**只销毁一次**，并且之后再也不要往它上面挂东西。
     */
    var webReleased by remember(webUrl) { mutableStateOf(false) }

    /**
     * 销毁 WebView，把 Chromium 占的资源还给系统。
     *
     * 顺序有讲究（漏掉任何一步都可能泄漏或崩溃）：
     *   1. stopLoading —— 停掉还在飞的网络请求（不然它还在拉视频分片）
     *   2. 载入 about:blank —— 让页面先卸载，释放 DOM / 解码器
     *   3. 移除 JS 桥 —— 避免回调打到已经销毁的实例上
     *   4. 从父容器摘掉 —— 还在视图树里的话 destroy() 之后会被重新绘制
     *   5. destroy() —— 真正释放
     */
    fun releaseWebView() {
        val wv = webView ?: return
        android.util.Log.i(TAG_LIVE, "硬解已稳定，销毁 WebView 释放内存")
        runCatching {
            wv.onFirstFrame = null
            wv.fullscreenCallback = null
            wv.fullscreenExit = null
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.clearHistory()
            (wv.parent as? android.view.ViewGroup)?.removeView(wv)
            wv.destroy()
        }.onFailure { android.util.Log.w(TAG_LIVE, "销毁 WebView 失败：${it.message}") }
        webView = null
    }

    /**
     * 从网页里捕获到的媒体流地址。
     *
     * 非空表示"可以改用 ExoPlayer 硬解播放了" —— 详见 TvWebPlayerView.onStreamFound。
     * 这是解决真机「直播卡成 PPT」的关键手段：网页播放器在电视上太吃力，
     * 而原生播放器直接走硬件解码。
     */
    var nativeFallbackUrl by remember { mutableStateOf<String?>(null) }

    // 切台/换源时清掉上一次捕获的地址，否则会拿旧流去播新台
    //
    // 60 秒是"提示"的寿命，不是"判失败"的期限：
    // 到点只是把转圈提示收掉，让画面（如果已经在播）干净地露出来，
    // 绝不再弹错误页。
    // ---------- 网页是否露面：**按状态统一决定** ----------
    //
    // 三种情况才让用户看到网页：
    //   · 真的出画面了（webFirstFrame，由注入脚本回报）
    //   · 兜底超时（12 秒还没出画面，别让用户一直盯着黑屏）
    //   · 硬解路线已经放弃（nativeActive 回退）
    //
    // 关键点是**只有一个地方写 visibility**，不会互相打架。
    LaunchedEffect(webUrl, webFirstFrame) {
        if (!useWeb) return@LaunchedEffect
        if (!webFirstFrame) {
            kotlinx.coroutines.delay(WEB_VISIBLE_FALLBACK_MS)
            if (!webFirstFrame) {
                android.util.Log.w(TAG_LIVE, "网页 ${WEB_VISIBLE_FALLBACK_MS}ms 未回报出画面，先放出来")
                webFirstFrame = true
            }
        }
        // 硬解已经接管时不要抢（那时网页要藏着）
        if (!nativeActive) {
            webView?.visibility = android.view.View.VISIBLE
            // ⚠️ 这里原来是 `buffering = false`，等于"到点就假装加载完了"。
            //
            // 后果：老电视上视频根本没起来，屏幕上却什么提示都没有，
            // 用户只能盯着央视的占位海报，以为软件坏了。
            //
            // 现在改成：网页露出来了，但如果**始终没有片子出画面**，
            // 就保持加载提示，并在再等一段时间后升级成"换个源试试"。
            // ⚠️ 这里**绝不能保持转圈**。
            //
            // 我上一版写成"网页露出后如果还没出画面就继续转圈"，
            // 结果是那个 CircularProgressIndicator 会**永久挂在视频上面**
            // 一直做动画 —— 在电视上持续动画要和视频抢合成，
            // 表现就是"画面卡住了"。而且注入脚本一旦失灵，
            // 转圈永远不会消失（它盖住的正是已经在播的画面）。
            //
            // 现在：网页一露面就收掉转圈。没出画面的话，
            // 用**不参与渲染**的静态文字提示（见下面的 slowHint 浮层）。
            buffering = false
            if (!sawRealFrame) {
                kotlinx.coroutines.delay(WEB_SLOW_HINT_MS)
                if (!sawRealFrame) {
                    webSlow = true
                    android.util.Log.w(TAG_LIVE, "网页起播超时，显示静态换源提示")
                }
            }
        }
    }

    // ---------- 出画面后主动请求站点全屏 ----------
    //
    // 目标是走"接管 View"这条路（见 docs/直播卡顿-参考项目对比分析.md）：
    // 整页不再参与渲染，只画那一个视频 View，最省 CPU。
    //
    // 站点不一定立刻响应，所以试两次：
    //   · 出画面后 0.6 秒试一次（多数站点这时播放器已就绪）
    //   · 再过 1.5 秒试第二次（有些站点的全屏按钮是延迟渲染的）
    // 两次都不成也无所谓 —— 还有那套 CSS 清理兜底，画面照样铺满。
    LaunchedEffect(webUrl, webFirstFrame, useWeb) {
        if (!useWeb || !webFirstFrame) return@LaunchedEffect
        if (siteFullscreenView != null) return@LaunchedEffect
        kotlinx.coroutines.delay(600)
        webView?.requestSiteFullscreen()
        kotlinx.coroutines.delay(1_500)
        if (siteFullscreenView == null) webView?.requestSiteFullscreen()
    }

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
                        // 起播攒 6 秒就够（原来 15 秒太贪，老电视内存吃紧反而更容易抖动）
                        /* minBufferMs = */ 6_000,
                        /* maxBufferMs = */ 30_000,
                        /* bufferForPlaybackMs = */ 1_500,
                        /* bufferForPlaybackAfterRebufferMs = */ 4_000,
            )
            .build()

        // ---------- 选轨策略：**保守起步，让自适应往上探** ----------
        //
        // 这里我改错过一次，值得写清楚。
        //
        // 抓到的央视流是多码率 master playlist：
        //     1800000 / 1280x720
        //     1350000 / 1024x576
        //      900000 /  854x480
        //      600000 /  640x360
        //
        // 我当时的想法是"直播宁可缓冲也别糊"，于是把上限放到 1920x1080 /
        // 20Mbps，等于**强迫起播就走 720p**。结果真机反馈"试了很多个电视
        // 都带不起来" —— 老电视的解码能力和 WiFi 吞吐根本吃不下 1.8Mbps，
        // 于是不停缓冲，看起来就是"卡"。
        //
        // 现在的做法：给一个**与设备能力相称的上限**，然后交给自适应。
        // 解码/带宽跟不上时它会自发停在能扛的档位，够用的时候才会升上去。
        // 这比"一刀切锁最高"或"一刀切锁最低"都稳。
        val isLowEnd = com.chinut.bawantv.live.DeviceTier.isLowEnd(context)

        val trackSelector = androidx.media3.exoplayer.trackselection
            .DefaultTrackSelector(context).apply {
                parameters = parameters.buildUpon()
                    .apply {
                        // 按**设备真实能力**给上限（核数 + 内存 + API + ABI），
                        // 而不是"是不是 32 位老系统"。
                        //
                        // 踩过的坑：原来只判断 `SDK < 24 && 非64位`，
                        // 结果大量 Android 7/8 的四核弱电视躲过判断、被喂 720p，
                        // 解码跟不上就成了"卡"甚至"黑屏"（实测日志显示硬解
                        // 不报错、只是永远不出画面 —— 就是码率吃不下）。
                        // 手动设置优先；没设（0）才走自动判定
                        val manual = com.chinut.bawantv.live.DeviceTier
                            .manualLimit(prefs.liveQuality)
                        val (w, h, bitrate) = manual
                            ?: com.chinut.bawantv.live.DeviceTier
                                .videoLimit(com.chinut.bawantv.live.DeviceTier.of(context))
                        android.util.Log.i(
                            TAG_LIVE,
                            "直播清晰度上限 ${w}x${h}@${bitrate / 1000}k " +
                                "（手动=${prefs.liveQuality}）",
                        )
                        setMaxVideoSize(w, h)
                        setMaxVideoBitrate(bitrate)
                    }
                    .build()
            }

        ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
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

    // 切台/换源时清掉上一次捕获的流地址，否则会拿旧流去播新台
    LaunchedEffect(webUrl, sourceIndex) {
        nativeFallbackUrl = null
        nativeActive = false
    }

    /**
     * 捕获到流地址 → 切到 ExoPlayer 硬解。
     *
     * 这是解决真机「直播卡成 PPT」的关键：网页播放器在电视上太吃力
     * （网页重 + 和解码抢 CPU），而原生播放器直接走硬件解码。
     *
     * 注意这里**不销毁 WebView**：留着它继续跑，万一原生解不了
     * （确实是加密专有流）还能回退到网页画面。
     */
    // ---------- 有直连缓存：直接用，不碰 WebView ----------
    LaunchedEffect(current.url, sourceIndex, cachedStreamUrl) {
        val u = cachedStreamUrl ?: return@LaunchedEffect
        android.util.Log.i(TAG_LIVE, "命中直连缓存，跳过网页直接硬解：$u")
        runCatching {
            player.setMediaSource(sourceFor(current, u))
            player.prepare()
            player.play()
        }.onFailure {
            android.util.Log.w(TAG_LIVE, "直连缓存起播失败，清除缓存下次重新截获：${it.message}")
            StreamCache.forget(context, cacheKeyOf(current, sourceIndex))
        }
    }

    LaunchedEffect(nativeFallbackUrl) {
        val u = nativeFallbackUrl ?: return@LaunchedEffect
        android.util.Log.i(TAG_LIVE, "使用捕获到的流地址起播（硬解）：$u")
        runCatching {
            player.setMediaSource(sourceFor(current, u))
            player.prepare()
            player.play()
        }.onFailure { android.util.Log.w(TAG_LIVE, "捕获流起播失败：${it.message}") }

        // ---------- 这里**先不要动 WebView** ----------
        //
        // 我在这里犯过一个错误：一"开始"走硬解就把网页隐藏掉（GONE）。
        // 结果硬解一旦失败（电视上很常见：CDN 拒连、编解码器不支持），
        // 用户看到的就是**纯黑屏、什么都没有**——比网页路线还糟。
        //
        // 正确做法：**等硬解真的渲染出第一帧**再隐藏网页（见播放器监听里的
        // STATE_READY / onRenderedFirstFrame）。在那之前网页继续当保底画面。
        //
        // 下面设一个超时：如果 N 秒内硬解没出画面，就把网页恢复出来。
        //
        // ⚠️ 这里原来是**盲砍**：不管硬解是在报错还是只是慢，到点就放弃。
        // 后果是"再等两秒就能出画面的"也被砍掉，退回那个在电视上根本跑不动的
        // WebView —— 老电视"直接播不出来"，有一部分就是这么来的。
        //
        // 现在分两种情况：
        //   · playerError 非空   → 真的解不了，直接放弃，不用等满超时
        //   · 还在 prepare/buffer → 只是慢，再给一轮时间
        nativeActive = false
        kotlinx.coroutines.delay(NATIVE_READY_TIMEOUT_MS)
        if (!nativeActive) {
            val failedHard = runCatching { player.playerError != null }.getOrDefault(false)
            if (failedHard) {
                android.util.Log.w(TAG_LIVE, "硬解确实报错，退回网页播放")
            } else {
                android.util.Log.i(
                    TAG_LIVE,
                    "硬解 ${NATIVE_READY_TIMEOUT_MS}ms 没出画面但也没报错，再等 ${NATIVE_EXTRA_WAIT_MS}ms",
                )
                kotlinx.coroutines.delay(NATIVE_EXTRA_WAIT_MS)
            }
        }
        if (!nativeActive) {
            android.util.Log.w(TAG_LIVE, "硬解最终未出画面，退回网页播放")
            runCatching {
                player.stop()
                webView?.let { wv -> wv.visibility = android.view.View.VISIBLE }
            }
        }
    }

    // 网页路线：进页面时给一段加载提示，之后交给网页自己

    // 频道 / 重试令牌变化 → 切流
    // ---------- 直播播放状态诊断 ----------
    //
    // 为什么需要：用户反馈"画面彻底冻住"，而日志只告诉我
    // "onRenderedFirstFrame 从未触发" —— 这不足以定位。
    //
    // 三种可能的表现完全一样（画面不动），但原因和处理方式完全不同：
    //   1. 流没数据        → position 不推进，buffered 也不涨。要换源。
    //   2. 有数据但没出帧   → buffered 在涨、position 可能也在走，
    //                        但 renderedFirstFrame 始终为 false。渲染层问题。
    //   3. 位置在推进      → 播放器是好的，是屏幕没刷新（合成问题）。
    //
    // 每条都带 videoFormat 的宽高码率，能直接看出跑在哪一档。
    LaunchedEffect(index, sourceIndex) {
        // ---------- 卡顿统计 ----------
        //
        // 为什么要它：用户反馈"每隔几秒卡零点几秒"，但光看每 3 秒一条的
        // 快照**看不出卡了几次**。这里累计，实测一次就能定性：
        //   · 卡顿时 state=BUFFERING  → 取流不够快（网络/源）
        //   · 卡顿时 state 一直 READY → 渲染卡（解码/码率）
        //   · 码率来回跳              → 自适应切档
        // 三种修法完全不同，所以先测再调。
        var lastPos = -1L
        var goneMs = 0L          // 3 秒里 position 没推进的次数
        var stalls = 0           // 累计"没推进"的次数
        var rebuf = 0            // 累计进入 BUFFERING 的次数
        var prevState = -1
        var ticks = 0

        while (true) {
            kotlinx.coroutines.delay(3_000)
            runCatching {
                // 卡顿判定：位置没推进（播放中却不走 = 卡了）
                val nowPos = player.currentPosition
                if (player.isPlaying && nowPos == lastPos) {
                    goneMs += 1
                    stalls += 1
                }
                lastPos = nowPos
                val st0 = player.playbackState
                if (st0 == Player.STATE_BUFFERING && prevState != Player.STATE_BUFFERING) {
                    rebuf += 1
                }
                prevState = st0
                ticks += 1
                if (ticks % 10 == 0) {
                    android.util.Log.i(
                        "BawanLiveStall",
                        "汇总：卡${stalls}次 重缓${rebuf}次 / 采样${ticks}次 " +
                            "码率=${(player.videoFormat?.bitrate ?: 0) / 1000}k " +
                            "分辨率=${player.videoFormat?.width}x${player.videoFormat?.height}",
                    )
                }
                val st = when (player.playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "?"
                }
                val fmt = player.videoFormat
                val fmtText = if (fmt == null) "null" else
                    "${fmt.width}x${fmt.height}@${(fmt.bitrate ?: 0) / 1000}k"
                android.util.Log.i(
                    "BawanLiveStat",
                    "state=$st playing=${player.isPlaying} pos=${player.currentPosition} " +
                        "buf=${player.bufferedPosition} dur=${player.duration} " +
                        "video=$fmtText err=${player.playerError?.errorCodeName ?: "none"} " +
                        "useWeb=$useWeb native=$nativeActive sawFrame=$sawRealFrame",
                )
            }
        }
    }

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
            override fun onRenderedFirstFrame() {
                // ---------- 硬解真的出画面了，这才轮到隐藏网页 ----------
                //
                // 顺序很重要：必须**先确认硬解能出画面**，再去动网页。
                // 反过来（起播时就隐藏网页）会在硬解失败时留下纯黑屏 ——
                // 我踩过这个坑，真机反馈就是"直播不出画面"。
                super.onRenderedFirstFrame()
                if (useWeb) {
                    android.util.Log.i(TAG_LIVE, "硬解已出画面，停掉网页路线")
                    nativeActive = true
                    // 把这次成功的直连地址记下来 —— 以后打开这个频道就不用再走网页了
                    nativeFallbackUrl?.let { StreamCache.put(context, cacheKeyOf(current, sourceIndex), it) }
                    webView?.let { wv ->
                        wv.onFirstFrame = null
                        // 停掉网页里的 video：两路同时解码既费 CPU 又会叠影
                        runCatching {
                            wv.evaluateJavascript(
                                "try{var vs=document.querySelectorAll('video');" +
                                    "for(var i=0;i<vs.length;i++){vs[i].pause();}}catch(e){}",
                                null,
                            )
                        }
                        wv.visibility = android.view.View.GONE
                    }
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING

                // ---------- 硬解稳定后释放 WebView ----------
                //
                // 这里原来只是把 WebView 设成 GONE("隐藏")，**没有销毁**。
                // 后果是直播期间 Chromium 一直活着：DOM 还在渲染、JS 定时器还在跑、
                // 页面还在占着 60~100MB 内存 —— 和 ExoPlayer 抢 CPU 和内存。
                //
                // 这正是"新电视也卡、十几年的电视直接播不动"的主因：
                // 不是解码能力不够，是一半资源被白白占着。
                //
                // 现在：硬解进入 READY（真的稳定了）就彻底销毁 WebView。
                // 只有等它播稳了才销毁，是因为硬解也可能中途失败，
                // 那时还需要网页路线兜底。
                if (state == Player.STATE_READY && useWeb && nativeActive && !webReleased) {
                    webReleased = true
                    releaseWebView()
                }
                // 真正播起来了 → 把之前的失败提示清掉。
                // 之前不这么做，导致"已经恢复正常播放了，黑幕还挂在那里"。
                if (state == Player.STATE_READY) {
                    failed = null
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // ---------- 网页频道：完全忽略 ExoPlayer 的报错 ----------
                //
                // 真机/模拟器都踩过这个坑：画面明明在正常播（网页播放器起起来了），
                // 却盖着一个「无法播放 …（ERROR_CODE_IO_BAD_HTTP_STATUS）」的浮层。
                //
                // 原因是网页频道的播放器**根本不是 ExoPlayer** ——
                // tune() 仍会先把它交给 ExoPlayer 试一下（那些 URL 多是网页地址，
                // ExoPlayer 当然解不了），于是这里报错 → 去试"备用源" →
                // 备用源也没有 → 弹出失败提示，盖住了 WebView 正在播的画面。
                //
                // 所以：**网页频道的 ExoPlayer 错误直接丢掉**，不要动任何状态。
                if (useWeb) {
                    // 但要**记下来**。原来这里直接 return，连日志都没有，
                    // 于是"硬解到底为什么没出画面"完全无从判断 ——
                    // 是解码器不支持？还是只是起播慢？排查全靠猜。
                    android.util.Log.w(
                        TAG_LIVE,
                        "硬解报错（网页路线，忽略并继续用网页兜底）：" +
                            "${error.errorCodeName} / ${error.cause?.javaClass?.simpleName}" +
                            " / ${error.message?.take(160)}",
                    )
                    return
                }
                // ---------- 不做自动换源 ----------
                //
                // 用户明确要求：「不要每次自动跳到源2，应该是默认源1，
                // 用户改了再改，每次都记住」。
                //
                // 自动往后跳有两个坏处：
                //  · 用户明明选好了源，一次网络抖动就被改掉，下次打开又是别的源；
                //  · 跳转期间黑屏等待，体感很差。
                //
                // 现在只提示、不擅自切换。用户按「← →」自己换，
                // 换到能播的源之后会被 [rememberSource] 记住（见上面的源记忆逻辑）。
                buffering = false
                val ch = playlist.getOrNull(index)
                val total = ch?.let { candidatesOf(it).size } ?: 0

                // ---------- 播着播着挂了 → **自动换源** ----------
                //
                // 用户反馈："部分频道明明可以播放，播放几秒后就黑屏了，声音也没了。"
                //
                // 这不是"用户选好的源被抢走"，而是**当前源真的断了**：
                // 直连流很常见的情况是能起播、过几秒被 CDN 掐掉（鉴权过期 / 并发限制）。
                // 这种必须自己换下一个源，否则用户看到的就是永久黑屏。
                //
                // 与"不要自动跳源"那条的区别：
                //   · 用户主动按 ←→ 选了源 → 记住它、**不覆盖**（见 rememberSource）
                //   · 当前源自己断了     → 自动往后试，这是救场，不是覆盖用户选择
                if (total > 1) {
                    val next = (sourceIndex + 1) % total
                    android.util.Log.w(
                        "BawanLive",
                        "源 $sourceIndex 播放中断（" + error.errorCodeName + "），自动切到源 $next",
                    )
                    sourceIndex = next
                    retryToken++
                    failed = "当前线路断了，正在自动换源…"
                } else {
                    failed = "该频道暂时无法播放（" + error.errorCodeName + "）"
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

    /**
     * 菜单里切换分类（←→ 键）。
     *
     * 顺便把分类行的光标和频道光标都归零 —— 否则换完分类，
     * 光标会停在一个对新分类无意义的下标上。
     */
    fun onMenuMoveCategory(delta: Int) {
        val cats = com.chinut.bawantv.live.LiveCatalog.Category.entries
        val n = cats.size
        menuCatCursor = ((menuCatCursor + delta) % n + n) % n
        menuCategory = cats[menuCatCursor]
        menuChannelCursor = 0
        menuSeg = 0
        hudTimeoutToken++
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
    // ---------- 设置里改了主源/老电视模式 → 关掉播放器回首页 ----------
    //
    // 原来只有「三横键菜单里自己改主源」会走 onReloadCatalog；
    // 在**设置页**改的时候播放器完全不感知（用户反馈「手动切换源后
    // 直播列表没有切换」）。
    //
    // prefs.revision 每次改设置都会 +1，这里盯着它：
    // 一变就退出播放器，首页会按新配置重新拉频道表。
    val prefsRevision by prefs.revision.collectAsState()
    var seenRevision by remember { mutableIntStateOf(prefsRevision) }
    LaunchedEffect(prefsRevision) {
        if (prefsRevision != seenRevision) {
            seenRevision = prefsRevision
            android.util.Log.i(TAG_LIVE, "设置变了（revision=$prefsRevision），退回首页重拉频道表")
            onReloadCatalog()
        }
    }

    // 按键约定（按遥控器直觉来）：
    //   ↑ ↓  换台         ← →  切换这个台的播放源
    //   确定  呼出/收起台标条+频道列表        返回  退出播放
    val focusManager = com.chinut.bawantv.ui.theme.LocalTvFocusManager.current
    // ⚠️ key 必须包含 `index` 和 `sourceIndex`。
    //
    // 踩过的坑（用户反馈「有的频道显示有多个源，但按左右键没有反应」）：
    // 拦截器 lambda 捕获了 `current`（= playlist[index]）和 `sourceIndex`。
    // 原来 key 只有 focusManager + playlist.size，**换台后这个 effect 不重启**，
    // 于是拦截器里 `candidatesOf(current)` 还是**上一个频道**的源列表：
    //   · 上一个台只有 1 个源 → `list.size <= 1` → 直接 return（只闪一下台标）
    //   · 用户视角就是"这台明明有好几个源，按左右没反应"
    //
    // 这和 v1.4.1 修的「短剧换集后按键拦截器失效」是同一个 bug 模式。
    DisposableEffect(focusManager, playlist.size, index, sourceIndex) {
        focusManager?.setKeyInterceptor { dir ->
            // 清晰度面板打开时，方向键在面板里选择，不要拿去换台
            if (qualityPanel) {
                when (dir) {
                    com.chinut.bawantv.ui.theme.Direction.Up -> {
                        qualityCursor = (qualityCursor - 1).coerceAtLeast(0); true
                    }

                    com.chinut.bawantv.ui.theme.Direction.Down -> {
                        val max = (candidatesOf(current).size - 1).coerceAtLeast(0)
                        qualityCursor = (qualityCursor + 1).coerceAtMost(max); true
                    }

                    // ←→ 也用来在列表里移动。
                    //
                    // 原来这两键直接返回 true（什么都不做），本意是"别拿去换台"，
                    // 但用户看到的是一按左右就没反应 —— 对着几十个源只能一个个按上下，
                    // 很别扭。现在让它们等价于上下。
                    com.chinut.bawantv.ui.theme.Direction.Left -> {
                        qualityCursor = (qualityCursor - 1).coerceAtLeast(0); true
                    }

                    com.chinut.bawantv.ui.theme.Direction.Right -> {
                        val max = (candidatesOf(current).size - 1).coerceAtLeast(0)
                        qualityCursor = (qualityCursor + 1).coerceAtMost(max); true
                    }
                }
            } else {
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
        }
        focusManager?.setConfirmInterceptor {
            if (qualityPanel) {
                // 面板里按确定 = 选中这一档
                if (qualityCursor != sourceIndex) {
                    sourceIndex = qualityCursor
                    retryToken++
                }
                qualityPanel = false
            } else {
                // 打开时重启自动收起计时；关闭时不需要
                showing = !showing
                if (showing) hudTimeoutToken++
            }
            true
        }
        // 遥控器上那颗「三横键」（菜单/设置）→ 呼出清晰度选择
        focusManager?.setRawKeyInterceptor { code ->
            if (code == android.view.KeyEvent.KEYCODE_MENU ||
                code == android.view.KeyEvent.KEYCODE_SETTINGS
            ) {
                qualityPanel = !qualityPanel
                // 打开时把光标定位到当前正在用的那一档
                if (qualityPanel) qualityCursor = sourceIndex
                hudTimeoutToken++
                true
            } else {
                false
            }
        }
        onDispose {
            focusManager?.setKeyInterceptor(null)
            focusManager?.setConfirmInterceptor(null)
            focusManager?.setRawKeyInterceptor(null)
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
                // ---------- 切换菜单打开时：方向键操作菜单 ----------
                //
                // 必须先拦截 —— 否则用户一边在菜单里选台，一边频道还在乱跳。
                if (qualityPanel) {
                    when (e.key) {
                        Key.DirectionUp -> {
                            if (menuSeg == 1 && menuChannelCursor <= 0) {
                                // 列表顶上再按「上」→ 跳到分类行
                                menuSeg = 0
                                hudTimeoutToken++
                            } else if (menuSeg == 1) {
                                menuChannelCursor -= 1
                                hudTimeoutToken++
                            }
                            true
                        }

                        Key.DirectionDown -> {
                            if (menuSeg == 0) {
                                menuSeg = 1
                                if (menuChannelCursor < 0) menuChannelCursor = 0
                            } else if (menuChannels.isNotEmpty()) {
                                val n = menuChannels.size
                                menuChannelCursor = (menuChannelCursor + 1) % n
                            }
                            hudTimeoutToken++
                            true
                        }

                        Key.DirectionLeft -> {
                            onMenuMoveCategory(-1); true
                        }

                        Key.DirectionRight -> {
                            onMenuMoveCategory(1); true
                        }

                        else -> false
                    }
                } else when (e.key) {
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
                        // ---------- 抓到流地址就切原生播放 ----------
                        //
                        // 真机实测：网页播放 1080p 在电视上**卡成 PPT** ——
                        // 网页本身很重（央视页面一堆脚本/统计/广告），
                        // 还要和视频解码抢 CPU，电视 SoC 扛不住。
                        //
                        // 但网页播放器最终还是要去拉一个真实的媒体流
                        // （m3u8/mp4），那个地址我们能在 shouldInterceptRequest 里截到。
                        // 拿到之后交给 ExoPlayer 走**硬件解码**，流畅度差一个量级。
                        //
                        // 策略：网页先加载并起播（保证能拿到地址、也兜住那些
                        // 确实只能由网页解码的加密流），一旦截到地址就悄悄切过去。
                        view.onStreamFound = { url ->
                            if (url != nativeFallbackUrl) {
                                android.util.Log.i(
                                    "BawanLive",
                                    "网页流地址已捕获，切换到 ExoPlayer 硬解：$url",
                                )
                                nativeFallbackUrl = url
                            }
                        }
                        // 站点请求全屏 → 接管它给的那个 View
                        view.fullscreenCallback = { v, _ ->
                            siteFullscreenView = v
                        }
                        view.fullscreenExit = {
                            siteFullscreenView = null
                        }
                        // 真的出画面了才让用户看到（之前只显示我们的转圈）
                        view.frameObserver = { sawRealFrame = true }
                        view.onFirstFrame = { sawRealFrame = true;
                            webFirstFrame = true
                            buffering = false
                        }
                        view.visibility = android.view.View.INVISIBLE
                        view.loadChannel(webUrl)
                    }
                },
                update = { view ->
                    // 只同步 URL。
                    //
                    // ⚠️ **绝不要在这里碰 visibility** —— update 每次重组都会跑，
                    // 之前在这里设可见性 + postDelayed 兜底，导致：
                    //   · 可见性被反复翻转，和硬解超时后的恢复逻辑打架；
                    //   · 定时器不断堆积，最终表现是"要等十分钟才出画面"。
                    // 可见性交给下面的 LaunchedEffect，按状态来。
                    view.loadChannel(webUrl)
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        // ---------- 用 TextureView，不用默认的 SurfaceView ----------
                        //
                        // 用户报「花屏」。实测截图：只有顶部一条在更新，
                        // 下面是一大片**陈旧残影**（不是黑边，是旧帧没擦掉）。
                        //
                        // 这是 SurfaceView 的锅 —— 它是独立图层、由 SurfaceFlinger
                        // 单独合成，模拟器和国产电视的定制 ROM 在"裁剪/缩放/被 UI 覆盖"
                        // 时经常合成出错。TextureView 走普通 View 绘制流程，
                        // 和 Compose UI 一起合成，不会有这个问题。
                        //
                        // 代价是多一次 GPU 拷贝，换来画面能看 —— 对老电视完全值得。
                        // ---------- 规避花屏 ----------
                        //
                        // 用户报「花屏」。实测截图特征很清楚：
                        //   · 只有**顶部一条**在更新
                        //   · 下面是一大片**陈旧残影**（不是黑边，是旧帧没擦掉）
                        //
                        // SurfaceView 是独立图层、由 SurfaceFlinger 单独合成。
                        // 在 Compose 里、以及模拟器/国产电视的定制 ROM 上，
                        // 缩放和裁剪时经常合成出错 —— 这就是花屏的来源。
                        //
                        // 两手都上：
                        //   1. Media3 自带的 Compose 合成修正开关（官方就是为这个场景加的）
                        //   2. 把渲染 View 换成 TextureView（见下面 player 赋值之后的调用）
                        resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        this.player = player
                        keepScreenOn = true
                        // player 就绪了才能切渲染 View（之前放在赋值前，日志报
                        // "player 还没就绪，TextureView 未绑定"，等于没生效）
                        applySurfaceWorkaround()
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // ---------- 起播慢的静态提示 ----------
        //
        // 刻意做得极简：**没有动画、没有进度条**。
        //
        // 为什么：这是叠在视频上的浮层，任何持续动画都要和视频抢合成，
        // 在电视上代价很高（上一版用转圈就导致"画面卡住"）。
        // 静态文字只画一帧，画完就不占资源。
        //
        // 15 秒后自动消失：它只是"告诉用户能做什么"，不该常驻挡画面。
        if (webSlow && failed == null) {
            var hintVisible by remember { mutableStateOf(true) }
            LaunchedEffect(webSlow) {
                kotlinx.coroutines.delay(15_000)
                hintVisible = false
            }
            if (hintVisible) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(bottom = 72.sdp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Text(
                        "${current.name} 起播较慢 · 按「左右键」换个源试试",
                        color = Color.White,
                        fontSize = Txt.Label,
                        modifier = Modifier
                            .background(
                                Color(0xCC000000),
                                RoundedCornerShape(10.sdp),
                            )
                            .padding(horizontal = 18.sdp, vertical = 10.sdp),
                    )
                }
            }
        }

        // ---------- 加载中 ----------
        //
        // ⚠️ 这里原来有 `&& !useWeb`，于是**网页路线完全没有加载提示**。
        //
        // 后果（真机反馈）：老电视上进央视，网页加载了但视频起不来，
        // 用户看到的是央视频那张"白底 + 深蓝椭圆 + 播放三角"的**占位海报**，
        // 而且 12 秒兜底一到连 buffering 都被清掉，屏幕上再没有任何反馈 ——
        // 看起来就像"卡死了"，完全不知道是在加载、还是已经失败。
        //
        // 现在网页路线也给状态提示，并且区分两种情况：
        //   · 还在加载（webSlow 为 false）→ 转圈 + "正在接入…"
        //   · 拖太久了（webSlow 为 true）→ 给出**可操作的**提示：换源
        if (buffering && failed == null && !sawRealFrame) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Ink.Accent, strokeWidth = 3.sdp)
                    Spacer(Modifier.height(14.sdp))
                    Text(
                        "${current.name}  正在接入…",
                        color = Ink.TextSecondary,
                        fontSize = Txt.Label,
                    )
                    if (webSlow) {
                        Spacer(Modifier.height(10.sdp))
                        Text(
                            "这个源在这台电视上起得慢，可按「左右键」换一个源试试",
                            color = Ink.Amber,
                            fontSize = Txt.Caption,
                        )
                    }
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

    // ---------- 全屏视频容器 ----------
    //
    // 站点进入全屏后，它的视频 View 会挂在这里。
    // 用 AndroidView 包一个 FrameLayout 当宿主 —— 因为 Compose 不能直接 addView，
    // 而我们又必须把那个原生 View 放进来。
    //
    // 放在最上层：全屏时它盖住下面所有东西（包括网页本身）。
    siteFullscreenView?.let { fv ->
        AndroidView(
            factory = { ctx ->
                android.widget.FrameLayout(ctx).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { host ->
                // 把站点给的 View 挂进来（先摘掉旧的，避免重复添加）
                val parent = fv.parent
                if (parent is android.view.ViewGroup) parent.removeView(fv)
                if (host.childCount == 0) {
                    host.addView(
                        fv,
                        android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                    android.util.Log.i(TAG_LIVE, "已接管站点全屏 View，页面其余部分不再渲染")
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }

    // ---------- 清晰度 / 线路选择面板 ----------
    //
    // 遥控器「三横键」（菜单/设置）呼出。
    //
    // 说明一下这里的"清晰度"到底是什么：
    // 直播是**电视台网页里的流**，清晰度由台站自己的播放器决定，
    // 我们没法像点播那样直接切 720P/1080P 档位。
    // 能提供的是**换个源** —— 不同源的清晰度/稳定性确实不一样，
    // 所以面板列的是这个频道所有可用的播放源，并标出它是什么类型。
    AnimatedVisibility(
        visible = qualityPanel,
        enter = fadeIn(tween(140)),
        exit = fadeOut(tween(160)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f)),
        )
    }
    AnimatedVisibility(
        visible = qualityPanel,
        enter = fadeIn(tween(140)) + slideInHorizontally(tween(200)) { it / 3 },
        exit = fadeOut(tween(180)) + slideOutHorizontally(tween(200)) { it / 3 },
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .wrapContentHeight()
            .padding(end = Dim.SafeH * 0.8f, top = Dim.SafeV, bottom = Dim.SafeV),
    ) {
        QualityPanel(
            channel = current,
            sources = candidatesOf(current),
            activeIndex = sourceIndex,
            cursor = qualityCursor,
            onPick = { i ->
                qualityCursor = i
                if (i != sourceIndex) {
                    sourceIndex = i
                    retryToken++
                }
                qualityPanel = false
            },
            // ---------- 切换菜单：分类 + 频道 ----------
            categories = com.chinut.bawantv.live.LiveCatalog.Category.entries,
            category = menuCategory,
            catCursor = menuCatCursor,
            segment = menuSeg,
            channels = menuChannels,
            channelCursor = menuChannelCursor,
            currentChannelUrl = current.url,
            onCategory = { cat ->
                menuCategory = cat
                menuSeg = 1
                menuChannelCursor = 0
                hudTimeoutToken++
            },
            onMoveCat = { d ->
                val n = com.chinut.bawantv.live.LiveCatalog.Category.entries.size
                menuCatCursor = ((menuCatCursor + d) % n + n) % n
                menuCategory = com.chinut.bawantv.live.LiveCatalog.Category.entries[menuCatCursor]
                menuSeg = 1
                menuChannelCursor = 0
                hudTimeoutToken++
            },
            onMoveChannel = { d ->
                if (menuChannels.isNotEmpty()) {
                    if (menuChannelCursor < 0) {
                        // 第一次移动：从当前频道出发
                        val at = menuChannels.indexOfFirst { it.url == current.url }
                        menuChannelCursor = if (at >= 0) at else 0
                    } else {
                        val n = menuChannels.size
                        menuChannelCursor = ((menuChannelCursor + d) % n + n) % n
                    }
                    hudTimeoutToken++
                }
            },
            onLeaveListUp = {
                // 在列表顶上再按「上」→ 跳到分类行
                menuSeg = 0
                hudTimeoutToken++
            },
            onPickChannel = { ch ->
                qualityCursor = 0
                sourceIndex = 0
                retryToken++
                onChannelChange(ch)
                qualityPanel = false
            },
            // ---------- 主源切换（A / B / AB）----------
            //
            // 用户要求：「在直播播放页面中点击三横线时用户自己选择主源」。
            // 切换后要重新加载频道表 —— 因为 A 和 B 是两个完全不同的列表。
            preset = com.chinut.bawantv.live.LivePreset.of(prefs.livePreset),
            oldTvMode = prefs.oldTvMode,
            onPreset = { p ->
                prefs.livePreset = p.name
                // HomeWarmup 会自己发现配置变了并重新预热（见它的 warmKey），
                // 这里只要关掉播放器回首页就行。
                hudTimeoutToken++
                onReloadCatalog()
                qualityPanel = false
            },
        )
    }
    }
}

// ==================== 清晰度 / 线路面板 ====================

/**
 * 清晰度（源）选择面板。
 *
 * 为什么标题写「清晰度 / 线路」而不是只写"清晰度"：
 * 直播流跑在电视台自己的网页播放器里，**没办法直接切码率档位**，
 * 能切换的是"用哪个源"。不同源的画质和稳定性差别很大（有的 720P、
 * 有的就是网页默认档），所以对用户来说它承担的就是"换个更清楚的"这个作用。
 * 但标题必须诚实，不能让用户以为能在 720P/1080P 之间精确选。
 */
@Composable
private fun QualityPanel(
    channel: LiveChannel,
    sources: List<String>,
    activeIndex: Int,
    cursor: Int,
    onPick: (Int) -> Unit,
    // ---------- 切换菜单 ----------
    categories: List<com.chinut.bawantv.live.LiveCatalog.Category>,
    category: com.chinut.bawantv.live.LiveCatalog.Category,
    catCursor: Int,
    segment: Int,
    channels: List<LiveChannel>,
    channelCursor: Int,
    currentChannelUrl: String,
    onCategory: (com.chinut.bawantv.live.LiveCatalog.Category) -> Unit,
    onMoveCat: (Int) -> Unit,
    onMoveChannel: (Int) -> Unit,
    onLeaveListUp: () -> Unit,
    onPickChannel: (LiveChannel) -> Unit,
    preset: com.chinut.bawantv.live.LivePreset,
    onPreset: (com.chinut.bawantv.live.LivePreset) -> Unit,
    /** 老电视模式：开了就强制 GitHub 源，面板里不再给主源切换。 */
    oldTvMode: Boolean,
) {
    // 每个频道的源可能有几十个（央视 33 个），面板必须能滚动，
    // 否则超出的项会跑到屏幕外 —— 用户「看得到列表但选不中」就是这么来的。
    val listState = rememberLazyListState()
    // 光标移动时自动把它滚进可见范围（遥控器没法拖动滚动条）
    LaunchedEffect(cursor, sources.size) {
        if (cursor >= 0 && cursor < sources.size) {
            runCatching { listState.animateScrollToItem(cursor) }
        }
    }

    Column(
        Modifier
            .width(360.sdp)
            .clip(RoundedCornerShape(Dim.BigRadius))
            .background(Color.Black.copy(alpha = 0.82f))
            .border(
                width = 1.sdp,
                color = Color.White.copy(alpha = 0.14f),
                shape = RoundedCornerShape(Dim.BigRadius),
            )
            .padding(20.sdp),
    ) {
        Text(
            "清晰度 / 线路",
            color = Color.White,
            fontSize = Txt.Section,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(10.sdp))

        // ---------- 主源切换（A / B / AB）----------
        //
        // 用户要求：「在直播播放页面中点击三横线时用户自己选择主源」。
        // A = 内置引擎（网页 + 直连），B = 开源源（全直连，轻），
        // AB = 两个都加载。点了会重新拉频道表。
        // 老电视模式开了就不给主源切换 —— 那个模式下只有一个正确选择
        // （GitHub 直连源），给他别的选项就是自相矛盾（用户指出过）。
        if (!oldTvMode) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.sdp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("主源", color = Ink.TextTertiary, fontSize = Txt.Tiny)
                Spacer(Modifier.width(4.sdp))
                com.chinut.bawantv.live.LivePreset.entries.forEach { p ->
                    val on = p == preset
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.sdp))
                            .background(
                                if (on) Ink.Accent.copy(alpha = 0.45f)
                                else Color.White.copy(alpha = 0.06f)
                            )
                            .border(
                                width = if (on) 2.sdp else 0.sdp,
                                color = if (on) Ink.AccentBright else Color.Transparent,
                                shape = RoundedCornerShape(10.sdp),
                            )
                            .clickable { onPreset(p) }
                            .padding(vertical = 7.sdp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            p.label,
                            color = if (on) Color.White else Ink.TextTertiary,
                            fontSize = Txt.Caption,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.sdp))

        // ---------- 分类行（全部 / 央视 / 地方台 / IPTV）----------
        //
        // 在播放中就能换分类 —— 不用退回列表页。这是用户要的"切换菜单"。
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.sdp),
        ) {
            categories.forEachIndexed { i, cat ->
                val on = cat == category
                val cursorHere = segment == 0 && i == catCursor
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.sdp))
                        .background(
                            when {
                                cursorHere -> Ink.Accent.copy(alpha = 0.45f)
                                on -> Color.White.copy(alpha = 0.16f)
                                else -> Color.White.copy(alpha = 0.06f)
                            }
                        )
                        .border(
                            width = if (cursorHere) 3.sdp else 0.sdp,
                            color = if (cursorHere) Ink.AccentBright else Color.Transparent,
                            shape = RoundedCornerShape(10.sdp),
                        )
                        .clickable { onCategory(cat) }
                        .padding(vertical = 8.sdp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        cat.label,
                        color = if (on || cursorHere) Color.White else Ink.TextTertiary,
                        fontSize = Txt.Caption,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.sdp))

        // ---------- 该分类下的频道列表 ----------
        Text(
            "频道 · " + channels.size + " 个",
            color = Ink.TextTertiary,
            fontSize = Txt.Tiny,
        )
        Spacer(Modifier.height(6.sdp))
        val chListState = rememberLazyListState()
        LaunchedEffect(channelCursor, channels.size) {
            if (channelCursor >= 0 && channelCursor < channels.size) {
                runCatching { chListState.animateScrollToItem(channelCursor) }
            }
        }
        androidx.compose.foundation.lazy.LazyColumn(
            state = chListState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 190.sdp),
        ) {
            itemsIndexed(channels) { i, ch ->
                val isHere = ch.url == currentChannelUrl
                val isCursor = segment == 1 && i == channelCursor
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.sdp)
                        .clip(RoundedCornerShape(12.sdp))
                        .background(
                            when {
                                isCursor -> Ink.Accent.copy(alpha = 0.30f)
                                isHere -> Color.White.copy(alpha = 0.10f)
                                else -> Color.White.copy(alpha = 0.04f)
                            }
                        )
                        .border(
                            width = if (isCursor) 3.sdp else 0.sdp,
                            color = if (isCursor) Ink.AccentBright else Color.Transparent,
                            shape = RoundedCornerShape(12.sdp),
                        )
                        .clickable { onPickChannel(ch) }
                        .padding(horizontal = 12.sdp, vertical = 9.sdp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        ch.name,
                        color = if (isCursor || isHere) Color.White else Ink.TextSecondary,
                        fontSize = Txt.Caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (isHere) {
                        Text("正在看", color = Ink.AccentBright, fontSize = Txt.Tiny)
                    }
                }
            }
        }

        Spacer(Modifier.height(14.sdp))

        // ---------- 本频道的源 ----------
        Text(
            "线路 · " + sources.size + " 个",
            color = Ink.TextTertiary,
            fontSize = Txt.Tiny,
        )
        Spacer(Modifier.height(6.sdp))
        Text(
            channel.name,
            color = Ink.TextTertiary,
            fontSize = Txt.Caption,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(14.dp))

        androidx.compose.foundation.lazy.LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.sdp),
        ) {
            itemsIndexed(sources) { i, url ->
            val isActive = i == activeIndex
            val isCursor = i == cursor
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.sdp)
                    .clip(RoundedCornerShape(14.sdp))
                    .background(
                        when {
                            isCursor -> Ink.Accent.copy(alpha = 0.30f)
                            isActive -> Color.White.copy(alpha = 0.10f)
                            else -> Color.White.copy(alpha = 0.05f)
                        }
                    )
                    .border(
                        width = if (isCursor) 3.sdp else 0.sdp,
                        color = if (isCursor) Ink.AccentBright else Color.Transparent,
                        shape = RoundedCornerShape(14.sdp),
                    )
                    .clickable { onPick(i) }
                    .padding(horizontal = 14.sdp, vertical = 12.sdp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        // 网页源没有码率信息，就按类型如实标注
                        if (LiveCatalog.isWebPage(url)) "线路 ${i + 1}（网页播放）"
                        else "线路 ${i + 1}（直连流）",
                        color = Color.White,
                        fontSize = Txt.Label,
                        fontWeight = if (isCursor || isActive) FontWeight.Bold else FontWeight.Normal,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        runCatching { java.net.URI(url).host ?: url }.getOrDefault(url),
                        color = Ink.TextFaint,
                        fontSize = Txt.Tiny,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (isActive) {
                    Text(
                        "使用中",
                        color = Ink.Green,
                        fontSize = Txt.Tiny,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            "↑↓ 选台/选线路 · ←→ 换分类 · 确定键选中 · 三横键关闭",
            color = Ink.TextFaint,
            fontSize = Txt.Tiny,
        )
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

        Spacer(Modifier.height(10.sdp))

        // ---------- 一行按键提示 ----------
        //
        // 原来是整个遥控器圆盘图（约 120dp 高）。用户反馈：
        // 「换台的时候提示下就好了啊」—— 说得对，那图看几次就记住了，
        // 常驻只是白占地方。压成一行文字，信息一样不少。
        Row(verticalAlignment = Alignment.CenterVertically) {
            HintKey("↑↓", "换台")
            Spacer(Modifier.width(14.sdp))
            HintKey("←→", if (alternates > 0) "换源" else "换源")
            Spacer(Modifier.width(14.sdp))
            HintKey("≡", "更多")
        }
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


/**
 * 从"开始走硬解"到"判定硬解不行"的等待上限。
 *
 * 太短会误判（直播流建立连接本来就要几秒），
 * 太长则用户盯着黑屏等 —— 12 秒是个折中：
 * 网页画面一直当保底，所以这段等待用户并不是在看黑屏。
 */
private const val NATIVE_READY_TIMEOUT_MS = 15_000L

/**
 * 第一轮超时后如果硬解**没报错**（只是还没起起来），再等这么久。
 *
 * 为什么要多等：慢速网络和弱解码器上"起播慢"很常见，
 * 而退回网页在电视上往往更糟（Chromium 跑不动）。多等十几秒更划算。
 */
private const val NATIVE_EXTRA_WAIT_MS = 12_000L


/**
 * 网页多久没回报"出画面"就先让它露面。
 *
 * 之所以要兜底：注入脚本依赖页面结构，站点改版就可能失效。
 * 没有这个兜底，用户会一直盯着黑屏 —— 真机反馈过"要等十分钟才出画面"。
 *
 * 12 秒是个折中：正常站点 3~6 秒就能出画面，超过 12 秒基本可以认为
 * 脚本这条路没走通，宁可先放出网页（哪怕它还带着站点的加载图）。
 */
private const val WEB_VISIBLE_FALLBACK_MS = 12_000L

/**
 * 网页露出之后，再等多久还没出画面就提示用户换源。
 *
 * 这个提示的意义：用户至少知道**自己能做点什么**（左右键换源），
 * 而不是干等一张站点的占位海报，以为软件坏了。
 */
private const val WEB_SLOW_HINT_MS = 10_000L

/**
 * 规避「花屏」（画面撕裂 / 残影 / 只刷新一部分）。
 *
 * ## 现象
 *
 * 用户报花屏，实测截图：**只有顶部一条在更新，下面是一大片陈旧残影**
 * （不是黑边 —— 是旧帧没被擦掉）。
 *
 * ## 原因
 *
 * Media3 的 PlayerView 默认用 **SurfaceView** 渲染。SurfaceView 是**独立图层**，
 * 由 SurfaceFlinger 单独合成，在「缩放 / 裁剪 / 位于 Compose 布局内」这些场景下
 * 很容易合成出错 —— 模拟器、以及国产电视的定制 ROM 尤其明显。
 * 表现就是画面撕裂、残影、只刷新一部分。
 *
 * TextureView 走普通 View 绘制流程，和 Compose UI 一起合成，没有这个问题。
 *
 * ## 做法
 *
 * 1. 打开 Media3 自带的 `setEnableComposeSurfaceSyncWorkaround(true)`
 *    —— 官方提供这个开关就是为"在 Compose 里用 SurfaceView"这个场景。
 * 2. 把 PlayerView 里的 SurfaceView 换成 TextureView：
 *    先把 PlayerView 自己切到 TextureView 路径，再把新 View 挂进去。
 *
 * 代价是多一次 GPU 拷贝，换来画面正常 —— 对老电视完全值得。
 */
private fun androidx.media3.ui.PlayerView.applySurfaceWorkaround() {
    runCatching { setEnableComposeSurfaceSyncWorkaround(true) }
        .onFailure { android.util.Log.w(TAG_LIVE, "Compose 合成修正开关不可用：$it") }

    // 只在真正需要时切一次，避免 AndroidView 多次 update 时重复替换
    if (getVideoSurfaceView() is android.view.TextureView) {
        android.util.Log.i(TAG_LIVE, "渲染 View 已是 TextureView，跳过")
        return
    }

    runCatching {
        val old = getVideoSurfaceView()
        val parent = old?.parent as? android.view.ViewGroup
        if (old == null || parent == null) {
            android.util.Log.w(TAG_LIVE, "拿不到视频 View，沿用默认渲染")
            return@runCatching
        }
        val index = parent.indexOfChild(old)
        val tv = android.view.TextureView(context).apply {
            layoutParams = old.layoutParams
        }
        parent.removeViewAt(index)
        parent.addView(tv, index, old.layoutParams)
        // 让播放器把画面输出到这个 TextureView
        val player = this.player
        if (player != null) {
            player.setVideoTextureView(tv)
            android.util.Log.i(TAG_LIVE, "视频渲染已切到 TextureView")
        } else {
            android.util.Log.w(TAG_LIVE, "player 还没就绪，TextureView 未绑定")
        }
    }.onFailure {
        android.util.Log.w(TAG_LIVE, "TextureView 切换失败，沿用 SurfaceView：$it")
    }
}
