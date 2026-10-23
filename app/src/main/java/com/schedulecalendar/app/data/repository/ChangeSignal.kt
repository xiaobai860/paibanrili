package com.schedulecalendar.app.data.repository

import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * 变更信号统一发送方法。
 *
 * 5 个 Repository 都有同构的 `_changeSignal` + `notifyChanged()`，
 * tryEmit 失败（无 collector / 缓冲满）时必须留日志，
 * 否则「数据已改但界面没刷新」这类问题完全无从排查。
 */
internal fun MutableSharedFlow<Unit>.notifyChangedOrLog(tag: String) {
    if (!tryEmit(Unit)) {
        android.util.Log.w(tag, "changeSignal emit dropped")
    }
}
