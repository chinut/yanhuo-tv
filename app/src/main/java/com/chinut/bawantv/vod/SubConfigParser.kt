package com.chinut.bawantv.vod

import kotlinx.serialization.json.Json
import org.json.JSONObject

/**
 * TVBox 接口 JSON 解析。
 *
 * 线上订阅文件格式五花八门（字段大小写不统一、有的带 BOM、有的把 ext 写成对象或字符串），
 * 所以这里用容错解析：先按标准结构读，读不到再用宽松规则兜。
 */
object SubConfigParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /** 解析订阅文本。失败返回 null。 */
    fun parse(text: String): SubConfig? {
        if (text.isBlank()) return null
        val cleaned = text.trim().removePrefix("\uFEFF")
        // 1) 标准 kotlinx 解析
        runCatching { return json.decodeFromString<SubConfig>(cleaned) }
        // 2) 容错 JSONObject 解析
        return runCatching { looseParse(cleaned) }.getOrNull()
    }

    private fun looseParse(text: String): SubConfig {
        val root = JSONObject(text)
        val sites = ArrayList<SubSite>()
        root.optJSONArray("sites")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                sites.add(
                    SubSite(
                        key = o.optString("key"),
                        name = o.optString("name"),
                        type = o.optString("type", "1").toIntOrNull() ?: 1,
                        api = o.optString("api"),
                        searchable = o.optString("searchable", "1").toIntOrNull() ?: 1,
                        quickSearch = o.optString("quickSearch", "1").toIntOrNull() ?: 1,
                        filterable = o.optString("filterable", "1").toIntOrNull() ?: 1,
                        ext = o.opt("ext")?.toString().orEmpty().takeIf { it != "null" }.orEmpty(),
                        jar = o.optString("jar"),
                        playUrl = o.optString("playUrl"),
                        playerType = o.optString("playerType"),
                        style = o.optString("style"),
                        hide = o.optString("hide", "0").toIntOrNull() ?: 0,
                    )
                )
            }
        }
        val parses = ArrayList<SubParse>()
        root.optJSONArray("parses")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val ext = o.optJSONObject("ext")
                parses.add(
                    SubParse(
                        name = o.optString("name"),
                        type = o.optString("type", "1").toIntOrNull() ?: 1,
                        url = o.optString("url"),
                        ext = ext?.let { e ->
                            SubParseExt(
                                flag = e.optJSONArray("flag")?.let { f ->
                                    (0 until f.length()).map { f.optString(it) }
                                }.orEmpty(),
                                header = e.optJSONObject("header")?.let { h ->
                                    h.keys().asSequence().associateWith { k -> h.optString(k) }
                                }.orEmpty(),
                            )
                        },
                    )
                )
            }
        }
        return SubConfig(
            spider = root.optString("spider"),
            sites = sites,
            parses = parses,
            logo = root.optString("logo"),
            wallPaper = root.optString("wallPaper"),
        )
    }

    /**
     * 可用的站点列表：只留 HTTP-JSON 型、没被隐藏、有名字的。
     * 保持订阅文件里的原始顺序（用户习惯「第一个能用的就是主线路」）。
     */
    fun usableSites(config: SubConfig, vodOnly: Boolean = true): List<SubSite> =
        config.sites.filter { s ->
            s.isHttpJson && s.hide == 0 && s.name.isNotBlank() && !isLiveSite(s)
        }.let { list ->
            if (!vodOnly) list else list.filterNot { isToolSite(it) }
        }

    /** 明显是直播/电视类站点，板块 B 要求只订阅影视，所以剔除。 */
    private fun isLiveSite(s: SubSite): Boolean {
        val n = s.name
        val liveWords = listOf("直播", "电视", "频道", "IPTV", "iptv", "电视直播", "电台", "广播")
        return liveWords.any { n.contains(it) }
    }

    /** 工具/网盘/音乐类站点，不属于影视订阅内容。 */
    private fun isToolSite(s: SubSite): Boolean {
        val n = s.name.lowercase()
        val toolWords = listOf("网盘", "音乐", "工具", "测试", "少儿", "纪录片", "体育", "短剧")
        return toolWords.any { n.contains(it.lowercase()) }
    }
}
