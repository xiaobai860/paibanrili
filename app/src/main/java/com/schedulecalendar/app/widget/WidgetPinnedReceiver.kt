// app/src/main/java/com/schedulecalendar/app/widget/WidgetPinnedReceiver.kt
package com.schedulecalendar.app.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * [WidgetPinHelper.pin] 成功后由系统经 PendingIntent 触发的广播，
 * 仅用于给出「已添加到桌面」的正向反馈（不处理任何业务逻辑）。
 */
class WidgetPinnedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == WidgetPinHelper_ACTION) {
            Toast.makeText(context, "已添加到桌面", Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        const val WidgetPinHelper_ACTION = "com.schedulecalendar.app.action.WIDGET_PINNED"
    }
}
