package com.chinut.bawantv.core

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import java.io.File

/**
 * 内存探针。
 *
 * ## 为什么要有这个
 *
 * 电视的内存普遍很小（512MB~1.5GB），而这个 App 实测 PSS 在 260~290MB ——
 * 接近 1GB 电视的三分之一。调优时必须能看到**实时数字**，
 * 否则只能靠"感觉卡不卡"猜，改完也不知道有没有用。
 *
 * 于是提供两条通道：
 *
 *   1. **屏幕浮层**（[AppPrefs.showMemoryHud]）—— 边用边看，最直观
 *   2. **周期日志**（启动后自动）—— 每 [LOG_INTERVAL_MS] 写一条 logcat，
 *      事后可以拉出完整时间线，看"哪个操作把内存顶上去了"
 *
 * ## 为什么用 PSS 而不是 RSS
 *
 * RSS 把共享库（libc、图形驱动、Chromium 的公共段）整份算进每个进程，
 * 数字会虚高很多（实测同一时刻 PSS 279MB / RSS 433MB）。
 * PSS 按共享页的**实际分摊**计算，才是"这个 App 真正占了多少内存"。
 *
 * 但也保留 RSS 和 Native/Java 分项 —— 判断"是泄漏还是缓存堆积"要看分项走势。
 */
object MemProbe {

    private const val TAG = "BawanMem"

    /** 浮层刷新间隔。太快会自己增加开销、也看不清。 */
    const val HUD_INTERVAL_MS = 1_500L

    /** 日志间隔。默认关闭。
     *
     * 之所以默认关：每 5 秒 dumpsys 级别的读取本身要开销，
     * 而且会刷屏。测试内存时在设置里打开即可。 */
    const val LOG_INTERVAL_MS = 5_000L

    /** 一次采样结果。 */
    data class Snapshot(
        /** 应用自身 PSS（KB）—— 最该看的数字 */
        val pssKb: Int,
        /** 应用自身 RSS（KB） */
        val rssKb: Int,
        /** Java 堆（KB） */
        val javaKb: Int,
        /** Native 堆（KB） */
        val nativeKb: Int,
        /** 图形内存（KB）—— 电视上硬件渲染时才有值 */
        val graphicsKb: Int,
        /** 代码段（KB）—— DEX + so + apk mmap，未开 R8 时是大头 */
        val codeKb: Int,
        /** 设备总内存（MB） */
        val deviceTotalMb: Int,
        /** 设备当前可用内存（MB） */
        val deviceAvailMb: Int,
        /** 系统是否把这个进程标记为低内存 */
        val lowMemory: Boolean,
    ) {
        /** 形如 "PSS 279MB · RSS 433MB · N 59/J 20" 的一行摘要 */
        fun short(): String {
            val pss = pssKb / 1024
            val rss = rssKb / 1024
            return "PSS ${pss}MB · RSS ${rss}MB · N ${nativeKb / 1024} / J ${javaKb / 1024}"
        }

        /** 多行详情（浮层用） */
        fun detail(): String = buildString {
            append("PSS ").append(pssKb / 1024).append(" MB")
            append("   RSS ").append(rssKb / 1024).append(" MB\n")
            append("Native ").append(nativeKb / 1024)
            append("   Java ").append(javaKb / 1024)
            append("   Code ").append(codeKb / 1024).append(" MB\n")
            if (graphicsKb > 0) {
                append("Graphics ").append(graphicsKb / 1024).append(" MB\n")
            }
            append("设备 ").append(deviceAvailMb).append(" / ").append(deviceTotalMb)
            append(" MB 可用")
            if (lowMemory) append("  ⚠低内存")
        }

        /** 一行日志（方便 grep 和画时间线） */
        fun logLine(tag: String): String =
            "$tag pss=${pssKb} rss=${rssKb} java=${javaKb} native=${nativeKb} " +
                "code=${codeKb} gfx=${graphicsKb} devAvail=${deviceAvailMb} " +
                "devTotal=${deviceTotalMb} lowMem=$lowMemory"
    }

    /** 采一次样。任何一项拿不到就记 0，不抛异常。 */
    fun sample(context: Context): Snapshot {
        val mi = Debug.MemoryInfo()
        runCatching { Debug.getMemoryInfo(mi) }

        // Debug.MemoryInfo 的各分项（单位 KB）
        val javaKb = runCatching { mi.dalvikPss }.getOrDefault(0)
        val nativeKb = runCatching { mi.nativePss }.getOrDefault(0)
        val codeKb = runCatching { mi.getMemoryStat("summary.code")?.toIntOrNull() ?: 0 }
            .getOrDefault(0)
        val gfxKb = runCatching { mi.getMemoryStat("summary.graphics")?.toIntOrNull() ?: 0 }
            .getOrDefault(0)

        var deviceTotalMb = 0
        var deviceAvailMb = 0
        var lowMemory = false
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            deviceTotalMb = (info.totalMem / (1024L * 1024L)).toInt()
            deviceAvailMb = (info.availMem / (1024L * 1024L)).toInt()
            lowMemory = info.lowMemory
        }

        return Snapshot(
            pssKb = mi.totalPss,
            rssKb = readRssKb(),
            javaKb = javaKb,
            nativeKb = nativeKb,
            codeKb = codeKb,
            graphicsKb = gfxKb,
            deviceTotalMb = deviceTotalMb,
            deviceAvailMb = deviceAvailMb,
            lowMemory = lowMemory,
        )
    }

    /** 从 /proc/self/statm 读 RSS（KB）。 */
    private fun readRssKb(): Int = runCatching {
        File("/proc/self/statm").readText().trim().split(' ')
            .getOrNull(1)?.toLongOrNull()?.let { pages ->
                (pages * 4).toInt()   // 页大小 4KB（Android 上恒为 4KB）
            } ?: 0
    }.getOrDefault(0)
}
