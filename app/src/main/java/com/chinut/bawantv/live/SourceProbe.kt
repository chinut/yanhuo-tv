package com.chinut.bawantv.live

import com.chinut.bawantv.core.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 直播源健康检查。
 *
 * # 为什么需要它
 *
 * 央视的直连地址**会失效**。实测教训：
 *
 * · 抓取当时全部 HTTP 200，ffmpeg 也能解码
 * · 但过一段时间后 CDN 返回的分片数据是**损坏的** ——
 *   PotPlayer 里表现为"有声音、画面花屏"，ffmpeg 解出来是灰帧
 * · 用户真机上就是"一直正在接入"，播不出来
 *
 * 也就是说：**"能取到 m3u8"完全不等于"能播"**。
 *
 * # 判据
 *
 * 电视上跑不了 ffmpeg，所以用一套务实的替代判据：
 *
 * 1. m3u8 取得到、且是合法 playlist
 * 2. 能定位到分片地址（master 就再下一层）
 * 3. **分片数据量合理**（> 20KB；4 秒的 720p 分片约 300KB）
 * 4. **连续取 2 个分片都成功** —— 单次可能命中缓存
 * 5. 记录下载速度 —— 太慢的源在电视上就是"一直转圈"
 *
 * ## 这个判据查不出什么（必须写清楚）
 *
 * 它**查不出"数据完整但内容损坏"** —— 那需要解码器。
 * 实测遇到过：分片能完整下载、字节数正常，但 H.264 数据是坏的，
 * ffmpeg 报 `error while decoding MB`，画面是灰的。
 * 这种情况只能靠设备端解码或用户反馈发现。
 *
 * 所以本功能的定位是**筛掉明显死的源**，
 * **不承诺"检测通过就一定能看"**。
 */
object SourceProbe {

    private const val TAG = "BawanProbe"

    /** 播放列表最多读这么多（m3u8 本身很小）。 */
    private const val PLAYLIST_LIMIT = 256 * 1024

    /** 分片最多读这么多（4 秒 1080p 也不超过 2MB）。 */
    private const val SEGMENT_LIMIT = 4 * 1024 * 1024

    /** 分片小于这个字节数就认为不是有效数据。 */
    private const val MIN_SEGMENT_BYTES = 20 * 1024

    data class Result(
        val url: String,
        val ok: Boolean,
        val reason: String,
        /** 耗时（毫秒）。 */
        val ms: Long = 0,
        /** 平均下载速度（KB/s）。0 表示没测到。 */
        val kbps: Int = 0,
    )

    fun interface OnProgress {
        fun on(done: Int, total: Int, current: String, okCount: Int)
    }

    /**
     * 检测一个源。
     *
     * @param segments 取几个分片验证（默认 2 —— 单次成功可能是缓存命中）
     */
    suspend fun check(url: String, segments: Int = 2): Result =
        withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            try {
                val text = fetchText(url, PLAYLIST_LIMIT)
                    ?: return@withContext Result(url, false, "取不到 m3u8", elapsed(t0))
                if (!text.trimStart().startsWith("#EXTM3U")) {
                    return@withContext Result(url, false, "返回的不是 m3u8", elapsed(t0))
                }

                // master playlist → 再取一层，拿到真正的分片列表
                var base = url
                var body = text
                if (body.contains("#EXT-X-STREAM-INF")) {
                    val sub = firstNonComment(body)
                        ?: return@withContext Result(url, false, "master 里没有子列表", elapsed(t0))
                    val subUrl = absolute(base, sub)
                    body = fetchText(subUrl, PLAYLIST_LIMIT)
                        ?: return@withContext Result(url, false, "子列表取不到", elapsed(t0))
                    base = subUrl
                }

                val segs = body.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .take(segments)
                    .toList()
                if (segs.isEmpty()) {
                    return@withContext Result(url, false, "没有可下载的分片", elapsed(t0))
                }

                var totalBytes = 0L
                var totalMs = 0L
                segs.forEachIndexed { i, rel ->
                    val segUrl = absolute(base, rel)
                    val st = System.currentTimeMillis()
                    val data = fetchBytes(segUrl, SEGMENT_LIMIT)
                    totalMs += System.currentTimeMillis() - st
                    if (data == null) {
                        return@withContext Result(
                            url, false, "第 ${i + 1} 个分片下载失败", elapsed(t0),
                        )
                    }
                    if (data.size < MIN_SEGMENT_BYTES) {
                        return@withContext Result(
                            url, false,
                            "第 ${i + 1} 个分片过小（${data.size / 1024}KB）",
                            elapsed(t0),
                        )
                    }
                    totalBytes += data.size
                }

                val kbps = if (totalMs > 0) {
                    (totalBytes / 1024.0 / (totalMs / 1000.0)).toInt()
                } else 0
                Result(
                    url, true,
                    "正常（${segs.size} 个分片，${totalBytes / 1024}KB）",
                    elapsed(t0), kbps,
                )
            } catch (e: Exception) {
                Result(url, false, e.javaClass.simpleName, elapsed(t0))
            }
        }

    private fun elapsed(t0: Long) = System.currentTimeMillis() - t0

    private fun fetchText(url: String, limit: Int): String? =
        fetchBytes(url, limit)?.toString(Charsets.UTF_8)

    private fun fetchBytes(url: String, limit: Int): ByteArray? = try {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", Http.UA_DESKTOP)
            .header("Referer", "https://tv.cctv.com/")
            .header("Accept", "*/*")
            .build()
        // 每次用短超时的 client —— 检测不能让用户等太久
        val client = Http.client.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val bytes = resp.body?.bytes() ?: return null
            if (bytes.size > limit) null else bytes
        }
    } catch (e: Exception) {
        null
    }

    private fun firstNonComment(body: String): String? =
        body.lineSequence().map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }

    private fun absolute(base: String, rel: String): String = when {
        rel.startsWith("http") -> rel
        rel.startsWith("/") -> {
            val m = Regex("^(https?://[^/]+)").find(base)
            (m?.groupValues?.get(1) ?: "") + rel
        }
        else -> base.substringBeforeLast('/') + "/" + rel
    }

    /**
     * 检测一批源，**边测边回调**（界面要能看到进度）。
     *
     * 串行执行而不是并发：电视的 CPU 和网络都有限，
     * 并发几十个请求会把老电视拖垮，而用户只是想看进度。
     */
    suspend fun checkAll(
        urls: List<String>,
        onProgress: OnProgress,
    ): List<Result> = withContext(Dispatchers.IO) {
        val out = ArrayList<Result>(urls.size)
        var okCount = 0
        urls.forEachIndexed { i, u ->
            val r = check(u)
            if (r.ok) okCount++
            out.add(r)
            onProgress.on(i + 1, urls.size, u, okCount)
        }
        android.util.Log.i(TAG, "检测完成：$okCount / ${urls.size} 个源可用")
        out
    }
}
