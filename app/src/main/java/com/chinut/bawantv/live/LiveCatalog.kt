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

    private const val TAG = "BawanLiveCatalog"

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
                val groups = parse(text).ifEmpty { fallbackCctv() }
                cachedBuiltin = groups
                groups
            }
        }
    }

    /*
     * ==================== 这里曾经尝试"直连流优先"，已撤销 ====================
     *
     * 起因是真机反馈：老电视看直播"卡到无法观看，大部分时间停在央视频的
     * 加载占位图上"。当时的判断是"让 WebView 跑央视频整站太重"，于是改成
     * 直接把流地址交给 ExoPlayer 硬解。
     *
     * **这个判断是错的，而且我犯了一个具体的错误：**
     *
     * 我给这些地址做的验证只有一条 —— "返回的文本以 #EXTM3U 开头"。
     * 但那个 CDN 上放的其实是**纯音频流**：
     *
     *     https://piccpndali.v.myalicdn.com/audio/cctv1_2.m3u8
     *                                          ^^^^^ 路径里就写着 audio
     *     #EXTINF:10.006,
     *     cctv1_audio/1790960938_14541823.ts      ← 分片名也带 audio
     *
     * 结果用户看到的是**只有声音、没有画面**。
     * 教训：验证媒体流必须确认**有没有视频轨**（看分片路径 / CODECS），
     * 光看"是不是合法播放列表"远远不够。
     *
     * 之后我又逐个实测了其他公开的央视直连源（电信/百视通/ivi/山东移动等），
     * **全部返回 502（早已下线）**。也就是说：
     *
     *   · 没有可用的央视直连视频流；
     *   · 央视的流是专有加密格式，只能由台站自己的网页播放器解。
     *
     * 所以现在**不再硬塞任何直连地址**，网页路线是唯一可行方案。
     * 它的性能问题不用改路线来解决，而是靠：
     *   · TvWebPlayerView 的强力起播 + 元素清理脚本
     *   · 直播页的预加载（出画面才显示 WebView，用户只看到转圈）
     *   · 连续失败时自动换下一个源
     */

    // ==================== 用户导入的本地 m3u ====================
    //
    // 为什么要有这个：用户的 IPTV 源往往是**文件**（运营商给的、
    // 或朋友发来的），让他在电视上用遥控器敲一个长网址不现实。
    //
    // 文件复制到 App 私有目录，而不是直接用 content:// URI ——
    // 因为 SAF 给的临时授权在重启后可能失效，那样用户会发现
    // "昨天还好好的源今天没了"。

    /** 导入文件的存放位置（App 私有目录）。 */
    fun importedFile(context: Context): java.io.File =
        java.io.File(context.filesDir, "custom_live.m3u")

    fun hasImported(context: Context): Boolean =
        importedFile(context).let { it.exists() && it.length() > 0 }

    /**
     * 把用户选中的文件复制进来。
     *
     * @return 成功时返回频道条数；失败返回 -1
     */
    fun importFrom(context: Context, uri: android.net.Uri): Int {
        return try {
            val text = context.contentResolver.openInputStream(uri)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: return -1
            if (text.isBlank()) return -1
            // 先解析一遍，确认真的是频道表（避免用户选错文件后
            // 频道列表整个变空，还找不到原因）
            val groups = parse(text)
            val n = groups.sumOf { it.channels.size }
            if (n == 0) {
                android.util.Log.w(TAG, "导入失败：文件里没解析出频道")
                return -1
            }
            importedFile(context).writeText(text, Charsets.UTF_8)
            android.util.Log.i(TAG, "导入成功：$n 个频道 → ${importedFile(context).name}")
            n
        } catch (e: Exception) {
            android.util.Log.w(TAG, "导入异常：${e.javaClass.simpleName} ${e.message}")
            -1
        }
    }

    fun clearImported(context: Context) {
        runCatching { importedFile(context).delete() }
    }

    /**
     * 最终频道表：内置频道 + 用户自定义源（如果有）。
     * 自定义源单独成一类，放在最前面，避免和内置的混在一起。
     *
     * 顺序：**本地导入的文件优先**，其次才是自定义网址。
     * 两者都给的话，本地文件赢 —— 因为它更可能是用户特意准备的。
     */
    suspend fun load(context: Context, customSourceUrl: String): List<LiveGroup> {
        val builtin = builtin(context)

        // 1) 本地导入的文件
        if (hasImported(context)) {
            val text = runCatching {
                importedFile(context).readText(Charsets.UTF_8)
            }.getOrNull()
            if (!text.isNullOrBlank()) {
                val custom = parse(text)
                if (custom.isNotEmpty()) {
                    val merged = ArrayList<LiveGroup>(custom.size + builtin.size)
                    custom.forEach { g ->
                        merged.add(g.copy(name = "我的源 · ${g.name}"))
                    }
                    merged.addAll(builtin)
                    android.util.Log.i(
                        TAG,
                        "用本地导入的源：${custom.sumOf { it.channels.size }} 个频道",
                    )
                    return mergeAlternates(merged)
                }
            }
            android.util.Log.w(TAG, "本地导入的文件解析不出频道，退回内置")
        }

        // 2) 自定义网址
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
        if (!isDeadSource(channel.url) && !isAudioOnlyStream(channel.url)) out.add(channel.url)
            channel.alternates.forEach { if (it !in out && !isDeadSource(it) && !isAudioOnlyStream(it)) out.add(it) }
        return out.ifEmpty { listOf(channel.url) }
    }

    /** 已知下线的源；实测返回 502，且没有替代域名。 */
    private val DEAD_HOSTS = listOf("ivi.bupt.edu.cn")

    fun isDeadSource(url: String): Boolean = DEAD_HOSTS.any { url.contains(it) }

    /**
     * 已知的**纯音频**流，不能当视频源用。
     *
     * 血的教训：我加过一批"央视直连流"，验证方式只有"返回文本以 #EXTM3U 开头"，
     * 结果它们其实是音频流（路径 `/audio/`、分片 `cctv1_audio/xxx.ts`），
     * 用户看到的是**只有声音没有画面**。
     *
     * 所以凡是路径里带 `audio` 的一律排除 —— 宁可退回网页路线，
     * 也不要给用户一个"能播但没画面"的源。
     */
    private val AUDIO_ONLY_HINTS = listOf("/audio/", "_audio/", "audio-only")

    /** 这个地址是不是已知的纯音频流。 */
    fun isAudioOnlyStream(url: String): Boolean =
        AUDIO_ONLY_HINTS.any { url.lowercase().contains(it) }

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
            // 央视自有直连流 CDN（CCTV 各频道的硬解源）。必带 Referer，否则被拒。
            host.contains("myalicdn") || host.contains("alicdn") -> "https://tv.cctv.com/"
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
     * 内置兜底频道表（assets 读取失败时才用）。
     *
     * 这里用的是**电视台自己的网页直播页**，和主频道表同一套地址。
     *
     * 以前这里拿的是一批第三方 HLS 直连地址，但那个域名已经下线 ——
     * 也就是说"兜底表"本身是坏的，真出问题时反而播不了。
     * 现在统一用官方网页地址：走 [TvWebPlayerView] 浏览器引擎播放，
     * 和正常频道的路径完全一致，不会再出现"兜底源全是死的"这种事。
     */
    private fun fallbackCctv(): List<LiveGroup> = listOf(
        LiveGroup(
            "央视",
            listOf(
                "CCTV-1 综合" to "https://tv.cctv.com/live/cctv1/",
                "CCTV-2 财经" to "https://tv.cctv.com/live/cctv2/",
                "CCTV-3 综艺" to "https://tv.cctv.com/live/cctv3/",
                "CCTV-4 中文国际" to "https://tv.cctv.com/live/cctv4/",
                "CCTV-5 体育" to "https://tv.cctv.com/live/cctv5/",
                "CCTV-6 电影" to "https://tv.cctv.com/live/cctv6/",
                "CCTV-7 国防军事" to "https://tv.cctv.com/live/cctv7/",
                "CCTV-8 电视剧" to "https://tv.cctv.com/live/cctv8/",
                "CCTV-9 纪录" to "https://tv.cctv.com/live/cctv9/",
                "CCTV-10 科教" to "https://tv.cctv.com/live/cctv10/",
                "CCTV-11 戏曲" to "https://tv.cctv.com/live/cctv11/",
                "CCTV-12 社会与法" to "https://tv.cctv.com/live/cctv12/",
                "CCTV-13 新闻" to "https://tv.cctv.com/live/cctv13/",
                "CCTV-14 少儿" to "https://tv.cctv.com/live/cctv14/",
                "CCTV-15 音乐" to "https://tv.cctv.com/live/cctv15/",
                "CCTV-17 农业农村" to "https://tv.cctv.com/live/cctv17/",
            ).map { (name, url) ->
                LiveChannel(name = name, url = url, group = "央视")
            }
        )
    )
}
