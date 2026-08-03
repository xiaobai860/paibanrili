---
kind: error_handling
name: 错误处理策略：局部 try-catch 兜底 + ViewModel 事件通道 + Result 包装
category: error_handling
scope:
    - '**'
source_files:
    - app/src/main/java/com/schedulecalendar/app/data/calendar/CalendarEventRepository.kt
    - app/src/main/java/com/schedulecalendar/app/ui/calendar/CalendarViewModel.kt
    - app/src/main/java/com/schedulecalendar/app/ui/settings/BackupManager.kt
    - app/src/main/java/com/schedulecalendar/app/data/repository/Mappers.kt
    - app/src/main/java/com/schedulecalendar/app/MainActivity.kt
---

本仓库未定义统一的异常类型或全局错误码，而是采用分层、分散的错误处理方式：底层 I/O 操作通过 try-catch 吞掉异常并返回默认值，业务层使用 Kotlin `Result` 与协程 `runCatching` 包装失败路径，UI 层通过每个 ViewModel 内部的 sealed class 事件通道（如 `CalendarUiEvent.ShowError`）向 Compose 界面推送错误消息。具体模式如下：

1. **数据访问层（Repository/Provider）**：以 `CalendarEventRepository.kt` 为代表，所有对系统 Calendar Provider 的查询/插入/更新/删除均包裹在 `try { ... } catch (e: Exception) { null / -1L / false }` 中，将异常静默降级为安全默认值（null、-1、空列表），保证 UI 不因第三方系统调用失败而崩溃。

2. **映射与解析层**：`Mappers.kt` 中使用 `runCatching { ScheduleType.valueOf(type) }.getOrDefault(...)` 和 `catch (_: Exception) { null }` 做容错解析，确保旧版 JSON 字段损坏时不中断应用。

3. **备份模块**：`BackupManager.kt` 同时使用两种风格——对关键文件读写抛出 `IllegalStateException("无法读取备份文件")` 等明确异常，由调用方用 `runCatching` 捕获；对自动备份流程整体用 `runCatching { ... }` 包裹，失败时静默忽略。

4. **ViewModel 层**：每个 ViewModel 定义一个 `sealed class XxxUiEvent`，其中统一包含 `ShowError(val msg: String)` 分支。异步操作通过 `.onFailure { _uiEvent.send(XxxUiEvent.ShowError(it.message)) }` 将错误推送到 Channel，再由 Compose Screen 消费并显示 Toast/Dialog。

5. **入口层**：`MainActivity.kt` 对反射 API（API 34+ OnBackInvokedDispatcher）使用 try-catch 包裹，失败时仅记录 Log 警告，不影响主流程。

该方案没有集中式错误枚举或全局异常处理器，错误信息以字符串形式在 UI 层展示，缺乏可机器判别的错误分类。