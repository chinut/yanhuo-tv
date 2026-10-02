package com.chinut.bawantv.live

import com.chinut.bawantv.BawanApp

/**
 * 最近播放过的直播频道。
 *
 * 首页要展示「最近播放的频道」，所以把用户点过的台按时间倒序记下来。
 * 存成一行一条 `频道名|地址`（用竖线分隔，地址里不会出现竖线），
 * 按「频道名」去重 —— 同一个台点多次只留最近那次。
 */
object RecentLive {

    private const val MAX = 12
    private const val SEP = "|"

    private fun raw(): String = BawanApp.prefs.recentLiveRaw

    private fun write(list: List<Pair<String, String>>) {
        BawanApp.prefs.recentLiveRaw = list.joinToString("\n") { "${it.first}$SEP${it.second}" }
    }

    /** 读取最近播放的频道（最近的在最前）。 */
    fun list(): List<Pair<String, String>> =
        raw().lineSequence()
            .mapNotNull { line ->
                val i = line.indexOf(SEP)
                if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
            }
            .toList()

    /** 记一次播放（同一个台只保留最近一次）。 */
    fun remember(name: String, url: String) {
        if (name.isBlank() || url.isBlank()) return
        val rest = list().filterNot { it.first == name }
        write((listOf(name to url) + rest).take(MAX))
    }

    fun clear() {
        write(emptyList())
    }
}
