package com.chinut.bawantv.unified

import com.chinut.bawantv.BawanApp
import com.chinut.bawantv.ddys.Ddys
import com.chinut.bawantv.ddys.DdysMovie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一个可播放的源。 */
data class UnifiedSource(
    /** 源标识。目前只有 "ddys"。 */
    val id: String,
    /** 回源取详情用的 id（低端影视是 slug）。 */
    val remoteId: String = "",
    /** 展示名，例如「低端影视 · 1080P」。 */
    val name: String,
    /** 补充说明（清晰度/评分）。 */
    val quality: String = "",
    /** 剧集数（列表阶段可能为 0，进详情页才补齐）。 */
    val episodeCount: Int = 0,
    /** 剧集列表。 */
    val episodes: List<Episode> = emptyList(),
)

/**
 * 一部作品的统一模型。
 *
 * 现在只承载低端影视的内容。之所以还叫 "Unified"，是因为界面层
 * 到处在用这个名字，改名会牵动几十处、收益为零。
 */
data class UnifiedMovie(
    val id: String,
    val title: String,
    val poster: String = "",
    val year: String = "",
    val typeName: String = "",
    val area: String = "",
    val score: String = "",
    val remarks: String = "",
    val sources: List<UnifiedSource> = emptyList(),
) {
    val hasPoster: Boolean get() = poster.startsWith("http")
    val sourceCount: Int get() = sources.size
}

/**
 * 低端影视内容层。
 *
 * ## 职责范围（本版收窄了）
 *
 * **只对接低端影视**。之前这里是个"聚合器"，并行拉低端影视 + 一堆 TVBox 站，
 * 再按标题合并 —— 但实测 TVBox 站**基本都播不了**（要么接口失效、
 * 要么地址需要配套解析接口），留着只是干扰：
 *   · 用户看到一堆源，点哪个都不行
 *   · 每次进详情页都要等好几个站的网络超时
 *
 * 现在删掉了 TVBox，只留低端影视：它有官方 JSON 接口、剧集是**直连 m3u8**、
 * 不需要任何解析接口就能播。内容少一点，但每条都能放。
 */
object MovieAggregator {

    // ==================== 首页海报墙的持久化缓存 ====================
    //
    // 首页那个「影视」按钮里的海报墙，上次抓到的片单落盘，
    // 进界面先用缓存即时渲染（海报命中 Coil 磁盘缓存），后台再刷新。

    private const val KEY_HOME_CACHE = "home_movie_cache"
    private const val MAX_CACHE_ITEMS = 12

    /** 读上次缓存的首页片单；没有则返回空。 */
    fun cachedHomeMovies(): List<UnifiedMovie> {
        val raw = runCatching { BawanApp.prefs.homeMovieCache }.getOrNull().orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val f = line.split('\u001F')
            if (f.size < 3) return@mapNotNull null
            UnifiedMovie(
                id = f[0],
                title = f[1],
                poster = f[2],
                year = f.getOrNull(3).orEmpty(),
                score = f.getOrNull(4).orEmpty(),
            )
        }.toList()
    }

    /** 把首页片单写进缓存（只留需要的字段，控制体积）。 */
    fun cacheHomeMovies(movies: List<UnifiedMovie>) {
        runCatching {
            BawanApp.prefs.homeMovieCache = movies
                .take(MAX_CACHE_ITEMS)
                .joinToString("\n") { m ->
                    listOf(m.id, m.title, m.poster, m.year, m.score).joinToString("\u001F")
                }
        }
    }

    /**
     * 预热海报：只让 Coil 去解码，不挂到界面上。
     *
     * 在开屏动画那两三秒里跑，等真正进首页时图已经在内存/磁盘缓存里了。
     */
    fun preloadPosters(context: android.content.Context, movies: List<UnifiedMovie>) {
        if (movies.isEmpty()) return
        runCatching {
            val loader = coil.Coil.imageLoader(context)
            movies.take(MAX_CACHE_ITEMS).forEach { m ->
                if (!m.hasPoster) return@forEach
                val req = coil.request.ImageRequest.Builder(context)
                    .data(m.poster)
                    .memoryCacheKey(m.poster)
                    .diskCacheKey(m.poster)
                    .build()
                loader.enqueue(req)
            }
        }
    }

    // ==================== 数据转换 ====================

    /** 低端影视的列表项 → 统一模型。 */
    fun fromDdysItem(m: DdysMovie): UnifiedMovie = UnifiedMovie(
        id = normalizeId(m.title, m.year),
        title = m.title,
        poster = m.poster,
        year = m.year,
        typeName = m.type + m.genres.joinToString(" ") { it },
        area = m.region,
        score = m.rating,
        remarks = if (m.isCompleted) "已完结" else "",
        sources = listOf(
            UnifiedSource(
                id = "ddys",
                remoteId = m.slug,
                name = "低端影视",
                quality = m.rating.takeIf { it.isNotBlank() }?.let { "评分 $it" } ?: "",
                episodeCount = m.onlineCount,
            )
        ),
    )

    /** 用片名+年份生成稳定的 id（同一部片每次都得到同一个 id）。 */
    fun normalizeId(title: String, year: String): String =
        (normalizeTitle(title) + "|" + year.trim()).take(120)

    /**
     * 片名归一化：去掉各站命名习惯带来的噪声，便于按标题匹配。
     *
     * 例如「庆余年(国语)」「庆余年 第一季」「庆余年HD」都应归到「庆余年」。
     */
    fun normalizeTitle(raw: String): String {
        var s = raw.trim()
        // 去掉各种括号及其内容
        s = s.replace(Regex("""[（(\[【][^）)\]】]*[）)\]】]"""), "")
        // 去掉常见噪声词
        val noise = listOf(
            "国语", "粤语", "英语", "日语", "韩语", "双语", "中字", "字幕",
            "高清", "标清", "超清", "蓝光", "bluray", "hd", "fhd", "4k", "8k", "hdr", "remux",
            "完结", "全集", "更新至", "已完结", "抢先版", "tc", "ts", "hdts",
            "第1季", "第2季", "第3季", "第一季", "第二季", "第三季",
        )
        noise.forEach { s = s.replace(it, "", ignoreCase = true) }
        // 去掉空白和标点
        s = s.replace(Regex("""[\s\-_·:：,，.。!！?？~～|/\\]+"""), "")
        return s.lowercase()
    }

    // ==================== 详情：补齐剧集 ====================

    /**
     * 为一部作品补齐剧集列表。
     *
     * 低端影视为直连地址，所以**不需要任何解析步骤** ——
     * 这也是删掉 TVBox 之后最大的简化：以前每个源都要走一遍
     * "解析接口"，现在拿到地址就能直接交给 ExoPlayer。
     *
     * ⚠️ 这里每一步都打日志。
     *
     * 之前这段代码**全是静默失败**：slug 拿不到、detail 返回 null、网络超时，
     * 最后都只是"原样返回"，界面上表现为「这个源没有可播放的剧集」——
     * 但根本看不出是哪一步断的。用户报了这个现象，我查了很久才定位到
     * "设备连不上 ddys.io"这一层。所以凡是可能失败的步骤都留痕。
     */
    suspend fun loadSources(movie: UnifiedMovie): List<UnifiedSource> =
        withContext(Dispatchers.IO) {
            val out = movie.sources.map { s ->
                runCatching {
                    if (s.id != "ddys") return@runCatching s
                    val api = Ddys.api()
                    // 优先用列表阶段就带下来的 slug；没有才退回按标题搜索
                    var slug = s.remoteId
                    if (slug.isBlank()) {
                        val found = runCatching { api.search(movie.title) }
                        if (found.isFailure) {
                            android.util.Log.w(
                                TAG,
                                "取源失败《${movie.title}》：搜索异常 " +
                                    found.exceptionOrNull()?.javaClass?.simpleName +
                                    " ${found.exceptionOrNull()?.message}",
                            )
                        }
                        slug = found.getOrNull()?.firstOrNull()?.slug.orEmpty()
                    }
                    if (slug.isBlank()) {
                        android.util.Log.w(TAG, "取源失败《${movie.title}》：拿不到 slug")
                        return@runCatching s
                    }
                    val detailRes = runCatching { api.detail(slug) }
                    if (detailRes.isFailure) {
                        android.util.Log.w(
                            TAG,
                            "取源失败《${movie.title}》(slug=$slug)：详情异常 " +
                                detailRes.exceptionOrNull()?.javaClass?.simpleName +
                                " ${detailRes.exceptionOrNull()?.message}",
                        )
                    }
                    val detail = detailRes.getOrNull()
                    if (detail == null) {
                        android.util.Log.w(TAG, "取源失败《${movie.title}》(slug=$slug)：详情返回 null")
                        return@runCatching s
                    }
                    val src = detail.sources.orEmpty()
                    val eps = src.flatMap { it.episodes }
                        .mapIndexed { i, e ->
                            Episode(
                                name = e.label.ifBlank { "第 ${i + 1} 集" },
                                url = e.url,
                            )
                        }
                    android.util.Log.i(
                        TAG,
                        "取源成功《${movie.title}》(slug=$slug)：源 ${src.size} 个 → 剧集 ${eps.size} 条",
                    )
                    s.copy(
                        remoteId = slug,
                        episodes = eps,
                        episodeCount = eps.size,
                        name = if (src.isNotEmpty()) {
                            "低端影视 · " + src.joinToString("/") { it.name }
                        } else {
                            "低端影视"
                        },
                    )
                }.getOrDefault(s)
            }
            out
        }
}

/** 日志标签。取源链路的每一步都靠它留痕，见 loadSources 的说明。 */
private const val TAG = "BawanVod"
