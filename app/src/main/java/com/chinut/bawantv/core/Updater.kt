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
    suspend fun check(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
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
            return@withContext null
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

    /** 调系统安装器。 */
    fun install(context: Context, file: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        }
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
