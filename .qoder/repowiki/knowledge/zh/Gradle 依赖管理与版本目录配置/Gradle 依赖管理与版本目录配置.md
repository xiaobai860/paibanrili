---
kind: dependency_management
name: Gradle 依赖管理与版本目录配置
category: dependency_management
scope:
    - '**'
source_files:
    - gradle/libs.versions.toml
    - settings.gradle.kts
    - app/build.gradle.kts
    - build.gradle.kts
    - gradle/wrapper/gradle-wrapper.properties
    - gradle.properties
---

## 1. 构建系统与依赖管理工具
该项目采用 **Gradle (Kotlin DSL)** 作为构建系统和依赖管理工具，具体版本为 **8.14.5**。项目遵循现代 Android 开发的最佳实践，使用了以下核心机制：

- **Version Catalogs (版本目录)**：通过 `gradle/libs.versions.toml` 文件集中管理所有第三方库的版本号和依赖坐标。这种方式取代了传统的 `ext` 变量或 `buildSrc`，提供了更好的类型安全、自动补全和跨模块共享能力。
- **Plugin Aliases**：在顶层 `build.gradle.kts` 中通过 `alias(libs.plugins.xxx)` 声明插件，并在子模块中应用，确保插件版本与依赖版本同步。
- **KSP (Kotlin Symbol Processing)**：使用 KSP 替代已废弃的 KAPT 进行注解处理（如 Room 和 Hilt），显著提升了编译速度。

## 2. 关键配置文件

| 文件路径 | 作用描述 |
| :--- | :--- |
| `gradle/libs.versions.toml` | **核心依赖清单**。定义了所有库的版本号 (`[versions]`)、依赖坐标 (`[libraries]`) 和插件 ID (`[plugins]`)。 |
| `settings.gradle.kts` | **仓库配置与项目结构**。定义了 Maven 仓库的优先级顺序，并启用了 `FAIL_ON_PROJECT_REPOS` 模式，强制所有依赖必须在 settings 中统一声明。 |
| `app/build.gradle.kts` | **模块依赖声明**。通过 `implementation(libs.xxx)` 引用版本目录中的依赖，并配置了 Android 特定的构建选项（如 ProGuard、签名配置）。 |
| `gradle/wrapper/gradle-wrapper.properties` | **Gradle 分发配置**。指定了 Gradle 发行版的下载地址（使用腾讯云镜像）和版本。 |
| `gradle.properties` | **全局构建属性**。配置了 JVM 内存参数、并行 GC、配置缓存以及 AndroidX 支持。 |

## 3. 架构与约定

### 3.1 仓库镜像策略
为了在国内网络环境下获得更快的下载速度，项目在 `settings.gradle.kts` 中配置了多层级的 Maven 仓库镜像：
1. **阿里云镜像**：优先用于 Google、Public 和 Gradle Plugin 仓库。
2. **华为云镜像**：作为备用源，覆盖阿里云可能未同步的 Artifact。
3. **JitPack**：专门用于获取第三方开源库（如 `WheelPickerCompose`）。
4. **官方源**：Google、MavenCentral 和 GradlePluginPortal 作为最终兜底。

### 3.2 依赖注入与代码生成
- **Hilt**：用于依赖注入，通过 `ksp` 处理器生成代码。
- **Room**：用于本地数据库操作，同样通过 `ksp` 生成 DAO 实现类，并开启了增量编译和 Schema 导出（位于 `app/schemas`）。

### 3.3 版本管理约定
- **BOM 管理**：使用 `androidx.compose:compose-bom` 来统一管理 Compose 相关库的版本，避免版本冲突。
- **语义化版本**：在 `libs.versions.toml` 中明确标注了主要库的版本，如 Kotlin `2.0.21`、AGP `8.13.0`、Room `2.7.1` 等。

## 4. 开发者规则与建议

1. **新增依赖**：严禁在 `build.gradle.kts` 中硬编码版本号。所有新依赖必须先在 `gradle/libs.versions.toml` 中定义版本和坐标，然后通过 `libs.xxx` 引用。
2. **仓库修改**：若需添加新的私有仓库或第三方仓库，必须在 `settings.gradle.kts` 的 `dependencyResolutionManagement` 块中声明，因为项目启用了 `FAIL_ON_PROJECT_REPOS`。
3. **编译优化**：项目已启用配置缓存 (`org.gradle.configuration-cache=true`) 和并行 GC，开发时应尽量保持构建脚本的稳定性以利用缓存加速。
4. **代码生成**：涉及 Room 或 Hilt 的修改后，若遇到编译错误，可尝试清理构建缓存 (`./gradlew clean`) 以重新触发 KSP 代码生成。