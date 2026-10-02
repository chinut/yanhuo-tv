package com.chinut.bawantv.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.chinut.bawantv.ui.theme.frostedGlass
import com.chinut.bawantv.ui.theme.Ink
import com.chinut.bawantv.ui.theme.rememberTvFocusState
import com.chinut.bawantv.ui.theme.sdp
import com.chinut.bawantv.ui.theme.ssp
import com.chinut.bawantv.ui.theme.tvFocusable
import com.chinut.bawantv.ui.theme.Txt

/**
 * 播放器错误页上的操作按钮（重试 / 下一集 等）。
 *
 * 抽成公共组件的原因：它原先定义在一个已被删除的旧界面文件里，
 * 而 ``VodPlayerScreen`` 还在用它 —— 放进公共组件目录，
 * 以后清理界面就不会再连带删掉正在用的东西。
 *
 * 焦点态沿用全局视觉语言：光晕 + 提亮，不描实线。
 */
@Composable
fun PlayButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = rememberTvFocusState()
    val shape = RoundedCornerShape(14.sdp)

    Box(
        modifier
            .frostedGlass(shape = shape, strong = true)
            .tvFocusable(
                focusState = focus,
                shape = shape,
                focusedScale = 1.06f,
                glow = true,
                borderWidth = 0.sdp,
                baseBackground = Color.Transparent,
                focusedBackground = Color.Transparent,
                onClick = onClick,
            )
            .padding(horizontal = 24.sdp, vertical = 12.sdp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (focus.focused) Color.White else Ink.TextSecondary,
            fontSize = Txt.Label.ssp,
            fontWeight = if (focus.focused) FontWeight.Bold else FontWeight.Medium,
        )
    }
}
