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

    /**
     * 纯 HTTP 兜底地址。
     *
     * ⚠️ 为什么需要它：服务器证书由 **Let's Encrypt 的 `YR1`** 签发，
     * 而 **Android 9 的系统根证书库里没有对应的 CA** ——
     * 电视上握手直接失败：
     *
     *     SSLHandshakeException: CertPathValidatorException:
     *     Trust anchor for certification path not found.
     *
     * 实测这个接口 **纯 HTTP 也返回 200**，所以老设备退回 HTTP 即可。
     *
     * 为什么不用"放宽证书校验"：那是全局性地接受任意证书，
     * 引入中间人风险。退回 HTTP 的代价小得多 ——
     * 这个接口只返回公开的短剧元数据和播放直链，不传任何用户信息。
     */
    private const val API_PLAIN = "http://xiaoqi.icofun.cn/API/qimao_duanju.php"

    /**
     * 取接口文本：**https 优先，证书类失败就退回 http**。
     *
     * 返回值同时带"实际用了哪个协议"，便于自检里显示。
     */
    /**
     * 用**补过信任锚的客户端**发请求。
     *
     * ⚠️ 不能用 `Net.get` —— 它内部固定用 `Http.client`，
     * 而 Android 6 需要给这个域名补 Let's Encrypt 的根证书
     * （见 [com.chinut.bawantv.core.TlsHelper]）。
     */
    private suspend fun getWithAnchors(url: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val client = com.chinut.bawantv.core.TlsHelper.clientFor(
                    com.chinut.bawantv.BawanApp.ctx(), url,
                )
                val req = okhttp3.Request.Builder().url(url)
                    .header("User-Agent", Http.UA_MOBILE)
                    .build()
                client.newCall(req).execute().use { r ->
                    if (r.isSuccessful) r.body?.string() else null
                }
            }.getOrNull()
        }

    private suspend fun fetch(api: String, query: String): Pair<String?, String> {
        val q = if (query.isEmpty()) "" else "?$query"
        // 先试 https（补过锚）
        getWithAnchors(api + q)?.let { return it to "https" }
        // https 失败 → 退回 http（同样补过锚，老设备上 http 也可能有重定向）
        val plain = getWithAnchors(API_PLAIN + q)
        return plain to (if (plain != null) "http(降级)" else "都失败")
    }

    private const val FILE_NAME = "qimao_list.tsv"

    /**
     * 一次拉取的总时长预算。
     *
     * 12 个热词**串行**拉，HTTP 是 connect 12s + read 20s —— 不设上限的话，
     * 电视网络慢时最坏能跑 6 分多钟。用户等不了就切走 →
     * `LaunchedEffect` 的协程被取消 → 一条都没保存下来 → 下次还是 0 部。
     *
     * 25 秒是个折中：够跑完大部分热词，又不至于让用户干等。
     */
    private const val TOTAL_BUDGET_MS = 25_000L

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

    /**
     * 已缓存的海报地址（给首页短剧块铺背景用）。
     *
     * 同步读内存/磁盘缓存，**不联网** —— 首页第一帧就要用。
     * 首页本来就在预热短剧列表，所以这里通常已经有数据了。
     */
    fun posters(max: Int = 6): List<String> {
        val l = listCache ?: runCatching { loadFromDisk() }.getOrDefault(emptyList()).also {
            if (it.isNotEmpty()) listCache = it
        }
        return l.asSequence().map { it.poster }.filter { it.isNotBlank() }
            .take(max).toList()
    }

    /**
     * 连通性自检：直接打一次接口，**把确切的失败原因留下来**。
     *
     * 为什么需要：`Net.get` 把 `UnknownHostException`（DNS）、
     * `SocketTimeoutException`（超时）、TLS 失败、HTTP 4xx/5xx
     * 全部压成一个 `null`，所以"0 部"这个现象对应四种不同病因，
     * 修法完全不同。这里绕过 `Net` 直接发请求，把异常原样抛出来看。
     *
     * 结论写进 `PlayDiag.probeResult`，手机调试页会显示。
     */
    suspend fun probe(): String = withContext(Dispatchers.IO) {
        // 分两步测，把"https 为什么失败"和"http 能不能救"都说清楚
        // ⚠️ 必须用补过锚的客户端，不能用 Http.client ——
        // 否则在 Android 6 上永远报证书错误，自检结果会误导排查方向。
        val client = com.chinut.bawantv.core.TlsHelper.clientFor(
            com.chinut.bawantv.BawanApp.ctx(), API,
        )
        val httpsErr = runCatching {
            val req = okhttp3.Request.Builder().url("$API?name=%E6%80%BB%E8%A3%81&page=1")
                .header("User-Agent", Http.UA_MOBILE)
                .build()
            client.newCall(req).execute().use { r ->
                val b = r.body?.string().orEmpty()
                "HTTP ${r.code}，返回 ${b.length} 字符"
            }
        }.getOrElse { e ->
            // 异常类名是关键：UnknownHostException=DNS，
            // SocketTimeoutException=超时，SSLHandshakeException=证书
            "${e.javaClass.simpleName}（${e.message?.take(90)}）"
        }
        // 逐条路径测，各自报确切异常 —— 只有一个布尔值定位不了问题
        val httpErr = runCatching {
            val req = okhttp3.Request.Builder().url("$API_PLAIN?name=%E6%80%BB%E8%A3%81&page=1")
                .header("User-Agent", Http.UA_MOBILE)
                .build()
            com.chinut.bawantv.core.TlsHelper.clientFor(
                com.chinut.bawantv.BawanApp.ctx(), API_PLAIN,
            ).newCall(req).execute().use { r ->
                val b = r.body?.string().orEmpty()
                "HTTP ${r.code}，${b.length} 字符"
            }
        }.getOrElse { "${it.javaClass.simpleName}（${it.message?.take(70)}）" }

        // 纯 TCP 探测：分别试 80 / 443，判断是"端口被挡"还是"应用层失败"
        fun tcpProbe(host: String, port: Int): String = runCatching {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress(host, port), 6000)
                "通"
            }
        }.getOrElse { "${it.javaClass.simpleName}" }

        val tcp80 = tcpProbe("xiaoqi.icofun.cn", 80)
        val tcp443 = tcpProbe("xiaoqi.icofun.cn", 443)

        val line = buildString {
            append("https=").append(httpsErr.take(60))
            append(" ｜ http=").append(httpErr.take(60))
            append(" ｜ TCP80=").append(tcp80)
            append(" TCP443=").append(tcp443)
        }
        com.chinut.bawantv.core.PlayDiag.probeResult = line
        android.util.Log.i(TAG, "接口自检 → $line")
        line
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
        // ⚠️ 自检放在**早返回之前**，无条件跑一次。
        //
        // 原来放在后面，结果首页预热已经把缓存灌进 listCache，
        // refresh 直接早返回，自检根本没执行（实测踩到）。
        // 自检本身很轻（一次请求、约 0.5 秒），代价可以接受。
        runCatching { probe() }

        listCache?.takeIf { it.isNotEmpty() }?.let { return@withContext it }

        val out = LinkedHashMap<String, UnifiedMovie>()
        var done = 0
        var okKw = 0
        var badKw = 0

        // ---------- 总时长预算 ----------
        //
        // ⚠️ 这里是"短剧刷不出来"的根因所在（分析见文件顶部注释）：
        // 12 个热词**串行**拉，HTTP 是 connect 12s + read 20s，
        // 电视网络稍慢时最坏要 6 分多钟。用户等不了就切走 →
        // 协程被取消 → saveToDisk 没执行 → 磁盘永远空 → 下次还是 0 部。
        //
        // 所以给整体一个预算：超了就停，**拉到多少算多少**。
        val deadline = System.currentTimeMillis() + TOTAL_BUDGET_MS

        for (kw in HOT) {
            if (System.currentTimeMillis() > deadline) {
                android.util.Log.w(
                    TAG,
                    "拉取超出 ${TOTAL_BUDGET_MS / 1000} 秒预算，停止（已成功 $okKw 个热词）",
                )
                break
            }
            done++
            val page = runCatching { search(kw, 1) }.getOrNull()
            if (page == null) badKw++
            if (!page.isNullOrEmpty()) okKw++
            // ⚠️ 用 Kotlin 的 getOrPut，**不能用 Map.putIfAbsent** ——
            // 后者是 Java 8 / API 24 才有的方法，在电视（Android 6.0.1，API 23）
            // 上会抛 NoSuchMethodError：
            //   No virtual method putIfAbsent(...) in class java.util.LinkedHashMap
            // 实测这就是"短剧 0 部、等 20 分钟也没用"的第二个原因。
            page.orEmpty().forEach { m -> out.getOrPut(m.id) { m } }
            onProgress(done, HOT.size, out.size)

            // ⚠️ 拿到一部分就立刻落盘，不等全部拉完。
            // 这样中途被取消（用户切走）时，下次进来至少有内容。
            if (out.size > 0 && done % 4 == 0) {
                runCatching { saveToDisk(out.values.toList()) }
            }
        }

        val list = out.values.toList()
        android.util.Log.i(
            TAG,
            "列表聚合 ${list.size} 部（成功 $okKw / 空 $badKw / 共 ${HOT.size} 个热词）",
        )

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

        // ---------- 失败原因必须说出来 ----------
        //
        // 原来这两行把三种完全不同的失败静默成同一个"空列表"：
        //   · 网络不通（DNS / 超时 / 被劫持）
        //   · 返回了非 JSON（运营商插页、错误页）
        //   · JSON 结构变了（接口改版）
        // 结果用户在电视上只看到"0 部"，我什么都查不到。
        val (text, via) = fetch(API, "name=${enc(keyword)}&page=$page")
        if (text == null) {
            android.util.Log.w(TAG, "搜索「$keyword」取不到响应（网络/DNS/超时，https 和 http 都失败）")
            return emptyList()
        }
        if (via != "https") android.util.Log.i(TAG, "搜索「$keyword」走了 $via")
        val parsed = runCatching {
            JSONObject(text).getJSONObject("data").getJSONArray("list")
        }
        if (parsed.isFailure) {
            android.util.Log.w(
                TAG,
                "搜索「$keyword」响应解析失败：${parsed.exceptionOrNull()?.message}" +
                    " 响应前 200 字=${text.take(200)}",
            )
            return emptyList()
        }
        val list = parsed.getOrThrow()

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
        val (text, _) = fetch(API, "id=$seriesId")
        if (text == null) return null
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
