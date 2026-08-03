---
kind: logging_system
name: 日志系统现状：基于 Android 原生 Log 的临时调试
category: logging_system
scope:
    - '**'
source_files:
    - app/src/main/java/com/schedulecalendar/app/MainActivity.kt
    - app/build.gradle.kts
    - gradle/libs.versions.toml
---

## 1. 系统/方法
当前应用**未集成**第三方日志框架（如 Timber、Kermit 或 SLF4J），而是直接使用 Android 原生的 `android.util.Log` 进行日志输出。这种方式通常用于开发阶段的临时调试，缺乏统一的日志管理、级别控制和结构化输出能力。

## 2. 关键文件与包
- **核心使用点**：`app/src/main/java/com/schedulecalendar/app/MainActivity.kt`
  - 该文件是目前唯一发现显式调用 `Log.d()` 和 `Log.w()` 的地方，主要用于跟踪页面状态（如 `isOnTabPage`、`calendarSubModeActive`）和返回键处理逻辑。
- **依赖配置**：`app/build.gradle.kts` 和 `gradle/libs.versions.toml`
  - 依赖列表中未发现任何日志库（如 `timber`、`kermit` 等）。

## 3. 架构与约定
- **分散式记录**：日志调用直接嵌入在业务逻辑（Activity 生命周期、状态 setter）中，没有封装统一的 Logger 工具类或单例。
- **硬编码标签**：使用固定的字符串标签（如 `"MainActivity"`）作为 Log 的第一个参数，便于在 Logcat 中过滤，但缺乏动态上下文信息。
- **无级别策略**：目前仅观察到 `DEBUG` (Log.d) 和 `WARN` (Log.w) 级别的使用，未见 `ERROR` 或 `INFO` 的系统性应用，也没有针对 Release 版本关闭日志的配置（如 ProGuard 规则中未移除 Log 调用）。

## 4. 开发者应遵循的规则
- **现状约束**：由于缺乏统一框架，新增日志时应继续使用 `android.util.Log`，并保持 Tag 与类名一致。
- **建议改进**：
  - 引入轻量级日志库（如 Timber）以支持自动 Tag 生成和发布版日志屏蔽。
  - 避免在生产代码中保留过多的 `Log.d` 调试信息，或建立统一的 `BuildConfig.DEBUG` 检查机制。
  - 对于关键业务异常，应补充 `Log.e` 并记录堆栈信息，以便线上问题排查。