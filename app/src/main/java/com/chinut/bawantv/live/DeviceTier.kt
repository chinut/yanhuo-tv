package com.chinut.bawantv.live

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * 设备能力分档。
 *
 * ## 为什么不能只看系统版本
 *
 * 直播选清晰度档位时，我原来用的判断是：
 *
 *     SDK_INT < 24 && 非 64 位        // 只覆盖 Android 5.x / 6.0
 *
 * 这个条件**太窄了**。真机反馈是"新电视也卡、十几年的电视直接播不出来"，
 * 而很多十几年的电视跑的是 **Android 7/8 + 四核弱 CPU + 1GB 内存** ——
 * 它们完全躲过了上面的判断，于是被喂了 720p/1.8Mbps，
 * 解码跟不上、缓冲永远填不满，表现就是"卡"甚至"黑屏"。
 *
 * 实测日志（模拟器，软件解码）：
 *
 *     硬解 15000ms 没出画面但也没报错，再等 12000ms
 *     硬解最终未出画面，退回网页播放
 *
 * 注意：**没有报错**。说明不是"编解码器不支持"，而是**这个码率它吃不下**。
 * 所以正确的方向是按设备能力**降档**，而不是死等或退回 WebView。
 *
 * ## 判定依据
 *
 * 综合 CPU 核数、内存大小、API 版本、ABI 宽度给出三档。
 * 单一指标都不可靠：有的老电视是 4 核但内存只有 512MB；
 * 有的新电视是 32 位系统（便宜方案）但 CPU 尚可。
 */
object DeviceTier {

    enum class Tier {
        /** 弱设备：老电视、低内存盒子。只用最低码率，优先"能播"。 */
        LOW,

        /** 中等：大部分国产电视。允许到 480p/576p。 */
        MID,

        /** 强设备：旗舰电视 / 盒子。允许到 720p。 */
        HIGH,
    }

    @Volatile
    private var cached: Tier? = null

    fun of(context: Context): Tier = cached ?: synchronized(this) {
        cached ?: detect(context).also { cached = it }
    }

    fun isLowEnd(context: Context): Boolean = of(context) == Tier.LOW

    private fun detect(context: Context): Tier {
        val cores = runCatching { Runtime.getRuntime().availableProcessors() }.getOrDefault(1)
        val is64 = Build.SUPPORTED_ABIS.any { it.contains("64") }
        val sdk = Build.VERSION.SDK_INT
        val ramMb = totalRamMb(context)
        // 低内存设备标记：系统自己都认为内存不够用，这是很硬的信号
        val lowRamDevice = runCatching {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
        }.getOrDefault(false)

        val tier = when {
            // 系统自报低内存 → 直接算弱设备
            lowRamDevice -> Tier.LOW

            // 内存很小（512MB 级）→ 弱设备
            ramMb in 1..700 -> Tier.LOW

            // 单核 / 双核 → 弱设备
            cores <= 2 -> Tier.LOW

            // 很老的系统 + 32 位 → 弱设备
            sdk < 23 && !is64 -> Tier.LOW

            // 内存偏小（1GB 级）或 32 位老系统 → 中等偏弱
            ramMb in 1..1100 -> Tier.LOW

            // 四核 + 32 位 + 老系统 → 中等
            !is64 && sdk < 26 -> Tier.MID

            // 新系统 + 64 位 + 四核以上 → 强
            is64 && sdk >= 26 && cores >= 4 -> Tier.HIGH

            else -> Tier.MID
        }

        android.util.Log.i(
            "BawanLive",
            "设备分档=$tier（核数=$cores 内存=${ramMb}MB 64位=$is64 API=$sdk 低内存标记=$lowRamDevice）",
        )
        return tier
    }

    /** 设备总内存（MB）。拿不到返回 0。 */
    private fun totalRamMb(context: Context): Int = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        (mi.totalMem / (1024L * 1024L)).toInt()
    }.getOrDefault(0)

    /**
     * 该档位下直播允许的最大视频尺寸与码率。
     *
     * 为什么不"锁死某一档"而是给上限：
     * 多码率流（央视就是 720p/576p/480p/360p 四档）交给自适应去挑，
     * 上限只是保证它**不会挑到设备吃不下的档**。够用的时候它自己会升上去。
     *
     * @return (宽, 高, 码率)
     */
    /**
     * 按**手动设置**给出上限。0 返回 null 表示"走自动判定"。
     *
     * 档位对应关系刻意做得保守：
     *   省流 → LOW（640x360）
     *   标清 → MID（854x480）
     *   高清 → HIGH（1280x720）
     *   超清 → 1080p
     *   原画 → **不设上限**
     *
     * ## ⚠️「上限」的语义是"**排除超过它的轨**"
     *
     * 这点非常关键，也是花屏的根因：
     * 有些流**只有单档高清**（实测 CCTV-1 那条只有 1920x1080），
     * 而 LOW 档的上限是 640x360 —— 于是**唯一的视频轨被排除**，
     * 解码器一帧都不出，屏幕上就是花屏。
     *
     * 实测日志（花屏时的状态）：
     *     state=READY playing=true err=none **sawFrame=false**
     *     状态 READY、位置在走、不报错，但一帧都没渲染出来。
     *
     * 所以除了加「原画」，还在 [com.chinut.bawantv.live.LivePlayerScreen]
     * 里加了自动放宽：起播后一直不出帧就去掉限制重试一次。
     */
    fun manualLimit(quality: Int): Triple<Int, Int, Int>? = when (quality) {
        1 -> videoLimit(Tier.LOW)
        2 -> videoLimit(Tier.MID)
        3 -> videoLimit(Tier.HIGH)
        4 -> Triple(1920, 1080, 8_000_000)
        // 5 = 原画：用 Int.MAX_VALUE 表示"不限"
        5 -> Triple(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
        else -> null
    }

    fun videoLimit(tier: Tier): Triple<Int, Int, Int> = when (tier) {
        // 只留最低档：640x360 / 600kbps 是央视流里最省的一档
        Tier.LOW -> Triple(640, 360, 700_000)
        // 中间档：854x480 / 900kbps
        Tier.MID -> Triple(854, 480, 1_100_000)
        // 强设备：允许 720p，但是否升上去由自适应决定
        Tier.HIGH -> Triple(1280, 720, 2_000_000)
    }
}
