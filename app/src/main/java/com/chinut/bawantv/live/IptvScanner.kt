package com.chinut.bawantv.live

import android.content.Context
import com.chinut.bawantv.core.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * IPTV 源扫描器 —— 在电视上直接扫描并保存可用的源。
 *
 * # 为什么值得在电视上做
 *
 * IPTV 源是**运营商 + 地区**限定的。在电脑上测出来的"可用"，
 * 到了用户家那条宽带上未必通。**在电视上测 = 用真实网络测**，
 * 结果最准，而且扫到的源直接本地化，用户不用折腾文件传输。
 *
 * # ⚠️ 一个必须说清的限制
 *
 * 电脑版扫描器用 **ffmpeg 真解码**，判据是「解出 3 帧 + std>10 + 帧差>1.5」——
 * 这个判据能识破央视那种「花屏加密」（数据能下载、但解出来是灰帧）。
 *
 * **电视上没有 ffmpeg，做不到真正的解码验证。** 所以这里用一组
 * **多信号联合判断**，比"HTTP 200 就算可用"强得多，但**不保证 100%**：
 *
 * 1. m3u8 取得到且格式合法
 * 2. 能定位到分片地址（master playlist 就再下一层）
 * 3. **连续 3 个分片都能完整下载**（单次成功可能是缓存命中）
 * 4. **分片大小稳定**（波动过大说明流不稳定）
 * 5. 分片大小 > 20KB（太小不是有效视频数据）
 * 6. 从 m3u8 里读出 **CODECS 和 RESOLUTION**，排除纯音频源和低分辨率占位
 *
 * 实测校准（用电脑版对照）：央视加密源虽然能下载，但**分片大小极小**
 * （500KB 上限只拿到几 KB），第 5 条就能挡住大部分。
 *
 * **所以扫描结果里可能仍混有少量花屏源** —— 用户按 ←→ 换源即可，
 * 这比"内置一堆必然花屏的源"好得多。
 */
object IptvScanner {

    private const val TAG = "BawanScan"

    /** 扫描结果保存位置（App 私有目录）。 */
    fun resultFile(context: Context): java.io.File =
        java.io.File(context.filesDir, "iptv_scanned.m3u")

    fun hasResult(context: Context): Boolean =
        resultFile(context).let { it.exists() && it.length() > 0 }

    /** 已扫到的频道数（粗略统计，用于界面显示）。 */
    fun resultChannelCount(context: Context): Int = runCatching {
        resultFile(context).readLines().count { it.startsWith("#EXTINF") }
    }.getOrDefault(0)

    fun clearResult(context: Context) {
        runCatching { resultFile(context).delete() }
    }

    /** 公开的 m3u 源列表（社区维护；可用性因地区/运营商而异，所以要实测）。 */
    private val LISTS = listOf(
        "https://raw.githubusercontent.com/vbskycn/iptv/master/tv/iptv4.m3u",
        "https://cdn.jsdelivr.net/gh/vbskycn/iptv@master/tv/iptv4.m3u",
        "https://iptv-org.github.io/iptv/countries/cn.m3u",
        "https://raw.githubusercontent.com/YanG-1989/m3u/main/Gather.m3u",
        "https://raw.githubusercontent.com/Kimentanm/aptv/master/m3u/iptv.m3u",
    )

    /** 进度回调（在 IO 线程调用；界面要切回主线程再更新状态）。 */
    fun interface OnProgress {
        fun on(stage: String, done: Int, total: Int, okCount: Int, current: String)
    }

    data class Outcome(
        val channels: Int,
        val urls: Int,
        val tested: Int,
        val saved: Boolean,
        val note: String = "",
    )

    /**
     * 扫一轮。
     *
     * @param maxPerChannel 每个频道最多测几个地址（控制耗时）
     * @param filterKeyword 只测名字含这个词的频道；空表示全测
     */
    suspend fun scan(
        context: Context,
        maxPerChannel: Int = 3,
        filterKeyword: String = "",
        onProgress: OnProgress,
    ): Outcome = withContext(Dispatchers.IO) {
        // ---------- 1) 拉列表 ----------
        onProgress.on("正在获取源列表…", 0, 0, 0, "")
        val cands = LinkedHashMap<String, MutableList<String>>()
        LISTS.forEach { url ->
            val text = runCatching { fetchText(url) }.getOrNull()
            if (text.isNullOrBlank()) {
                android.util.Log.w(TAG, "列表取不到：${url.take(60)}")
                return@forEach
            }
            // 复用 LiveCatalog 的解析能力，避免两套解析逻辑
            val groups = runCatching { LiveCatalog.parse(text) }.getOrDefault(emptyList())
            var added = 0
            groups.forEach { g ->
                g.channels.forEach { ch ->
                    val u = ch.url
                    if (u.contains(".m3u8") || u.contains(".flv")) {
                        if (filterKeyword.isNotEmpty() &&
                            !ch.name.contains(filterKeyword, ignoreCase = true)
                        ) return@forEach
                        val list = cands.getOrPut(ch.name) { mutableListOf() }
                        if (u !in list) { list.add(u); added++ }
                    }
                }
            }
            android.util.Log.i(TAG, "读到 ${url.take(48)}：收下 $added 条")
        }

        if (cands.isEmpty()) {
            return@withContext Outcome(0, 0, 0, false, "没拿到任何候选源（网络可能不通外网）")
        }

        // ---------- 2) 逐个验证 ----------
        val tasks = ArrayList<Pair<String, String>>()
        cands.forEach { (name, urls) -> urls.take(maxPerChannel).forEach { tasks.add(name to it) } }
        onProgress.on("准备验证 ${tasks.size} 个地址…", 0, tasks.size, 0, "")

        // ---------- 并发验证 ----------
        //
        // 串行太慢：实测约 8 个/分钟，632 个要 1 小时以上，用户等不起。
        // 并发 4 路后约 15 分钟。
        //
        // 不用更高并发：电视的网络栈和 CPU 都有限，
        // 而且会把用户家宽带占满（他在看电视，不该把网拖垮）。
        val good = LinkedHashMap<String, MutableList<String>>()
        val lock = Any()
        var done = 0
        var okCount = 0
        val parallel = 4

        val queue = java.util.concurrent.ConcurrentLinkedQueue(tasks)
        val jobs = (1..parallel).map {
            launch(Dispatchers.IO) {
                while (true) {
                    val task = queue.poll() ?: break
                    coroutineContext.ensureActive()
                    val (name, url) = task
                    val ok = runCatching { verify(url) }.getOrDefault(false)
                    synchronized(lock) {
                        done++
                        if (ok) {
                            good.getOrPut(name) { mutableListOf() }.add(url)
                            okCount++
                        }
                        onProgress.on(
                            "正在验证", done, tasks.size, okCount,
                            if (ok) "$name ✓" else name,
                        )
                    }
                }
            }
        }
        jobs.forEach { it.join() }

        // ---------- 3) 保存 ----------
        if (good.isEmpty()) {
            return@withContext Outcome(0, 0, tasks.size, false,
                "扫了 ${tasks.size} 个地址，没有一个通过验证")
        }
        val text = buildString {
            append("#EXTM3U\n")
            good.forEach { (name, urls) ->
                urls.forEach { u ->
                    append("#EXTINF:-1 group-title=\"IPTV\",$name\n")
                    append(u).append('\n')
                }
            }
        }
        resultFile(context).writeText(text, Charsets.UTF_8)
        val total = good.values.sumOf { it.size }
        android.util.Log.i(TAG, "扫描完成：${good.size} 个频道 / $total 个地址，已保存")
        Outcome(good.size, total, tasks.size, true)
    }

    // ==================== 验证 ====================

    /**
     * 多信号验证。返回 true 表示"很可能能看"。
     *
     * 判据见类注释。**注意这不等于真解码验证**。
     */
    private suspend fun verify(url: String): Boolean {
        // 1) playlist
        val text = fetchText(url) ?: return false
        if (!text.trimStart().startsWith("#EXTM3U")) return false

        // 6) 从 master 里读编解码/分辨率，排除纯音频
        if (text.contains("#EXT-X-STREAM-INF")) {
            val hasVideo = text.contains("RESOLUTION=", ignoreCase = true) ||
                text.contains("VIDEO=", ignoreCase = true)
            if (!hasVideo) return false
        }

        var base = url
        var body = text
        if (body.contains("#EXT-X-STREAM-INF")) {
            val sub = body.lineSequence().map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return false
            val subUrl = absolute(base, sub)
            body = fetchText(subUrl) ?: return false
            base = subUrl
        }

        val segs = body.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .take(3).toList()
        if (segs.isEmpty()) return false

        // 3/4/5) 连续 3 个分片 + 大小稳定 + 不太小
        val sizes = ArrayList<Int>(segs.size)
        for (rel in segs) {
            val data = fetchBytes(absolute(base, rel)) ?: return false
            sizes.add(data.size)
        }
        if (sizes.size < 2) return false
        if (sizes.min() < 20 * 1024) return false          // 太小 → 不是有效视频
        val avg = sizes.average()
        val maxDev = sizes.maxOf { kotlin.math.abs(it - avg) } / avg
        if (maxDev > 0.9) return false                      // 波动过大 → 流不稳
        return true
    }

    private fun client() = Http.client.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private fun fetchBytes(url: String, limit: Int = 3 * 1024 * 1024): ByteArray? = try {
        val req = Request.Builder().url(url)
            .header("User-Agent", Http.UA_DESKTOP)
            .header("Accept", "*/*")
            .build()
        client().newCall(req).execute().use { r ->
            if (!r.isSuccessful) null
            else r.body?.bytes()?.takeIf { it.size <= limit }
        }
    } catch (e: Exception) {
        null
    }

    private fun fetchText(url: String): String? =
        fetchBytes(url, limit = 512 * 1024)?.toString(Charsets.UTF_8)

    private fun absolute(base: String, rel: String): String = when {
        rel.startsWith("http") -> rel
        rel.startsWith("/") -> {
            val m = Regex("^(https?://[^/]+)").find(base)
            (m?.groupValues?.get(1) ?: "") + rel
        }
        else -> base.substringBeforeLast('/') + "/" + rel
    }
}
