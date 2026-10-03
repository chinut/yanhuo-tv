package com.chinut.bawantv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.chinut.bawantv.ui.theme.Dim
import com.chinut.bawantv.ui.theme.Ink
import com.chinut.bawantv.ui.theme.Txt
import com.chinut.bawantv.ui.theme.sdp
import com.chinut.bawantv.ui.theme.ssp

/**
 * 短剧板块。
 *
 * ## 设计取舍：为什么复用影视那套界面
 *
 * 短剧在交互上和影视是**同一件事** —— 海报墙 → 详情 → 选集 → 播放。
 * 差别只有两点：
 *   · 集数多得多（几十到上百集，一集 1~3 分钟）
 *   · 播放要**自动连播**
 *
 * 所以这里不另写一套 UI，而是走影视那套组件。
 * 电视的内存本来就紧（实测已占 253MB），多一套界面等于多一份
 * 图片缓存和组合开销，不划算。
 *
 * ## 当前状态：等待数据源
 *
 * 那个短剧 App（`各种果`）是 **Flutter 编译产物**，没有 Java 源码可读；
 * 它用的接口都需要 `sign` 签名，而签名算法编译在 `libduanju_core.so` 里，
 * 拿不到。实测直接请求返回 404 / 403（部分站点还有 JS 反爬）。
 *
 * 所以这个页面先做出来占位，把入口和导航打通；
 * **等确定一个能通的数据源，把列表部分换成真实数据即可**，
 * 详情 / 选集 / 播放可以直接复用影视那套。
 */
@Composable
fun ShortDramaScreen(entryKey: Any) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = Dim.SafeH * 0.7f, vertical = Dim.SafeV * 0.8f),
    ) {
        // ---------- 顶栏（和影视页保持一致的结构） ----------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "短剧",
                color = Color.White,
                fontSize = Txt.Section,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(14.sdp))
            Text(
                "竖屏短剧 · 自动连播",
                color = Ink.TextFaint,
                fontSize = Txt.Caption,
            )
        }

        Spacer(Modifier.height(8.sdp))

        Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "数据源待接入",
                    color = Ink.TextSecondary,
                    fontSize = Txt.Section,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(12.sdp))
                Box(
                    Modifier
                        .width(620.sdp)
                        .background(Ink.Card, RoundedCornerShape(12.sdp))
                        .padding(18.sdp),
                ) {
                    Text(
                        "短剧 App 是 Flutter 编译产物，接口需要签名（算法在 .so 里），" +
                            "直接请求返回 404/403，无法逆向。\n\n" +
                            "入口和导航已经打通；确定一个可用的数据源后，" +
                            "列表替换成真实数据，详情 / 选集 / 播放复用影视那套即可。",
                        color = Ink.TextTertiary,
                        fontSize = Txt.Caption,
                        lineHeight = 22.ssp,
                    )
                }
            }
        }
    }
}
