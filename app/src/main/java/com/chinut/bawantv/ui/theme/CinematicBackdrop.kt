package com.chinut.bawantv.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 首页背景：**电影感自然风光**。
 *
 * # 为什么换掉原来的 [AuroraBackdrop]
 *
 * 原来那版是「4 团大模糊色斑 + 屏幕混合 + 无限漂移动画」，
 * 而调研报告逐条指出了它的问题（见 `docs/design-research-tv-ui-2026.md`）：
 *
 * · 紫到蓝的网格渐变是公认的「AI 味」第一标志
 * · 模糊色斑（bokeh orbs）被列为 P1 反模式 —— 背景在抢内容的戏
 * · 34 秒无限漂移在电视上持续重绘，老机器白耗性能
 * · Google 的明文规定：「**不要调整用户不直接交互的背景元素**」，
 *   背景应该是**安静的满幅场**，不是构图的一部分
 *
 * # 新背景的依据
 *
 * 用户给了 5 张参考图（故宫角楼 / 米尔福德峡湾 / 苏格兰高地 / 阿尔卑斯），
 * 都是「电影感自然风光」。我把它们的颜色**逐张取样**，得到共同规律：
 *
 *     明度 V ≈ 0.29~0.41  → 是**暗调**
 *     饱和 S ≈ 0.25~0.42  → 低饱和、克制
 *     **最亮处在上中 22%**（云被霞光照亮），不是地平线
 *     结构：冷蓝灰(顶) → 玫瑰紫(上中·最亮) → 暖褐(中下) → 深墨(底)
 *
 * 所以这里就是照这个结构做的**静态**渐变 —— 没有动画、没有色斑，
 * 便宜且安静；"电影感"来自配色和明暗层次，不来自特效。
 *
 * ## 为什么不用 Canvas
 *
 * 用几个 `Box` 叠渐变比 `Canvas` + 逐像素画便宜得多，
 * 而这点视觉差别在电视观看距离下根本看不出来。
 */
@Composable
fun CinematicBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.background(CINEMATIC_BASE)) {

        // ---------- 云带 ----------
        //
        // 参考图里最亮的地方是"被霞光照亮的云"，位置在画面上中偏上。
        // 用拉长的椭圆渐变模拟：横向铺满、纵向很扁。
        Box(
            Modifier
                .fillMaxWidth()
                .height(420.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            CLOUD_WARM.copy(alpha = 0.46f),
                            Color.Transparent,
                        ),
                        center = Offset(0.42f, 0.62f),
                        radius = 1400f,
                    )
                )
        )
        // 云缝透出的冷光，压在上缘 —— 避免整屏都在暖
        Box(
            Modifier
                .fillMaxWidth()
                .height(240.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            CLOUD_COOL.copy(alpha = 0.16f),
                            Color.Transparent,
                        ),
                        center = Offset(0.72f, 0.20f),
                        radius = 1200f,
                    )
                )
        )
        // 底部一层柔雾：让卡片下方"沉"一点，同时不把画面压黑
        Box(
            Modifier
                .fillMaxWidth()
                .height(520.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.5f to FLOOR_HAZE.copy(alpha = 0.14f),
                        1f to FLOOR_HAZE.copy(alpha = 0.24f),
                    )
                )
        )

        content()
    }
}

// ---------- 颜色常量（数值来自对参考图的取样，见文件头注释）----------

/** 天顶冷蓝灰 */
private val SKY_TOP = Color(0xFF3B4468)

/** 上中玫瑰紫 —— 画面最亮处 */
private val SKY_LIT = Color(0xFFA9859C)

/** 暖带峰值（云被霞光打亮） */
private val SKY_WARM = Color(0xFFB58E92)

/** 中下暖褐紫 */
private val SKY_MID = Color(0xFF6A5262)

/** 底部深墨紫 */
private val SKY_DEEP = Color(0xFF2A2430)

/** 云带的暖色 */
private val CLOUD_WARM = Color(0xFFE8A184)

/** 云缝的冷色 */
private val CLOUD_COOL = Color(0xFF6E7BA8)

/** 底部雾色 */
private val FLOOR_HAZE = Color(0xFF221E2C)

/**
 * 主渐变。
 *
 * 各档位置照着参考图的取样点走（0 / 11 / 20 / 27 / 38 / 50 / 64 / 78 / 90 / 100），
 * 其中 **20~27% 是最亮的暖带** —— 这是"电影感"的关键：
 * 光是从云里透出来的，不是在画面底部。
 */
private val CINEMATIC_BASE = Brush.verticalGradient(
    0.00f to SKY_TOP,
    0.11f to Color(0xFF6A5F84),
    0.20f to SKY_LIT,
    0.27f to SKY_WARM,
    0.38f to Color(0xFF8B6B7E),
    0.50f to Color(0xFF634D5E),
    0.64f to SKY_MID,
    0.78f to Color(0xFF4A3E4E),
    0.90f to Color(0xFF3A3140),
    1.00f to SKY_DEEP,
)
