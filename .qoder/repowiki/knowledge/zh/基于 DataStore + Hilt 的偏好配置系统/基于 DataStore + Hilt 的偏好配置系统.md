---
kind: configuration_system
name: 基于 DataStore + Hilt 的偏好配置系统
category: configuration_system
scope:
    - '**'
source_files:
    - app/src/main/java/com/schedulecalendar/app/data/prefs/AppPreferences.kt
    - app/src/main/java/com/schedulecalendar/app/di/PreferencesModule.kt
    - app/src/main/java/com/schedulecalendar/app/domain/model/Models.kt
    - app/build.gradle.kts
    - gradle.properties
---

本应用采用 Android Jetpack DataStore Preferences 作为运行时配置持久化方案，通过 Hilt 依赖注入统一管理。配置系统围绕 `AppPreferences` 单例类构建，所有用户设置、应用状态和序列化对象均通过该统一入口读写。

**核心架构与组件**
- 持久化层：使用 `androidx.datastore.preferences.core.Preferences` 键值对存储，文件名为 `app_config`（定义于 `Context.dataStore` 扩展属性）
- 依赖注入：`PreferencesModule` 通过 `@Provides @Singleton` 暴露 `AppPreferences`，由 Hilt 在 `SingletonComponent` 中提供
- 响应式更新：所有配置项同时暴露 `Flow<T>` 形式的只读流，便于 Compose UI 实时订阅变化
- 复杂对象序列化：使用 Gson + `InstanceCreator` 确保 Kotlin data class 反序列化时保留默认值

**配置分类与键命名约定**
所有配置键集中在 `AppPreferences` 伴生对象的 `KEY_*` 常量中，按功能域分组：
- 薪资/考勤/排班规则：`salary_config`、`attend_config`、`schedule_rule`
- 显示方案：`display_schemes`（List<DisplayScheme>）、颜色索引、排序顺序
- 小组件数据：`widget_data`（供 Glance Widget 直接读取）
- 提醒设置：`reminder_enabled`、`reminder_method`、`reminder_clock_in/out_minutes` 等
- 账户管理：`disabled_account_ids`、`account_categories`、`accounts_initialized`
- 备份相关：`app_data_keep_count`、`shift_config_keep_count`、`backup_custom_path`
- 首次启动标记：`initial_permissions_done`、`shortcut_enabled`

**设计模式与约束**
- 每个配置项提供三件套：`get*()` 同步快照、`save*()` 写入、`*Flow` 响应式流
- 布尔值以字符串 "true"/"false" 存储（非原生 Boolean），空值时返回合理默认值
- 列表/集合通过逗号分隔字符串或 JSON 序列化存储
- 复杂对象（SalaryConfig、AttendConfig、ScheduleRule、DisplayScheme）统一通过 Gson 序列化为 JSON 字符串
- 读取操作优先使用 `.first()` 获取当前快照，避免在 `edit{}` 块内读取导致的数据不一致
- 提供 `clearAll()` 方法一键清除所有 DataStore 键值对，恢复出厂默认值

**构建期配置**
- `gradle.properties` 仅包含 Gradle 和 AndroidX 全局开关，无应用级 BuildConfig 字段
- `build.gradle.kts` 中硬编码了 applicationId、版本号、签名信息等构建配置
- Room schema 版本迁移历史保存在 `schemas/com.schedulecalendar.app.data.db.AppDatabase/*.json`