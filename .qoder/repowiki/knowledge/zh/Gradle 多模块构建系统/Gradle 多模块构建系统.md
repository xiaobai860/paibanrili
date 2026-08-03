---
kind: build_system
name: Gradle 多模块构建系统
category: build_system
scope:
    - '**'
source_files:
    - settings.gradle.kts
    - build.gradle.kts
    - gradle/libs.versions.toml
    - app/build.gradle.kts
    - gradle.properties
---

## 构建系统概述

该项目采用 Gradle Kotlin DSL 作为核心构建系统，基于 Android Gradle Plugin (AGP) 8.13.0 和 Kotlin 2.0.21，使用版本目录（Version Catalog）集中管理依赖和插件版本。

## 核心架构

### 版本目录管理
- **gradle/libs.versions.toml**: 统一管理所有依赖版本，包括 AGP、Kotlin、Compose BOM、Hilt、Room、Navigation 等核心库
- 通过 `[versions]` 和 `[libraries]` 段定义版本号和依赖映射
- 通过 `[plugins]` 段声明项目级插件及其版本

### 仓库镜像策略
- **主镜像**: 阿里云 Maven 仓库（google/public/gradle-plugin）
- **备用镜像**: 华为云 Maven 仓库
- **官方兜底**: google()、mavenCentral()、gradlePluginPortal()
- **第三方源**: JitPack（用于 WheelPickerCompose 等 GitHub 库）

### 模块结构
- **单应用模块**: 仅包含 `:app` 子模块，根项目不直接包含业务代码
- **settings.gradle.kts**: 通过 `include(":app")` 引入应用模块
- **顶层 build.gradle.kts**: 仅声明插件并设置 `apply false`，避免在根项目应用

## 构建配置要点

### 应用模块配置 (app/build.gradle.kts)
- **命名空间**: `com.schedulecalendar.app`
- **SDK 版本**: compileSdk/targetSdk = 36, minSdk = 26
- **Java 版本**: 17 (sourceCompatibility/targetCompatibility/jvmTarget)
- **版本号策略**: 使用日期格式 `2026071501`（年+月+日+序号），禁用 HighAppVersionCode lint 警告

### 构建类型优化
- **Release 模式**: 启用代码混淆 (minifyEnabled)、资源压缩 (shrinkResources)
- **ProGuard 规则**: 使用 `proguard-android-optimize.txt` + 自定义 `proguard-rules.pro`

### Compose 集成
- 使用独立 Compose Compiler 插件 (`kotlin-compose`)，无需 `composeOptions.kotlinCompilerExtensionVersion`
- 通过 Compose BOM 统一管理 UI 组件版本

### 依赖注入与数据层
- **Hilt**: 使用 KSP 替代 kapt 进行注解处理
- **Room**: 配置 schema 导出到 `app/schemas/` 目录，支持数据库迁移追踪
- **DataStore**: 使用 preferences 存储用户设置

## Gradle 性能优化
- **JVM 参数**: `-Xmx4g -XX:+UseParallelGC`
- **配置缓存**: `org.gradle.configuration-cache=true`
- **构建缓存**: `org.gradle.caching=true`
- **Java 路径**: 固定使用 Android Studio 内置 JBR

## 开发工具链
- **Kotlin 版本**: 2.0.21
- **KSP 版本**: 2.0.21-1.0.28（与 Kotlin 版本对齐）
- **Android Gradle Plugin**: 8.13.0
- **Compose BOM**: 2025.05.01

## 关键约束
- 禁止在子项目中重复声明仓库源（`RepositoriesMode.FAIL_ON_PROJECT_REPOS`）
- 强制使用 AndroidX（`android.useAndroidX=true`）
- 禁用 Jetifier（`android.enableJetifier=false`）
- 统一 Kotlin 代码风格（`kotlin.code.style=official`）