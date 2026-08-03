---
kind: build_system
name: Gradle Kotlin DSL 构建系统
category: build_system
scope:
    - '**'
source_files:
    - build.gradle.kts
    - settings.gradle.kts
    - app/build.gradle.kts
    - gradle/libs.versions.toml
    - gradle.properties
---

## 构建系统与工具链

本项目采用 **Gradle Kotlin DSL** 作为 Android 应用的构建系统，基于 AGP 8.13.0 + Kotlin 2.0.21，使用 Version Catalog（libs.versions.toml）统一管理插件与依赖版本。

### 核心构建配置
- **顶层 build.gradle.kts**：仅声明插件并 `apply false`，不直接应用到 root，由子模块按需引入
- **settings.gradle.kts**：集中管理仓库源（阿里云镜像优先、华为云备用、官方源兜底），启用 `FAIL_ON_PROJECT_REPOS` 禁止子模块自定义仓库，确保依赖来源统一可控
- **app/build.gradle.kts**：应用层构建配置，包含命名空间、SDK 版本、签名、编译选项、KSP Room schema 导出、Packaging 优化等
- **gradle/libs.versions.toml**：Version Catalog 定义所有版本号和库引用，实现单一版本源
- **gradle.properties**：JVM 参数（4GB 堆）、配置缓存、增量缓存、AndroidX 开关、Java 路径等全局构建属性

### 构建特性与约定
- **KSP 替代 kapt**：Hilt 和 Room 编译器全部使用 KSP，提升编译性能
- **Compose BOM 管理**：通过 Compose BOM 统一 Compose 相关依赖版本
- **Room Schema 迁移**：schema 文件导出至 `app/schemas/` 目录，便于追踪数据库迁移历史
- **Release 构建优化**：启用代码混淆（R8）和资源压缩，使用 ProGuard 规则文件
- **Lint 策略**：禁用部分警告（如 HighAppVersionCode、图标重复等），跳过 release 的 lint 检查以避免 AS 菜单构建时的文件系统锁问题
- **版本号策略**：versionCode 和 versionName 均采用日期格式（YYYYMMDDNN），便于快速识别发布批次

### 依赖管理与仓库策略
- 优先使用阿里云 Maven 镜像加速下载，华为云作为备用源
- JitPack 用于第三方组件（如 WheelPickerCompose）
- 严格禁止子模块自行添加仓库，防止依赖来源不一致
- 使用 Gradle Toolchains 自动管理 JDK 版本（foojay-resolver-convention）

### 构建性能优化
- 启用 Gradle 配置缓存和构建缓存
- 使用 Parallel GC 和 4GB JVM 堆内存
- Kotlin 代码风格统一为 official
- 指定 Android Studio 内置 JBR 作为 Java 运行环境