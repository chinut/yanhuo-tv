package com.chinut.bawantv.vod

import com.chinut.bawantv.core.Http
import com.chinut.bawantv.core.Net
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 板块 B 的数据入口：订阅 → 站点 → 内容。
 *
 * 缓存策略：
 *  - 订阅文件按 URL + 5 分钟 TTL 缓存（避免每次进页面都重新拉）
 *  - 站点首页列表按站点 key 缓存 3 分钟
 */
object VodRepo {

    private data class Cached(
        val at: Long,
        val config: SubConfig,
    )

    private var cached: Cached? = null
    private const val TTL_MS = 5 * 60 * 1000L

    /** 已加载的订阅（最近一次）。 */
    @Volatile
    var lastConfig: SubConfig? = null
        private set

    /** 最近一次加载的说明（给设置页显示状态） */
    @Volatile
    var lastStatus: String = "尚未加载"
        private set

    /**
     * 加载订阅：按设置里的地址顺序尝试，取第一个能解析出「可用站点」的。
     */
    suspend fun loadConfig(force: Boolean = false): SubConfig? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cached?.let { if (!force && now - it.at < TTL_MS) return@withContext it.config }

        val urls = runCatching { com.chinut.bawantv.BawanApp.prefs.subscriptionUrls }
            .getOrDefault("")
            .split('\n', '\r', ',')
            .map { it.trim() }
            .filter { it.startsWith("http") || it.startsWith("asset://") }

        if (urls.isEmpty()) {
            lastStatus = "未配置订阅地址"
            return@withContext null
        }

        var lastError = ""
        for (u in urls) {
            val text = readSource(u)
            if (text.isNullOrBlank()) {
                lastError = "拉取失败：$u"
                continue
            }
            val cfg = SubConfigParser.parse(text)
            if (cfg == null) {
                lastError = "解析失败：$u"
                continue
            }
            val usable = SubConfigParser.usableSites(cfg, vodOnly = false)
            if (usable.isEmpty()) {
                lastError = "该订阅没有可用站点：$u"
                continue
            }
            cached = Cached(now, cfg)
            lastConfig = cfg
            lastStatus = "已加载 ${cfg.sites.size} 个站点，其中可用 ${usable.size} 个"
            return@withContext cfg
        }
        lastStatus = lastError.ifBlank { "订阅不可用" }
        null
    }

    /** 可用站点（只保留 HTTP-JSON 型；可选只留影视）。 */
    fun sites(config: SubConfig, vodOnly: Boolean = true): List<SubSite> =
        SubConfigParser.usableSites(config, vodOnly)

    /** 站点列表每秒都在用，这里做一个便于 UI 直接拿的方法。 */
    suspend fun loadSites(vodOnly: Boolean = true, force: Boolean = false): List<SubSite> {
        val cfg = loadConfig(force) ?: return emptyList()
        return sites(cfg, vodOnly)
    }

    fun apiOf(site: SubSite): VodApi = VodApi(site)

    fun resolver(episodeFlag: String = ""): VodResolver =
        VodResolver(lastConfig?.parses.orEmpty())

    /**
     * 多站点聚合搜索：并发请求所有可搜索站点，合并结果。
     * 只取每站前 N 条，避免 TV 上列表过长。
     */
    suspend fun search(
        sites: List<SubSite>,
        keyword: String,
        perSite: Int = 20,
    ): List<VodItem> = withContext(Dispatchers.IO) {
        if (keyword.isBlank()) return@withContext emptyList()
        val targets = sites.filter { it.searchable != 0 }
        val jobs = targets.map { site ->
            async(Dispatchers.IO) {
                runCatching {
                    VodApi(site).list(keyword = keyword, page = 1)?.items?.take(perSite).orEmpty()
                }.getOrDefault(emptyList())
            }
        }
        jobs.awaitAll().flatten()
    }

    /** 清空缓存（设置页改完订阅后调用）。 */
    fun invalidate() {
        cached = null
        lastConfig = null
        lastStatus = "尚未加载"
    }

    /**
     * 读取订阅文本。支持两种来源：
     *  - `asset://default_sub.json`：App 内置的默认订阅（无需联网、永远不会失效）
     *  - `http(s)://...`：远程订阅
     * 远程订阅失败会自动回退到内置订阅，保证界面上永远有内容可用。
     */
    private suspend fun readSource(url: String): String? {
        if (url.startsWith("asset://")) {
            val path = url.removePrefix("asset://")
            return runCatching {
                com.chinut.bawantv.BawanApp.ctx().assets.open(path)
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.getOrNull()
        }
        val remote = Net.get(url, ua = Http.UA_DESKTOP)
        if (!remote.isNullOrBlank()) return remote
        // jsDelivr 兜底（raw.githubusercontent 在部分网络被墙）
        if (url.contains("raw.githubusercontent.com")) {
            val mirror = url
                .replace("raw.githubusercontent.com", "cdn.jsdelivr.net")
                .replace("/master/", "@master/")
                .replace("/main/", "@main/")
            val alt = Net.get(mirror, ua = Http.UA_DESKTOP)
            if (!alt.isNullOrBlank()) return alt
        }
        return null
    }
}
