// app/src/main/java/com/schedulecalendar/app/widget/WidgetPinHelper.kt
package com.schedulecalendar.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * 一键把指定 Glance 小组件钉到桌面。
 *
 * 原理：Android 8.0+（API 26，本应用 minSdk=34 完全支持）官方提供的
 * [AppWidgetManager.requestPinAppWidget]，调用后系统弹出「添加到主屏幕？」确认框，
 * 用户确认即直接钉到桌面。无需任何权限、不需要当 widget host，也不必去桌面翻小组件列表。
 *
 * 注意：本应用所有 widget 都没有 android:configure（之前为规避部分 ROM 的
 * 「载入窗口小部件时出现问题」已移除），因此 pin 后以默认样式上桌，
 * 样式调整仍走「小部件设置」内的配置入口（按 widget 类型全局共享）。
 */
object WidgetPinHelper {

    /** 当前桌面是否支持一键钉 widget（极少数第三方桌面不支持） */
    fun canPin(context: Context): Boolean =
        AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported

    /**
     * 请求把 [receiverClass] 对应的小组件钉到桌面。
     * 不支持时给出兜底提示（引导用户去桌面长按添加）。
     */
    fun pin(context: Context, receiverClass: Class<out GlanceAppWidgetReceiver>) {
        val awm = AppWidgetManager.getInstance(context)
        if (!awm.isRequestPinAppWidgetSupported) {
            Toast.makeText(
                context,
                "当前桌面不支持一键添加，请长按桌面空白处 → 小组件 → 选择「排班日历」或「快捷打卡」",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val component = ComponentName(context, receiverClass)
        val successIntent = Intent(context, WidgetPinnedReceiver::class.java).apply {
            action = ACTION_WIDGET_PINNED
        }
        val successCallback = PendingIntent.getBroadcast(
            context,
            0,
            successIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        awm.requestPinAppWidget(component, null, successCallback)
    }

    private const val ACTION_WIDGET_PINNED = "com.schedulecalendar.app.action.WIDGET_PINNED"
}
