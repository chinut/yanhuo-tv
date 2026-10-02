package com.chinut.bawantv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chinut.bawantv.ui.theme.Ink
import com.chinut.bawantv.ui.theme.sdp
import com.chinut.bawantv.ui.theme.ssp
import com.chinut.bawantv.ui.theme.Txt

/**
 * 遥控器方向键图形提示。
 *
 * 画一个遥控器上的「圆 + 四向箭头」，四个方向各配一句说明，
 * 让用户一眼看懂这个界面上方向键分别干什么 —— 比只写一行文字直观得多。
 *
 * 电视端常见做法：方向键的语义在不同界面会变（直播里 ↑↓ 换台、←→ 换源），
 * 所以做成通用组件，四向文字由调用方传。
 *
 * @param up/down/left/right 四个方向各自的说明文字
 * @param center 圆心里的字，默认「确定」
 * @param title 可选的小标题（例如「遥控器」）
 * @param compact true 时整体缩小，用于播放器浮层这种空间紧张的地方
 */
@Composable
fun DpadHint(
    up: String,
    down: String,
    left: String,
    right: String,
    modifier: Modifier = Modifier,
    center: String = "确定",
    title: String? = null,
    compact: Boolean = false,
    /**
     * 不画自己的底板。
     * 用在已经有卡片底的地方（例如直播播放页左下角的信息卡）——
     * 否则会「卡片里再嵌一层卡片」，看起来脏。
     */
    transparent: Boolean = false,
) {
    val ringSize = if (compact) 62.sdp else 76.sdp
    val labelSize = if (compact) Txt.Tiny else Txt.Caption
    val gap = if (compact) 8.sdp else 12.sdp
    val labelWidth = if (compact) 74.sdp else 92.sdp
    val shape = RoundedCornerShape(if (compact) 12.sdp else 16.sdp)

    Box(
        modifier
            .let { base ->
                if (transparent) {
                    base.padding(0.sdp)
                } else {
                    // 压在视频/亮色背景上时，半透明底会让文字糊掉，所以给一个更实的底 + 细边框
                    base
                        .background(Ink.Deep.copy(alpha = 0.82f), shape)
                        .border(1.dp, Ink.TextTertiary.copy(alpha = 0.30f), shape)
                        .padding(
                            horizontal = if (compact) 12.sdp else 16.sdp,
                            vertical = if (compact) 8.sdp else 12.sdp,
                        )
                }
            },
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (title != null) {
                Text(
                    title,
                    color = Ink.TextFaint,
                    fontSize = Txt.Tiny,
                    modifier = Modifier.padding(bottom = if (compact) 4.sdp else 7.sdp),
                )
            }

            // 上方说明
            Text(
                up,
                color = Ink.AccentBright,
                fontSize = labelSize,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(labelWidth),
            )

            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 左侧说明
                Text(
                    left,
                    color = Ink.AccentBright,
                    fontSize = labelSize,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(labelWidth),
                )

                androidx.compose.foundation.layout.Spacer(Modifier.width(gap))

                // 遥控器圆盘
                Box(
                    Modifier.size(ringSize),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val r = size.minDimension / 2f - 4f

                        // 外圈
                        drawCircle(
                            color = Ink.TextTertiary.copy(alpha = 0.45f),
                            radius = r,
                            center = Offset(cx, cy),
                            style = Stroke(width = 2.4f),
                        )
                        // 内圆点（确定键）
                        drawCircle(
                            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                                colors = listOf(Ink.AccentBright, Ink.Accent),
                                center = Offset(cx, cy),
                                radius = r * 0.42f,
                            ),
                            radius = r * 0.42f,
                            center = Offset(cx, cy),
                        )

                        // 四向箭头（三角）
                        val aw = r * 0.20f          // 箭头半宽
                        val ah = r * 0.22f          // 箭头高
                        fun arrow(tip: Offset, dir: Int) {
                            val p = Path()
                            p.moveTo(tip.x, tip.y)
                            when (dir) {
                                0 -> { // 上
                                    p.lineTo(tip.x - aw, tip.y + ah)
                                    p.lineTo(tip.x + aw, tip.y + ah)
                                }
                                1 -> { // 下
                                    p.lineTo(tip.x - aw, tip.y - ah)
                                    p.lineTo(tip.x + aw, tip.y - ah)
                                }
                                2 -> { // 左
                                    p.lineTo(tip.x + ah, tip.y - aw)
                                    p.lineTo(tip.x + ah, tip.y + aw)
                                }
                                else -> { // 右
                                    p.lineTo(tip.x - ah, tip.y - aw)
                                    p.lineTo(tip.x - ah, tip.y + aw)
                                }
                            }
                            p.close()
                            drawPath(p, color = Ink.AccentBright)
                        }

                        arrow(Offset(cx, cy - r * 0.80f), 0)
                        arrow(Offset(cx, cy + r * 0.80f), 1)
                        arrow(Offset(cx - r * 0.80f, cy), 2)
                        arrow(Offset(cx + r * 0.80f, cy), 3)
                    }

                    Text(
                        center,
                        color = Color.White,
                        fontSize = if (compact) 9.ssp else 11.ssp,
                        fontWeight = FontWeight.Bold,
                    )
                }

                androidx.compose.foundation.layout.Spacer(Modifier.width(gap))

                // 右侧说明
                Text(
                    right,
                    color = Ink.AccentBright,
                    fontSize = labelSize,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.width(labelWidth),
                )
            }

            // 下方说明
            Text(
                down,
                color = Ink.AccentBright,
                fontSize = labelSize,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = if (compact) 2.sdp else 4.sdp)
                    .width(labelWidth),
            )
        }
    }
}
