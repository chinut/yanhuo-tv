package com.chinut.bawantv.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 全局设置仓库（板块 D 的数据源）。
 *
 * 用 SharedPreferences 承载，配一个 [StateFlow] 让界面实时响应改动。
 * 所有字段都可以通过「手机网页调试模式」远程修改，改完立即生效。
 */
class AppPrefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("bawan_prefs", Context.MODE_PRIVATE)

    /** 设置变更通知（任意字段变化都 +1） */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private fun touch() {
        _revision.value = _revision.value + 1
    }

    /** 板块 C 的域名（网站换域名时在这里改）。默认用官方永久域名。 */
    var domain: String
        get() = sp.getString(KEY_DOMAIN, DEFAULT_DOMAIN)?.trim()?.trimEnd('/')
            ?.removePrefix("http://")?.removePrefix("https://")
            ?.let { if (it.isBlank()) DEFAULT_DOMAIN else it } ?: DEFAULT_DOMAIN
        set(v) = sp.edit().putString(KEY_DOMAIN, v.trim()).apply().also { touch() }

    /** 板块 C 是否在启动时探测四个官方域名，挑一个能通的 */
    var autoPickDomain: Boolean
        get() = sp.getBoolean(KEY_AUTO_PICK, true)
        set(v) = sp.edit().putBoolean(KEY_AUTO_PICK, v).apply().also { touch() }

    /** 上次探测出来的可用域名 */
    var resolvedDomain: String
        get() = sp.getString(KEY_RESOLVED_DOMAIN, "") ?: ""
        set(v) = sp.edit().putString(KEY_RESOLVED_DOMAIN, v).apply()

    /** 板块 C 是否使用 WebView 兜底（遇到 Cloudflare 验证时自动开启） */
    var webFallbackEnabled: Boolean
        get() = sp.getBoolean(KEY_WEB_FALLBACK, true)
        set(v) = sp.edit().putBoolean(KEY_WEB_FALLBACK, v).apply().also { touch() }

    /** 自定义 UA（留空用内置桌面 UA） */
    var userAgent: String
        get() = sp.getString(KEY_UA, "") ?: ""
        set(v) = sp.edit().putString(KEY_UA, v).apply().also { touch() }

    /** 自动探测可用域名（每次启动尝试镜像域名，取第一个通的） */
    var autoDomain: Boolean
        get() = sp.getBoolean(KEY_AUTO_DOMAIN, true)
        set(v) = sp.edit().putBoolean(KEY_AUTO_DOMAIN, v).apply().also { touch() }
    var ddysPageSize: Int
        get() = sp.getInt(KEY_DDYS_PAGE_SIZE, 30)
        set(v) = sp.edit().putInt(KEY_DDYS_PAGE_SIZE, v.coerceIn(12, 50)).apply().also { touch() }

    // ==================== 板块 A：直播 ====================

    /** 自定义直播源（m3u / txt），留空用内置央视直播源 */
    var liveSourceUrl: String
        get() = sp.getString(KEY_LIVE_SRC, "") ?: ""
        set(v) = sp.edit().putString(KEY_LIVE_SRC, v).apply().also { touch() }

    /** 启动后自动进入上次看的频道（老电视那种「开机就是台」） */
    var autoPlayLastChannel: Boolean
        get() = sp.getBoolean(KEY_AUTO_LAST, true)
        set(v) = sp.edit().putBoolean(KEY_AUTO_LAST, v).apply().also { touch() }

    /** 上次播放的频道地址 */
    var lastChannelUrl: String
        get() = sp.getString(KEY_LAST_CHANNEL, "") ?: ""
        set(v) = sp.edit().putString(KEY_LAST_CHANNEL, v).apply()

    /** 收藏的频道地址（换行分隔） */
    var favoriteChannels: String
        get() = sp.getString(KEY_FAV_CH, "") ?: ""
        set(v) = sp.edit().putString(KEY_FAV_CH, v).apply().also { touch() }

    // ==================== 手机网页调试 ====================

    /** 局域网调试服务端口 */
    var debugPort: Int
        get() = sp.getInt(KEY_PORT, 8899)
        set(v) = sp.edit().putInt(KEY_PORT, v).apply().also { touch() }

    var debugEnabled: Boolean
        get() = sp.getBoolean(KEY_DEBUG_ON, true)
        set(v) = sp.edit().putBoolean(KEY_DEBUG_ON, v).apply().also { touch() }

    /** 修改设置的口令（手机网页上需要输入） */
    var debugToken: String
        get() = sp.getString(KEY_TOKEN, "") ?: ""
        set(v) = sp.edit().putString(KEY_TOKEN, v).apply().also { touch() }

    // ==================== 更新 ====================

    var autoCheckUpdate: Boolean
        get() = sp.getBoolean(KEY_AUTO_UPDATE, true)
        set(v) = sp.edit().putBoolean(KEY_AUTO_UPDATE, v).apply().also { touch() }

    var skippedVersion: Int
        get() = sp.getInt(KEY_SKIP_VERSION, 0)
        set(v) = sp.edit().putInt(KEY_SKIP_VERSION, v).apply().also { touch() }

    // ==================== 播放偏好 ====================

    /** 直播上次的音量（仅记录用；App 不再主动改系统音量） */
    var liveVolume: Int
        get() = sp.getInt(KEY_VOLUME, 100)
        set(v) = sp.edit().putInt(KEY_VOLUME, v.coerceIn(0, 100)).apply()

    /** 直播解码：true=硬件（默认），false=软件（花屏时用） */
    var hardwareDecode: Boolean
        get() = sp.getBoolean(KEY_HW_DECODE, true)
        set(v) = sp.edit().putBoolean(KEY_HW_DECODE, v).apply().also { touch() }

    /** 换台时是否显示台标浮层 */
    var showChannelHud: Boolean
        get() = sp.getBoolean(KEY_SHOW_HUD, true)
        set(v) = sp.edit().putBoolean(KEY_SHOW_HUD, v).apply().also { touch() }

    // ==================== 每个频道记住「能播的那个源」 ====================

    /**
     * 频道 → 上次成功播放的源地址。
     *
     * 一个台往往有多个来源（央视网 / 央视频），有的能播有的播不出。
     * 这里把「确实放出画面来的那个源」记下来，下次进这个台直接用它，
     * 省掉「先黑屏一会儿再自动跳源」的等待；万一它失效了，
     * 健康探测照样会自动往后切，并且把新的可用源覆盖上来。
     *
     * 存成一行一条 `频道标识=源地址`，读的时候做成 Map。
     */
    private fun sourceMemoryMap(): MutableMap<String, String> {
        val out = linkedMapOf<String, String>()
        sp.getString(KEY_SOURCE_MEMORY, "")
            .orEmpty()
            .lineSequence()
            .forEach { line ->
                val i = line.indexOf('=')
                if (i > 0) out[line.substring(0, i)] = line.substring(i + 1)
            }
        return out
    }

    /** 查询某频道记住的可用源；没有记录返回 null。 */
    fun rememberedSource(channelKey: String): String? =
        sourceMemoryMap()[channelKey]?.takeIf { it.isNotBlank() }

    /** 记录某频道「这个源能播」。同值不重复写，避免频繁落盘。 */
    fun rememberSource(channelKey: String, url: String) {
        val map = sourceMemoryMap()
        if (map[channelKey] == url) return
        map[channelKey] = url
        // 只保留最近 400 条，防止无限增长
        while (map.size > 400) {
            val first = map.keys.firstOrNull() ?: break
            map.remove(first)
        }
        sp.edit()
            .putString(KEY_SOURCE_MEMORY, map.entries.joinToString("\n") { "${it.key}=${it.value}" })
            .apply()
    }

    /** 清空源记忆（设置页「重置源记忆」用）。 */
    fun clearSourceMemory() {
        sp.edit().remove(KEY_SOURCE_MEMORY).apply()
    }

    // ==================== 观看历史（断点续播 + 首页继续观看） ====================

    /** 观看历史的原始文本，一行一条记录；解析交给 `WatchHistory`。 */
    var watchHistoryRaw: String
        get() = sp.getString(KEY_WATCH_HISTORY, "").orEmpty()
        set(v) = sp.edit().putString(KEY_WATCH_HISTORY, v).apply()

    // ==================== 最近播放（首页「最近播放的频道 / 影视」） ====================

    /**
     * 最近播放过的直播频道（存的是一行一个频道名 + 地址，交给 LiveCatalog 还原）。
     * 首页要展示「最近播放的频道」，所以得把点过的台记下来。
     */
    var recentLiveRaw: String
        get() = sp.getString(KEY_RECENT_LIVE, "").orEmpty()
        set(v) = sp.edit().putString(KEY_RECENT_LIVE, v).apply()

    // ==================== 未成年人保护 ====================

    /** 未成年人保护开关。 */
    var parentalEnabled: Boolean
        get() = sp.getBoolean(KEY_PARENTAL_ON, false)
        set(v) = sp.edit().putBoolean(KEY_PARENTAL_ON, v).apply()

    /** 家长 PIN（4 位数字）。空串表示还没设置过。 */
    var parentalPin: String
        get() = sp.getString(KEY_PARENTAL_PIN, "").orEmpty()
        set(v) = sp.edit().putString(KEY_PARENTAL_PIN, v).apply()

    /** 家长手工黑名单（逗号分隔的片名/关键词）。 */
    var parentalBlockedWords: String
        get() = sp.getString(KEY_PARENTAL_BLOCK, "").orEmpty()
        set(v) = sp.edit().putString(KEY_PARENTAL_BLOCK, v).apply()

    /** 家长手工白名单（逗号分隔）。 */
    var parentalAllowedWords: String
        get() = sp.getString(KEY_PARENTAL_ALLOW, "").orEmpty()
        set(v) = sp.edit().putString(KEY_PARENTAL_ALLOW, v).apply()

    // ==================== 首页海报墙缓存 ====================

    /**
     * 上次抓到的一页影视（精简字段），用于首页海报墙**秒开**。
     *
     * 海报图片本身由 Coil 的磁盘缓存负责，所以只要片单在，
     * 进首页就能立刻把海报渲染出来，不用等接口。
     */
    var homeMovieCache: String
        get() = sp.getString(KEY_HOME_CACHE, "").orEmpty()
        set(v) = sp.edit().putString(KEY_HOME_CACHE, v).apply()

    /** 导出所有设置（手机网页调试页读取用）。 */
    fun snapshot(): Map<String, String> = mapOf(
        KEY_DOMAIN to domain,
        KEY_LIVE_SRC to liveSourceUrl,
        KEY_UA to userAgent,
        KEY_AUTO_PICK to autoPickDomain.toString(),
        KEY_AUTO_DOMAIN to autoDomain.toString(),
        KEY_WEB_FALLBACK to webFallbackEnabled.toString(),
        KEY_DDYS_PAGE_SIZE to ddysPageSize.toString(),
        KEY_AUTO_UPDATE to autoCheckUpdate.toString(),
        KEY_AUTO_LAST to autoPlayLastChannel.toString(),
        KEY_HW_DECODE to hardwareDecode.toString(),
        KEY_SHOW_HUD to showChannelHud.toString(),
        KEY_TOKEN to debugToken,
    )

    /** 供手机网页调试模式批量写入。 */
    fun applyRemote(map: Map<String, String>) {
        val e = sp.edit()
        map[KEY_DOMAIN]?.let { e.putString(KEY_DOMAIN, it) }
        map[KEY_LIVE_SRC]?.let { e.putString(KEY_LIVE_SRC, it) }
        map[KEY_UA]?.let { e.putString(KEY_UA, it) }
        map[KEY_AUTO_PICK]?.let { e.putBoolean(KEY_AUTO_PICK, it.toBoolean()) }
        map[KEY_AUTO_DOMAIN]?.let { e.putBoolean(KEY_AUTO_DOMAIN, it.toBoolean()) }
        map[KEY_DDYS_PAGE_SIZE]?.toIntOrNull()?.let { e.putInt(KEY_DDYS_PAGE_SIZE, it.coerceIn(12, 50)) }
        map[KEY_WEB_FALLBACK]?.let { e.putBoolean(KEY_WEB_FALLBACK, it.toBoolean()) }
        map[KEY_AUTO_UPDATE]?.let { e.putBoolean(KEY_AUTO_UPDATE, it.toBoolean()) }
        map[KEY_AUTO_LAST]?.let { e.putBoolean(KEY_AUTO_LAST, it.toBoolean()) }
        map[KEY_HW_DECODE]?.let { e.putBoolean(KEY_HW_DECODE, it.toBoolean()) }
        map[KEY_SHOW_HUD]?.let { e.putBoolean(KEY_SHOW_HUD, it.toBoolean()) }
        map[KEY_TOKEN]?.let { e.putString(KEY_TOKEN, it) }
        e.apply()
        touch()
    }

    companion object {
        /** 低端影视官方永久域名（要科学上网才能直连） */
        const val DEFAULT_DOMAIN = "ddys.io"

        /** 官方镜像域名，按顺序探测（首页脚注里公布的三个备用域名 + 主域名） */
        val DOMAIN_CANDIDATES = listOf(
            "ddys.io",
            "ddys.pics",
            "ddys.live",
            "ddys.help",
        )
        private const val KEY_DOMAIN = "domain"
        private const val KEY_AUTO_PICK = "auto_pick_domain"
        private const val KEY_RESOLVED_DOMAIN = "resolved_domain"
        private const val KEY_DDYS_PAGE_SIZE = "ddys_page_size"
        private const val KEY_WEB_FALLBACK = "web_fallback"
        private const val KEY_UA = "user_agent"
        private const val KEY_AUTO_DOMAIN = "auto_domain"
        private const val KEY_LIVE_SRC = "live_source"
        private const val KEY_AUTO_LAST = "auto_last_channel"
        private const val KEY_LAST_CHANNEL = "last_channel"
        private const val KEY_FAV_CH = "fav_channels"
        private const val KEY_PORT = "debug_port"
        private const val KEY_DEBUG_ON = "debug_enabled"
        private const val KEY_TOKEN = "debug_token"
        private const val KEY_AUTO_UPDATE = "auto_update"
        private const val KEY_SKIP_VERSION = "skip_version"
        private const val KEY_VOLUME = "live_volume"
        private const val KEY_SOURCE_MEMORY = "source_memory"
        private const val KEY_WATCH_HISTORY = "watch_history"
        private const val KEY_RECENT_LIVE = "recent_live"
        private const val KEY_PARENTAL_ON = "parental_on"
        private const val KEY_PARENTAL_PIN = "parental_pin"
        private const val KEY_PARENTAL_BLOCK = "parental_block"
        private const val KEY_PARENTAL_ALLOW = "parental_allow"
        private const val KEY_HOME_CACHE = "home_movie_cache"
        private const val KEY_HW_DECODE = "hw_decode"
        private const val KEY_SHOW_HUD = "show_hud"
    }
}
