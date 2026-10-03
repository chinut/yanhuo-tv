package com.chinut.bawantv.live

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.chinut.bawantv.core.Http
import java.io.ByteArrayInputStream

/**
 * 电视台网页播放用的 WebView（移植音乐库项目的做法）。
 *
 * 为什么需要它：央视与多数省台的直播流是**加密专有格式**，只在它们自己的网页播放器里能解，
 * 直接把地址交给 ExoPlayer 会报 `PARSING_MANIFEST_MALFORMED`。
 * 所以这一类频道改成「用浏览器引擎打开电视台的直播页，让网页自己播」，原生这边负责：
 *
 * 1. 按站点补 `Referer` / `User-Agent`（很多站点的流做了防盗链，UA 或 Referer 不对就 403）；
 * 2. 播放页加载完后注入脚本，**把页面里除视频外的元素全部隐藏**，让视频铺满整屏 ——
 *    这样用户在电视上看到的就是「一个干净的台」，而不是一个带导航栏的网站；
 * 3. 自动尝试静音自动播放（浏览器策略要求静音才能自动播），随后恢复音量；
 * 4. 把控制台错误转发到 logcat，方便排查某个台为什么放不出来。
 */
class TvWebPlayerView(context: Context) : WebView(context) {

    /** 是否已经成功找到并放大了视频元素。 */
    @Volatile
    var videoFound: Boolean = false
        private set

    /**
     * 网页里实际使用的**媒体流地址**（第一个被拦截到的 m3u8/mp4/ts）。
     *
     * 网页播放器在电视上往往很卡（网页本身重，还和视频解码抢 CPU）。
     * 拿到这个地址就能改用 ExoPlayer 硬件解码，流畅度完全不同。
     */
    @Volatile
    var foundStreamUrl: String? = null
        private set

    /**
     * 捕获到媒体流时的回调。
     *
     * 上层收到后可以决定"切到原生播放器"（见 LivePlayerScreen 的处理）。
     */
    var onStreamFound: ((String) -> Unit)? = null

    /**
     * **真的出画面了**时的回调（预加载用）。
     *
     * 直播页用它做"后台先加载、加载好了再露出来"：
     * 在收到这个回调之前 WebView 是 `INVISIBLE` 的，用户只看到转圈；
     * 收到之后才显示出来，避免让他盯着央视频的加载占位图发呆
     * （真机反馈：老电视上"大部分时间都在看那张加载图"）。
     */
    var onFirstFrame: (() -> Unit)? = null

    /** 是否已经出过画面。 */
    var hasFirstFrame: Boolean = false
        private set

    /** 视频是否已经真的在播（由注入脚本回报）。 */
    private var videoPlaying = false

    /**
     * JavaScript 桥：注入脚本检测到 `<video>` 真的在播时调这里。
     *
     * 这是"出画面"最可靠的判据 —— 比"页面加载完成"准得多：
     * 页面加载完不等于播放器起来了（那正是真机上卡住的原因）。
     */
    private inner class Bridge {
        @android.webkit.JavascriptInterface
        fun onPlaying() {
            post {
                videoPlaying = true
                if (!hasFirstFrame) {
                    hasFirstFrame = true
                    android.util.Log.i(TAG, "网页播放器已出画面，可以让用户看了")
                    onFirstFrame?.invoke()
                }
            }
        }
    }

    private val bridge = Bridge()

    private var pageLoaded = false

    init {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        setBackgroundColor(AndroidColor.BLACK)
        @SuppressLint("SetJavaScriptEnabled")
        settings.apply {
            javaScriptEnabled = true
            // 桥：注入脚本检测到 video 真的在播时会回调 onPlaying()，
            // 用来实现“后台加载好再露出来”（见 onFirstFrame）。
            runCatching { addJavascriptInterface(bridge, "BawanBridge") }
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadsImagesAutomatically = true
            // ---------- 缩放设置：**不要动** ----------
            //
            // 我在这里踩过两次坑，都直接毁掉了画面，所以留个警告：
            //
            //   1. 把这两个开关关掉（本意是"按设备宽度铺满"）
            //      → 画面被**横向拉伸成椭圆**。播放页是靠它们配合自己的
            //        viewport meta 来定尺寸的，关掉之后它算出的宽高就错了。
            //   2. 把视频元素的宽高从百分比改成 100vw/100vh
            //      → 变成**黑屏**。
            //
            // 结论：这两个开关配桌面 UA 是**能正常铺满**的（实测 1080p 全屏无黑边）。
            // 黑边问题要到**播放器**那一层解决（见 LivePlayerScreen 里
            // PlayerView 的 RESIZE_MODE_FILL），不要去改页面的排版方式。
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            // 桌面 UA：移动 UA 会被央视频等站点拦成「请前往客户端」
            userAgentString = Http.UA_DESKTOP
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }
        isFocusable = false
        isFocusableInTouchMode = false
        keepScreenOn = true

        webViewClient = object : WebViewClient() {
            /**
             * 按站点给 CORS 请求补 `Origin` / `Referer`。
             *
             * 实测：央视页面的播放器能解析出真实 m3u8，但 CDN 会以
             * `No 'Access-Control-Allow-Origin'` 拒绝 —— 因为 WebView 的页面源
             * 与 CDN 不同源，而 CDN 只对「可信来源」返回 CORS 头。
             * 这里在原生层把 Origin/Referer 改成页面自己的源，能救回相当一部分站点。
             */
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                val raw = request?.url?.toString() ?: return null

                // ---------- 从请求里认出"真正的媒体流地址" ----------
                //
                // 这里踩过一个很隐蔽的坑，直接导致"能起播、几秒后黑屏"：
                //
                // 央视的播放页会往统计接口发一个请求，形如
                //   https://p.data.cctv.com/play.1.3?gmkey=...&streamUrl=https%3A%2F%2F...index.m3u8%3F...
                // 它的 **path 根本不是媒体文件**（是 /play.1.3），但 query 里带了
                // "m3u8" 字样，于是旧代码的 `url.contains("m3u8")` 命中了，
                // 被当成流地址交给 ExoPlayer —— 当然播不出任何画面。
                //
                // 真正的流地址就藏在 `streamUrl=` 参数里（本身还是 URL 编码的）。
                // 所以顺序是：先尝试解出内嵌的 streamUrl，解不到再看 path。
                val url = extractEmbeddedStreamUrl(raw) ?: raw
                if (!looksLikeMediaUrl(url)) {
                    return null
                }

                // ---------- 记下真实的流地址 ----------
                //
                // 网页播放器的性能在电视上往往是瓶颈（网页本身很重，
                // 还要和视频解码抢 CPU）。这里顺手把**媒体流的真实地址**记下来，
                // 上层就能改用 ExoPlayer 走硬件解码播放 —— 流畅度完全不是一个量级。
                //
                // 只在第一次命中时回调（avoid 每个 .ts 分片都触发一次）。
                if (foundStreamUrl == null) {
                    foundStreamUrl = url
                    android.util.Log.i(TAG, "捕获到媒体流：$url")
                    onStreamFound?.let { cb ->
                        post { runCatching { cb(url) } }
                    }
                }

                val headers = LiveCatalog.headersFor(url)
                val referer = headers["Referer"] ?: return null
                val origin = runCatching {
                    val u = java.net.URI(referer)
                    "${u.scheme}://${u.host}"
                }.getOrDefault("")
                return try {
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.setRequestProperty("Referer", referer)
                    if (origin.isNotEmpty()) conn.setRequestProperty("Origin", origin)
                    conn.setRequestProperty("User-Agent", headers["User-Agent"] ?: Http.UA_DESKTOP)
                    conn.setRequestProperty("Accept", "*/*")
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 12_000
                    val type = when {
                        url.contains(".m3u8") || url.contains("m3u8") -> "application/vnd.apple.mpegurl"
                        url.contains(".ts") -> "video/mp2t"
                        url.contains(".flv") -> "video/x-flv"
                        else -> "video/mp4"
                    }
                    val stream = conn.inputStream
                    WebResourceResponse(type, null, conn.responseCode, "OK", corsHeaders(), stream)
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "代理失败 $url : ${e.message}")
                    null
                }
            }

            private fun corsHeaders(): MutableMap<String, String> = mutableMapOf(
                "Access-Control-Allow-Origin" to "*",
                "Access-Control-Allow-Headers" to "*",
                "Access-Control-Allow-Methods" to "GET,HEAD,OPTIONS",
            )

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // 目标页开始加载 → 这一页是「新鲜的」，健康探测从现在开始计时
                if (url != null && samePage(url, targetUrl)) {
                    pageLoaded = false
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 只认「本次目标地址」的加载完成回调。
                // 切台时旧页面的 onPageFinished 可能晚到，若不加判断，
                // 会把新页面误标成「已加载完」，健康探测随即对新页面下错结论
                // （表现为一次换台瞬间把三个源全跳完）。
                if (!samePage(url, targetUrl)) {
                    android.util.Log.d(TAG, "忽略非目标页面回调: $url (目标 $targetUrl)")
                    return
                }
                pageLoaded = true
                injectPlayerCleanup()
                // 同时强力促使站点自己的播放器起来 ——
                // 真机上卡住的根因就是"播放器在等用户点击"，
                // 光靠清理脚本（只在 video 已存在时才起作用）救不了。
                injectAutoStart()
            }

            /** 同一页面的宽松比较（站点常有 301/补斜杠，故忽略末尾斜杠与 http/https 差异）。 */
            private fun samePage(a: String?, b: String?): Boolean {
                if (a == null || b == null) return false
                fun norm(s: String) = s.trim().removeSuffix("/")
                    .removePrefix("https://").removePrefix("http://")
                return norm(a) == norm(b)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    android.util.Log.w(TAG, "页面加载失败: ${error?.description}")
                }
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                android.util.Log.d(TAG, "[web] ${msg?.message()}")
                return true
            }

            /**
             * 站点播放器请求进入全屏。
             *
             * 这是**参考同类项目的做法**：不要去注入 CSS 强撑 `<video>`，
             * 而是让站点自己进全屏，然后把它交出来的那个 View 直接接管铺满。
             *
             * 两者差别很大：
             *   · 接管 View  —— 只有这一个 View 参与渲染，页面其余部分不画
             *   · 注入 CSS   —— 整页照常渲染，只是被 CSS 藏起来了，CPU 白烧
             */
            override fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
                if (view == null) return
                android.util.Log.i(TAG, "站点请求全屏，接管视频 View")
                fullscreenCallback?.invoke(view, callback)
            }

            override fun onHideCustomView() {
                android.util.Log.i(TAG, "站点退出全屏")
                fullscreenExit?.invoke()
            }
        }
    }

    /**
     * 站点请求全屏时的回调：(要接管的视频 View, 用于退回的回调)。
     *
     * 由外层提供容器并 addView —— 因为本类是 WebView 的子类，
     * 自己不能容纳子 View。
     */
    var fullscreenCallback: ((View, WebChromeClient.CustomViewCallback?) -> Unit)? = null

    /** 站点退出全屏。 */
    var fullscreenExit: (() -> Unit)? = null

    /**
     * 让站点播放器进入全屏（如果它支持）。
     *
     * 优先调站点的全屏 API，其次对 `<video>` 请求全屏。
     * 成功后站点会走 [WebChromeClient.onShowCustomView]，我们把那个 View 接管过来。
     *
     * @return true 表示已经发起了全屏请求
     */
    fun requestSiteFullscreen(): Boolean {
        val js = """
        (function(){
          try{
            /* 1) 站点自己的全屏按钮/接口 */
            var sels = ['[class*=fullscreen]','[class*=full-screen]','[class*=fullScreen]',
                        '[aria-label*=全屏]','[title*=全屏]','[class*=quanping]'];
            for (var i=0;i<sels.length;i++){
              var el = document.querySelector(sels[i]);
              if (el) { el.click(); return 'site'; }
            }
            /* 2) 退而求其次：对 video 本身请求全屏 */
            var vs = document.querySelectorAll('video');
            for (var j=0;j<vs.length;j++){
              var v = vs[j];
              var fn = v.requestFullscreen || v.webkitRequestFullscreen ||
                       v.webkitEnterFullscreen || v.mozRequestFullScreen;
              if (fn) { fn.call(v); return 'video'; }
            }
          }catch(e){}
          return 'none';
        })();
        """.trimIndent()
        evaluateJavascript(js, null)
        return true
    }

    /** 播放健康状态，供外层决定是否切换备用源。 */
    enum class Health { Unknown, Playing, Failed }

    /** 当前网页播放的健康状态。 */
    @Volatile
    var health: Health = Health.Unknown
        private set

    /** 健康探测的定时任务（切台/换源时要取消上一个）。 */
    private var healthTask: Runnable? = null

    /** 期望加载的目标地址（用于识别「页面是否已经是本次目标的新页面」）。 */
    private var targetUrl: String? = null

    /**
     * 加载代际号：每次换台/换源递增。
     *
     * 健康探测任务在启动时记住自己的代际，回调触发时若代际已过期就丢弃。
     * 只靠 removeCallbacks() 不够 —— 已经在执行中的 lambda 拦不住，
     * 它会在新频道上触发一次「切备用源」，表现为换台瞬间连跳好几个源。
     */
    @Volatile
    private var generation: Int = 0

    /**
     * 观察网页播放是否出画面。
     *
     * ## 这里**不做"超时即失败"的断言**
     *
     * 之前是「32 秒还没画面 → 判定这个源播不出来 → 自动切源 + 弹错误页」。
     * 实际用下来问题很大：
     *  - 很多电视台的网页播放器就是慢（要加载一串脚本、有时还要等一个内部请求），
     *    30 多秒才出画面很常见
     *  - 我的回读条件又偏严，明明已经在播也可能读到 false
     *  - 一旦误判就弹出全屏错误页，**黑幕把已经正常播放的画面挡住了**，
     *    而且这个错误状态不会因为"后来播出来了"而自动消失
     *
     * 现在改成：**只把"探测到在播"当成好消息**，用来清掉加载提示；
     * 探测不到就一直等（继续显示"正在接入…"），**不再自动切源、不再弹错误页**。
     * 播不出来时用户按「← →」手动换源就行 —— 手动切换这件事本来就该由人决定。
     *
     * @param onPlaying 确认已经出画面（外层据此收起加载提示）
     */
    fun startHealthWatch(onPlaying: () -> Unit) {
        health = Health.Unknown
        healthTask?.let { removeCallbacks(it) }
        val myGeneration = generation
        val task = object : Runnable {
            override fun run() {
                // 代际过期（已经切到别的台/别的源）→ 丢弃，避免在新频道上误判
                if (myGeneration != generation) return
                if (health == Health.Playing) return
                if (!pageLoaded) {
                    postDelayed(this, HEALTH_INTERVAL_MS)
                    return
                }
                evaluateJavascript(PROBE_JS) { r ->
                    if (myGeneration != generation) return@evaluateJavascript
                    if (r?.contains("true") == true) {
                        health = Health.Playing
                        android.util.Log.i(TAG, "网页播放已出画面")
                        onPlaying()
                        return@evaluateJavascript
                    }
                    // 没探到就接着探 —— 页面自己早晚会开始播，不催它
                    postDelayed(this, HEALTH_INTERVAL_MS)
                }
            }
        }
        healthTask = task
        postDelayed(task, HEALTH_INTERVAL_MS)
    }

    /** 加载某个电视频道的网页。 */
    fun loadChannel(url: String) {
        // 已经在加载/已经加载的就是这个地址，不用重复 load。
        // 但要把 pageLoaded 复位：此时 WebView 里面的旧页面（上一个台）
        // 早已加载完，若不复位，健康探测会拿旧页面的状态对新台下结论。
        if (url == targetUrl) {
            if (this.url != url) {
                pageLoaded = false
                generation++
            }
            return
        }
        targetUrl = url
        generation++
        videoFound = false
        pageLoaded = false
        health = Health.Unknown
        healthTask?.let { removeCallbacks(it) }
        settings.userAgentString = LiveCatalog.webUserAgentFor(url)
        android.util.Log.i(TAG, "load $url  ua=${settings.userAgentString}")
        loadUrl(url)
    }

    /**
     * 遥控器按键一律不消费，交给外层（Activity 层统一处理）。
     *
     * WebView 默认会吃掉方向键去做焦点移动/滚动，那样「上下键换台」就失效了。
     * 这里直接放行，焦点系统仍然掌控所有按键。
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean = false

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean = false

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent?): Boolean = false

    /**
     * 注入「只看视频」脚本。
     *
     * 做成**反复执行**（不是一次性）：页面里播放器创建 video 的时机不一致，
     * 而且不同台的面板结构不同 —— CCTV-13 是标准 video，CCTV-1 会额外露出
     * 进度条 / 节目单 / 时移控件。所以脚本里挂一个定时器，持续把「非视频层」清掉，
     * 直到视频稳定铺满（或超时）。
     */
    private fun injectPlayerCleanup() {
        evaluateJavascript(CLEANUP_JS, null)
    }

    /** 触发强力起播（见 [AUTOSTART_JS]）。 */
    private fun injectAutoStart() {
        evaluateJavascript(AUTOSTART_JS, null)
    }

    /**
     * 从"包装 URL"里解出真正的媒体流地址。
     *
     * 央视（以及不少台）的播放器会把真实流地址放在 query 参数里，
     * 常见键名是 `streamUrl`，值还是 URL 编码过的。例如：
     *
     *   https://p.data.cctv.com/play.1.3?...&streamUrl=https%3A%2F%2Fxxx%2Findex.m3u8%3Fb%3D...
     *
     * 不解出来的话，交给 ExoPlayer 的就是那个统计接口，必然黑屏。
     *
     * @return 解出来的媒体地址；没有内嵌地址时返回 null
     */
    private fun extractEmbeddedStreamUrl(raw: String): String? {
        val keys = listOf("streamUrl", "stream_url", "url", "src", "playUrl")
        for (k in keys) {
            val v = runCatching {
                android.net.Uri.parse(raw).getQueryParameter(k)
            }.getOrNull().orEmpty()
            if (v.isBlank()) continue
            // 解出来还得是个媒体地址，否则可能是别的用途的 url 参数
            if (looksLikeMediaUrl(v)) return v
        }
        return null
    }

    /**
     * 判断一个地址是不是媒体流。
     *
     * **只看 path，不看 query** —— 这是关键：
     * query 里出现 "m3u8" 不代表这个请求就是媒体流
     * （统计/上报接口经常把真实地址塞在参数里，见上面的坑）。
     */
    private fun looksLikeMediaUrl(url: String): Boolean {
        val path = runCatching {
            java.net.URI(url).path.orEmpty()
        }.getOrDefault("").lowercase()
        return path.endsWith(".m3u8") ||
            path.endsWith(".ts") ||
            path.endsWith(".m4s") ||
            path.endsWith(".flv") ||
            path.endsWith(".mp4") ||
            path.contains(".m3u8")
    }

    companion object {
        private const val TAG = "TvWebPlayer"

        /**
         * **强力起播脚本**：专治「页面加载了、播放器就是不起来」。
         *
         * ## 为什么需要它
         *
         * 真机实测（小米电视）：直播卡住时画面停在央视频的页面上，
         * 中间一个红圈转菊花 —— 那是**央视频自己的加载指示器**，
         * 说明它的播放器压根没初始化。
         *
         * 原因：现在的视频站普遍要**用户手势**才创建播放器实例
         * （省流量 / 规避自动播放限制）。WebView 里虽然设了
         * `mediaPlaybackRequiresUserGesture = false`，但那只是允许 `<video>`
         * 自动播；**站点自己的 JS 逻辑**照样在等 click/touch。
         *
         * 所以这里模拟真实用户操作：在播放器区域派发一整套
         * mousedown / mouseup / click / touchstart / touchend，
         * 让站点的播放器"以为用户点了一下"。
         *
         * 节奏：前 12 秒每 700ms 试一次（覆盖播放器异步初始化的各种时机），
         * 一旦真的有 `<video>` 在播就停手，不再打扰页面。
         */
        private const val AUTOSTART_JS = """
        (function(){
          if (window.__bawanAutostart) return 'again';
          window.__bawanAutostart = true;

          function tap(el){
            if(!el) return;
            var r = el.getBoundingClientRect();
            var x = r.left + r.width/2, y = r.top + r.height/2;
            if (x <= 0 || y <= 0 || r.width < 20 || r.height < 20) {
              x = window.innerWidth/2; y = window.innerHeight/2;
            }
            var base = {bubbles:true, cancelable:true, clientX:x, clientY:y,
                        screenX:x, screenY:y, button:0, buttons:1};
            ['mousedown','mouseup','click'].forEach(function(t){
              try{ el.dispatchEvent(new MouseEvent(t, base)); }catch(err){}
            });
            /* 有些站点只监听触摸事件 */
            try{
              el.dispatchEvent(new TouchEvent('touchstart', {bubbles:true, cancelable:true}));
              el.dispatchEvent(new TouchEvent('touchend',   {bubbles:true, cancelable:true}));
            }catch(err){}
          }

          /* 找出"最可能是播放器"的那块：优先已知类名，其次找大块的 video */
          function target(){
            var sels = ['[class*=player]','[class*=Player]','[class*=video]',
                        'video','canvas','iframe'];
            for (var i=0;i<sels.length;i++){
              try{
                var list = document.querySelectorAll(sels[i]);
                for (var j=0;j<list.length;j++){
                  var el = list[j];
                  var r = el.getBoundingClientRect();
                  if (r.width > window.innerWidth*0.3 && r.height > window.innerHeight*0.3) {
                    return el.tagName === 'VIDEO' ? el : (el.parentElement || el);
                  }
                }
              }catch(e){}
            }
            return document.body;
          }

          function playing(){
            try{
              var vs = document.querySelectorAll('video');
              for (var i=0;i<vs.length;i++){
                var v = vs[i];
                if (v && !v.paused && (v.currentTime > 0 || v.videoWidth > 0)) return true;
              }
            }catch(e){}
            return false;
          }

          var n = 0;
          var t = setInterval(function(){
            n++;
            if (playing()) { clearInterval(t); return; }
            /* 最快路径：直接让 video 播 */
            try{
              var vs = document.querySelectorAll('video');
              for (var i=0;i<vs.length;i++){
                var v = vs[i];
                if (!v) continue;
                if (v.muted) v.muted = false;
                if (v.volume < 1) v.volume = 1;
                if (v.paused) {
                  var pr = v.play();
                  if (pr && pr.catch) pr.catch(function(){});
                }
              }
            }catch(e){}
            /* 再模拟点击，促使站点自己初始化播放器 */
            tap(target());
            if (n > 17) clearInterval(t);
          }, 700);

          tap(target());
          return 'autostart';
        })();
        """

        /** 出画面探测的轮询间隔。 */
        private const val HEALTH_INTERVAL_MS = 2000L

        /**
         * 探测页面上是否已经有正在播放的 video。
         *
         * 判据刻意做得**宽**：只要「没暂停」且「已经有画面尺寸 或 时间在走」就算在播。
         * 之前要求 `readyState>=2`，把一堆明明在播的源判成了失败。
         */
        private const val PROBE_JS = """
        (function(){
          try{
            var vs=document.querySelectorAll('video');
            for(var i=0;i<vs.length;i++){
              var v=vs[i];
              if(!v) continue;
              if(!v.paused && (v.currentTime>0 || v.videoWidth>0)) return 'true';
              /* 有些播放器是静音自动播放起步、或 paused 标志没及时更新，
                 只要已经有画面尺寸也认为"出画面了" */
              if(v.videoWidth>0 && v.readyState>=2) return 'true';
            }
          }catch(e){}
          return 'false';
        })();
        """

        /**
         * 把视频放大铺满，并持续清理页面上的「非视频层」。
         *
         * 做成了自循环（最多 40 次 × 500ms ≈ 20 秒）：
         *  - 播放器创建 video 的时机不一致；
         *  - 不同台会额外露出进度条 / 节目单(EPG) / 时移开关 / 台标水印；
         *  - 页面里的控件还会在鼠标/触摸事件后重新出现。
         *
         * 清理策略尽量保守：只隐藏「不是 video 祖先链」的兄弟节点，
         * 以及明确属于播放器控件的元素（进度条、EPG、遮罩、水印、广告）。
         */
        private const val CLEANUP_JS = """
        (function(){
          if(window.__bawanCleanup){ try{ window.__bawanCleanup(); }catch(e){} return 'again'; }

          var junkSel = [
            '[class*=mask]','[class*=cover]','[id*=cover]',
            '[class*=watermark]','[class*=logo-]','[class*=ad-]','[class*=advert]',
            '[class*=epg]','[class*=program]','[class*=schedule]','[class*=timeline]',
            '[class*=progress]','[class*=control]','[class*=toolbar]','[class*=tool-bar]',
            '[class*=player-bar]','[class*=playerBar]','[class*=bottom]','[class*=footer]',
            '[class*=header]','[class*=nav]','[class*=aside]','[class*=sidebar]',
            '[class*=share]','[class*=qrcode]','[class*=download]',
            /* 登录/弹窗/遮罩：央视部分频道会弹「短信登录」把画面完全挡住 */
            '[class*=login]','[id*=login]','[class*=Login]',
            '[class*=dialog]','[id*=dialog]','[class*=Dialog]',
            '[class*=modal]','[id*=modal]','[class*=Modal]',
            '[class*=popup]','[id*=popup]','[class*=Popup]',
            '[class*=overlay]','[id*=overlay]','[class*=Overlay]',
            '[class*=verify]','[class*=auth]','[class*=passport]'
          ].join(',');

          function isVideoChain(el, v){
            var n = v;
            while(n){ if(n===el) return true; n=n.parentElement; }
            return false;
          }

          /* 保险：任何元素在隐藏前，先确认它既不包含 video、也不是 video 的祖先。
             这一条是为了避免像 CCTV-1 那样「清理过猛把播放器容器一起藏掉 → 黑屏」。 */
          function safeToHide(el, v){
            if(!el || el===v) return false;
            if(el.tagName==='VIDEO' || el.tagName==='SCRIPT' || el.tagName==='STYLE') return false;
            if(isVideoChain(el, v)) return false;
            if(el.contains(v)) return false;
            if(el.querySelector && el.querySelector('video')) return false;
            return true;
          }

          /* 隐藏后校验：视频还在文档里、尺寸还在，就认为这次清理是安全的 */
          function videoStillOk(v){
            return !!(v && v.isConnected && v.offsetWidth>0 && v.offsetHeight>0);
          }

          function cleanup(){
            try{
              document.documentElement.style.background='#000';
              if(document.body){ document.body.style.background='#000'; document.body.style.margin='0'; document.body.style.overflow='hidden'; }

              var vs=document.querySelectorAll('video');
              var v=null;
              for(var i=0;i<vs.length;i++){ if(vs[i].videoWidth>0||vs[i].readyState>0||vs[i].currentTime>0){ v=vs[i]; break; } }
              if(!v && vs.length){ v=vs[0]; }
              if(!v) return false;

              /* 1) video 及其祖先铺满，祖先的兄弟节点隐藏（带保险） */
              var node=v;
              while(node && node!==document.body){
                node.style.setProperty('width','100%','important');
                node.style.setProperty('height','100%','important');
                node.style.setProperty('max-width','none','important');
                node.style.setProperty('max-height','none','important');
                node.style.setProperty('position','static','important');
                node.style.setProperty('margin','0','important');
                node.style.setProperty('padding','0','important');
                node.style.setProperty('transform','none','important');
                node.style.setProperty('overflow','hidden','important');
                var p=node.parentElement;
                if(p){
                  for(var i2=0;i2<p.children.length;i2++){
                    var sib=p.children[i2];
                    if(sib!==node && safeToHide(sib,v)){
                      sib.style.setProperty('display','none','important');
                    }
                  }
                }
                node=p;
              }
              v.style.setProperty('position','fixed','important');
              v.style.setProperty('left','0','important');
              v.style.setProperty('top','0','important');
              v.style.setProperty('width','100vw','important');
              v.style.setProperty('height','100vh','important');
              v.style.setProperty('object-fit','contain','important');
              v.style.setProperty('z-index','2147483647','important');
              v.style.setProperty('background','#000','important');
              v.controls = false;  /* 关掉浏览器自带控件 */

              /* 2) 清掉播放器控件 / EPG / 遮罩 / 水印等（带保险） */
              try{
                document.querySelectorAll(junkSel).forEach(function(e){
                  if(safeToHide(e,v)) e.style.setProperty('display','none','important');
                });
              }catch(e){}

              /* 3) 兜底：贴在屏幕底部的一条（进度条/EPG）也藏掉，但严格排除含 video 的节点 */
              try{
                var all=document.querySelectorAll('div,section');
                for(var i3=0;i3<all.length;i3++){
                  var e2=all[i3];
                  if(!safeToHide(e2,v)) continue;
                  var r=e2.getBoundingClientRect();
                  if(r.height>0 && r.height<170 && r.top>window.innerHeight*0.72 && r.width>window.innerWidth*0.5){
                    e2.style.setProperty('display','none','important');
                  }
                }
              }catch(e){}

              /* 3b) 全屏遮罩/弹窗：几乎铺满屏幕、又把视频挡在下面的那一层，直接藏掉。 */
              try{
                var layers=document.querySelectorAll('div,section,aside');
                for(var i5=0;i5<layers.length;i5++){
                  var e3=layers[i5];
                  if(!safeToHide(e3,v)) continue;
                  var r3=e3.getBoundingClientRect();
                  if(r3.width<window.innerWidth*0.25 || r3.height<window.innerHeight*0.25) continue;
                  var st=window.getComputedStyle(e3);
                  var z=parseInt(st.zIndex,10);
                  if(isNaN(z)) z=0;
                  /* 大块 + 浮层定位 + 高 z-index → 判定为遮挡层 */
                  if((st.position==='fixed'||st.position==='absolute') && z>=100){
                    e3.style.setProperty('display','none','important');
                  }
                }
              }catch(e){}

              /* 3d) 兜底：顶部那条「网站自己的导航栏」。
                 真机反馈里最显眼的问题就是它 —— 央视/央视频在播放器上方留一整条
                 菜单栏（首页/时政/新闻/…、地方/乡村振兴/…），用户一眼就看出
                 "这不是电视，是个网页"。

                 之前这里只认**浅色背景**，而央视频的导航栏是**深蓝渐变**，
                 所以规则压根没命中。现在放宽成：
                   位于顶部 15% + 横向占屏幕 60% 以上 + 高度小于 20% + 不是视频祖先链
                 → 一律隐藏。不再看背景色。
                 视频单独一条保护：safeToHide 已经排除了 video 及其祖先。 */
              try{
                var tops=document.querySelectorAll('div,section,header,nav,ul');
                for(var i6=0;i6<tops.length;i6++){
                  var e4=tops[i6];
                  if(!safeToHide(e4,v)) continue;
                  var r4=e4.getBoundingClientRect();
                  if(r4.height<=0 || r4.height>window.innerHeight*0.20) continue;
                  if(r4.top>window.innerHeight*0.15) continue;
                  if(r4.width<window.innerWidth*0.6) continue;
                  e4.style.setProperty('display','none','important');
                }
              }catch(e){}

              /* 3e) 底部那条「时间轴 / 节目单 / 播放控制条」。
                 真机截图里央视频底部有一条带时间刻度和"时移/自动/原声"的控制条。
                 判据：位于底部 25% + 横向占屏幕 60% 以上 + 高度小于 30%。 */
              try{
                var bots=document.querySelectorAll('div,section,footer');
                for(var i7=0;i7<bots.length;i7++){
                  var e5=bots[i7];
                  if(!safeToHide(e5,v)) continue;
                  var r5=e5.getBoundingClientRect();
                  if(r5.height<=0 || r5.height>window.innerHeight*0.30) continue;
                  if(r5.bottom < window.innerHeight*0.75) continue;
                  if(r5.width<window.innerWidth*0.6) continue;
                  e5.style.setProperty('display','none','important');
                }
              }catch(e){}

              /* 3c) 关掉滚动锁：有些站点用 body 的 overflow 锁住页面配合弹窗 */
              try{
                document.documentElement.style.setProperty('overflow','hidden','important');
                if(document.body) document.body.style.setProperty('overflow','hidden','important');
              }catch(e){}

              /* 4) 校验：这一步清理之后视频必须还在。不在的话把刚才隐藏的祖先兄弟恢复回来。 */
              if(!videoStillOk(v)){
                try{
                  var node2=v;
                  while(node2 && node2!==document.body){
                    var p2=node2.parentElement;
                    if(p2){
                      for(var i4=0;i4<p2.children.length;i4++){
                        var s2=p2.children[i4];
                        if(s2!==node2) s2.style.removeProperty('display');
                      }
                    }
                    node2=p2;
                  }
                }catch(e){}
                return false;
              }

              /* 5) 自动播放 + 音量保障。
                 —— 这里刻意「不做静音自动播放」：
                 WebView 已设置 mediaPlaybackRequiresUserGesture=false，本来就允许带声音自动播放；
                 而且清理函数是每 500ms 反复执行的，一旦在里面写 v.muted=true，
                 就会把上一次恢复的音量又静音回去 —— 这正是「有画面没声音」的根因。
                 —— 同时把音量补到 1：实测央视频页面自己会把 volume 设成 0.5，
                 不补的话同一个 App 里不同台音量不一致。 */
              try {
                if (v.muted) v.muted = false;
                if (v.volume < 1) v.volume = 1;

                /* ---------- 铺满全屏（真机反馈「下方和右边有黑边」）----------
                   页面的 <video> 常带自己的固定尺寸/宽高比，只靠 WebView 铺满不够。
                   这里把 video 自身和它的祖先链一起撑到 100%×100%，
                   并用 object-fit:fill 让它**填满而不是等比留边**。 */
                v.style.setProperty('width', '100%', 'important');
                v.style.setProperty('height', '100%', 'important');
                v.style.setProperty('max-width', 'none', 'important');
                v.style.setProperty('max-height', 'none', 'important');
                v.style.setProperty('object-fit', 'fill', 'important');
                v.style.setProperty('position', 'absolute', 'important');
                v.style.setProperty('left', '0', 'important');
                v.style.setProperty('top', '0', 'important');
                v.style.setProperty('z-index', '2147483000', 'important');

                /* 祖先链也要清掉限宽限高与 padding/margin，否则 video 撑不开 */
                var p = v.parentElement, depth = 0;
                while (p && p !== document.body && depth < 6) {
                  p.style.setProperty('width', '100%', 'important');
                  p.style.setProperty('height', '100%', 'important');
                  p.style.setProperty('max-width', 'none', 'important');
                  p.style.setProperty('max-height', 'none', 'important');
                  p.style.setProperty('padding', '0', 'important');
                  p.style.setProperty('margin', '0', 'important');
                  p.style.setProperty('overflow', 'hidden', 'important');
                  p = p.parentElement;
                  depth++;
                }
                /* 页面本体掐掉滚动条和默认外边距，避免右下角露白边 */
                document.documentElement.style.setProperty('overflow', 'hidden', 'important');
                if (document.body) {
                  document.body.style.setProperty('overflow', 'hidden', 'important');
                  document.body.style.setProperty('margin', '0', 'important');
                  document.body.style.setProperty('background', '#000', 'important');
                }

                if (v.paused) { var pr = v.play(); if (pr && pr.catch) pr.catch(function(){}); }
              /* 回报“真的在播了”，让原生层把 WebView 显示出来 */
              try {
                if (!window.__bawanReported && !v.paused && v.readyState >= 2) {
                  window.__bawanReported = true;
                  if (window.BawanBridge && window.BawanBridge.onPlaying) {
                    window.BawanBridge.onPlaying();
                  }
                }
              } catch(e){}
              } catch(e){}
              return true;
            }catch(e){ return false; }
          }

          window.__bawanCleanup = cleanup;

          /* ---------- 清理节奏：前密后疏，出画面就停 ----------
             真机实测「直播卡成 PPT」，这也是主因之一：
             原来是无条件 `setInterval(500ms)` 跑 40 次（20 秒），
             每次都对整个页面做多轮 querySelectorAll（央视页面 DOM 很重）。
             视频一开始解码，这些全量 DOM 查询就跟解码抢 CPU。

             现在改成：
               · 前 6 次每 400ms（覆盖页面刚加载完、控件陆续冒出来的阶段）
               · 之后每 1.5 秒，最多再跑 8 次（兜住慢站点）
               · **一旦视频确实在播了就彻底停止**，不再干扰解码
             清理是"把页面弄干净"的一次性工作，不该在观看全程持续跑。 */
          var n = 0;
          var t = null;
          function tick(){
            n++;
            /* 视频已经在放了 → 收工，不再动 DOM */
            try{
              var vs = document.querySelectorAll('video');
              for(var i=0;i<vs.length;i++){
                var v = vs[i];
                if(v && !v.paused && (v.currentTime>0 || v.videoWidth>0)){ clearInterval(t); return; }
              }
            }catch(e){}
            cleanup();
            if(n >= 6 && t){ clearInterval(t); t = setInterval(tick, 1500); }
            if(n >= 14 && t){ clearInterval(t); }
          }
          t = setInterval(tick, 400);
          cleanup();
          return 'started';
        })();
        """
    }
}

/** 供测试断言使用（避免未使用告警） */
internal val unusedStream = ByteArrayInputStream(ByteArray(0))
