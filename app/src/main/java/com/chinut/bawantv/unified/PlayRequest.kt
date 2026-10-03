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
    /**
     * 短剧模式：一集播完**自动播下一集**。
     *
     * 为什么不让播放器按"集数 > 1"自己判断：一部电视剧也可能有几十集，
     * 但播完一集未必想自动连播（有的用户就是看一集）。
     * 短剧是明确的产品语义 —— 一集 1~2 分钟，就该连着看。
     * 所以由发起方（板块）显式标记。
     */
    val autoNext: Boolean = false,
) {
    val current: Episode? get() = episodes.getOrNull(index) ?: episodes.firstOrNull()
}
