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
import com.chinut.bawantv.ui.theme.sdp
import com.chinut.bawantv.ui.theme.tvFocusable
import com.chinut.bawantv.ui.theme.Txt
import com.chinut.bawantv.vod.VodRepo
import com.chinut.bawantv.vod.VodResolver
import kotlinx.coroutines.delay

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
    request: PlayingEpisode,
    onClose: () -> Unit,
    onSwitchEpisode: (PlayingEpisode) -> Unit,
) {
    val context = LocalContext.current
    val prefs = BawanApp.prefs

    var index by remember(request) {
        // 断点续播：上次看到第几集，打开时就直接从那一集开始
        val saved = WatchHistory.find(WatchHistory.KIND_VOD, request.vodId)
        val start = saved?.episodeIndex?.takeIf { it in request.episodes.indices } ?: request.index
        mutableIntStateOf(start.coerceIn(0, request.episodes.lastIndex.coerceAtLeast(0)))
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
    var buffered by remember { mutableLongStateOf(0L) }
    var retry by remember { mutableIntStateOf(0) }

    val episode = request.episodes.getOrNull(index) ?: request.episodes.firstOrNull()
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
                    // 起播前至少攒够 20 秒
                    .setBufferDurationsMs(
                        /* minBufferMs = */ 25_000,
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

    // ---------- 解析剧集地址 ----------
    LaunchedEffect(episode?.url, retry) {
        val raw = episode?.url ?: return@LaunchedEffect
        resolving = true
        failed = null
        url = null
        val resolver = VodRepo.resolver(request.flag)
        val resolved = runCatching { resolver.resolve(raw, request.flag) }.getOrNull()
        if (resolved == null) {
            resolving = false
            failed = if (raw.startsWith("http") && raw.contains(".")) {
                "解析失败：该线路的播放地址需要对应解析接口，可在「设置 → 解析接口」里补充"
            } else {
                "解析失败：地址格式无法识别"
            }
        } else {
            url = resolved.url
            headerMap = resolved.headers
            via = resolved.via
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

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                isPlaying = player.isPlaying
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
                kind = WatchHistory.KIND_VOD,
                id = request.vodId,
                title = request.title,
                poster = request.poster,
                episodeIndex = index,
                episodeName = episode?.name.orEmpty(),
                flag = request.flag,
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

    // ---------- 观看记录 / 断点续播 / 自动联播 ----------
    // 和板块 C 共用同一套逻辑（见 ui/player/WatchProgress.kt）
    val episodeUrls = remember(request.episodes) { request.episodes.map { it.url } }
    rememberWatchProgress(
        player = player,
        activeUrl = url,
        isLiveStream = false,
        episodeUrls = episodeUrls,
        identityId = request.vodId,
        kind = WatchHistory.KIND_VOD,
        title = request.title,
        poster = request.poster,
        flag = request.flag,
        episodeName = episode?.name.orEmpty(),
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

    fun step(delta: Int) {
        val next = index + delta
        if (next !in request.episodes.indices) return
        onSwitchEpisode(request.copy(index = next))
    }

    BackHandler {
        when {
            listVisible -> listVisible = false
            else -> onClose()
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
                        seekBy(-10_000); true
                    }

                    Key.DirectionRight -> {
                        seekBy(10_000); true
                    }

                    Key.DirectionUp -> {
                        step(-1); true
                    }

                    Key.DirectionDown -> {
                        step(1); true
                    }

                    Key.Enter, Key.DirectionCenter, Key.NumPadEnter -> {
                        if (hudVisible && !listVisible) {
                            togglePlay()
                        } else {
                            hudVisible = true
                        }
                        true
                    }

                    Key.MediaPlayPause -> {
                        togglePlay(); true
                    }

                    Key.Menu, Key.M -> {
                        listVisible = !listVisible; true
                    }

                    else -> false
                }
            }
    ) {
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
                    Row(horizontalArrangement = Arrangement.spacedBy(12.sdp)) {
                        PlayButton("重试", onClick = { retry++ })
                        PlayButton("下一集", onClick = { step(1) })
                    }
                }
            }
        }

        // ---------- 常驻的极简进度指示 ----------
        //
        // 控制条收起后，底部只留一条细线 + 一个亮点：
        // 既能一眼看出「播到哪了」，又不会挡画面。
        // 用户一按左右键，完整的控制条就会重新出现（见 seekBy）。
        if (!hudVisible && failed == null && duration > 0L) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(3.sdp)
                    .background(Ink.Deep.copy(alpha = 0.55f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth((position.toFloat() / duration).coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(Ink.AccentBright.copy(alpha = 0.9f))
                )
                // 进度头部的小亮点，像播放器的"光标"
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth((position.toFloat() / duration).coerceIn(0f, 1f))
                ) {
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .size(9.sdp)
                            .background(Ink.AccentBright, RoundedCornerShape(5.sdp))
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
                        if (via.isNotBlank()) append("   ·   $via")
                        // 显示来源名，而不是解析器标识（后者是内部开关，写出来只会让人困惑）
                        val src = request.sourceLabel.ifBlank { request.flag }
                        if (src.isNotBlank()) append("   ·   $src")
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
    episodes: List<com.chinut.bawantv.vod.VodEpisode>,
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
