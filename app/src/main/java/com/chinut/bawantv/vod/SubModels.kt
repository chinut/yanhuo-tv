package com.chinut.bawantv.vod

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/**
 * TVBox 订阅（接口）配置的数据模型。
 *
 * 只保留原生 Kotlin 客户端真正用得到的字段：
 *  - sites：站点列表（我们只支持 HTTP-JSON 型：type 0/1）
 *  - parses：播放地址解析接口（type 1 的 POST 解析）
 *  - spider / lives 等依赖 jar/dex 的部分只做记录，不在 App 内执行
 */
@Serializable
data class SubConfig(
    val spider: String = "",
    val wallPaper: String = "",
    val logo: String = "",
    val sites: List<SubSite> = emptyList(),
    val parses: List<SubParse> = emptyList(),
    val flags: List<String> = emptyList(),
    val lives: List<SubLive> = emptyList(),
)

@Serializable
data class SubSite(
    val key: String = "",
    val name: String = "",
    val type: Int = 3,
    val api: String = "",
    val searchable: Int = 1,
    val quickSearch: Int = 1,
    val filterable: Int = 1,
    val ext: String = "",
    val jar: String = "",
    val playUrl: String = "",
    val playerType: String = "",
    val categories: List<String> = emptyList(),
    val style: String = "",
    @SerialName("changeable") val changeable: Int = 1,
    val hide: Int = 0,
) {
    /**
     * 是否是本 App 能直接调用的「HTTP JSON 接口」站点。
     *
     * type 1 = 苹果 CMS 风格 JSON 接口（?ac=videolist / ac=detail）
     * type 0 = 同上的 XML 变体（也走同一套参数，返回 JSON 的居多）
     * type 3 = 需要 jar/dex 的爬虫 → 原生端无法执行，直接跳过
     * type 4 = 同样是 jar 爬虫
     */
    val isHttpJson: Boolean
        get() = (type == 1 || type == 0) && api.startsWith("http") && jar.isBlank()

    /** 是否需要 jar：这类站点在原生端标记为不支持 */
    val needsSpider: Boolean
        get() = jar.isNotBlank() || type == 3 || type == 4 || api.startsWith("csp_")
}

@Serializable
data class SubParse(
    val name: String = "",
    val type: Int = 1,
    val url: String = "",
    val ext: SubParseExt? = null,
)

@Serializable
data class SubParseExt(
    val flag: List<String> = emptyList(),
    val header: Map<String, String> = emptyMap(),
)

@Serializable
data class SubLive(
    val name: String = "",
    val type: Int = 0,
    val url: String = "",
    val epg: String = "",
    val logo: String = "",
)

// ==================== 站点返回的数据 ====================

/** 一级分类。 */
data class VodCategory(
    val id: String,
    val name: String,
    val pic: String = "",
)

/** 列表项 / 搜索结果项。 */
data class VodItem(
    val vodId: String,
    val name: String,
    val pic: String = "",
    val remarks: String = "",
    val year: String = "",
    val area: String = "",
    val typeName: String = "",
    val score: String = "",
    /** 来自哪个站点（多站点聚合时用） */
    val siteKey: String = "",
    val siteName: String = "",
) {
    /** 是否有可用的竖版海报 */
    val hasPoster: Boolean get() = pic.startsWith("http")
}

/** 一集 / 一条播放线路。 */
data class VodEpisode(
    val name: String,
    val url: String,
)

/** 一条播放来源（线路），包含多集。 */
data class VodPlaySource(
    val flag: String,
    val episodes: List<VodEpisode>,
)

/** 详情。 */
data class VodDetail(
    val vodId: String,
    val name: String,
    val pic: String = "",
    val content: String = "",
    val year: String = "",
    val area: String = "",
    val typeName: String = "",
    val remarks: String = "",
    val score: String = "",
    val director: String = "",
    val actors: String = "",
    val sources: List<VodPlaySource> = emptyList(),
    val siteKey: String = "",
    val siteName: String = "",
) {
    /** 全部剧集，按线路顺序 */
    val allEpisodes: List<VodEpisode> get() = sources.flatMap { it.episodes }
}

/** 一页列表结果。 */
data class VodPage(
    val categories: List<VodCategory> = emptyList(),
    val items: List<VodItem> = emptyList(),
    val page: Int = 1,
    val pageCount: Int = 1,
    val total: Int = 0,
    val limit: Int = 20,
)
