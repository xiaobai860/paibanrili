// app/src/main/java/com/schedulecalendar/app/ui/theme/Theme.kt
package com.schedulecalendar.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode

@Composable
fun ScheduleCalendarTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,   // Android 12+ 动态取色
    content: @Composable () -> Unit
) {
    // ═══ Preview / 无 Activity Context 环境使用 Material 默认色板 ═══
    // dynamicColorScheme 需要真实 Context，在 IDE 预览中会崩溃，故用默认 scheme 兜底
    if (LocalInspectionMode.current) {
        val colors = if (darkTheme) darkColorScheme() else lightColorScheme()
        MaterialTheme(colorScheme = colors, typography = Typography, content = content)
        return
    }

    val colors = when {
        // 本项目 minSdk = 34（Android 14），动态取色在运行时始终可用
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        // dynamicColor=false 时的兜底（运行时几乎不会走到）
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, typography = Typography, content = content)
}
