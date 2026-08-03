---
kind: dependency_management
name: Android Gradle + Version Catalog 依赖管理
category: dependency_management
scope:
    - '**'
source_files:
    - gradle/libs.versions.toml
    - settings.gradle.kts
    - app/build.gradle.kts
    - build.gradle.kts
    - gradle.properties
---

本项目采用 Android Gradle 构建系统，结合 **Version Catalog（libs.versions.toml）** 统一管理所有第三方库版本与插件，通过 `settings.gradle.kts` 集中配置仓库源并禁止子模块自行声明仓库，确保依赖来源可控、版本一致。

**1. 版本目录（Version Catalog）集中管理**
- 所有依赖版本定义在 `gradle/libs.versions.toml` 的 `[versions]` 段，如 AGP 8.13.0、Kotlin 2.0.21、Compose BOM 2025.05.01、Room 2.7.1、Hilt 2.52、Coroutines 1.9.0 等。
- 依赖别名在 `[libraries]` 段统一声明，使用 `version.ref = "xxx"` 引用版本号，避免硬编码；部分独立版本（如 hilt-navigation-compose 1.2.0、kotlinx-serialization-json 1.7.3）直接指定。
- 插件在 `[plugins]` 段集中声明（android-application、kotlin-android、kotlin-compose、kotlin-serialization、hilt、ksp），顶层 `build.gradle.kts` 仅用 `apply false` 注册，`app/build.gradle.kts` 按需启用。

**2. 依赖声明与模块化**
- `app/build.gradle.kts` 中通过 `implementation(libs.xxx)` 引用依赖，按功能分组：Android Core、Compose（通过 BOM 管理）、Navigation、Hilt、Room、DataStore、Lifecycle、Glance、Coroutines、Gson、Serialization、WheelPicker、tyme4j、DocumentFile。
- Compose 组件全部通过 `platform(libs.compose.bom)` 引入，保证 UI 相关库版本兼容。
- 编译期注解处理器统一使用 KSP（`ksp(libs.hilt.compiler)`、`ksp(libs.room.compiler)`），不再使用 kapt。

**3. 仓库源与镜像策略**
- `settings.gradle.kts` 的 `dependencyResolutionManagement.repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)` 强制禁止子模块自定义仓库，所有依赖必须通过根级配置。
- 仓库优先级：阿里云镜像（google/public/gradle-plugin）→ 华为云镜像 → JitPack（用于 WheelPickerCompose 等 GitHub 库）→ 官方 google() / mavenCentral() / gradlePluginPortal() 兜底。
- `pluginManagement` 同样配置阿里云和华为云镜像，加速插件下载。

**4. 构建优化与约束**
- `gradle.properties` 启用配置缓存（`org.gradle.configuration-cache=true`）和构建缓存（`org.gradle.caching=true`），JVM 参数 `-Xmx4g -XX:+UseParallelGC` 提升内存。
- Java/Kotlin 目标版本固定为 17，`compileOptions` 与 `kotlinOptions.jvmTarget` 保持一致。
- Room schema 导出至 `app/schemas` 目录便于迁移追踪，lint 禁用若干无关警告（HighAppVersionCode、IconLauncherShape 等）。

**5. 关键约束**
- 禁止子模块声明自己的 repositories 或 plugins（由 FAIL_ON_PROJECT_REPOS 强制执行）。
- 所有依赖版本必须通过 Version Catalog 管理，不得在模块 build.gradle.kts 中硬编码版本号。
- 第三方库优先从阿里云/华为云镜像拉取，JitPack 仅用于 GitHub 托管库。