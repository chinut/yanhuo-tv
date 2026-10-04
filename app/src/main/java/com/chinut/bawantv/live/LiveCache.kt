package com.chinut.bawantv.live

import android.content.Context
import java.io.File

/**
 * 直播频道表的**磁盘缓存** —— 让频道秒开。
 *
 * # 用户问题
 *
 * 「通常来说用户安装完 app 且首次设置完成后基本不会动设置，
 *   那么我希望直播的频道每次不要加载那么长时间，
 *   有没有什么机制可以让用户的频道可以加载得快一点」
 *
 * # 现在慢在哪（实测定位）
 *
 * | 慢点 | 原因 |
 * |---|---|
 * | `LiveCatalog.builtin()` | 每次冷启动都要**重新解析 544 个频道**的 m3u（只在内存缓存，进程一死就没了）|
 * | 开源源 | **每次启动都去拉 GitHub**，在用户电视上必然超时（12 秒预算耗尽才退回内置）|
 *
 * 两个问题其实是同一个：**结果没有落到磁盘，所以每次冷启动都要重来一遍**。
 *
 * # 做法：磁盘缓存 + 新鲜度判断
 *
 * · 首次算完把结果写盘
 * · 下次冷启动**先读盘**，命中就直接用，完全不碰网络和 assets 解析
 * · 缓存够新（[TTL_MS] 内）就**跳过联网刷新** —— 这正是用户说的
 *   "设置完基本不会动"，没必要每次都去拉
 * · 缓存过期也不阻塞：先用旧的看着，后台再刷（调用方决定）
 *
 * 和 [com.chinut.bawantv.unified.QimaoSource] 的磁盘缓存是同一套思路
 * （那边已验证有效：断网也能出 119 部短剧）。
 *
 * # 为什么用 TSV 而不是 JSON
 *
 * kotlinx.serialization 在 544 个频道上要反射建对象，在老电视上不快；
 * TSV 手写解析几毫秒就完事，而且**格式一目了然**，出问题好排查。
 *
 * # 文件格式
 *
 *     #v1 <TAB> 配置指纹 <TAB> 写入时间戳
 *     G <US> 分组名
 *     C <US> name <US> url <US> logo <US> alternates(RS 分隔)
 *     ...
 *
 * 所有字段用 `\u001F`(US) 分隔、alternates 用 `\u001E`(RS) 分隔，
 * 这样频道名里出现任何常见字符都不会串味。
 */
object LiveCache {

    private const val TAG = "BawanLiveCache"
    private const val FILE = "live_cache.tsv"

    /** 频道表的新鲜期。这段时间内直接用缓存，不去联网。 */
    const val TTL_MS = 6 * 60 * 60 * 1000L      // 6 小时

    private const val US = '\u001F'      // 字段分隔
    private const val RS = '\u001E'      // 数组内分隔

    /**
     * 缓存内容。
     *
     * 分组和扁平表都存 —— 首页预览用扁平表（换台顺序），
     * 频道墙用分组结构，两个都要，存一份省得算两遍。
     */
    data class Snap(
        val groups: List<LiveGroup>,
        val channels: List<LiveChannel>,
        val at: Long,
        val key: String,
    ) {
        fun isFresh(now: Long = System.currentTimeMillis()): Boolean = now - at < TTL_MS
    }

    private fun fileOf(context: Context): File = File(context.filesDir, FILE)

    /**
     * 缓存指纹的**唯一算法**。
     *
     * ⚠️ 必须只有这一份。缓存读写用的 key 只要有一点不一致，
     * 就会出现"写进去了但读不出来"（表现为缓存永远不命中、
     * 或者设置改了但列表没变）。之前 HomeWarmup 想自己拼一个 key，
     * 就是这种隐患 —— 所以这里对外开放，谁需要谁来调。
     */
    fun keyOf(
        presetName: String,
        oldTvMode: Boolean,
        customSourceUrl: String,
        hasImported: Boolean,
    ): String = listOf(
        presetName, oldTvMode.toString(), customSourceUrl.trim(), hasImported.toString(),
    ).joinToString("|")

    // ---------- 写 ----------

    fun save(
        context: Context,
        groups: List<LiveGroup>,
        channels: List<LiveChannel>,
        key: String,
    ) {
        if (channels.isEmpty()) return
        runCatching {
            val sb = StringBuilder(channels.size * 64)
            sb.append("#v1").append('\t').append(key).append('\t')
                .append(System.currentTimeMillis()).append('\n')

            groups.forEach { g ->
                sb.append('G').append(US).append(g.name).append('\n')
                g.channels.forEach { c ->
                    sb.append('C').append(US)
                        .append(c.name).append(US)
                        .append(c.url).append(US)
                        .append(c.logo).append(US)
                        .append(c.alternates.joinToString(RS.toString()))
                        .append('\n')
                }
            }

            // 先写临时文件再改名：中途被杀不会留下半个文件
            val tmp = File(context.filesDir, "$FILE.tmp")
            tmp.writeText(sb.toString(), Charsets.UTF_8)
            val dst = fileOf(context)
            if (dst.exists()) dst.delete()
            tmp.renameTo(dst)
            android.util.Log.i(
                TAG,
                "频道表已落盘：${groups.size} 组 / ${channels.size} 个频道",
            )
        }.onFailure {
            android.util.Log.w(TAG, "写频道缓存失败：${it.message}")
        }
    }

    // ---------- 读 ----------

    /** 读缓存；没有、损坏、或配置对不上都返回 null。 */
    fun load(context: Context, key: String): Snap? = runCatching {
        val f = fileOf(context)
        if (!f.exists()) return null
        val lines = f.readLines(Charsets.UTF_8)
        if (lines.isEmpty()) return null

        val head = lines[0].split('\t')
        if (head.size < 3 || head[0] != "#v1") return null
        val cachedKey = head[1]
        val at = head[2].toLongOrNull() ?: return null
        // 配置变了（用户换了主源 / 开关了老电视模式）→ 缓存作废
        if (cachedKey != key) {
            android.util.Log.i(TAG, "配置变了（缓存=$cachedKey 现在=$key），频道缓存作废")
            return null
        }

        val groups = ArrayList<LiveGroup>()
        val flat = ArrayList<LiveChannel>()
        var curName: String? = null
        var curList = ArrayList<LiveChannel>()

        fun flush() {
            val n = curName ?: return
            groups.add(LiveGroup(n, curList))
        }

        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            val p = line.split(US)
            when (p[0]) {
                "G" -> {
                    flush()
                    curName = p.getOrNull(1).orEmpty()
                    curList = ArrayList()
                }
                "C" -> {
                    if (p.size < 5) continue
                    val c = LiveChannel(
                        name = p[1],
                        url = p[2],
                        group = curName.orEmpty(),
                        logo = p[3],
                        alternates = p[4].takeIf { it.isNotEmpty() }
                            ?.split(RS)?.filter { it.isNotEmpty() }.orEmpty(),
                    )
                    curList.add(c)
                    flat.add(c)
                }
            }
        }
        flush()

        if (flat.isEmpty()) return null
        Snap(groups, flat, at, cachedKey)
    }.getOrElse {
        android.util.Log.w(TAG, "读频道缓存失败：${it.message}")
        null
    }

    /** 从扁平表还原分组（没缓存到分组时的兜底）。保持首次出现的顺序。 */
    fun grouped(channels: List<LiveChannel>): List<LiveGroup> {
        val out = LinkedHashMap<String, MutableList<LiveChannel>>()
        channels.forEach { c ->
            out.getOrPut(c.group) { ArrayList() }.add(c)
        }
        return out.map { (name, list) -> LiveGroup(name, list) }
    }

    fun clear(context: Context) {
        runCatching {
            fileOf(context).delete()
            File(context.filesDir, "$FILE.tmp").delete()
        }
    }
}
