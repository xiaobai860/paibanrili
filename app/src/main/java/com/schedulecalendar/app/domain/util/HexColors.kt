package com.schedulecalendar.app.domain.util

import androidx.compose.ui.graphics.Color

/**
 * 十六进制颜色安全解析（支持 `#RRGGBB` 与 `#AARRGGBB`）。
 *
 * 项目里此前有 3 份几乎相同的实现（班次圆点、颜色选择器、小组件颜色），
 * 连兜底色都不一样（0xFF059669 / Color.Gray / 各自传入），
 * 统一后兜底色由调用方决定，避免「同一个颜色在不同页面显示不同」。
 */
fun hexToColorSafe(hex: String, fallback: Color): Color = runCatching {
    val h = hex.removePrefix("#")
    val a = if (h.length == 8) h.substring(0, 2).toInt(16) / 255f else 1f
    val r = h.substring(h.length - 6, h.length - 4).toInt(16) / 255f
    val g = h.substring(h.length - 4, h.length - 2).toInt(16) / 255f
    val b = h.substring(h.length - 2).toInt(16) / 255f
    Color(r, g, b, a)
}.getOrElse { fallback }
