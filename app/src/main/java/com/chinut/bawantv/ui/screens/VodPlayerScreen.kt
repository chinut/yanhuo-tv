package com.chinut.bawantv.ui.screens

import android.content.Context
import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.chinut.bawantv.BawanApp
import com.chinut.bawantv.core.Http
import com.chinut.bawantv.core.WatchHistory
import com.chinut.bawantv.ui.components.PlayButton
import com.chinut.bawantv.ui.player.rememberWatchProgress
import com.chinut.bawantv.ui.player.saveProgressNow
import com.chinut.bawantv.ui.theme.Dim
import com.chinut.bawantv.ui.theme.Ink
import com.chinut.bawantv.ui.theme.rememberTvFocusState
import com.chinut.bawantv.ui.theme.focusBorder
import com.chinut.bawantv.ui.theme.sdp
import com.chinut.bawantv.ui.theme.ssp
import com.chinut.bawantv.ui.theme.tvFocusable
import com.chinut.bawantv.ui.theme.Txt
import kotlinx.coroutines.delay
import androidx.compose.foundation.clickable

/**
 * 影视播放页。
 *
 * 遥控器逻辑：
 *   ← / →   快退 / 快进 10 秒
 *   ↑ / ↓   上一个 / 下一个剧集
 *   确定键  呼出控制条；控制条里再按确定 = 播放/暂停
 *   返回键  退出播放
 *
 * 地址解析（直链 / 内嵌 JSON / 网页解析接口）在起播前完成，
 * 解析期间在画面上给明确的进度提示，不让用户面对黑屏。
 */
@UnstableApi
@Composable
fun VodPlayerScreen(
    request: com.chinut.bawantv.unified.PlayRequest,
    onClose: () -> Unit,
    onSwitchEpisode: (com.chinut.bawantv.unified.PlayRequest) -> Unit,
    /**
     * 短剧模式：一集播完**自动播下一集**。
     *
     * 为什么用开关而不是自动判断：影视（一部电影）播完就该结束，
     * 自动播下一部会让用户莫名其妙。短剧一集只有 1~2 分钟，
     * 不自动连播就得一直按遥控器 —— 那才是没法用。
     */
    isShortDrama: Boolean = false,
) {
    val context = LocalContext.current
    val prefs = BawanApp.prefs

    var index by remember(request) {
        // 断点续播：上次看到第几集，打开时就直接从那一集开始。
        //
        // ⚠️ 这里必须防住"剧集列表为空"的情况：
        // 空列表时 lastIndex == -1，`coerceIn(0, -1)` 会直接抛
        // IllegalArgumentException（minimum > maximum）导致闪退。
        // 之前的写法是 `coerceIn(0, request.episodes.lastIndex.coerceAtLeast(0))`，
        // 看着好像防住了，实际 coerceAtLeast(0) 把 -1 变成 0 之后
        // 仍然会对"列表为空"的输入产生越界索引。
        val lastIdx = request.episodes.lastIndex
        val saved = WatchHistory.find(WatchHistory.KIND_VOD, request.vodId)
        val start = saved?.episodeIndex?.takeIf { it in request.episodes.indices }
            ?: request.index
        mutableIntStateOf(if (lastIdx < 0) 0 else start.coerceIn(0, lastIdx))
    }
    var url by remember { mutableStateOf<String?>(null) }
    var headerMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var via by remember { mutableStateOf("") }
    var resolving by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf<String?>(null) }
    var hudVisible by remember { mutableStateOf(true) }
    var listVisible by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }

    // ---------- 拖动进度（左右键）状态 ----------
    //
    // 设计要点（用户要求"允许左右键拖动播放条 + 提供阻尼效果"）：
    //
    // **不每按一次就 seek**。ExoPlayer 对 HLS 的 seek 是重新拉分片，
    // 连按十下就是十次重新缓冲 —— 真机上表现就是"拖一下卡半天"。
    // 所以这里只累加一个**目标位置**，画面照常播；等用户停手 600ms
    // 才真正 seek 一次（见下面的 LaunchedEffect）。
    //
    // 拖动期间 HUD 强制显示，进度条上会画出"要跳到哪里"的预览。
    var scrubbing by remember { mutableStateOf(false) }

    /** 拖动目标位置（毫秒）。 */
    var scrubTarget by remember { mutableLongStateOf(0L) }

    /** 开始拖动时的原始位置，用于同时显示"从哪到哪"。 */
    var scrubOrigin by remember { mutableLongStateOf(0L) }

    /** 当前这一档的步长 —— 阻尼就体现在它随按住时间变大。 */
    var scrubStepMs by remember { mutableLongStateOf(SCRUB_MIN_MS) }

    /** 上一次按左右键的时间戳，用来判断"是不是在连续按"。 */
    var lastScrubAt by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var retry by remember { mutableIntStateOf(0) }

    /** 失败弹窗里光标停在哪个按钮（0=重试，1=下一集）。 */
    var failCursor by remember { mutableIntStateOf(0) }

    // ---------- 播放期间不让系统息屏 ----------
    //
    // 真机实测：播几分钟后电视进入待机画面，**但音频还在继续**。
    // 原因是之前只给 PlayerView 这个子 View 设了 keepScreenOn，
    // 而部分电视只认**窗口级**的 FLAG_KEEP_SCREEN_ON，不认子 View 的标记。
    // 这里在播放页存在期间给窗口加上这个标志，离开时撤掉。
    DisposableEffect(Unit) {
        val activity = context as? android.app.Activity
        activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val episode = request.episodes.getOrNull(index) ?: request.episodes.firstOrNull()

    // 没有剧集就别往下走了：直接显示提示，避免后面一堆空指针/越界。
    // 正常路径不会到这里（上层已判非空），这是兜底。
    if (episode == null) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("这部作品没有可播放的剧集", color = Color.White, fontSize = Txt.Section.ssp)
                Spacer(Modifier.height(16.sdp))
                com.chinut.bawantv.ui.components.PlayButton("返回", onClick = onClose)
            }
        }
        return
    }

    val player = remember {
        ExoPlayer.Builder(context)
            // ---------- 缓冲策略：专治"卡卡顿顿" ----------
            //
            // 低端影视是 **HLS 分片流**，切片一个接一个下载。用 ExoPlayer 的
            // 默认 LoadControl 时，只要某一小段下载慢一点，缓冲就见底 → 卡一下
            // → 再缓冲，表现就是你说的"卡卡的"。
            //
            // 这里的调整思路是**用启动延迟换播放流畅**：
            // 多等一两秒攒够缓冲，之后就基本不会再卡。
            // 电视用户对"开头转圈两秒"的容忍度，远高于"看着看着卡"。
            .setLoadControl(
                DefaultLoadControl.Builder()
                    // ⚠️ ExoPlayer 有一组**硬性约束**，写反了会直接抛
                    // IllegalArgumentException，把播放页整个崩掉（真机实测踩过）：
                    //     minBufferMs >= bufferForPlaybackMs
                    //     minBufferMs >= bufferForPlaybackAfterRebufferMs
                    // 上一版把 minBufferMs 设成 25000、rebuffer 设成 35000，
                    // 正好违反第二条 → **所有影视点播放立刻闪退**。
                    // 现在 minBufferMs 抬到 40000 满足约束。
                    .setBufferDurationsMs(
                        /* minBufferMs = */ 40_000,
                        /* maxBufferMs = */ 90_000,
                        /* bufferForPlaybackMs = */ 20_000,
                        /* bufferForPlaybackAfterRebufferMs = */ 35_000,
                    )
                    // 允许在已缓存数据后面的部分继续预加载，减少二次卡顿
                    .setPrioritizeTimeOverSizeThresholds(false)
                    .build()
            )
            .build()
            .apply {
                playWhenReady = true
                // 自动联播：一集播完继续往下走，不要停在片尾黑屏
                setPauseAtEndOfMediaItems(false)
            }
    }

    fun mediaSourceOf(u: String, headers: Map<String, String>): MediaSource {
        val ua = headers["User-Agent"] ?: Http.UA_DESKTOP
        val factory = DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            // 超时放宽：分片服务器偶尔慢，默认 8 秒会直接判失败
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(headers.filterKeys { it != "User-Agent" })
        return if (u.contains(".m3u8") || u.contains("m3u8")) {
            HlsMediaSource.Factory(factory)
                // 允许多个分片并行预取，显著减少卡顿
                .setAllowChunklessPreparation(true)
                .createMediaSource(MediaItem.fromUri(Uri.parse(u)))
        } else {
            ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(Uri.parse(u)))
        }
    }

    // ---------- 准备播放地址 ----------
    //
    // 影视现在只有低端影视，它给的剧集地址是**直连 m3u8**，
    // 不需要任何"解析接口"。所以这里从原来的一段解析流程
    // 简化成"拿来就用" —— TVBox 时代那套解析器已随包一起删除。
    LaunchedEffect(episode?.url, retry) {
        val raw = episode?.url ?: return@LaunchedEffect
        resolving = true
        failed = null
        url = null
        if (raw.isBlank()) {
            resolving = false
            failed = "这一集没有可播放的地址"
        } else {
            url = raw
            headerMap = com.chinut.bawantv.live.LiveCatalog.headersFor(raw)
            via = ""
            resolving = false
        }
    }

    // ---------- 起播 ----------
    LaunchedEffect(url) {
        val u = url ?: return@LaunchedEffect
        runCatching {
            player.setMediaSource(mediaSourceOf(u, headerMap))
            player.prepare()
            player.play()
        }.onFailure { failed = it.message }
    }

    // ---------- 自动连播（短剧） ----------
    //
    // 两个必须防住的点：
    //
    // 1. **重复触发**：`onPlaybackStateChanged` 在同一个 STATE_ENDED 上
    //    可能回调多次，不加标志会连切好几集。
    // 2. **空地址**：红果只有免费集能取到播放地址，其余是空串（正常付费墙）。
    //    遇到空地址要给明确提示，而不是切过去黑屏。
    //
    // 注意：Kotlin 的局部函数必须在**使用之前**声明，
    // 所以这段放在 DisposableEffect 之前。
    var autoNextFired by remember(request) { mutableStateOf(false) }

    /** 找下一集里第一个有地址的；没有就返回 null。 */
    fun nextPlayable(): Int? {
        for (i in (index + 1)..request.episodes.lastIndex) {
            if (request.episodes[i].url.isNotBlank()) return i
        }
        return null
    }

    fun onEpisodeEnded() {
        if (autoNextFired) return
        autoNextFired = true
        val next = nextPlayable()
        when {
            next != null -> {
                android.util.Log.i(TAG_VOD, "自动连播：第 ${index + 1} 集 → 第 ${next + 1} 集")
                // 同上：手动复位，别指望 remember(request) 会重置
                // （实测换集后 index 没更新，那个 key 不会变）。
                autoNextFired = false
                onSwitchEpisode(request.copy(index = next))
            }
            index < request.episodes.lastIndex -> {
                android.util.Log.i(TAG_VOD, "自动连播停止：后续剧集没有播放地址（付费集）")
                failed = "后面的剧集需要在「红果短剧」App 里观看"
            }
            else -> android.util.Log.i(TAG_VOD, "自动连播结束：已是最后一集")
        }
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                isPlaying = player.isPlaying
                // 一集播完 → 短剧自动下一集
                if (state == androidx.media3.common.Player.STATE_ENDED && isShortDrama) {
                    onEpisodeEnded()
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlayerError(error: PlaybackException) {
                failed = "播放失败：${error.errorCodeName}"
            }
        }
        player.addListener(listener)
        onDispose {
            // 退出时补记一次进度：自动联播的协程到这里已被取消，不补的话最后几秒会丢
            saveProgressNow(
                player = player,
                kind = WatchHistory.KIND_DDYS,
                id = request.vodId,
                title = request.title,
                poster = request.poster,
                episodeIndex = index,
                episodeName = episode?.name.orEmpty(),
                // flag 是 TVBox 时代的线路标识，现在恒为空串
                // （字段保留只为兼容旧观看记录的解析格式）
                flag = "",
                episodeUrls = request.episodes.map { it.url },
            )
            player.removeListener(listener)
            runCatching {
                player.stop()
                player.release()
            }
        }
    }

    // 进度轮询
    LaunchedEffect(Unit) {
        while (true) {
            position = runCatching { player.currentPosition }.getOrDefault(0L)
            duration = runCatching { player.duration }.getOrDefault(0L).coerceAtLeast(0L)
            buffered = runCatching { player.bufferedPosition }.getOrDefault(0L)
            delay(500)
        }
    }

    LaunchedEffect(hudVisible, listVisible) {
        if (hudVisible && !listVisible) {
            delay(5000)
            hudVisible = false
        }
    }

    // ---------- 松手后才真正 seek（拖动阻尼的落点）----------
    //
    // 用户停手 600ms 才执行一次 seek，而不是每按一下都 seek。
    // 这样连按十几下也不会触发十几次重新缓冲。
    LaunchedEffect(scrubbing, scrubTarget) {
        if (!scrubbing || listVisible) return@LaunchedEffect
        delay(SCRUB_COMMIT_DELAY_MS)
        runCatching { player.seekTo(scrubTarget) }
        scrubbing = false
        hudVisible = true
    }

    // ---------- 观看记录 / 断点续播 / 自动联播 ----------
    // 和板块 C 共用同一套逻辑（见 ui/player/WatchProgress.kt）
    val episodeUrls = remember(request.episodes) { request.episodes.map { it.url } }
    rememberWatchProgress(
        player = player,
        activeUrl = url,
        isLiveStream = false,
        episodeUrls = episodeUrls,
        identityId = request.vodId,
        kind = WatchHistory.KIND_DDYS,
        title = request.title,
        poster = request.poster,
        episodeName = episode?.name.orEmpty(),
        flag = "",
        onSwitchToEpisode = { onSwitchEpisode(request.copy(index = it)) },
        onReachedEnd = onClose,
    )

    // 音量交给系统：App 不再强制设置系统音量（原来会把音量拉满并弹出系统音量面板）

    fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
        isPlaying = player.isPlaying
    }

    fun seekBy(ms: Long) {
        runCatching {
            player.seekTo((player.currentPosition + ms).coerceIn(0, player.duration.coerceAtLeast(0)))
        }
        hudVisible = true
    }

    /**
     * 左右键拖动进度（带阻尼）。
     *
     * ## 阻尼是怎么做的
     *
     * 第一次按：±[SCRUB_MIN_MS]（10 秒），方便精确微调。
     * 如果**连续按**（两次间隔小于 [SCRUB_CHAIN_MS]），步长按 [SCRUB_GROWTH] 增长，
     * 上限 [SCRUB_MAX_MS]（120 秒）—— 按住不放就能快速扫过整部片子。
     *
     * 这就是"阻尼"的手感：**轻点微调、长按加速**，
     * 而不是不管按多久都只跳固定 10 秒（那是现在的问题，拖一部长片要按几十次）。
     *
     * @param forward true = 向右（前进）
     */
    fun scrub(forward: Boolean) {
        val total = runCatching { player.duration }.getOrDefault(0L).coerceAtLeast(0L)
        if (total <= 0L) return

        val now = android.os.SystemClock.elapsedRealtime()
        val chained = scrubbing && (now - lastScrubAt) < SCRUB_CHAIN_MS

        if (!scrubbing) {
            // 刚开始拖：记下起点
            scrubOrigin = runCatching { player.currentPosition }.getOrDefault(0L)
            scrubTarget = scrubOrigin
            scrubStepMs = SCRUB_MIN_MS
        } else if (chained) {
            // 连续按 → 步长递增（阻尼加速），并限制上限
            scrubStepMs = (scrubStepMs * SCRUB_GROWTH).toLong().coerceAtMost(SCRUB_MAX_MS)
        } else {
            // 停了一会儿又按 → 回到微调档
            scrubStepMs = SCRUB_MIN_MS
        }
        lastScrubAt = now

        val delta = if (forward) scrubStepMs else -scrubStepMs
        scrubTarget = (scrubTarget + delta).coerceIn(0L, total)
        scrubbing = true
        hudVisible = true
    }

    fun step(delta: Int) {
        val next = index + delta
        if (next !in request.episodes.indices) return
        // ⚠️ 必须手动重置。
        //
        // `autoNextFired` 原来是 `remember(request)`，指望"request 变了就重置"。
        // 但实测**换集后 index 并没有跟着更新**（日志里切到第 6 集后 index 还是 4），
        // 于是 autoNextFired 一直是 true → 第二次触发被
        // `if (autoNextFired) return` 挡掉，表现就是**只有第一次能自动连播**
        // （用户反馈「一集播放完后不会自动跳转下一集」）。
        //
        // 不依赖 remember 的 key，直接在这里清掉，最可靠。
        autoNextFired = false
        onSwitchEpisode(request.copy(index = next))
    }

    /**
     * 失败弹窗里可选的按钮。
     *
     * 「下一集」只在**确实有下一集**时才给 —— 电影只有一集，
     * 给它一个"下一集"按钮没有任何意义（用户也在问"这个下一集代表什么"）。
     */
    fun failActions(): List<Pair<String, () -> Unit>> = buildList {
        add("重试" to { retry++ })
        if (index + 1 in request.episodes.indices) add("下一集" to { step(1) })
    }

    BackHandler {
        when {
            listVisible -> listVisible = false
            else -> onClose()
        }
    }

    // ---------- 把按键接管下来（这就是"只有返回能点"的根因）----------
    //
    // 之前这里只靠 `onPreviewKeyEvent` 收按键 —— 但**收不到**：
    // `MainActivity.dispatchKeyEvent` 在 ACTION_DOWN 就把方向键/确定键
    // 交给了焦点系统并 return true，事件根本到不了 Compose 的预览回调。
    // 于是播放页上除返回键（走 onBackPressedDispatcher）之外全都失效：
    // 暂停没反应、左右键拖不动进度。
    //
    // 直播页一直是靠这套拦截器工作的，点播页漏了 —— 现在对齐。
    val focusManager = com.chinut.bawantv.ui.theme.LocalTvFocusManager.current
    // ⚠️ key 必须包含 `index`。
    //
    // 踩过的坑（用户反馈「按下只能到第2集，再按就没反应」）：
    // 原来 key 只有 focusManager，换集后这个 effect 不重启，
    // 而换集会重建 ExoPlayer（下面的 DisposableEffect(Unit)），
    // 结果**按键拦截器被顶掉** —— 实测日志里第二次按下之后
    // 完全没有 KeyInterceptor 输出。
    //
    // 把 index 放进 key，换集后重新注册，就正常了。
    DisposableEffect(focusManager, index) {
        focusManager?.setKeyInterceptor { dir ->
            // 失败弹窗显示时，方向键只在"重试 / 下一集"之间走，不要拿去拖进度。
            // （用户反馈过：弹窗上的按钮遥控器选不中。）
            if (failed != null) {
                val acts = failActions()
                when (dir) {
                    com.chinut.bawantv.ui.theme.Direction.Left -> {
                        failCursor = (failCursor - 1).coerceAtLeast(0); true
                    }

                    com.chinut.bawantv.ui.theme.Direction.Right -> {
                        failCursor = (failCursor + 1).coerceAtMost(acts.size - 1); true
                    }

                    com.chinut.bawantv.ui.theme.Direction.Up,
                    com.chinut.bawantv.ui.theme.Direction.Down,
                    -> true
                }
            } else {
                when (dir) {
                    com.chinut.bawantv.ui.theme.Direction.Left -> {
                        scrub(forward = false); true
                    }

                    com.chinut.bawantv.ui.theme.Direction.Right -> {
                        scrub(forward = true); true
                    }

                    com.chinut.bawantv.ui.theme.Direction.Up -> {
                        step(-1); true
                    }

                    com.chinut.bawantv.ui.theme.Direction.Down -> {
                        step(1); true
                    }
                }
            }
        }
        focusManager?.setConfirmInterceptor {
            // 失败弹窗优先：确定 = 执行当前选中的按钮
            val acts = failActions()
            if (failed != null && acts.isNotEmpty()) {
                acts.getOrNull(failCursor.coerceIn(0, acts.size - 1))?.second?.invoke()
                return@setConfirmInterceptor true
            }
            // 否则确定键始终暂停/恢复（用户要求"按确定键暂停或恢复播放"）。
            // 暂停时让控制条留在屏幕上，多半接着要拖进度。
            togglePlay()
            true
        }
        focusManager?.setRawKeyInterceptor { code ->
            if (code == android.view.KeyEvent.KEYCODE_MENU) {
                listVisible = !listVisible; true
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

    // 播放页由主框架（BawanRoot）挂在**根布局层**渲染，所以这里能铺满整屏，
    // 也不会被影视板块的内容区（左侧导航栏 + overscan 边距）挤成小窗口。
    //
    // 注意：不要用 Popup/Dialog 来实现全屏 —— Popup 是独立 window，
    // 返回键会被送到那个窗口，而 BackHandler 注册在主窗口，结果就是**按返回退不出来**。
    // 之前就是这么踩的坑。
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> {
                        scrub(forward = false); true
                    }

                    Key.DirectionRight -> {
                        scrub(forward = true); true
                    }

                    Key.DirectionUp -> {
                        step(-1); true
                    }

                    Key.DirectionDown -> {
                        step(1); true
                    }

                    Key.Enter, Key.DirectionCenter, Key.NumPadEnter -> {
                        // 确定键**始终**暂停/恢复。
                        //
                        // 原来是"控制条显示时才暂停，否则先唤出控制条"，
                        // 结果用户按一下没反应（只是把控制条调出来了），得按两下才暂停。
                        // 用户明确要求"按确定键暂停或恢复播放"，所以改成一步到位。
                        // 控制条的自动收起仍然管着"怎么让它出现"（按左右键即可）。
                        togglePlay()
                        // 暂停时保持控制条可见（用户多半要接着拖进度），
                        // 恢复播放时让它照常自动收起
                        hudVisible = !isPlaying
                        true
                    }

                    Key.MediaPlayPause -> {
                        togglePlay(); true
                    }

                    // 菜单键：剧集列表
                    Key.Menu -> {
                        listVisible = !listVisible; true
                    }

                    else -> false
                }
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                        resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
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

        if (resolving) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Ink.Accent, strokeWidth = 3.sdp)
                    Spacer(Modifier.height(14.sdp))
                    Text("正在解析播放地址…", color = Ink.TextSecondary, fontSize = Txt.Label)
                    Spacer(Modifier.height(4.sdp))
                    Text(
                        episode?.name.orEmpty(),
                        color = Ink.TextFaint,
                        fontSize = Txt.Caption,
                    )
                }
            }
        }

        failed?.let { msg ->
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Ink.Scrim),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "无法播放《${request.title}》",
                        color = Color.White,
                        fontSize = Txt.Section,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(10.sdp))
                    Text(msg, color = Ink.TextTertiary, fontSize = Txt.Label)
                    Spacer(Modifier.height(20.sdp))
                    // ---------- 失败弹窗的两个按钮 ----------
                    //
                    // 用户反馈："播放失败的时候，无法用遥控器选择重试和下一集"。
                    //
                    // 根因和播放页其它按键一样：这个弹窗里的按钮**既没接焦点系统，
                    // 也没接按键拦截**，遥控器按下去当然没反应。
                    // 现在左右键在两者间切换、确定键触发（见上面的 keyInterceptor）。
                    Row(horizontalArrangement = Arrangement.spacedBy(12.sdp)) {
                        for ((i, item) in failActions().withIndex()) {
                            val selected = i == failCursor
                            Box(
                                Modifier
                                    .focusBorder(
                                        visible = selected,
                                        cornerRadius = Dim.CardRadius,
                                        color = Ink.AccentBright,
                                        width = 3.sdp,
                                    )
                            ) {
                                PlayButton(item.first, onClick = item.second)
                            }
                        }
                    }
                }
            }
        }

        // ---------- 拖动进度时的居中提示 ----------
        //
        // 没有这个反馈，用户按住左右键时只能盯着底部那条细进度线猜
        // —— 电视上离得远，根本看不清。这里给一个大号时间提示：
        //   目标时间（大）+ 起点→目标 + 跳转幅度。
        AnimatedVisibility(
            visible = scrubbing && failed == null && duration > 0L,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.Center),
        ) {
            val diffSec = (scrubTarget - scrubOrigin) / 1000
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.sdp))
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 34.sdp, vertical = 22.sdp),
            ) {
                Text(
                    fmt(scrubTarget),
                    color = Color.White,
                    fontSize = 40.ssp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.sdp))
                Text(
                    "${fmt(scrubOrigin)}  →  ${fmt(scrubTarget)}",
                    color = Ink.TextTertiary,
                    fontSize = Txt.Caption,
                )
                Spacer(Modifier.height(4.sdp))
                Text(
                    (if (diffSec >= 0) "+" else "−") +
                        (kotlin.math.abs(diffSec) / 60) + ":" +
                        (kotlin.math.abs(diffSec) % 60).toString().padStart(2, '0'),
                    color = Ink.AccentBright,
                    fontSize = Txt.Label,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // ---------- 短剧：利用竖屏两侧的黑边 ----------
        //
        // 短剧是 720x1280 竖屏，在横屏电视上居中后左右各空一块。
        // 老人在电视上看短剧最需要两件事：**知道第几集**、**怎么切下一集**。
        //
        // 所以两侧放的是**遥控器按键提示**（▲ 上键 / ▼ 下键），不是按钮 ——
        // 遥控器没法"选中"两侧的东西，放按钮只会误导。
        // 详见 SideKeyHint 的说明。
        //
        // 只在 isShortDrama 时显示 —— 影视是宽屏内容，没有黑边可用。
        if (isShortDrama && failed == null) {
            val hasPrev = request.episodes.getOrNull(index - 1)?.url?.isNotBlank() == true
            val hasNext = nextPlayable() != null

            // 左：上一集
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .fillMaxWidth(0.16f)
                    .padding(horizontal = Dim.SafeH * 0.2f),
                contentAlignment = Alignment.Center,
            ) {
                SideKeyHint(
                    keyGlyph = "▲",
                    keyName = "上键",
                    label = "上一集",
                    hint = if (hasPrev) "第 ${index} 集" else "已是第一集",
                    enabled = hasPrev,
                )
            }

            // 右：下一集 + 集数
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .fillMaxWidth(0.16f)
                    .padding(horizontal = Dim.SafeH * 0.2f),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "${index + 1} / ${request.episodes.size}",
                        color = Color.White,
                        fontSize = Txt.Label,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(10.sdp))
                    SideKeyHint(
                        keyGlyph = "▼",
                        keyName = "下键",
                        label = "下一集",
                        hint = if (hasNext) {
                            "第 ${(nextPlayable() ?: index) + 1} 集"
                        } else {
                            "已是最后一集"
                        },
                        enabled = hasNext,
                    )
                }
            }
        }

        // ---------- 常驻的极简进度指示 ----------
        //
        // 控制条收起后，底部只留一条细线 + 一个亮点：
        // 既能一眼看出「播到哪了」，又不会挡画面。
        // 拖动中会把"将要跳到的位置"一起画出来，让左右键有明确的落点反馈。
        if ((!hudVisible || scrubbing) && failed == null && duration > 0L) {
            val cursorPos = if (scrubbing) scrubTarget else position
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(if (scrubbing) 5.sdp else 3.sdp)
                    .background(Ink.Deep.copy(alpha = 0.55f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth((position.toFloat() / duration).coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(Ink.AccentBright.copy(alpha = 0.9f))
                )
                // 拖动中的"目标位置"预览：半透明的第二层盖上去
                if (scrubbing) {
                    Box(
                        Modifier
                            .fillMaxWidth((scrubTarget.toFloat() / duration).coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(Color.White.copy(alpha = 0.55f))
                    )
                }
                // 进度头部的小亮点，像播放器的"光标"
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth((cursorPos.toFloat() / duration).coerceIn(0f, 1f))
                ) {
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .size(if (scrubbing) 14.sdp else 9.sdp)
                            .background(
                                if (scrubbing) Color.White else Ink.AccentBright,
                                RoundedCornerShape(7.sdp),
                            )
                    )
                }
            }
        }

        // ---------- 控制条 ----------
        AnimatedVisibility(
            visible = hudVisible && failed == null,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(300)),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Ink.Deep.copy(alpha = 0.94f))
                        )
                    )
                    .padding(horizontal = Dim.SafeH, vertical = 22.sdp),
            ) {
                Text(
                    request.title,
                    color = Color.White,
                    fontSize = Txt.Section,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.sdp))
                Text(
                    buildString {
                        append(episode?.name.orEmpty())
                        // 来源名由发起方给（影视=低端影视，短剧=红果短剧）。
                        // 原来这里写死"低端影视"，短剧复用播放器后就显示错了。
                        val label = request.sourceLabel
                            .ifBlank { if (isShortDrama) "红果短剧" else "低端影视" }
                        if (label.isNotBlank()) append("   ·   $label")
                    },
                    color = Ink.TextTertiary,
                    fontSize = Txt.Caption,
                )
                Spacer(Modifier.height(12.sdp))

                // 进度条
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.sdp)
                        .clip(RoundedCornerShape(3.sdp))
                        .background(Ink.CardStrong)
                ) {
                    if (duration > 0) {
                        Box(
                            Modifier
                                .fillMaxWidth((buffered.toFloat() / duration).coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(Ink.TextFaint)
                        )
                        Box(
                            Modifier
                                .fillMaxWidth((position.toFloat() / duration).coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(
                                    Brush.horizontalGradient(listOf(Ink.Accent, Ink.AccentBright))
                                )
                        )
                    }
                }
                Spacer(Modifier.height(10.sdp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${fmt(position)} / ${if (duration > 0) fmt(duration) else "--:--"}",
                        color = Ink.TextSecondary,
                        fontSize = Txt.Caption,
                    )
                    Spacer(Modifier.width(18.sdp))
                    // 只留图标，不写文字：遥控器上本来就有这些键，
                    // 写成「← 快退」只是屏幕上的噪声（用户明确要求去掉文字提示）
                    ControlHint(Icons.Default.Replay10, "")
                    Spacer(Modifier.width(14.sdp))
                    ControlHint(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        "",
                    )
                    Spacer(Modifier.width(14.sdp))
                    ControlHint(Icons.Default.Forward10, "")
                    Spacer(Modifier.width(14.sdp))
                    ControlHint(Icons.Default.SwapVert, "")
                    Spacer(Modifier.width(14.sdp))
                    ControlHint(Icons.AutoMirrored.Filled.List, "")
                    Spacer(Modifier.weight(1f))
                }
            }
        }

        // ---------- 选集面板 ----------
        AnimatedVisibility(
            visible = listVisible,
            enter = fadeIn(tween(150)) + slideInHorizontally(tween(180)) { it },
            exit = fadeOut(tween(200)) + slideOutHorizontally(tween(200)) { it },
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        ) {
            EpisodePanel(
                episodes = request.episodes,
                current = index,
                onPick = { i ->
                    listVisible = false
                    onSwitchEpisode(request.copy(index = i))
                },
            )
        }
    }
}

@Composable
private fun ControlHint(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = Ink.TextTertiary,
            modifier = Modifier.width(17.sdp).height(17.sdp),
        )
        Spacer(Modifier.width(5.sdp))
        Text(label, color = Ink.TextTertiary, fontSize = Txt.Tiny)
    }
}

/** ↑↓ 图标的替代（Material 没有现成的上下箭头组合） */
@Composable
private fun EpisodePanel(
    episodes: List<com.chinut.bawantv.unified.Episode>,
    current: Int,
    onPick: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(current) {
        runCatching { listState.scrollToItem((current - 4).coerceAtLeast(0)) }
    }
    Column(
        Modifier
            .width(320.sdp)
            .fillMaxHeight()
            .background(Ink.Sheet.copy(alpha = 0.94f))
            .padding(vertical = 20.sdp),
    ) {
        Text(
            "选集 · 共 ${episodes.size} 集",
            color = Color.White,
            fontSize = Txt.Body,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.sdp),
        )
        Spacer(Modifier.height(10.sdp))
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.sdp),
            verticalArrangement = Arrangement.spacedBy(4.sdp),
        ) {
            itemsIndexed(episodes) { i, ep ->
                val f = rememberTvFocusState()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .tvFocusable(
                            focusState = f,
                            shape = RoundedCornerShape(9.sdp),
                            focusedScale = 1.03f,
                            glow = false,
                            borderWidth = 2.dp,
                            baseBackground = if (i == current) Ink.AccentSoft else Color.Transparent,
                            focusedBackground = Ink.CardStrong,
                            onClick = { onPick(i) },
                        )
                        .padding(horizontal = 14.sdp, vertical = 11.sdp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        ep.name,
                        color = if (i == current) Ink.AccentBright else Ink.TextSecondary,
                        fontSize = Txt.Label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun fmt(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

// ==================== 进度拖动的阻尼参数 ====================

/**
 * 第一档步长：轻点一下跳 10 秒 —— 方便精确微调到想看的台词/镜头。
 */
private const val TAG_VOD = "BawanVodPlayer"
private const val SCRUB_MIN_MS = 10_000L

/**
 * 步长上限：按住不放最快 120 秒/次。
 * 再大就会"一跳跳过一整段剧情"，反而不好定位。
 */
private const val SCRUB_MAX_MS = 120_000L

/** 每次连续按的步长放大倍数（阻尼加速的斜率）。 */
private const val SCRUB_GROWTH = 1.35

/** 两次按键间隔小于这个值就算"连续按"，步长才继续放大。 */
private const val SCRUB_CHAIN_MS = 900L

/** 停手多久后真正 seek。太短会在连按中途触发（等于没做阻尼）。 */
private const val SCRUB_COMMIT_DELAY_MS = 600L

/**
 * 短剧两侧的**遥控器按键提示**（不是按钮）。
 *
 * # 为什么是"提示"而不是"按钮"
 *
 * 第一版把它做成了 `Modifier.clickable` 的按钮 —— 结果遥控器根本够不着
 * （只能触摸/鼠标点），而且它调用的 `step()` 和 ↑↓ 完全一样，
 * **没提供任何新能力**；更糟的是它长得像"要用方向键去选中它"，会误导用户。
 *
 * 遥控器的真实映射是：
 * ```
 *     ← →   拖动进度
 *     ↑ ↓   切上一集 / 下一集     ← 切集在这里
 *     确定   暂停 / 恢复
 *     三横键  选集列表
 * ```
 *
 * 所以两侧应该做的是**把这个映射告诉用户** —— 对着 2~3 米外、
 * 眼神不好的老人，"按遥控器上键"比一个点不到的按钮有用得多。
 *
 * [enabled] 为 false 时整体压暗，并给出原因（"已是第一集"），
 * 而不是让用户按了没反应。
 */
@Composable
private fun SideKeyHint(
    keyGlyph: String,
    keyName: String,
    label: String,
    hint: String,
    enabled: Boolean,
) {
    val tint = if (enabled) Color.White else Ink.TextFaint
    val bg = if (enabled) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.03f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(18.sdp))
            .background(bg)
            .padding(horizontal = 20.sdp, vertical = 22.sdp),
    ) {
        // 遥控器上的那个键（画大一点，一眼能认）
        Text(keyGlyph, color = tint, fontSize = 34.ssp)
        Spacer(Modifier.height(6.sdp))
        Text(
            "按$keyName",
            color = if (enabled) Ink.AccentBright else Ink.TextFaint,
            fontSize = Txt.Tiny,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(10.sdp))
        Text(
            label,
            color = tint,
            fontSize = Txt.Label,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.sdp))
        Text(
            hint,
            color = Ink.TextTertiary,
            fontSize = Txt.Tiny,
        )
    }
}
