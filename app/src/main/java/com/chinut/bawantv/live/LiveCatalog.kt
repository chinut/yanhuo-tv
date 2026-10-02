package com.chinut.bawantv.live

import android.content.Context
import com.chinut.bawantv.core.Http
import com.chinut.bawantv.core.Net
import java.net.URLDecoder

/** 一个频道。 */
data class LiveChannel(
    val name: String,
    val url: String,
    val group: String = "其他",
    val logo: String = "",
    /** 同名频道的备用源（换源用） */
    val alternates: List<String> = emptyList(),
)

/** 一个频道分组。 */
data class LiveGroup(
    val name: String,
    val channels: List<LiveChannel>,
)

/**
 * 直播源解析与频道目录。
 *
 * 支持两种格式：
 *  1. 内置 m3u（assets/live/cctv.m3u，由音乐库项目的 cctv/tv.json 转换而来）
 *  2. 用户自定义直播源：m3u / m3u8 播放列表，或「台名,地址」每行一条的 txt
 *
 * 老电视体验：同名频道在多个分组里出现时，自动合并成「备用源」，
 * 当前源播不出来直接按一下换源键就切下一个地址。
 */
object LiveCatalog {

    private const val BUILTIN_ASSET = "live/cctv.m3u"

    @Volatile
    private var cachedBuiltin: List<LiveGroup>? = null

    /**
     * 读取内置频道表。
     *
     * 频道表里有相当一部分是「电视台网页」（例如 `tv.cctv.com/live/cctv13/`），
     * 这些地址交给 ExoPlayer 必然报 `PARSING_MANIFEST_MALFORMED`。
     * 所以加载后做一次「补源」：给每个网页类频道挂上长期可用的直连 HLS 备用源。
     */
    fun builtin(context: Context): List<LiveGroup> {
        cachedBuiltin?.let { return it }
        return synchronized(this) {
            cachedBuiltin ?: run {
                val text = runCatching {
                    context.assets.open(BUILTIN_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
                }.getOrDefault("")
                val groups = attachKnownStreams(parse(text).ifEmpty { fallbackCctv() })
                cachedBuiltin = groups
                groups
            }
        }
    }

    /**
     * 给「网页类频道」补上直连流地址。
     *
     * 关键：**网页地址必须保持在第一位**。
     * 央视与多数省台的流是加密专有格式，只有电视台自己的网页播放器能解，
     * 所以这类频道默认走「浏览器引擎打开网页」的路线（见 [TvWebPlayerView]）；
     * 直连流只作为备用源挂在后面，网页路线放不出来时才轮到它。
     */
    private fun attachKnownStreams(groups: List<LiveGroup>): List<LiveGroup> {
        val known = knownCctvStreams()
        return groups.map { g ->
            g.copy(
                channels = g.channels.map { ch ->
                    if (isDirectStream(ch.url)) {
                        ch
                    } else {
                        val extra = known[normalizeName(ch.name)]
                        if (extra.isNullOrEmpty()) {
                            ch
                        } else {
                            ch.copy(
                                // 网页地址保持第一，直连流追加为备用
                                alternates = (ch.alternates + extra).distinct(),
                            )
                        }
                    }
                }
            )
        }
    }

    /** 关键词 → 直连 HLS 地址表。 */
    private fun knownCctvStreams(): Map<String, List<String>> {
        val base = "http://ivi.bupt.edu.cn/hls"
        val entries = listOf(
            "CCTV-1 综合" to "cctv1hd",
            "CCTV-2 财经" to "cctv2hd",
            "CCTV-3 综艺" to "cctv3hd",
            "CCTV-4 中文国际" to "cctv4hd",
            "CCTV-5 体育" to "cctv5hd",
            "CCTV-5+ 体育赛事" to "cctv5phd",
            "CCTV-6 电影" to "cctv6hd",
            "CCTV-7 国防军事" to "cctv7hd",
            "CCTV-8 电视剧" to "cctv8hd",
            "CCTV-9 纪录" to "cctv9hd",
            "CCTV-10 科教" to "cctv10hd",
            "CCTV-11 戏曲" to "cctv11hd",
            "CCTV-12 社会与法" to "cctv12hd",
            "CCTV-13 新闻" to "cctv13hd",
            "CCTV-14 少儿" to "cctv14hd",
            "CCTV-15 音乐" to "cctv15hd",
            "CCTV-17 农业农村" to "cctv17hd",
        )
        return entries.associate { (name, file) ->
            normalizeName(name) to listOf("$base/$file.m3u8")
        }
    }
    /**
     * 最终频道表：内置频道 + 用户自定义源（如果有）。
     * 自定义源单独成一类，放在最前面，避免和内置的混在一起。
     */
    suspend fun load(context: Context, customSourceUrl: String): List<LiveGroup> {
        val builtin = builtin(context)
        val url = customSourceUrl.trim()
        if (url.isEmpty()) return builtin

        val text = Net.get(url, ua = Http.UA_MOBILE) ?: return builtin
        val custom = parse(text)
        if (custom.isEmpty()) return builtin

        val merged = ArrayList<LiveGroup>(custom.size + builtin.size)
        custom.forEach { g ->
            merged.add(g.copy(name = "自定义 · ${g.name}"))
        }
        merged.addAll(builtin)
        return mergeAlternates(merged)
    }

    /**
     * 解析 m3u / 纯文本频道表。
     */
    fun parse(text: String): List<LiveGroup> {
        if (text.isBlank()) return emptyList()
        return if (text.contains("#EXTINF", ignoreCase = true)) parseM3u(text) else parsePlain(text)
    }

    private fun parseM3u(text: String): List<LiveGroup> {
        val out = LinkedHashMap<String, MutableList<LiveChannel>>()
        var pendingName: String? = null
        var pendingGroup = "默认"
        var pendingLogo = ""

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.isEmpty() -> Unit

                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    // #EXTINF:-1 tvg-logo="..." group-title="央视",CCTV-1 综合
                    val comma = line.lastIndexOf(',')
                    pendingName = if (comma >= 0) line.substring(comma + 1).trim() else null
                    pendingGroup = attr(line, "group-title") ?: attr(line, "tvg-group") ?: "默认"
                    pendingLogo = attr(line, "tvg-logo") ?: ""
                }

                line.startsWith("#") -> Unit

                else -> {
                    val name = pendingName?.takeIf { it.isNotBlank() }
                        ?: line.substringAfterLast('/').substringBefore('?').ifBlank { "未知频道" }
                    if (line.startsWith("http", ignoreCase = true)) {
                        val direct = directStreamOf(line) ?: line
                        out.getOrPut(pendingGroup) { mutableListOf() }
                            .add(LiveChannel(name = name, url = direct, group = pendingGroup, logo = pendingLogo))
                    }
                    pendingName = null
                    pendingLogo = ""
                }
            }
        }
        return out.entries
            .filter { it.value.isNotEmpty() }
            .map { LiveGroup(it.key, it.value) }
            .let(::mergeAlternates)
    }

    /** 「台名,地址」纯文本格式（老式 TV 源常见）。 */
    private fun parsePlain(text: String): List<LiveGroup> {
        val out = LinkedHashMap<String, MutableList<LiveChannel>>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val sep = line.indexOf(',')
            if (sep <= 0) return@forEach
            val name = line.substring(0, sep).trim()
            val addr = line.substring(sep + 1).trim()
            if (!addr.startsWith("http", ignoreCase = true)) return@forEach
            val group = when {
                name.contains("CCTV") || name.contains("央视") -> "央视"
                name.contains("卫视") -> "卫视"
                else -> "默认"
            }
            out.getOrPut(group) { mutableListOf() }
                .add(LiveChannel(name = name, url = directStreamOf(addr) ?: addr, group = group))
        }
        return out.entries.map { LiveGroup(it.key, it.value) }.let(::mergeAlternates)
    }

    private fun attr(line: String, key: String): String? {
        val re = Regex("""$key\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
        return re.find(line)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * 同名频道合并备用源。
     * 归一化名称（去掉空格、-、·、频道号后缀等），把同名但地址不同的合成一个频道。
     */
    fun mergeAlternates(groups: List<LiveGroup>): List<LiveGroup> {
        data class Acc(val channel: LiveChannel, val alts: LinkedHashSet<String>)

        val seen = HashMap<String, Acc>()
        groups.forEach { g ->
            g.channels.forEach { ch ->
                val key = normalizeName(ch.name)
                val acc = seen[key]
                if (acc == null) {
                    seen[key] = Acc(ch, linkedSetOf())
                } else {
                    if (!acc.alts.contains(ch.url) && acc.channel.url != ch.url) acc.alts.add(ch.url)
                }
            }
        }
        // 回填 alternates
        val finalChannels = seen.mapValues { (_, acc) ->
            acc.channel.copy(alternates = acc.alts.toList())
        }
        return groups.map { g ->
            g.copy(channels = g.channels.mapNotNull { finalChannels[normalizeName(it.name)] })
        }.filter { it.channels.isNotEmpty() }
    }

    /** 频道名归一化，用于同名匹配：CCTV-1 综合 / CCTV1综合 → cctv1。 */
    fun normalizeName(name: String): String = name
        .lowercase()
        .replace(Regex("""[\s\-_·、,，.。:：()（）\[\]【】]"""), "")
        .replace("高清", "")
        .replace("标清", "")
        .replace("超清", "")
        .replace("频道", "")
        .trim()

    /**
     * 从「包装页」里取出真实流地址。
     * 音乐库项目的 tv.json 里大量频道是这种形式：
     *   https://xxx/tv-web/live.html?url=<真实流地址>
     * 这类地址依赖已下线的域名做跳转，必须把内层地址抽出来直接播放。
     */
    fun directStreamOf(raw: String): String? {
        var cur = raw.trim()
        var depth = 0
        while (depth < 3) {
            val inner = unwrapOnce(cur) ?: break
            if (inner == cur) break
            cur = inner
            depth++
        }
        return cur
    }

    private fun unwrapOnce(url: String): String? {
        val idx = url.indexOf("url=", ignoreCase = true)
        if (idx < 0) return null
        if (!(url.contains("live.html") || url.contains("live.js") || url.contains("/tv-web/"))) return null
        val value = url.substring(idx + 4).substringBefore('&').trim()
        if (value.isEmpty()) return null
        return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }

    /** 是否是可交给 ExoPlayer 直连的媒体流。 */
    fun isDirectStream(url: String): Boolean {
        val u = url.lowercase()
        if (!u.startsWith("http")) return false
        if (u.contains("live.html") || u.contains("live.js")) return false
        return u.contains(".m3u8") || u.contains(".flv") || u.contains(".mp4") || u.contains("m3u8")
    }

    /** 是不是「需要浏览器引擎打开」的电视台网页（央视网、各省台直播页等）。 */
    fun isWebPage(url: String): Boolean = !isDirectStream(url)

    /**
     * 一个频道的全部候选播放地址，**顺序即优先级**。
     *
     * 关键规则：**频道自身的地址永远排第一**。
     * 央视/省台的地址是电视台网页，而这类流只有电视台自己的网页播放器能解，
     * 所以必须先走「浏览器引擎打开网页」这条路线；直连备用源排在后面，
     * 只有网页路线放不出画面（健康探测超时）时才轮到它们。
     *
     * （踩过的坑：曾经把「不是直连流」的地址直接过滤掉，结果网页路线被降级成
     *  直连流，画面直接黑屏。）
     *
     * 另外会剔除**已确认下线**的备用源（例如北邮那批 m3u8 已 502），
     * 免得用户按「← →」切源时轮到一个死源。
     */
    fun candidatesOf(channel: LiveChannel): List<String> {
        val out = ArrayList<String>(channel.alternates.size + 1)
        if (!isDeadSource(channel.url)) out.add(channel.url)
        channel.alternates.forEach { if (it !in out && !isDeadSource(it)) out.add(it) }
        return out.ifEmpty { listOf(channel.url) }
    }

    /** 已知下线的源；实测返回 502，且没有替代域名。 */
    private val DEAD_HOSTS = listOf("ivi.bupt.edu.cn")

    fun isDeadSource(url: String): Boolean = DEAD_HOSTS.any { url.contains(it) }

    /**
     * 网页播放时按站点挑 UA。
     *
     * 实测结论（央视站）：
     * - 桌面 UA 下页面里的播放器能正常解析出真实 m3u8；用移动 UA 时页面会回
     *   「请移至移动端观看」或者拿到不带 CORS 头的流地址，导致 CDN 拒绝跨域请求；
     * - 央视频（yangshipin）同样只认桌面端，移动 UA 会弹「前往央视频客户端」。
     *
     * 所以默认统一用桌面 UA；只有明确需要移动端页面时才用移动 UA。
     */
    fun webUserAgentFor(pageUrl: String): String {
        val host = runCatching { java.net.URI(pageUrl).host ?: "" }.getOrDefault("").lowercase()
        return when {
            host.contains("yangshipin") -> UA_DESKTOP
            host.contains("cctv") -> UA_DESKTOP
            else -> UA_DESKTOP
        }
    }

    private const val UA_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private const val UA_MOBILE =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    /**
     * 播放某个流时该带的请求头。
     * 国内直播流普遍做防盗链：UA 不对或没有 Referer 就 403。
     * 这里按域名给一套经验值（来自音乐库项目实测结论）。
     */
    fun headersFor(streamUrl: String): Map<String, String> {
        val host = runCatching { java.net.URI(streamUrl).host ?: "" }.getOrDefault("").lowercase()
        val ua = if (host.contains("yangshipin")) Http.UA_DESKTOP else Http.UA_MOBILE
        val referer = when {
            host.endsWith("gstv.com.cn") -> "https://www.gstv.com.cn/"
            host.contains("kankanlive") -> "https://www.kankanews.com/"
            host.contains("hyrtv") -> "https://www.hyrtv.cn/"
            host.contains("cctv") -> "https://tv.cctv.com/"
            host.contains("cctvpic") -> "https://tv.cctv.com/"
            host.contains("yangshipin") -> "https://www.yangshipin.cn/"
            host.contains("sctv") -> "https://www.sctv.com/"
            host.contains("jxgdw") || host.contains("jxntv") -> "https://www.jxntv.cn/"
            host.contains("dztv") -> "https://iapp.dztv.tv/"
            host.contains("chinamcache") -> "https://www.zzjy.gov.cn/"
            host.contains("qq.com") -> "https://v.qq.com/"
            host.contains("migu") -> "https://www.miguvideo.com/"
            else -> ""
        }
        return buildMap {
            put("User-Agent", ua)
            if (referer.isNotEmpty()) put("Referer", referer)
        }
    }

    /**
     * 内置兜底频道表（assets 缺失时用）。
     * 这几个是长期稳定的央视公开 HLS 源。
     */
    private fun fallbackCctv(): List<LiveGroup> = listOf(
        LiveGroup(
            "央视",
            knownCctvStreams().map { (key, urls) ->
                LiveChannel(
                    name = key.replaceFirstChar { it.uppercase() },
                    url = urls.first(),
                    group = "央视",
                )
            }
        )
    )
}
