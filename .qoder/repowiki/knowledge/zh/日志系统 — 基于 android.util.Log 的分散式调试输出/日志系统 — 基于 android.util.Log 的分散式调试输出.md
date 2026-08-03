---
kind: logging_system
name: 日志系统 — 基于 android.util.Log 的分散式调试输出
category: logging_system
scope:
    - '**'
source_files:
    - app/src/main/java/com/schedulecalendar/app/MainActivity.kt
---

本仓库未实现统一的日志框架或集中式日志基础设施，仅在 MainActivity.kt 中直接使用 Android 平台原生的 android.util.Log 进行调试输出。具体观察如下：

1. 使用的系统与工具：仅依赖 android.util.Log，未引入 Timber、SLF4J、Logback 等第三方日志库，也未在 build.gradle 中添加任何日志相关依赖。

2. 关键文件与位置：所有日志调用集中在 app/src/main/java/com/schedulecalendar/app/MainActivity.kt 一个文件中，用于跟踪返回键处理、Overlay 回调注册、Tab 页面状态切换等流程。

3. 架构与约定：无全局 Logger 初始化或配置类；无日志级别开关或过滤机制；无结构化字段封装，直接以字符串拼接形式输出；Tag 统一使用类名 "MainActivity" 作为标识。

4. 使用模式与约束：主要使用 Log.d() 输出调试信息，辅以 Log.w() 记录异常堆栈；日志内容包含关键状态变量（如 isOnTabPage、calendarSubModeActive）以便定位问题；未发现对日志输出的条件控制（如 debug/build 变体区分），所有 Log 调用在生产构建中仍会执行。

由于该应用是个人开发的小型 Android 项目，日志需求简单，因此未采用企业级日志方案。若需改进，可考虑引入 Timber 并配合 BuildConfig.DEBUG 进行条件编译。