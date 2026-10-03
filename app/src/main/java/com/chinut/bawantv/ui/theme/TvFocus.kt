package com.chinut.bawantv.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import kotlin.math.abs
import androidx.compose.runtime.rememberUpdatedState

/**
 * TV 焦点与遥控器按键系统（自绘，不依赖 Compose 的 focus 机制）。
 *
 * ## 为什么不用 Compose 的 focus / clickable
 *
 * 在 Android TV 与盒子上实测（模拟器同样复现）暴露了三个绕不开的问题：
 *
 * 1. `Modifier.clickable` 与 `Modifier.focusable` 各建一个焦点目标，
 *    确定键 `KEYCODE_DPAD_CENTER` 会落到没绑点击的那个上 → 「能高亮但按确定没反应」。
 * 2. 把 `onKeyEvent` / `onPreviewKeyEvent` 挂在焦点节点上，确定键也收不到
 *    （方向键能收到，确定键一条日志都没有）。
 * 3. `onFocusChanged` 只在首次组合回调一次 `false`，之后焦点移动不再回调，
 *    无法可靠知道「现在选中的是哪一项」。
 *
 * ## 现在的做法
 *
 * - 焦点**完全自绘**：每个可聚焦项组合时向 [TvFocusManager] 注册，并用
 *   `onGloballyPositioned` 提供自己在窗口中的位置。
 * - 方向键 / 确定键在 **Activity.dispatchKeyEvent** 截获，交给管理器：
 *   方向键按「几何最近 + 交叉轴重罚」移动焦点（同一行/列优先），确定键触发当前项动作。
 * - 焦点状态是我们自己的普通 state，高亮、放大、读取当前项 100% 可控。
 *
 * 代价：懒加载列表的滚动要项自己处理（[tvFocusable] 的 bringIntoView）。
 */
class TvFocusManager {

    /** 一个可聚焦项。 */
    class Item internal constructor(
        val key: Any,
        internal val focusState: TvFocusState,
        internal val boundsProvider: () -> Rect,
    ) {
        internal var onActivate: (() -> Unit)? = null
        // 返回 true = 已处理；false = 没处理，交回几何导航。
        // 允许"有条件的覆盖"，例如网格卡片只在"上面还有未显示的行"时才滚动。
        internal var onLeft: (() -> Boolean)? = null
        internal var onRight: (() -> Boolean)? = null
        internal var onUp: (() -> Boolean)? = null
        internal var onDown: (() -> Boolean)? = null
        internal var bringIntoView: (() -> Unit)? = null
        internal var enabled: Boolean = true
    }

    private val items = LinkedHashMap<Any, Item>()

    /** 当前焦点项的 key；外部用 [isFocused] 判断自己是否被选中。 */
    var focusedKey: Any? by mutableStateOf(null)
        private set

    internal fun itemOf(key: Any): Item? = items[key]

    /**
     * 滚动作用域（可为空）。
     *
     * 由 [ProvideTvFocus] 在根节点注入。管理器需要它来在"找不到下一项"时
     * 滚动视口 —— 见 [move] 里的说明。
     */
    internal var tvFocusScope: TvFocusScope? = null

    /** 注册。第一个注册的项自动获得焦点，保证「进来就有东西被选中」。 */
    internal fun register(item: Item) {
        items[item.key] = item
        if (focusedKey == null || !items.containsKey(focusedKey)) {
            moveTo(item.key)
        }
    }

    internal fun unregister(key: Any) {
        items.remove(key)
        if (focusedKey == key) {
            moveTo(items.keys.firstOrNull())
        }
    }

    fun moveTo(key: Any?) {
        if (key == null) return
        val target = items[key] ?: return
        if (!target.enabled) return
        focusedKey = key
        items.values.forEach { it.focusState.focused = it.key == key }
        target.bringIntoView?.invoke()
    }

    fun move(direction: Direction): Boolean {
        if (step(direction)) return true
        // 关键：几何导航找不到候选时，**先把视口滚一点再试一次**。
        //
        // 为什么必须有这一步：懒加载容器里屏幕外的项不参与布局，
        // boundsProvider 返回空矩形会被跳过 —— 于是"往下按"永远找不到目标，
        // 列表就卡住不动了（实测表现就是「瀑布流不能向下滚动」）。
        // 滚动之后新的项进入可视区，第二次 step 就能找到它们。
        val focusScope = tvFocusScope
        if (focusScope != null &&
            focusScope.scrollViewport(direction) {
                // 把"当前焦点项在窗口里的位置"交给滚动作用域，
                // 它据此只滚包含焦点的那个容器
                items[focusedKey]?.boundsProvider()
            }
        ) {
            return step(direction)
        }
        return false
    }

    /** 单次几何导航（不涉及滚动）。 */
    private fun step(direction: Direction): Boolean {
        val cur = items[focusedKey] ?: run {
            moveTo(items.keys.firstOrNull())
            return items.isNotEmpty()
        }
        val custom = when (direction) {
            Direction.Left -> cur.onLeft
            Direction.Right -> cur.onRight
            Direction.Up -> cur.onUp
            Direction.Down -> cur.onDown
        }
        if (custom != null) {
            // 处理器说"我处理了"才结束；说"没处理"就继续往下走几何导航。
            if (custom()) return true
        }

        val origin = cur.boundsProvider()
        if (origin.isEmpty) return false
        val cx = origin.center.x
        val cy = origin.center.y

        var best: Item? = null
        var bestScore = Float.MAX_VALUE
        items.values.forEach { cand ->
            if (cand.key == focusedKey || !cand.enabled) return@forEach
            val r = cand.boundsProvider()
            if (r.isEmpty) return@forEach
            val dx = r.center.x - cx
            val dy = r.center.y - cy
            val mainOk = when (direction) {
                Direction.Left -> dx < -4f
                Direction.Right -> dx > 4f
                Direction.Up -> dy < -4f
                Direction.Down -> dy > 4f
            }
            if (!mainOk) return@forEach

            val main = if (direction == Direction.Left || direction == Direction.Right) abs(dx) else abs(dy)
            val cross = if (direction == Direction.Left || direction == Direction.Right) abs(dy) else abs(dx)
            // 交叉轴错位重罚：保证「同一行 / 同一列」优先，不会斜着跳
            val score = main + cross * 2.5f
            if (score < bestScore) {
                bestScore = score
                best = cand
            }
        }
        val target = best ?: return false
        moveTo(target.key)
        return true
    }

    /** 确定键：执行当前焦点项的动作。 */
    fun activate(): Boolean {
        val act = items[focusedKey]?.onActivate ?: return false
        act()
        return true
    }

    /** 给外部（例如预聚焦到某一项）用的 key 列表。 */
    val keys: List<Any> get() = items.keys.toList()

    // ==================== 屏幕级按键接管 ====================
    // 播放页这类「没有可聚焦项、纯按键操作」的界面需要自己接管方向键/确定键，
    // 否则按键会落到这里没人处理（表现就是「播放中按上下键没反应」）。

    private var keyInterceptor: ((Direction) -> Boolean)? = null
    private var confirmInterceptor: (() -> Boolean)? = null
    private var rawKeyInterceptor: ((Int) -> Boolean)? = null

    fun setKeyInterceptor(interceptor: ((Direction) -> Boolean)?) {
        keyInterceptor = interceptor
    }

    fun setConfirmInterceptor(interceptor: (() -> Boolean)?) {
        confirmInterceptor = interceptor
    }

    /**
     * 接管"方向键和确定键之外"的按键（按 Android keyCode 传进来）。
     *
     * 用途：电视遥控器上那颗**三横「菜单/设置」键**。直播播放页用它呼出
     * 清晰度选择 —— 这类按键不在方向/确定的语义里，需要单独一条通道。
     *
     * 返回 true 表示已消费（Activity 不再往下传）。
     */
    fun setRawKeyInterceptor(interceptor: ((Int) -> Boolean)?) {
        rawKeyInterceptor = interceptor
    }

    /** 给 Activity 调用：把一个原始按键交给当前屏幕。 */
    fun dispatchRawKey(keyCode: Int): Boolean =
        rawKeyInterceptor?.let { runCatching { it(keyCode) }.getOrDefault(false) } ?: false

    /** 返回 true 表示该方向键已被当前屏幕接管。 */
    fun dispatchDirection(direction: Direction): Boolean {
        keyInterceptor?.let { if (it(direction)) return true }
        return move(direction)
    }

    /** 返回 true 表示确定键已被当前屏幕接管。 */
    fun dispatchConfirm(): Boolean {
        confirmInterceptor?.let { if (it()) return true }
        val ok = activate()
        // 只在按了确定却什么都没触发时记一笔：这是排查「按键没反应」最有用的线索，
        // 正常触发时不打日志，免得刷屏。
        if (!ok) {
            android.util.Log.w(
                "BawanFocus",
                "确定键未激活任何项：focused=$focusedKey 已注册=${keys}",
            )
        }
        return ok
    }
}

/** 方向。 */
enum class Direction { Left, Right, Up, Down }

/** 滚动作用域：让拿到焦点的项能把自己滚进可视区。 */
class TvFocusScope(
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    private val requesters = LinkedHashMap<Any, () -> Unit>()

    /**
     * 当前界面上所有**可滚动容器**。
     *
     * ## 为什么要记"范围"
     *
     * 一个界面里常有好几个滚动容器：影视页有「分类横向栏」+「海报网格」，
     * 设置页有纵向列表。之前只是把它们排成一队逐个尝试，真机上就出了这个 bug：
     * **在分类栏上按右键，把下面的网格滚下去了**。
     *
     * 现在每个容器登记自己在窗口里的矩形，滚动时**只挑包含当前焦点**的那个。
     * 分类栏只横向滚、网格只纵向滚，互不干扰。
     */
    private class Viewport(
        val scroll: (Int) -> Boolean,
        val bounds: () -> androidx.compose.ui.geometry.Rect,
    )

    private val viewportScrolls = LinkedHashMap<Any, Viewport>()

    fun bind(key: Any, scroll: () -> Unit) {
        requesters[key] = scroll
    }

    fun unbind(key: Any) {
        requesters.remove(key)
    }

    fun scrollTo(key: Any) {
        runCatching { requesters[key]?.invoke() }
    }

    /**
     * 注册一个滚动容器。
     *
     * @param id 唯一标识（调用方用 remember 生成）
     * @param bounds 容器在**窗口坐标系**里的矩形，用来判断焦点在不在里面
     * @param scroll 参数是滚动量：正数向下/右，负数向上/左
     */
    fun addViewportScroll(
        id: Any,
        bounds: () -> androidx.compose.ui.geometry.Rect,
        scroll: (Int) -> Boolean,
    ) {
        viewportScrolls[id] = Viewport(scroll, bounds)
    }

    fun removeViewportScroll(id: Any) {
        viewportScrolls.remove(id)
    }

    /**
     * 把视口朝某方向滚一点。
     *
     * **只滚"包含当前焦点"的那个容器** —— 见 [viewportScrolls] 的说明。
     * 这一步是修「在分类栏按右键却把网格滚下去」的关键。
     *
     * @param focusRectProvider 当前焦点项在窗口里的矩形（由管理器提供）
     */
    fun scrollViewport(
        direction: Direction,
        focusRectProvider: () -> androidx.compose.ui.geometry.Rect? = { null },
    ): Boolean {
        if (viewportScrolls.isEmpty()) return false
        val delta = when (direction) {
            Direction.Down -> SCROLL_STEP
            Direction.Up -> -SCROLL_STEP
            Direction.Right -> SCROLL_STEP
            Direction.Left -> -SCROLL_STEP
        }

        val focusRect = runCatching { focusRectProvider() }.getOrNull()
        if (focusRect != null && !focusRect.isEmpty) {
            viewportScrolls.values.forEach { vp ->
                val r = runCatching { vp.bounds() }.getOrNull() ?: return@forEach
                if (r.isEmpty) return@forEach
                // 用"焦点中心落在容器内"判断归属，比整块相交稳
                // （卡片可能有一半压在容器外）
                val cx = focusRect.center.x
                val cy = focusRect.center.y
                if (cx >= r.left && cx <= r.right && cy >= r.top && cy <= r.bottom) {
                    val moved = runCatching { vp.scroll(delta) }.getOrDefault(false)
                    if (moved) return true
                }
            }
            // 焦点确实在某个容器里但那个容器到头了 → **不要让别的容器乱滚**
            return false
        }

        // 拿不到焦点矩形时（罕见）退回逐个尝试
        viewportScrolls.values.forEach { vp ->
            val moved = runCatching { vp.scroll(delta) }.getOrDefault(false)
            if (moved) return true
        }
        return false
    }

    /**
     * 找到包含 [rect] 中心点的那个滚动视口，拿它的可视范围。
     *
     * 用途：焦点卡片自己判断"我有没有被裁掉"。
     * 判断方式用"中心点在谁里面"，和 [scrollViewport] 保持一致 ——
     * 用整块相交的话，卡片一半压在容器外时归属会算错。
     */
    fun viewportRectFor(rect: Rect): Rect? {
        if (rect.isEmpty) return null
        val cx = rect.center.x
        val cy = rect.center.y
        viewportScrolls.values.forEach { vp ->
            val r = runCatching { vp.bounds() }.getOrNull() ?: return@forEach
            if (r.isEmpty) return@forEach
            if (cx >= r.left && cx <= r.right && cy >= r.top && cy <= r.bottom) return r
        }
        return null
    }

    /**
     * 按精确像素量滚动"包含 [focusRect]"的那个容器。
     *
     * 与 [scrollViewport] 的区别：那个是按一个固定的步长滚（方向键触发），
     * 这个是"差多少补多少" —— 由焦点项自己算出被裁掉的部分，正好把它滚出来。
     * 用于保证「选中的内容 100% 在屏幕内」。
     *
     * @param focusRect 焦点项在窗口里的矩形（用来判断它属于哪个容器）
     * @param delta 要滚动的像素量（正数向下）
     */
    fun scrollViewportBy(focusRect: Rect, delta: Float) {
        if (delta == 0f || viewportScrolls.isEmpty()) return
        val vp = viewportRectFor(focusRect)?.let { r ->
            viewportScrolls.values.firstOrNull { v ->
                runCatching { v.bounds() }.getOrNull() == r
            }
        }
        if (vp != null) {
            runCatching { vp.scroll(delta.toInt()) }
            return
        }
        // 拿不到归属时退回第一个容器，总比不滚好
        viewportScrolls.values.firstOrNull()?.let { v ->
            runCatching { v.scroll(delta.toInt()) }
        }
    }

    companion object {
        /** 一次方向键滚动的像素量 —— 约半行卡片，滚动手感比较连续。 */
        private const val SCROLL_STEP = 260
    }
}

/** 当前焦点管理器。 */
val LocalTvFocusManager = staticCompositionLocalOf<TvFocusManager?> { null }

/** 当前滚动作用域。 */
val LocalTvFocusScope = staticCompositionLocalOf<TvFocusScope?> { null }

/** 在 App 根节点提供焦点管理器。 */
@Composable
fun ProvideTvFocus(
    manager: TvFocusManager,
    focusScope: TvFocusScope? = null,
    content: @Composable () -> Unit,
) {
    // 把作用域挂到管理器上：move() 在找不到候选时要靠它滚动视口
    DisposableEffect(manager, focusScope) {
        manager.tvFocusScope = focusScope
        onDispose { manager.tvFocusScope = null }
    }
    CompositionLocalProvider(
        LocalTvFocusManager provides manager,
        LocalTvFocusScope provides focusScope,
    ) {
        content()
    }
}

/**
 * 让某个 Composable 成为"焦点导航的兜底滚动目标"，并记录它的矩形范围。
 *
 * 用法：把返回的 Modifier 挂到滚动容器上。
 * ```
 * LazyVerticalGrid(modifier = Modifier.registerViewportScroll { d -> state.scrollBy(d.toFloat()) })
 * ```
 * 只能滚包含当前焦点的那个容器 —— 这样分类横向栏和海报网格不会互相干扰。
 */
@Composable
fun Modifier.registerViewportScroll(scrollBy: suspend (Int) -> Unit): Modifier {
    val scope = LocalTvFocusScope.current ?: return this
    val coroutineScope = rememberCoroutineScope()
    val id = remember { Any() }
    val current by rememberUpdatedState(scrollBy)
    var bounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    DisposableEffect(scope, id) {
        scope.addViewportScroll(
            id = id,
            bounds = { bounds },
            scroll = { delta ->
                coroutineScope.launch { current(delta) }
                true
            },
        )
        onDispose { scope.removeViewportScroll(id) }
    }
    return this.onGloballyPositioned { bounds = it.boundsInWindow() }
}

/** TV 焦点状态。 */
class TvFocusState {
    var focused by mutableStateOf(false)
        internal set
}

@Composable
fun rememberTvFocusState(): TvFocusState = remember { TvFocusState() }

/**
 * 把一个 Composable 变成可聚焦项（自绘焦点）。
 *
 * @param onClick     确定键 / 点击动作
 * @param onLeft/onRight/onUp/onDown 覆盖默认几何方向导航
 *        （例如「内容区最左侧按左 → 回导航栏」）
 * @param scrollKey   非空时，获得焦点后请求把该 key 对应的项滚进可视区
 */
@Composable
fun Modifier.tvFocusable(
    focusState: TvFocusState = rememberTvFocusState(),
    /** 焦点唯一标识。跨区域显式跳转（如导航栏 → 内容区）需要指定它。 */
    focusKey: Any? = null,
    shape: Shape = RoundedCornerShape(Dim.CardRadius),
    focusedScale: Float = 1.07f,
    glow: Boolean = true,
    borderWidth: Dp = 3.dp,
    baseBackground: Color = Color.Transparent,
    focusedBackground: Color = Ink.CardStrong,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onFocus: ((Boolean) -> Unit)? = null,
    onLeft: (() -> Boolean)? = null,
    onRight: (() -> Boolean)? = null,
    onUp: (() -> Boolean)? = null,
    onDown: (() -> Boolean)? = null,
    scrollKey: Any? = null,
): Modifier {
    val manager = LocalTvFocusManager.current
    val focusScope = LocalTvFocusScope.current

    val scale by animateFloatAsState(
        targetValue = if (focusState.focused) focusedScale else 1f,
        animationSpec = tween(150),
        label = "tvScale",
    )
    val glowAlpha by animateFloatAsState(
        targetValue = if (focusState.focused && glow) 1f else 0f,
        animationSpec = tween(150),
        label = "tvGlow",
    )

    val key = focusKey ?: remember { Any() }
    var bounds by remember { mutableStateOf(Rect.Zero) }

    // ---------- 拿到焦点时，把自己**完整**滚进可视区 ----------
    //
    // 真机反馈：「用下键显示未显示的内容时，应确保选中的内容 100% 显示在屏幕内」。
    //
    // 之前只有"焦点系统找不到下一项 → 滚动容器按固定像素滚一点"这一种机制，
    // 滚完焦点项可能只露出半截（一半在屏幕外）。
    //
    // bringIntoViewRequester 交给 Compose 自己算：它知道滚动容器的可视范围，
    // 会把这一项完整带进视野。
    //
    // ⚠️ 但**光靠它不够**，实测踩到了：
    //
    //   bringIntoView 只在目标**完全不可见**时才滚动。卡片"下半截在屏幕外"
    //   属于部分可见，它判定"已经在视野里了" → 什么都不做。
    //   于是连续按向下键时，焦点卡片的上半截会被容器顶边切掉。
    //
    // 所以下面自己算一遍：拿卡片在窗口里的实际位置，和它所在滚动容器的可视
    // 范围比一比，差多少就精确滚多少。bringIntoView 保留作为兜底
    // （比如跨容器跳转那种它更擅长的情况）。
    //
    // 留白为什么是这些值：
    //   · 上 12 / 下 10 —— 电视普遍有 overscan（边缘会被切几像素），
    //     而且卡片聚焦时会放大 3%，上下都得留出余量。
    //   · 左右 24 —— 网格本身已有安全边距，这里只需要盖住放大和光晕。
    val bringPadTop = 12f
    val bringPadBottom = 10f
    val bringPadSide = 24f

    val bringRequester = androidx.compose.foundation.relocation.BringIntoViewRequester()
    val scopeForBring = rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(focusState.focused, focusScope) {
        if (!focusState.focused) return@LaunchedEffect
        // 轻微延迟：等布局稳定（尤其刚切分类、列表项才重组完）
        kotlinx.coroutines.delay(60)
        runCatching {
            bringRequester.bringIntoView(
                Rect(
                    left = -bringPadSide,
                    top = -bringPadTop,
                    right = bounds.width + bringPadSide,
                    bottom = bounds.height + bringPadBottom,
                )
            )
        }

        // ---------- 精确补齐：把被裁掉的部分滚出来 ----------
        if (focusScope == null) return@LaunchedEffect
        repeat(3) {
            val b = bounds
            if (b.isEmpty) return@repeat
            val vp = focusScope.viewportRectFor(b) ?: return@LaunchedEffect

            // 相对容器上下沿算差值：正数=内容在视口上方，需要往上滚（负 delta）
            val overTop = vp.top - b.top + bringPadTop
            val overBottom = b.bottom - vp.bottom + bringPadBottom
            val delta = when {
                overTop > 0f -> -overTop
                overBottom > 0f -> overBottom
                else -> 0f
            }
            if (delta == 0f) return@LaunchedEffect
            focusScope.scrollViewportBy(b, delta)
            // 等一帧，让滚动生效后再判断一次（可能有图片解码导致的尺寸变化）
            kotlinx.coroutines.delay(80)
        }
    }

    if (manager != null) {
        DisposableEffect(manager, key) {
            val item = TvFocusManager.Item(key, focusState) { bounds }
            item.onActivate = onClick
            item.onLeft = onLeft
            item.onRight = onRight
            item.onUp = onUp
            item.onDown = onDown
            item.enabled = enabled
            item.bringIntoView = {
                // 显式指定的滚动目标优先（跨区域跳转用）
                if (scrollKey != null) {
                    focusScope?.scrollTo(scrollKey)
                } else {
                    // 没有指定就交给 Compose 把自己完整滚进视野
                    scopeForBring.launch {
                        runCatching {
                            bringRequester.bringIntoView(
                                androidx.compose.ui.geometry.Rect(
                                    left = -bringPadSide,
                                    top = -bringPadTop,
                                    right = bounds.width + bringPadSide,
                                    bottom = bounds.height + bringPadBottom,
                                )
                            )
                        }
                    }
                }
            }
            manager.register(item)
            onDispose { manager.unregister(key) }
        }

        // 参数变化时刷新（注册只做一次，后续变化要同步进管理器）
        DisposableEffect(manager, key, onClick, enabled, onLeft, onRight, onUp, onDown) {
            manager.itemOf(key)?.let { it ->
                it.onActivate = onClick
                it.onLeft = onLeft
                it.onRight = onRight
                it.onUp = onUp
                it.onDown = onDown
                it.enabled = enabled
            }
            onDispose { }
        }
    }

    var m = this
        .bringIntoViewRequester(bringRequester)
        .onGloballyPositioned { bounds = it.boundsInWindow() }
        .scale(scale)
        .background(if (focusState.focused) focusedBackground else baseBackground)

    if (glow) {
        // 光晕加强：从 18dp 提到 30dp。
        // 实测电视上 18dp 在深色背景里太弱，用户看不清当前选中的是哪一项
        // （电视有 overscan、色彩也不如显示器准，对比度会被进一步吃掉）。
        m = m.shadow(
            elevation = 30.dp * glowAlpha,
            shape = shape,
            ambientColor = Ink.AccentBright,
            spotColor = Ink.AccentBright,
        )
    }

    if (borderWidth > 0.dp) {
        m = m.border(
            width = if (focusState.focused) borderWidth else 0.dp,
            brush = Brush.linearGradient(
                listOf(Ink.AccentBright, Ink.Accent, Ink.Pink.copy(alpha = 0.75f))
            ),
            shape = shape,
        )
    }

    if (onClick != null && enabled) {
        m = m.pointerInput(Unit) {
            detectTapGestures(onTap = {
                manager?.moveTo(key)
                onClick()
            })
        }
    }

    // 焦点变化回调（在自绘体系里由 manager 驱动 focusState，这里只做通知）
    DisposableEffect(focusState) {
        onDispose { }
    }
    if (onFocus != null) {
        androidx.compose.runtime.LaunchedEffect(focusState.focused) {
            onFocus.invoke(focusState.focused)
        }
    }

    return m
}

/** 顶部渐变遮罩，用于「沉浸式」让文字压在图片上仍然清晰。 */
fun Modifier.bottomScrim(alpha: Float = 1f): Modifier = this.background(
    Brush.verticalGradient(
        0.35f to Color.Transparent,
        0.7f to Ink.Deep.copy(alpha = 0.62f * alpha),
        1f to Ink.Deep.copy(alpha = 0.96f * alpha),
    )
)

/** 左侧渐变遮罩（配合横向列表） */
fun Modifier.leftScrim(): Modifier = this.background(
    Brush.horizontalGradient(
        0f to Ink.Deep.copy(alpha = 0.92f),
        1f to Color.Transparent,
    )
)

/** 自绘焦点描边（需要额外强调时用）。 */
@Composable
fun FocusRing(visible: Boolean, modifier: Modifier = Modifier, radius: Float = 18f) {
    if (!visible) return
    Canvas(modifier) {
        drawRoundRect(
            brush = Brush.linearGradient(listOf(Ink.AccentBright, Ink.Accent, Ink.Pink)),
            cornerRadius = CornerRadius(radius, radius),
            style = Stroke(width = 6f, cap = StrokeCap.Round),
            topLeft = Offset(3f, 3f),
            size = Size(size.width - 6f, size.height - 6f),
        )
    }
}

/**
 * 只注册一个「入口焦点 key」，不改变外观与按键行为。
 *
 * 用途：板块里某一项需要能被导航栏的右键直接跳进来（例如设置页第一张卡片、
 * 内容区第一个频道），但它自己不是可点击控件（真正的点击由内部控件处理）。
 */
@Composable
fun Modifier.entryFocusable(focusKey: Any): Modifier {
    val manager = LocalTvFocusManager.current
    val focusState = remember { TvFocusState() }
    var bounds by remember { mutableStateOf(Rect.Zero) }

    if (manager != null) {
        DisposableEffect(manager, focusKey) {
            val item = TvFocusManager.Item(focusKey, focusState) { bounds }
            manager.register(item)
            onDispose { manager.unregister(focusKey) }
        }
    }

    return this.onGloballyPositioned { bounds = it.boundsInWindow() }
}

/** 遥控器「确定 / OK」在各种设备上对应的键值。 */fun isEnterKeyCode(keyCode: Int): Boolean =
    keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
        keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER ||
        keyCode == android.view.KeyEvent.KEYCODE_BUTTON_A

/**
 * 全局焦点 key 约定。
 *
 * 自绘焦点系统里，焦点用「稳定 key」标识，跨区域的显式跳转（例如从左导航栏
 * 按右键进内容区）就靠这些 key 互通。命名统一放在这里，避免各屏幕各写一套字符串。
 */
object FocusKeys {
    fun nav(section: String): String = "nav:$section"
    fun entry(section: String): String = "entry:$section"
}

/**
 * 安全地把焦点移到某个 key：目标项可能还没组合出来（例如刚切板块），
 * 这里做有限次重试，不会崩，也保证最终会落到正确位置。
 */
@Composable
fun TvFocusManager.moveToSafely(key: Any, times: Int = 8) {
    androidx.compose.runtime.LaunchedEffect(key) {
        repeat(times) { attempt ->
            if (keys.contains(key)) {
                moveTo(key)
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(50L * (attempt + 1))
        }
    }
}
