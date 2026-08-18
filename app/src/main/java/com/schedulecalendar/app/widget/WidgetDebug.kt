// TEMP DEBUG: 抓取 Glance 渲染异常，同时写文件 + 打 Log（logcat 一定能看到）
package com.schedulecalendar.app.widget

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.glance.layout.*
import java.io.File

fun logWidget(msg: String) {
    Log.e("WIDGET_DEBUG", msg)
    runCatching { File("/data/local/tmp/widget_log.txt").appendText("${System.currentTimeMillis()} $msg\n") }
}

fun dumpWidgetCrash(tag: String, t: Throwable) {
    val sb = StringBuilder()
    sb.append("TIME=${System.currentTimeMillis()}\n")
    sb.append("TAG=$tag\n")
    sb.append("MSG=${t.message}\n")
    sb.append("STACK:\n")
    t.stackTraceToString().lineSequence().take(60).forEach { sb.append(it).append("\n") }
    var c: Throwable? = t.cause
    var depth = 0
    while (c != null && depth < 4) {
        sb.append("CAUSE[$depth]: ${c.message}\n")
        c.stackTraceToString().lineSequence().take(20).forEach { sb.append("  ").append(it).append("\n") }
        c = c.cause
        depth++
    }
    val text = sb.toString()
    Log.e("WIDGET_DEBUG", "CRASH[$tag] $text")
    runCatching { File("/data/local/tmp", "widget_crash_$tag.txt").writeText(text) }
}

// TEMP DEBUG: content 包裹（组合期无法 try，仅作标记用）
@Composable
fun SafeContent(tag: String, content: @Composable () -> Unit) {
    content()
}
