---
kind: error_handling
name: 基于 sealed class 事件与 Result 的轻量错误处理体系
category: error_handling
scope:
    - '**'
source_files:
    - app/src/main/java/com/schedulecalendar/app/ui/calendar/CalendarViewModel.kt
    - app/src/main/java/com/schedulecalendar/app/ui/settings/BackupManager.kt
    - app/src/main/java/com/schedulecalendar/app/data/calendar/CalendarEventRepository.kt
    - app/src/main/java/com/schedulecalendar/app/ui/settings/SettingsViewModel.kt
    - app/src/main/java/com/schedulecalendar/app/ui/settings/StorageViewModel.kt
    - app/src/main/java/com/schedulecalendar/app/ui/hours/HoursViewModel.kt
    - app/src/main/java/com/schedulecalendar/app/ui/salary/SalaryViewModel.kt
---

本仓库未引入统一的异常类型或全局错误码，而是采用“UI 层用密封类事件 + 业务层用 Kotlin Result”的两层模式，配合 try/catch 在边界处兜底。

1. UI 层：每个 ViewModel 定义自己的 sealed class XxxUiEvent，统一包含 ShowMessage(msg) 和 ShowError(msg) 两个子类型，通过 Channel<XxxUiEvent> 单向推送给 Compose 屏幕消费；同时用 data class XxxUiState 承载 loading、数据等状态。例如 CalendarUiEvent.ShowError、SettingsUiEvent.ShowError、StorageUiEvent.ShowError 等，由 Screen 侧 when(event) { is ShowError -> ... } 展示 Toast/Dialog。

2. 业务层：对可能失败的操作使用 runCatching { ... }.onFailure { ... } 包裹（如 applyScheduleRule），或在 Repository 中直接 try { ... } catch(e: Exception) { null/false/-1 } 将异常吞掉并返回安全默认值（如 CalendarEventRepository.createEvent 返回 -1L）。文件 I/O 相关方法（BackupManager.restoreFromJson、restoreAppDataFromPrivate）则抛出明确的 IllegalArgumentException / IllegalStateException，由调用方捕获。

3. 协程与 IO 边界：所有外部系统调用（ContentResolver、文件读写、Gson 解析）均显式放在 Dispatchers.IO 并用 try/catch 包裹，避免主线程崩溃；ViewModel 内部逻辑走主线程，仅通过 _uiEvent.send(ShowError(...)) 把错误信息回传 UI。

4. 约定与约束：
- 不要在 Domain/Repository 层抛出自定义异常类型，统一以返回值（null/false/-1）或 Result<T> 表达失败。
- UI 层只消费 XxxUiEvent.ShowError 做用户可见提示，不在 View 中自行 catch 业务异常。
- 需要区分“静默失败”（如日历权限缺失时返回 null）与“可恢复失败”（如 JSON 解析失败抛异常让上层决定重试）两种语义。