---
kind: configuration_system
name: Gradle 版本目录 + DataStore 应用配置系统
category: configuration_system
scope:
    - '**'
source_files:
    - gradle/libs.versions.toml
    - settings.gradle.kts
    - gradle.properties
    - app/build.gradle.kts
    - app/src/main/java/com/schedulecalendar/app/data/prefs/AppPreferences.kt
    - app/src/main/java/com/schedulecalendar/app/di/PreferencesModule.kt
---

本仓库的配置体系分为两个层次：构建期配置与运行时配置。

## 构建期配置（Gradle）
- **依赖与插件集中管理**：根目录 `gradle/libs.versions.toml` 通过 Version Catalog 统一声明 AGP、Kotlin、Compose BOM、Hilt、Room、Datastore 等所有依赖的版本号，子模块仅通过 `alias(libs.plugins.xxx)` 和 `libs.xxx` 引用，避免版本漂移。
- **仓库镜像与解析策略**：`settings.gradle.kts` 在 `pluginManagement` 与 `dependencyResolutionManagement` 中分别配置阿里云/华为云镜像作为主源，Google/MavenCentral/JitPack 为兜底，并启用 `RepositoriesMode.FAIL_ON_PROJECT_REPOS` 禁止子模块自行声明仓库，确保全仓一致。
- **全局 Gradle 属性**：`gradle.properties` 固定 JVM 参数、开启 Configuration Cache 与 Build Cache、禁用 Jetifier、指定 Kotlin 代码风格与 JDK 路径。
- **单模块应用**：`settings.gradle.kts` 仅 include `:app`，无多 flavor/buildType 变体，版本号采用日期格式（`versionCode = versionName = "2026071501"`），由 `app/build.gradle.kts` 的 `defaultConfig` 定义。

## 运行时配置（DataStore Preferences）
- **唯一持久化入口**：`app/src/main/java/com/schedulecalendar/app/data/prefs/AppPreferences.kt` 是应用唯一的用户配置中心，基于 `androidx.datastore.preferences` 以键值对形式存储 JSON 序列化的业务对象（薪资、考勤、排班规则、显示方案、小组件数据、排序、提醒设置、账户分类等）。
- **类型安全访问**：每个配置项提供 `getXxx()/saveXxx()` 同步 API 以及 `xxxFlow: Flow<T>` 响应式流，UI 层通过 Hilt 注入后直接 collect；复杂对象使用 Gson + `InstanceCreator` 保证 data class 默认值正确反序列化。
- **DI 绑定**：`app/src/main/java/com/schedulecalendar/app/di/PreferencesModule.kt` 将 `AppPreferences` 以 `@Singleton` 暴露给 Hilt，消费方无需关心 Context 传递。
- **命名约定**：所有 key 集中在 companion object 中以 `KEY_` 前缀声明，字符串型布尔值统一用 `"true"/"false"` 或 `!= "false"` 判断，集合类以逗号分隔字符串存取。
- **默认值策略**：读取时若 key 不存在则返回硬编码默认值（如保留份数默认 5、提前分钟默认 15、提醒方式默认 `alarm`），并提供 `clearAll()` 一键恢复出厂。

## 设计决策与约束
- 未引入 `.env`、`application.properties`、BuildConfig 字段或多渠道 flavor，所有可配置项均走 DataStore，便于热更新与跨进程共享（Widget/Glance 通过同一 DataStore 文件读取）。
- 构建期与运行期配置完全解耦：Gradle 负责编译期产物与依赖版本，DataStore 负责用户偏好与功能开关。