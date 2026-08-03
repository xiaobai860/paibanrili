---
kind: configuration_system
name: Android 应用配置系统（DataStore + Hilt + Gradle）
category: configuration_system
scope:
    - '**'
source_files:
    - app/src/main/java/com/schedulecalendar/app/data/prefs/AppPreferences.kt
    - app/src/main/java/com/schedulecalendar/app/di/PreferencesModule.kt
    - app/src/main/java/com/schedulecalendar/app/di/DatabaseModule.kt
    - app/build.gradle.kts
    - gradle/libs.versions.toml
    - gradle.properties
---

本项目的运行时配置由三层构成：Gradle 构建参数、Android DataStore 偏好存储、以及 Hilt 依赖注入模块。三者分别负责编译期/构建期配置、用户持久化设置与依赖装配。

### 1. 构建期配置（Gradle Kotlin DSL）
- 顶层 `build.gradle.kts` 仅声明插件并 `apply false`，通过 `gradle/libs.versions.toml` 集中管理所有插件与依赖版本（AGP 8.13.0、Kotlin 2.0.21、Hilt 2.52、Room 2.7.1、DataStore 1.1.4 等）。
- `app/build.gradle.kts` 定义 `defaultConfig`（applicationId、minSdk/targetSdk、versionCode/versionName）、`signingConfigs`（release 签名信息硬编码在工程内）、`buildTypes`（debug/release 开关混淆与资源压缩）、`ksp` 参数（Room schema 导出至 `app/schemas` 目录）以及 `lint` 规则禁用项。
- `gradle.properties` 配置 JVM 参数、Configuration Cache、AndroidX/Jetifier 开关与 Kotlin 代码风格。

### 2. 用户运行时配置（DataStore Preferences）
- 核心实现位于 `app/src/main/java/com/schedulecalendar/app/data/prefs/AppPreferences.kt`，基于 `androidx.datastore.preferences` 以 `preferencesDataStore("app_config")` 持久化所有用户设置。
- 所有键值通过 `stringPreferencesKey` / `intPreferencesKey` 声明为 `companion object` 中的 `KEY_*` 常量，覆盖薪资配置、考勤配置、排班规则、显示方案、小组件数据、各类排序、颜色索引、备份路径、提醒设置、账户分类、快捷方式开关、权限申请标记等。
- 复杂对象（`SalaryConfig`、`AttendConfig`、`ScheduleRule`、`DisplayScheme`、`Map<String,String>` 等）通过 Gson + `InstanceCreator` 序列化为 JSON 字符串存取；集合类（如排序列表）以逗号分隔字符串形式保存。
- 每个配置项同时提供 `suspend fun get/save...()` 与 `Flow<T>` 响应式读取接口，部分组合字段（如 `reminderSettingsFlow`）使用 `combine` 聚合多个键。
- 提供 `clearAll()` 方法清空所有 DataStore 键值对以恢复出厂默认。

### 3. 依赖注入与装配（Hilt）
- `app/src/main/java/com/schedulecalendar/app/di/PreferencesModule.kt` 通过 `@Module @InstallIn(SingletonComponent::class)` 将 `AppPreferences` 以单例形式暴露给 Hilt。
- `DatabaseModule.kt` 同样以 Hilt 模块提供 Room 数据库实例及 DAO 单例。
- 所有配置读写均通过 Hilt 注入 `@ApplicationContext Context`，避免内存泄漏。

### 4. 架构约定与约束
- **单一配置入口**：所有用户设置必须通过 `AppPreferences` 的公开 API 访问，禁止直接操作 `Context.getSharedPreferences` 或 `DataStore` 扩展。
- **键命名规范**：所有 DataStore 键必须以 `KEY_` 前缀声明在 `AppPreferences` 伴生对象中，按功能分组注释（薪资、考勤、提醒、备份等）。
- **响应式优先**：UI 层应订阅对应的 `Flow<T>` 而非轮询读取，确保设置变更后界面自动刷新。
- **默认值策略**：所有读取方法在无数据时返回合理默认值（布尔默认 `true`/`false`、整数默认 `0` 或 `15`、字符串默认 `"alarm"`、集合默认空列表），保证首次启动不崩溃。
- **序列化容错**：Gson 反序列化失败时回退到空对象或空集合，避免脏数据导致应用异常。
- **Widget 共享**：小组件通过 Glance DataStore 读取 `widget_data` 字段，与主进程共用同一份配置源。

### 5. 未使用的配置机制
- 项目中未发现 `.env`、`.yaml`、`.properties`（除 gradle.properties）、`BuildConfig` 动态字段、Feature Flags 框架或远程配置服务，所有可配置项均以本地 DataStore 偏好形式存在。