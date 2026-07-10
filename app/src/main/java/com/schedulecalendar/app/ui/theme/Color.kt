// app/src/main/java/com/schedulecalendar/app/ui/theme/Color.kt
package com.schedulecalendar.app.ui.theme

import androidx.compose.ui.graphics.Color

// 主色调：绿色系
val Green700   = Color(0xFF059669)
val Green600   = Color(0xFF10B981)
val Green100   = Color(0xFFD1FAE5)
val Green50    = Color(0xFFECFDF5)

// 辅色：蓝色
val Blue600    = Color(0xFF1677FF)
val Blue100    = Color(0xFFDBEAFE)

// 中性色
val Gray900    = Color(0xFF111827)
val Gray700    = Color(0xFF374151)
val Gray500    = Color(0xFF6B7280)
val Gray300    = Color(0xFFD1D5DB)
val Gray100    = Color(0xFFF3F4F6)
val Gray50     = Color(0xFFF9FAFB)

// 语义色
val RedError   = Color(0xFFEF4444)
val OrangeWarn = Color(0xFFF59E0B)
val White      = Color(0xFFFFFFFF)
val Black      = Color(0xFF000000)

// 节假日/休息
val HolidayRed = Color(0xFFDC2626)
val RestGray   = Color(0xFF9CA3AF)

// 班次预设颜色（18色，2行x9列）—— 高区分度配色方案
val ShiftPresetColors = listOf(
    // 第一行：高饱和鲜艳色（暖冷交替）
    "#059669", "#2563EB", "#DC2626", "#D97706", "#7C3AED",
    "#DB2777", "#0891B2", "#65A30D", "#EA580C",
    // 第二行：低饱和/深色/中性（避免与第一行相邻色接近）
    "#4338CA", "#0D9488", "#0F766E", "#C026D3", "#0284C7",
    "#A3E635", "#F43F5E", "#78716C", "#6B7280"
)
