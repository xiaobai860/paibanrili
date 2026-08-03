---
kind: dependency_management
name: Gradle 版本目录与镜像仓库集中管理
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

该 Android 多模块工程采用 Gradle 版本目录（Version Catalog）机制进行依赖管理，通过 `gradle/libs.versions.toml` 文件集中声明所有第三方库的版本号，并在各模块中通过 `alias(libs.xxx)` 引用。构建系统配置了阿里云和华为云 Maven 镜像作为首选源，JitPack 用于获取 GitHub 上的第三方库，官方 Google 和 Maven Central 作为兜底源。项目使用 `RepositoriesMode.FAIL_ON_PROJECT_REPOS` 策略禁止子模块自行声明仓库源，确保依赖来源的统一性和可追溯性。插件管理通过顶层 `build.gradle.kts` 统一声明并标记为 `apply false`，在应用模块中按需启用。构建性能优化包括开启配置缓存、并行 GC 和禁用 Jetifier。