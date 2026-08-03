---
kind: build_system
name: Android Gradle 构建系统（AGP + Kotlin + Compose）
category: build_system
scope:
    - '**'
source_files:
    - build.gradle.kts
    - app/build.gradle.kts
    - settings.gradle.kts
    - gradle/libs.versions.toml
    - gradle.properties
---

## 构建系统与工具链

本项目采用 **Android Gradle Plugin (AGP) 8.13.0** + **Kotlin 2.0.21** 的现代化 Android 构建体系，使用 `build.gradle.kts` 脚本和版本目录（Version Catalog）管理依赖。

### 核心构建配置
- **顶层 build.gradle.kts**：仅声明插件别名，不应用到 root，保持简洁
- **app/build.gradle.kts**：应用所有插件（Android Application、Kotlin Android、Compose、Hilt、KSP），定义编译选项、签名配置、构建类型
- **settings.gradle.kts**：集中管理仓库源（阿里云镜像优先 + 华为云备用 + 官方兜底）、依赖解析策略（FAIL_ON_PROJECT_REPOS 禁止子模块自定义仓库）
- **gradle/libs.versions.toml**：统一版本管理，所有依赖通过版本号引用，避免硬编码

### 构建特性
- **Java/Kotlin 17**：compile/target compatibility 均为 Java 17
- **Room Schema 导出**：KSP 生成 schema 文件到 `app/schemas/` 目录，便于数据库迁移追踪
- **ProGuard/R8 混淆**：release 构建启用代码压缩和资源缩减
- **Lint 定制**：禁用 HighAppVersionCode、IconLauncherShape 等警告，关闭 release lint 以避免 AS 菜单构建时的文件系统锁问题
- **Gradle 优化**：启用配置缓存（configuration-cache）和构建缓存，JVM 堆内存 4GB

### 依赖管理策略
- 使用 **Compose BOM** 统一管理 Compose 组件版本
- **Hilt 与 Room 均使用 KSP** 替代 kapt，提升编译速度
- 仓库优先级：阿里云 > 华为云 > JitPack > Google > MavenCentral
- 强制 FAIL_ON_PROJECT_REPOS，确保依赖来源可控

### 版本命名策略
- versionCode/versionName 均采用日期格式 `YYYYMMDDNN`（如 2026072601），便于快速识别发布时间

### 未发现的 CI/CD 配置
仓库中未发现 GitHub Actions、GitLab CI、Jenkins 等持续集成配置文件，也未见 Dockerfile 或发布脚本。构建流程主要依赖本地 Gradle 命令（`./gradlew assembleDebug` / `assembleRelease`）。