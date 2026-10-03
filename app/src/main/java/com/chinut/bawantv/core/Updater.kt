package com.chinut.bawantv.core

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.chinut.bawantv.BuildConfig
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/** 一次可用的更新。 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val apkSources: List<ApkSource>,
    val apkSize: Long = 0L,
    val releasePage: String = "",
) {
    val sizeText: String
        get() = when {
            apkSize <= 0 -> ""
            apkSize > 1024 * 1024 -> "%.1f MB".format(apkSize / 1024f / 1024f)
            else -> "${apkSize / 1024} KB"
        }
}

data class ApkSource(val name: String, val url: String, val priority: Int)

/** 下载状态。 */
sealed interface UpdateState {
    data object Idle : UpdateState
    data class Checking(val message: String = "正在检查更新…") : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val progress: Float, val from: String) : UpdateState
    data class Ready(val file: File, val info: UpdateInfo) : UpdateState
    data class Failed(val message: String) : UpdateState
    data object UpToDate : UpdateState
}

/**
 * 自动更新（发布在 Gitee + GitHub 两个仓库，国内优先 Gitee）。
 *
 * 版本号从 Release 说明里按 `versionCode: N` 解析；APK 取 Release 附件。
 * 下载完成后调系统安装器安装（TV 端通用做法）。
 */
object Updater {

    // ==================== 仓库配置 ====================
    //
    // ⚠️ 仓库名必须和实际发布的仓库一致，否则检查更新永远 404。
    // 这里曾经写成 "bawan-tv"（项目早期的名字），而实际仓库是 "yanhuo-tv"，
    // 结果两个源都返回 404 —— 界面一直提示"两个更新源都不可达"。
    private const val TAG_INSTALL = "BawanInstall"

    private const val GITEE_OWNER = "chinut"
    private const val GITEE_REPO = "yanhuo-tv"

    private const val GITHUB_OWNER = "chinut"
    private const val GITHUB_REPO = "yanhuo-tv"

    private data class Source(
        val name: String,
        val api: String,
        val priority: Int,
        val accept: String,
        val ua: String,
    )

    private val sources = listOf(
        Source(
            "Gitee",
            "https://gitee.com/api/v5/repos/$GITEE_OWNER/$GITEE_REPO/releases/latest",
            0, "application/json", "bawanTV",
        ),
        Source(
            "GitHub",
            "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest",
            1, "application/vnd.github+json", "bawanTV",
        ),
    )

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** 当前 App 的 versionCode。 */
    fun currentVersionCode(context: Context): Int = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            pi.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            pi.versionCode
        }
    }.getOrDefault(BuildConfig.VERSION_CODE)

    /** 检查更新。返回可用的更新信息，没有则 null。 */
    suspend fun check(context: Context, force: Boolean = false): UpdateInfo? = withContext(Dispatchers.IO) {
        _state.value = UpdateState.Checking()
        val current = currentVersionCode(context)

        // 探测每个源：query 返回 null 表示网络/HTTP 失败；
        // 返回 Raw 但 error 非空表示"连上了但读不出有效版本"。
        val probed = coroutineScope {
            sources.map { src -> async(Dispatchers.IO) { src to query(src) } }.awaitAll()
        }

        val reachable = probed.mapNotNull { (_, r) -> r }
        val usable = reachable.filter { it.error.isEmpty() && it.versionCode > 0 }

        // ---------- 一个有效源都没有：把原因说清楚 ----------
        //
        // 这里必须区分三种情况，否则用户只会看到一句笼统的"不可达"，
        // 完全无从下手（这正是之前"两个更新源都不可达"最让人困惑的地方）：
        //   · 网络不通 / 站点访问不了
        //   · 连上了，但 Release 正文没写 versionCode（发布方的问题）
        //   · 连上了、也有版本号，但版本号不比自己新
        if (usable.isEmpty()) {
            val reason = when {
                reachable.isEmpty() ->
                    "两个更新源都连不上（检查网络，或稍后重试）"

                reachable.all { it.error.isNotEmpty() } ->
                    "更新源可达，但 ${reachable.first().error}" +
                        "（这是发布方的配置问题，不是你的网络）"

                else -> "检查更新失败"
            }
            android.util.Log.w(
                "BawanUpdater",
                "检查更新失败：$reason；各源=" +
                    probed.joinToString { (s, r) ->
                        "${s.name}=" + (r?.error?.ifEmpty { "ok(code=${r.versionCode})" } ?: "unreachable")
                    },
            )
            _state.value = UpdateState.Failed(reason)
            return@withContext null
        }

        val newer = usable.filter { it.versionCode > current }
        if (newer.isEmpty()) {
            _state.value = UpdateState.UpToDate
            // force：即使没有更新的版本，也把"当前最新 Release"交出去。
            // 设置页的「重新下载并覆盖安装」需要它 —— 否则已是最新时拿不到
            // 任何下载地址，那个按钮就成了摆设。
            if (!force) return@withContext null
            val newest = usable.maxByOrNull { it.versionCode } ?: return@withContext null
            val forcedSources = usable.filter { it.apkUrl.isNotBlank() }
                .sortedBy { it.priority }
                .map { ApkSource(it.sourceName, it.apkUrl, it.priority) }
            if (forcedSources.isEmpty()) {
                _state.value = UpdateState.Failed("Release 里没有 APK 附件，无法下载")
                return@withContext null
            }
            return@withContext UpdateInfo(
                versionCode = newest.versionCode,
                versionName = newest.versionName,
                notes = newest.notes,
                apkSources = forcedSources,
                apkSize = newest.apkSize,
                releasePage = newest.releasePage,
            )
        }

        val latest = newer.maxByOrNull { it.versionCode }!!
        val apkSources = newer.filter { it.apkUrl.isNotBlank() }
            .sortedBy { it.priority }
            .map { ApkSource(it.sourceName, it.apkUrl, it.priority) }
        if (apkSources.isEmpty()) {
            _state.value = UpdateState.Failed("找到了新版本，但 Release 里没有 APK 附件")
            return@withContext null
        }

        val info = UpdateInfo(
            versionCode = latest.versionCode,
            versionName = latest.versionName,
            notes = latest.notes,
            apkSources = apkSources,
            apkSize = latest.apkSize,
            releasePage = latest.releasePage,
        )
        _state.value = UpdateState.Available(info)
        info
    }

    /** 下载 APK（自动按优先级换源）。 */
    suspend fun download(context: Context, info: UpdateInfo): File? = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "updates").apply { mkdirs() }
        val apk = File(dir, "bawanTV-${info.versionCode}.apk")
        if (apk.exists() && info.apkSize > 0 && apk.length() == info.apkSize) {
            _state.value = UpdateState.Ready(apk, info)
            return@withContext apk
        }

        for (src in info.apkSources.sortedBy { it.priority }) {
            _state.value = UpdateState.Downloading(0f, src.name)
            val ok = downloadTo(src.url, apk, info.apkSize) { p ->
                _state.value = UpdateState.Downloading(p, src.name)
            }
            if (ok) {
                _state.value = UpdateState.Ready(apk, info)
                return@withContext apk
            }
            if (apk.exists()) apk.delete()
        }
        _state.value = UpdateState.Failed("下载失败，请稍后重试或到 Release 页面手动下载")
        null
    }

    private fun downloadTo(
        url: String,
        dest: File,
        expected: Long,
        onProgress: (Float) -> Unit,
    ): Boolean = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "bawanTV")
            .header("Accept", "*/*")
            .build()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return false
            val body = resp.body ?: return false
            val total = body.contentLength().let { if (it > 0) it else expected }
            var done = 0L
            body.byteStream().use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                    out.flush()
                }
            }
        }
        dest.exists() && (expected <= 0 || dest.length() >= expected * 9 / 10)
    }.getOrDefault(false)

    /**
     * 本应用是否被允许安装未知来源应用（Android 8.0+ 才有这个概念）。
     *
     * Android 8.0 起装 APK 需要用户**对每个应用单独授权**，没授权时
     * 系统会直接把安装拦掉。而各家电视把这个开关藏得很深，用户基本找不到 ——
     * 所以我们要主动引导，而不是让"点安装没反应"。
     */
    fun canInstallPackages(context: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** 跳到"允许安装未知应用"的授权页。 */
    fun openInstallPermissionSettings(context: Context) {
        runCatching {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                android.net.Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.onFailure {
            // 个别定制系统没有这个页面，退到应用详情页
            runCatching {
                context.startActivity(
                    Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    /**
     * 调系统安装器。
     *
     * **不再静默吞异常**：原来整个包在 `runCatching{}` 里，失败时用户和开发者
     * 都看不到任何东西 —— 表现就是"点了下载并安装，然后什么都没发生"，
     * 完全无从判断是没下载完、没权限、还是安装器没起来。
     * 现在把失败原因写进 [state]，界面上会显示出来。
     */
    fun install(context: Context, file: File) {
        if (!canInstallPackages(context)) {
            android.util.Log.w(TAG_INSTALL, "没有安装未知应用的权限，跳去授权页")
            _state.value = UpdateState.Failed("需要先允许「安装未知应用」，已在设置里打开对应开关")
            openInstallPermissionSettings(context)
            return
        }

        val apkMime = "application/vnd.android.package-archive"

        // ---------- 逐级尝试，第一个能起来的就用它 ----------
        //
        // 为什么要多级：**各家的电视系统限制不一样**。
        //
        // 实测小米电视（MIUI TV）报：
        //     ActivityNotFoundException No Activity found to handle Intent
        //     {act=android.intent.action.VIEW dat=content://... typ=application/vnd.android.package-archive}
        // 也就是说它**不响应"用 ACTION_VIEW 打开 APK"**（安全限制）。
        //
        // 而"只报一句失败"对用户毫无帮助（老版本甚至静默无反应）。
        // 所以这里每一级都先 resolveActivity 探测，能起来的才 startActivity，
        // 全都不行就把**文件路径**告诉用户，让他用文件管理器装。
        val attempts = mutableListOf<Pair<String, Intent>>()

        // 1) 标准做法：content:// + FileProvider（Android 7+ 的正路，多数电视可用）
        runCatching {
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file,
            )
            attempts += "FileProvider" to Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, apkMime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        // 2) 显式点名系统安装器。部分定制系统对 ACTION_VIEW 的隐式匹配做了限制，
        //    但显式指定组件仍然放行。
        //
        //    ⚠️ 只有拿到 content:// 才加这一级。
        //    之前写成 `?: return@apply`，FileProvider 失败时会产出一个
        //    **没有 data 的空 ACTION_VIEW Intent** —— 那种 Intent 可能被系统
        //    解析到完全无关的东西上，反而添乱。
        val contentUri = runCatching {
            FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file,
            )
        }.getOrNull()
        if (contentUri != null) {
            for (pkg in listOf("com.android.packageinstaller", "com.google.android.packageinstaller")) {
                attempts += "显式:$pkg" to Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(contentUri, apkMime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    setPackage(pkg)
                }
            }
        }

        // 3) 把 APK 拷到外部私有目录，用 file:// 交给安装器。
        //    这条是给"FileProvider 那条路被系统拦掉"的老机器兜底的。
        runCatching {
            @Suppress("DEPRECATION")
            val ext = context.getExternalFilesDir(null) ?: context.filesDir
            val pub = File(ext, file.name)
            if (!pub.exists() || pub.length() != file.length()) file.copyTo(pub, overwrite = true)
            attempts += "file://" to Intent(Intent.ACTION_VIEW).apply {
                @Suppress("DEPRECATION")
                setDataAndType(android.net.Uri.fromFile(pub), apkMime)
            }
        }

        for ((label, intent) in attempts) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val resolved = runCatching { context.packageManager.resolveActivity(intent, 0) }
                .getOrNull()
            if (resolved == null) {
                android.util.Log.i(TAG_INSTALL, "$label 无可用组件，试下一级")
                continue
            }
            val ok = runCatching { context.startActivity(intent) }.isSuccess
            if (ok) {
                android.util.Log.i(TAG_INSTALL, "已用 $label 调起安装器")
                return
            }
            android.util.Log.w(TAG_INSTALL, "$label 启动失败，试下一级")
        }

        // ---------- 全都不行：给出可操作的出路 ----------
        //
        // 到这一步说明这台电视**彻底不允许第三方调起安装器**。
        // 与其显示一句"失败"，不如把安装包位置和手动安装方法告诉用户。
        android.util.Log.e(TAG_INSTALL, "所有安装方式都不可用，APK 在 ${file.absolutePath}")
        _state.value = UpdateState.Failed(
            "这台电视不允许应用自己调起安装界面。\n\n" +
                "安装包已下载到：\n${file.absolutePath}\n\n" +
                "可以：① 用「电视的文件管理器」找到它打开安装；" +
                "② 或在电脑上用 adb install 安装。",
        )
    }

    fun reset() {
        _state.value = UpdateState.Idle
    }

    // ==================== 查询 ====================

    private data class Raw(
        val sourceName: String,
        val priority: Int,
        val versionCode: Int,
        val versionName: String,
        val notes: String,
        val apkUrl: String,
        val apkSize: Long,
        val releasePage: String,
        /** 非空表示这个源有问题（拿不到版本号 / 网络失败），用于给用户可读的提示。 */
        val error: String = "",
    )

    private fun query(src: Source): Raw? = runCatching {
        val req = Request.Builder()
            .url(src.api)
            .header("User-Agent", src.ua)
            .header("Accept", src.accept)
            .build()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val text = resp.body?.string() ?: return null
            val json = JSONObject(text)
            val tag = json.optString("tag_name")
            val notes = json.optString("body")
            val page = json.optString("html_url")

            // ---------- 版本号解析 ----------
            //
            // 只认**正文里显式写的 versionCode**。
            //
            // 之前有一条"读不到就从 tag 抠数字"的兜底，那是**错的**：
            // tag 是 v1.0.22，抠出来是 "1022"，而真实 versionCode 是 23 ——
            // 差两个数量级。一旦正文漏写，就会算出一个离谱的版本号，
            // 导致"永远提示可升级"或"永远提示已最新"，而且完全看不出原因。
            //
            // 现在：读不到就**明确放弃这个源**，并把原因记下来。
            val parsed = parseVersionCode(notes)
            if (parsed == null) {
                val detail = "Release 正文里没有 versionCode"
                android.util.Log.w("BawanUpdater", "${src.name}：$detail（tag=$tag）")
                return Raw(
                    sourceName = src.name, priority = src.priority,
                    versionCode = 0, versionName = "", notes = "",
                    apkUrl = "", apkSize = 0L, releasePage = page,
                    error = detail,
                )
            }

            var apkUrl = ""
            var apkSize = 0L
            json.optJSONArray("assets")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val a = arr.optJSONObject(i) ?: continue
                    val name = a.optString("name")
                    if (name.endsWith(".apk", true)) {
                        apkUrl = a.optString("browser_download_url")
                        apkSize = a.optLong("size")
                        break
                    }
                }
            }
            Raw(
                sourceName = src.name,
                priority = src.priority,
                versionCode = parsed,
                versionName = tag.removePrefix("v").ifBlank { "新版本" },
                notes = notes,
                apkUrl = apkUrl,
                apkSize = apkSize,
                releasePage = page,
                error = "",
            )
        }
    }.getOrNull()

    /**
     * 从 Release 正文里取出 versionCode。
     *
     * 容忍几种常见写法（发布时手抖写出哪种都不至于失效）：
     *
     *     versionCode: 23          ← 推荐写法
     *     versionCode = 23
     *     versionCode 23
     *     **versionCode**: 23      ← markdown 加粗
     *     `versionCode`: 23        ← markdown 行内代码
     *     - versionCode: 23        ← 列表项
     *
     * **不做"从 tag 抠数字"的兜底** —— 那个兜底算出来的值必然是错的
     * （v1.0.22 → 1022），比"没有版本号"更糟：它会静默地给出错误结论。
     *
     * @return 解析出的 versionCode；正文里确实没有则返回 null
     */
    private fun parseVersionCode(notes: String): Int? {
        if (notes.isBlank()) return null
        val re = Regex(
            """version\s*code\s*[*`]*\s*[:=]?\s*(\d{1,9})""",
            RegexOption.IGNORE_CASE,
        )
        return re.find(notes)?.groupValues?.get(1)?.toIntOrNull()
    }
}
