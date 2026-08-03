---
kind: configuration_system
name: 应用配置系统：DataStore + SharedPreferences 混合持久化
category: configuration_system
scope:
    - '**'
source_files:
    - app/src/main/java/com/schedulecalendar/app/data/prefs/AppPreferences.kt
    - app/src/main/java/com/schedulecalendar/app/di/PreferencesModule.kt
    - app/src/main/java/com/schedulecalendar/app/widget/ScheduleGlanceWidget.kt
    - app/src/main/java/com/schedulecalendar/app/widget/CalendarGlanceWidget.kt
    - app/src/main/java/com/schedulecalendar/app/widget/WidgetConfigActivity.kt
    - gradle/libs.versions.toml
    - app/build.gradle.kts
---

## 1. 使用的系统与框架
- **主配置存储**：`androidx.datastore.preferences`（DataStore Preferences），通过 `AppPreferences` 单例统一管理所有用户偏好与运行时配置。
- **辅助存储**：`SharedPreferences` 仍用于小组件（Glance）的即时读写，如打卡状态、Widget 配置等。
- **依赖注入**：Hilt (`@Singleton` + `PreferencesModule`) 提供 `AppPreferences` 实例。
- **序列化**：Gson 负责复杂对象（`SalaryConfig`、`AttendConfig`、`ScheduleRule`、`DisplayScheme`、账户分类映射等）与 DataStore 字符串键之间的 JSON 转换。
- **版本管理**：依赖版本集中在 `gradle/libs.versions.toml`，DataStore 1.1.4、Gson 2.11.0、Hilt 2.52。

## 2. 核心文件与包
- `app/src/main/java/com/schedulecalendar/app/data/prefs/AppPreferences.kt` — 所有配置键定义、读写 API、Flow 暴露。
- `app/src/main/java/com/schedulecalendar/app/di/PreferencesModule.kt` — Hilt 模块，提供 `AppPreferences` 单例。
- `app/src/main/java/com/schedulecalendar/app/widget/ScheduleGlanceWidget.kt`、`CalendarGlanceWidget.kt`、`WidgetConfigActivity.kt` — 使用 SharedPreferences 处理 Widget 相关配置。
- `gradle/libs.versions.toml` — 集中声明 DataStore、Gson、Hilt 等依赖版本。
- `app/build.gradle.kts` — 声明 datastore-prefs 依赖。

## 3. 架构与设计约定
- **单一入口**：`AppPreferences` 是全局唯一配置访问点，按功能域划分键前缀（薪资、考勤、排班、显示方案、排序、颜色索引、备份、提醒、快捷方式、权限、账户分类等）。
- **响应式配置**：关键配置通过 `Flow<T>` 暴露（如 `salaryConfigFlow`、`attendConfigFlow`、`reminderEnabledFlow`、`shortcutEnabledFlow` 等），UI 层用 `collectAsStateWithLifecycle` 订阅。
- **默认值策略**：读取时统一返回合理默认值（空列表、空 Map、0、"alarm" 等），避免 null 分支。
- **JSON 反序列化容错**：对 List/Map 类型使用 `runCatching` + `TypeToken` 解析，失败回退到空集合；Gson 通过 `InstanceCreator` 保留 Kotlin data class 默认值。
- **组合 Flow**：`reminderSettingsFlow` 使用 `combine` 将多个布尔/字符串键合并为结构化快照。
- **清理能力**：`clearAll()` 支持一键恢复出厂设置。

## 4. 开发者应遵循的规则
- **新增配置项**：在 `AppPreferences` 中先定义 `KEY_*` 常量，再提供对应的 `get*` / `save*` / `*Flow` 三件套。
- **优先使用 DataStore**：仅 Widget 交互场景允许直接使用 `SharedPreferences`，其他一律走 `AppPreferences`。
- **保持默认值语义一致**：布尔型以 `"true"`/`"false"` 字符串存储，缺失时按业务逻辑推断（如 `isShortcutEnabled` 默认 true）。
- **复杂对象必须用 Gson 序列化**：禁止直接拼接 JSON 字符串，统一通过 `gson.toJson/fromJson` 并注册 `InstanceCreator`。
- **Flow 暴露只读视图**：对外暴露 `Flow<T>`，写入方法均为 `suspend`，保证协程安全。
- **迁移兼容**：解析 JSON 时必须 try-catch 并回退默认值，确保旧版本数据不崩溃。