---
kind: error_handling
name: Android 应用错误处理模式：try-catch 静默降级 + ViewModel 事件上报
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
    - app/src/main/java/com/schedulecalendar/app/MainActivity.kt
---

## 1. 采用的错误处理体系

该 Android 单模块应用未引入统一的异常类型或错误码框架，而是采用**分层混合策略**：

- **数据层（Repository）**：对系统 API（CalendarProvider、AccountManager）调用使用 `try { ... } catch (e: Exception) { 返回默认值 }` 的静默降级模式，失败时返回 null / -1 / false / emptyList，不向上抛出。
- **业务/备份层**：在 `BackupManager` 中使用 `runCatching { ... }` 包装 I/O 操作，对外暴露 `Result<File>`；恢复入口则通过 `throw IllegalArgumentException(...)` 显式抛出可识别的业务异常。
- **UI 层（ViewModel）**：定义每个功能域专属的 `sealed class XxxUiEvent`，其中统一包含 `ShowError(val msg: String)` 子类型，用于向 Compose UI 层传递用户可读的错误消息。
- **顶层入口（MainActivity）**：在启动流程中用 try-catch 包裹权限检查等关键逻辑，捕获后直接忽略，保证应用能继续运行。

## 2. 关键文件与位置

| 层次 | 文件 | 角色 |
|---|---|---|
| 数据层 | `data/calendar/CalendarEventRepository.kt` | 大量 `try/catch(Exception)` 包裹 CalendarProvider 调用，失败返回空/默认值 |
| 备份层 | `ui/settings/BackupManager.kt` | `runCatching` 返回 `Result<File>`；恢复方法抛 `IllegalArgumentException` |
| UI 事件 | `ui/calendar/CalendarViewModel.kt` | `sealed class CalendarUiEvent` 含 `ShowError(msg)` |
| UI 事件 | `ui/detail/DetailViewModels.kt` | `ScheduleDetailUiEvent` / `ExtraItemsUiEvent` 均含 `ShowError` |
| UI 事件 | `ui/hours/HoursViewModel.kt` | `HoursUiEvent.ShowError(message)` |
| UI 事件 | `ui/salary/SalaryViewModel.kt` | `SalaryUiEvent.ShowError(message)` |
| UI 事件 | `ui/settings/SettingsViewModel.kt` / `StorageViewModel.kt` | 各自 UiEvent 含 `ShowError` |
| UI 事件 | `ui/shifts/ShiftsViewModel.kt` | `ShiftsUiEvent` / `ShiftEditorUiEvent` 含 `ShowError` |
| 顶层容错 | `MainActivity.kt` | 启动期权限检查 try-catch 兜底 |

## 3. 架构约定与设计决策

1. **数据层“静默失败”原则**  
   `CalendarEventRepository` 中几乎所有系统调用都被 `try { ... } catch (e: Exception) { return null/-1/false/emptyList }` 包裹，目的是让上层业务不受系统日历权限、ROM 差异等不稳定因素干扰。例如 `createEvent` 失败返回 `-1L`，`getOrCreateLocalCalendarId` 失败返回 `null`。

2. **备份操作使用 Kotlin Result**  
   `BackupManager.createAppDataBackup()` / `createShiftConfigBackup()` 以 `suspend fun createXxx(): Result<File>` 形式暴露，调用方可选择 `.onSuccess` / `.onFailure` 分支处理；而 `restoreFromJson` 等破坏性操作则直接抛 `IllegalArgumentException`，由调用方决定是否需要捕获。

3. **UI 层统一通过 sealed event 上报错误**  
   每个 ViewModel 都定义自己的 `sealed class XxxUiEvent`，并强制包含 `ShowError(val msg: String)` 变体。UI 侧收集 `Channel<XxxUiEvent>` 后根据类型分发：`ShowMessage` 显示 Toast，`ShowError` 弹出对话框或红色提示。

4. **无全局错误中间件**  
   未发现 Hilt 提供的拦截器、Retrofit ErrorInterceptor、或自定义 `CoroutineExceptionHandler`。错误传播路径是：Repository → ViewModel → Channel → Compose State。

5. **无 panic/recover 模式**  
   代码中未见 `try { ... } finally { recover {} }` 或 `Thread.setDefaultUncaughtExceptionHandler` 的使用，崩溃由系统默认行为处理。

## 4. 开发者应遵循的规则

- **数据层**：对不可控的外部 API（CalendarProvider、ContentResolver、文件系统）一律用 `try/catch(Exception)` 包裹，失败时返回安全的默认值，不要向上抛出原始异常。
- **备份/导入导出**：幂等读取类接口返回 `Result<T>`；会修改数据的恢复接口抛 `IllegalArgumentException`，并在注释中说明触发条件。
- **ViewModel**：新增功能时必须定义对应的 `sealed class XxxUiEvent`，并将所有用户可见的错误信息封装为 `ShowError` 事件发送，禁止在 ViewModel 内直接调用 `Toast` 或 `Snackbar`。
- **UI 层**：仅消费 `ShowError` 事件进行展示，不得自行构造错误消息字符串绕过 ViewModel。
- **避免吞掉所有异常**：虽然 Repository 层倾向于静默失败，但应在关键路径（如创建本地日历账户）保留日志输出，便于定位 ROM 兼容问题。
