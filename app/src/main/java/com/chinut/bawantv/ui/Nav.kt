package com.chinut.bawantv.ui

import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.vector.ImageVector
import com.chinut.bawantv.BawanApp
import com.chinut.bawantv.core.AppPrefs

/** 顶层板块定义（TV 端只有 4 个一级入口，遥控器按几下就到）。 */
enum class TopSection(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val subtitle: String,
) {
    Home("home", "首页", Icons.Default.Whatshot, "推荐与入口"),
    Vod("vod", "影视", Icons.Default.Movie, "电影 · 电视剧 · 综艺"),
    ShortDrama("shortdrama", "短剧", Icons.Default.SmartDisplay, "竖屏短剧 · 自动连播"),
    Settings("settings", "设置", Icons.Default.Settings, "接口 · 域名 · 手机调试"),
}

/** 全局单例设置（UI 层各处直接用）。 */
val prefs: AppPrefs get() = BawanApp.prefs
