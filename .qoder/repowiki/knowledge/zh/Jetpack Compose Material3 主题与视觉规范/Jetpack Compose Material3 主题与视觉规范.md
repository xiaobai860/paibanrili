---
kind: frontend_style
name: Jetpack Compose Material3 主题与视觉规范
category: frontend_style
scope:
    - '**'
source_files:
    - app/src/main/java/com/schedulecalendar/app/ui/theme/Theme.kt
    - app/src/main/java/com/schedulecalendar/app/ui/theme/Color.kt
    - app/src/main/java/com/schedulecalendar/app/ui/theme/Typography.kt
    - app/src/main/java/com/schedulecalendar/app/ui/component/CommonComponents.kt
    - app/src/main/java/com/schedulecalendar/app/ui/calendar/CalendarScreen.kt
    - app/src/main/java/com/schedulecalendar/app/ui/navigation/AppNavHost.kt
    - app/src/main/res/values/themes.xml
---

该应用采用 **Jetpack Compose** 作为唯一的 UI 框架，遵循 **Material Design 3 (Material You)** 设计规范。整体视觉风格强调功能性与清晰度，通过语义化色彩和自适应布局提供一致的跨设备体验。

### 1. 核心架构与技术栈
- **UI 框架**: Jetpack Compose (Android Native)。
- **设计系统**: Material3 (`androidx.compose.material3`)。
- **主题管理**: 自定义 `ScheduleCalendarTheme`，支持动态取色（Dynamic Color）和深色模式自动切换。
- **导航结构**: 底部导航栏（Bottom Navigation）+ 嵌套导航图（NavHost），主 Tab 包括日历、事项、统计、班次、设置。

### 2. 色彩体系 (Color System)
色彩定义在 `ui/theme/Color.kt` 中，分为基础色板与业务语义色：
- **主色调 (Primary)**: 绿色系 (`Green700 #059669`)，用于主要按钮、选中状态及今日高亮。
- **辅助色 (Secondary)**: 蓝色系 (`Blue600 #1677FF`)，用于次要操作或链接。
- **背景与表面**: 
  - 浅色模式: `Gray50` 背景, `White` 表面。
  - 深色模式: `#111827` 背景, `#1F2937` 表面。
- **业务语义色**:
  - **节假日/休息**: `HolidayRed (#DC2626)` 用于法定节假日文字及角标；`RestGray` 用于调休。
  - **考勤状态**: `EarlyLeaveOrange` (早退), `RedError` (迟到/错误), `RemarkCyan` (备注)。
  - **财务相关**: `AllowanceGreen` (补贴), `DeductionRed` (扣款)。
  - **班次颜色**: 预设 18 种高区分度颜色 (`ShiftPresetColors`)，用于不同班次的视觉标识。

### 3. 排版与字体 (Typography)
定义在 `ui/theme/Typography.kt` 中，基于 Material3 默认字重进行微调：
- **标题**: `headlineLarge` (24sp/Bold) 用于页面主标题；`titleLarge` (16sp/SemiBold) 用于卡片标题。
- **正文**: `bodyMedium` (14sp/Normal) 为默认文本大小；`labelSmall` (10sp/Medium) 用于紧凑的日历格子内文字。
- **行高**: 严格设定 `lineHeight` 以确保多语言环境下的可读性。

### 4. 组件与布局规范
- **通用组件**: `CommonComponents.kt` 封装了 `StatCard` (统计卡片), `MonthNavigator` (月份切换), `SettingRow` (设置项), `TimePickerField` (时间选择器) 等复用组件。
- **日历网格**: 采用自定义 `HorizontalPager` 实现月份滑动，单元格 `DayCell` 根据状态（今日、选中、节假日、有日程）动态渲染背景色、边框及角标。
- **自适应输入**: `ImeAdaptiveOutlinedTextField` 解决了软键盘遮挡长文本输入框的问题，通过精确计算滚动位置提升用户体验。
- **边缘到边缘 (Edge-to-Edge)**: `themes.xml` 配置透明状态栏/导航栏，配合 Compose 的 `enableEdgeToEdge()` 实现沉浸式布局。

### 5. 开发约定
- **主题调用**: 所有屏幕必须包裹在 `ScheduleCalendarTheme` 中。
- **颜色使用**: 优先使用 `MaterialTheme.colorScheme` 中的语义色（如 `primary`, `onSurface`），避免硬编码 Hex 值，以适配深色模式。
- **预览支持**: `Theme.kt` 中针对 `LocalInspectionMode` 做了特殊处理，确保 Android Studio Preview 在不连接真机的情况下也能正确渲染静态色板。
- **图标资源**: 统一使用 `Icons.Filled` 系列 Material Icons，保持视觉风格统一。