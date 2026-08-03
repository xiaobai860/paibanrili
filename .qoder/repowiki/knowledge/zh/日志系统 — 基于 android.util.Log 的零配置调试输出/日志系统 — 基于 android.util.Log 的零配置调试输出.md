---
kind: logging_system
name: 日志系统 — 基于 android.util.Log 的零配置调试输出
category: logging_system
scope:
    - '**'
source_files:
    - app/src/main/java/com/schedulecalendar/app/MainActivity.kt
---

本仓库未引入任何第三方日志框架（如 Timber、SLF4J、Logback 等），也未定义统一的 Logger 抽象或日志初始化模块。全项目仅使用 Android 平台自带的 `android.util.Log`，且仅在单一文件 `MainActivity.kt` 中调用，用于记录返回键处理与 Tab 状态切换等 UI 流程调试信息。

**现状概览**
- 依赖：无额外日志库依赖；所有日志通过 `import android.util.Log` 直接调用。
- 使用范围：仅 `MainActivity.kt` 一处，其余数据层、领域层、UI 组件均未输出日志。
- 级别策略：全部使用 `Log.d()` 打印调试信息，异常路径使用 `Log.w(tag, message, e)` 附带堆栈。
- 结构化字段：无统一 tag 常量或结构化字段约定，tag 直接使用类名字符串字面量（如 `"MainActivity"`）。
- 生命周期/初始化：无 Application 级日志初始化、无日志开关、无 sink 路由。

**开发者应遵循的规则（建议）**
1. 如需在更多位置输出日志，建议抽取一个应用内统一的 `AppLogger` 单例，集中管理 tag 前缀与日志级别过滤。
2. 为关键业务路径（数据库操作、网络请求、提醒触发）补充结构化日志，包含必要上下文字段（日期、班次 ID、用户动作）以便问题定位。
3. 避免在生产构建中保留过多 `Log.d` 输出，可通过 ProGuard/R8 规则或构建变体控制日志级别。
4. 异常日志优先使用 `Log.e(tag, "message", throwable)` 而非 `Log.w`，确保堆栈可追踪。
5. 若后续引入第三方日志框架（推荐 Timber），应在 `ScheduleApp` 或 `Application` 启动时完成初始化，并替换所有 `android.util.Log` 调用。