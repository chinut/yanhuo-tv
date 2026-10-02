package com.chinut.bawantv.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.chinut.bawantv.core.WatchHistory
import com.chinut.bawantv.core.WatchRecord
import kotlinx.coroutines.delay

/** 距离片尾多少毫秒算「快完了」，据此提前切下一集（留出缓冲/黑屏的时间）。 */
private const val NEXT_EPISODE_LEAD_MS = 12_000L

/** 进度落盘间隔：太频繁写盘没意义，太稀又怕用户直接退出丢进度。 */
private const val SAVE_INTERVAL_MS = 5_000L

/**
 * 播放页的「观看记录 + 断点续播 + 自动联播」。
 *
 * 三个职责放在一个地方，是因为它们共享同一套状态（当前第几集、播到哪），
 * 拆开反而要来回传参、还容易各自为政。板块 B（VOD）和板块 C（低端影视）都用它，
 * 保证两个播放页的行为完全一致。
 *
 * 行为：
 *  1. **断点续播**：进入某集时，如果之前记录的就是这一集，自动跳到上次的位置
 *  2. **进度落盘**：每 5 秒记一次；视频放完时记 0，这样下次从头开始
 *  3. **自动联播**：单集快播完（距片尾 [NEXT_EPISODE_LEAD_MS]）就自动切下一集，
 *     最后一集播完则调用 [onReachedEnd]
 *
 * @param player 播放器实例
 * @param activeUrl 当前真正在播的流地址；为空表示还在解析，这时不做记录也不续播
 * @param isLiveStream 直播/未知时长的流不做续播（否则会被当成"看完了"）
 * @param episodeUrls 当前线路的全部剧集地址（决定自动联播有没有下一集）
 * @param identityId 作品唯一标识
 * @param kind "vod" 或 "ddys"
 * @param title / poster / flag / episodeName 记录用到的展示与恢复信息
 * @param onSwitchToEpisode 切到指定集
 * @param onReachedEnd 最后一集播完时回调（用于清空记录/回列表）
 */
@Composable
fun rememberWatchProgress(
    player: ExoPlayer,
    activeUrl: String?,
    isLiveStream: Boolean,
    episodeUrls: List<String>,
    identityId: String,
    kind: String,
    title: String,
    poster: String,
    flag: String,
    episodeName: String,
    onSwitchToEpisode: (Int) -> Unit,
    onReachedEnd: () -> Unit = {},
) {
    var episodeIndex by remember { mutableStateOf(0) }
    var resumed by remember { mutableStateOf(false) }

    // 当前流一变（换集/换线路）就复位，避免用上一集的进度去续播这一集
    LaunchedEffect(activeUrl, episodeUrls, identityId) {
        resumed = false
        episodeIndex = episodeUrls.indexOf(activeUrl.orEmpty()).coerceAtLeast(0)
    }

    // ---------- 续播 + 进度记录 + 自动联播 ----------
    LaunchedEffect(activeUrl, isLiveStream, episodeUrls, identityId) {
        val url = activeUrl ?: return@LaunchedEffect
        if (isLiveStream || durationUnknown(player)) return@LaunchedEffect

        val saved = WatchHistory.find(kind, identityId)
        val sameEpisode = saved != null && saved.episodeIndex == episodeIndex

        // 1) 续播：只对「上次看的正是这一集」生效。
        //    从选集进来时会显式 seek，这里就不再抢着定位；
        //    等几秒让播放器把时长准备好，才能判断是不是快看完了。
        if (sameEpisode && saved != null && !resumed) {
            var waited = 0
            while (player.duration <= 0L && waited < 20) {
                delay(300)
                waited++
            }
            val target = saved.positionMs
            val dur = runCatching { player.duration }.getOrDefault(0L)
            // 片尾 30 秒内就别续播了，直接从头（用户大概率是想重看）
            if (dur > 0L && target in 1..(dur - 30_000L)) {
                runCatching { player.seekTo(target) }
            }
            resumed = true
        }

        // 2) 落盘 + 自动联播
        while (true) {
            val pos = runCatching { player.currentPosition }.getOrDefault(0L)
            val dur = runCatching { player.duration }.getOrDefault(0L)
            if (dur > 0L) {
                val hasNext = episodeIndex < episodeUrls.size - 1
                if (pos >= dur - NEXT_EPISODE_LEAD_MS) {
                    if (hasNext) {
                        // 记下这一集「已看完」，然后自动接下一集
                        save(kind, identityId, title, poster, episodeIndex, episodeName, flag,
                            episodeUrls, 0L, dur)
                        onSwitchToEpisode(episodeIndex + 1)
                        return@LaunchedEffect
                    } else if (pos >= dur - 1_500L) {
                        // 最后一集播完：清进度，下次从头看
                        save(kind, identityId, title, poster, episodeIndex, episodeName, flag,
                            episodeUrls, 0L, dur)
                        onReachedEnd()
                        return@LaunchedEffect
                    }
                }
                // 中途退出也不丢进度
                if (pos > WatchRecord.MIN_POSITION_MS) {
                    save(kind, identityId, title, poster, episodeIndex, episodeName, flag,
                        episodeUrls, pos, dur)
                }
            }
            delay(SAVE_INTERVAL_MS)
        }
    }

    /**
     * 手动退出播放时同步落一次盘。
     *
     * 自动联播用的是协程里的循环，退出时那个协程会被取消，
     * 所以要在 `onDispose` 里调用 [saveProgressNow] 补记一次，
     * 否则最后几秒的进度会丢。
     */
}

private fun durationUnknown(player: ExoPlayer): Boolean =
    runCatching { player.duration <= 0L }.getOrDefault(true)

private fun save(
    kind: String,
    id: String,
    title: String,
    poster: String,
    episodeIndex: Int,
    episodeName: String,
    flag: String,
    episodeUrls: List<String>,
    positionMs: Long,
    durationMs: Long,
) {
    WatchHistory.save(
        WatchRecord(
            kind = kind,
            id = id,
            title = title,
            poster = poster,
            episodeIndex = episodeIndex,
            episodeName = episodeName,
            flag = flag,
            episodeUrls = episodeUrls,
            positionMs = positionMs,
            durationMs = durationMs,
        )
    )
}

/**
 * 退出播放时补一次进度。
 *
 * 单独抽出来是因为它必须在 `onDispose` 里调用（那时协程已经被取消，
 * 不能在协程里写），所以用普通函数而不是挂起函数。
 */
fun saveProgressNow(
    player: Player?,
    kind: String,
    id: String,
    title: String,
    poster: String,
    episodeIndex: Int,
    episodeName: String,
    flag: String,
    episodeUrls: List<String>,
) {
    val p = player ?: return
    val pos = runCatching { p.currentPosition }.getOrDefault(0L)
    val dur = runCatching { p.duration }.getOrDefault(0L)
    if (dur <= 0L) return
    // 快看完了就记 0，下次从头开始；否则记当前位置
    val value = if (pos >= dur - 30_000L) 0L else pos
    save(kind, id, title, poster, episodeIndex, episodeName, flag, episodeUrls, value, dur)
}
