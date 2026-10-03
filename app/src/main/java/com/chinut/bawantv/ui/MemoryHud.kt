package com.chinut.bawantv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chinut.bawantv.core.MemProbe
import kotlinx.coroutines.delay

/**
 * 内存监控浮层（测试用）。
 *
 * 显示在屏幕右上角，每 [MemProbe.HUD_INTERVAL_MS] 刷新一次。
 *
 * ## 为什么不做成可聚焦控件
 *
 * 这是**调试叠加层**，一旦可聚焦就会进入遥控器的导航范围，
 * 把正常的焦点流打乱（本项目的焦点是几何导航，多一个可聚焦块就多一个落点）。
 * 所以它完全不参与焦点，纯展示。
 *
 * ## 颜色是刻意选的
 *
 * 半透明黑底 + 等宽字：不抢画面、数字能对齐好读。
 * 数字旁边带颜色标记 —— 内存吃紧时一眼能看出来，不用去记阈值：
 *   · 绿  < 200MB    宽松
 *   · 黄  200~280MB  偏高（1GB 电视开始有压力）
 *   · 红  > 280MB    危险（512MB~1GB 电视可能被杀）
 */
@Composable
fun MemoryHud(enabled: Boolean) {
    if (!enabled) return
    val context = LocalContext.current
    var snap by remember { mutableStateOf<MemProbe.Snapshot?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            snap = runCatching { MemProbe.sample(context) }.getOrNull()
            delay(MemProbe.HUD_INTERVAL_MS)
        }
    }

    val s = snap ?: return
    val pssMb = s.pssKb / 1024
    val level = when {
        pssMb > 280 -> Color(0xFFFF7B7B)   // 红
        pssMb > 200 -> Color(0xFFFFC97A)   // 黄
        else -> Color(0xFF7BEBB4)          // 绿
    }

    Box(
        Modifier
            .padding(top = 8.dp, end = 8.dp)
            .width(224.dp)
            .background(Color(0xCC000000), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Column {
            Text(
                "内存 $pssMb MB",
                color = level,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "RSS ${s.rssKb / 1024}  Code ${s.codeKb / 1024}",
                color = Color(0xFFB9C4DA),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "Native ${s.nativeKb / 1024}  Java ${s.javaKb / 1024}",
                color = Color(0xFFB9C4DA),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "设备 ${s.deviceAvailMb}/${s.deviceTotalMb} MB",
                color = if (s.lowMemory) Color(0xFFFF7B7B) else Color(0xFF8E9BB5),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            if (s.graphicsKb > 0) {
                Text(
                    "Graphics ${s.graphicsKb / 1024} MB",
                    color = Color(0xFF8E9BB5),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
