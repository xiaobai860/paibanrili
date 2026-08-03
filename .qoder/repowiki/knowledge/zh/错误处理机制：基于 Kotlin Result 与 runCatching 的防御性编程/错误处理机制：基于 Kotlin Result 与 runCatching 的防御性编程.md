---
kind: error_handling
name: 错误处理机制：基于 Kotlin Result 与 runCatching 的防御性编程
category: error_handling
scope:
    - '**'
source_files:
    - app/src/main/java/com/schedulecalendar/app/ui/settings/BackupManager.kt
    - app/src/main/java/com/schedulecalendar/app/ui/settings/StorageViewModel.kt
    - app/src/main/java/com/schedulecalendar/app/data/calendar/CalendarEventRepository.kt
    - app/src/main/java/com/schedulecalendar/app/MainActivity.kt
    - app/src/main/java/com/schedulecalendar/app/data/repository/Mappers.kt
---

该 Android 应用采用了一种**非侵入式、基于 `runCatching` 和 `Result<T>`** 的错误处理模式。代码库中未定义全局的自定义异常类或统一的错误码枚举，而是广泛利用 Kotlin 标准库提供的工具来处理运行时异常，特别是在涉及 I/O、系统 API 调用和数据解析的场景中。

### 1. 核心策略：`runCatching` 与静默失败
- **通用模式**：在 Repository、ViewModel 和工具类中，绝大多数可能抛出异常的操作（如数据库查询、文件读写、网络/日历同步）都被包裹在 `runCatching { ... }` 块中。
- **结果处理**：
  - **ViewModel 层**：通常使用 `.onSuccess { ... }.onFailure { ... }` 链式调用，将错误转换为 UI 事件（如 `StorageUiEvent.ShowError`），确保用户能感知到操作失败。
  - **Repository/底层**：倾向于“静默失败”或返回默认值。例如，当日历查询失败时返回 `null` 或空列表；当颜色解析失败时回退到默认颜色。这种设计提高了应用的健壮性，避免了因局部非关键错误导致整个功能崩溃。

### 2. 关键领域的错误处理实践
- **数据持久化与备份 (`BackupManager`, `StorageViewModel`)**：
  - 所有备份和恢复操作均通过 `runCatching` 保护。
  - 在 `BackupManager` 中，针对 SAF (Storage Access Framework) 的复杂 URI 解析和文件操作，使用了多层 `try-catch` 和 `runCatching`，并在解析失败时回退到原始路径或默认行为。
  - 错误信息会通过 `Channel` 传递给 UI 层进行展示。
- **系统日历集成 (`CalendarEventRepository`)**：
  - 由于 Android 日历 Provider 的行为在不同厂商 ROM 上存在差异，该类中的几乎所有公开方法（`createEvent`, `deleteEvent`, `getOrCreateLocalCalendarId` 等）都使用了 `try-catch (e: Exception)`。
  - 发生异常时，通常返回 `-1`、`null` 或 `false`，而不是向上抛出异常。这确保了即使日历同步失败，应用的核心排班功能仍能正常运行。
- **UI 渲染与数据转换**：
  - 在 Compose UI 中解析颜色字符串或枚举值时（如 `Mappers.kt`, `CommonComponents.kt`），使用 `runCatching { ... }.getOrElse { default }` 提供安全的回退机制，防止因数据脏读导致 UI 崩溃。
- **反射与系统兼容性 (`MainActivity`)**：
  - 在处理 API 34+ 的 `OnBackInvokedDispatcher` 时，使用了反射并包裹在 `try-catch` 中，以确保在低版本或不支持的设备上能优雅降级。

### 3. 架构约定与开发者指南
- **禁止裸抛异常**：在数据层和业务逻辑层，避免直接抛出未检查的异常。应使用 `Result<T>` 封装操作结果。
- **UI 层负责反馈**：ViewModel 是错误处理的边界。它负责捕获底层错误，并将其转化为有意义的用户提示（Toast/Snackbar）。
- **防御性解析**：对于来自外部（如 JSON 导入、系统日历、SharedPreferences）的数据，必须进行防御性解析，提供合理的默认值。
- **日志记录**：虽然在 `runCatching` 的 `onFailure` 中通常只展示消息，但在关键的系统交互点（如 `MainActivity` 的反射调用）会记录警告日志，便于调试。

### 4. 局限性
- **缺乏细粒度错误分类**：目前主要依赖 `Exception.message`，没有结构化的错误类型（如 `NetworkError`, `DbError`），这在需要针对不同错误类型执行不同重试策略时会显得不足。
- **静默吞没风险**：部分底层 `catch (_: Exception) {}` 块完全忽略了异常，可能导致问题难以排查。建议在关键路径至少记录日志。