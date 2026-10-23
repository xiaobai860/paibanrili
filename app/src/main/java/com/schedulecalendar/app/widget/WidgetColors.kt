package com.schedulecalendar.app.widget

import androidx.compose.ui.graphics.Color

/**
 * 小组件颜色工具（3x3/3x4 排班组件与日历组件共用）。
 *
 * 颜色串来自用户配置/备份，可能是 `#RRGGBB`、`#AARRGGBB` 甚至非法值，
 * 解析失败必须回退到传入的兜底色，绝不能让 Glance 渲染阶段抛异常。
 */
internal fun hexToWidgetColor(hex: String, fallback: Color): Color {
    return runCatching {
        val h = hex.removePrefix("#")
        val a = if (h.length == 8) h.substring(0, 2).toInt(16) / 255f else 1f
        val r = h.substring(h.length - 6, h.length - 4).toInt(16) / 255f
        val g = h.substring(h.length - 4, h.length - 2).toInt(16) / 255f
        val b = h.substring(h.length - 2).toInt(16) / 255f
        Color(r, g, b, a)
    }.getOrElse { fallback }
}
