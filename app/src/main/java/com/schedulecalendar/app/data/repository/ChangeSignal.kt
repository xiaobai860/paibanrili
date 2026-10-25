package com.schedulecalendar.app.data.repository

import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * 变更信号统一发送方法。
 *
 * 5 个 Repository 都有同构的 `_changeSignal` + `notifyChanged()`，
 * tryEmit 失败（无 collector / 缓冲满）时必须留日志，
 * 否则「数据已改但界面没刷新」这类问题完全无从排查。
 *
 * ⚠️ 这里**只负责发信号，不打日志**，也**刻意不接受 TAG 参数**。
 * 参数化 TAG 会让日志来源绕开 `LogTags` 规范与守护测试
 * （2026-10-26 复审发现：5 个 Repository 曾在这里传 "ShiftRepo" / "ExtraItemRepo"
 * 等硬编码字符串，既不在 `LogTags` 里、值也不等于类名）。
 * 现在改由各 Repository 用自己的 `LogTags.XXX` 常量打日志 → 守护测试可完整覆盖。
 */
internal fun MutableSharedFlow<Unit>.tryNotifyChanged(): Boolean = tryEmit(Unit)
