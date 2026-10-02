package com.chinut.bawantv.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 焰火TV 设计令牌。
 *
 * 整体基调：深邃的夜空蓝紫底 + 高亮天蓝强调色，透明度分层营造玻璃质感。
 * TV 端屏幕大、观看距离远，所以：
 *  - 字号整体比手机端放大 1.35 倍左右
 *  - 卡片间距更大，一屏信息量适中
 *  - 焦点态必须极度明显（放大 + 亮边 + 外发光）
 */
object Ink {
    // 底色改成更有层次的"深夜蓝"，而不是接近纯黑 ——
    // 纯黑在电视上显得廉价，略带蓝紫的深色更有影院感。
    val Void = Color(0xFF05060C)
    val Deep = Color(0xFF0A0C16)
    val Base = Color(0xFF0E1120)
    val Soft = Color(0xFF161A2E)
    val Violet = Color(0xFF1E1548)

    /** 玻璃面板：整体比之前更"薄"，靠高光和投影立起来，而不是靠实色。 */
    val Card = Color(0x14FFFFFF)
    val CardStrong = Color(0x24FFFFFF)
    val Row = Color(0x10FFFFFF)
    val Divider = Color(0x14FFFFFF)
    val Scrim = Color(0xB3000000)
    val Sheet = Color(0xF2101424)

    val Accent = Color(0xFF63AEFF)
    val AccentBright = Color(0xFF9CCEFF)
    val AccentSoft = Color(0x335AA9FF)
    val AccentGlow = Color(0x665AA9FF)
    val Pink = Color(0xFFFF7BAE)
    val Green = Color(0xFF7BEBB4)
    val Amber = Color(0xFFFFC97A)
    val Red = Color(0xFFFF7B7B)

    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xE0FFFFFF)
    val TextTertiary = Color(0x9AFFFFFF)
    val TextFaint = Color(0x5CFFFFFF)
}

/** TV 端字号（sp），命名按用途而非尺寸，方便统一调整。 */
object Txt {
    val Hero = 46.sp
    val Title = 32.sp
    val Section = 24.sp
    val CardTitle = 19.sp
    val Body = 17.sp
    val Label = 15.sp
    val Caption = 13.sp
    val Tiny = 11.sp
}

object Dim {
    /** 左侧主导航栏宽度（四象限首页已不再使用导航栏，保留给可能的次级布局） */
    val RailWidth = 168.dp

    /** 屏幕内容安全边距（TV overscan，必须留） */
    val SafeH = 48.dp
    val SafeV = 32.dp

    // 圆角统一调大一点：大圆角 + 柔和渐变是"现代感"的主要来源
    val CardRadius = 18.dp
    val BigRadius = 26.dp
    val ChipRadius = 22.dp

    /** 海报卡片宽度（竖版 2:3） */
    val PosterW = 176.dp
    val PosterH = 264.dp

    /** 横版卡片（16:9） */
    val WideW = 300.dp
    val WideH = 169.dp
}
