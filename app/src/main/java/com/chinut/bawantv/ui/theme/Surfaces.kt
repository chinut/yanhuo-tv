package com.chinut.bawantv.ui.theme

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import kotlin.math.PI

/**
 * 视觉基元：把"一张好看的卡片"抽出来复用。
 *
 * ## 为什么要重做视觉
 *
 * 之前的卡片是「纯色灰底 + 一圈亮描边」，问题在于：
 *  - 描边把每个块都框得很死，满屏都是线，非常"工程感"
 *  - 平面色块没有纵深，电视上看像没渲染完
 *  - 一屏几十个元素都在喊"看我"，反而没有重点
 *
 * 现在统一成：
 *  - **纵向渐变底**（上亮下暗）制造微弱的光照感，而不是一块平色
 *  - **一层极淡的高光内描边**（顶亮底透）模拟玻璃厚度
 *  - 焦点态用**光晕 + 轻微上浮 + 亮度提升**表达，而不是一圈刺眼的实线
 */

/** 卡片/面板的柔和渐变底。 */
@Composable
fun Modifier.glassSurface(
    shape: Shape = RoundedCornerShape(Dim.CardRadius),
    topAlpha: Float = 0.10f,
    bottomAlpha: Float = 0.04f,
): Modifier = this
    .clip(shape)
    .background(
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = topAlpha),
                Color.White.copy(alpha = bottomAlpha),
            )
        ),
        shape,
    )

/** 更"抬起来"的面板：深色玻璃 + 高光内边，用于四象限这种大块。 */
@Composable
fun Modifier.panelSurface(
    shape: Shape = RoundedCornerShape(Dim.BigRadius),
    glow: Color = Color.Transparent,
    glowAlpha: Float = 0f,
): Modifier {
    val base = this
        .shadow(
            elevation = if (glowAlpha > 0f) 22.dp else 8.dp,
            shape = shape,
            ambientColor = Color.Black.copy(alpha = 0.45f),
            spotColor = if (glowAlpha > 0f) glow.copy(alpha = glowAlpha) else Color.Black,
        )
        .clip(shape)
        .background(
            Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = 0.075f),
                    Color.White.copy(alpha = 0.028f),
                )
            ),
            shape,
        )
    // 顶部一层极淡的横向高光，模拟玻璃边缘的反光
    return base.background(
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.10f),
            0.06f to Color.Transparent,
            1f to Color.Transparent,
        ),
        shape,
    )
}

/**
 * iOS 风格毛玻璃。
 *
 * ## 关键取舍：这是"假"毛玻璃
 *
 * 真·背景模糊（把**面板后面的内容**采样再模糊）在 Compose 里做不到 ——
 * 它需要把已经画好的图层读回来，而 Compose 是单向绘制的。
 * 只有 API 31+ 的 ``RenderEffect`` 配合 ``graphicsLayer`` 能模糊自身内容，
 * 但那是模糊**自己**，不是背后，视觉上不是一回事。
 *
 * 所以采用的是 iOS 上同样在用的**叠加式玻璃**（很多 iOS 控件也是这个原理）：
 *
 *  1. 一层半透明深色底 —— 让后面的画面变暗、透出来一点轮廓
 *  2. 一层极淡的白色纵向渐变 —— 模拟玻璃厚度造成的亮度差
 *  3. **1dp 的亮色顶部描边** —— 玻璃边缘的反光，这是"像玻璃"的关键一笔
 *
 * 这三层叠起来，视觉上就足够像磨砂玻璃了，而且**全 API 通用**（minSdk 24），
 * 不依赖 RenderEffect。
 *
 * 如果哪天要"真模糊"，正确做法是让调用方自己把背景先 ``Modifier.blur()``
 * 一遍再铺在面板下面 —— 那是调用方的构图问题，不适合塞进这个 Modifier。
 *
 * @param tint 玻璃底色，默认深色（电视上深色玻璃更耐看）
 * @param strong true 时更不透明，适合压在动态画面上（例如视频）
 */
@Composable
fun Modifier.frostedGlass(
    shape: Shape = RoundedCornerShape(Dim.BigRadius),
    tint: Color = Ink.Deep,
    topAlpha: Float = 0.62f,
    bottomAlpha: Float = 0.78f,
    highlight: Float = 0.16f,
    strong: Boolean = false,
): Modifier {
    val a1 = if (strong) (topAlpha + 0.12f).coerceAtMost(0.95f) else topAlpha
    val a2 = if (strong) (bottomAlpha + 0.10f).coerceAtMost(0.98f) else bottomAlpha
    return this
        .clip(shape)
        // 1) 半透明底色：后面的画面会透出轮廓
        .background(
            Brush.verticalGradient(
                listOf(tint.copy(alpha = a1), tint.copy(alpha = a2)),
            ),
            shape,
        )
        // 2) 白色微渐变：模拟玻璃厚度带来的亮度差
        .background(
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.055f),
                0.35f to Color.White.copy(alpha = 0.012f),
                1f to Color.Transparent,
            ),
            shape,
        )
        // 3) 顶部亮边：玻璃边缘反光，"像玻璃"的关键
        .border(
            width = 1.dp,
            brush = Brush.verticalGradient(
                0f to Color.White.copy(alpha = highlight),
                0.5f to Color.White.copy(alpha = highlight * 0.25f),
                1f to Color.White.copy(alpha = highlight * 0.5f),
            ),
            shape = shape,
        )
}

/**
 * **焦点亮边框**（遥控器的"选中框"）。
 *
 * ## 为什么最终做成实心亮边
 *
 * 前面几版都用"光晕"表达焦点（外层柔和光斑、`shadow` 外发光），
 * 设计上更高级，但**在电视上根本认不出来** —— 尤其是老人：
 *
 *  - 电视有 overscan、亮度对比度普遍不如显示器；
 *  - 光晕是**渐隐**的，没有一个明确的边界，眼睛抓不住"从哪到哪是选中的"；
 *  - 深色 UI 上光晕本来就不显眼。
 *
 * 结论：**选中框要有一条看得见的实线边**，这是电视 UI 的通行做法，
 * 也正是用户明确要的（"边框亮起"）。
 *
 * ## 实现要点
 *
 * - 用 `drawWithContent` 把边框画在**内容之上**。Compose 的 `Modifier.border`
 *   画在背景之上、内容之下，而卡片常用半透明的 `frostedGlass`，
 *   会把边框冲淡；先画内容再画边，边就一定是"最亮的那一层"。
 * - 默认 3dp、**纯色**高亮，不用渐变 —— 渐变会让边在某几段变暗。
 * - 未聚焦时**完全不画**，避免满屏都是线（"每张卡都有描边"以前被吐槽过）。
 *
 * 用在与遥控器交互的主界面上；纯展示性元素不要加。
 */
@Composable
fun Modifier.focusBorder(
    visible: Boolean,
    cornerRadius: Dp = Dim.BigRadius,
    color: Color = Ink.AccentBright,
    width: Dp = 3.dp,
): Modifier = this
    .clip(RoundedCornerShape(cornerRadius))
    .drawWithContent {
        drawContent()
        if (!visible) return@drawWithContent
        val w = width.toPx()
        val inset = w / 2f
        val r = cornerRadius.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(inset, inset),
            size = Size(size.width - w, size.height - w),
            cornerRadius = CornerRadius(r, r),
            style = Stroke(width = w),
        )
    }

/**
 * **动态极光背景**。
 *
 * ## 为什么要动起来
 *
 * 毛玻璃的本质是「透出后面的东西」—— 背景是死的，玻璃就没有内容可透，
 * 看起来只是一块半透明灰板。背景缓慢流动起来之后：
 *  - 光斑移动经过玻璃面板时，面板的亮度会随之变化 → **玻璃质感立刻出来**
 *  - 静态图看久了是"壁纸"，流动的光是"氛围"
 *
 * ## 怎么动（以及为什么这么动）
 *
 * 不用 ``Modifier.blur()``：那在 TV 的 GPU 上开销太大，而且大半径模糊容易掉帧。
 * 而是**直接用多个大半径的径向渐变画光斑**，本身就是柔和的，
 * 每帧只改变它们的位置 —— 成本只有几个 drawCircle。
 *
 * 运动设计：
 *  - 3 团主光斑沿不同相位的正弦轨迹缓慢漂移（周期 24~40 秒，几乎察觉不到"在动"，
 *    但几分钟后回头看会明显不同）
 *  - 一团高光做更慢的呼吸（透明度起伏），避免画面"死平"
 *  - 极淡的星点缓慢横移，给深空一点层次
 *
 * 整体周期都在 20 秒以上：电视是长时间开着的设备，背景动得快会非常烦人。
 */
@Composable
fun AuroraBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val t = rememberInfiniteTransition(label = "aurora")

    // 三个不同周期的相位，避免光斑同步移动（同步看起来像整块在漂）
    val p1 by t.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(28000, easing = LinearEasing)),
        label = "p1",
    )
    val p2 by t.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(37000, easing = LinearEasing)),
        label = "p2",
    )
    val p3 by t.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(45000, easing = LinearEasing)),
        label = "p3",
    )
    // 呼吸：透明度缓慢起伏
    val breathe by t.animateFloat(
        initialValue = 0.75f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            tween(11000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathe",
    )

    Box(
        modifier.background(
            Brush.linearGradient(
                colors = listOf(Ink.Void, Ink.Deep, Ink.Base, Ink.Violet),
                start = Offset.Zero,
                end = Offset(1700f, 1300f),
            )
        )
    ) {
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height

            // ---------- 3 团主光斑：沿正弦轨迹缓慢漂移 ----------
            data class Blob(
                val baseX: Float, val baseY: Float,
                val ampX: Float, val ampY: Float,
                val radius: Float, val color: Color,
                val phase: Float, val alpha: Float,
            )

            val blobs = listOf(
                // 左上冷蓝：主光源，最大
                Blob(0.20f, 0.16f, 0.13f, 0.09f, 0.66f, Ink.Accent, p1, 0.34f),
                // 右下品红：与冷蓝对冲，形成冷暖对比
                Blob(0.86f, 0.90f, 0.10f, 0.08f, 0.60f, Ink.Pink, p2, 0.24f),
                // 中间偏下紫：把两团连起来，避免只有两个孤立的点
                Blob(0.52f, 0.72f, 0.17f, 0.12f, 0.74f, Color(0xFF7B5CFF), p3, 0.28f),
            )

            blobs.forEach { b ->
                val cx = w * (b.baseX + b.ampX * kotlin.math.sin(b.phase))
                val cy = h * (b.baseY + b.ampY * kotlin.math.cos(b.phase * 0.8f))
                val r = w * b.radius
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            b.color.copy(alpha = (b.alpha * breathe).coerceIn(0f, 0.60f)),
                            Color.Transparent,
                        ),
                        center = Offset(cx, cy),
                        radius = r,
                    ),
                    radius = r,
                    center = Offset(cx, cy),
                )
            }

            // ---------- 顶部一道极淡的斜向光带：给画面一个"光源方向" ----------
            val sweep = w * (0.55f + 0.12f * kotlin.math.sin(p1 * 0.6f))
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.085f * breathe),
                        Color.Transparent,
                    ),
                    center = Offset(sweep, -h * 0.15f),
                    radius = w * 0.75f,
                ),
                radius = w * 0.75f,
                center = Offset(sweep, -h * 0.15f),
            )

            // ---------- 星点：极淡、缓慢横移 ----------
            // 用确定性的伪随机（不引入 Random），保证每帧位置一致不会闪
            val drift = (p1 / (2 * PI).toFloat())
            for (i in 0 until 26) {
                val seed = i * 7.13f
                val bx = (seed * 0.37f) % 1f
                val by = (seed * 0.91f) % 1f
                // 横向缓慢漂移，出屏后从另一侧回来
                val x = ((bx + drift * 0.35f) % 1f) * w
                val y = by * h
                val twinkle = 0.35f + 0.65f * kotlin.math.abs(
                    kotlin.math.sin(p2 * 0.9f + i.toFloat())
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.16f * twinkle),
                    radius = if (i % 5 == 0) 2.4f else 1.4f,
                    center = Offset(x, y),
                )
            }
        }

        content()
    }
}

/** 用径向渐变做一层柔和的背景光，给整页铺氛围（替代纯色背景）。 */
@Composable
fun AmbientBackdrop(
    modifier: Modifier = Modifier,
    accent: Color = Ink.Accent,
    secondary: Color = Ink.Pink,
    content: @Composable BoxScope.() -> Unit,
) {
    AuroraBackdrop(modifier, content)
}
