package com.chinut.bawantv.ui.screens

/**
 * 一次「起播」请求：播放器需要知道的全部信息。
 *
 * 从已删除的旧影视详情页里抽出来的 —— 它现在只作为**数据载体**存在，
 * 由主框架（``BawanRoot``）构造后交给 ``VodPlayerScreen``。
 *
 * 之所以保留成独立文件：它被主框架依赖，不该藏在某个已废弃的界面文件里，
 * 否则以后清理界面时很容易把这个还在用的类型一起删掉。
 */
data class PlayingEpisode(
    val episodes: List<com.chinut.bawantv.vod.VodEpisode>,
    val index: Int,
    /** 解析器标识（TVBox 的 flag / ddys 的线路名），给 VodResolver 用 */
    val flag: String,
    val title: String,
    /** 以下三项用于观看记录（断点续播 / 首页继续观看） */
    val vodId: String = "",
    val poster: String = "",
    /**
     * 给用户看的来源名（例如「非凡 · 量子线路」）。
     *
     * 和 [flag] 是两件事：flag 是解析器开关，写进播放界面只会让人困惑，
     * 所以界面上显示的是这个。
     */
    val sourceLabel: String = "",
)
