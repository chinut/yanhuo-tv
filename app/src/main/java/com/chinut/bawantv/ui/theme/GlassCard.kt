package com.chinut.bawantv.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * **液态玻璃卡片**材质。
 *
 * # 它是什么（以及不是什么）
 *
 * **不是**"背景模糊"——那需要 `RenderEffect`，是 API 31+ 的 API，
 * 用户的电视是 Android 6，根本没有。（`frostedGlass` 的注释里也写过这点。）
 *
 * 所以这里的"玻璃感"是用**光学代理**做出来的：
 *
 *   1. 半透明底色（能透出背后的霞光颜色）
 *   2. 顶部一条更亮的竖向渐变 —— 模拟玻璃厚度在顶边聚光
 *   3. 一圈**极淡的白色描边** —— 玻璃棱边反光
 *   4. 底部一条**暖色内高光** —— 让背后的夕照从下缘渗进来
 *   5. 两层弥散阴影 —— 让卡片"浮"在背景上
 *
 * 这几层叠起来，在电视观看距离下的观感已经很接近真玻璃，
 * 而**代价只有几次 alpha 叠加**，老机器扛得住。
 *
 * # 数值依据
 *
 * 圆角按 Google 的 TV 形状 token：`CornerExtraLarge = 28dp`。
 * 但**圆角不随卡片宽度等比缩放** —— 官方明确说过这点，
 * 所以大卡和窄卡用相近而非成比例的值（48 / 40dp）。
 */
@Composable
fun Modifier.glassCard(
    shape: Shape = RoundedCornerShape(40.dp),
    /** 聚焦时整体更亮、更"抬起来"。 */
    focused: Boolean = false,
): Modifier {
    val topAlpha = if (focused) 0.20f else 0.146f
    val botAlpha = if (focused) 0.045f else 0.030f
    val edgeAlpha = if (focused) 0.38f else 0.26f
    val fillTop = if (focused) 0.30f else 0.215f
    val fillBot = if (focused) 0.42f else 0.325f

    return this
        .clip(shape)
        // 1) 半透明底色：背后的霞光会透上来
        .background(
            Brush.verticalGradient(
                0f to GLASS_TINT.copy(alpha = fillTop),
                0.46f to GLASS_TINT.copy(alpha = (fillTop + fillBot) / 2f),
                1f to GLASS_TINT_WARM.copy(alpha = fillBot),
            ),
            shape,
        )
        // 2) 顶部柔光带（玻璃厚度聚光）
        .background(
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = topAlpha),
                0.40f to Color.White.copy(alpha = botAlpha),
                1f to Color.Transparent,
            ),
            shape,
        )
        // 5) 弥散阴影（浮起来）
        .shadow(
            elevation = if (focused) 30.dp else 16.dp,
            shape = shape,
            ambientColor = Color.Black.copy(alpha = 0.55f),
            spotColor = Color.Black.copy(alpha = 0.70f),
        )
        // 3+4) 棱边反光：上冷白、下暖
        .border(
            width = 1.dp,
            brush = Brush.verticalGradient(
                0f to Color.White.copy(alpha = edgeAlpha),
                0.5f to Color.White.copy(alpha = edgeAlpha * 0.30f),
                1f to GLASS_EDGE_WARM.copy(alpha = edgeAlpha * 0.75f),
            ),
            shape = shape,
        )
}

/** 玻璃底色：带一点冷调，因为背景上方是冷蓝灰。 */
private val GLASS_TINT = Color(0xFF2C3348)

/** 玻璃底部：偏暖，让下方的夕照渗进来。 */
private val GLASS_TINT_WARM = Color(0xFF3A2E38)

/** 棱边下缘的暖反光。 */
private val GLASS_EDGE_WARM = Color(0xFFFFD6A8)

/**
 * 焦点外框：**白色实边 + 黑辉光**。
 *
 * 这是 Google TV 公布的焦点默认值，不是我自己拍的：
 * · Scale 1.025 / 1.05 / 1.1
 * · Glow 2–32dp（= 4–64px）
 * · 辉光**只用黑色**，不用彩色（彩色辉光是报告点名的 P2 反模式）
 *
 * ## 为什么用白色而不是品牌色
 *
 * 用户反复强调过焦点要**一眼看得见**（家里有老人）。
 * 在深色背景 + 半透明玻璃上，白色实边是辨识度最高的选择，
 * 而且不会和海报里的颜色打架（品牌蓝边在蓝色海报上会"消失"）。
 */
@Composable
fun Modifier.focusRing(focused: Boolean, shape: Shape = RoundedCornerShape(40.dp)): Modifier =
    if (!focused) {
        this
    } else {
        this
            .border(width = 2.dp, color = Color.White.copy(alpha = 0.88f), shape = shape)
            .shadow(
                elevation = 32.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.80f),
                spotColor = Color.Black.copy(alpha = 0.90f),
            )
    }
