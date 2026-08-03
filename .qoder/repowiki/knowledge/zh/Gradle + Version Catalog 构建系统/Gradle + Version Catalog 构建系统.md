---
kind: build_system
name: Gradle + Version Catalog 构建系统
category: build_system
scope:
    - '**'
source_files:
    - settings.gradle.kts
    - gradle/libs.versions.toml
    - build.gradle.kts
    - app/build.gradle.kts
    - gradle.properties
---

## 1. 构建系统与工具链
- **Gradle Kotlin DSL**：顶层与模块均使用 `build.gradle.kts`，通过 `settings.gradle.kts` 统一仓库与插件管理。
- **Version Catalog（libs.versions.toml）**：所有依赖版本集中在 `gradle/libs.versions.toml`，通过 `[versions]`、`[libraries]`、`[plugins]` 三段式声明，模块内以 `alias(libs.plugins.xxx)` / `libs.xxx` 引用，避免硬编码版本号。
- **Android Gradle Plugin (AGP) 8.13.0 + Kotlin 2.0.21**：启用独立 Compose Compiler 插件 (`kotlin-compose`)，不再需要 `composeOptions.kotlinCompilerExtensionVersion`。
- **KSP 替代 kapt**：Hilt 与 Room 全部走 KSP（`com.google.devtools.ksp`），编译更快。
- **JVM/Java 17**：`compileOptions.sourceCompatibility/targetCompatibility = JavaVersion.VERSION_17`，`kotlinOptions.jvmTarget = "17"`。
- **Gradle 性能优化**：开启配置缓存 (`org.gradle.configuration-cache=true`) 与构建缓存 (`org.gradle.caching=true`)，JVM 堆 `-Xmx4g`，使用 ParallelGC；通过 `org.gradle.toolchains.foojay-resolver-convention` 自动解析 JDK。

## 2. 关键文件与职责
| 文件 | 作用 |
|---|---|
| `settings.gradle.kts` | 仓库源（阿里云优先 → 华为云备用 → JitPack → google/mavenCentral）、插件管理、项目名与模块包含 |
| `gradle/libs.versions.toml` | 全局版本与依赖声明中心 |
| `build.gradle.kts`（根） | 仅声明插件并 `apply false`，不应用到 root |
| `app/build.gradle.kts` | 应用模块构建脚本：namespace、SDK、签名、混淆、Room schema 导出、lint 策略、依赖汇总 |
| `gradle.properties` | Gradle JVM 参数、缓存开关、AndroidX/Jetifier、Kotlin 代码风格、本地 JDK 路径 |

## 3. 架构与约定
- **单模块结构**：`include(":app")`，无多模块拆分，所有业务逻辑位于 `app/src/main/java/com/schedulecalendar/app`。
- **版本命名策略**：`versionCode` 与 `versionName` 采用日期格式 `yyyyMMddNN`（如 `2026072002`），在 lint 中显式禁用 `HighAppVersionCode` 警告。
- **Release 产物**：启用 R8 混淆与资源压缩，签名复用 debug key（便于本地测试），ProGuard 规则位于 `proguard-rules.pro`。
- **Room Schema 追踪**：通过 KSP 参数 `room.schemaLocation="$projectDir/schemas"` 将迁移 JSON 导出到 `app/schemas/...`，已存在 v2~v5 历史版本。
- **仓库镜像优先级**：阿里云 Google/Public/Gradle Plugin 三源 → 华为云 → 官方 → JitPack，保证国内网络可快速拉取。
- **Compose BOM**：通过 `platform(libs.compose.bom)` 统一管理 Compose 组件版本，避免版本冲突。

## 4. 开发者应遵循的规则
1. **新增依赖必须写入 `libs.versions.toml`**，禁止在 `app/build.gradle.kts` 中直接写版本号。
2. **保持 AGP/Kotlin/Hilt/Room 等核心库版本联动更新**，注意 KSP 版本需与 Kotlin 版本匹配（当前为 `2.0.21-1.0.28`）。
3. **修改 Room Entity 后提交生成的 schema JSON**，确保数据库迁移可追溯。
4. **发布前检查 `app/build.gradle.kts` 中的 `versionCode`/`versionName` 是否按日期递增**。
5. **如需引入第三方库且不在 Maven Central**，先在 `settings.gradle.kts` 的 `dependencyResolutionManagement.repositories` 中添加对应仓库，再在 `libs.versions.toml` 中声明依赖。
6. **不要修改 `gradle.properties` 中的 Gradle 缓存与 JVM 参数**，除非有明确的性能或兼容性问题。