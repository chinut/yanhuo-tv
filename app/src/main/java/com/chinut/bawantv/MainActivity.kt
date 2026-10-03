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

        // ---------- 全局禁止息屏 ----------
        //
        // 用户反馈："打开这个软件的时候能不能不要进入屏保啊，看一会就进入屏保了"。
        //
        // 之前只在两个播放页加了 FLAG_KEEP_SCREEN_ON —— 那是错的：
        //   · **浏览页面**（首页、频道墙、影视列表、设置）照样会息屏，
        //     而这些页面本来就要慢慢挑，正是最容易息屏的时候；
        //   · 而且熄屏后音频还在放，体验非常怪。
        //
        // 这是一个**遥控器操作的全屏电视应用**，息屏没有任何意义：
        // 用户在看电视时就该常亮。所以直接在窗口级加标志，全程有效，
        // 直到用户主动退出应用。
        //
        // 用 window 标志而不是 View.keepScreenOn：
        // 部分国产电视（实测小米）只认窗口级标志，不认子 View 的。
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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
                        BawanRoot(
                            focusManager = focusManager,
                            // 首页再按返回 → 退出 App。
                            // 用 finish() 而不是 finishAffinity()：电视上这个应用
                            // 就是从桌面启动的，finish 之后自然回到桌面。
                            onExitRequested = { finish() },
                        )

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

            // ---------- 飞鼠 / 游戏手柄上的"确认"类按键 ----------
            //
            // 实测：电视遥控器没问题，但**飞鼠（空中鼠标）**上那几个键按了没反应。
            // 原因是飞鼠发的不是 DPAD_CENTER，而是下面这些键码之一 ——
            // 各个厂商实现还不统一，所以常见的都收进来。
            if (event.keyCode in AIR_MOUSE_CONFIRM_KEYS) {
                if (focusManager.dispatchConfirm()) return true
            }

            // ---------- 飞鼠上的"返回 / 菜单" ----------
            // 这两个键在部分飞鼠上映射到 ENTER / ESCAPE / MENU / BACK，
            // 如果不显式处理，会直接落到 super 被系统吞掉（表现为按键无效）。
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    // 交给 BackHandler（BawanRoot 里注册的那套逻辑）
                    onBackPressedDispatcher.onBackPressed()
                    return true
                }

                KeyEvent.KEYCODE_MENU -> {
                    // 三横「菜单/设置」键交给当前界面处理。
                    //
                    // 直播播放页用它呼出**清晰度/线路选择**；别的界面没接管就吞掉，
                    // 避免弹出一个空的系统菜单把画面挡住。
                    if (focusManager.dispatchRawKey(KeyEvent.KEYCODE_MENU)) return true
                    if (focusManager.dispatchRawKey(KeyEvent.KEYCODE_SETTINGS)) return true
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        /**
         * 飞鼠/手柄上用来"确定"的各种键码。
         *
         * 之所以要列一串：飞鼠厂商的实现很随意 —— 有的发 ENTER、
         * 有的发 NUMPAD_ENTER、有的发 BUTTON_A，还有的干脆发 DPAD_CENTER 的变体。
         * 只认 DPAD_CENTER 的话，用户会觉得"飞鼠的确认键坏了"。
         */
        private val AIR_MOUSE_CONFIRM_KEYS = setOf(
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_SPACE,
        )
    }
}

/** 供调试/预览用 */
@Composable
internal fun Placeholder() {
    Box(Modifier.fillMaxSize())
}
