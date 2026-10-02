package com.chinut.bawantv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.chinut.bawantv.core.UpdateInfo
import com.chinut.bawantv.core.Updater
import com.chinut.bawantv.core.UpdateState
import com.chinut.bawantv.ui.theme.Dim
import com.chinut.bawantv.ui.theme.Ink
import com.chinut.bawantv.ui.theme.rememberTvFocusState
import com.chinut.bawantv.ui.theme.sdp
import com.chinut.bawantv.ui.theme.ssp
import com.chinut.bawantv.ui.theme.tvFocusable
import com.chinut.bawantv.ui.theme.Txt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 更新提示弹窗：遥控器可以直接「下载并安装」。
 * 下载完成后调系统安装器（TV 端标准做法）。
 */
@Composable
fun UpdateDialog(
    info: UpdateInfo,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by Updater.state.collectAsState()
    val firstFocus = remember { FocusRequester() }

    // 弹窗自己吃掉返回键：否则返回会穿透到根 BackHandler 被当成「回首页」，
    // 而弹窗还挂在上面，看起来就像卡住了。
    BackHandler(enabled = true) { onDismiss() }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(760.sdp)
                .background(Ink.Sheet, RoundedCornerShape(Dim.BigRadius))
                .padding(26.sdp),
        ) {
            Text(
                "发现新版本",
                color = Color.White,
                fontSize = Txt.Title,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.sdp))
            Text(
                "v${info.versionName}  ·  ${info.sizeText}  ·  发布源 ${info.apkSources.firstOrNull()?.name.orEmpty()}",
                color = Ink.AccentBright,
                fontSize = Txt.Label,
            )
            Spacer(Modifier.height(14.sdp))

            if (info.notes.isNotBlank()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(200.sdp)
                        .background(Ink.Deep, RoundedCornerShape(12.sdp))
                        .padding(14.sdp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        info.notes,
                        color = Ink.TextSecondary,
                        fontSize = Txt.Caption,
                        lineHeight = 20.ssp,
                    )
                }
                Spacer(Modifier.height(16.sdp))
            }

            when (val st = state) {
                is UpdateState.Downloading -> {
                    Text(
                        "正在从 ${st.from} 下载 ${(st.progress * 100).toInt()}%",
                        color = Ink.AccentBright,
                        fontSize = Txt.Label,
                    )
                    Spacer(Modifier.height(8.sdp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(6.sdp)
                            .background(Ink.CardStrong, RoundedCornerShape(3.sdp))
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(st.progress.coerceIn(0f, 1f))
                                .height(6.sdp)
                                .background(Ink.AccentBright)
                        )
                    }
                    Spacer(Modifier.height(16.sdp))
                }

                is UpdateState.Ready -> {
                    Text(
                        "下载完成，点「立即安装」调起系统安装界面",
                        color = Ink.Green,
                        fontSize = Txt.Label,
                    )
                    Spacer(Modifier.height(16.sdp))
                }

                is UpdateState.Failed -> {
                    Text(st.message, color = Ink.Amber, fontSize = Txt.Label)
                    Spacer(Modifier.height(16.sdp))
                }

                else -> Unit
            }

            Row(horizontalArrangement = Arrangement.spacedBy(14.sdp)) {
                when (val st = state) {
                    is UpdateState.Ready -> {
                        DialogButton("立即安装", primary = true, focusRequester = firstFocus) {
                            Updater.install(context, st.file)
                        }
                    }

                    is UpdateState.Downloading -> Unit

                    else -> {
                        DialogButton("下载并安装", primary = true, focusRequester = firstFocus) {
                            scope.launch {
                                val f = withContext(Dispatchers.IO) { Updater.download(context, info) }
                                if (f != null) Updater.install(context, f)
                            }
                        }
                    }
                }
                DialogButton("以后再说", primary = false) {
                    Updater.reset()
                    onDismiss()
                }
            }

            Spacer(Modifier.height(10.sdp))
            Text(
                "提示：如果安装被系统拦截，请在「设置 → 应用 → 焰火TV → 安装未知应用」里放行",
                color = Ink.TextFaint,
                fontSize = Txt.Tiny,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
}

@Composable
private fun DialogButton(
    label: String,
    primary: Boolean,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    val f = rememberTvFocusState()
    var mod = Modifier
        .height(50.sdp)
        .tvFocusable(
            focusState = f,
            shape = RoundedCornerShape(25.sdp),
            focusedScale = 1.05f,
            borderWidth = 3.dp,
            baseBackground = if (primary) Ink.Accent.copy(alpha = 0.9f) else Ink.Card,
            focusedBackground = if (primary) Ink.AccentBright else Ink.CardStrong,
            onClick = onClick,
        )
    if (focusRequester != null) mod = mod.focusRequester(focusRequester)

    Box(
        mod.padding(horizontal = 26.sdp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (primary) Ink.Deep else Ink.TextSecondary,
            fontSize = Txt.Body,
            fontWeight = FontWeight.Bold,
        )
    }
}
