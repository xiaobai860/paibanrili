// app/src/main/java/com/schedulecalendar/app/widget/GlanceSessionError.kt
package com.schedulecalendar.app.widget

/**
 * Glance「会话被取消」竞态异常的判定 —— 供本包所有 Glance 组件共用。
 *
 * 背景：Glance 每次收到新的 widget 更新请求时，会取消上一轮 composition 并抛出
 * `IllegalStateException("The coroutine scope left the composition")`。
 * 这是**预期的竞态**（后续 update 会覆盖结果），并非渲染失败。
 *
 * 为什么要专门区分：把它按 E 级记录，会让 E 级失去"真错误"的含义 ——
 * 真出问题时反而被这类噪音淹没（正是「狼来了」）。
 * 因此约定：**预期竞态 → D 级**（release 下由 DIAGNOSTIC_LOGS 门控整体剔除，零开销），
 * **真正的渲染失败 → E 级**（release 下必留）。
 *
 * @see ScheduleGlanceWidget（2x1 打卡）
 * @see CalendarGlanceWidget（3x2 日历）
 * @see Calendar3x4GlanceWidget（3x4 日历）
 */
internal fun Throwable.isGlanceSessionCancelled(): Boolean =
    this is IllegalStateException &&
        message?.contains("coroutine scope left the composition", ignoreCase = true) == true
