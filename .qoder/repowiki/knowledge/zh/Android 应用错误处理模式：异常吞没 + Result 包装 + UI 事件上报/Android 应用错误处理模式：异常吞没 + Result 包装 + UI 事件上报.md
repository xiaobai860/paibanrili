---
kind: error_handling
name: Android 应用错误处理模式：异常吞没 + Result 包装 + UI 事件上报
category: error_handling
scope:
    - '**'
source_files:
    - app/src/main/java/com/schedulecalendar/app/data/calendar/CalendarEventRepository.kt
    - app/src/main/java/com/schedulecalendar/app/ui/settings/BackupManager.kt
    - app/src/main/java/com/schedulecalendar/app/ui/calendar/CalendarViewModel.kt
    - app/src/main/java/com/schedulecalendar/app/ui/detail/DetailViewModels.kt
    - app/src/main/java/com/schedulecalendar/app/ui/hours/HoursViewModel.kt
    - app/src/main/java/com/schedulecalendar/app/ui/salary/SalaryViewModel.kt
    - app/src/main/java/com/schedulecalendar/app/ui/settings/SettingsViewModel.kt
    - app/src/main/java/com/schedulecalendar/app/ui/settings/StorageViewModel.kt
    - app/src/main/java/com/schedulecalendar/app/ui/shifts/ShiftsViewModel.kt
---

本仓库采用**分层混合策略**处理错误，没有统一的错误类型体系或全局中间件，而是根据调用位置选择不同方式：

## 1. 数据层：try-catch 吞没 + Log.e 记录
`CalendarEventRepository` 对 Android Calendar Provider 的 I/O 操作广泛使用 `try { ... } catch (e: Exception) { Log.e(..., e); return null }` 模式，将异常直接吞掉并返回空值。这种方式保证上层不会崩溃，但丢失了具体错误信息。

## 2. 业务层：`runCatching` + `Result<T>` 包装
`BackupManager` 中的备份相关方法（如 `createAppDataBackup()`、`autoBackupAppData()`）统一使用 `runCatching { ... }` 包裹，返回 `Result<File>` 给调用方。这是仓库中最规范的错误传播方式。

## 3. 参数校验：抛出标准异常
`BackupManager` 在文件路径解析、JSON 解析等场景直接 `throw IllegalStateException` / `IllegalArgumentException`，由上层协程捕获。

## 4. UI 层：sealed class 事件上报
每个 ViewModel 定义自己的 `UiEvent` sealed class，包含 `ShowError(val msg: String)` 变体，通过 `Channel<UiEvent>` 向 Compose UI 发送错误消息。例如：
- `CalendarUiEvent.ShowError`
- `ScheduleDetailUiEvent.ShowError`  
- `HoursUiEvent.ShowError`
- `SettingsUiEvent.ShowError`
- `StorageUiEvent.ShowError`
- `ShiftsUiEvent.ShowError`

UI 侧收集这些事件后弹出 Toast 或 Snackbar 提示用户。

## 5. 日志系统：原生 android.util.Log
全仓使用 `android.util.Log.v/i/d/w/e` 进行调试输出，按模块划分 Tag（如 `CalendarEventRepo`、`ReminderScheduler`），但没有结构化日志框架。

## 6. 缺失的部分
- 无自定义 `Exception` 类或 `Error` 枚举
- 无全局异常处理器（未覆盖 `UncaughtExceptionHandler`）
- 无统一的错误码/错误消息资源管理
- 部分 catch 块使用 `_` 忽略异常（如 `catch (_: Exception) {}`），可能掩盖问题

## 开发者约定
- 对外暴露的方法优先返回 `Result<T>` 而非抛异常
- UI 交互错误通过 `ShowError` 事件上报，不在 ViewModel 中直接弹 Toast
- 底层 I/O 失败时记录 `Log.e` 并返回安全默认值（null/空集合）
- 参数校验失败直接抛 `IllegalStateException`/`IllegalArgumentException`