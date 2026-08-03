---
kind: error_handling
name: Android 应用错误处理：UI 事件通道 + try-catch 静默降级
category: error_handling
scope:
    - '**'
source_files:
    - app/src/main/java/com/schedulecalendar/app/data/calendar/CalendarEventRepository.kt
    - app/src/main/java/com/schedulecalendar/app/ui/calendar/CalendarViewModel.kt
    - app/src/main/java/com/schedulecalendar/app/ui/detail/DetailViewModels.kt
    - app/src/main/java/com/schedulecalendar/app/ui/hours/HoursViewModel.kt
    - app/src/main/java/com/schedulecalendar/app/ui/salary/SalaryViewModel.kt
    - app/src/main/java/com/schedulecalendar/app/ui/settings/SettingsViewModel.kt
    - app/src/main/java/com/schedulecalendar/app/ui/settings/StorageViewModel.kt
    - app/src/main/java/com/schedulecalendar/app/ui/shifts/ShiftsViewModel.kt
    - app/src/main/java/com/schedulecalendar/app/MainActivity.kt
---

本仓库采用“分层混合”的错误处理策略：数据层以 try-catch 吞异常并返回安全默认值，业务/ViewModel 层通过 `runCatching` 捕获协程异常并通过 Channel 发送 UI 事件，UI 层用统一的 sealed class 事件模型展示错误。

## 1. 系统与方法

- **数据层（Repository）**：对 Android Calendar Provider、ContentResolver 等可能抛异常的 I/O 调用使用 `try { ... } catch (e: Exception) { null / -1L / false / emptyList() }` 包裹，将异常转换为安全的返回值，不向上抛出。
- **业务层（ViewModel）**：对可能失败的业务逻辑使用 `runCatching { ... }.onFailure { _uiEvent.send(ShowError(...)) }` 捕获异常，再经 Channel 下发到 UI。
- **UI 层**：每个 ViewModel 定义一个 `sealed class XxxUiEvent`，其中统一包含 `data class ShowError(val msg: String)` 子类型；Screen 侧收集 `uiEvent` 流后弹出 Toast 或 Snackbar 提示用户。
- **顶层入口**：`MainActivity` 在启动阶段也使用 try-catch 兜底，避免崩溃。

## 2. 关键文件与位置

- 数据层异常吞掉示例：`app/src/main/java/com/schedulecalendar/app/data/calendar/CalendarEventRepository.kt`（大量 `try { ... } catch (e: Exception) { null/-1L/false }`）
- UI 事件模型（示例）：
  - `app/src/main/java/com/schedulecalendar/app/ui/calendar/CalendarViewModel.kt` → `sealed class CalendarUiEvent { ShowError(...) }`
  - `app/src/main/java/com/schedulecalendar/app/ui/detail/DetailViewModels.kt` → `ScheduleDetailUiEvent.ShowError`
  - `app/src/main/java/com/schedulecalendar/app/ui/hours/HoursViewModel.kt` → `HoursUiEvent.ShowError`
  - `app/src/main/java/com/schedulecalendar/app/ui/salary/SalaryViewModel.kt` → `SalaryUiEvent.ShowError`
  - `app/src/main/java/com/schedulecalendar/app/ui/settings/SettingsViewModel.kt` → `SettingsUiEvent.ShowError`
  - `app/src/main/java/com/schedulecalendar/app/ui/settings/StorageViewModel.kt` → `StorageUiEvent.ShowError`
  - `app/src/main/java/com/schedulecalendar/app/ui/shifts/ShiftsViewModel.kt` → `ShiftsUiEvent.ShowError`
- runCatching 使用示例：`CalendarViewModel.applyRule()` 中 `runCatching { ... }.onFailure { _uiEvent.send(CalendarUiEvent.ShowError(...)) }`
- 顶层 try-catch：`app/src/main/java/com/schedulecalendar/app/MainActivity.kt`

## 3. 架构约定与设计决策

- **数据层不抛异常**：所有外部 I/O 调用都被 try-catch 包裹，失败时返回空集合、null、0 或 -1 等“无副作用”的默认值，保证上层稳定。
- **UI 错误集中表达**：每个 ViewModel 的 `XxxUiEvent` sealed class 都包含 `ShowError`，便于 Screen 统一处理（如显示 Toast）。
- **Channel 单向传递**：ViewModel 通过 `Channel<XxxUiEvent>` 把错误事件推给 Compose Screen，避免状态污染。
- **无全局错误中间件**：未发现全局异常处理器、自定义 Exception 类或错误码枚举，错误信息直接以字符串形式携带。

## 4. 开发者应遵循的规则

1. **数据层**：对任何可能失败的 I/O 调用使用 `try { ... } catch (e: Exception) { 安全默认值 }`，不要向上传播异常。
2. **业务层**：对可能失败的操作使用 `runCatching { ... }.onFailure { _uiEvent.send(ShowError(it.message ?: "操作失败")) }`，并把成功结果通过 StateFlow 更新。
3. **UI 层**：为每个 ViewModel 新增 `sealed class XxxUiEvent` 并包含 `ShowError` 子类型；Screen 侧收集 `uiEvent` 流并统一展示错误。
4. **避免吞掉可恢复错误**：对于需要用户感知的失败（如权限不足、网络错误），务必通过 `ShowError` 上报，而不是静默忽略。
5. **不在 UI 线程做耗时 I/O**：如需访问数据库或网络，应在 `viewModelScope.launch { withContext(IO) { ... } }` 中执行，并用 `runCatching` 捕获异常。