package com.chinut.bawantv.ui.screens

import androidx.compose.foundation.border
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.chinut.bawantv.BawanApp
import com.chinut.bawantv.BuildConfig
import com.chinut.bawantv.core.DebugWebServer
import com.chinut.bawantv.core.ParentalControl
import com.chinut.bawantv.core.Qr
import com.chinut.bawantv.core.Updater
import com.chinut.bawantv.core.UpdateState
import com.chinut.bawantv.ddys.Ddys
import com.chinut.bawantv.ui.ParentalPinDialog
import com.chinut.bawantv.ui.ReadonlyKeyValue
import com.chinut.bawantv.ui.theme.Dim
import com.chinut.bawantv.ui.theme.entryFocusable
import com.chinut.bawantv.ui.theme.Ink
import com.chinut.bawantv.ui.theme.registerViewportScroll
import com.chinut.bawantv.ui.theme.rememberTvFocusState
import com.chinut.bawantv.ui.theme.sdp
import com.chinut.bawantv.ui.theme.ssp
import com.chinut.bawantv.ui.theme.tvFocusable
import com.chinut.bawantv.ui.theme.Txt
import com.chinut.bawantv.ui.TvKeyboardDialog
import com.chinut.bawantv.ui.TvSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 板块 D：设置。
 *
 * 全部设置都可以在电视上完成，但为了不折磨遥控器，长文本（订阅地址）优先引导用户
 * 用手机扫码改（手机网页调试模式）。域名、开关这类短设置直接在电视上改。
 */
@Composable
fun SettingsScreen(
    entryKey: Any,
) {
    val context = LocalContext.current
    val prefs = BawanApp.prefs
    val scope = rememberCoroutineScope()

    // 让设置项变更后立刻触发重组
    val revision by prefs.revision.collectAsState()
    val serverRunning by DebugWebServer.running.collectAsState()
    val serverPort by DebugWebServer.port.collectAsState()
    val updateState by Updater.state.collectAsState()

    // 焦点管理器：进页面时用它把焦点放到「导入直播源文件」行
    val focusManager = com.chinut.bawantv.ui.theme.LocalTvFocusManager.current
    /** 上一次「检查更新」的结果是否"已是最新"（用于 Idle 状态给出准确提示）。 */
    var checkedAndCurrent by remember { mutableStateOf(false) }

    var editing by remember { mutableStateOf<EditTarget?>(null) }

    // ---------- 导入 m3u 文件 ----------
    //
    // 为什么需要：用户的 IPTV 源往往是**文件**（运营商给的、朋友发来的），
    // 让他在电视上用遥控器敲一个长网址不现实。
    //
    // 用系统的文件选择器（SAF）—— 它是独立窗口，但那是系统自己的界面，
    // 用户在里面的操作由系统处理，选完回来我们才接管。所以没有
    // "Compose Dialog 吞按键"那个问题。
    var importMsg by remember { mutableStateOf("") }

    // ---------- IPTV 自动扫描 ----------
    //
    // 在电视上扫，用的是**用户自己那条宽带** —— 这比在开发者电脑上测准得多。
    //
    // ⚠️ 电视上没有 ffmpeg，做不到真正的解码验证（电脑版能识破"花屏加密"，
    // 这里不能）。所以界面上要如实说明"可能仍有少量看不了的源"。
    var scanning by remember { mutableStateOf(false) }
    var scanMsg by remember { mutableStateOf("") }
    var scanDone by remember { mutableIntStateOf(0) }
    var scanTotal by remember { mutableIntStateOf(0) }
    var scanOk by remember { mutableIntStateOf(0) }
    var scannedCount by remember {
        mutableIntStateOf(
            com.chinut.bawantv.live.IptvScanner.resultChannelCount(context)
        )
    }
    val scanScope = rememberCoroutineScope()
    var importedCount by remember {
        mutableIntStateOf(
            if (com.chinut.bawantv.live.LiveCatalog.hasImported(context)) 1 else 0
        )
    }
    val pickM3u = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts
            .OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val n = com.chinut.bawantv.live.LiveCatalog.importFrom(context, uri)
        if (n > 0) {
            importedCount = n
            importMsg = "已导入 $n 个频道，重启直播后生效"
            toast(context, "导入成功：$n 个频道")
        } else {
            importMsg = "导入失败：这个文件里没有识别到频道"
            toast(context, "导入失败，请确认是 m3u / txt 频道表")
        }
    }
    var showQr by remember { mutableStateOf(false) }
    var resolvedDomain by remember { mutableStateOf(Ddys.activeBase) }

    // ---------- 未成年人保护的状态 ----------
    var parentalOn by remember { mutableStateOf(ParentalControl.enabled) }
    /** 家长密码流程处于哪一步；null 表示没在输密码。 */
    var pinStage by remember { mutableStateOf<PinStage?>(null) }
    /** 首次设置时暂存第一次输入，用于二次确认。 */
    var pinFirst by remember { mutableStateOf("") }
    /** 输完 PIN 后想把保护设成什么状态。 */
    var pendingEnable by remember { mutableStateOf(false) }
    /** 名单被改过时用它触发重组（名单内容存在 prefs 里，不是 Compose 状态）。 */
    var wordEpoch by remember { mutableIntStateOf(0) }

    fun blockedCount() = prefs.parentalBlockedWords.split(',').count { it.isNotBlank() }
    fun allowedCount() = prefs.parentalAllowedWords.split(',').count { it.isNotBlank() }

    // 进入设置页时启动调试服务（方便随时扫码）
    LaunchedEffect(Unit) {
        if (prefs.debugEnabled && !DebugWebServer.running.value) {
            DebugWebServer.start(context, prefs.debugPort)
        }
        withContext(Dispatchers.IO) {
            resolvedDomain = Ddys.activeBase
        }
    }


    val listState = rememberLazyListState()

    // 把设置列表注册成"焦点导航的兜底滚动目标"。
    //
    // 之前设置页**没有注册**，于是方向键移到屏幕外的那一项时就完全没反应 ——
    // 表现就是"下拉看不到最下方内容"。这个机制在影视页有、设置页漏了。

    // ---------- 根容器 ----------
    //
    // 三个浮层（二维码 / 家长密码 / TV 键盘）要用 fillMaxSize 铺满整屏。
    // 如果把它们留在 LazyColumn 的 item 里，fillMaxSize 拿到的是那个 item
    // 的边界，浮层会被滚动容器裁成窄条 —— 所以必须和 LazyColumn 平级。
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        // ---------- 设置项变更后必须刷新界面 ----------
        //
        // ⚠️ 踩过一个很隐蔽的坑：原来在函数末尾写了一句裸的 `revision`，
        // 指望它触发重组。但 **Compose 只把"被真正消费的状态读取"算作依赖** ——
        // 读出来就丢掉的表达式不构成依赖，revision 变化时这一层不重组。
        //
        // 表现就是用户报的「设置里所有开关按了没反应」：
        // 日志显示 onToggle 被调用、pref 也写进了文件，
        // 但 TvSwitch 收到的 checked 永远是旧值，屏幕一动不动。
        //
        // 现在用 key(revision) 包住整块内容：revision 一变化，
        // 这棵子树重建，所有开关/按钮都拿到新值。
        key(revision) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().registerViewportScroll { d -> listState.scrollBy(d.toFloat()) },
            contentPadding = PaddingValues(end = 12.sdp, bottom = 40.sdp),
            verticalArrangement = Arrangement.spacedBy(18.sdp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("设置", color = Color.White, fontSize = Txt.Title, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(14.sdp))
                    Text(
                        "遥控器上下选择，确定键进入/切换",
                        color = Ink.TextTertiary,
                        fontSize = Txt.Caption,
                    )
                }
            }

            // ==================== 手机网页调试 ====================
            item {
                // ---------- 导入直播源文件 ----------
                //
                // 放在**最上面**：这是用户最可能想做的事（换掉内置源），
                // 而且原来它在第 2~3 屏时，方向键会跳过它 ——
                // 因为那一行的可点击区域右边是窄按钮、上下都是整行，
                // 几何导航的"交叉轴错位 × 2.5"打分让它永远不是最优目标
                // （实测按 15 次下键都落不到）。放最上面就绕开了这个问题。
                SettingsCard(
                    title = "直播源",
                    subtitle = "自动扫描（用你家网络实测），或导入运营商给的 m3u 文件",
                    accent = Ink.Green,
                ) {
                    TvRow(
                        label = if (importedCount > 0) {
                            "已导入 $importedCount 个频道"
                        } else {
                            "还没有导入自定义源"
                        },
                        hint = if (importedCount > 0) {
                            "导入的源优先于下面的「直播源地址」，也优先于内置频道表"
                        } else {
                            "导入后优先于内置频道表；想换回来点「清除」"
                        },
                        hintColor = if (importedCount > 0) Ink.Green else Ink.TextFaint,
                        actionText = if (importedCount > 0) "重新导入" else "选择文件",
                    ) {
                        pickM3u.launch(
                            arrayOf(
                                "application/vnd.apple.mpegurl",
                                "audio/x-mpegurl",
                                "application/x-mpegURL",
                                "text/plain",
                                "*/*",
                            )
                        )
                    }
                    // ---------- 自动扫描 IPTV 源 ----------
                    TvRow(
                        label = if (scanning) {
                            "正在扫描… $scanDone/$scanTotal（可用 $scanOk）"
                        } else if (scannedCount > 0) {
                            "已扫到 $scannedCount 个可用频道"
                        } else {
                            "自动扫描 IPTV 源"
                        },
                        hint = if (scanning) {
                            "用的是你家宽带，结果最准。大概几分钟，别关电视"
                        } else if (scannedCount > 0) {
                            "扫描源优先于内置频道表。可能仍有看不了的，按 ←→ 换源"
                        } else {
                            "从网上找直播源并逐个实测，可用的存到本地"
                        },
                        hintColor = when {
                            scanning -> Ink.Amber
                            scannedCount > 0 -> Ink.Green
                            else -> Ink.TextFaint
                        },
                        actionText = if (scanning) "扫描中" else "开始扫描",
                    ) {
                        if (!scanning) {
                            scanning = true
                            scanMsg = ""
                            scanDone = 0; scanTotal = 0; scanOk = 0
                            scanScope.launch {
                                val r = runCatching {
                                    com.chinut.bawantv.live.IptvScanner.scan(
                                        context = context,
                                        maxPerChannel = 3,
                                    ) { stage, done, total, ok, cur ->
                                        scanDone = done
                                        scanTotal = total
                                        scanOk = ok
                                        scanMsg = if (total > 0) "$stage $done/$total" else stage
                                    }
                                }.getOrElse {
                                    com.chinut.bawantv.live.IptvScanner.Outcome(
                                        0, 0, 0, false,
                                        "扫描出错：${it.javaClass.simpleName}",
                                    )
                                }
                                scanning = false
                                scannedCount =
                                    com.chinut.bawantv.live.IptvScanner
                                        .resultChannelCount(context)
                                scanMsg = if (r.saved) {
                                    "扫到 ${r.channels} 个频道 / ${r.urls} 个地址，已保存"
                                } else {
                                    r.note.ifBlank { "没扫到可用的源" }
                                }
                                toast(
                                    context,
                                    if (r.saved) "扫到 ${r.channels} 个频道" else "没扫到可用的源",
                                )
                            }
                        }
                    }
                    if (scanMsg.isNotBlank()) {
                        Spacer(Modifier.height(6.sdp))
                        Text(scanMsg, color = Ink.Amber, fontSize = Txt.Tiny)
                    }
                    if (scannedCount > 0 && !scanning) {
                        TvRow(
                            label = "清除扫描到的源",
                            hint = "回到内置频道表",
                            actionText = "清除",
                        ) {
                            com.chinut.bawantv.live.IptvScanner.clearResult(context)
                            scannedCount = 0
                            scanMsg = "已清除扫描结果"
                            toast(context, "已清除")
                        }
                    }

                    if (importedCount > 0) {
                        TvRow(
                            label = "清除导入的源",
                            hint = "回到内置频道表",
                            actionText = "清除",
                        ) {
                            com.chinut.bawantv.live.LiveCatalog.clearImported(context)
                            importedCount = 0
                            importMsg = "已清除导入的源"
                            toast(context, "已清除")
                        }
                    }
                    if (importMsg.isNotBlank()) {
                        Spacer(Modifier.height(6.sdp))
                        Text(importMsg, color = Ink.Amber, fontSize = Txt.Tiny)
                    }
                }

                SettingsCard(
                    title = "手机网页调试",
                    subtitle = "电视上打字太麻烦：用手机扫码，在手机上改所有设置，保存后电视立即生效",
                    focusKey = entryKey,
                    accent = Ink.Green,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (serverRunning) {
                                    "服务运行中：${Qr.debugUrl(context, serverPort)}"
                                } else {
                                    "服务未运行"
                                },
                                color = if (serverRunning) Ink.Green else Ink.TextTertiary,
                                fontSize = Txt.Label,
                            )
                            Spacer(Modifier.height(4.sdp))
                            Text(
                                "手机与电视需连同一个 WiFi。默认端口 ${prefs.debugPort}。",
                                color = Ink.TextFaint,
                                fontSize = Txt.Tiny,
                            )
                        }
                        Spacer(Modifier.width(12.sdp))
                        SmallButton(if (showQr) "收起二维码" else "显示二维码") { showQr = !showQr }
                        Spacer(Modifier.width(10.sdp))
                        SmallButton(if (serverRunning) "停止" else "启动") {
                            if (serverRunning) {
                                DebugWebServer.stop()
                            } else {
                                DebugWebServer.start(context, prefs.debugPort)
                            }
                        }
                    }

                    if (showQr && serverRunning) {
                        Spacer(Modifier.height(16.sdp))
                        QrPanel(url = Qr.debugUrl(context, serverPort)) { showQr = false }
                    }
                    Spacer(Modifier.height(10.sdp))
                    TvSwitch(
                        label = "允许手机调试",
                        hint = "关闭后不再自动启动局域网服务",
                        checked = prefs.debugEnabled,
                    ) { prefs.debugEnabled = it }

                    Spacer(Modifier.height(4.sdp))
                    KeyValueRow(
                        label = "调试端口",
                        value = prefs.debugPort.toString(),
                        onEdit = { editing = EditTarget.Port },
                    )
                    KeyValueRow(
                        label = "手机口令",
                        value = prefs.debugToken.ifBlank { "（未设置）" },
                        onEdit = { editing = EditTarget.Token },
                    )
                }
            }

            // ==================== 影视内容来源 ====================
            item {
                SettingsCard(
                    title = "影视内容来源",
                    subtitle = "影视内容全部来自**低端影视**：片库缓存在电视本地，" +
                        "进影视页立刻有内容，断网也能浏览已缓存的片子。" +
                        "它的剧集是直连地址，不需要任何「解析接口」。",
                    accent = Ink.Green,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            com.chinut.bawantv.unified.LibraryStore.let { ls ->
                                val n = runCatching { ls.load().size }.getOrDefault(0)
                                if (n > 0) "本地片库：$n 部（已缓存，离线可看）"
                                else "本地片库：空，去影视页按「刷新片库」拉取"
                            },
                            color = Ink.TextTertiary,
                            fontSize = Txt.Tiny,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        SmallButton("清空片库") {
                            com.chinut.bawantv.unified.LibraryStore.clear()
                            toast(context, "本地片库已清空")
                        }
                    }
                }
            }


            // ==================== 板块 C：低端影视 ====================
            item {
                SettingsCard(
                    title = "低端影视域名（板块 C）",
                    subtitle = "网站换域名时改这里。官方永久域名 ddys.io，另有 ddys.pics / ddys.live / ddys.help 三个镜像。",
                    accent = Ink.Amber,
                ) {
                    KeyValueRow(
                        label = "当前域名",
                        value = prefs.domain,
                        onEdit = { editing = EditTarget.Domain },
                    )
                    Spacer(Modifier.height(6.sdp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "最近探测可用：${resolvedDomain.removePrefix("https://")}",
                            color = Ink.TextFaint,
                            fontSize = Txt.Tiny,
                            modifier = Modifier.weight(1f),
                        )
                        SmallButton("探测可用域名") {
                            scope.launch {
                                val d = withContext(Dispatchers.IO) { Ddys.probe(force = true) }
                                resolvedDomain = "https://$d"
                                toast(context, "可用域名：$d")
                            }
                        }
                    }
                    Spacer(Modifier.height(6.sdp))
                    TvSwitch(
                        label = "自动探测域名",
                        hint = "启动时自动在四个官方域名里挑一个能通的",
                        checked = prefs.autoPickDomain,
                    ) { prefs.autoPickDomain = it }
                    Spacer(Modifier.height(4.sdp))
                    TvSwitch(
                        label = "网页兜底",
                        hint = "接口异常时改用网页方式抓取（备用方案）",
                        checked = prefs.webFallbackEnabled,
                    ) { prefs.webFallbackEnabled = it }
                }
            }

            // ==================== 板块 A：直播 ====================
            item {
                SettingsCard(
                    title = "电视直播（板块 A）",
                    subtitle = "内置央视频道表；也可以填自己的 m3u / txt 直播源地址（留空用内置）",
                    accent = Ink.Pink,
                ) {
                    ReadonlyKeyValue(
                        value = prefs.liveSourceUrl,
                        placeholder = "（留空使用内置频道表）",
                        onEditRequest = { editing = EditTarget.LiveSource },
                        maxLines = 2,
                    )
                    Spacer(Modifier.height(8.sdp))


                    Spacer(Modifier.height(6.sdp))
                    TvSwitch(
                        label = "开机自动播放上次频道",
                        hint = "像老电视一样，打开就在台上",
                        checked = prefs.autoPlayLastChannel,
                        upKey = if (importedCount > 0) "set:clearImport"
                        else "set:importM3u",
                        downKey = "set:channelHud",
                        focusKey = "set:autoLast",
                    ) { prefs.autoPlayLastChannel = it }
                    TvSwitch(
                        label = "换台时显示台标浮层",
                        hint = "关闭后换台更干净",
                        checked = prefs.showChannelHud,
                        upKey = "set:autoLast",
                        downKey = "set:hwDecode",
                        focusKey = "set:channelHud",
                    ) { prefs.showChannelHud = it }
                    TvSwitch(
                        label = "硬件解码",
                        hint = "画面花屏/绿屏时关掉试试",
                        checked = prefs.hardwareDecode,
                        upKey = "set:channelHud",
                        downKey = "set:liveQuality",
                        focusKey = "set:hwDecode",
                    ) { prefs.hardwareDecode = it }

                    // ---------- 播放清晰度 ----------
                    //
                    // 为什么要给手动档：自动判定再准也猜不透每一台电视。
                    // 老电视解码弱的时候，用户自己降到「省流」就能流畅 ——
                    // 这比让他等我们改代码现实得多。确定键循环切换，遥控器好按。
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.sdp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "播放清晰度",
                                color = Ink.TextSecondary,
                                fontSize = Txt.Label,
                            )
                            Text(
                                "画面卡顿/一直转圈就往下调一档；确定键循环切换",
                                color = Ink.TextFaint,
                                fontSize = Txt.Tiny,
                            )
                        }
                        Spacer(Modifier.width(14.sdp))
                        SmallButton(
                            label = listOf("自动", "省流 360p", "标清 480p", "高清 720p")
                                .getOrElse(prefs.liveQuality) { "自动" },
                            focusKey = "set:liveQuality",
                            upKey = "set:hwDecode",
                        ) {
                            prefs.liveQuality = (prefs.liveQuality + 1) % 4
                            toast(
                                context,
                                "直播清晰度：" +
                                    listOf("自动", "省流 360p", "标清 480p", "高清 720p")
                                        .getOrElse(prefs.liveQuality) { "自动" },
                            )
                        }
                    }
                }
            }

            // ==================== 未成年人保护 ====================
            item {
                SettingsCard(
                    title = "未成年人保护",
                    subtitle = "按分类与关键词过滤不合适的内容；开关由 4 位家长密码保护",
                    accent = Ink.Amber,
                ) {
                    TvSwitch(
                        label = if (parentalOn) "已开启" else "已关闭",
                        hint = if (parentalOn) {
                            "少儿/动漫/科教/纪录等分类可看，恐怖/犯罪/情色等被拦下"
                        } else {
                            "开启后，明显不适合未成年人的内容不会出现在列表里"
                        },
                        checked = parentalOn,
                    ) { want ->
                        if (!ParentalControl.hasPin) {
                            // 第一次开启：先让家长设一个 PIN
                            if (want) {
                                pendingEnable = true
                                pinStage = PinStage.SetFirst
                            } else {
                                parentalOn = false
                            }
                        } else if (ParentalControl.unlocked) {
                            ParentalControl.setEnabled(want)
                            parentalOn = want
                        } else {
                            // 已经有 PIN：改开关前必须验一次
                            pendingEnable = want
                            pinStage = PinStage.Verify
                        }
                    }

                    if (ParentalControl.hasPin) {
                        Spacer(Modifier.height(4.sdp))
                        KeyValueRow(
                            label = "家长密码",
                            value = if (ParentalControl.unlocked) "本次已解锁" else "已设置（点此重设）",
                            onEdit = { pinStage = PinStage.SetFirst },
                        )
                    }

                    Spacer(Modifier.height(6.sdp))
                    Text(
                        "怎么判断能不能看：内容源**都不提供年龄分级**，" +
                            "所以这里是用「分类 + 关键词」做启发式过滤——" +
                            "少儿/动漫/科教/纪录等放行，恐怖/犯罪/情色/暴力等拦下，" +
                            "两条都没命中时默认拦（不确定就不放）。" +
                            "它拦得住明显的，拦不住刻意包装的内容；" +
                            "要更严格可以在下面的名单里补具体片名。",
                        color = Ink.TextFaint,
                        fontSize = Txt.Tiny,
                        lineHeight = 18.ssp,
                    )

                    Spacer(Modifier.height(8.sdp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "自定义名单：拦 ${blockedCount()} 词 · 放行 ${allowedCount()} 词",
                            color = Ink.TextSecondary,
                            fontSize = Txt.Label,
                            modifier = Modifier.weight(1f),
                        )
                        SmallButton("清空名单") {
                            ParentalControl.clearWordLists()
                            wordEpoch++
                        }
                    }
                }
            }

            // ==================== 更新 ====================
            item {
                SettingsCard(
                    title = "版本与更新",
                    subtitle = "发布在 Gitee 与 GitHub，优先走 Gitee 下载",
                    accent = Ink.Green,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "当前版本 v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                            color = Ink.TextSecondary,
                            fontSize = Txt.Label,
                            modifier = Modifier.weight(1f),
                        )
                        SmallButton("检查更新") {
                            scope.launch {
                                val info = withContext(Dispatchers.IO) { Updater.check(context) }
                                if (info == null && Updater.state.value is UpdateState.UpToDate) {
                                    toast(context, "已经是最新版本")
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.sdp))
                    when (val st = updateState) {
                        is UpdateState.Checking -> StatusLine(st.message, Ink.TextTertiary)

                        // ---------- 已是最新 ----------
                        //
                        // 以前这里只有一行字，**没有任何按钮** —— 用户在当前版本下
                        // 翻遍设置都找不到"下载与安装"，反馈就是"现版本的下载与安装
                        // 点选不到"。因为那个按钮原来只在"检测到新版"时才存在。
                        //
                        // 现在即使已是最新，也给一个「重新下载安装的版本」的出路：
                        // 覆盖安装是电视上的常规操作，装坏了、想重装都用得上。
                        is UpdateState.UpToDate -> {
                            StatusLine("已经是最新版本", Ink.Green)
                            Spacer(Modifier.height(8.sdp))
                            SmallButton("重新下载并覆盖安装") {
                                scope.launch {
                                    // 强制重新查一次，拿到当前最新 Release 的下载地址
                                    val info = runCatching {
                                        withContext(Dispatchers.IO) { Updater.check(context, force = true) }
                                    }.getOrNull()
                                    if (info == null) {
                                        toast(context, "拿不到下载地址，请稍后再试")
                                    } else {
                                        val f = withContext(Dispatchers.IO) {
                                            Updater.download(context, info)
                                        }
                                        if (f != null) Updater.install(context, f)
                                    }
                                }
                            }
                        }

                        is UpdateState.Failed -> StatusLine(st.message, Ink.Amber)
                        is UpdateState.Available -> {
                            StatusLine(
                                "发现新版本 v${st.info.versionName}（versionCode ${st.info.versionCode}）",
                                Ink.AccentBright,
                            )
                            if (st.info.notes.isNotBlank()) {
                                Spacer(Modifier.height(6.sdp))
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.sdp))
                                        .background(Ink.Deep)
                                        .padding(12.sdp)
                                ) {
                                    Text(
                                        st.info.notes.take(600),
                                        color = Ink.TextTertiary,
                                        fontSize = Txt.Tiny,
                                        lineHeight = 18.ssp,
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.sdp))
                            Row {
                                SmallButton("下载并安装") {
                                    scope.launch {
                                        val f = withContext(Dispatchers.IO) { Updater.download(context, st.info) }
                                        if (f != null) Updater.install(context, f)
                                    }
                                }
                            }
                        }

                        is UpdateState.Downloading -> {
                            StatusLine("正在从 ${st.from} 下载 ${(st.progress * 100).toInt()}%", Ink.AccentBright)
                            Spacer(Modifier.height(6.sdp))
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(6.sdp)
                                    .clip(RoundedCornerShape(3.sdp))
                                    .background(Ink.CardStrong)
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(st.progress.coerceIn(0f, 1f))
                                        .height(6.sdp)
                                        .background(Ink.AccentBright)
                                )
                            }
                        }

                        is UpdateState.Ready -> {
                            StatusLine("下载完成 ${st.info.sizeText}，点下面安装", Ink.Green)
                            Spacer(Modifier.height(8.sdp))
                            SmallButton("立即安装") { Updater.install(context, st.file) }
                        }

                        // ---------- 空闲：说明当前没有可下的新版 ----------
                        //
                        // 这里原来什么都不显示，于是"下载并安装"这个按钮**只在真有新版时
                        // 才会出现** —— 用户在当前版本下翻遍设置也找不到它，
                        // 反馈就是"现版本的下载与安装点选不到"。
                        //
                        // 现在 Idle 状态也给一个常驻按钮：检查完之后如果确实有新版，
                        // 就接着下载并调起安装，省得用户再点一次。
                        UpdateState.Idle -> {
                            if (checkedAndCurrent) {
                                StatusLine("已是最新版本，无需下载", Ink.Green)
                            } else {
                                StatusLine("点下面的按钮检查并下载新版本", Ink.TextTertiary)
                            }
                            Spacer(Modifier.height(8.sdp))
                            SmallButton("检查更新并下载") {
                                scope.launch {
                                    val info = runCatching {
                                        withContext(Dispatchers.IO) { Updater.check(context) }
                                    }.getOrNull()
                                    if (info == null) {
                                        checkedAndCurrent = true
                                        toast(context, "已经是最新版本")
                                    } else {
                                        checkedAndCurrent = false
                                        val f = withContext(Dispatchers.IO) {
                                            Updater.download(context, info)
                                        }
                                        if (f != null) Updater.install(context, f)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.sdp))
                    TvSwitch(
                        label = "启动时自动检查更新",
                        hint = "打开 App 后台静默检查",
                        checked = prefs.autoCheckUpdate,
                    ) { prefs.autoCheckUpdate = it }
                }
            }

            // ==================== 关于 ====================
            // ==================== 诊断（测试用） ====================
            item {
                SettingsCard(
                    title = "诊断",
                    subtitle = "测试期间用来看内存和清缓存；日常使用可以不管",
                    accent = Ink.Amber,
                ) {
                    TvSwitch(
                        label = "显示内存浮层",
                        hint = "屏幕右上角实时显示 PSS / RSS / Native / Java",
                        checked = prefs.showMemoryHud,
                    ) { prefs.showMemoryHud = it }

                    TvSwitch(
                        label = "记录内存日志",
                        hint = "每 5 秒写一条 logcat（tag=BawanMem），可事后拉完整时间线",
                        checked = prefs.logMemory,
                    ) { prefs.logMemory = it }

                    Spacer(Modifier.height(10.sdp))
                    Row {
                        SmallButton("清空直播直连缓存") {
                            com.chinut.bawantv.live.StreamCache.clearAll(context)
                            toast(context, "已清空，下次换台会重新获取直连地址")
                        }
                    }
                }
            }


            item {
                SettingsCard(
                    title = "关于焰火TV",
                    subtitle = "为电视大屏与遥控器重新设计的播放器",
                    accent = Ink.AccentBright,
                ) {
                    val about = listOf(
                        "直播" to "内置央视频道表；也可填自定义 m3u 直播源。播放中上下键换台、左右键换源",
                        "影视" to "内容全部来自低端影视，片库缓存在电视本地，断网也能浏览已缓存的片子",
                        "输入" to "电视端自带虚拟键盘，遥控器字母键可直接输入（支持拼音首字母搜索）",
                    )
                    about.forEach { (k, v) ->
                        Row(Modifier.padding(vertical = 5.sdp)) {
                            Text(k, color = Ink.AccentBright, fontSize = Txt.Caption, modifier = Modifier.width(70.sdp))
                            Text(v, color = Ink.TextTertiary, fontSize = Txt.Caption, lineHeight = 20.ssp)
                        }
                    }

                    // ---------- 鸣谢 ----------
                    //
                    // 单独一块并加了标题色，是为了让它在"关于"页面里能被一眼看到 ——
                    // 这些人是真的花了时间和设备在这上面，不该混在功能说明里。
                    Spacer(Modifier.height(14.sdp))
                    Text(
                        "鸣谢",
                        color = Ink.Amber,
                        fontSize = Txt.Label,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(6.sdp))

                    /**
                     * (名字, 贡献)。
                     *
                     * 名字按提供者给的原文**原样保留**（含特殊字符与表情），
                     * 不要做"规范化"——那是人家自己的标识。
                     */
                    val credits = listOf(
                        "™ᴰ  ⃔ ᥬ💀ᩤ  ⃕ 兔" to "提供测试环境",
                        "夙丶夜" to "提供开发建议",
                        "Bawan_xw" to "提供软件初期规则命名",
                    )
                    credits.forEach { (who, what) ->
                        Row(
                            Modifier.padding(vertical = 4.sdp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                "·",
                                color = Ink.TextFaint,
                                fontSize = Txt.Caption,
                                modifier = Modifier.width(14.sdp),
                            )
                            Text(
                                who,
                                color = Color.White,
                                fontSize = Txt.Caption,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.width(190.sdp),
                            )
                            Text(
                                what,
                                color = Ink.TextTertiary,
                                fontSize = Txt.Caption,
                                lineHeight = 20.ssp,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    Spacer(Modifier.height(14.sdp))
                    Text(
                        "免责声明：本应用只做播放器与界面聚合，不存储、不传播任何影视资源。\n" +
                            "直播源与在线影视地址均来自第三方公开接口，仅限个人学习研究使用，" +
                            "请勿用于任何商业用途。",
                        color = Ink.TextFaint,
                        fontSize = Txt.Tiny,
                        lineHeight = 18.ssp,
                    )
                }
            }

            item {
                // 兜底留白
                Box(Modifier.width(1.sdp).height(1.sdp))
            }
        }
        }
        // ---------- 家长密码弹窗 ----------
        when (pinStage) {
            PinStage.SetFirst -> ParentalPinDialog(
                title = if (ParentalControl.hasPin) "设置新的家长密码" else "设置家长密码",
                onCancel = { pinStage = null },
                verify = { input ->
                    // 第一次输入只暂存，不算"通过"，所以永远返回 false 并切到确认阶段
                    pinFirst = input
                    pinStage = PinStage.SetConfirm
                    false
                },
                onDone = { },
            )

            PinStage.SetConfirm -> ParentalPinDialog(
                title = "请再输入一次确认",
                onCancel = { pinStage = null },
                verify = { input ->
                    if (input == pinFirst) {
                        ParentalControl.setPin(input)
                        ParentalControl.setEnabled(true)
                        parentalOn = true
                        pinStage = null
                        true
                    } else {
                        // 两次不一致：回到第一步重来
                        pinFirst = ""
                        pinStage = PinStage.SetFirst
                        false
                    }
                },
                onDone = { },
            )

            PinStage.Verify -> ParentalPinDialog(
                title = if (pendingEnable) "开启未成年人保护" else "关闭未成年人保护",
                onCancel = { pinStage = null },
                verify = { input ->
                    if (ParentalControl.unlock(input)) {
                        ParentalControl.setEnabled(pendingEnable)
                        parentalOn = pendingEnable
                        pinStage = null
                        true
                    } else {
                        false
                    }
                },
                onDone = { },
            )

            null -> Unit
        }

        // ---------- 输入弹窗（TV 键盘） ----------
        editing?.let { target ->
            TvKeyboardDialog(
                title = target.title,
                initial = target.current(prefs),
                onDismiss = { editing = null },
                onConfirm = { value ->
                    target.apply(prefs, value)
                    editing = null
                },
            )
        }

    }
}

/**
 * 设置项编辑目标。
 *
 * 去掉了 `SubUrls`（TVBox 订阅地址）：影视现在只有低端影视，
 * 没有"订阅接口"这个概念了。
 */
private enum class EditTarget(val title: String) {
    Domain("低端影视域名"),
    LiveSource("直播源地址"),
    Port("调试端口"),
    Token("手机调试口令");

    fun current(prefs: com.chinut.bawantv.core.AppPrefs): String = when (this) {
        Domain -> prefs.domain
        LiveSource -> prefs.liveSourceUrl
        Port -> prefs.debugPort.toString()
        Token -> prefs.debugToken
    }

    fun apply(prefs: com.chinut.bawantv.core.AppPrefs, value: String) {
        when (this) {
            Domain -> prefs.domain = value
            LiveSource -> prefs.liveSourceUrl = value
            Port -> value.toIntOrNull()?.let { prefs.debugPort = it.coerceIn(1024, 65535) }
            Token -> prefs.debugToken = value
        }
    }
}

// ==================== 通用小组件 ====================

@Composable
private fun SettingsCard(
    title: String,
    subtitle: String,
    accent: Color,
    focusKey: Any? = null,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dim.BigRadius))
            .background(Ink.Card)
            .then(
                // 第一张卡片作为板块入口：注册一个可聚焦项，供导航栏按右键进入
                if (focusKey != null) {
                    Modifier.entryFocusable(focusKey)
                } else {
                    Modifier
                }
            )
            .padding(20.sdp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(5.sdp)
                    .height(22.sdp)
                    .background(accent, RoundedCornerShape(3.sdp))
            )
            Spacer(Modifier.width(12.sdp))
            Column {
                Text(title, color = Color.White, fontSize = Txt.Section, fontWeight = FontWeight.Bold)
                if (subtitle.isNotBlank()) {
                    Spacer(Modifier.height(2.sdp))
                    Text(subtitle, color = Ink.TextTertiary, fontSize = Txt.Tiny, lineHeight = 18.ssp)
                }
            }
        }
        Spacer(Modifier.height(14.sdp))
        content()
    }
}

@Composable
private fun KeyValueRow(label: String, value: String, onEdit: () -> Unit) {
    val f = rememberTvFocusState()
    Row(
        Modifier
            .fillMaxWidth()
            .tvFocusable(
                focusState = f,
                shape = RoundedCornerShape(12.sdp),
                focusedScale = 1.01f,
                glow = false,
                borderWidth = 2.dp,
                baseBackground = Ink.Deep,
                focusedBackground = Ink.CardStrong,
                onClick = onEdit,
            )
            .padding(horizontal = 14.sdp, vertical = 12.sdp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Ink.TextFaint, fontSize = Txt.Caption, modifier = Modifier.width(90.sdp))
        Text(
            value,
            color = if (f.focused) Color.White else Ink.TextSecondary,
            fontSize = Txt.Label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text("修改", color = Ink.AccentBright, fontSize = Txt.Tiny)
    }
}

@Composable
private fun SmallButton(
    label: String,
    focusKey: Any? = null,
    upKey: Any? = null,
    downKey: Any? = null,
    onClick: () -> Unit,
) {
    val f = rememberTvFocusState()
    Box(
        Modifier
            .height(38.sdp)
            .tvFocusable(
                focusState = f,
                focusKey = focusKey,
                upKey = upKey,
                downKey = downKey,
                // 操作类控件：实心底色 + 粗边框 + 强光晕。
                // 原来用半透明的 CardStrong/AccentSoft，两者亮度几乎一样，
                // 聚焦时看不出变化（用户："看起很诡异"）。
                action = true,
                shape = RoundedCornerShape(Dim.ChipRadius),
                focusedScale = 1.06f,
                borderWidth = 3.dp,
                baseBackground = Ink.Action,
                focusedBackground = Ink.ActionFocus,
                onClick = onClick,
            )
            .padding(horizontal = 16.sdp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (f.focused) Color.White else Ink.TextSecondary,
            fontSize = Txt.Caption,
            fontWeight = if (f.focused) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun StatusLine(text: String, color: Color) {
    Text(text, color = color, fontSize = Txt.Caption, lineHeight = 20.ssp)
}

/** 二维码面板（手机扫码入口）。 */
@Composable
private fun QrPanel(url: String, onClose: () -> Unit) {
    val bitmap = remember(url) { Qr.bitmap(url, 560) }

    // 关键：这是个全屏浮层，必须自己处理返回键，否则按返回会一路穿透到
    // 根 BackHandler（被当成"回首页"），用户看起来就像"卡在二维码里出不来"。
    BackHandler(enabled = true) { onClose() }

    // ---------- 为什么不用 Compose 的 Dialog ----------
    //
    // Dialog 会开一个**独立窗口接管按键**，MainActivity.dispatchKeyEvent 收不到 ——
    // 而本应用整套遥控器操作（方向键 / 确定键 / 返回键）全靠那个 dispatchKeyEvent
    // 转发给自定义的 TvFocusManager。按键进不来，弹窗里的控件就永远点不到。
    //
    // 更新框已经踩过这个坑（"点下载并安装没反应"），这里是同一类问题。
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .background(Ink.Sheet, RoundedCornerShape(Dim.BigRadius))
                .padding(28.sdp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("手机扫码修改设置", color = Color.White, fontSize = Txt.Section, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.sdp))
            Text(
                "手机连同一个 WiFi，扫码打开网页即可修改订阅、域名等全部设置",
                color = Ink.TextTertiary,
                fontSize = Txt.Caption,
            )
            Spacer(Modifier.height(18.sdp))
            if (bitmap != null) {
                Box(
                    Modifier
                        .size(300.sdp)
                        .background(Color.White, RoundedCornerShape(16.sdp))
                        .padding(12.sdp)
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "调试二维码",
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                Text("二维码生成失败", color = Ink.Amber, fontSize = Txt.Label)
            }
            Spacer(Modifier.height(14.sdp))
            Text(url, color = Ink.AccentBright, fontSize = Txt.Label, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(16.sdp))
            // 提示用遥控器返回键关闭（不放按钮，保持干净）
            Text(
                "按遥控器「返回」关闭",
                color = Ink.TextFaint,
                fontSize = Txt.Caption,
            )
        }
    }
}

private fun toast(context: android.content.Context, msg: String) {
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}

/** 家长密码流程的三个阶段。 */
private enum class PinStage {
    /** 首次设置：输一遍，再确认一遍。 */
    SetFirst,

    /** 首次设置的第二次确认。 */
    SetConfirm,

    /** 已有密码，改开关前校验。 */
    Verify,
}

/**
 * 设置页的「整行可聚焦 + 右侧动作按钮」行。
 *
 * # 为什么整行都要可聚焦
 *
 * 原来这种行只让右边的小按钮可聚焦，结果**方向键会跳过它** ——
 * 因为几何导航按「主轴距离 + 交叉轴错位 × 2.5」打分，
 * 而窄按钮贴右边缘时，与上下整行控件的交叉轴错位约半屏宽，
 * 乘以 2.5 后分数很大，反而选中更远但"横向对齐更好"的项。
 *
 * 整行可聚焦之后，上下的邻居都是同类几何形状，导航自然正常。
 * 这也是电视 UI 的常规做法 —— 遥控器没法精确指向一个小按钮。
 *
 * 视觉上和 [TvSwitch] 保持一致（同样的边框/底色/内边距），
 * 这样一列行看起来是统一的。
 */
@Composable
private fun TvRow(
    label: String,
    hint: String,
    actionText: String,
    hintColor: Color = Ink.TextFaint,
    /** 本行的焦点 key（供上下邻居指向它）。 */
    focusKey: Any? = null,
    /** 显式上下邻居，避免几何导航跳过这一行（见 tvFocusable 的说明）。 */
    upKey: Any? = null,
    downKey: Any? = null,
    onClick: () -> Unit,
) {
    val f = rememberTvFocusState()
    Row(
        Modifier
            .fillMaxWidth()
            .tvFocusable(
                focusState = f,
                action = true,
                focusKey = focusKey,
                upKey = upKey,
                downKey = downKey,
                shape = RoundedCornerShape(12.sdp),
                focusedScale = 1f,
                borderWidth = 3.dp,
                baseBackground = Color.Transparent,
                focusedBackground = Ink.ActionFocus,
                onClick = onClick,
            )
            .padding(horizontal = 14.sdp, vertical = 12.sdp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                color = if (f.focused) Color.White else Ink.TextSecondary,
                fontSize = Txt.Label,
            )
            if (hint.isNotBlank()) {
                Text(hint, color = hintColor, fontSize = Txt.Tiny, lineHeight = 17.ssp)
            }
        }
        Spacer(Modifier.width(14.sdp))
        // 纯视觉元素，不单独接收焦点（焦点在整行上）
        Box(
            Modifier
                .background(
                    if (f.focused) Ink.ActionFocus else Ink.Action,
                    RoundedCornerShape(Dim.ChipRadius),
                )
                .border(
                    width = 3.dp,
                    color = if (f.focused) Ink.AccentBright else Color.Transparent,
                    shape = RoundedCornerShape(Dim.ChipRadius),
                )
                .padding(horizontal = 16.sdp, vertical = 8.sdp),
        ) {
            Text(
                actionText,
                color = if (f.focused) Color.White else Ink.TextSecondary,
                fontSize = Txt.Label,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
