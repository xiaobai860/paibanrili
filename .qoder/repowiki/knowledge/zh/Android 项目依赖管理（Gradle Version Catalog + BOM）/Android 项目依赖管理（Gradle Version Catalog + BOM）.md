---
kind: dependency_management
name: Android 项目依赖管理（Gradle Version Catalog + BOM）
category: dependency_management
scope:
    - '**'
source_files:
    - gradle/libs.versions.toml
    - settings.gradle.kts
    - app/build.gradle.kts
    - build.gradle.kts
    - gradle.properties
---

## 依赖管理系统概述

该项目采用现代 Android 项目的标准依赖管理方案，基于 Gradle Version Catalog、Compose BOM 和阿里云镜像加速构建。

## 核心架构

### 1. 版本集中管理（Version Catalog）
- **核心文件**: `gradle/libs.versions.toml`
- 所有第三方库版本在 `[versions]` 段统一定义
- 库别名在 `[libraries]` 段声明，通过 `version.ref` 引用统一版本
- 插件版本在 `[plugins]` 段管理，确保插件与 Kotlin/AGP 版本兼容

### 2. 仓库源配置策略
- **主仓库**: 阿里云镜像（google/public/gradle-plugin）优先，提供最快下载速度
- **备用仓库**: 华为云 Maven 仓库，覆盖阿里云未同步的 artifact
- **官方兜底**: google()、mavenCentral()、gradlePluginPortal()
- **第三方支持**: JitPack（用于 WheelPickerCompose 等 GitHub 库）
- **安全策略**: `RepositoriesMode.FAIL_ON_PROJECT_REPOS` 禁止子模块自定义仓库

### 3. Compose BOM 统一管理
- 使用 `compose-bom: 2025.05.01` 统一管理所有 Compose 组件版本
- 避免 Compose 各组件版本冲突问题
- 简化依赖声明，无需为每个 Compose 库指定版本

### 4. 构建工具链管理
- 使用 `org.gradle.toolchains.foojay-resolver-convention` 自动管理 JDK 版本
- 强制 Java 17 兼容性（compile/targetCompatibility = 17）
- Kotlin 2.0.21 配合 KSP 替代 kapt，提升编译性能

## 关键依赖分类

### Android 框架层
- AndroidX Core (1.16.0)、AppCompat (1.7.0)、Activity Compose (1.10.1)
- Lifecycle (2.9.1) 配合 ViewModel Compose 集成
- DataStore Preferences (1.1.4) 替代 SharedPreferences

### UI 层
- Jetpack Compose BOM 统一管理 UI 组件
- Navigation Compose (2.8.9) 类型安全路由
- Material3 设计系统
- Glance (1.1.1) 桌面小组件支持

### 数据持久化
- Room (2.7.1) + KSP 注解处理器
- Gson (2.11.0) JSON 序列化

### 业务功能
- Hilt (2.52) 依赖注入
- Coroutines (1.9.0) 异步编程
- Tyme4j (1.5.1) 农历计算
- WheelPickerCompose (1.1.11) 滚轮选择器

## 开发者规范

1. **新增依赖**: 必须在 `libs.versions.toml` 中声明版本和别名
2. **版本更新**: 修改 `[versions]` 段的版本号，保持相关库版本协调
3. **仓库访问**: 不得在子模块添加自定义仓库，统一由 settings.gradle.kts 管理
4. **Compose 依赖**: 必须通过 BOM 引入，不单独指定版本
5. **KSP 迁移**: 新项目使用 KSP 而非 kapt，提升编译性能