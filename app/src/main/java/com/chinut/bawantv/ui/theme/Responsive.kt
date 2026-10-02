package com.chinut.bawantv.ui.theme

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit

/**
 * 多分辨率适配。
 *
 * ## 为什么必须专门处理
 *
 * 这个 App 要装在很多不同电视上，而**电视的 dpi 差异极大**：
 *
 * | 屏幕 | density | 逻辑尺寸（dp×dp） |
 * |---|---|---|
 * | 1080p | 320 | 960 × 540 |
 * | 4K | 320 | 1920 × 1080 ← 逻辑空间直接翻倍 |
 * | 4K | 640 | 960 × 540 |
 * | 8K | 640 | 1920 × 1080 |
 *
 * 如果尺寸全写死 dp（例如「设置按钮高 116dp」），那么同一份界面：
 *  - 在 1080p@320 上占 116/540 = 21% 的高度
 *  - 在 4K@320 上只占 116/1080 = 11% —— **缩成一小条**
 *
 * 所以不能写死，必须**按可用空间等比缩放**。
 *
 * ## 做法
 *
 * 以 1080p@320（逻辑 960×540）为基准，取**较短边**算一个缩放系数，
 * 然后把字号和关键尺寸都乘上它：
 *
 * ```
 * scale = min(宽/960, 高/540)
 * ```
 *
 * 取短边而不是长边，是为了兼容超宽屏 / 带鱼屏：竖着能放下才不会溢出。
 * 系数做上下限钳制，避免极端设备上出现字号过大或过小。
 *
 * 局限：这个方案能覆盖 16:9 的各种分辨率；如果将来要支持 21:9，
 * 短边方案仍然成立（宽度会多出空白，需要布局层面用 weight 自己吃掉）。
 */
class Responsive(val scale: Float) {

    /** 缩放后的 dp。 */
    fun dp(value: Float): Dp = (value * scale).dp
    fun dp(value: Int): Dp = (value * scale).dp

    /** 缩放后的字号。 */
    fun sp(value: Float): TextUnit = (value * scale).sp
    fun sp(value: Int): TextUnit = (value * scale).sp

    /** 便捷取整，便于日志排查。 */
    val percent: Int get() = (scale * 100).toInt()
}

/**
 * 全局适配上下文。
 *
 * 用 CompositionLocal 而不是到处传参：尺寸几乎每个组件都要用，
 * 一路透传会让签名膨胀得没法看。
 */
val LocalResponsive: ProvidableCompositionLocal<Responsive> =
    compositionLocalOf { Responsive(1f) }

/** 基准逻辑尺寸（1080p @ 320dpi → 960×540 dp）。 */
private const val REF_W = 960f
private const val REF_H = 540f

/**
 * 在根布局套一层，向下提供按屏幕算好的缩放。
 *
 * 用 [BoxWithConstraints] 拿到真实可用尺寸（含刘海/系统栏影响后的结果），
 * 比读 DisplayMetrics 更准。
 */
@Composable
fun ProvideResponsive(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val w = with(density) { maxWidth.toPx() } / density.density
        val h = with(density) { maxHeight.toPx() } / density.density
        val responsive = remember(w, h) { Responsive(scaleFor(w, h)) }
        CompositionLocalProvider(LocalResponsive provides responsive) {
            content()
        }
    }
}

/**
 * 算缩放系数。
 *
 * 以短边为准（宽 960 / 高 540），并钳制在 [0.75, 3.2]：
 *  - 下限 0.75：再小字就看不清了
 *  - 上限 3.2：8K 上也不至于把字号撑到离谱
 */
internal fun scaleFor(widthDp: Float, heightDp: Float): Float {
    if (widthDp <= 0f || heightDp <= 0f) return 1f
    val s = minOf(widthDp / REF_W, heightDp / REF_H)
    return s.coerceIn(0.75f, 3.2f)
}

/** 当前适配上下文。 */
val responsive: Responsive
    @Composable get() = LocalResponsive.current

/**
 * 缩放 dp 的语法糖。
 *
 * 让迁移成本降到最低：把 `116.dp` 改成 `116.sdp` 就行，
 * 不用改成 `R.dp(116)` 这种读起来别扭的写法。
 */
val Int.sdp: Dp
    @Composable get() = LocalResponsive.current.dp(this)

val Float.sdp: Dp
    @Composable get() = LocalResponsive.current.dp(this)

/** 缩放字号的语法糖：`18.ssp`。 */
val Int.ssp: TextUnit
    @Composable get() = LocalResponsive.current.sp(this)

val Float.ssp: TextUnit
    @Composable get() = LocalResponsive.current.sp(this)

/**
 * 给设计令牌用的缩放：``Txt.Section.ssp``。
 *
 * [Txt] 里那些预设字号本身是 TextUnit，不能直接乘系数，
 * 这个扩展让它们也能参与适配，写法保持统一。
 *
 * 注意：Dp 版本（``Dim.SafeH.sdp``）做不了 —— ``Dp.value`` 本身就是 Float，
 * 会和 ``Float.sdp`` 撞 JVM 签名。Dim 里那几个常量请直接用
 * ``LocalResponsive.current.dp(...)``。
 */
val TextUnit.ssp: TextUnit
    @Composable get() = LocalResponsive.current.sp(this.value)
