---
kind: error_handling
name: Android 应用错误处理策略：分层捕获与 Result/Channel 模式
category: error_handling
scope:
    - '**'
source_files:
    - app/src/main/java/com/schedulecalendar/app/data/calendar/CalendarEventRepository.kt
    - app/src/main/java/com/schedulecalendar/app/ui/calendar/CalendarViewModel.kt
    - app/src/main/java/com/schedulecalendar/app/ui/settings/BackupManager.kt
    - app/src/main/java/com/schedulecalendar/app/MainActivity.kt
---

该 Android 排班日历项目采用**分层、混合式**的错误处理策略，根据调用层级选择不同的模式：

## 1. 数据层（Repository）—— try-catch + 空值/默认值返回
- `CalendarEventRepository` 中所有 I/O 操作（ContentResolver 查询、插入、更新、删除）均使用 `try { ... } catch (e: Exception) { null/-1/false }` 模式
- 异常被静默吞掉或返回默认值（如 `-1L`、`null`、`false`、`emptyList()`），不向上抛出
- 这种设计确保系统日历 API 的兼容性（不同 ROM 行为差异），失败时优雅降级

## 2. UI 层（ViewModel）—— Channel + sealed class 事件流
- 每个 ViewModel 定义 `sealed class XxxUiEvent`，包含 `ShowError(val msg: String)` 变体
- 通过 `Channel<XxxUiEvent>` 向 Compose UI 发送错误消息
- 使用 Kotlin `Result<T>` 和 `runCatching` 包装协程操作，通过 `.onFailure { }` 处理错误
- 示例：`BackupManager.createAppDataBackup(): Result<File>` 返回 `Result<File>`

## 3. 工具类（BackupManager）—— runCatching + 显式异常
- 文件操作使用 `runCatching { }` 包装，成功返回 `Result<File>`
- 参数验证失败时直接 `throw IllegalArgumentException(...)` 或 `IllegalStateException(...)`
- 恢复操作在找不到文件时抛出明确异常，由调用方处理

## 4. Activity 层 —— try-catch 兜底
- `MainActivity` 中使用 try-catch 包裹反射调用（API 34+ 的 OnBackInvokedDispatcher）
- 捕获 `Exception` 并记录日志，确保兼容性问题的容错

## 5. 未使用的模式
- 未发现统一的自定义异常类型体系（如 sealed interface Error）
- 没有全局错误处理器或中间件
- 未使用 Kotlin Flow 的错误处理操作符（如 `catch`、`retry`）

**核心原则**：数据层静默失败保证稳定性，UI 层通过事件流传递用户可见的错误信息，工具方法使用 `Result` 类型明确错误传播。