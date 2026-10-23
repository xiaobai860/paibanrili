package com.schedulecalendar.app.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import java.time.LocalDate

/**
 * 记忆化的「今天」，并且能跨天自动更新。
 *
 * 直接写 `LocalDate.now()` 会在每次重组都取一次（开销其实很小，不是性能问题）；
 * 但简单写成 `remember { LocalDate.now() }` 会引入真实 bug：页面长时间停留跨过 0 点、
 * 或应用在后台跨天后切回前台时，日期不再变化，「今天」高亮/倒计时/≤ 今天的统计全部停在昨天。
 *
 * 因此这里做双保险刷新：
 * 1. 每分钟比对一次（覆盖页面停留跨零点）；
 * 2. 页面回到前台时立即比对（后台协程可能被系统暂停，靠定时器不一定可靠）。
 */
@Composable
fun rememberToday(): LocalDate {
    var today by remember { mutableStateOf(LocalDate.now()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(TODAY_REFRESH_INTERVAL_MS)
            val now = LocalDate.now()
            if (now != today) today = now
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val now = LocalDate.now()
                if (now != today) today = now
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return today
}

/** 「今天」比对的间隔：1 分钟足够覆盖跨零点，又不会有明显开销 */
private const val TODAY_REFRESH_INTERVAL_MS = 60_000L
