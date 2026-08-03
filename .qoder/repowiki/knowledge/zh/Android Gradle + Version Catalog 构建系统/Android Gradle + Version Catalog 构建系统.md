---
kind: build_system
name: Android Gradle + Version Catalog 构建系统
category: build_system
scope:
    - '**'
source_files:
    - settings.gradle.kts
    - build.gradle.kts
    - app/build.gradle.kts
    - gradle/libs.versions.toml
    - gradle.properties
---

## 1. 构建系统与工具链
- 构建系统：Gradle Kotlin DSL（`build.gradle.kts`），AGP 8.13.0，Kotlin 2.0.21。
- 依赖版本管理：使用 `gradle/libs.versions.toml`（Version Catalog）集中声明所有插件与库版本，模块内通过 `alias(libs.plugins.xxx)`、`libs.xxx` 引用，避免硬编码版本号。
- 源码/字节码级别：Java/Kotlin 目标 17，启用 Compose Compiler 独立插件（不再需要 `composeOptions.kotlinCompilerExtensionVersion`）。
- 代码生成：全部迁移到 KSP（Hilt、Room 均使用 `ksp()` 而非 `kapt`），并导出 Room schema 到 `app/schemas/` 目录用于数据库迁移追踪。
- 仓库源：优先阿里云镜像（google/public/gradle-plugin）、华为云备用，兜底 google() / mavenCentral()；第三方 JitPack 库通过 `dependencyResolutionManagement` 全局引入。
- JVM 与缓存：配置 `-Xmx4g -XX:+UseParallelGC`，开启 Configuration Cache 与 Build Cache；强制使用 Android Studio 自带的 JBR（`org.gradle.java.home`）。

## 2. 关键文件与职责
- `settings.gradle.kts`：定义 pluginManagement 与 dependencyResolutionManagement 的仓库源、JitPack 接入、foojay toolchain resolver、单模块 `:app` 包含关系。
- `build.gradle.kts`（顶层）：仅声明各子模块需用的插件并 `apply false`，不直接参与编译。
- `app/build.gradle.kts`：应用层构建脚本，声明 namespace、compileSdk/targetSdk/minSdk、versionCode/versionName、buildTypes（release 开启 minify/shrinkResources + ProGuard）、lint 策略、KSP 参数、packaging 选项、Compose 特性开关以及全部依赖项。
- `gradle/libs.versions.toml`：统一版本中心，按功能域分组（core-ktx、Compose BOM、Navigation、Room、DataStore、Hilt、Glance、tyme4j 等）。
- `gradle.properties`：Gradle 运行期参数与 AndroidX/Jetifier 开关。

## 3. 架构与约定
- 单模块结构：根项目只包含 `:app` 一个模块，无 library 子模块，构建产物即最终 APK/AAB。
- 版本策略：`versionCode` 与 `versionName` 采用日期格式（如 `2026071847`），在 lint 中显式禁用 `HighAppVersionCode` 警告以兼容此约定。
- 构建变体：仅定义 `debug`（默认）与 `release` 两种 buildType；release 开启混淆与资源压缩，使用 `proguard-rules.pro`。
- Lint 策略：`abortOnError = false`，跳过 release 阶段的 `lintVitalAnalyzeRelease` 以避免 AS 菜单触发时的文件系统锁冲突，同时批量禁用若干无关警告（图标形状、重复、UnusedAttribute、NewerVersionAvailable 等）。
- Room schema 管理：通过 KSP 参数 `room.schemaLocation` 将每次编译生成的 schema JSON 输出到 `app/schemas/com.schedulecalendar.app.data.db.AppDatabase/`，便于人工审查与迁移。
- 打包优化：`packaging.jniLibs.useLegacyPackaging = true`，并排除 `/META-INF/{AL2.0,LGPL2.1}` 以减少包体积冲突。

## 4. 开发者应遵循的规则
- **新增依赖**：一律在 `gradle/libs.versions.toml` 的 `[versions]` 与 `[libraries]` 中声明，模块内通过 `libs.xxx` 引用，禁止在 `app/build.gradle.kts` 中硬编码版本号。
- **新增插件**：先在 `libs.versions.toml` 的 `[plugins]` 段注册，再在顶层 `build.gradle.kts` 用 `alias(...).apply false` 声明，最后在 `app/build.gradle.kts` 中 `alias(libs.plugins.xxx)` 应用。
- **版本升级**：修改 `libs.versions.toml` 中的版本号即可全局生效，无需逐个模块调整。
- **数据库变更**：修改 Room Entity 后重新编译，确保 `app/schemas/.../AppDatabase/<N>.json` 被正确生成并提交，以便后续迁移。
- **发布流程**：release 构建由 AGP 自动生成签名 AAB/APK，ProGuard 规则位于 `app/proguard-rules.pro`；如需自定义签名或上传分发平台，可在 CI 中调用 `./gradlew bundleRelease assembleRelease`。
- **仓库源**：不要在各模块 `build.gradle.kts` 中重复声明 `repositories`，统一走 `settings.gradle.kts` 的 `dependencyResolutionManagement`，否则会被 `RepositoriesMode.FAIL_ON_PROJECT_REPOS` 拒绝。