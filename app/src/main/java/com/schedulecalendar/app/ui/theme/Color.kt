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

// 业务语义色（补贴/扣款/分类标签）
val AllowanceGreen = Color(0xFF059669)   // 补贴/确认绿色
val DeductionRed   = Color(0xFFDC2626)   // 扣款红色
val CategoryGreen  = Color(0xFF059669)   // 节气分类
val CategoryOrange = Color(0xFFD97706)   // 传统分类
val CategoryBlue   = Color(0xFF2563EB)   // 国际分类

// 状态角标语义色
val EarlyLeaveOrange = Color(0xFFF97316) // 早退角标
val RemarkCyan       = Color(0xFF06B6D4) // 备注角标

// 班次预设颜色（12色，2行x6列）—— 高区分度配色方案（互相差异尽量大，方便区分）
val ShiftPresetColors = listOf(
    "#DC2626", // 红
    "#EA580C", // 橙
    "#CA8A04", // 黄
    "#16A34A", // 绿
    "#0D9488", // 青绿
    "#0891B2", // 青
    "#2563EB", // 蓝
    "#4F46E5", // 靛
    "#7C3AED", // 紫
    "#DB2777", // 粉
    "#92400E", // 棕
    "#64748B"  // 岩灰
)
