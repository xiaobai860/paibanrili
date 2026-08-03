---
kind: dependency_management
name: Gradle 版本目录与镜像仓库策略
category: dependency_management
scope:
    - '**'
source_files:
    - gradle/libs.versions.toml
    - settings.gradle.kts
    - build.gradle.kts
    - app/build.gradle.kts
---

本仓库采用 Gradle Version Catalog（gradle/libs.versions.toml）统一管理所有第三方库的版本，并通过 settings.gradle.kts 集中配置 Maven 镜像源，形成单一事实来源加强制集中解析的依赖管理方案。

1. 版本集中声明（Version Catalog）
- 所有版本号集中在 gradle/libs.versions.toml 的 [versions] 段，如 AGP 8.13.0、Kotlin 2.0.21、Compose BOM 2025.05.01、Room 2.7.1、Hilt 2.52 等；
- 每个 artifact 在 [libraries] 中通过 version.ref = xxx 引用统一版本，避免各模块重复声明；
- 插件 ID 也在 [plugins] 中集中定义，顶层 build.gradle.kts 仅用 apply false 注册，子模块按需 alias(libs.plugins.xxx) 应用。

2. 依赖解析与仓库源
- settings.gradle.kts 启用 dependencyResolutionManagement.repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)，禁止子模块自行声明仓库，确保所有依赖解析路径唯一可控；
- 仓库优先级：阿里云镜像（google/public/gradle-plugin）→ 华为云备用 → JitPack（用于 WheelPickerCompose 等 GitHub 发布包）→ 官方 google()/mavenCentral()/gradlePluginPortal() 兜底；
- 使用 org.gradle.toolchains.foojay-resolver-convention 自动管理 JDK Toolchain，无需手动安装特定 JDK。

3. 关键约定与约束
- 新增依赖必须先在 libs.versions.toml 的 [versions] 和 [libraries] 中声明，再在 app/build.gradle.kts 中以 implementation(libs.xxx) 引用，禁止硬编码版本号；
- Compose 相关依赖全部走 compose-bom 平台导入，不单独指定版本；
- Hilt 与 Room 均迁移到 KSP 注解处理器（ksp(libs.hilt.compiler) / ksp(libs.room.compiler)），不再使用 kapt；
- 项目为单模块（仅 :app），无多 module 拆分，因此不存在跨模块依赖冲突问题。

4. 未覆盖的场景
- 无私有 Maven/Nexus 仓库配置，也未见 vendor 或本地 aar 引入；
- 无依赖更新自动化脚本或 CI 检查任务。