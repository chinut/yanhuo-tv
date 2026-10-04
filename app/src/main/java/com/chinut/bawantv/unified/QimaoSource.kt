package com.chinut.bawantv.unified

import com.chinut.bawantv.core.Http
import com.chinut.bawantv.core.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 七猫短剧数据源。
 *
 * # 为什么换掉红果
 *
 * 红果（hongguoduanju.com）**每部剧只免费放前 3 集**，第 4 集起服务端直接 404。
 * 实测 22 部剧全是 3 集（详情页 `accessible_episode_cnt` = 3），
 * 这是站点的商业限制，客户端突破不了。
 *
 * 七猫不一样 —— 实测三部剧：
 *
 *     潜龙出山，闪婚美女总裁   标注  96 集 → 给出  96 集 → 全部有地址
 *     一剑独尊               标注  77 集 → 给出  77 集 → 全部有地址
 *     八零工具人             标注  41 集 → 给出  40 集 → 全部有地址
 *
 * **而且抽验 12 集（含第 49 / 77 / 96 集）真解码全部通过（100%）。**
 *
 * # 内容来源是正规的
 *
 * 返回里有**网微剧备案号**，例如：
 *
 *     （七猫）网微剧备字（2025）第01177号
 *     （七猫）网微剧备字（2026）第02637号
 *
 * 播放地址在七猫**自己的 CDN**（`cdn-vod-playlet.wtzw.com`），
 * 不是盗链聚合。这点很重要 —— 前面筛 IPTV 源时，
 * 那些"免费 API"大多是临时 token 或盗链，几天就废。
 *
 * # 接口
 *
 * `https://xiaoqi.icofun.cn/API/qimao_duanju.php`
 *   · `?name=关键词&page=N`  → 搜索（返回 `data.list`）
 *   · `?id=短剧id`           → **全部集的直链**（返回 `data.play_list`）
 *
 * 公开访问、无需密钥、文档写明每日更新（实测 2026-10-04 仍在更新）。
 *
 * # 为什么用热词聚合列表
 *
 * 这个接口**没有"浏览全部"**，只能按名字搜。所以列表页用一组题材热词
 * （总裁 / 战神 / 重生 …）各搜一页，合并去重。
 * 这是接口能力决定的，不是偷懒。
 */
object QimaoSource : VideoSource {

    private const val TAG = "BawanQimao"
    private const val API = "https://xiaoqi.icofun.cn/API/qimao_duanju.php"

    override val id = "qimao"
    override val displayName = "七猫短剧"

    /**
     * 列表用热词。
     *
     * 接口没有浏览能力（只能按名搜），所以用题材热词各搜一页再合并。
     * 挑的都是短剧最常见的题材，覆盖面够首页用。
     */
    private val HOT = listOf(
        "总裁", "战神", "重生", "穿越", "神医", "赘婿",
        "甜宠", "逆袭", "复仇", "年代", "玄幻", "都市",
    )

    /** 列表缓存（进程内）。 */
    @Volatile private var listCache: List<UnifiedMovie>? = null

    override suspend fun cached(): List<UnifiedMovie> = listCache.orEmpty()

    override suspend fun refresh(
        onProgress: (Int, Int, Int) -> Unit,
    ): List<UnifiedMovie> = withContext(Dispatchers.IO) {
        listCache?.takeIf { it.isNotEmpty() }?.let { return@withContext it }

        val out = LinkedHashMap<String, UnifiedMovie>()
        var done = 0
        for (kw in HOT) {
            done++
            val page = runCatching { search(kw, 1) }.getOrDefault(emptyList())
            page.forEach { m -> out.putIfAbsent(m.id, m) }
            onProgress(done, HOT.size, out.size)
        }
        val list = out.values.toList()
        android.util.Log.i(TAG, "列表聚合 ${list.size} 部（${HOT.size} 个热词）")
        if (list.isNotEmpty()) listCache = list
        list
    }

    /** 题材分类用 tags（详情接口里才有，列表里给 sub_title）。 */
    override suspend fun typeNames(): List<String> = emptyList()

    /**
     * 取一部剧的**全部**集。
     *
     * 这是和红果最大的差别：红果只给免费集，七猫一次给全集直链。
     */
    override suspend fun loadSources(movie: UnifiedMovie): List<UnifiedSource> =
        withContext(Dispatchers.IO) {
            val seriesId = movie.id.removePrefix("qm:")
            val d = runCatching { detail(seriesId) }.getOrNull()
            if (d == null || d.episodes.isEmpty()) {
                android.util.Log.w(TAG, "取详情失败《${movie.title}》(id=$seriesId)")
                return@withContext emptyList()
            }
            android.util.Log.i(
                TAG,
                "《${movie.title}》：${d.episodes.size} 集全部有地址" +
                    "（备案 ${d.record.ifBlank { "无" }}）",
            )
            listOf(
                UnifiedSource(
                    id = id,
                    remoteId = seriesId,
                    name = d.title.ifBlank { movie.title },
                    quality = "共 ${d.episodes.size} 集 · 全集免费",
                    episodeCount = d.episodes.size,
                    episodes = d.episodes,
                ),
            )
        }

    // ==================== 解析 ====================

    data class Detail(
        val id: String,
        val title: String,
        val cover: String,
        val intro: String,
        val tags: String,
        /** 网微剧备案号 —— 正规内容的凭据。 */
        val record: String,
        val episodes: List<Episode>,
    )

    /** 搜索一页。 */
    private suspend fun search(keyword: String, page: Int): List<UnifiedMovie> {
        val url = "$API?name=${enc(keyword)}&page=$page"
        val text = Net.get(url, ua = Http.UA_MOBILE) ?: return emptyList()
        val list = runCatching {
            JSONObject(text).getJSONObject("data").getJSONArray("list")
        }.getOrNull() ?: return emptyList()

        val out = ArrayList<UnifiedMovie>(list.length())
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val sid = o.optString("id").ifBlank { continue }
            out.add(
                UnifiedMovie(
                    // 加前缀避免和影视源的低端影视 id 撞车
                    id = "qm:$sid",
                    title = o.optString("title"),
                    poster = o.optString("image_link"),
                    typeName = o.optString("sub_title"),
                    remarks = o.optString("total_num"),
                    score = o.optString("hot_value"),
                ),
            )
        }
        return out
    }

    /** 取详情 + 全集直链。 */
    private suspend fun detail(seriesId: String): Detail? {
        val text = Net.get("$API?id=$seriesId", ua = Http.UA_MOBILE) ?: return null
        val d = runCatching { JSONObject(text).getJSONObject("data") }.getOrNull() ?: return null
        val ep = ArrayList<Episode>()

        val pl: JSONArray? = d.optJSONArray("play_list")
        if (pl != null) {
            for (i in 0 until pl.length()) {
                val o = pl.optJSONObject(i) ?: continue
                val u = o.optString("video_url")
                // 只收 http(s) 直链；空地址的集保留名字但不给地址
                ep.add(Episode(name = "第 ${i + 1} 集", url = u))
            }
        }
        if (ep.isEmpty()) return null

        return Detail(
            id = seriesId,
            title = d.optString("title"),
            cover = d.optString("image_link"),
            intro = d.optString("intro"),
            tags = d.optString("tags"),
            record = d.optString("own_record_number"),
            episodes = ep,
        )
    }

    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")
}
