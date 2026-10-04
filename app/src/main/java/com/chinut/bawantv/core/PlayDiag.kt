package com.chinut.bawantv.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 播放诊断：把"卡不卡、卡在哪"变成手机上能看的一行字。
 *
 * # 为什么需要它
 *
 * 用户反馈老电视模式有些台「每隔几秒卡零点几秒」，但我让他在电视上抓 logcat
 * —— **电视根本没有文件管理器，也没有 adb**，这条路不现实
 * （用户原话：「电视机上不好抓日志啊，电视机没有文件管理器」）。
 *
 * 所以改成：App 自己把关键数据记下来，**显示在手机调试页上**。
 * 用户拿手机打开那个页面，看一眼或者截个图发我，就够了。
 *
 * # 为什么要判断"这三种情况"
 *
 * 卡顿的可能原因有三类，**修法完全相反**，所以必须先分清：
 *
 * | 现象 | 结论 | 修法 |
 * |---|---|---|
 * | 卡顿时 `state=BUFFERING` | 取流不够快（网络 / 源） | 调大重缓冲阈值 |
 * | 卡顿时 `state` 一直 READY | 渲染卡（解码能力 / 码率） | 降码率上限 |
 * | 码率来回跳 | 自适应切档 | 固定档位 |
 *
 * 所以我记的不只是"卡了几次"，还记**每次卡的时候播放器处于什么状态**、
 * 以及当时的码率是多少。
 *
 * # 三条数据流
 *
 * · [onReady]    —— 起播时记下频道名、源地址、是否有直连
 * · [onTick]     —— 播放中每 3 秒采样一次（位置 / 缓冲 / 状态 / 码率）
 * · [onStall]    —— 判定"卡了"时记一笔（含当时的 state）
 */
object PlayDiag {

    /** 一次卡顿的记录。 */
    data class Stall(
        val at: Long,
        /** 卡住时播放器的状态（BUFFERING = 取流不够；READY = 渲染卡）。 */
        val playerState: String,
        /** 当时的码率（kbps），用来判断是不是码率太高带不动。 */
        val bitrateKbps: Int,
        val width: Int,
        val height: Int,
    )

    private const val TAG = "BawanDiag"

    /**
     * 最近一次"接口连通性自检"的结论。
     *
     * 用途：用户电视上短剧刷不出来，但代码里 `Net.get` 把
     * DNS / 超时 / TLS / HTTP 错误全压成一个 null，查不出原因。
     * 这里存一句人能看懂的话，手机调试页直接显示。
     */
    @Volatile var probeResult: String = "（还没测过）"
    private const val MAX_STALLS = 60

    // ---------- 当前状态 ----------
    @Volatile var channelName: String = ""
    @Volatile var channelUrl: String = ""
    @Volatile var sourceIndex: Int = 0
    @Volatile var sourceCount: Int = 0
    @Volatile var usingWeb: Boolean = false

    @Volatile var playerState: String = "—"
    @Volatile var bitrateKbps: Int = 0
    @Volatile var width: Int = 0
    @Volatile var height: Int = 0
    /**
     * `Player.bufferedPosition` 的原始值。
     *
     * ⚠️ **直播流上这是相对量，不是"缓冲到哪个绝对位置"** ——
     * 实测见过 `-49978` 这种值。所以**不能用它做 `buf - pos`**：
     * 我一开始就是这么算"缓冲余量"的，结果报告里出现
     * 「缓冲余量 -13 秒 ⚠️ 透支 16 次」和「没检测到卡顿」自相矛盾。
     *
     * 这里只原样记录供参考，判定一律不用它。
     */
    @Volatile var bufferedRawMs: Long = 0
    @Volatile var positionMs: Long = 0

    /** 本次播放累计卡顿次数。 */
    @Volatile var stallCount: Int = 0
        private set

    /** 本次播放累计重缓冲次数。 */
    @Volatile var rebufferCount: Int = 0
        private set

    @Volatile var stallMsTotal: Long = 0
        private set

    @Volatile private var startedAt: Long = 0

    private val stalls = ArrayDeque<Stall>()

    /** 起播时调用（换台 / 换源 / 重新 tune 都要调）。 */
    fun onReady(
        name: String,
        url: String,
        srcIndex: Int,
        srcTotal: Int,
        web: Boolean,
    ) {
        channelName = name
        channelUrl = url
        sourceIndex = srcIndex
        sourceCount = srcTotal
        usingWeb = web
        stallCount = 0
        rebufferCount = 0
        stallMsTotal = 0
        startedAt = System.currentTimeMillis()
        synchronized(stalls) { stalls.clear() }
        android.util.Log.i(TAG, "开始诊断：$name 源 ${srcIndex + 1}/$srcTotal web=$web")
    }

    /** 播放中每 3 秒采样。 */
    fun onTick(
        state: String,
        posMs: Long,
        bufMs: Long,
        rateKbps: Int,
        w: Int,
        h: Int,
        prevPosMs: Long,
        playing: Boolean,
        prevState: String,
    ) {
        playerState = state
        positionMs = posMs
        bufferedRawMs = bufMs
        bitrateKbps = rateKbps
        width = w
        height = h

        if (state == "BUFFERING" && prevState != "BUFFERING") {
            rebufferCount++
        }

        // 播放中位置却不推进 = 卡了
        if (playing && posMs == prevPosMs) {
            stallCount++
            stallMsTotal += 3_000
            synchronized(stalls) {
                if (stalls.size >= MAX_STALLS) stalls.removeFirst()
                stalls.addLast(Stall(System.currentTimeMillis(), state, rateKbps, w, h))
            }
        }
    }

    /**
     * 生成给人看的诊断文本（显示在手机调试页上）。
     *
     * 刻意写成"结论 + 数据"两段：用户只要看第一行就知道该不该找我，
     * 而我要的是后面的细节。
     */
    fun report(): String {
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
        val upSec = if (startedAt == 0L) 0 else (System.currentTimeMillis() - startedAt) / 1000
        val sb = StringBuilder()

        sb.append("接口自检：").append(probeResult).append('\n')
        sb.append("频道：").append(channelName.ifBlank { "（未播放）" }).append('\n')
        sb.append("来源：第 ").append(sourceIndex + 1).append('/').append(sourceCount)
        sb.append(if (usingWeb) " 条 · 网页播放\n" else " 条 · 直连播放\n")
        sb.append("画质：").append(width).append('x').append(height)
        sb.append(" · ").append(bitrateKbps).append(" kbps\n")
        sb.append("状态：").append(playerState)
        sb.append(" · 已播 ").append(upSec).append(" 秒\n")
        // 只打原始值，并标注它不可用于判断（直播流上是相对量）
        sb.append("缓冲原始值：").append(bufferedRawMs).append(" ms（直播流上为相对量，仅供参考）\n")
        sb.append('\n')
        sb.append("卡顿 ").append(stallCount).append(" 次")
        sb.append(" · 重缓冲 ").append(rebufferCount).append(" 次")
        if (stallCount > 0) {
            sb.append(" · 累计 ").append(stallMsTotal / 1000).append(" 秒")
        }
        sb.append('\n')

        // ---------- 定性结论 ----------
        //
        // 这一段是关键：用户看不懂 state 和码率，但他看得懂这句话。
        val recent = synchronized(stalls) { stalls.toList() }
        if (recent.isEmpty()) {
            sb.append("判定：本次没检测到卡顿\n")
        } else {
            val bufStalls = recent.count { it.playerState == "BUFFERING" }
            val readyStalls = recent.count { it.playerState == "READY" }
            when {
                bufStalls > readyStalls -> sb.append(
                    "判定：**取流慢**（卡顿时播放器在缓冲）\n" +
                        "→ 属于网络/源的问题，不是电视带不动\n",
                )
                readyStalls > bufStalls -> sb.append(
                    "判定：**渲染卡**（数据够但画面没跟上）\n" +
                        "→ 多半是码率超过电视解码能力\n",
                )
                else -> sb.append("判定：两种都有，按多的那边算\n")
            }
            // 码率是否在跳（自适应切档）
            val rates = recent.map { it.bitrateKbps }.distinct()
            if (rates.size > 1) {
                sb.append("注意：卡顿时出现过 ").append(rates.size)
                sb.append(" 种不同码率 —— 可能在自适应切档\n")
            }
            sb.append('\n').append("最近几次：\n")
            recent.takeLast(6).reversed().forEach {
                sb.append("  ").append(fmt.format(Date(it.at)))
                sb.append(' ').append(it.playerState)
                sb.append(' ').append(it.bitrateKbps).append("kbps")
                sb.append(' ').append(it.width).append('x').append(it.height)
                sb.append('\n')
            }
            sb.append("\n最近卡顿时的码率：")
            sb.append(rates.joinToString(", ") { "${it}k" })
            sb.append('\n')
        }
        return sb.toString()
    }
}
