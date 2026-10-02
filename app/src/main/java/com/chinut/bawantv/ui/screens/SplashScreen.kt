package com.chinut.bawantv.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chinut.bawantv.ui.HomeWarmup
import com.chinut.bawantv.ui.theme.Ink
import com.chinut.bawantv.ui.theme.sdp
import com.chinut.bawantv.ui.theme.ssp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 开屏动画。
 *
 * 视觉主线：**一颗焰火从屏幕左边沿波浪轨迹飞到右边，边飞边洒星火，飞到右端炸开**，
 * 正好呼应「焰火TV / 焰火随想 美好随现」。设计稿（横版主视觉，含 Logo 与 slogan）
 * 作为整屏背景，内容不做任何改动，动画只叠在它上面：
 *
 *  1. 背景：设计稿从 0.92 倍缓慢推到 1.0（Ken Burns 呼吸感）+ 淡入
 *  2. 焰火：从左向右飞过 2 个波峰，带彗尾拖尾与沿途火花，抵达右侧后绽放
 *  3. slogan 副标题延迟淡入
 *  4. 飞行结束即进入主页（飞行进度同时充当加载进度）
 */
@Composable
fun SplashScreen(onFinish: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(0.75f) }
    val subAlpha = remember { Animatable(0f) }

    /** 焰火从左飞到右的进度 0→1。 */
    val flight = remember { Animatable(0f) }

    /** 尾部整屏淡出由 MainActivity 的 AnimatedVisibility 负责，本文件不再自己淡。 */

    val aurora = rememberInfiniteTransition(label = "aurora")
    val drift by aurora.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing)),
        label = "drift",
    )

    LaunchedEffect(Unit) {
        val startedAt = System.currentTimeMillis()

        // 和动画**并行**预热首页（频道表 + 海报片单）。
        // 开屏本来就要播几秒，拿这段时间把首页备好，切过去就是完整的，
        // 不会出现"直播区黑着、海报墙空着，过一会儿才补上"。
        val warm = launch { runCatching { HomeWarmup.warmUp(context) } }

        // 1) 主标题淡入 + 放大
        alpha.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
        scale.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
        // 2) 副标题稍后跟上
        subAlpha.animateTo(1f, tween(450))
        // 3) 焰火飞过整屏 + 右端绽放
        //    比之前拉长了一截（2.6s → 3.4s），飞得更从容，也给预热留出时间
        flight.animateTo(1.6f, tween(3400, easing = LinearEasing))

        // 4) 动画播完了，但首页可能还没备好 —— 等它，同时保证：
        //    · 至少播够 minSplashMs（太短会一闪而过，很难看）
        //    · 最多等 maxSplashMs（网络再差别让用户一直盯开屏）
        val elapsed = System.currentTimeMillis() - startedAt
        val minWait = (HomeWarmup.minSplashMs - elapsed).coerceAtLeast(0L)
        if (minWait > 0) delay(minWait)

        val warmDeadline = HomeWarmup.maxSplashMs - (System.currentTimeMillis() - startedAt)
        if (warmDeadline > 0) {
            // 谁先到就听谁的：预热完成，或者超时
            withTimeoutOrNull(warmDeadline) { warm.join() }
        }
        warm.cancel()

        // 5) 交接：报告完成。
        //    **淡出不放在这里**，由 MainActivity 的 AnimatedVisibility 统一负责 ——
        //    放在控制层才能保证「首页在开屏还完全不透明时就已经渲染好」，
        //    淡化过程两屏都有内容，不会闪一下黑底。
        onFinish()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(Ink.Violet, Ink.Soft, Ink.Deep),
                    start = Offset(0f, 0f),
                    end = Offset(1920f, 1080f),
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        // ---------- 极光背景 ----------
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val x1 = w * (0.18f + 0.24f * drift)
            val y1 = h * (0.24f + 0.16f * drift)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Ink.Accent.copy(alpha = 0.30f), Color.Transparent),
                    center = Offset(x1, y1),
                    radius = w * 0.42f,
                ),
                radius = w * 0.42f,
                center = Offset(x1, y1),
            )
            val x2 = w * (0.84f - 0.22f * drift)
            val y2 = h * (0.78f - 0.14f * drift)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Ink.Pink.copy(alpha = 0.22f), Color.Transparent),
                    center = Offset(x2, y2),
                    radius = w * 0.36f,
                ),
                radius = w * 0.36f,
                center = Offset(x2, y2),
            )
        }

        // ---------- 品牌主视觉 ----------
        //
        // 设计稿（横版开屏，Logo + slogan「焰火随想 美好随现」）作为整屏主视觉，
        // **原图内容不做任何改动**，动画只叠在它上面：
        //  · 从 0.92 倍缓慢推到 1.0（Ken Burns 呼吸感）+ 淡入
        //  · 一颗焰火从左沿波浪轨迹飞到右，带彗尾与星火，到右端绽放
        val brand = remember { com.chinut.bawantv.BawanApp.splashBrandRes }
        if (brand != 0) {
            val kenBurns by animateFloatAsState(
                targetValue = if (alpha.value > 0.2f) 1f else 0.92f,
                animationSpec = tween(2600, easing = FastOutSlowInEasing),
                label = "kenBurns",
            )
            Image(
                painter = painterResource(id = brand),
                contentDescription = "焰火TV",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(alpha.value)
                    .scale(kenBurns),
            )

            // 焰火飞行：主题动画，同时充当「正在准备」的进度
            FireworkFlight(progress = flight.value)
        } else {
            // ---------- 兜底：没有主视觉素材时，用极光 + 文字开屏 ----------
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val x1 = w * (0.18f + 0.24f * drift)
                val y1 = h * (0.24f + 0.16f * drift)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Ink.Accent.copy(alpha = 0.30f), Color.Transparent),
                        center = Offset(x1, y1),
                        radius = w * 0.42f,
                    ),
                    radius = w * 0.42f,
                    center = Offset(x1, y1),
                )
                val x2 = w * (0.84f - 0.22f * drift)
                val y2 = h * (0.78f - 0.14f * drift)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Ink.Pink.copy(alpha = 0.22f), Color.Transparent),
                        center = Offset(x2, y2),
                        radius = w * 0.36f,
                    ),
                    radius = w * 0.36f,
                    center = Offset(x2, y2),
                )
            }

            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val logo = remember { com.chinut.bawantv.BawanApp.logoRes }
                if (logo != 0) {
                    Image(
                        painter = painterResource(id = logo),
                        contentDescription = "焰火TV",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(280.sdp)
                            .alpha(alpha.value)
                            .scale(scale.value),
                    )
                } else {
                    Text(
                        "焰火TV",
                        color = Color.White,
                        fontSize = 92.ssp,
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .alpha(alpha.value)
                            .scale(scale.value),
                    )
                }
                Spacer(Modifier.height(18.sdp))
                Text(
                    "焰火随想  美好随现",
                    color = Ink.AccentBright.copy(alpha = 0.88f),
                    fontSize = 22.ssp,
                    letterSpacing = 6.ssp,
                    modifier = Modifier.alpha(subAlpha.value),
                )
            }
        }

        // ---------- 右下角版本号 ----------
        Text(
            text = "v" + com.chinut.bawantv.BuildConfig.VERSION_NAME,
            color = Ink.TextFaint,
            fontSize = 13.ssp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .alpha(subAlpha.value)
                .then(Modifier),
        )
    }
}

/**
 * 焰火飞行：一颗焰火从屏幕左边沿曲折轨迹飞到右边，边飞边洒星火，抵达右端绽放。
 *
 * 轨迹用「水平推进 + 正弦起伏」表达，起伏幅度沿路径先大后小，飞起来更自然
 * （像被抛出去的焰火，而不是机械的锯齿）。
 *
 * 另外还留一条**延迟消散的光轨**：记录飞过的历史位置，按「年龄」渐隐并微微下沉，
 * 于是焰火过去之后，身后会残留一条流星尾迹，再慢慢散开。
 *
 * @param progress 飞行进度：0→1 为从左飞到右，1→1.6 为右端绽放并淡出
 */
@Composable
private fun FireworkFlight(progress: Float) {
    // 光轨残留参数：寿命越大尾巴越长，下沉量越大越像烟雾
    val flightDurationMs = 2000f   // 与 animateTo 的 2600ms(0→1.6) 对应
    val residueLife = 0.38f
    val residueDrift = 26f

    Canvas(
        Modifier
            .fillMaxSize()
            // blur() 要求编译期常量，不能用 @Composable 的缩放属性；
            // 而且模糊半径本来也不该随分辨率放大，用固定 dp 更合适
            .blur(0.5.dp)
    ) {
        if (progress <= 0f) return@Canvas

        val w = size.width
        val h = size.height

        // 轨迹：起点略在屏幕外，终点在右侧（让「从屏幕外飞出去」的收尾更自然）
        val startX = -w * 0.05f
        val endX = w * 1.03f
        val baseY = h * 0.46f
        val amp = h * 0.18f
        // 曲折感关键：波数取 3 左右（约 1.5 个完整来回），配合正弦就能读出明显的折返
        val waves = 3.0f


        val headT = progress.coerceIn(0f, 1f)

        // ---------- 透视：飞出去再飞回来（近大远小）----------
        //
        // 深度用正弦控制：起点在近处 → 中段飞到最远（最小）→ 末端又回到近处（最大），
        // 于是观感就是「从屏幕里飞出去、绕一圈、又飞回屏幕前」。
        // 透视用最朴素的一分式投影 scale = d / (d + z)，z 越大越远、越小越近。
        val zMax = 3.05f
        val camD = 2.15f
        val cx0 = w * 0.5f
        val cy0 = h * 0.47f

        fun depthAt(t: Float): Float =
            (kotlin.math.sin(t * Math.PI.toFloat()) * zMax).coerceAtLeast(0f)

        fun scaleFor(z: Float): Float = camD / (camD + z)

        /** 透视变换：远端的点向画面中心收拢（用固定收拢中心，避免路径扭曲抖动）。 */
        fun project(p: Offset, z: Float): Offset {
            val s = scaleFor(z)
            return Offset(cx0 + (p.x - cx0) * s, cy0 + (p.y - cy0) * s)
        }

        /** 深度带来的亮度/大小系数：远处暗而小，近处亮而大。 */
        fun nearness(z: Float): Float = scaleFor(z)

        // 二维路径（未投影）
        fun pathAt(t: Float): Offset {
            val x = startX + (endX - startX) * t
            val envelope = 1f - 0.30f * t
            val y = baseY +
                kotlin.math.sin(t * waves * 2f * Math.PI.toFloat()) * amp * envelope -
                amp * 0.22f * t
            return Offset(x, y)
        }

        fun screenAt(t: Float): Offset = project(pathAt(t), depthAt(t))

        val headZ = depthAt(headT)
        val headScale = nearness(headZ)
        val head = screenAt(headT)

        // ---------- 1) 延迟消散的光轨（流星尾迹）----------
        //
        // 飞过的位置会留下残影，残影按「年龄」渐隐并微微下沉；
        // 每个采样点同时带上它当时的透视大小与亮度，
        // 于是光轨远端细而暗、近端粗而亮 —— 这是"飞出去又飞回来"最直观的线索。
        if (headT > 0.005f && progress < 1f) {
            val columns = 130
            for (i in 1 until columns) {
                val t = i / columns.toFloat()
                if (t > headT) break
                val ageSec = (headT - t) * flightDurationMs / 1000f
                val life = (1f - ageSec / residueLife).coerceIn(0f, 1f)
                if (life <= 0.02f) continue

                val z = depthAt(t)
                val near = nearness(z)
                val p = screenAt(t)
                val drop = (1f - life) * residueDrift
                val c = Offset(p.x, p.y + drop)

                drawCircle(
                    color = Ink.AccentBright.copy(alpha = 0.40f * life * life * near),
                    radius = (1.2f + 7.5f * life) * (0.45f + 0.75f * near),
                    center = c,
                )
                if (i % 5 == 0) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Ink.AccentBright.copy(alpha = 0.22f * life * near),
                                Color.Transparent,
                            ),
                            center = c,
                            radius = (34f * life + 8f) * (0.5f + 0.7f * near),
                        ),
                        radius = (34f * life + 8f) * (0.5f + 0.7f * near),
                        center = c,
                    )
                }
            }
        }

        // ---------- 2) 彗尾：沿轨迹回采样，带上各自的透视大小 ----------
        val tailCount = 34
        for (i in tailCount downTo 1) {
            val t = (headT - i * 0.009f)
            if (t < 0f) continue
            val z = depthAt(t)
            val near = nearness(z)
            val p = screenAt(t)
            val k = 1f - i / tailCount.toFloat()          // 1 = 靠近头部

            drawCircle(
                color = Color.White.copy(alpha = 0.62f * k * k * near),
                radius = (7.5f * k + 1.2f) * (0.4f + 0.8f * near),
                center = p,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Ink.Pink.copy(alpha = 0.34f * k * near),
                        Color.Transparent,
                    ),
                    center = p,
                    radius = (32f * k + 10f) * (0.5f + 0.7f * near),
                ),
                radius = (32f * k + 10f) * (0.5f + 0.7f * near),
                center = p,
            )
        }

        // 3) 沿途星火：固定散布点，被扫过之后点亮再渐暗（也带透视大小）
        val sparks = 26
        for (i in 0..sparks) {
            val seed = i * 1.37f
            val sx = w * ((seed % 1f) * 1.06f) - w * 0.03f
            val sy = h * 0.16f + h * 0.62f * ((seed * 3.7f) % 1f)
            // 用横向位置近似它被飞过的时间点
            val tAt = ((sx - startX) / (endX - startX)).coerceIn(0f, 1f)
            val passed = headT - tAt
            if (passed <= 0f) continue
            val fade = (1f - passed * 1.5f).coerceIn(0f, 1f)
            if (fade <= 0.02f) continue

            val near = nearness(depthAt(tAt))
            val jx = (i % 5 - 2) * 7f
            val jy = (i % 3 - 1) * 9f
            drawCircle(
                color = if (i % 3 == 0) {
                    Ink.AccentBright.copy(alpha = fade * near)
                } else {
                    Color.White.copy(alpha = fade * 0.9f * near)
                },
                radius = (2.2f + 2.6f * fade) * (0.5f + 0.8f * near),
                center = Offset(sx + jx, sy + jy),
            )
        }

        // 4) 焰火头部：亮核 + 两层光晕，整体乘透视缩放
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Ink.Pink.copy(alpha = 0.38f * headScale), Color.Transparent),
                center = head,
                radius = 200f * headScale,
            ),
            radius = 200f * headScale,
            center = head,
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Ink.AccentBright.copy(alpha = 0.48f * headScale), Color.Transparent),
                center = head,
                radius = 100f * headScale,
            ),
            radius = 100f * headScale,
            center = head,
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White, Ink.AccentBright.copy(alpha = 0.55f)),
                center = head,
                radius = 28f * headScale,
            ),
            radius = 28f * headScale,
            center = head,
        )
        drawCircle(color = Color.White, radius = 8.5f * headScale, center = head)

        // ---------- 绽放阶段：飞行到末端后炸开一朵花，并整体淡出 ----------
        if (progress >= 1f) {
            val boom = ((progress - 1f) / 0.6f).coerceIn(0f, 1f)
            if (boom > 0f && boom < 1f) {
                val fade = 1f - boom
                // 终点在屏幕外一点，绽放要往回收一点才看得见
                val center = Offset(head.x - w * 0.09f, head.y - h * 0.04f)
                val petals = 24
                for (i in 0 until petals) {
                    val ang = (i / petals.toFloat()) * 2f * Math.PI.toFloat()
                    val len = 50f + 430f * boom
                    val p = Offset(
                        center.x + kotlin.math.cos(ang) * len,
                        center.y + kotlin.math.sin(ang) * len,
                    )
                    drawCircle(
                        color = when (i % 3) {
                            0 -> Color.White.copy(alpha = 0.9f * fade)
                            1 -> Ink.AccentBright.copy(alpha = 0.85f * fade)
                            else -> Ink.Pink.copy(alpha = 0.75f * fade)
                        },
                        radius = 15f * fade + 3f,
                        center = p,
                    )
                    // 花瓣拖尾：让绽放更有速度感
                    drawCircle(
                        color = Color.White.copy(alpha = 0.25f * fade),
                        radius = 5f * fade + 1f,
                        center = Offset(
                            center.x + kotlin.math.cos(ang) * len * 0.72f,
                            center.y + kotlin.math.sin(ang) * len * 0.72f,
                        ),
                    )
                }
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.6f * fade), Color.Transparent),
                        center = center,
                        radius = 300f * (0.4f + boom),
                    ),
                    radius = 300f * (0.4f + boom),
                    center = center,
                )
            }
        }
    }
}
