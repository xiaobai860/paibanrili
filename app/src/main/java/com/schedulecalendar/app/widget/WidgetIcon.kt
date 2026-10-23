// app/src/main/java/com/schedulecalendar/app/widget/WidgetIcon.kt
package com.schedulecalendar.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider

/** 位图边长上限：防止异常矢量尺寸导致过大分配（小组件图标实际只需几十 px） */
private const val MAX_ICON_BITMAP_SIZE = 128

/**
 * 以矢量 drawable 渲染小组件图标，并按主题着色。
 * 相比直接用文字字形（⚙ / ↻），矢量图标包围盒对称、基线与字号无关，
 * 可保证「设置」与「刷新」两个图标始终在同一水平线上。
 *
 * 稳定性：矢量解析/位图分配失败时回退到 [ImageProvider] 资源形式，
 * 不让组合期异常影响整个小组件渲染。
 */
@Composable
fun widgetIcon(
    context: Context,
    @DrawableRes resId: Int,
    color: Color,
    modifier: GlanceModifier
) {
    val bmp = runCatching {
        val drawable = ContextCompat.getDrawable(context, resId) ?: return@runCatching null
        val size = drawable.intrinsicWidth
            .coerceIn(1, MAX_ICON_BITMAP_SIZE)
            .coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        val argb = android.graphics.Color.argb(
            (color.alpha * 255).toInt().coerceIn(0, 255),
            (color.red * 255).toInt().coerceIn(0, 255),
            (color.green * 255).toInt().coerceIn(0, 255),
            (color.blue * 255).toInt().coerceIn(0, 255)
        )
        drawable.colorFilter = PorterDuffColorFilter(argb, PorterDuff.Mode.SRC_IN)
        drawable.draw(canvas)
        bitmap
    }.onFailure { Log.w("widgetIcon", "render icon failed, fallback to resource", it) }.getOrNull()

    Image(
        provider = bmp?.let { ImageProvider(it) } ?: ImageProvider(resId),
        contentDescription = null,
        modifier = modifier
    )
}
