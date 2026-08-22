// app/src/main/java/com/schedulecalendar/app/widget/WidgetIcon.kt
package com.schedulecalendar.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider

/**
 * 以矢量 drawable 渲染小组件图标，并按主题着色。
 * 相比直接用文字字形（⚙ / ↻），矢量图标包围盒对称、基线与字号无关，
 * 可保证「设置」与「刷新」两个图标始终在同一水平线上。
 */
@Composable
fun widgetIcon(
    context: Context,
    @DrawableRes resId: Int,
    color: Color,
    modifier: GlanceModifier
) {
    val drawable = ContextCompat.getDrawable(context, resId)
    if (drawable == null) {
        Image(provider = ImageProvider(resId), contentDescription = null, modifier = modifier)
        return
    }
    val size = drawable.intrinsicWidth.coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    drawable.setBounds(0, 0, size, size)
    val argb = android.graphics.Color.argb(
        (color.alpha * 255).toInt().coerceIn(0, 255),
        (color.red * 255).toInt().coerceIn(0, 255),
        (color.green * 255).toInt().coerceIn(0, 255),
        (color.blue * 255).toInt().coerceIn(0, 255)
    )
    drawable.colorFilter = PorterDuffColorFilter(argb, PorterDuff.Mode.SRC_IN)
    drawable.draw(canvas)
    Image(provider = ImageProvider(bmp), contentDescription = null, modifier = modifier)
}
