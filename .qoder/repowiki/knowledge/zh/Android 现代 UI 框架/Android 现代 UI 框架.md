---
kind: external_dependency
name: Android 现代 UI 框架
slug: android-jetpack-compose
category: external_dependency
category_hints:
    - framework_behavior
scope:
    - '**'
---

### Android Jetpack Compose
- **角色**: 应用的主要 UI 框架，用于构建所有界面组件
- **集成点**: 所有 UI 文件位于 `app/src/main/java/com/schedulecalendar/app/ui/` 目录下
- **使用模式**: 采用 MVVM + Hilt 依赖注入架构，使用 Compose BOM 管理版本
- **关键特性**: Material3 设计系统、响应式状态管理、类型安全导航
- **验证**: 参考官方 Compose 文档确认 API 使用方法