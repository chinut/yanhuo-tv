package com.chinut.bawantv.ddys

import com.chinut.bawantv.core.AppPrefs
import com.chinut.bawantv.core.Http
import com.chinut.bawantv.core.Net
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 低端影视上的一部影视作品。 */
data class DdysMovie(
    val slug: String,
    val title: String,
    val poster: String = "",
    val year: String = "",
    val type: String = "",
    val typeCode: String = "",
    val region: String = "",
    val rating: String = "",
    val updatedAt: String = "",
    val isCompleted: Boolean = false,
    /** 剧情简介（详情接口才有） */
    val intro: String = "",
    val director: String = "",
    val actors: String = "",
    val onlineCount: Int = 0,
    val genres: List<String> = emptyList(),
    /** 详情里的所有可播线路 */
    val sources: List<DdysSource> = emptyList(),
) {
    val hasPoster: Boolean get() = poster.startsWith("http")
}

/** 一条播放线路：name + 可能的「剧集列表」。 */
data class DdysSource(
    val id: String,
    val name: String,
    val quality: String,
    /** 剧集：电影只有一条（label 为空），剧集是「第1集 / 普通话 / 粤语版」等 */
    val episodes: List<DdysEpisode>,
) {
    /** 单集（电影）直接播第一条 */
    val isSingle: Boolean get() = episodes.size <= 1
}

data class DdysEpisode(val label: String, val url: String)

/**
 * 低端影视（DDYS）客户端。
 *
 * 重要事实（已实测确认）：
 *  - 官方永久域名是 ddys.io，备用域名 ddys.pics / ddys.live / ddys.help，
 *    四个域名返回完全一致的接口数据，所以可以直接轮流用，谁通就用谁。
 *  - 官方 JSON 接口 `{domain}/api/v1/...` **没有任何验证/防盗链**，
 *    不需要 Cookie、不需要 Referer、不需要执行 JS，普通 GET 就能拿到数据。
 *  - 视频源（m3u8）由 `.../sources` 接口直接给出，ExoPlayer 可以直接播。
 *  - ddys.app 是需要密码 + 点选验证码的仿站（品牌名都不是「低端影视」），不要使用。
 *
 * 因此这里不做任何「绕过验证」的 hack：走官方 JSON 接口是最稳、最干净的方案。
 */
class DdysApi(private val domain: String) {

    private val base: String = normalise(domain)

    // ==================== 接口 ====================

    /** 最新更新（默认按 updated_at 倒序），瀑布流首屏用。 */
    suspend fun latest(page: Int = 1, perPage: Int = 30): Pair<List<DdysMovie>, Int> {
        val json = get("/api/v1/movies?page=$page&per_page=${perPage.coerceIn(1, 50)}")
            ?: return emptyList<DdysMovie>() to 1
        val root = runCatching { JSONObject(json) }.getOrNull()
            ?: return emptyList<DdysMovie>() to 1
        val items = parseList(root.optJSONArray("data"))
        val pages = root.optJSONObject("meta")?.optInt("total_pages") ?: 1
        return items to pages
    }

    /** 分类筛选：type=movie/series/variety/anime，genre/region/year 可选。 */
    suspend fun browse(
        type: String? = null,
        genre: String? = null,
        region: String? = null,
        year: String? = null,
        sort: String? = null,
        page: Int = 1,
        perPage: Int = 30,
    ): Pair<List<DdysMovie>, Int> {
        val q = buildString {
            append("?page=").append(page)
            append("&per_page=").append(perPage.coerceIn(1, 50))
            if (!type.isNullOrBlank()) append("&type=").append(type)
            if (!genre.isNullOrBlank()) append("&genre=").append(genre)
            if (!region.isNullOrBlank()) append("&region=").append(region)
            if (!year.isNullOrBlank()) append("&year=").append(year)
            if (!sort.isNullOrBlank()) append("&sort=").append(sort)
        }
        val json = get("/api/v1/movies$q") ?: return emptyList<DdysMovie>() to 1
        val root = runCatching { JSONObject(json) }.getOrNull()
            ?: return emptyList<DdysMovie>() to 1
        return parseList(root.optJSONArray("data")) to
            (root.optJSONObject("meta")?.optInt("total_pages") ?: 1)
    }

    /** 搜索。 */
    suspend fun search(keyword: String): List<DdysMovie> {
        if (keyword.isBlank()) return emptyList()
        val json = get("/api/v1/search?q=${enc(keyword)}") ?: return emptyList()
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        return parseList(root.optJSONArray("data"))
    }

    /** 详情（含简介、演职员、可播线路）。 */
    suspend fun detail(slug: String): DdysMovie? {
        val detailJson = get("/api/v1/movies/${enc(slug)}") ?: return null
        val root = runCatching { JSONObject(detailJson) }.getOrNull() ?: return null
        val o = root.optJSONObject("data") ?: return null
        var movie = parseOne(o)

        // 播放线路单独一个接口
        val srcJson = get("/api/v1/movies/${enc(slug)}/sources")
        if (srcJson != null) {
            val sources = parseSources(srcJson)
            movie = movie.copy(sources = sources, onlineCount = sources.size)
        }
        return movie
    }

    // ==================== 解析 ====================

    private fun parseList(arr: JSONArray?): List<DdysMovie> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { runCatching { parseOne(it) }.getOrNull() }
        }
    }

    private fun parseOne(o: JSONObject): DdysMovie {
        val genres = ArrayList<String>()
        o.optJSONArray("genres")?.let { g ->
            for (i in 0 until g.length()) {
                val go = g.optJSONObject(i) ?: continue
                val name = go.optString("name").ifBlank { go.optString("genre_name") }
                if (name.isNotBlank()) genres.add(name)
            }
        }
        val director = joinNames(o.optJSONArray("director"))
        val actors = joinNames(o.optJSONArray("actors"))
        return DdysMovie(
            slug = o.optString("slug"),
            title = o.optString("title").ifBlank { o.optString("name") },
            poster = o.optString("poster").trim(),
            year = o.opt("year")?.toString().orEmpty().takeIf { it != "null" }.orEmpty(),
            type = o.optString("type"),
            typeCode = o.optString("type_code"),
            region = o.optString("region"),
            rating = o.opt("rating")?.toString().orEmpty().takeIf { it != "null" }.orEmpty(),
            updatedAt = o.optString("updated_at"),
            isCompleted = o.optBoolean("is_completed", false),
            intro = stripHtml(o.optString("intro")),
            director = director,
            actors = actors,
            genres = genres,
            onlineCount = o.optInt("online_sources_count", 0),
        )
    }

    private fun joinNames(arr: JSONArray?): String {
        if (arr == null) return ""
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val item = arr.opt(i)
            when (item) {
                is String -> out.add(item)
                is JSONObject -> {
                    val n = item.optString("name").ifBlank { item.optString("actor_name") }
                    if (n.isNotBlank()) out.add(n)
                }
            }
        }
        return out.joinToString(" / ")
    }

    /** 播放线路解析。 */
    private fun parseSources(json: String): List<DdysSource> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val data = root.optJSONObject("data") ?: return emptyList()

        // 新版：videos[] 里每条一个 url
        val out = ArrayList<DdysSource>()
        val videos = data.optJSONArray("videos")
        if (videos != null) {
            for (i in 0 until videos.length()) {
                val v = videos.optJSONObject(i) ?: continue
                val url = v.optString("url")
                if (url.isBlank()) continue
                val sourceName = v.optString("source").ifBlank { v.optString("name") }
                val quality = v.optString("quality")
                val eps = parseEpisodeList(url)
                if (eps.isEmpty()) continue
                val label = listOf(sourceName, quality)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                    .ifBlank { "线路 ${out.size + 1}" }
                out.add(
                    DdysSource(
                        id = v.opt("id")?.toString().orEmpty().ifBlank { "${out.size}" },
                        name = label,
                        quality = quality,
                        episodes = eps,
                    )
                )
            }
            if (out.isNotEmpty()) return out
        }

        // 旧版：online[] 里 url 是 `第1集$url#第2集$url` 形式
        val online = data.optJSONArray("online") ?: return emptyList()
        for (i in 0 until online.length()) {
            val o = online.optJSONObject(i) ?: continue
            val eps = parseEpisodeList(o.optString("url"))
            if (eps.isEmpty()) continue
            val name = o.optString("name").ifBlank { "播放源 ${i + 1}" }
            val quality = o.optString("quality")
            out.add(
                DdysSource(
                    id = o.opt("id")?.toString().orEmpty().ifBlank { "$i" },
                    name = listOf(name, quality).filter { it.isNotBlank() }.joinToString(" · "),
                    quality = quality,
                    episodes = eps,
                )
            )
        }
        return out
    }

    /**
     * 把 `第1集$url#第2集$url` 或裸 URL 解析成剧集列表。
     *
     * 实测踩坑：label 不一定是「第N集」，也可能是「普通话」「粤语版」，
     * 电影则是完全不带 `$` 的裸地址，所以两种都要兼容。
     */
    fun parseEpisodeList(raw: String): List<DdysEpisode> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        if (!text.contains('#')) {
            val i = text.indexOf('$')
            return if (i < 0) {
                listOf(DdysEpisode("播放", text.trim()))
            } else {
                val label = text.substring(0, i).trim()
                val url = text.substring(i + 1).trim()
                if (url.isEmpty()) emptyList() else listOf(DdysEpisode(label.ifBlank { "播放" }, url))
            }
        }
        var idx = -1
        return text.split('#').mapNotNull { seg ->
            idx++
            val s = seg.trim()
            if (s.isEmpty()) return@mapNotNull null
            val i = s.indexOf('$')
            if (i < 0) {
                if (s.startsWith("http")) DdysEpisode("播放", s) else null
            } else {
                val label = s.substring(0, i).trim()
                val url = s.substring(i + 1).trim()
                if (url.startsWith("http")) {
                    DdysEpisode(label.ifBlank { "第${idx + 1}集" }, url)
                } else {
                    null
                }
            }
        }.ifEmpty { if (text.startsWith("http")) listOf(DdysEpisode("播放", text)) else emptyList() }
    }

    // ==================== 网络 ====================

    private suspend fun get(path: String): String? {
        val url = "$base$path"
        val headers = mapOf(
            "Accept" to "application/json",
            "Referer" to "$base/",
        )
        val body = Net.get(url, headers, Http.UA_DESKTOP) ?: return null
        // Cloudflare 托管挑战兜底：识别出来就当作失败，让上层换域名
        if (body.contains("Just a moment") || body.contains("_cf_chl_opt")) return null
        if (!body.trimStart().startsWith("{")) return null
        return body
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    companion object {
        fun stripHtml(raw: String): String = raw
            .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""</p>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""<[^>]+>"""), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()

        fun normalise(domain: String): String {
            val d = domain.trim().trimEnd('/')
                .removePrefix("https://").removePrefix("http://")
            return if (d.isBlank()) "https://${AppPrefs.DEFAULT_DOMAIN}" else "https://$d"
        }
    }
}

/**
 * 域名探测与容错。
 *
 * ddys.io 在国内需要科学上网，所以官方给了三个备用域名。这里在启动时并发探一遍，
 * 用最快能返回 JSON 的那个；请求过程中如果发现当前域名被 Cloudflare 拦了或超时，
 * 自动切到下一个候选域名重试（对用户完全透明）。
 */
object Ddys {

    /** 当前实际生效的域名（含协议），UI 上显示用。 */
    @Volatile
    var activeBase: String = "https://${AppPrefs.DEFAULT_DOMAIN}"
        private set

    @Volatile
    private var probedAt: Long = 0L

    /** 探测所有候选域名，返回第一个可用的域名（不含协议）。 */
    suspend fun probe(force: Boolean = false): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && now - probedAt < 5 * 60 * 1000L && activeBase.isNotBlank()) {
            return@withContext activeBase.removePrefix("https://")
        }

        val prefs = runCatching { com.chinut.bawantv.BawanApp.prefs }.getOrNull()
        val configured = prefs?.domain.orEmpty()

        // 候选顺序：用户配置的域名优先，然后官方镜像
        val candidates = LinkedHashSet<String>()
        if (configured.isNotBlank()) candidates.add(configured)
        candidates.addAll(AppPrefs.DOMAIN_CANDIDATES)

        val results = coroutineScope {
            candidates.map { d ->
                async(Dispatchers.IO) { d to test(d) }
            }.awaitAll()
        }
        val best = results.firstOrNull { it.second }?.first
            ?: configured.ifBlank { AppPrefs.DEFAULT_DOMAIN }

        activeBase = "https://$best"
        probedAt = now
        runCatching { prefs?.resolvedDomain = best }
        best
    }

    /** 单个域名是否可用（拉一页最小的列表）。 */
    private suspend fun test(domain: String): Boolean {
        val base = DdysApi.normalise(domain)
        return try {
            val body = Net.get(
                "$base/api/v1/movies?page=1&per_page=1",
                mapOf("Accept" to "application/json", "Referer" to "$base/"),
                Http.UA_DESKTOP,
            ) ?: return false
            body.trimStart().startsWith("{") && body.contains("\"data\"")
        } catch (e: Exception) {
            false
        }
    }

    /** 取一个可用的 API 客户端（自动用当前生效域名）。 */
    suspend fun api(): DdysApi {
        val prefs = runCatching { com.chinut.bawantv.BawanApp.prefs }.getOrNull()
        if (prefs != null && !prefs.autoPickDomain) {
            return DdysApi(prefs.domain)
        }
        val d = probe()
        return DdysApi(d)
    }

    /** 强制切换下一个候选域名（当前域名失败时调用）。 */
    suspend fun failover(): String {
        val current = activeBase.removePrefix("https://")
        val next = AppPrefs.DOMAIN_CANDIDATES.firstOrNull { it != current }
            ?: AppPrefs.DEFAULT_DOMAIN
        activeBase = "https://$next"
        return next
    }
}
