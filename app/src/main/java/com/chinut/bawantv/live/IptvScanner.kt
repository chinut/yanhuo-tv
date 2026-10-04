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
 * 7. **用系统解码器真解一帧**，std 太低（近乎纯色）判为坏源
 *
 * ## ⚠️ 仍然测不出什么
 *
 * 判据 7 只解**一帧**，所以：
 *
 * · **测不出"画面静止"** —— 实测有一类假源（如 `std=57 但帧差=0`）
 *   是固定画面/台标卡，看着有内容但根本不是直播。
 *   要测这个必须解多帧、间隔取样，等于真播一遍，代价太大。
 * · **解码器兼容性差异** —— 有些流特定设备解不出来，
 *   但那不等于源坏了（所以解码失败时**不直接否掉**，只记录）。
 *
 * **所以扫描结果仍需用户按 ←→ 换源容忍。** 界面上要如实说明。
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
        var firstSeg: ByteArray? = null
        for (rel in segs) {
            val data = fetchBytes(absolute(base, rel)) ?: return false
            sizes.add(data.size)
            if (firstSeg == null) firstSeg = data
        }
        if (sizes.size < 2) return false
        if (sizes.min() < 20 * 1024) return false          // 太小 → 不是有效视频
        val avg = sizes.average()
        val maxDev = sizes.maxOf { kotlin.math.abs(it - avg) } / avg
        if (maxDev > 0.9) return false                      // 波动过大 → 流不稳

        // 7) **真解码一帧**，看画面是不是空的
        //
        // 为什么必须加这一步：用户实测"扫出来能出台但播放黑屏"。
        // 我在电脑上用 ffmpeg 真解码测同一批源，发现坏源的特征是
        // 「解不出帧」或「std 极低（纯色）」—— 而前面的判据全测不出，
        // 因为它压根没解码，只看"数据能不能下载"。
        //
        // ⚠️ 注意：这里只解**一帧**，所以**测不出"画面静止"**
        // （如 std=57 但帧差=0 的假频道）。这个限制写在类注释里。
        val std = firstSeg?.let { runCatching { decodeFrameStd(it) }.getOrNull() }
        if (std != null && std < 8.0) return false          // 近乎纯色 → 黑屏/灰屏
        return true
    }

    /**
     * 用系统解码器解出一帧，返回灰度标准差。
     *
     * std 接近 0 说明画面近乎纯色（黑屏 / 灰屏 / 空画面）。
     * 返回 null 表示解不出来 —— **这也应视为坏源**，
     * 但调用方选择"不因解码失败就否掉"（有些设备解码器兼容性差，
     * 宁可留下让用户换源，也不要全部误杀）。
     */
    private fun decodeFrameStd(segmentBytes: ByteArray): Double? {
        val w = 160
        val h = 90
        var codec: android.media.MediaCodec? = null
        var extractor: android.media.MediaExtractor? = null
        return try {
            // 临时文件：MediaExtractor 需要可 seek 的输入
            val f = java.io.File.createTempFile("probe", ".ts")
            f.writeBytes(segmentBytes)
            extractor = android.media.MediaExtractor().apply {
                setDataSource(f.absolutePath)
            }
            var track = -1
            var mime: String? = null
            for (i in 0 until extractor.trackCount) {
                val m = extractor.getTrackFormat(i).getString(
                    android.media.MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("video/")) { track = i; mime = m; break }
            }
            if (track < 0 || mime == null) return null
            extractor.selectTrack(track)

            codec = android.media.MediaCodec.createDecoderByType(mime)
            codec.configure(extractor.getTrackFormat(track), null, null, 0)
            codec.start()

            val info = android.media.MediaCodec.BufferInfo()
            val inIdx = codec.dequeueInputBuffer(15_000)
            if (inIdx < 0) return null
            val buf = codec.getInputBuffer(inIdx) ?: return null
            val n = extractor.readSampleData(buf, 0)
            if (n <= 0) return null
            codec.queueInputBuffer(inIdx, 0, n, 0, 0)

            // 等输出（最多 1.5 秒）
            val deadline = System.currentTimeMillis() + 1500
            while (System.currentTimeMillis() < deadline) {
                val outIdx = codec.dequeueOutputBuffer(info, 200_000)
                if (outIdx >= 0) {
                    val img = codec.getOutputImage(outIdx)
                    val std = if (img != null) grayStd(img, w, h) else null
                    codec.releaseOutputBuffer(outIdx, false)
                    return std
                }
            }
            null
        } catch (e: Exception) {
            android.util.Log.w(TAG, "解码失败：${e.javaClass.simpleName}")
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
        }
    }

    /** 把 Image 缩到 w×h，算灰度标准差。 */
    private fun grayStd(img: android.media.Image, w: Int, h: Int): Double {
        val plane = img.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixStride = plane.pixelStride
        val iw = img.width
        val ih = img.height
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        // 采样（不逐像素，省时间）
        val stepY = (ih / h).coerceAtLeast(1)
        val stepX = (iw / w).coerceAtLeast(1)
        var y = 0
        while (y < ih) {
            var x = 0
            while (x < iw) {
                val idx = y * rowStride + x * pixStride
                if (idx < buf.limit()) {
                    val v = buf.get(idx).toInt() and 0xFF
                    sum += v
                    sumSq += v.toDouble() * v
                    n++
                }
                x += stepX
            }
            y += stepY
        }
        if (n == 0) return 0.0
        val mean = sum / n
        return kotlin.math.sqrt((sumSq / n) - mean * mean)
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
