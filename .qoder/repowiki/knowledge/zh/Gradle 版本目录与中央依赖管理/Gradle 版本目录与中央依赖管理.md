---
kind: dependency_management
name: Gradle 版本目录与中央依赖管理
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

本项目采用 Gradle Kotlin DSL + Version Catalog（libs.versions.toml）的集中式依赖管理模式，所有第三方库与插件版本统一在根级 `gradle/libs.versions.toml` 中声明，子模块通过 `alias(libs.plugins.xxx)` 和 `libs.xxx` 引用，避免硬编码版本号。

**核心机制**
- 版本目录文件：`gradle/libs.versions.toml`，包含 `[versions]`、`[libraries]`、`[plugins]` 三段，聚合 AGP、Kotlin、Compose BOM、Hilt、Room、DataStore、Coroutines、Gson、Glance、tyme4j、WheelPickerCompose、DocumentFile 等全部依赖。
- 顶层 `build.gradle.kts` 仅声明插件并 `apply false`，实际插件由 `app/build.gradle.kts` 通过 `alias(libs.plugins.*)` 引入。
- 子模块 `app/build.gradle.kts` 的 `dependencies {}` 块完全使用 `libs.*` 引用，无一处直接写版本号。

**仓库源与镜像策略**
- `settings.gradle.kts` 中通过 `dependencyResolutionManagement.repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)` 强制禁止子模块自行配置仓库，确保全项目统一源。
- 仓库优先级：阿里云镜像（google/public/gradle-plugin）→ 华为云镜像 → JitPack（用于 WheelPickerCompose 等 GitHub 库）→ 官方 google()/mavenCentral()/gradlePluginPortal() 兜底。
- `pluginManagement.repositories` 单独配置插件下载源，与依赖仓库分离。

**构建与缓存优化**
- `gradle.properties` 启用 `org.gradle.configuration-cache=true` 和 `org.gradle.caching=true`，配合 `-Xmx4g -XX:+UseParallelGC` 提升构建速度。
- 使用 `org.gradle.toolchains.foojay-resolver-convention` 自动解析 JDK Toolchain（指向 Android Studio 内置 JBR）。
- KSP 替代 kapt 处理 Hilt 与 Room 注解处理器，提升编译性能。

**约束与规范**
- 禁止子模块自定义仓库（FAIL_ON_PROJECT_REPOS），所有依赖必须通过 libs.versions.toml 暴露。
- Compose 依赖统一走 `compose-bom` 平台依赖，避免版本冲突。
- 版本号集中在 `[versions]` 段，库引用使用 `version.ref = "xxx"` 间接引用，便于批量升级。