package com.chinut.bawantv.unified

/**
 * 一集剧集：名字 + 可直接播放的地址。
 *
 * 为什么自己定义一个而不是复用旧 `vod.VodEpisode`：
 * 那个类型带着 `flag`（解析器标识）等字段 —— 那是 TVBox 时代的产物。
 * 现在影视只保留低端影视，它的剧集地址是**直连的 HLS/mp4**，
 * 不需要任何"解析接口"就能播，所以那些字段全是多余的。
 *
 * 类型越简单，能出错的地方越少。
 */
data class Episode(
    /** 显示用名字，例如「第01集」；缺失时用「第 N 集」。 */
    val name: String,
    /** 可直接播放的地址（低端影视给的是直连 m3u8）。 */
    val url: String,
)
