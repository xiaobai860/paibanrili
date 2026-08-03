---
kind: build_system
name: Gradle构建与发布体系
category: build_system
scope:
    - '**'
source_files:
    - build.gradle.kts
    - settings.gradle.kts
    - app/build.gradle.kts
    - gradle/libs.versions.toml
    - gradle.properties
    - app/proguard-rules.pro
---

该项目采用标准的 **Android Gradle** 构建系统，基于 **Kotlin DSL** (`build.gradle.kts`) 进行配置。整体架构遵循现代 Android 开发的最佳实践，包括版本目录管理、KSP 代码生成以及 Jetpack Compose 的独立编译器插件。

### 1. 核心构建工具与依赖管理
- **构建工具**: 使用 **Gradle** (通过 `gradlew` 包装器) 和 **Android Gradle Plugin (AGP)** 8.13.0。
- **依赖管理**: 采用 **Version Catalogs** (`gradle/libs.versions.toml`) 统一管理所有第三方库的版本和坐标，实现了依赖声明与版本控制的解耦。
- **镜像加速**: 在 `settings.gradle.kts` 中配置了阿里云和华为云的 Maven 镜像，以优化国内环境下的依赖下载速度。
- **代码生成**: 使用 **KSP (Kotlin Symbol Processing)** 替代传统的 Kapt，用于处理 Hilt 依赖注入和 Room 数据库的代码生成，显著提升了编译效率。

### 2. 编译与优化策略
- **JVM 目标**: 统一使用 **Java 17** 作为编译和目标兼容性版本。
- **Compose 编译**: 启用了 Kotlin 2.0+ 的独立 Compose Compiler 插件，不再依赖旧的 `kotlinCompilerExtensionVersion`。
- **Release 优化**: 
  - 开启 **R8** 代码压缩与混淆 (`isMinifyEnabled = true`)。
  - 开启资源压缩 (`isShrinkResources = true`)。
  - 配置了详细的 `proguard-rules.pro`，重点保护 Hilt 注入点、Room 实体、Gson 序列化模型以及 Glance 小组件相关类，防止运行时崩溃。
- **Lint 检查**: 在 Release 构建中禁用了部分非关键 Lint 检查（如 `HighAppVersionCode`、图标形状等），并关闭了 `abortOnError` 以避免因警告导致构建中断。

### 3. 版本管理与签名
- **版本号策略**: 采用 **日期格式** (`YYYYMMDDNN`) 作为 `versionCode` 和 `versionName`（例如 `2026072701`），便于直观追踪发布历史。
- **签名配置**: 在 `build.gradle.kts` 中硬编码了 Release 签名的密钥库路径和密码（注意：在生产环境中建议通过环境变量或本地属性文件管理敏感信息）。

### 4. 开发者规范
- **构建命令**: 推荐使用 `./gradlew assembleDebug` 进行调试包构建，或 `./gradlew assembleRelease` 进行发布包构建。
- **缓存利用**: `gradle.properties` 中开启了配置缓存 (`org.gradle.configuration-cache=true`) 和构建缓存，开发者应尽量避免在构建脚本中使用非确定性逻辑以最大化缓存命中率。
- **Schema 管理**: Room 数据库的 Schema 文件导出至 `app/schemas` 目录，开发者在进行数据库迁移时应关注这些 JSON 文件的变更。