package com.chinut.bawantv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chinut.bawantv.BawanApp
import com.chinut.bawantv.live.LiveCatalog
import com.chinut.bawantv.live.LiveChannel
import com.chinut.bawantv.live.LiveGroup
import com.chinut.bawantv.ui.SectionEmpty
import com.chinut.bawantv.ui.SectionLoading
import com.chinut.bawantv.ui.theme.Dim
import com.chinut.bawantv.ui.theme.FocusKeys
import com.chinut.bawantv.ui.theme.Ink
import com.chinut.bawantv.ui.theme.LocalTvFocusManager
import com.chinut.bawantv.ui.theme.rememberTvFocusState
import com.chinut.bawantv.ui.theme.sdp
import com.chinut.bawantv.ui.theme.tvFocusable
import com.chinut.bawantv.ui.theme.Txt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 板块 A：直播。
 *
 * 交互设计（对齐老电视的使用直觉）：
 *  - 进来就是频道墙，按确定键直接开播，不需要二次确认
 *  - 顶部一行是分组（央视 / 卫视 / 各省……），左右键切换
 *  - 播放后上下键换台，左右键调音量，遥控器不需要瞄准任何按钮
 *  - 同名频道合并成「一个台 + 备用源」，网格里只显示一格
 */
@Composable
fun LiveScreen(
    entryKey: Any,
    onPlayingChanged: (LiveChannel, List<LiveChannel>) -> Unit,

    /**
     * 调试：频道表就绪后自动起播第一个频道。
     *
     * 只为自动化验证服务（`am broadcast --es route live_play`）——
     * 验证播放页时靠方向键盲按选台经常落错位置。正常使用恒为 false。
     */
    debugAutoPlayFirst: Boolean = false,
    /** 返回键：回首页 */
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val prefs = BawanApp.prefs
    val scope = rememberCoroutineScope()
    // 返回键回首页（首页就是那个大直播画面）
    BackHandler(enabled = onBack != null) { onBack?.invoke() }

    val manager = LocalTvFocusManager.current

    var groups by remember { mutableStateOf<List<LiveGroup>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    // 默认看第一个分组（老电视「打开就是台」）
    var selectedGroup by remember { mutableIntStateOf(0) }

    suspend fun reload() {
        loading = true
        val loaded = withContext(Dispatchers.IO) {
            runCatching { LiveCatalog.load(context, prefs.liveSourceUrl) }.getOrDefault(emptyList())
        }
        groups = loaded
        if (loaded.isNotEmpty() && selectedGroup >= loaded.size) selectedGroup = 0
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    if (loading) {
        SectionLoading("正在读取频道表…")
        return
    }
    if (groups.isEmpty()) {
        SectionEmpty("频道表为空，去「设置」里填一个直播源地址")
        return
    }

    /**
     * 全部频道（跨分组合并、按台名去重）。
     *
     * 播放时的「换台表」就是它 —— 用户按上下键能一路从央视换到地方台，
     * 走到头自动绕回第一个，跟老电视的体验一致。
     * 去重是为了避免同一个台在整表里出现两次（合并来源后同名台会有多条）。
     */
    val allChannels = remember(groups) {
        val uniq = LinkedHashMap<String, LiveChannel>()
        groups.flatMap { it.channels }.forEach { c ->
            val k = LiveCatalog.normalizeName(c.name)
            val exist = uniq[k]
            if (exist == null) {
                uniq[k] = c
            } else {
                // 同名合并：把后面那条的来源并入前一条，避免丢源
                val alts = (exist.alternates + c.url + c.alternates)
                    .filter { it != exist.url }
                    .distinct()
                uniq[k] = exist.copy(alternates = alts)
            }
        }
        uniq.values.toList()
    }

    // 调试自动起播：频道表就绪后直接播第一个频道（见 debugAutoPlayFirst）。
    // 仅用于自动化验证 —— 验证播放页时靠方向键盲按选台经常落错位置。
    androidx.compose.runtime.LaunchedEffect(debugAutoPlayFirst, allChannels) {
        if (!debugAutoPlayFirst) return@LaunchedEffect
        val first = allChannels.firstOrNull() ?: return@LaunchedEffect
        kotlinx.coroutines.delay(800)
        android.util.Log.i("BawanRoute", "live_play：起播 " + first.name)
        onPlayingChanged(first, allChannels)
    }

    /** 按频道名去重后的台数（合并来源后，同一台只算一个）。 */
    fun uniqueCount(list: List<LiveChannel>): Int =
        list.map { LiveCatalog.normalizeName(it.name) }.distinct().size

    val rawShown: List<LiveChannel> = groups.getOrNull(selectedGroup)?.channels.orEmpty()

    /**
     * 网格里同名频道只显示一次。
     *
     * 频道表合并后，同一家电视台会有多条来源（例如 CCTV-13 同时有 tv.cctv.com
     * 和央视频两个网页）。列表要把它们折叠成「一个台 + 备用源」，而不是并排两格：
     * 既看不懂，又会因为 key 重复让 LazyGrid 直接崩溃（踩过这个坑）。
     */
    val shown = remember(rawShown) {
        val out = ArrayList<LiveChannel>(rawShown.size)
        val index = HashMap<String, Int>()
        rawShown.forEach { ch ->
            val key = LiveCatalog.normalizeName(ch.name)
            val at = index[key]
            if (at == null) {
                index[key] = out.size
                out.add(ch)
            } else {
                val first = out[at]
                val alts = (first.alternates + ch.url + ch.alternates)
                    .filter { it != first.url }
                    .distinct()
                out[at] = first.copy(alternates = alts)
            }
        }
        out
    }

    // 分组下标失效时回到第一个分组
    LaunchedEffect(groups.size) {
        if (groups.isNotEmpty() && selectedGroup >= groups.size) selectedGroup = 0
    }

    Column(Modifier.fillMaxSize()) {
        // ---------- 标题 ----------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.LiveTv,
                contentDescription = null,
                tint = Ink.AccentBright,
                modifier = Modifier.width(30.sdp).height(30.sdp),
            )
            Spacer(Modifier.width(12.sdp))
            Text("电视直播", color = Color.White, fontSize = Txt.Title, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(14.sdp))
            Text(
                "${uniqueCount(allChannels)} 个频道 · 按「确定」立即观看，播放中「↑ ↓」换台",
                color = Ink.TextTertiary,
                fontSize = Txt.Label,
            )
            Spacer(Modifier.weight(1f))
            IconAction(icon = Icons.Default.Refresh, label = "重新加载") {
                scope.launch { reload() }
            }
        }

        Spacer(Modifier.height(14.sdp))

        // ---------- 遥控器方向键图示 ----------
        // 直播页方向键的语义和别处不一样（↑↓ 换台、←→ 换源），
        // 所以这里放一张遥控器圆盘图，把四个方向各干什么直接画出来。
        com.chinut.bawantv.ui.DpadHint(
            up = "上一个台",
            down = "下一个台",
            left = "上一个源",
            right = "下一个源",
            center = "确定",
            title = "遥控器 · 播放中",
            modifier = Modifier.padding(bottom = 4.sdp),
        )

        Spacer(Modifier.height(14.sdp))

        // ---------- 分组 chips ----------
        val chipListState = rememberLazyListState()
        LazyRow(
            state = chipListState,
            horizontalArrangement = Arrangement.spacedBy(10.sdp),
            contentPadding = PaddingValues(end = 24.sdp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(groups.size) { i ->
                val g = groups[i]
                GroupChip(
                    text = "${g.name} ${uniqueCount(g.channels)}",
                    selected = i == selectedGroup,
                    onClick = { selectedGroup = i },
                )
            }
        }

        Spacer(Modifier.height(16.sdp))

        // ---------- 频道墙 ----------
        if (shown.isEmpty()) {
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "这个分组暂时没有频道",
                        color = Ink.TextSecondary,
                        fontSize = Txt.Section,
                    )
                    Spacer(Modifier.height(10.sdp))
                    Text(
                        "换一个分组看看",
                        color = Ink.TextFaint,
                        fontSize = Txt.Label,
                    )
                }
            }
            return@Column
        }

        val gridState = rememberLazyGridState()
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 208.sdp),
            contentPadding = PaddingValues(bottom = 24.sdp),
            horizontalArrangement = Arrangement.spacedBy(14.sdp),
            verticalArrangement = Arrangement.spacedBy(14.sdp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(shown, key = { it.url + "|" + LiveCatalog.normalizeName(it.name) }) { ch ->
                ChannelCard(
                    channel = ch,
                    focusKey = if (ch == shown.first()) entryKey else null,
                    onClick = {
                        // 换台列表 = **全频道**（所有分组合并、同名去重），
                        // 这样上下键可以像老电视一样一路往下换，走到头自动绕回第一个。
                        // 不传当前分组，免得"只能在央视里转"。
                        onPlayingChanged(ch, allChannels)
                    },
                    onLeft = {
                        // 内容区最左侧按「左」回导航栏
                        manager?.moveTo(FocusKeys.nav("live"))
                        true
                    },
                )
            }
        }
    }
}

// ==================== 小组件 ====================

@Composable
private fun GroupChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val focus = rememberTvFocusState()
    Box(
        Modifier
            .height(42.sdp)
            .tvFocusable(
                focusState = focus,
                shape = RoundedCornerShape(Dim.ChipRadius),
                focusedScale = 1.06f,
                glow = false,
                borderWidth = 2.dp,
                baseBackground = if (selected) Ink.AccentSoft else Ink.Card,
                focusedBackground = if (selected) Ink.AccentSoft else Ink.CardStrong,
                onClick = onClick,
            )
            .padding(horizontal = 18.sdp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = when {
                selected -> Ink.AccentBright
                focus.focused -> Color.White
                else -> Ink.TextSecondary
            },
            fontSize = Txt.Label,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
private fun ChannelCard(
    channel: LiveChannel,
    focusKey: Any?,
    onClick: () -> Unit,
    onLeft: (() -> Boolean)? = null,
) {
    val focus = rememberTvFocusState()
    val mod = Modifier
        .fillMaxWidth()
        .height(96.sdp)
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
        )

    Column(
        mod.padding(horizontal = 14.sdp, vertical = 12.sdp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                channel.name,
                color = if (focus.focused) Color.White else Ink.TextSecondary,
                fontSize = Txt.CardTitle,
                fontWeight = if (focus.focused) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(5.sdp))
        Text(
            buildString {
                append(channel.group)
                if (channel.alternates.isNotEmpty()) {
                    append("  ·  备用源 ${channel.alternates.size}")
                }
            },
            color = Ink.TextFaint,
            fontSize = Txt.Caption,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun IconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val focus = rememberTvFocusState()
    Row(
        Modifier
            .height(40.sdp)
            .tvFocusable(
                focusState = focus,
                shape = RoundedCornerShape(Dim.ChipRadius),
                focusedScale = 1.05f,
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
            tint = if (focus.focused) Color.White else Ink.TextSecondary,
            modifier = Modifier.width(18.sdp).height(18.sdp),
        )
        Spacer(Modifier.width(7.sdp))
        Text(label, color = Ink.TextSecondary, fontSize = Txt.Caption, maxLines = 1)
    }
}
