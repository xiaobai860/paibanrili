// app/src/main/java/com/schedulecalendar/app/ui/theme/Theme.kt
package com.schedulecalendar.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode

private val LightColors = lightColorScheme(
    primary          = Green700,
    onPrimary        = White,
    primaryContainer = Green100,
    onPrimaryContainer = Gray900,
    secondary        = Blue600,
    onSecondary      = White,
    secondaryContainer = Blue100,
    background       = Gray50,
    onBackground     = Gray900,
    surface          = White,
    onSurface        = Gray900,
    surfaceVariant   = Gray100,
    onSurfaceVariant = Gray500,
    outline          = Gray300,
    error            = RedError,
    onError          = White
)

private val DarkColors = darkColorScheme(
    primary          = Green600,
    onPrimary        = White,
    primaryContainer = Color(0xFF064E3B),
    onPrimaryContainer = Green100,
    secondary        = Blue600,
    onSecondary      = White,
    background       = Color(0xFF111827),
    onBackground     = Color(0xFFF9FAFB),
    surface          = Color(0xFF1F2937),
    onSurface        = Color(0xFFF9FAFB),
    surfaceVariant   = Color(0xFF374151),
    onSurfaceVariant = Gray300,
    outline          = Color(0xFF4B5563),
    error            = Color(0xFFF87171),
    onError          = White
)

@Composable
fun ScheduleCalendarTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,   // Android 12+ 动态取色
    content: @Composable () -> Unit
) {
    // ═══ Preview 模式使用静态色板 ═══
    // 避免 dynamicColorScheme 在无 Activity Context 的预览环境中崩溃
    if (LocalInspectionMode.current) {
        val colors = if (darkTheme) DarkColors else LightColors
        MaterialTheme(colorScheme = colors, typography = Typography, content = content)
        return
    }

    val colors = when {
        // Android 12+ 使用系统壁纸动态取色（Material You）
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        // 旧设备使用自定义静态色板
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, typography = Typography, content = content)
}
