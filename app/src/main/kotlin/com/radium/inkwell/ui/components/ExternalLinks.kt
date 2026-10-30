package com.radium.inkwell.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/**
 * 用外部应用（通常是浏览器）打开链接；返回有没有真的拉起来。拉不起来时由调用方告诉用户，
 * 文案统一用 [NO_BROWSER_MESSAGE]。
 *
 * 不能裸调 `startActivity(ACTION_VIEW)`：设备上没有能处理链接的应用（没装浏览器、被停用、
 * 被家长控制藏起来）时它抛 [ActivityNotFoundException]，整个 App 当场崩 —— 而点的只是
 * 「开源地址」「查看进展」这种无关紧要的链接。个别 ROM 上目标 Activity 不让外部调起，
 * 抛的是 [SecurityException]，同样接住。
 */
fun Context.openExternalLink(url: String): Boolean {
    if (url.isBlank()) return false
    return try {
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

/** [openExternalLink] 拉不起应用时给用户的那句话 */
const val NO_BROWSER_MESSAGE = "没有可以打开链接的应用，请先安装或启用浏览器"
