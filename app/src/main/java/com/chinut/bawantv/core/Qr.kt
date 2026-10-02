package com.chinut.bawantv.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 二维码生成 + 局域网地址获取。
 *
 * 用途：TV 上输入长文本（订阅地址）极度痛苦，所以设置页给一个二维码，
 * 手机扫码就能打开手机端网页去改这些设置。
 */
object Qr {

    /** 生成二维码位图。失败返回 null。 */
    fun bitmap(text: String, sizePx: Int = 512): Bitmap? = runCatching {
        val hints = hashMapOf<EncodeHintType, Any>(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val offset = y * w
            for (x in 0 until w) {
                pixels[offset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }.getOrNull()

    /**
     * 本机在局域网里的 IPv4 地址（优先 wlan0 / eth0）。
     * TV 盒子和手机在同一个路由器下时，这个地址就是手机能访问的地址。
     */
    fun lanIp(context: Context): String {
        // 1) 遍历网卡，跳过回环和未启用的
        runCatching {
            val candidates = ArrayList<Pair<String, String>>()
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { nif ->
                if (!nif.isUp || nif.isLoopback) return@forEach
                val name = nif.name.lowercase()
                nif.inetAddresses?.toList()?.forEach { addr ->
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: return@forEach
                        if (host.startsWith("169.254.")) return@forEach
                        candidates.add(name to host)
                    }
                }
            }
            candidates.firstOrNull { it.first.startsWith("wlan") }?.let { return it.second }
            candidates.firstOrNull { it.first.startsWith("eth") }?.let { return it.second }
            candidates.firstOrNull()?.let { return it.second }
        }
        return "127.0.0.1"
    }

    /** 手机扫码后应该打开的调试页地址。 */
    fun debugUrl(context: Context, port: Int): String =
        "http://${lanIp(context)}:$port/"
}
