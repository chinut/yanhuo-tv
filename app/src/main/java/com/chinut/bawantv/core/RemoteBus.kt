package com.chinut.bawantv.core

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent

/**
 * **网络遥控总线**。
 *
 * ## 它解决什么
 *
 * 电视遥控器只有一个，手机却人人都有。这个类把「遥控器按键」抽象成一个
 * 可以被**网络请求**触发的动作，于是手机 App 就能当第二只遥控器用。
 *
 * ## 为什么要走 Activity 的按键链路，而不是直接操作 UI
 *
 * 按键注入刻意**复用 [KeyEvent] 派发**（见 `RemoteHost.injectKey`），
 * 而不是"提供一组 navUp()/navDown() 这样的语义方法"。原因是：
 *
 *  - 全 App 的按键都统一在 `MainActivity.dispatchKeyEvent` 里处理，
 *    包括播放页的「左右键调进度」「上下键换台」这类**界面特有的语义**。
 *  - 如果遥控协议只暴露"上/下/左/右"四个抽象动作，那么每加一个界面
 *    都要在协议里加新方法，手机端也得跟着改。
 *  - 走按键链路则天然继承全部现有行为，**零维护成本**：
 *    电视上按 ↓ 会怎样，手机上发 ↓ 就完全一样。
 *
 * 所以协议传的是**键码**（Android KeyEvent keyCode），不是语义动作。
 */
object RemoteBus {

    /** 一个按键注入点。由 Activity 在 onCreate 时注册自己。 */
    interface Host {
        /** 把一个按键事件派发进 App 的按键链路。返回是否已消费。 */
        fun injectKey(keyCode: Int, action: Int): Boolean
    }

    @Volatile
    private var host: Host? = null

    /**
     * 当前界面名。
     *
     * 由 `BawanRoot` 在切板块时写进来 —— Activity 自己不知道
     * Compose 里当前显示的是哪个板块，而 Compose 又拿不到 Activity
     * （硬拿会让层级耦合）。一个全局字段最省事，而且这里本来就只需要
     * "最近一次是什么界面"这一个值。
     */
    @Volatile
    private var currentScreen: String = "home"

    fun setScreen(name: String) {
        currentScreen = name
    }

    /** 最近的按键事件（给手机端做"回显"用）。 */
    private val recent = ArrayDeque<Pair<Long, Int>>()
    private const val MAX_RECENT = 40

    /** 手机端上一次拉取的时间戳，用于增量取事件。 */
    fun registerHost(h: Host?) {
        host = h
    }

    /**
     * 注入一个按键。
     *
     * @param keyCode Android KeyEvent 键码（如 19=上、20=下、21=左、22=右、23=确定、4=返回）
     * @param action KeyEvent.ACTION_DOWN / ACTION_UP
     * @return 是否已被 App 消费
     */
    fun injectKey(keyCode: Int, action: Int = KeyEvent.ACTION_DOWN): Boolean {
        val h = host ?: return false
        val ok = runCatching { h.injectKey(keyCode, action) }.getOrDefault(false)
        if (action == KeyEvent.ACTION_DOWN) {
            synchronized(recent) {
                recent.addLast(System.currentTimeMillis() to keyCode)
                while (recent.size > MAX_RECENT) recent.removeFirst()
            }
        }
        return ok
    }

    /** 取 [sinceMs] 之后的按键事件（毫秒时间戳, 键码）。 */
    fun eventsSince(sinceMs: Long): List<Pair<Long, Int>> = synchronized(recent) {
        recent.filter { it.first > sinceMs }
    }

    /** 当前界面名。 */
    fun screen(): String = currentScreen

    // ==================== 音量 ====================

    private fun audio(ctx: Context): AudioManager? =
        runCatching { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }.getOrNull()

    /**
     * 当前音量 / 最大音量 / 是否静音。
     *
     * 走系统媒体音量流（STREAM_MUSIC），和电视遥控器上的音量键是同一个。
     * **不自己维护一份音量**：那样会和系统音量脱节，用户按电视遥控器
     * 调音量时手机显示的数字就不对了。
     */
    fun volumeState(ctx: Context): Triple<Int, Int, Boolean> {
        val am = audio(ctx) ?: return Triple(0, 15, false)
        val cur = runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
        val max = runCatching { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(15)
        val muted = runCatching { am.isStreamMute(AudioManager.STREAM_MUSIC) }.getOrDefault(false)
        return Triple(cur, max, muted)
    }

    /**
     * 设音量。
     *
     * @param value 绝对值；传 null 表示用 [delta] 相对调整
     * @param delta 相对调整量（value 为 null 时生效）
     */
    fun setVolume(ctx: Context, value: Int?, delta: Int): Boolean {
        val am = audio(ctx) ?: return false
        return runCatching {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val target = (value ?: (cur + delta)).coerceIn(0, max)
            // flags = 0：不要弹系统音量条。电视上那个浮层会挡住画面，
            // 而且用户是用手机在调，不需要电视再提示一次。
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            true
        }.getOrDefault(false)
    }

    /** 静音 / 取消静音。 */
    fun setMuted(ctx: Context, muted: Boolean): Boolean {
        val am = audio(ctx) ?: return false
        return runCatching {
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (muted) {
                if (cur > 0) {
                    // 记住原音量，方便恢复
                    lastVolume = cur
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                }
            } else {
                val restore = lastVolume.coerceAtLeast(1)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, restore, 0)
            }
            true
        }.getOrDefault(false)
    }

    /** 静音前的音量，用于恢复。 */
    @Volatile
    private var lastVolume: Int = 8
}
