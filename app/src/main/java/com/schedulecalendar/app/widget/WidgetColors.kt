package com.schedulecalendar.app.widget

import androidx.compose.ui.graphics.Color
import com.schedulecalendar.app.domain.util.hexToColorSafe

/**
 * 小组件颜色工具（3x3/3x4 排班组件与日历组件共用）。
 *
 * 颜色串来自用户配置/备份，可能是 `#RRGGBB`、`#AARRGGBB` 甚至非法值，
 * 解析失败必须回退到传入的兜底色，绝不能让 Glance 渲染阶段抛异常。
 */
internal fun hexToWidgetColor(hex: String, fallback: Color): Color =
    hexToColorSafe(hex, fallback)
