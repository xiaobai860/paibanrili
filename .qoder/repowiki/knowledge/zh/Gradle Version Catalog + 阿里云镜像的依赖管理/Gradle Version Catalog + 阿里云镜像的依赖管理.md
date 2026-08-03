---
kind: dependency_management
name: Gradle Version Catalog + 阿里云镜像的依赖管理
category: dependency_management
scope:
    - '**'
source_files:
    - gradle/libs.versions.toml
    - settings.gradle.kts
    - build.gradle.kts
    - app/build.gradle.kts
---

本仓库采用 Gradle Version Catalog（libs.versions.toml）统一管理所有第三方库版本，并通过 settings.gradle.kts 中的 dependencyResolutionManagement 集中配置 Maven 源与插件源。

### 1. 使用的系统与工具
- Gradle Version Catalog：所有版本号集中在 gradle/libs.versions.toml 的 [versions] 段，依赖声明通过 version.ref = xxx 引用，避免散落的硬编码版本。
- Compose BOM：使用 androidx.compose:compose-bom 统一 Compose 子模块版本，各 implementation(libs.compose.ui) 等不再指定版本。
- KSP 替代 kapt：Hilt、Room 注解处理器全部走 KSP（ksp(libs.hilt.compiler) / ksp(libs.room.compiler)），构建更快。
- Foojay Toolchain Resolver：通过 org.gradle.toolchains.foojay-resolver-convention 自动拉取 JDK 17，无需开发者本地预装。

### 2. 关键文件
- gradle/libs.versions.toml — 全局版本与 library/alias 定义
- settings.gradle.kts — 仓库源、插件源、RepositoriesMode.FAIL_ON_PROJECT_REPOS 强制约束
- build.gradle.kts（顶层）— 仅声明插件别名并 apply false
- app/build.gradle.kts — 应用模块通过 alias(libs.plugins.*) 和 libs.* 引入依赖

### 3. 架构与约定
- 单一真源：repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS) 禁止在子模块中重复声明仓库，所有源集中在 settings.gradle.kts。
- 镜像优先：Maven 源顺序为 阿里云 → 华为云 → JitPack → Google/MavenCentral/GradlePluginPortal，加速国内下载；官方源作为兜底。
- 版本分层：Android/Kotlin/AGP/Hilt 等核心工具链在 [versions] 顶部集中维护；业务库（tyme4j、wheel-picker-compose、documentfile）与 AndroidX 库并列，按功能分组注释。
- 插件即依赖：Android、Kotlin、Hilt、KSP、Serialization 等插件也通过 Version Catalog 的 [plugins] 段引用，确保插件版本与 Kotlin/AGP 对齐。

### 4. 开发者应遵循的规则
- 新增依赖时：先在 gradle/libs.versions.toml 的 [versions] 中声明版本号，再在 [libraries] 中定义 alias，最后在 app/build.gradle.kts 中以 implementation(libs.xxx) 引用；不要在任意 build 脚本里写死版本字符串。
- 不要自行添加仓库：所有仓库已在 settings.gradle.kts 中集中配置，子模块不得再写 repositories { ... }，否则构建会失败。
- 升级策略：优先更新 Version Catalog 中的版本号；Compose 相关库通过 BOM 自动跟随，无需逐个改版本。
- JDK 版本：由 Foojay 自动解析，保持 compileOptions 与 kotlinOptions.jvmTarget 为 Java 17 即可。