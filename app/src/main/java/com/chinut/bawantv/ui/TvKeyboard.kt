package com.chinut.bawantv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.chinut.bawantv.ui.theme.Dim
import com.chinut.bawantv.ui.theme.Ink
import com.chinut.bawantv.ui.theme.rememberTvFocusState
import com.chinut.bawantv.ui.theme.sdp
import com.chinut.bawantv.ui.theme.ssp
import com.chinut.bawantv.ui.theme.tvFocusable
import com.chinut.bawantv.ui.theme.Txt

/**
 * 电视端虚拟键盘。
 *
 * 需求里明确要求「输入逻辑和遥控器一致」，所以搜索不依赖系统输入法，
 * 而是给一个自己画的九宫格式键盘：全部按键都能用方向键走到，确定键输入，
 * 左上角是输入框，退格/清空/确认都在键盘里。
 *
 * 说明：这里只做英数字与常用符号。中文片名建议用「设置 → 手机调试」扫码后用手机输入，
 * 或在键盘上输入英文名（低端影视与多数影视接口都支持英文检索）。
 */
@Composable
fun TvKeyboardDialog(
    title: String = "输入关键词",
    initial: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val firstKeyFocus = remember { FocusRequester() }

    // ---------- 为什么不用 Compose 的 Dialog ----------
    //
    // Dialog 会开一个**独立窗口接管按键**，MainActivity.dispatchKeyEvent
    // 根本收不到 —— 而本应用整套遥控器操作全靠那个 dispatchKeyEvent 转发给
    // 自定义的 TvFocusManager。按键进不来，键盘里的按键就永远点不到，
    // 表现就是「弹出的键盘按返回关不掉」。
    //
    // 更新框踩过同一个坑，这里是同一类问题。
    //
    // 注意：设置页会把这个浮层放在 LazyColumn 的**外面**（与它平级），
    // 否则 fillMaxSize 拿到的是 LazyColumn item 的边界，浮层会被裁成窄条。
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(980.sdp)
                .background(Ink.Sheet, RoundedCornerShape(Dim.BigRadius))
                .padding(24.sdp),
        ) {
            Text(title, color = Color.White, fontSize = Txt.Section, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.sdp))

            // ---------- 输入框 ----------
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(62.sdp)
                    .background(Ink.Deep, RoundedCornerShape(14.sdp))
                    .padding(horizontal = 18.sdp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (text.isEmpty()) {
                    Text("用遥控器方向键选择字母，确定键输入", color = Ink.TextFaint, fontSize = Txt.Body)
                } else {
                    Text(
                        text,
                        color = Color.White,
                        fontSize = 26.ssp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(16.sdp))

            // ---------- 键盘本体 ----------
            val rows = KEY_ROWS
            val keyFocus = remember { FocusRequester() }
            var lastAction by remember { mutableIntStateOf(0) }

            Column(verticalArrangement = Arrangement.spacedBy(8.sdp)) {
                rows.forEachIndexed { rowIndex, row ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.sdp),
                    ) {
                        row.forEach { key ->
                            val f = rememberTvFocusState()
                            val weight = if (key == "SPACE") 4f else if (key == "DEL") 2f else 1f
                            var mod = Modifier
                                .weight(weight)
                                .height(58.sdp)
                                .tvFocusable(
                                    focusState = f,
                                    shape = RoundedCornerShape(11.sdp),
                                    focusedScale = 1.08f,
                                    borderWidth = 3.dp,
                                    baseBackground = Ink.Card,
                                    focusedBackground = Ink.AccentSoft,
                                    onClick = {
                                        when (key) {
                                            "DEL" -> if (text.isNotEmpty()) text = text.dropLast(1)
                                            "SPACE" -> text += " "
                                            else -> text += key
                                        }
                                        lastAction++
                                    },
                                )
                            if (rowIndex == 0 && key == row.first()) {
                                mod = mod.focusRequester(firstKeyFocus)
                            }
                            Box(mod, contentAlignment = Alignment.Center) {
                                Text(
                                    when (key) {
                                        "DEL" -> "退格"
                                        "SPACE" -> "空格"
                                        else -> key
                                    },
                                    color = if (f.focused) Color.White else Ink.TextSecondary,
                                    fontSize = if (key == "DEL" || key == "SPACE") Txt.Label else Txt.Body,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(4.sdp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.sdp)) {
                    KeyboardWide("清空", Ink.Card) { text = "" }
                    KeyboardWide("取消", Ink.Card) { onDismiss() }
                    KeyboardWide("搜索", Ink.Accent) { onConfirm(text.trim()) }
                }
            }
        }
    }

    LaunchedEffect(Unit) { runCatching { firstKeyFocus.requestFocus() } }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.KeyboardWide(
    label: String,
    color: Color,
    onClick: () -> Unit,
) {
    val f = rememberTvFocusState()
    Box(
        Modifier
            .weight(1f)
            .height(56.sdp)
            .tvFocusable(
                focusState = f,
                shape = RoundedCornerShape(12.sdp),
                focusedScale = 1.05f,
                borderWidth = 3.dp,
                baseBackground = color,
                focusedBackground = Ink.AccentSoft,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (f.focused) Color.White else Ink.TextSecondary,
            fontSize = Txt.Body,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** 键盘布局：数字行 + 字母三行 + 空格行 */
private val KEY_ROWS: List<List<String>> = listOf(
    listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
    listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
    listOf("a", "s", "d", "f", "g", "h", "j", "k", "l", "-"),
    listOf("z", "x", "c", "v", "b", "n", "m", ".", "DEL"),
    listOf("SPACE"),
)

/** 只读设置项：显示当前值，点击后弹出输入 */
@Composable
fun ReadonlyKeyValue(
    value: String,
    placeholder: String,
    onEditRequest: () -> Unit,
    modifier: Modifier = Modifier,
    maxLines: Int = 3,
) {
    val f = rememberTvFocusState()
    Box(
        modifier
            .fillMaxWidth()
            .tvFocusable(
                focusState = f,
                shape = RoundedCornerShape(12.sdp),
                focusedScale = 1.01f,
                glow = false,
                borderWidth = 2.dp,
                baseBackground = Ink.Deep,
                focusedBackground = Ink.CardStrong,
                onClick = onEditRequest,
            )
            .padding(horizontal = 14.sdp, vertical = 12.sdp),
    ) {
        Text(
            value.ifBlank { placeholder },
            color = if (value.isBlank()) Ink.TextFaint else Ink.TextSecondary,
            fontSize = Txt.Label,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 21.ssp,
        )
    }
}

/** 一个可以左右切换的开关（TV 上用确定键直接切换，比手机上的滑动条好按） */
@Composable
fun TvSwitch(
    label: String,
    hint: String,
    checked: Boolean,
    /** 本行的焦点 key（供上下邻居指向它）。 */
    focusKey: Any? = null,
    /** 显式指定的上下邻居，避免几何导航跳过这一行（见 tvFocusable 的说明）。 */
    upKey: Any? = null,
    downKey: Any? = null,
    onToggle: (Boolean) -> Unit,
) {
    val f = rememberTvFocusState()
    Row(
        Modifier
            .fillMaxWidth()
            .tvFocusable(
                focusState = f,
                focusKey = focusKey,
                upKey = upKey,
                downKey = downKey,
                // 开关属于操作类控件：原来的 glow=false + 半透明底
                // 导致聚焦时几乎看不出变化（用户反馈"开关部分焦点很不明显"）。
                // 现在改成实心底色 + 粗边框 + 光晕，聚焦时整行明显点亮。
                action = true,
                shape = RoundedCornerShape(12.sdp),
                focusedScale = 1f,
                borderWidth = 3.dp,
                // 未聚焦完全干净（无底色无边框），聚焦时才整行点亮。
                // 比"每行都挂一圈边"干净得多 —— 后者满屏都是框，反而看不出焦点在哪。
                // 另外不做放大：1.01 虽然很小，但会让聚焦行比别的行宽一点，
                // 右边看起来"缩进去一截"。
                baseBackground = Color.Transparent,
                focusedBackground = Ink.ActionFocus,
                onClick = { onToggle(!checked) },
            )
            .padding(horizontal = 14.sdp, vertical = 12.sdp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                color = if (f.focused) Color.White else Ink.TextSecondary,
                fontSize = Txt.Label,
            )
            if (hint.isNotBlank()) {
                Text(hint, color = Ink.TextFaint, fontSize = Txt.Tiny, lineHeight = 17.ssp)
            }
        }
        Spacer(Modifier.width(14.sdp))
        Box(
            Modifier
                .width(58.sdp)
                .height(30.sdp)
                .background(
                    if (checked) Ink.Accent.copy(alpha = 0.9f) else Ink.CardStrong,
                    RoundedCornerShape(15.sdp),
                ),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .padding(horizontal = 4.sdp)
                    .width(22.sdp)
                    .height(22.sdp)
                    .background(if (checked) Ink.Deep else Color.White, RoundedCornerShape(11.sdp))
            )
        }
    }
}

/** 一个可以左右切换的开关（TV 上用确定键直接切换，比手机上的滑动条好按） */
