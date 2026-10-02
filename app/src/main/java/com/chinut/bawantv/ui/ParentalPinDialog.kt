package com.chinut.bawantv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.chinut.bawantv.core.ParentalControl
import com.chinut.bawantv.ui.theme.Dim
import com.chinut.bawantv.ui.theme.Ink
import com.chinut.bawantv.ui.theme.rememberTvFocusState
import com.chinut.bawantv.ui.theme.sdp
import com.chinut.bawantv.ui.theme.ssp
import com.chinut.bawantv.ui.theme.tvFocusable
import com.chinut.bawantv.ui.theme.Txt

/**
 * 家长 PIN 输入弹窗。
 *
 * 为什么需要一个真正的 PIN，而不是「设置里点一下开关」：
 * 如果关掉保护不需要任何凭据，孩子自己进设置点一下就破了，
 * 那这个功能就只是好看而已。所以：
 *  - 打开/关闭保护都要输 PIN
 *  - PIN 只存在本机
 *  - 校验通过只对**本次 App 会话**有效（内存变量），重启后要重新输
 *
 * 用自绘的九宫格数字键盘而不是系统输入法：TV 上系统键盘又丑又难用，
 * 而遥控器输入 4 位数字是件很轻松的事。
 *
 * @param title 弹窗标题（首次设置 / 校验 两种语境）
 * @param onDone 校验通过回调
 * @param onCancel 取消
 * @param verify 传入输入内容，返回是否正确；首次设置时直接返回 true
 */
@Composable
fun ParentalPinDialog(
    title: String,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    verify: (String) -> Boolean,
) {
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = true) { onCancel() }

    fun push(d: String) {
        if (input.length >= ParentalControl.PIN_LENGTH) return
        error = null
        val next = input + d
        input = next
        // 输满就立刻校验（不用再按一次确定）。
        // 注意放在事件处理里而不是组合期间：组合期间调 onDone() 会改上层状态，
        // 属于"组合中改状态"，容易触发重组的奇怪问题。
        if (next.length == ParentalControl.PIN_LENGTH) {
            if (verify(next)) {
                onDone()
            } else {
                error = "PIN 不对，请重试"
                input = ""
            }
        }
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .width(460.sdp)
                .background(Ink.Sheet, RoundedCornerShape(Dim.BigRadius))
                .padding(26.sdp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, color = Color.White, fontSize = Txt.Section, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.sdp))
            Text(
                "请输入 ${ParentalControl.PIN_LENGTH} 位数字密码",
                color = Ink.TextTertiary,
                fontSize = Txt.Caption,
            )

            Spacer(Modifier.height(18.sdp))

            // 四个圆点表示已输入几位
            Row(horizontalArrangement = Arrangement.spacedBy(16.sdp)) {
                repeat(ParentalControl.PIN_LENGTH) { i ->
                    Box(
                        Modifier
                            .width(22.sdp)
                            .height(22.sdp)
                            .background(
                                if (i < input.length) Ink.AccentBright else Ink.CardStrong,
                                RoundedCornerShape(11.sdp),
                            )
                            .border(
                                1.dp,
                                Ink.TextTertiary.copy(alpha = 0.4f),
                                RoundedCornerShape(11.sdp),
                            )
                    )
                }
            }

            error?.let {
                Spacer(Modifier.height(10.sdp))
                Text(it, color = Ink.Red, fontSize = Txt.Label)
            }

            Spacer(Modifier.height(20.sdp))

            // 九宫格数字键盘
            val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.sdp)) {
                    row.forEach { d -> PinKey(d) { push(d) } }
                }
                Spacer(Modifier.height(12.sdp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.sdp)) {
                PinKey("删除") { if (input.isNotEmpty()) input = input.dropLast(1) }
                PinKey("0") { push("0") }
                PinKey("取消", accent = false) { onCancel() }
            }
        }
    }
}

@Composable
private fun PinKey(label: String, accent: Boolean = true, onClick: () -> Unit) {
    val f = rememberTvFocusState()
    Box(
        Modifier
            .width(96.sdp)
            .height(56.sdp)
            .tvFocusable(
                focusState = f,
                shape = RoundedCornerShape(12.sdp),
                focusedScale = 1.08f,
                borderWidth = 3.dp,
                baseBackground = if (accent) Ink.Card else Ink.CardStrong,
                focusedBackground = Ink.AccentSoft,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (f.focused) Color.White else Ink.TextSecondary,
            fontSize = if (label.length > 1) Txt.Caption else 22.ssp,
            fontWeight = if (f.focused) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
