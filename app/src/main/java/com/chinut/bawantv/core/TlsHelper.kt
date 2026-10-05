package com.chinut.bawantv.core

import android.content.Context
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/**
 * 给**特定的老设备**补 TLS 信任锚。
 *
 * # 为什么需要它
 *
 * 用户电视是 **Android 6.0.1（API 23）**，短剧接口一直刷不出来，抓到的错误是：
 *
 *     SSLHandshakeException:
 *       CertPathValidatorException: Trust anchor for certification path not found.
 *
 * 因为接口证书由 **Let's Encrypt 的 `YR1` 中间证书**签发，而 Android 6 的
 * 系统根证书库里没有对应的 CA。
 *
 * # 为什么不用 network_security_config 的 `@raw`
 *
 * 我先试了在 `res/xml/network_security_config.xml` 里写：
 *
 *     <certificates src="@raw/isrg_root_x1" />
 *
 * 这在**新设备上有效，但在 Android 6 上没生效** —— 实测灌了新包之后
 * 依然是同一个证书错误（老系统对这种引用形式的支持不完整）。
 *
 * 所以改成**代码里显式构造信任锚**：把根证书读进 KeyStore、
 * 建 TrustManagerFactory、用它初始化 SSLContext。
 * 这条路 Android 4+ 行为一致，不依赖系统对配置文件的解析。
 *
 * # 安全边界
 *
 * · **不是"信任所有证书"** —— 仍然完整校验证书链与主机名，
 *   只是把"信任谁"从系统库换成"系统库 + 内置的两个 ISRG 根"
 * · 两个根取自 Let's Encrypt 官方发布（letsencrypt.org/certs/）
 * · **只对短剧接口那一个域名生效**，不是全局放宽
 */
object TlsHelper {

    private const val TAG = "BawanTls"

    /**
     * 需要补锚的域名。只对它们生效，别的一律走系统默认。
     *
     * # 为什么把 GitHub 相关域名也加进来
     *
     * 用户两台电视，同一份 App、同一个网络：
     *
     * | 电视 | 系统 | GitHub 订阅源 |
     * |---|---|---|
     * | 192.168.31.101 | Android 11 | ✅ 正常（141 个频道）|
     * | 192.168.31.233 | **Android 6.0.1** | ❌ 全部失败 |
     *
     * 用户一句话点破了方向：「一个电视好着呢，一个不行说明不是网的问题」。
     *
     * 打开异常日志后拿到真实原因（之前 `getOrNull()` 把异常吞了）：
     *
     *     SSLHandshakeException: java.security.cert.CertPathValidatorException:
     *       Trust anchor for certification path not found.
     *
     * 实测这几个源**全是 Let's Encrypt 签的**：
     *
     *     iptv-org.github.io         → Let's Encrypt
     *     raw.githubusercontent.com  → Let's Encrypt
     *
     * 而 **Android 6 的系统证书库里没有 ISRG Root X1** ——
     * Let's Encrypt 的根原先靠 DST Root CA X3 交叉签名，2021-09 那张过期后，
     * 老系统（Android < 7.1.1）就再也验不过 Let's Encrypt 的新证书了。
     *
     * 这个项目**早就为同一个问题写过解法**（短剧接口 xiaoqi.icofun.cn 也是
     * Let's Encrypt），内置了 `isrg_root_x1.pem` / `isrg_root_x2.pem`。
     * 当时只对短剧域名生效 —— 现在把订阅源域名也纳入，复用同一套补锚。
     *
     * ⚠️ 注意 `live.fanmingming.com` 也要加：它同样是 HTTPS 源，
     * 在老系统上会遇到一样的问题。
     */
    private val HOSTS = setOf(
        // 短剧接口（原本就有的）
        "xiaoqi.icofun.cn",
        "icofun.cn",
        // GitHub 订阅源：best-fan / iptv-org / vbskycn
        "raw.githubusercontent.com",
        "githubusercontent.com",
        "github.io",
        "github.com",
        // 其他 HTTPS 订阅源
        "live.fanmingming.com",
    )

    @Volatile
    private var cached: OkHttpClient? = null

    /** ZIP 里内置的两个 Let's Encrypt 根证书。 */
    private fun rootRes(): List<Int> = listOf(
        com.chinut.bawantv.R.raw.isrg_root_x1,
        com.chinut.bawantv.R.raw.isrg_root_x2,
    )

    /** 这个 URL 是否属于需要补锚的域名。 */
    fun needsExtraAnchors(url: String): Boolean =
        runCatching { java.net.URI(url).host }.getOrNull()
            ?.let { h -> HOSTS.any { h == it || h.endsWith(".$it") } } == true

    /**
     * 构造补过锚的客户端。
     *
     * 把「系统默认信任的 CA」与「内置的两个 ISRG 根」**合并**成一个
     * KeyStore，再建 TrustManager：
     *
     * · 内置根能验过的（我们的接口）→ 通过
     * · 系统默认能验过的（其它站点）→ 照样通过
     * · 两者都验不过的（真的坏人）→ **仍然拒绝**
     */
    private fun buildClient(context: Context): OkHttpClient? = runCatching {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
        var idx = 0

        // 1) 先灌系统默认的 CA（保持对其它站点的信任不变）
        val sysTmf = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm(),
        ).apply { init(null as KeyStore?) }
        sysTmf.trustManagers.filterIsInstance<X509TrustManager>().forEach { tm ->
            runCatching {
                tm.acceptedIssuers.forEach { ks.setCertificateEntry("sys${idx++}", it) }
            }
        }

        // 2) 再灌内置的 ISRG 根
        val cf = CertificateFactory.getInstance("X.509")
        var added = 0
        rootRes().forEach { res ->
            runCatching {
                context.resources.openRawResource(res).use { ins ->
                    cf.generateCertificates(ins).forEach { c: java.security.cert.Certificate ->
                        ks.setCertificateEntry("isrg${idx++}", c)
                        added++
                    }
                }
            }
        }

        val tmf = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm(),
        ).apply { init(ks) }
        val tm = tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            ?: return@runCatching null

        val ctx = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(tm), SecureRandom())
        }
        android.util.Log.i(TAG, "TLS 补锚完成：系统 CA + $added 个内置 ISRG 根")

        Http.client.newBuilder()
            .sslSocketFactory(ctx.socketFactory, tm)
            .build()
    }.onFailure {
        android.util.Log.w(TAG, "TLS 补锚失败：${it.message}")
    }.getOrNull()

    /**
     * 取"对这个 URL 合适"的客户端。
     *
     * 域名在 [HOSTS] 里 → 用补过锚的；否则用全局默认的。
     * 补锚失败就退回默认客户端，**不影响其它功能**。
     *
     * ⚠️ `cached` 只缓存**补锚过的那个客户端**。
     * 原来这里写的是 `cached?.let { return it }` —— 但 cached 里存的
     * 可能是"默认客户端"（当第一个调用的域名不在 [HOSTS] 里时），
     * 于是后面所有域名都会拿到默认客户端，**补锚永远不生效**。
     * 改成只缓存补锚结果，默认分支直接返回 [Http.client]。
     */
    fun clientFor(context: Context, url: String): OkHttpClient {
        if (!needsExtraAnchors(url)) return Http.client
        cached?.let { return it }
        return synchronized(this) {
            cached ?: (buildClient(context) ?: Http.client).also { cached = it }
        }
    }
}
