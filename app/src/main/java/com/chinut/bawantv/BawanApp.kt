package com.chinut.bawantv

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import coil.disk.DiskCache
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import com.chinut.bawantv.core.AppPrefs
import com.chinut.bawantv.core.Http
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * 全局 Application。
 *
 * - 单例持有 [AppPrefs] / OkHttp 客户端
 * - 记录开屏是否已经展示（旋转、回前台不重复播放）
 * - 给 Coil 提供统一带 Referer 的图片加载器（低端影视的海报有防盗链）
 */
class BawanApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        prefs = AppPrefs(this)
        Http.init(this)

        // ---------- 启动局域网服务 ----------
        //
        // 这个服务同时提供两件事：
        //  1. 手机网页调试（扫码改设置）
        //  2. **网络遥控 API**（手机 App 当遥控器用，见 core/RemoteBus.kt）
        //
        // 之前 `DebugWebServer` 定义了却**没有任何地方调用 start()** ——
        // 也就是说这个功能一直是死的，设置页那句"服务运行中"是假的。
        // 现在在 Application 启动时按设置拉起。
        //
        // 放在 Application 而不是 Activity：遥控要在**任何界面**都能用，
        // 而且应用退到后台时服务不该跟着断（用户在手机上操作，
        // 电视只是待机在客厅）。
        if (prefs.debugEnabled) {
            runCatching {
                com.chinut.bawantv.core.DebugWebServer.start(this, prefs.debugPort)
            }
        }
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(
                OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .addInterceptor { chain ->
                        val req = chain.request()
                        val host = req.url.host
                        val b = req.newBuilder()
                        if (b.build().header("User-Agent") == null) {
                            b.header("User-Agent", Http.UA_DESKTOP)
                        }
                        // 低端影视的图床校验 Referer
                        if (host.contains("ddys") || host.contains("ddyscdn")) {
                            b.header("Referer", "https://${prefs.domain}/")
                        }
                        chain.proceed(b.build())
                    }
                    .build()
            )
            .crossfade(true)
            .memoryCache {
                MemoryCache.Builder(this).maxSizePercent(0.2).build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(200L * 1024 * 1024)
                    .build()
            }
            .build()

    companion object {
        @Volatile
        lateinit var instance: BawanApp
            private set

        @Volatile
        lateinit var prefs: AppPrefs
            private set

        /** 开屏动画是否已经播放过（本次进程内只播一次）。 */
        @Volatile
        var splashShown: Boolean = false

        /**
         * 开屏/图标使用的 Logo 资源 id（图形单图）。
         *
         * 由编译期决定：放了对应资源就返回 id，否则返回 0，
         * 界面退回文字版，保证任何情况下都有内容、不会崩。
         */
        val logoRes: Int = resId("logo_yanhuo")

        /** 横版开屏主视觉（含 slogan「焰火随想 美好随现」）。 */
        val splashBrandRes: Int = resId("splash_brand")

        private fun resId(name: String): Int = runCatching {
            Class.forName("$PACKAGE.R\$drawable")
                .getField(name)
                .getInt(null)
        }.getOrDefault(0)

        private const val PACKAGE = "com.chinut.bawantv"

        fun ctx(): Context = instance.applicationContext
    }
}
