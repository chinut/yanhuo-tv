package com.chinut.bawantv.unified

import com.chinut.bawantv.BawanApp
import com.chinut.bawantv.core.Pinyin
import com.chinut.bawantv.ddys.Ddys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * **低端影视核心库**（本地缓存）。
 *
 * ## 为什么要有它
 *
 * 之前的做法是「聚合各源 → 按标题合并 → 用户想搜什么搜什么」，
 * 结果有两个问题：
 *  1. **每次进页面都要联网重抓**，慢，而且网络一差就空白；
 *  2. 用户面对的是"搜索引擎"，得先知道自己想看什么 —— 电视上很难用。
 *
 * 现在的定位是：**低端影视是内容基础，有什么用户看什么**。
 * 所以把它的片库**整份缓存到本地**，进页面直接展示；联网只是"补货"。
 *
 * ## 数据来源
 *
 * 低端影视官方 JSON 接口（[Ddys]），不需要解析 HTML，也**不需要过验证**。
 * 注意域名可能被墙，[Ddys] 内部会做镜像探测。
 *
 * ## 存储格式
 *
 * 用简单的行式文本（每行一条，字段用 U+001F 分隔），不用 JSON：
 *  - 解析快、体积小，几千条也就几百 KB
 *  - 字段里有换行/分隔符时做转义，保证不会把记录读串
 *
 * 放在「文件」而不是 SharedPreferences：SharedPreferences 是整份读进内存的
 * XML，塞几千条片单会明显拖慢启动。
 */
object LibraryStore {

    private const val FILE_NAME = "ddys_library.tsv"
    private const val SEP = '\u001F'
    private const val MAX_ITEMS = 3000

    /** 库里的最后更新时间（毫秒）。 */
    @Volatile
    var updatedAt: Long = 0L
        private set

    /** 内存缓存，避免每次进页面都读文件。 */
    @Volatile
    private var memo: List<UnifiedMovie>? = null

    private fun file(): File = File(BawanApp.ctx().filesDir, FILE_NAME)

    /** 读本地库（进程内缓存）。没有缓存文件返回空。 */
    fun load(): List<UnifiedMovie> {
        memo?.let { return it }
        val f = file()
        if (!f.exists()) return emptyList()
        val list = runCatching {
            f.readLines()
                .asSequence()
                .mapNotNull { parseLine(it) }
                .toList()
        }.getOrDefault(emptyList())
        memo = list
        return list
    }

    /** 库里是否已有内容（用来决定要不要显示"首次准备中"）。 */
    val hasLibrary: Boolean get() = load().isNotEmpty()

    /**
     * 从低端影视拉取并**替换**本地库。
     *
     * ## 为什么分类型抓
     *
     * 低端影视的 `/movies` 是混合流（电影 + 连续剧 + 综艺 + 动漫混在一起）。
     * 但用户找片时是**按类型找**的（"我想看部剧" / "我想看个电影"），
     * 而且混合流里连续剧的更新频率远高于电影，前几页可能全是剧集，
     * 电影会被挤到很后面。
     *
     * 所以对每个类型分别抓，保证**每一类都有足够的内容**，
     * 首屏也不会出现"翻好几页全是剧"的情况。
     *
     * @param pagesPerType 每个类型抓几页（每页 30 条）
     * @param onProgress 每抓完一页回调一次，用于显示进度
     * @return 最终库内容
     */
    suspend fun refresh(
        pagesPerType: Int = 5,
        onProgress: ((done: Int, total: Int, count: Int) -> Unit)? = null,
    ): List<UnifiedMovie> = withContext(Dispatchers.IO) {
        val api = runCatching { Ddys.api() }.getOrNull() ?: return@withContext load()

        val acc = LinkedHashMap<String, UnifiedMovie>()
        val totalSteps = TYPES.size * pagesPerType
        var done = 0

        TYPES.forEach { type ->
            for (p in 1..pagesPerType) {
                val batch = runCatching {
                    api.browse(page = p, type = type, perPage = 30).first
                }.getOrDefault(emptyList())

                // 某一页空了说明这类到底了，换下一个类型
                if (batch.isEmpty()) break

                batch.forEach { m ->
                    val movie = runCatching { MovieAggregator.fromDdysItem(m) }.getOrNull()
                    if (movie != null) acc[movie.id] = movie
                }
                done++
                onProgress?.invoke(done, totalSteps, acc.size)
                if (acc.size >= MAX_ITEMS) break
            }
            if (acc.size >= MAX_ITEMS) return@forEach
        }

        if (acc.isEmpty()) {
            // 一条都没抓到（多半是网络问题）→ 保留旧库，别把好的缓存冲掉
            return@withContext load()
        }

        val list = acc.values.toList()
        runCatching {
            file().writeText(list.joinToString("\n") { toLine(it) })
            updatedAt = System.currentTimeMillis()
            memo = list
        }
        list
    }

    /** 抓取的类型。顺序即优先级：电影和剧集是主体，综艺/动漫锦上添花。 */
    private val TYPES = listOf("movie", "series", "anime", "variety")

    /**
     * 在**本地库**里搜索。
     *
     * 为什么不直接调接口搜索：本地库是"应有的内容"，而且
     * 支持拼音首字母（接口只认汉字），这对电视遥控器很关键。
     */
    fun search(keyword: String): List<UnifiedMovie> {
        if (keyword.isBlank()) return emptyList()
        return load().filter { Pinyin.matches(keyword, it.title) }
    }

    /** 按类型筛选（电影 / 电视剧 / 动漫…）。空串表示全部。 */
    fun byType(type: String): List<UnifiedMovie> {
        if (type.isBlank()) return load()
        return load().filter { it.typeName.contains(type) }
    }

    /** 库里出现过的类型（按出现次数排序），用来做筛选栏。 */
    fun typeNames(): List<String> {
        val counter = HashMap<String, Int>()
        load().forEach { m ->
            // typeName 里可能塞了多个分类（类型 + 题材），拆开分别计数
            m.typeName.split(' ', '，', ',', '/')
                .map { it.trim() }
                .filter { it.length in 2..6 }
                .forEach { counter[it] = (counter[it] ?: 0) + 1 }
        }
        return counter.entries
            .sortedByDescending { it.value }
            .map { it.key }
            .take(14)
    }

    fun clear() {
        runCatching { file().delete() }
        memo = null
        updatedAt = 0L
    }

    // ==================== 行式序列化 ====================

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\n", "\\n").replace(SEP.toString(), "\\u")

    private fun unesc(s: String): String = buildString(s.length) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> { append('\n'); i += 2 }
                    'u' -> { append(SEP); i += 2 }
                    '\\' -> { append('\\'); i += 2 }
                    else -> { append(c); i++ }
                }
            } else {
                append(c); i++
            }
        }
    }

    private fun toLine(m: UnifiedMovie): String = listOf(
        m.id, m.title, m.poster, m.year, m.typeName, m.area, m.score, m.remarks,
    ).joinToString(SEP.toString()) { esc(it) }

    private fun parseLine(line: String): UnifiedMovie? {
        if (line.isBlank()) return null
        val f = line.split(SEP)
        if (f.size < 8) return null
        return UnifiedMovie(
            id = unesc(f[0]),
            title = unesc(f[1]),
            poster = unesc(f[2]),
            year = unesc(f[3]),
            typeName = unesc(f[4]),
            area = unesc(f[5]),
            score = unesc(f[6]),
            remarks = unesc(f[7]),
            // 核心库的内容天生带 ddys 源；TVBox 补充源在需要时才去查
            sources = listOf(
                UnifiedSource(
                    id = "ddys",
                    remoteId = unesc(f[0]),
                    name = "低端影视",
                    quality = "官方",
                )
            ),
        )
    }
}
