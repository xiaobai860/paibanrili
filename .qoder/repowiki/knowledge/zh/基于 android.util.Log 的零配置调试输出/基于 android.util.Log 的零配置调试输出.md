---
kind: logging_system
name: 基于 android.util.Log 的零配置调试输出
category: logging_system
scope:
    - '**'
source_files:
    - app/src/main/java/com/schedulecalendar/app/MainActivity.kt
---

本仓库未引入任何第三方日志框架（如 Timber、SLF4J、Log4j2 等），也未建立统一的日志封装层或日志级别策略。全项目仅存在一处对 `android.util.Log` 的直接调用，且全部集中在 `MainActivity.kt` 中，用于打印页面切换与悬浮窗回调相关的调试信息。其余业务模块（data、domain、ui、reminder、widget 等）均未使用任何日志输出。因此该仓库不存在成体系的日志系统，仅有最基础的调试打印。