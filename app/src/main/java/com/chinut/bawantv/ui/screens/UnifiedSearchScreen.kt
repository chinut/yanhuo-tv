package com.chinut.bawantv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.chinut.bawantv.core.Pinyin
import com.chinut.bawantv.ui.theme.AmbientBackdrop
import com.chinut.bawantv.ui.theme.Dim
import com.chinut.bawantv.ui.theme.Ink
import com.chinut.bawantv.ui.theme.Txt
import com.chinut.bawantv.ui.theme.focusBorder
import com.chinut.bawantv.ui.theme.frostedGlass
import com.chinut.bawantv.ui.theme.rememberTvFocusState
import com.chinut.bawantv.ui.theme.sdp
import com.chinut.bawantv.ui.theme.ssp
import com.chinut.bawantv.ui.theme.tvFocusable
import com.chinut.bawantv.unified.UnifiedMovie

/**
 * 搜索结果项：一个「词」候选。
 *
 * 为什么要有这一层：用户打 `ld` 时，直接命中《鹿鼎记》当然好，
 * 但很多时候打的是**半个词**（`ld` 想打「鹿鼎」而不是整个片名）。
 * 这时把库里所有以 `ld` 开头的首字母片段列出来，
 * 用户点一下就能**收窄成精确搜索** —— 这就是「先选词，再精确搜」。
 */
private data class WordCandidate(
    /** 展示用的拼音串，例如 "ld" / "ldj"。 */
    val pinyin: String,
    /** 这个拼音对应了多少部片子（给用户一个数量感）。 */
    val count: Int,
    /** 点它之后用来精确搜索的关键词。 */
    val query: String,
)

/**
 * 统一搜索界面（拼音首字母）。
 *
 * ## 交互流程
 *
 * ```
 *   用户按字母  →  实时联想
 *                   ├─ 左列：直接命中的影视（拼音首字母 或 汉字子串）
 *                   └─ 右列：候选**词**（以当前输入开头的首字母片段）
 *   选中任意一项 →  精确搜索 / 直接进详情
 * ```
 *
 * 「软件提供可能的词语」就落在右列：候选词是从**真实片库**里统计出来的，
 * 不是凭空猜的，所以点了一定有结果。
 *
 * ## 为什么右列要按片名分组统计
 *
 * 打 `l` 时会冒出几百个前缀，没有意义。所以：
 *  - 只取长度 ≥ 2 的前缀（单个字母太宽泛）
 *  - 按「有多少部片子共享这个前缀」排序，数量多的排前面
 *  - 最多显示 [MAX_WORDS] 个，避免列表刷屏
 */
@Composable
fun UnifiedSearchScreen(
    entryKey: Any,
    /** 直接打开某部作品的详情。 */
    onPickMovie: (UnifiedMovie) -> Unit,
    /** 用关键词精确搜索（跳到结果列表）。 */
    onSearch: (String) -> Unit,
    /** 返回影视首页。 */
    onExit: () -> Unit,
) {
    // 当前输入（拼音字母 或 汉字）
    var query by remember { mutableStateOf("") }
    var showKeyboard by remember { mutableStateOf(false) }

    // 接收遥控器/键盘的字母数字键。
    // 没有这一步的话，字母键会被 Activity 丢给 super 而不进输入框，
    // 用户只能用屏幕软键盘一个字母一个字母点。
    com.chinut.bawantv.ui.RegisterTextInput(
        onChar = { c ->
            // 只收字母和数字：搜索关键词就是拼音首字母/片名
            if (c.isLetterOrDigit()) query = (query + c.lowercaseChar()).take(24)
        },
        onBackspace = { if (query.isNotEmpty()) query = query.dropLast(1) },
    )

    // 片库快照：搜索的**数据源**。进来拉一页就够做联想了。
    var pool by remember { mutableStateOf<List<UnifiedMovie>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        loading = true
        // 搜索数据源 = 低端影视的本地片库（影视只有这一个来源）
        val fresh = runCatching {
            val lib = com.chinut.bawantv.unified.LibraryStore.load()
            if (lib.isNotEmpty()) lib
            else com.chinut.bawantv.unified.LibraryStore.refresh(pagesPerType = 3)
        }.getOrDefault(emptyList())
        if (fresh.isNotEmpty()) pool = fresh
        loading = false
    }

    // 命中结果 + 候选词。都用 remember(query, pool) 缓存，避免每次重组重算。
    val hits = remember(query, pool) {
        if (query.isBlank()) emptyList()
        else pool.filter { Pinyin.matches(query, it.title) }.take(60)
    }
    val words = remember(query, pool) {
        buildCandidates(query, pool)
    }

    // 诊断：把查询、片库规模、几个样本的首字母打出来，方便定位"搜不到"的原因。
    // 之前 `ldj` 搜不到《鹿鼎记》，光看界面无法判断是片库里没有、
    // 还是拼音转换出了问题 —— 有了这几行一眼就能分辨。
    LaunchedEffect(query, pool) {
        if (query.isNotBlank() && pool.isNotEmpty()) {
            val sample = pool.take(6).joinToString(" | ") {
                "${it.title}=>${Pinyin.initialsOf(it.title)}"
            }
            android.util.Log.i(
                "BawanSearch",
                "query=$query pool=${pool.size} hits=${hits.size} words=${words.size} sample=$sample",
            )
        }
    }

    AmbientBackdrop(Modifier.fillMaxSize(), accent = Ink.Accent, secondary = Ink.Pink) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(Dim.SafeH * 0.7f, Dim.SafeV * 0.8f),
        ) {
            SearchHeader(
                query = query,
                loading = loading,
                poolSize = pool.size,
                focusKey = entryKey,
                // 不再弹软键盘：字母键盘就在下方，常驻可见。
                // （遥控器自带的字母键也依然能直接输入。）
                onEdit = { showKeyboard = false },
                onClear = { query = "" },
                onExit = onExit,
            )

            Spacer(Modifier.height(16.sdp))

            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(18.sdp),
            ) {
                // 左：字母键盘（**常驻，不弹窗**）
                //
                // 用户要求照参考图做成这样：遥控器直接上下左右选字母、确定输入，
                // 不用再弹一个软键盘出来遮住结果。
                // 遥控器自带的字母键通道依然有效（见上面的 RegisterTextInput），
                // 两条路都能用 —— 有实体字母键的直接打，没有的用这个。
                LetterPad(
                    modifier = Modifier.width(350.sdp),
                    onLetter = { ch -> query = (query + ch).take(24) },
                    onBackspace = { query = query.dropLast(1) },
                    onClear = { query = "" },
                )
                // 中：直接命中的影视
                HitColumn(
                    hits = hits,
                    query = query,
                    loading = loading,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onPick = onPickMovie,
                )
                // 右：候选词（可从词再收窄搜索）
                WordColumn(
                    words = words,
                    modifier = Modifier.width(300.sdp).fillMaxHeight(),
                    onPick = { w -> query = w.pinyin; onSearch(w.query) },
                )
            }
        }
    }

    if (showKeyboard) {
        com.chinut.bawantv.ui.TvKeyboardDialog(
            title = "搜索影视（可输拼音首字母，如 lqy）",
            initial = query,
            onDismiss = { showKeyboard = false },
            onConfirm = {
                query = it
                showKeyboard = false
            },
        )
    }
}

/**
 * 从片库统计候选词。
 *
 * 取每个片名首字母串的 2~[MAX_WORD_LEN] 位前缀，按出现次数聚合。
 * 只保留**以当前输入开头**的那些 —— 这样列表里的每一项都是"接着打下去的可能"。
 */
private const val MAX_WORD_LEN = 8
private const val MAX_WORDS = 14

private fun buildCandidates(query: String, pool: List<UnifiedMovie>): List<WordCandidate> {
    if (pool.isEmpty()) return emptyList()
    val q = query.trim().lowercase()
    val counter = HashMap<String, Int>()

    // 把所有片名的首字母串的前缀都统计一遍（只做一次，与 query 无关的部分靠过滤）
    val initialsList = pool.map { Pinyin.initialsOf(it.title) }
    initialsList.forEach { ini ->
        val upper = minOf(ini.length, MAX_WORD_LEN)
        for (n in 2..upper) {
            val p = ini.substring(0, n)
            counter[p] = (counter[p] ?: 0) + 1
        }
    }

    return counter.entries
        .asSequence()
        // 没有输入时，给几个"热门词"充场面（取出现次数最多的短前缀）
        .filter { q.isBlank() || it.key.startsWith(q) }
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(MAX_WORDS)
        .map { WordCandidate(pinyin = it.key, count = it.value, query = it.key) }
        .toList()
}

// ==================== 顶部：输入状态 ====================

@Composable
private fun SearchHeader(
    query: String,
    loading: Boolean,
    poolSize: Int,
    focusKey: Any,
    onEdit: () -> Unit,
    onClear: () -> Unit,
    onExit: () -> Unit,
) {
    // 把用户输入的字母还原成"像哪几个字"的提示
    val hint = remember(query) {
        if (query.isBlank()) "" else "拼音首字母 · 共 ${query.length} 个字母"
    }

    Row(
        Modifier.fillMaxWidth().height(96.sdp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 返回
        CircleChip("←") { onExit() }
        Spacer(Modifier.width(14.sdp))

        // 输入框：点它用键盘改，也支持遥控器直接打字（MainActivity 会把字母键透传上来）
        val focus = rememberTvFocusState()
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .frostedGlass(shape = RoundedCornerShape(Dim.CardRadius)),
        ) {
            Row(
                Modifier
                    .fillMaxSize()
                    .tvFocusable(
                        focusState = focus,
                        focusKey = focusKey,
                        shape = RoundedCornerShape(Dim.CardRadius),
                        focusedScale = 1.0f,
                        glow = false,
                        borderWidth = 0.sdp,
                        baseBackground = Color.Transparent,
                        focusedBackground = Color.Transparent,
                        onClick = onEdit,
                    )
                    .padding(horizontal = 22.sdp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "搜索",
                    color = Ink.AccentBright,
                    fontSize = Txt.Label.ssp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(16.sdp))
                Text(
                    query.ifBlank { "用左侧键盘或遥控器字母键 · 支持拼音首字母" },
                    color = if (query.isBlank()) Ink.TextFaint else Color.White,
                    fontSize = if (query.isBlank()) Txt.Body.ssp else 34.ssp,
                    fontWeight = if (query.isBlank()) FontWeight.Normal else FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (query.isNotBlank()) {
                    Text(hint, color = Ink.TextFaint, fontSize = Txt.Tiny.ssp)
                    Spacer(Modifier.width(14.sdp))
                }
                if (loading) {
                    Text("片库加载中…", color = Ink.Amber, fontSize = Txt.Tiny.ssp)
                } else {
                    Text("片库 $poolSize 部", color = Ink.TextFaint, fontSize = Txt.Tiny.ssp)
                }
            }
        }

        if (query.isNotBlank()) {
            Spacer(Modifier.width(14.sdp))
            CircleChip("清空") { onClear() }
        }
    }
}

@Composable
private fun CircleChip(label: String, onClick: () -> Unit) {
    val f = rememberTvFocusState()
    Box(
        Modifier
            .height(52.sdp)
            .frostedGlass(shape = RoundedCornerShape(16.sdp))
            .tvFocusable(
                focusState = f,
                shape = RoundedCornerShape(16.sdp),
                focusedScale = 1.05f,
                glow = true,
                borderWidth = 0.sdp,
                baseBackground = Color.Transparent,
                focusedBackground = Color.Transparent,
                onClick = onClick,
            )
            .padding(horizontal = 20.sdp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (f.focused) Color.White else Ink.TextSecondary,
            fontSize = Txt.Label.ssp,
            fontWeight = FontWeight.Bold,
        )
    }
}

// ==================== 左列：命中的影视 ====================

@Composable
private fun HitColumn(
    hits: List<UnifiedMovie>,
    query: String,
    loading: Boolean,
    modifier: Modifier,
    onPick: (UnifiedMovie) -> Unit,
) {
    val listState = rememberLazyListState()
    Column(
        modifier
            .frostedGlass(shape = RoundedCornerShape(Dim.BigRadius))
            .padding(18.sdp),
    ) {
        SectionTitle(
            "匹配的影视",
            when {
                loading -> "正在拉取片库…"
                query.isBlank() -> "输入字母开始搜索"
                hits.isEmpty() -> "没有匹配，试试右边的候选词"
                else -> "${hits.size} 个结果"
            },
            Ink.Accent,
        )
        Spacer(Modifier.height(12.sdp))
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.sdp),
            contentPadding = PaddingValues(bottom = 8.sdp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(hits, key = { "hit:" + it.id }) { m ->
                HitRow(m, query) { onPick(m) }
            }
        }
    }
}

@Composable
private fun HitRow(m: UnifiedMovie, query: String, onClick: () -> Unit) {
    val f = rememberTvFocusState()
    val byPinyin = remember(query, m.title) { Pinyin.matchedByPinyin(query, m.title) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.sdp))
            .background(
                if (f.focused) Ink.Accent.copy(alpha = 0.22f)
                else Color.White.copy(alpha = 0.05f)
            )
            .tvFocusable(
                focusState = f,
                shape = RoundedCornerShape(14.sdp),
                focusedScale = 1.02f,
                glow = true,
                borderWidth = 0.sdp,
                baseBackground = Color.Transparent,
                focusedBackground = Color.Transparent,
                onClick = onClick,
            )
            .padding(horizontal = 16.sdp, vertical = 12.sdp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                m.title,
                color = Color.White,
                fontSize = Txt.CardTitle.ssp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.sdp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 拼音命中时把字母串亮出来，让用户明白"为什么这个结果会出现"
                if (byPinyin && query.isNotBlank()) {
                    Text(
                        Pinyin.initialsOf(m.title),
                        color = Ink.AccentBright,
                        fontSize = Txt.Caption.ssp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(10.sdp))
                }
                val meta = listOfNotNull(
                    m.year.takeIf { it.isNotBlank() },
                    m.typeName.takeIf { it.isNotBlank() },
                    "${m.sourceCount} 个源".takeIf { m.sourceCount > 0 },
                ).joinToString(" · ")
                Text(
                    meta,
                    color = Ink.TextFaint,
                    fontSize = Txt.Tiny.ssp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (m.score.isNotBlank()) {
            Spacer(Modifier.width(12.sdp))
            Text(
                m.score,
                color = Ink.Amber,
                fontSize = Txt.Label.ssp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

// ==================== 右列：候选词 ====================

@Composable
private fun WordColumn(
    words: List<WordCandidate>,
    modifier: Modifier,
    onPick: (WordCandidate) -> Unit,
) {
    val listState = rememberLazyListState()
    Column(
        modifier
            .frostedGlass(shape = RoundedCornerShape(Dim.BigRadius))
            .padding(18.sdp),
    ) {
        SectionTitle("候选词", "点一下按这个词精确搜索", Ink.Pink)
        Spacer(Modifier.height(12.sdp))
        if (words.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "片库为空，稍后再试",
                    color = Ink.TextFaint,
                    fontSize = Txt.Caption.ssp,
                )
            }
            return@Column
        }
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.sdp),
            contentPadding = PaddingValues(bottom = 8.sdp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(words, key = { "w:" + it.pinyin }) { w ->
                WordRow(w) { onPick(w) }
            }
        }
    }
}

@Composable
private fun WordRow(w: WordCandidate, onClick: () -> Unit) {
    val f = rememberTvFocusState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.sdp))
            .background(
                if (f.focused) Ink.Pink.copy(alpha = 0.22f)
                else Color.White.copy(alpha = 0.045f)
            )
            .tvFocusable(
                focusState = f,
                shape = RoundedCornerShape(12.sdp),
                focusedScale = 1.03f,
                glow = true,
                borderWidth = 0.sdp,
                baseBackground = Color.Transparent,
                focusedBackground = Color.Transparent,
                onClick = onClick,
            )
            .padding(horizontal = 14.sdp, vertical = 11.sdp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            w.pinyin,
            color = if (f.focused) Color.White else Ink.AccentBright,
            fontSize = 20.ssp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        Text(
            "${w.count} 部",
            color = Ink.TextFaint,
            fontSize = Txt.Tiny.ssp,
        )
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String, accent: Color) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .size(6.sdp, 22.sdp)
                .background(accent, RoundedCornerShape(3.sdp))
        )
        Spacer(Modifier.width(10.sdp))
        Text(title, color = Color.White, fontSize = Txt.Section.ssp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(12.sdp))
        Text(subtitle, color = Ink.TextFaint, fontSize = Txt.Tiny.ssp, maxLines = 1)
    }
}

// ==================== 字母键盘（常驻） ====================

/**
 * 常驻的字母键盘。
 *
 * 设计取舍：**不做弹窗**。
 *
 * 参考用户给的图（某电视应用）—— 字母表直接摆在页面上，用户按遥控器
 * 上下左右选、确定输入。比"先按确定弹出软键盘、输完再关掉"少两步，
 * 而且**不会遮住搜索结果**（输入的同时就能看到匹配变化）。
 *
 * 两列排布之外还铺了数字，是为了覆盖"片名里带数字"的情况（如"第2季"）。
 * 遥控器自带的字母键通道依然有效 —— 有实体字母键的用户直接打更快。
 */
@Composable
private fun LetterPad(
    modifier: Modifier,
    onLetter: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
) {
    // A-Z 按 6 列排；数字单独放在最后。
    val letters = ('A'..'Z').map { it.toString() }
    val digits = (1..9).map { it.toString() } + listOf("0")
    val cols = 6

    // **固定键宽**，不用 weight。
    // 用 weight 的话，最后一行只有 Y/Z 两个键，它们会被拉成两倍宽 —— 网格就不整齐了。
    val padH = 12.sdp          // 键盘内边距
    val gap = 6.sdp            // 键间距
    val keyW = (350.sdp - padH * 2 - gap * (cols - 1)) / cols

    Column(
        modifier
            .fillMaxHeight()
            .frostedGlass(shape = RoundedCornerShape(Dim.CardRadius))
            .padding(12.sdp),
    ) {
        Text("字母键盘", color = Color.White, fontSize = Txt.Label, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.sdp))

        // A-Z
        letters.chunked(cols).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.sdp)) {
                row.forEach { ch ->
                    LetterKey(ch, Modifier.width(keyW)) { onLetter(ch.lowercase()) }
                }
            }
            Spacer(Modifier.height(5.sdp))
        }

        // 数字
        digits.chunked(cols).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.sdp)) {
                row.forEach { ch ->
                    LetterKey(ch, Modifier.width(keyW)) { onLetter(ch) }
                }
                // 补满整行，保持网格整齐
                repeat(cols - row.size) { Spacer(Modifier.width(padH + gap)) }
            }
            Spacer(Modifier.height(5.sdp))
        }

        Spacer(Modifier.weight(1f))

        // 操作键
        Row(horizontalArrangement = Arrangement.spacedBy(6.sdp)) {
            ActionKey("删除", Modifier.weight(1f), Ink.Amber, onBackspace)
            ActionKey("清空", Modifier.weight(1f), Ink.Pink, onClear)
        }
    }
}

/** 一个字母/数字键。 */
@Composable
private fun LetterKey(label: String, modifier: Modifier, onClick: () -> Unit) {
    val f = rememberTvFocusState()
    Box(
        modifier
            .height(38.sdp)
            .clip(RoundedCornerShape(9.sdp))
            .background(
                if (f.focused) Ink.Accent.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.07f)
            )
            .focusBorder(
                visible = f.focused,
                cornerRadius = 10.sdp,
                color = Ink.AccentBright,
                width = 3.sdp,
            )
            .tvFocusable(
                focusState = f,
                shape = RoundedCornerShape(10.sdp),
                focusedScale = 1.10f,
                glow = false,
                borderWidth = 0.sdp,
                baseBackground = Color.Transparent,
                focusedBackground = Color.Transparent,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = Color.White,
            fontSize = Txt.Label,
            fontWeight = if (f.focused) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/** 键盘底部的操作键（删除/清空）。 */
@Composable
private fun ActionKey(
    label: String,
    modifier: Modifier,
    accent: Color,
    onClick: () -> Unit,
) {
    val f = rememberTvFocusState()
    Box(
        modifier
            .height(40.sdp)
            .clip(RoundedCornerShape(11.sdp))
            .background(if (f.focused) accent.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.09f))
            .focusBorder(
                visible = f.focused,
                cornerRadius = 12.sdp,
                color = accent,
                width = 3.sdp,
            )
            .tvFocusable(
                focusState = f,
                shape = RoundedCornerShape(12.sdp),
                focusedScale = 1.08f,
                glow = false,
                borderWidth = 0.sdp,
                baseBackground = Color.Transparent,
                focusedBackground = Color.Transparent,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = Txt.Label, fontWeight = FontWeight.Bold)
    }
}

