package com.chinut.bawantv

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.chinut.bawantv.ui.BawanRoot
import com.chinut.bawantv.ui.screens.SplashScreen
import com.chinut.bawantv.ui.theme.BawanTheme
import com.chinut.bawantv.ui.theme.Direction
import com.chinut.bawantv.ui.theme.Ink
import com.chinut.bawantv.ui.theme.isEnterKeyCode
import com.chinut.bawantv.ui.theme.TvFocusManager

/**
 * 唯一 Activity（TV 端单 Activity + Compose 导航）。
 *
 * 遥控器按键的最终处理点：方向键与确定键都在这里截获，交给 [TvFocusManager]
 * 这套自绘焦点系统（详见 TvFocusManager 的注释：为什么不能用 Compose 的 focus）。
 *
 * 这样做的好处是按键行为 100% 确定 —— 不再依赖 Compose 的焦点搜索与按键分发，
 * 也就不会出现「卡片能高亮但按确定没反应」这类问题。其余按键（音量、返回、
 * 播放控制等）原样放行。
 */
class MainActivity : ComponentActivity(), com.chinut.bawantv.core.RemoteBus.Host {

    /** 全局唯一焦点管理器，Compose 与 Activity 共同使用。 */
    private val focusManager = TvFocusManager()

    override fun injectKey(keyCode: Int, action: Int): Boolean {
        // 复用 Activity 的按键链路：方向键走焦点系统、其余交给 dispatchKeyEvent。
        //
        // 这里刻意**不自己实现一套导航**：那样"电视上按↓"和"手机发↓"
        // 会出现行为差异，每加一个界面都要改两处。
        return try {
            val ev = KeyEvent(action, keyCode)
            dispatchKeyEvent(ev)
        } catch (t: Throwable) {
            false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 注册为网络遥控的执行端：手机 App 发来的按键最终由这里注入
        com.chinut.bawantv.core.RemoteBus.registerHost(this)
        // 调试入口诊断：把 intent 里的 extras 全打出来。
        // 之前遇到"am start 明明带了 --es，读出来却是 null"，
        // 先把原始 intent 打出来才能判断是参数没送到还是 key 对不上。
        runCatching {
            val b = intent?.extras
            if (b == null) {
                android.util.Log.i("BawanRoute", "onCreate extras=null intent=$intent")
            } else {
                val keys = b.keySet().joinToString(",") { "$it=${b.get(it)}" }
                android.util.Log.i("BawanRoute", "onCreate extras: $keys")
            }
        }
        setContent {
            BawanTheme {
                // 自适应挂在这里（整棵 UI 树的根）：
                // 按真实可用尺寸算出缩放系数，向下用 CompositionLocal 提供。
                // 4K/8K 电视的逻辑分辨率差异极大，尺寸写死 dp 会导致同一份界面
                // 在有些电视上只占半屏、在有些上挤成一团。
                com.chinut.bawantv.ui.theme.ProvideResponsive(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Ink.Deep)
                    ) {
                        // ---------- 开屏 → 首页：交叉淡化交接 ----------
                        //
                        // 时序是刻意这样排的：
                        //
                        //   0s ────────────────── 开屏动画（焰火飞行等）──────────┐
                        //                                                          │ 开屏淡出
                        //   0s ── 首页在下面同步渲染（被盖住看不见）──────────────┤（420ms）
                        //                                                          ↓
                        //                                                     只剩首页
                        //
                        // 要点：**首页在开屏还是完全不透明的时候就已经挂载并渲染了**，
                        // 只是被盖着。等开屏自己淡出时，底下早就是画好的首页，
                        // 于是整个交接过程两屏都有内容 ——
                        // 不会出现「开屏没了、首页还没画出来」中间闪一下黑底。
                        // 首页**立即挂载**（首次启动时它就在开屏底下渲染），
                        // 这样等开屏淡出时底下早就是画好的首页了。
                        BawanRoot(focusManager = focusManager)

                        // 首次启动才播开屏；从后台回来、旋屏都不重播。
                        var showSplash by remember { mutableStateOf(!BawanApp.splashShown) }

                        AnimatedVisibility(
                            visible = showSplash,
                            enter = EnterTransition.None,
                            // 开屏淡出的同时首页已经完整可见，所以这个过程不会有黑底
                            exit = fadeOut(animationSpec = tween(460)),
                        ) {
                            SplashScreen(onFinish = {
                                BawanApp.splashShown = true
                                // 只把开屏标记为不可见，首页早已在下面画好
                                showSplash = false
                            })
                        }
                    }
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            // 字符输入优先：搜索等界面注册了输入通道时，字母数字键先给它。
            // 这一步必须在方向键判断之前 —— 否则退格键之类会被下面的分支漏掉。
            if (com.chinut.bawantv.ui.TextInputSink.active) {
                val unicode = event.unicodeChar
                if (unicode != 0 && com.chinut.bawantv.ui.TextInputSink.dispatch(unicode.toChar())) {
                    return true
                }
                if (event.keyCode == KeyEvent.KEYCODE_DEL &&
                    com.chinut.bawantv.ui.TextInputSink.dispatchBackspace()
                ) {
                    return true
                }
            }
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT ->
                    if (focusManager.dispatchDirection(Direction.Left)) return true

                KeyEvent.KEYCODE_DPAD_RIGHT ->
                    if (focusManager.dispatchDirection(Direction.Right)) return true

                KeyEvent.KEYCODE_DPAD_UP ->
                    if (focusManager.dispatchDirection(Direction.Up)) return true

                KeyEvent.KEYCODE_DPAD_DOWN ->
                    if (focusManager.dispatchDirection(Direction.Down)) return true
            }
            if (isEnterKeyCode(event.keyCode)) {
                if (focusManager.dispatchConfirm()) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

/** 供调试/预览用 */
@Composable
internal fun Placeholder() {
    Box(Modifier.fillMaxSize())
}
