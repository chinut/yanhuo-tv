package com.chinut.bawantv.core

import com.chinut.bawantv.BawanApp

/**
 * 一条观看记录。
 *
 * 用途有两个，所以字段要能同时支撑它们：
 *  1. **断点续播** —— 知道某部作品看到第几集、第几毫秒
 *  2. **首页「继续观看」入口** —— 要能直接用这条记录重新起播，
 *     所以必须把起播所需的信息（剧集列表、线路标识）一起存下来，
 *     否则首页点进去还得重新搜索一遍才能播。
 *
 * @param kind 来源：`vod`（TVBox 订阅）或 `ddys`（低端影视）
 * @param id 作品在来源里的唯一标识（VOD 是 vodId，ddys 是 slug）
 * @param title 显示标题
 * @param poster 海报地址（没有海报就空着，UI 用渐变兜底）
 * @param episodeIndex 看到第几集（电影固定 0）
 * @param episodeName 第几集的显示名（例如「第12集」）
 * @param flag VOD 的线路标识（播放时要按线路解析地址）
 * @param episodeUrls 该线路的**全部剧集地址**，用于恢复自动联播
 * @param episodeNames 与 episodeUrls 一一对应的显示名
 * @param positionMs 播放进度
 * @param durationMs 总时长（用于算观看百分比、判断是否看完）
 * @param updatedAt 最后观看时间戳，用于排序
 */
data class WatchRecord(
    /**
     * 来源标识。
     *
     * 影视现在只有低端影视，所以新记录一律是 [KIND_DDYS]。
     * 保留这个字段是因为它是记录去重键的一部分（`kind|id`），
     * 删掉会让旧记录和"继续观看"的匹配逻辑出问题。
     */
    val kind: String,
    val id: String,
    val title: String,
    val poster: String = "",
    val episodeIndex: Int = 0,
    val episodeName: String = "",
    /**
     * 线路标识。
     *
     * **已经不再使用**：影视只保留低端影视之后，"按线路解析地址"那套
     * （TVBox 时代的东西）没有了，地址本身就是直连的。
     *
     * 但字段**必须保留**：它是 [encode] 的第 7 个字段，
     * 删掉会让老用户已存的观看记录整体错位、解析失败（等于清空历史）。
     * 所以继续占位，读的时候照旧忽略。
     */
    val flag: String = "",
    val episodeUrls: List<String> = emptyList(),
    val episodeNames: List<String> = emptyList(),
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val updatedAt: Long = 0L,
) {
    /** 已经看了多少（0~1）。时长未知时返回 0。 */
    val progress: Float
        get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /** 是不是「基本看完了」：看完的就不该留在继续观看里。 */
    val finished: Boolean
        get() = durationMs > 0L && positionMs >= durationMs - 30_000L

    /** 序列化成一行，字段之间用不可见分隔符，避免和地址里的字符冲突。 */
    fun encode(): String = listOf(
        kind, id, title, poster,
        episodeIndex.toString(), episodeName, flag,
        episodeUrls.joinToString(URL_SEP), episodeNames.joinToString(URL_SEP),
        positionMs.toString(), durationMs.toString(), updatedAt.toString(),
    ).joinToString(FIELD_SEP)

    companion object {
        /** 字段分隔符：用单元分隔符（U+001F），正常文本里不会出现。 */
        private const val FIELD_SEP = "\u001F"

        /** 列表内分隔符：用记录分隔符（U+001E）。 */
        private const val URL_SEP = "\u001E"

        fun decode(line: String): WatchRecord? {
            val f = line.split(FIELD_SEP)
            if (f.size < 12) return null
            fun at(i: Int) = f.getOrNull(i).orEmpty()
            fun listOf(i: Int) = at(i).takeIf { it.isNotEmpty() }?.split(URL_SEP) ?: emptyList()
            return runCatching {
                WatchRecord(
                    kind = at(0),
                    id = at(1),
                    title = at(2),
                    poster = at(3),
                    episodeIndex = at(4).toIntOrNull() ?: 0,
                    episodeName = at(5),
                    flag = at(6),
                    episodeUrls = listOf(7),
                    episodeNames = listOf(8),
                    positionMs = at(9).toLongOrNull() ?: 0L,
                    durationMs = at(10).toLongOrNull() ?: 0L,
                    updatedAt = at(11).toLongOrNull() ?: 0L,
                )
            }.getOrNull()
        }

        /** 进度低于这个值就不值得记（刚点开就退出，记了反而碍事）。 */
        const val MIN_POSITION_MS = 15_000L
    }
}

/**
 * 观看历史仓库。
 *
 * 存成一行一条记录，最多保留 [MAX_RECORDS] 条，按最后观看时间倒序。
 * 内容不大（每条最多带一部剧的剧集地址列表），所以直接放在 SharedPreferences 里，
 * 不引入数据库 —— 电视端这点数据量完全够用，也省一次依赖。
 */
object WatchHistory {

    /** 来源标识：TVBox 影视订阅。 */
    const val KIND_VOD = "vod"

    /** 来源标识：低端影视。 */
    const val KIND_DDYS = "ddys"

    private const val MAX_RECORDS = 60

    private fun prefs() = BawanApp.prefs

    /** 读取全部记录，已按最近观看排序。 */
    fun all(): List<WatchRecord> =
        prefs().watchHistoryRaw
            .lineSequence()
            .mapNotNull { it.takeIf { l -> l.isNotBlank() }?.let(WatchRecord::decode) }
            .sortedByDescending { it.updatedAt }
            .toList()

    /** 「继续观看」：过滤掉已看完的，取前 N 条。 */
    fun continueWatching(limit: Int = 10): List<WatchRecord> =
        all().filter { !it.finished && it.positionMs >= WatchRecord.MIN_POSITION_MS }.take(limit)

    /** 查某部作品上次看到哪。 */
    fun find(kind: String, id: String): WatchRecord? =
        all().firstOrNull { it.kind == kind && it.id == id }

    private fun key(kind: String, id: String) = "$kind|$id"

    /**
     * 记录/更新播放进度。
     *
     * 同一部作品只保留一条（同 kind + id 会被覆盖），
     * 这样首页「继续观看」不会同一部剧出现好几条。
     */
    fun save(record: WatchRecord) {
        val merged = linkedMapOf<String, WatchRecord>()
        merged[key(record.kind, record.id)] = record.copy(updatedAt = System.currentTimeMillis())
        all().forEach { old ->
            val k = key(old.kind, old.id)
            if (!merged.containsKey(k)) merged[k] = old
        }
        // 超出上限就丢掉最旧的
        val kept = merged.values.sortedByDescending { it.updatedAt }.take(MAX_RECORDS)
        prefs().watchHistoryRaw = kept.joinToString("\n") { it.encode() }
    }

    /** 删除一条记录。 */
    fun remove(kind: String, id: String) {
        val kept = all().filterNot { it.kind == kind && it.id == id }
        prefs().watchHistoryRaw = kept.joinToString("\n") { it.encode() }
    }

    /** 清空历史。 */
    fun clear() {
        prefs().watchHistoryRaw = ""
    }
}
