---
kind: logging_system
name: 基于 android.util.Log 的散点式日志输出
category: logging_system
scope:
    - '**'
source_files:
    - app/src/main/java/com/schedulecalendar/app/data/calendar/CalendarEventRepository.kt
    - app/src/main/java/com/schedulecalendar/app/ui/todo/CalendarEventViewModel.kt
    - app/src/main/java/com/schedulecalendar/app/reminder/ReminderScheduler.kt
    - app/src/main/java/com/schedulecalendar/app/ui/component/CommonComponents.kt
---

本仓库未引入任何第三方日志框架（如 Timber、SLF4J、java.util.logging），也未建立统一的日志基础设施。全项目采用 Android 原生 `android.util.Log` 进行散点式打印，各模块自行决定 tag 与级别，无集中初始化、无全局配置、无结构化字段约定。

- 使用方式：直接调用 `Log.v/i/d/w/e(tag, msg)` 或 `Log.e(tag, msg, throwable)`，tag 多为类名或功能缩写（如 `CalendarEventRepo`、`ReminderScheduler`、`CalendarEventVM`、`ImeAdaptive`）。
- 级别分布：错误路径普遍用 `Log.e`，调试信息用 `Log.d`/`Log.v`，警告用 `Log.w`，但不同文件对同一场景的级别并不一致，缺少统一策略。
- 结构化程度：消息以字符串拼接为主，偶尔附带异常堆栈；没有统一的 JSON/键值对格式，也没有按模块/来源分桶输出的 sink 机制。
- 生命周期管理：未发现 Application 级别的 Log 初始化或开关控制逻辑，无法在运行时动态调整级别或关闭输出。
- 构建期过滤：未在 build.gradle.kts 中配置 ProGuard/R8 规则来剥离 debug-only 的日志调用，也未见 `BuildConfig.DEBUG` 条件包裹。

结论：该工程不存在成体系的 logging_system，仅依赖系统原生 Log 做临时调试输出，不具备可观测性工程化能力。