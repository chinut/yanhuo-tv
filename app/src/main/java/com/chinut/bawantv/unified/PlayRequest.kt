package com.chinut.bawantv.unified

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 一次「起播」请求：播放器需要的全部信息。
 *
 * 从旧的 `PlayingEpisode` 精简而来 —— 去掉了 TVBox 时代的解析器字段
 * （`flag` / `sourceLabel` 那套是给"解析接口"用的，现在影视只有低端影视，
 * 剧集地址是直连 m3u8，不需要解析）。
 */
data class PlayRequest(
    val episodes: List<Episode>,
    val index: Int,
    val title: String,
    /** 用于观看记录（断点续播 / 首页继续观看）。 */
    val vodId: String = "",
    val poster: String = "",
) {
    val current: Episode? get() = episodes.getOrNull(index) ?: episodes.firstOrNull()
}
