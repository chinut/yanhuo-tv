package com.chinut.bawantv.hongguo

import com.chinut.bawantv.core.Http
import com.chinut.bawantv.core.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 红果短剧（hongguoduanju.com）的抓取层。
 *
 * # 为什么是"爬网页"而不是调接口
 *
 * 那个短剧 App 走不通（Flutter 编译产物、接口要 sign 签名、还有证书固定），
 * 但**它的官网**是服务端渲染的，而且内容都在 HTML 里 —— 实测确认：
 *
 * | 页面 | 怎么拿 | 要点 |
 * |---|---|---|
 * | 分类页 /category/real-drama | 一次 GET | 24 部剧；剧名、封面、集数、series_id 都在 HTML |
 * | 详情页 /detail?series_id=xxx | 一次 GET | **`"vid_list"` 数组含全部分集 id** |
 * | 播放页 /player/{sid}/{vid} | 一次 GET | SSR 里有 `video_player_info.main_url` |
 *
 * 也就是说**整个数据层不需要 WebView、不需要执行 JS、不需要签名**。
 * 这对老电视很重要 —— 跑 OkHttp 毫无压力，跑 WebView 就跑不动。
 *
 * # 免费集限制
 *
 * 详情页里 `"accessible_episode_cnt"` 说明能免费看几集（实测是 3）。
 * 超出范围的播放页会返回 **404**，这不是异常，是正常的付费墙。
 * 所以 [episodes] 拿到的地址要标上"可能不可播"，播放时按失败处理。
 *
 * # 踩过的坑
 *
 * · `vid_list` 有 72 个，但页面 DOM 里只有 3 个 `<a>` —— 分集列表是**懒加载**的。
 *   不要试图从 DOM 结构解析，直接正则抽 `vid_list` 数组。
 * · 用真实浏览器打开时视频变成 `blob:`（MSE），`main_url` 反而拿不到。
 *   所以取地址一律用**普通 GET**（不带浏览器 UA 的那些复杂头），别用 WebView 去取。
 */
object Hongguo {

    private const val TAG = "BawanHongguo"
    private const val BASE = "https://hongguoduanju.com"

    /** 分类。slug 是官网 URL 里的那一段。 */
    data class Category(val slug: String, val name: String)

    val CATEGORIES = listOf(
        Category("real-drama", "真人剧"),
        Category("comic-drama", "漫剧"),
        Category("ai-drama", "AI 剧"),
    )

    /** 列表页里的一部剧。 */
    data class Brief(
        val seriesId: String,
        val title: String,
        val cover: String,
        val episodeCount: Int,
        /** 分类里带的子标签（爱情/家庭…），可能为空。 */
        val tags: List<String> = emptyList(),
    )

    /** 详情页解析结果。 */
    data class Detail(
        val seriesId: String,
        val title: String,
        val cover: String,
        val intro: String,
        /** 全部分集 id，按顺序。 */
        val vids: List<String>,
        /** 免费可看的集数（超出会 404）。 */
        val freeCount: Int,
    )

    // ==================== 列表 ====================

    /**
     * 拉一个分类的剧集列表。
     *
     * @param page 从 1 开始。实测分类页 `total=800`，有分页。
     */
    suspend fun list(category: Category, page: Int = 1): List<Brief> =
        withContext(Dispatchers.IO) {
            val url = if (page <= 1) {
                "$BASE/category/${category.slug}"
            } else {
                "$BASE/category/${category.slug}?page=$page"
            }
            val html = Net.get(url, ua = Http.UA_DESKTOP) ?: run {
                Log.w("列表页取不到：$url")
                return@withContext emptyList()
            }
            val out = parseBriefs(html)
            Log.i("列表 ${category.name} 第 $page 页：${out.size} 部")
            out
        }

    /** 首页推荐（首页 HTML 里也是同样的卡片结构）。 */
    suspend fun home(): List<Brief> = withContext(Dispatchers.IO) {
        val html = Net.get(BASE + "/", ua = Http.UA_DESKTOP) ?: return@withContext emptyList()
        parseBriefs(html)
    }

    /**
     * 从列表/首页 HTML 里解析剧集卡片。
     *
     * 结构（实测）：
     * ```
     * <a href="/detail?series_id=7672388285074787352" ...>
     *   <picture><img src="https://p3-novel.byteimg.com/..." /></picture>
     *   <p class="pc-episode-xxx">全64集</p>
     *   <p class="pc-title-xxx m-title-xxx">千里共婵娟</p>
     *   <div class="pc-tag-xxx"><span>家庭</span></div>
     * </a>
     * ```
     * 不能按 DOM 精确解析（类名带哈希、每版都变），所以按**卡片块**切分再逐个抽。
     */
    internal fun parseBriefs(html: String): List<Brief> {
        val out = LinkedHashMap<String, Brief>()

        // 按 series_id 出现的位置切块，每块覆盖到下一个 series_id 之前
        val marks = Regex("""/detail\?series_id=(\d{10,25})""").findAll(html).toList()
        for ((i, m) in marks.withIndex()) {
            val sid = m.groupValues[1]
            if (out.containsKey(sid)) continue
            val start = m.range.first
            val end = if (i + 1 < marks.size) marks[i + 1].range.first
            else minOf(html.length, start + 3000)
            val block = html.substring(start, end)

            val title = Regex("""<p[^>]*class="[^"]*title[^"]*"[^>]*>([^<]{1,80})</p>""")
                .find(block)?.groupValues?.get(1)?.trim().orEmpty()

            // 封面：块里第一张图片
            val cover = Regex("""(?:src|data-src)="(https://[^"]+)"""")
                .find(block)?.groupValues?.get(1).orEmpty()

            // 「全64集」
            val epCount = Regex("""全\s*(\d+)\s*集""")
                .find(block)?.groupValues?.get(1)?.toIntOrNull() ?: 0

            val tags = Regex("""<span[^>]*class="[^"]*tag-text[^"]*"[^>]*>([^<]{1,12})</span>""")
                .findAll(block).map { it.groupValues[1].trim() }
                .filter { it.isNotEmpty() }.take(3).toList()

            if (title.isNotEmpty()) {
                out[sid] = Brief(sid, title, cover, epCount, tags)
            }
        }
        return out.values.toList()
    }

    // ==================== 详情 ====================

    /**
     * 拉详情。**一次请求就有全部分集 id**（`vid_list` 数组）。
     *
     * 这一步是整个短剧功能的关键：有 `vid_list` 才能列出分集、
     * 才能做"一集播完播下一集"。
     */
    suspend fun detail(seriesId: String): Detail? = withContext(Dispatchers.IO) {
        val url = "$BASE/detail?series_id=$seriesId"
        val html = Net.get(url, ua = Http.UA_DESKTOP) ?: run {
            Log.w("详情页取不到：$url")
            return@withContext null
        }
        parseDetail(html, seriesId)?.also {
            Log.i("详情 ${it.title}：${it.vids.size} 集（免费 ${it.freeCount}）")
        }
    }

    internal fun parseDetail(html: String, seriesId: String): Detail? {
        val vids = grabVidList(html)
        if (vids.isEmpty()) {
            Log.w("详情页里没找到 vid_list（seriesId=$seriesId）")
            return null
        }
        val title = str(html, "series_name")
            .ifBlank { Regex("""<title[^>]*>([^<]{1,80})""").find(html)
                ?.groupValues?.get(1)?.substringBefore('_')?.trim().orEmpty() }
        val cover = Regex("""<img[^>]+(?:src|data-src)="(https://[^"]*(?:byteimg|douyinpic|fqnovelpic)[^"]*)"""")
            .find(html)?.groupValues?.get(1).orEmpty()
        val intro = str(html, "series_intro").ifBlank { str(html, "abstract") }
        val free = int(html, "accessible_episode_cnt")
            ?: int(html, "episode_cnt") ?: vids.size
        return Detail(seriesId, title, cover, intro, vids, free)
    }

    /** 抽 `"vid_list": ["...", "..."]`。注意括号里可能有几十上百项。 */
    internal fun grabVidList(html: String): List<String> {
        val i = html.indexOf("\"vid_list\"")
        if (i < 0) return emptyList()
        val j = html.indexOf('[', i)
        val k = html.indexOf(']', j)
        if (j < 0 || k < 0 || k <= j) return emptyList()
        return Regex("""["'](\d{10,25})["']""")
            .findAll(html.substring(j + 1, k))
            .map { it.groupValues[1] }
            .toList()
    }

    // ==================== 播放地址 ====================

    /**
     * 取某一集的直连播放地址。
     *
     * 用**普通 GET**（不是 WebView）—— 实测这样才拿得到 SSR 里的 `main_url`；
     * 用真实浏览器打开反而会变成 `blob:`（MSE），取不到。
     *
     * 返回 null 的两种情况都算正常：
     *   · 超出免费范围（页面 404）
     *   · 页面结构变了
     */
    suspend fun streamUrl(seriesId: String, vid: String): String? =
        withContext(Dispatchers.IO) {
            val url = "$BASE/player/$seriesId/$vid"
            val html = Net.get(url, ua = Http.UA_DESKTOP) ?: run {
                Log.w("播放页取不到：$url")
                return@withContext null
            }
            val mu = parseMainUrl(html)
            if (mu == null) {
                Log.w("播放页里没有 main_url：$url（可能是免费集之外的）")
            } else {
                Log.i("取到地址：${mu.take(60)}…")
            }
            mu
        }

    /** 从播放页 HTML 抽 `video_player_info.main_url`。 */
    internal fun parseMainUrl(html: String): String? {
        // 先试直接正则（最快）
        Regex(""""main_url"\s*:\s*"([^"]{20,})"""")
            .find(html)?.groupValues?.get(1)?.let { return unescape(it) }

        // 退回：定位 video_player_info 再配对花括号（字段顺序不保证）
        val i = html.indexOf("\"video_player_info\"")
        if (i < 0) return null
        val j = html.indexOf('{', i)
        if (j < 0) return null
        var depth = 0
        var k = j
        var inStr = false
        var esc = false
        while (k < html.length) {
            val c = html[k]
            when {
                esc -> esc = false
                c == '\\' -> esc = true
                c == '"' -> inStr = !inStr
                !inStr && c == '{' -> depth++
                !inStr && c == '}' -> {
                    depth--
                    if (depth == 0) {
                        val obj = html.substring(j, k + 1)
                        return Regex(""""main_url"\s*:\s*"([^"]{20,})"""")
                            .find(obj)?.groupValues?.get(1)?.let { unescape(it) }
                    }
                }
            }
            k++
        }
        return null
    }

    /** 页面里的地址做过 `\u002F` 转义，要还原。 */
    private fun unescape(s: String): String =
        s.replace("\\u002F", "/").replace("\\/", "/")

    // ---------- 小工具 ----------

    private fun str(html: String, key: String): String =
        Regex(""""$key"\s*:\s*"([^"]{0,600})"""")
            .find(html)?.groupValues?.get(1)?.let { unescape(it) }.orEmpty()

    private fun int(html: String, key: String): Int? =
        Regex(""""$key"\s*:\s*(\d+)""").find(html)?.groupValues?.get(1)?.toIntOrNull()

    /** 日志。取源链路每一步都留痕 —— 静默失败最难查。 */
    private object Log {
        fun i(msg: String) = android.util.Log.i(TAG, msg)
        fun w(msg: String) = android.util.Log.w(TAG, msg)
    }
}
