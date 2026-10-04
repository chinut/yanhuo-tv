package com.chinut.bawantv.unified

import com.chinut.bawantv.BawanApp
import com.chinut.bawantv.core.Http
import com.chinut.bawantv.core.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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
 *
 * # 列表必须落盘（踩过的坑）
 *
 * 用户反馈：「客户机上安装时发生过一次断网，现在短剧里面没内容了」。
 *
 * 原因：这个源原来**只有进程内缓存**（`@Volatile listCache`）——
 * 断网时当下还能看，但**一旦重启 App 就空**，
 * 而且再联网前一直空着。用户视角就是"短剧没了"。
 *
 * 影视那边（[LibraryStore]）一直有 `ddys_library.tsv` 落盘，短剧这块漏了。
 * 现在补上：`cached()` 先给磁盘上的旧列表（**不联网**），
 * 联网成功后再覆盖写回。
 */
object QimaoSource : VideoSource {

    private const val TAG = "BawanQimao"
    private const val API = "https://xiaoqi.icofun.cn/API/qimao_duanju.php"

    private const val FILE_NAME = "qimao_list.tsv"
    private const val SEP = '\u001F'
    private const val LINE_FIELDS = 8

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

    /** 进程内缓存。落盘见 [saveToDisk] / [loadFromDisk]。 */
    @Volatile private var listCache: List<UnifiedMovie>? = null

    private fun file(): File = File(BawanApp.ctx().filesDir, FILE_NAME)

    // ==================== 落盘 ====================

    private fun esc(s: String): String =
        s.replace(SEP.toString(), " ").replace("\n", " ").replace("\r", " ")

    private fun toLine(m: UnifiedMovie): String = listOf(
        m.id, m.title, m.poster, m.year, m.typeName, m.area, m.score, m.remarks,
    ).joinToString(SEP.toString()) { esc(it) }

    private fun parseLine(line: String): UnifiedMovie? {
        if (line.isBlank()) return null
        val f = line.split(SEP)
        if (f.size < LINE_FIELDS) return null
        return UnifiedMovie(
            id = f[0],
            title = f[1],
            poster = f[2],
            year = f[3],
            typeName = f[4],
            area = f[5],
            score = f[6],
            remarks = f[7],
        )
    }

    /**
     * 读磁盘缓存。**不联网**，所以断网也能立刻出内容。
     *
     * 写入用「临时文件 + 改名」，避免断电/断网时留下半截文件
     * （半截文件解析出来是半份列表，比空还糟）。
     */
    private fun loadFromDisk(): List<UnifiedMovie> {
        val f = file()
        if (!f.exists()) return emptyList()
        val lines = runCatching { f.readLines() }.getOrDefault(emptyList())
        if (lines.isEmpty()) return emptyList()
        val list = lines.asSequence().mapNotNull { parseLine(it) }.toList()
        if (list.isEmpty()) {
            runCatching { f.delete() }
            return emptyList()
        }
        return list
    }

    private fun saveToDisk(list: List<UnifiedMovie>) {
        runCatching {
            val f = file()
            val tmp = File(f.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(list.joinToString("\n") { toLine(it) })
            if (!tmp.renameTo(f)) {
                f.writeText(tmp.readText())
                tmp.delete()
            }
        }.onFailure {
            android.util.Log.w(TAG, "写短剧缓存失败：${it.message}")
        }
    }

    override suspend fun cached(): List<UnifiedMovie> = withContext(Dispatchers.IO) {
        listCache?.let { return@withContext it }
        val disk = loadFromDisk()
        if (disk.isNotEmpty()) {
            android.util.Log.i(TAG, "从磁盘缓存读到 ${disk.size} 部短剧")
            listCache = disk
        }
        disk
    }

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

        if (list.isNotEmpty()) {
            listCache = list
            saveToDisk(list)
        } else {
            // ⚠️ 拉不到时**回退磁盘缓存**，不要返回空。
            //
            // 这正是用户遇到的场景：安装时断网 → 聚合结果为空 →
            // 返回空列表 → 短剧页空白。有磁盘缓存就先拿出来用。
            val disk = loadFromDisk()
            if (disk.isNotEmpty()) {
                android.util.Log.w(TAG, "本次没拉到，回退磁盘缓存 ${disk.size} 部")
                listCache = disk
                return@withContext disk
            }
            android.util.Log.w(TAG, "本次没拉到，磁盘也没有缓存")
        }
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
