// app/src/main/java/com/schedulecalendar/app/ui/util/LocaleUtil.kt
package com.schedulecalendar.app.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale

/**
 * 以「可观察」方式获取当前平台 Locale。
 *
 * 直接在 composable 中调用 `Locale.getDefault()`（或自行读取 `Configuration.locales`）属于
 * 非可观察读取：系统语言/区域切换时不会触发重组，本地化文本与日期格式不会刷新，
 * Android Studio 会给出告警 “Reading locale in a non-observable way in a composable function”。
 *
 * `LocalConfiguration.current` 是 Compose 提供的可观察来源，配置变化会触发重组，
 * 用它替换可消除告警并保证 locale 变化时正确刷新。
 *
 * 注意：只能在 @Composable 上下文中调用；若需在 `remember {}` 内使用，
 * 请先 `val locale = currentLocale()` 再作为 key 传入（如 `remember(locale) { ... }`）。
 */
@Composable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0]
