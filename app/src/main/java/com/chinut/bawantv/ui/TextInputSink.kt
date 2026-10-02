package com.chinut.bawantv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember

/**
 * 字符输入通道：把遥控器/键盘上的**字母数字键**送到当前正在输入的界面。
 *
 * ## 为什么需要它
 *
 * 全 App 的按键都在 `MainActivity.dispatchKeyEvent` 里统一拦截，
 * 但那里只处理了方向键和确定键 —— **字母键直接落到 `super` 就丢了**。
 * 结果就是在搜索界面按遥控器上的字母（或外接键盘）完全没反应，
 * 只能用屏幕上的软键盘一个字母一个字母点。对"拼音首字母搜索"来说，
 * 能用实体键盘直接打是最基本的体验。
 *
 * ## 做法
 *
 * 用一个极简的全局槽位：谁在输入谁就注册，Activity 收到字符键时回调它。
 * 同一时刻只允许一个注册者（界面上也只有一处会输入），
 * 所以不需要队列，一个 `var` 就够。
 *
 * 之所以不做成 CompositionLocal + `Modifier.onKeyEvent`：
 * 这条链路必须能接住**在 Compose 焦点体系之外**的按键
 * （本 App 用的是自绘的 TvFocusManager，根本不走 Compose 焦点），
 * 所以只能从 Activity 那一层直接转发。
 */
object TextInputSink {

    /** 当前接收字符的回调；没有输入界面时为 null。 */
    @Volatile
    private var handler: ((Char) -> Unit)? = null

    /** 退格键回调 —— 和字符输入配套，否则打了字删不掉。 */
    @Volatile
    private var backspaceHandler: (() -> Unit)? = null

    /** 是否有界面正在接收字符输入。 */
    val active: Boolean get() = handler != null

    /**
     * 把一次字符输入交给当前界面。
     *
     * @return true 表示已被消费（调用方应 return true，别再往下传）
     */
    fun dispatch(ch: Char): Boolean {
        val h = handler ?: return false
        // 只放行可打印字符：控制字符（换行、制表符等）不该进输入框
        if (ch.code < 0x20 || ch.code == 0x7F) return false
        h(ch)
        return true
    }

    /** 退格。已消费返回 true。 */
    fun dispatchBackspace(): Boolean {
        val h = backspaceHandler ?: return false
        h()
        return true
    }

    internal fun register(onChar: (Char) -> Unit, onBackspace: () -> Unit) {
        handler = onChar
        backspaceHandler = onBackspace
    }

    internal fun unregister() {
        handler = null
        backspaceHandler = null
    }
}

/**
 * 在当前 Composable 存在期间接收字符输入。
 *
 * 用 `DisposableEffect(Unit)` 而不是 `LaunchedEffect`：
 * 注册/注销必须严格和组合生命周期对齐，泄漏一个旧回调会导致
 * 输入跑到已经消失的界面上去。
 */
@Composable
fun RegisterTextInput(
    onChar: (Char) -> Unit,
    onBackspace: () -> Unit,
) {
    val charHandler = remember { { c: Char -> onChar(c) } }
    val backHandler = remember { { onBackspace() } }
    DisposableEffect(Unit) {
        TextInputSink.register(charHandler, backHandler)
        onDispose { TextInputSink.unregister() }
    }
}
