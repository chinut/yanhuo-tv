package com.chinut.bawantv.live

import android.content.Context
import com.chinut.bawantv.core.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 源体检 —— 在**用户自己的网络**上验证源能不能看。
 *
 * # 为什么需要它
 *
 * 我在开发机上验证源时踩了一个绕不过去的坑：
 *
 *     ivi.bupt.edu.cn   → TCP 超时（连不上）
 *     39.135.34.150     → TCP 超时
 *     liveop.cctv.cn    → 域名解析失败
 *
 * **开发机访问不了国内很多源。** 所以"在我这里测能用/不能用"
 * 对用户没有意义 —— 他家的宽带是电信/联通/移动，策略完全不同。
 *
 * 反过来也一样：网上那些"每日自动检测"的项目（实测可用率
 * best-fan 12% / iptv-search 2%），检测是在**他们的机器**上做的。
 *
 * 唯一可靠的办法是**在用户自己的电视上测一遍**。
 *
 * # 和"内置扫描"的区别（为什么这个可以做，那个被否了）
 *
 * 用户否决过"本地自扫描"，理由是对的：它在电视上持有整张频道表和
 * 分片数据，内存压力大、易崩。
 *
 * 这个不一样：
 *   · **不生成频道表**，只给每个源打一个"能/不能"的标记
 *   · 一次只处理一个源，**不并发** —— 内存占用是常数
 *   · 只读前几个分片就断开，不缓冲整个流
 *   · 结果只存一个很小的 TSV
 *
 * 所以它是"体检"，不是"扫描"。
 *
 * # 判据（必须在用户网络上跑，所以判据要保守）
 *
 * 三层，全过才算可用：
 *   1. manifest 能取到且以 `#EXTM3U` 开头
 *   2. 能定位到分片（master 就再下一层）
 *   3. **连续 2 个分片都能完整下载**，且每个 > 20KB
 *
 * ⚠️ **测不出「画面静止」** —— 那需要解码多帧，电视上代价太大。
 * 所以标成"可用"的源仍可能是静止画面。这一点界面上要如实说。
 */
object SourceDoctor {

    private const val TAG = "BawanDoctor"

    /** 结果存这个文件：每行 `可用标记<TAB>地址`。 */
    private fun resultFile(context: Context) =
        java.io.File(context.filesDir, "live_checked.tsv")

    /** 读已体检的结论（url → 是否可用）。 */
    fun loadChecked(context: Context): Map<String, Boolean> = runCatching {
        resultFile(context).readLines()
            .mapNotNull { line ->
                val p = line.split('\t')
                if (p.size == 2) p[1] to (p[0] == "1") else null
            }
            .toMap()
    }.getOrDefault(emptyMap())

    /** 追加/覆盖一条结论。 */
    private fun save(context: Context, url: String, ok: Boolean) {
        runCatching {
            resultFile(context).appendText(
                (if (ok) "1" else "0") + "\t" + url + "\n",
                Charsets.UTF_8,
            )
        }
    }

    fun clear(context: Context) {
        runCatching { resultFile(context).delete() }
    }

    /** 体检进度。 */
    fun interface OnProgress {
        fun on(done: Int, total: Int, okCount: Int, current: String)
    }

    data class Outcome(val tested: Int, val ok: Int)

    /**
     * 给一批地址做体检。
     *
     * **串行执行**（不并发）—— 电视上并发拉流很容易把内存和带宽打满，
     * 这正是用户否决"本地扫描"的原因。串行慢一些，但稳。
     *
     * @param maxPerRun 一次最多测几个（给用户一个"分批"的感觉，别一次跑太久）
     */
    suspend fun run(
        context: Context,
        urls: List<String>,
        maxPerRun: Int = 60,
        onProgress: OnProgress,
    ): Outcome = withContext(Dispatchers.IO) {
        val list = urls.distinct().take(maxPerRun)
        var okCount = 0
        list.forEachIndexed { i, u ->
            val ok = runCatching { check(u) }.getOrDefault(false)
            save(context, u, ok)
            if (ok) okCount++
            onProgress.on(i + 1, list.size, okCount, u)
        }
        android.util.Log.i(TAG, "体检完成：${list.size} 个里 $okCount 个可用")
        Outcome(list.size, okCount)
    }

    /** 单个源的三层判据。 */
    private suspend fun check(url: String): Boolean {
        val text = getText(url) ?: return false
        if (!text.trimStart().startsWith("#EXTM3U")) return false

        var base = url
        var body = text
        if (body.contains("#EXT-X-STREAM-INF")) {
            // master：下钻到第一个子列表
            val sub = body.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                ?: return false
            val subUrl = absolute(base, sub)
            body = getText(subUrl) ?: return false
            base = subUrl
        }

        val segs = body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .take(2)
            .toList()
        if (segs.size < 2) return false

        // 连续 2 个分片都要能下下来，且不能太小
        for (rel in segs) {
            val data = getBytes(absolute(base, rel)) ?: return false
            if (data.size < 20 * 1024) return false
        }
        return true
    }

    private fun client() = Http.client.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private fun getBytes(url: String, limit: Int = 2 * 1024 * 1024): ByteArray? = try {
        val req = Request.Builder().url(url)
            .header("User-Agent", Http.UA_DESKTOP)
            .header("Accept", "*/*")
            .build()
        client().newCall(req).execute().use { r ->
            if (r.isSuccessful) r.body?.bytes()?.takeIf { it.size <= limit } else null
        }
    } catch (e: Exception) {
        null
    }

    private fun getText(url: String): String? =
        getBytes(url, limit = 256 * 1024)?.toString(Charsets.UTF_8)

    private fun absolute(base: String, rel: String): String = when {
        rel.startsWith("http") -> rel
        rel.startsWith("/") -> {
            val m = Regex("^(https?://[^/]+)").find(base)
            (m?.groupValues?.get(1) ?: "") + rel
        }
        else -> base.substringBeforeLast('/') + "/" + rel
    }
}
