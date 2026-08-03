---
kind: error_handling
name: 错误处理体系：ViewModel 事件 + runCatching + Repository 容错
category: error_handling
scope:
    - '**'
source_files:
    - app/src/main/java/com/schedulecalendar/app/ui/calendar/CalendarViewModel.kt
    - app/src/main/java/com/schedulecalendar/app/ui/detail/DetailViewModels.kt
    - app/src/main/java/com/schedulecalendar/app/ui/hours/HoursViewModel.kt
    - app/src/main/java/com/schedulecalendar/app/ui/salary/SalaryViewModel.kt
    - app/src/main/java/com/schedulecalendar/app/ui/settings/SettingsViewModel.kt
    - app/src/main/java/com/schedulecalendar/app/ui/settings/StorageViewModel.kt
    - app/src/main/java/com/schedulecalendar/app/data/calendar/CalendarEventRepository.kt
    - app/src/main/java/com/schedulecalendar/app/MainActivity.kt
---

该排班日历 Android 应用采用分层、一致的错误处理模式，核心思想是：**Repository 层吞掉异常并返回空值或默认值，ViewModel 层用 `runCatching` 捕获业务异常并通过 sealed class UI 事件向上通知 UI**。具体体现在以下方面：

### 1. ViewModel 层统一的事件模型
每个 ViewModel 都定义一个 `sealed class XxxUiEvent`，其中包含 `ShowError(val msg: String)` 作为统一的错误上报通道。例如：
- `CalendarUiEvent.ShowError`
- `ScheduleDetailUiEvent.ShowError`
- `HoursUiEvent.ShowError`
- `SalaryUiEvent.ShowError`
- `SettingsUiEvent.ShowError`
- `ExtraItemsUiEvent.ShowError`
- `StorageUiEvent.ShowError`

UI 侧通过 `viewModel.uiEvent.collect { ... }` 订阅这些事件，统一展示 Toast 或对话框。

### 2. 异步操作使用 `runCatching` + `.onFailure`
所有可能抛出异常的协程块都包裹在 `runCatching { ... }.onFailure { _uiEvent.send(ShowError(...)) }` 中，如 `DetailViewModels.kt` 的保存/删除操作、`HoursViewModel.kt` 的加载逻辑、`SettingsViewModel.kt` 的数据清空等。成功路径直接更新 state 并发送导航/提示事件。

### 3. Repository 层静默容错
数据访问层（尤其是 `CalendarEventRepository.kt`）大量使用 `try { ... } catch (e: Exception) { null }` 的模式，将系统 API 调用（Calendar Provider、AccountManager 等）的异常吞掉并返回空值或空集合，避免上层崩溃。这种设计让上层只需关注业务逻辑而非底层 I/O 异常。

### 4. 无全局错误类型定义
代码库中没有统一的 `AppException`、`Result<T>` 封装或错误码枚举。错误信息以字符串形式直接传递，由 UI 层决定如何展示。这是一种轻量但缺乏结构化的处理方式。

### 5. 未使用 try-catch-finally 或自定义异常类
除了 `MainActivity.kt` 中有少量原始 try-catch 外，其余地方均遵循上述模式。没有使用 Kotlin 的 `Result` 类型或 Arrow 的 `Either` 等函数式错误处理库。

### 开发者应遵循的规则
- **新增 ViewModel**：定义 `sealed class XxxUiEvent`，至少包含 `ShowError(msg: String)`；所有 suspend 方法用 `runCatching` 包裹，失败时发送 `ShowError`。
- **Repository 层**：对第三方 API 调用使用 try-catch 吞异常，返回安全默认值（null/空集合），不向上传播异常。
- **UI 层**：统一收集 `uiEvent`，根据事件类型展示对应反馈（错误弹窗、成功提示、页面跳转）。
- **避免**：不要在 UI 层直接 try-catch；不要抛出自定义异常；不要在 Repository 层中断言或抛出业务异常。