---
kind: error_handling
name: 基于 Result + sealed class 的轻量级错误处理模式
category: error_handling
scope:
    - '**'
source_files:
    - app/src/main/java/com/schedulecalendar/app/ui/calendar/CalendarViewModel.kt
    - app/src/main/java/com/schedulecalendar/app/ui/detail/DetailViewModels.kt
    - app/src/main/java/com/schedulecalendar/app/ui/hours/HoursViewModel.kt
    - app/src/main/java/com/schedulecalendar/app/ui/salary/SalaryViewModel.kt
    - app/src/main/java/com/schedulecalendar/app/ui/settings/BackupManager.kt
    - app/src/main/java/com/schedulecalendar/app/data/calendar/CalendarEventRepository.kt
    - app/src/main/java/com/schedulecalendar/app/ui/component/CommonComponents.kt
---

本仓库未建立统一的错误类型体系（无 AppError、ApiException 等集中定义），而是采用 Kotlin 标准库 Result<T> 与每个 ViewModel 自有的 sealed class XxxUiEvent 组合的轻量级错误处理方案，辅以少量 try/catch 兜底。

## 1. 核心机制
- 数据层返回 Result<T>：业务操作（保存、删除、备份等）通过 runCatching { ... } 包装，成功返回 T，失败返回异常。例如 BackupManager.createAppDataBackup() / createShiftConfigBackup() 均返回 suspend fun create...(): Result<File>。
- UI 层用 Channel<UiEvent> 上报错误：每个 ViewModel 定义一个 sealed class XxxUiEvent，其中统一包含 data class ShowError(val msg: String) 子类型；ViewModel 在 runCatching {...}.onFailure { _uiEvent.send(XxxUiEvent.ShowError(it.message)) } 中捕获异常并发送事件，Screen 侧收集该 Flow 弹出 Toast/对话框。
- 局部 I/O 使用 try/catch 兜底：对可能抛出的系统异常（如文件读写、Calendar Provider 调用、颜色解析等）使用 try { ... } catch (e: Exception) { ... } 或 getOrDefault/getElse 降级为默认值，避免崩溃。

## 2. 关键文件与位置
- UI 事件模型：app/src/main/java/com/schedulecalendar/app/ui/calendar/CalendarViewModel.kt（CalendarUiEvent.ShowError）、app/src/main/java/com/schedulecalendar/app/ui/detail/DetailViewModels.kt（ScheduleDetailUiEvent.ShowError、ExtraItemsUiEvent.ShowError）、app/src/main/java/com/schedulecalendar/app/ui/hours/HoursViewModel.kt（HoursUiEvent.ShowError）、app/src/main/java/com/schedulecalendar/app/ui/salary/SalaryViewModel.kt（SalaryUiEvent.ShowError）
- Result 用法：app/src/main/java/com/schedulecalendar/app/ui/settings/BackupManager.kt（createAppDataBackup()/createShiftConfigBackup() 返回 Result<File>）
- try/catch 兜底：app/src/main/java/com/schedulecalendar/app/data/calendar/CalendarEventRepository.kt（Calendar Provider 交互大量 catch (e: Exception)）、app/src/main/java/com/schedulecalendar/app/MainActivity.kt（全局入口 catch (e: Exception)）
- 降级默认值：app/src/main/java/com/schedulecalendar/app/ui/component/CommonComponents.kt（runCatching { Color(...) }.getOrElse { Color.Gray }）

## 3. 架构约定与设计决策
- 不抛出受检异常：Repository 层不定义自定义异常类，直接让底层 API 抛出的异常冒泡到调用方，由上层 runCatching 捕获。
- 错误信息本地化：ShowError(msg) 中的消息多为中文硬编码字符串（如 "保存失败：${it.message}"），未抽取到资源文件，便于快速展示但不可国际化。
- 无全局错误中间件：没有应用级 CoroutineExceptionHandler、Hilt 模块级别的错误拦截器或 Compose SnackbarHostState 集中管理，错误传播依赖 ViewModel -> Screen 的单向事件流。
- 恢复型错误：对于可恢复场景（如颜色解析失败、JSON 字段缺失），优先使用 getOrDefault/getOrNull 回退到安全默认值而非中断流程。

## 4. 开发者应遵循的规则
1. 对外暴露 Result<T>：任何可能失败的 suspend 函数优先返回 Result<T>，并在调用处用 runCatching { ... }.onSuccess/onFailure 处理。
2. UI 错误走 ShowError 事件：在 ViewModel 中捕获异常后，统一通过 _uiEvent.send(ShowError(...)) 上报，不要在 View 层直接 Toast。
3. I/O 与系统调用加 try/catch：涉及文件、ContentProvider、网络等外部资源的代码必须包裹 try/catch (e: Exception)，并以降级逻辑替代崩溃。
4. 避免吞掉异常：catch (_: Exception) {} 仅用于明确可忽略的场景（如权限持久化失败），其余情况至少记录日志或转为用户可见的错误提示。
5. 不在 Repository 层定义自定义异常类型：保持轻量，复用 Kotlin 标准异常即可。