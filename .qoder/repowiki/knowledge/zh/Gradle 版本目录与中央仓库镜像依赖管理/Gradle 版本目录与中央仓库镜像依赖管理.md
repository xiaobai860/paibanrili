---
kind: dependency_management
name: Gradle 版本目录与中央仓库镜像依赖管理
category: dependency_management
scope:
    - '**'
source_files:
    - gradle/libs.versions.toml
    - settings.gradle.kts
    - build.gradle.kts
    - app/build.gradle.kts
    - gradle.properties
---

本项目采用 Gradle 8.x + Kotlin DSL 构建系统，通过 **Version Catalog（libs.versions.toml）** 统一管理所有第三方库的版本与依赖声明，实现单一事实来源的依赖治理。

**核心机制**
- `gradle/libs.versions.toml` 集中定义 `[versions]` 和 `[libraries]`，所有模块通过 `alias(libs.xxx)` 引用，避免硬编码版本号。
- 顶层 `build.gradle.kts` 仅声明插件别名，`app/build.gradle.kts` 通过 `alias(libs.plugins.*)` 应用插件，确保插件版本与版本目录一致。
- `settings.gradle.kts` 中启用 `dependencyResolutionManagement` 并设置 `RepositoriesMode.FAIL_ON_PROJECT_REPOS`，禁止子模块自行声明仓库源，强制全局统一。

**仓库策略**
- 优先使用阿里云镜像（google/public/gradle-plugin）和华为云镜像加速下载，最终兜底官方 `google()`、`mavenCentral()`、`gradlePluginPortal()`。
- JitPack 作为第三方库（如 WheelPickerCompose）的补充源。
- 通过 `org.gradle.toolchains.foojay-resolver-convention` 自动解析 JDK 工具链。

**关键依赖分类**
- Android/Compose：AGP 8.13.0、Kotlin 2.0.21、Compose BOM 2025.05.01、Navigation Compose 2.8.9
- 数据层：Room 2.7.1（KSP 注解处理器）、DataStore Preferences 1.1.4
- DI：Hilt 2.52（KSP 替代 kapt）
- 异步：Kotlin Coroutines 1.9.0
- 其他：Gson 2.11.0、Glance 1.1.1（桌面组件）、tyme4j 1.5.1（农历计算）

**构建优化**
- `gradle.properties` 启用配置缓存（`configuration-cache=true`）和构建缓存（`caching=true`），JVM 堆内存 4GB。
- Room schema 导出至 `app/schemas/` 目录追踪数据库迁移历史。
- ProGuard 开启混淆与资源压缩，Lint 禁用部分警告以适配日期格式版本号。

**开发者规范**
- 新增依赖必须添加到 `libs.versions.toml`，禁止在模块 build.gradle.kts 中直接写版本号。
- 不允许在子模块 `repositories {}` 中声明新仓库，需通过 settings.gradle.kts 统一管理。
- Hilt/Room 等注解处理器统一使用 KSP，不再使用 kapt。