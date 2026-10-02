package com.chinut.bawantv.vod

import com.chinut.bawantv.core.Http
import com.chinut.bawantv.core.Net
import org.json.JSONArray
import org.json.JSONObject

/**
 * 苹果 CMS（TVBox type 1）HTTP JSON 接口客户端。
 *
 * 请求契约（TVBox 生态通用）：
 *   {api}?ac=list                      → 分类 + 首页列表
 *   {api}?ac=videolist&t=<分类id>&pg=N  → 某个分类第 N 页
 *   {api}?ac=videolist&wd=<关键词>&pg=N → 搜索
 *   {api}?ac=detail&ids=<id>           → 详情
 *
 * 返回结构里三条最容易踩坑的约定，这里都做了兼容：
 *  - vod_play_url 用 `$$$` 分隔线路、`#` 分隔剧集、`$` 分隔「剧集名 + 地址」
 *  - 播放地址可能是 http、可能是 `name$http`、也可能是 `json字符串`
 *  - 分类 id 可能是数字也可能是字符串，统一按字符串处理
 */
class VodApi(private val site: SubSite) {

    private val base: String = site.api.trim()

    private fun url(params: String): String =
        if (base.contains('?')) "$base&$params" else "$base?$params"

    /** 首页 / 分类列表。 */
    suspend fun list(typeId: String? = null, page: Int = 1, keyword: String? = null): VodPage? {
        val params = buildString {
            append("ac=videolist")
            if (!keyword.isNullOrBlank()) {
                append("&wd=").append(enc(keyword))
            } else if (!typeId.isNullOrBlank()) {
                append("&t=").append(enc(typeId))
            }
            append("&pg=").append(page)
        }
        val text = fetch(url(params)) ?: return null
        // type 0 是 XML 变形接口：同一个 ac 参数，返回 rss/list 结构
        if (isXml(text)) return parseXmlList(text, page)
        return parseList(text, page)
    }

    /** 详情。 */
    suspend fun detail(vodId: String): VodDetail? {
        val text = fetch(url("ac=detail&ids=${enc(vodId)}")) ?: return null
        if (isXml(text)) return parseXmlDetail(text)
        val items = parseItems(text)
        val obj = items.firstOrNull() ?: return null
        return toDetail(obj)
    }

    // ==================== XML（type 0）====================

    private fun isXml(text: String): Boolean {
        val t = text.trimStart()
        return t.startsWith("<?xml") || t.startsWith("<rss") || t.startsWith("<list")
    }

    private fun parseXmlList(xml: String, page: Int): VodPage? {
        val listTag = Regex("""<list\b([^>]*)>""", RegexOption.IGNORE_CASE).find(xml)
        val attrs = listTag?.groupValues?.getOrNull(1).orEmpty()
        fun attrOf(name: String): Int =
            Regex("""$name\s*=\s*"(\d+)"""", RegexOption.IGNORE_CASE)
                .find(attrs)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        val pageCount = attrOf("pagecount").coerceAtLeast(1)
        val total = attrOf("recordcount")

        // 分类表在 XML 里叫 <class><ty id="1">电影</ty></class>
        val categories = ArrayList<VodCategory>()
        Regex("""<ty\b[^>]*\bid\s*=\s*"(\d+)"[^>]*>(.*?)</ty>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(xml)
            .forEach { m ->
                val id = m.groupValues[1]
                val name = text(m.groupValues[2])
                if (name.isNotBlank()) categories.add(VodCategory(id, name))
            }

        val items = Regex("""<video>(.*?)</video>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(xml)
            .mapNotNull { m -> runCatching { toXmlItem(m.groupValues[1]) }.getOrNull() }
            .toList()

        return VodPage(
            categories = categories,
            items = items,
            page = attrOf("page").takeIf { it > 0 } ?: page,
            pageCount = pageCount,
            total = if (total > 0) total else items.size,
            limit = attrOf("pagesize").takeIf { it > 0 } ?: 20,
        )
    }

    private fun parseXmlDetail(xml: String): VodDetail? {
        val block = Regex("""<video>(.*?)</video>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(xml)?.groupValues?.getOrNull(1) ?: return null
        val item = toXmlItem(block)

        val flags = ArrayList<String>()
        val groups = ArrayList<String>()
        Regex("""<dd\b[^>]*\bflag\s*=\s*"([^"]*)"[^>]*>(.*?)</dd>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(block)
            .forEach { m ->
                flags.add(m.groupValues[1])
                groups.add(text(m.groupValues[2]))
            }

        val sources = ArrayList<VodPlaySource>()
        groups.forEachIndexed { i, group ->
            if (group.isBlank()) return@forEachIndexed
            val eps = parseEpisodes(group)
            if (eps.isNotEmpty()) {
                sources.add(VodPlaySource(flags.getOrNull(i)?.ifBlank { "线路${i + 1}" } ?: "线路${i + 1}", eps))
            }
        }

        return VodDetail(
            vodId = item.vodId,
            name = item.name,
            pic = item.pic,
            content = stripHtml(tag(block, "des")),
            year = tag(block, "year"),
            area = tag(block, "area"),
            typeName = item.typeName,
            remarks = item.remarks,
            score = tag(block, "score"),
            director = tag(block, "director"),
            actors = tag(block, "actor"),
            sources = sources,
            siteKey = site.key,
            siteName = site.name,
        )
    }

    private fun toXmlItem(block: String): VodItem = VodItem(
        vodId = tag(block, "id"),
        name = cleanName(tag(block, "name")),
        pic = tag(block, "pic").trim(),
        remarks = tag(block, "note"),
        year = tag(block, "year"),
        area = tag(block, "area"),
        typeName = tag(block, "type"),
        score = tag(block, "score"),
        siteKey = site.key,
        siteName = site.name,
    )

    /** 取 <tag> 内容并剥掉 CDATA。 */
    private fun tag(xml: String, name: String): String {
        val m = Regex(
            """<$name\b[^>]*>(.*?)</$name>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(xml) ?: return ""
        return text(m.groupValues[1])
    }

    /** 去掉 CDATA 包裹并反转义。 */
    private fun text(raw: String): String {
        var s = raw.trim()
        val cd = Regex("""<!\[CDATA\[(.*?)]]>""", RegexOption.DOT_MATCHES_ALL).find(s)
        if (cd != null) {
            s = cd.groupValues[1]
        } else {
            s = s.replace("<![CDATA[", "").replace("]]>", "")
        }
        return s.trim()
    }

    // ==================== 网络 ====================

    private suspend fun fetch(u: String): String? {
        val headers = buildMap {
            put("Accept", "application/json, text/plain, */*")
            if (base.contains("cj.52itv") || base.contains("json")) put("Accept", "application/json")
        }
        // 先用桌面 UA，失败再用 TV UA（部分接口按 UA 区别对待）
        return Net.get(u, headers, Http.UA_DESKTOP)
            ?: Net.get(u, headers, Http.UA_TV)
    }

    // ==================== 解析 ====================

    private fun parseList(text: String, page: Int): VodPage? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val categories = ArrayList<VodCategory>()
        root.opt("class")?.let { cls ->
            val arr: JSONArray? = when (cls) {
                is JSONArray -> cls
                is JSONObject -> cls.optJSONArray("class")
                else -> null
            }
            arr?.let {
                for (i in 0 until it.length()) {
                    val o = it.optJSONObject(i) ?: continue
                    val id = o.opt("type_id")?.toString().orEmpty()
                    val name = o.optString("type_name").ifBlank { "分类${i + 1}" }
                    if (id.isNotBlank()) categories.add(VodCategory(id, name, o.optString("type_pic")))
                }
            }
        }
        val items = parseItems(text).map { toItem(it) }
        return VodPage(
            categories = categories,
            items = items,
            page = root.opt("page")?.toString()?.toIntOrNull() ?: page,
            pageCount = root.opt("pagecount")?.toString()?.toIntOrNull() ?: 1,
            total = root.opt("total")?.toString()?.toIntOrNull() ?: items.size,
            limit = root.opt("limit")?.toString()?.toIntOrNull() ?: 20,
        )
    }

    private fun parseItems(text: String): List<JSONObject> {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("list") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    /**
     * 解析「一整个播放线路」的剧集串。
     *
     * 约定：剧集之间用 `#` 分隔，每条是 `剧集名$地址`；
     * 电影常常只有一个裸地址（不带 `$`）。两种都要兼容。
     * 注意先按 `#` 切、再取第一个 `$`，因为地址本身可能带 `$`。
     */
    private fun parseEpisodes(group: String): List<VodEpisode> {
        val eps = ArrayList<VodEpisode>()
        group.split('#').forEach { seg ->
            val s = seg.trim()
            if (s.isEmpty()) return@forEach
            val dollar = s.indexOf('$')
            if (dollar > 0) {
                val name = s.substring(0, dollar).trim()
                val u = s.substring(dollar + 1).trim()
                if (u.isNotEmpty()) {
                    eps.add(VodEpisode(name.ifBlank { "第${eps.size + 1}集" }, u))
                }
            } else if (s.startsWith("http") || s.startsWith("{")) {
                eps.add(VodEpisode("第${eps.size + 1}集", s))
            }
        }
        return eps
    }

    private fun toItem(o: JSONObject): VodItem = VodItem(
        vodId = o.opt("vod_id")?.toString().orEmpty(),
        name = cleanName(o.optString("vod_name")),
        pic = o.optString("vod_pic").trim(),
        remarks = o.optString("vod_remarks").trim(),
        year = o.optString("vod_year").trim(),
        area = o.optString("vod_area").trim(),
        typeName = o.optString("vod_class").ifBlank { o.optString("type_name") }.trim(),
        score = o.opt("vod_score")?.toString().orEmpty(),
        siteKey = site.key,
        siteName = site.name,
    )

    private fun toDetail(o: JSONObject): VodDetail {
        val item = toItem(o)
        val flags = o.optString("vod_play_from").split(SEP_GROUP).map { it.trim() }
        val urlGroups = o.optString("vod_play_url").split(SEP_GROUP)

        val sources = ArrayList<VodPlaySource>()
        urlGroups.forEachIndexed { index, group ->
            if (group.isBlank()) return@forEachIndexed
            val flag = flags.getOrNull(index)?.takeIf { it.isNotBlank() } ?: "线路${index + 1}"
            val eps = parseEpisodes(group)
            if (eps.isNotEmpty()) sources.add(VodPlaySource(flag, eps))
        }

        return VodDetail(
            vodId = item.vodId,
            name = item.name,
            pic = item.pic,
            content = stripHtml(o.optString("vod_content")).trim(),
            year = item.year,
            area = item.area,
            typeName = item.typeName,
            remarks = item.remarks,
            score = item.score,
            director = o.optString("vod_director").trim(),
            actors = o.optString("vod_actor").trim(),
            sources = sources,
            siteKey = site.key,
            siteName = site.name,
        )
    }

    companion object {
        /** TVBox 生态约定的分隔符：线路之间、剧集分组之间 */
        const val SEP_GROUP = "$$$"

        /** 站点名称里常见的花哨前缀（颜色标记），清理掉更清爽。 */
        fun cleanName(raw: String): String = raw
            .replace(Regex("""<[^>]+>"""), "")
            .replace("&nbsp;", " ")
            .trim()

        fun stripHtml(raw: String): String = raw
            .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""<[^>]+>"""), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("""\n{3,}"""), "\n\n")

        private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
