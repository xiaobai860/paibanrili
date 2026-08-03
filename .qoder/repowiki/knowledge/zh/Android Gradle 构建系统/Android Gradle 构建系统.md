---
kind: build_system
name: Android Gradle 构建系统
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

该项目使用基于 Gradle Kotlin DSL 的 Android 构建系统，采用版本目录（Version Catalog）统一管理依赖与插件，配合 KSP、Hilt、Room、Compose 等现代 Android 开发工具链。

## 构建系统与工具链

- **构建脚本**: 顶层 build.gradle.kts 仅声明插件并设置 apply false，实际构建配置集中在 app/build.gradle.kts
- **Gradle 版本管理**: 通过 gradle/libs.versions.toml 集中管理 AGP 8.13.0、Kotlin 2.0.21、KSP、Hilt 2.52、Compose BOM 2025.05.01 等所有依赖版本
- **插件管理**: 使用 alias(libs.plugins.*) 统一引用插件，包括 android.application、kotlin.android、kotlin.compose、kotlin.serialization、hilt、ksp
- **JVM 配置**: Java/Kotlin 目标版本均为 17，Gradle JVM 内存设置为 4GB 并使用 ParallelGC

## 依赖管理与仓库策略

- **仓库优先级**: 阿里云镜像（主）→ 华为云镜像（备用）→ JitPack（第三方库）→ Google/Maven Central（兜底）
- **依赖解析模式**: RepositoriesMode.FAIL_ON_PROJECT_REPOS 强制禁止在模块级 build.gradle 中声明仓库，确保依赖来源可控
- **Toolchain 管理**: 通过 org.gradle.toolchains.foojay-resolver-convention 自动管理 JDK 工具链

## 构建变体与签名

- **Debug 变体**: 可调试，无混淆
- **Release 变体**: 启用代码混淆（R8）、资源压缩、ProGuard 规则、APK 签名（签名文件硬编码在构建脚本中）
- **版本号策略**: 使用日期格式 versionCode = versionName = 2026080301（年月日+序号），并通过 lint 禁用 HighAppVersionCode 警告

## 编译特性与优化

- **Compose**: 启用 Compose 编译选项，使用独立 Compose Compiler 插件（Kotlin 2.0+ 方式）
- **KSP**: 替代 kapt 用于 Hilt 和 Room 注解处理，提升编译性能
- **Room Schema**: 导出 schema 到 app/schemas/ 目录便于数据库迁移追踪
- **JNI 打包**: 启用 useLegacyPackaging = true 控制 .so 库打包方式

## 构建配置优化

- **Gradle 缓存**: 启用配置缓存和构建缓存（configuration-cache=true, caching=true）
- **Lint 配置**: 禁用部分警告（HighAppVersionCode、IconLauncherShape、IconDuplicates、UnusedAttribute、NewerVersionAvailable、ReportShortcutUsage），release 构建跳过 lint 以避免 IDE 锁文件问题
- **资源排除**: 排除 META-INF 中的 AL2.0/LGPL2.1 许可证文件

## 项目结构

- **单模块应用**: 仅包含 :app 模块，无多模块架构
- **命名空间**: com.schedulecalendar.app
- **SDK 版本**: minSdk=26, targetSdk=compileSdk=36