---
kind: build_system
name: Android Gradle 单模块构建系统
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

本项目采用 Android Gradle Plugin (AGP) 8.13 + Kotlin 2.0 的单模块构建体系，通过 Version Catalog 集中管理依赖与插件版本，配合阿里云/华为云镜像加速依赖解析。

## 构建工具链
- **Gradle + AGP 8.13**：使用 com.android.application 插件，JVM 目标为 Java 17（Kotlin jvmTarget=17）
- **Kotlin 2.0.21**：启用独立 Compose Compiler 插件（kotlin-compose），不再需要 composeOptions.kotlinCompilerExtensionVersion
- **KSP 替代 kapt**：Hilt 与 Room 全部使用 KSP 注解处理器（ksp(libs.hilt.compiler)、ksp(libs.room.compiler)）
- **Compose BOM 2025.05.01**：统一管理所有 Compose 组件版本

## 版本与发布策略
- **版本号格式**：versionCode 与 versionName 均采用日期格式 YYYYMMDDNN（如 2026071850），便于按日期排序和回滚
- **Release 配置**：开启代码混淆（R8）、资源压缩，签名复用 debug 签名（本地开发用），ProGuard 规则位于 app/proguard-rules.pro
- **Lint 策略**：禁用 HighAppVersionCode 等告警，跳过 release 的 lint 分析以避免 AS 菜单触发时的文件锁问题

## 依赖与仓库
- **Version Catalog**：所有依赖集中在 gradle/libs.versions.toml，通过 alias(libs.plugins.*) 引用插件
- **仓库源优先级**：阿里云镜像 → 华为云备用 → JitPack（第三方库）→ Google/MavenCentral 兜底
- **强制模式**：RepositoriesMode.FAIL_ON_PROJECT_REPOS 禁止子模块自行声明仓库，统一由根级管控
- **Gradle 优化**：启用配置缓存与构建缓存，JVM 堆内存 4GB，使用 ParallelGC

## 关键特性
- **Room Schema 导出**：通过 KSP 参数将 schema 导出到 app/schemas/ 目录，便于追踪数据库迁移历史
- **JNI 打包**：启用 useLegacyPackaging = true 以兼容旧版 .so 打包方式
- **单模块结构**：settings.gradle.kts 仅 include :app，无多模块拆分

## 开发者约定
- 新增依赖时统一在 libs.versions.toml 中声明，避免在 build.gradle.kts 中硬编码版本
- 修改 Room Entity 后需重新编译以生成新的 schema JSON 文件
- 发布前确保 versionCode/versionName 遵循日期递增规则