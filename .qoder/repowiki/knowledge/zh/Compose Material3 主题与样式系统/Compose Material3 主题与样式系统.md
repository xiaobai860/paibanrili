---
kind: frontend_style
name: Compose Material3 主题与样式系统
category: frontend_style
scope:
    - '**'
source_files:
    - app/src/main/java/com/schedulecalendar/app/ui/theme/Color.kt
    - app/src/main/java/com/schedulecalendar/app/ui/theme/Theme.kt
    - app/src/main/java/com/schedulecalendar/app/ui/theme/Typography.kt
    - app/src/main/java/com/schedulecalendar/app/ui/component/CommonComponents.kt
    - app/src/main/res/values/themes.xml
---

## 样式系统概述

该项目采用 **Jetpack Compose + Material Design 3** 作为前端 UI 框架，通过集中式主题配置实现跨屏幕一致的视觉风格。

## 核心架构

### 主题定义层（`ui/theme/`）
- **Color.kt**: 定义完整的色板体系，包括主色调（绿色系）、辅色（蓝色）、中性灰阶、语义色（错误/警告）以及18个高区分度班次预设颜色
- **Theme.kt**: `ScheduleCalendarTheme` 组合函数提供主题切换，支持 Android 12+ 动态取色（Material You）和静态色板回退
- **Typography.kt**: 基于 Material3 Typography 的自定义字体规范，定义了从 headlineLarge 到 labelSmall 的完整文本层级

### 组件库层（`ui/component/`）
- **CommonComponents.kt**: 封装可复用 UI 组件，如 `ScheduleTopBar`、`StatCard`、`ColorPicker`、`TimePickerField` 等
- 所有组件严格遵循 Material3 设计语言，使用 `MaterialTheme.colorScheme` 和 `MaterialTheme.typography`

### 应用入口
- **MainActivity.kt**: 通过 `ScheduleCalendarTheme` 包裹整个应用，确保主题一致性
- **themes.xml**: Activity 桥接主题，仅设置透明状态栏以配合 Compose 的 edge-to-edge 模式

## 设计决策

1. **Material3 优先**: 全面采用 Material3 组件和 API，避免硬编码颜色值
2. **动态主题支持**: 自动检测系统深色模式和 Android 12+ 动态取色能力
3. **预览友好**: 在 Preview 模式下使用静态色板，避免无 Context 环境崩溃
4. **响应式设计**: 通过 Compose Modifier 和布局约束实现自适应界面
5. **无障碍支持**: 遵循 Material3 对比度和可访问性标准

## 开发规范

- 所有颜色必须通过 `MaterialTheme.colorScheme.*` 或主题色常量引用
- 文本样式统一使用 `MaterialTheme.typography.*` 预定义样式
- 圆角半径使用 `RoundedCornerShape(12.dp)` 等标准尺寸
- 间距遵循 4dp/8dp/16dp 的倍数规则
- 图标使用 Material Icons，通过 `Icons.Filled.*` 或 `Icons.AutoMirrored.*` 引用