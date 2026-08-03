---
kind: frontend_style
name: 基于 Material3 的 Compose 主题与组件体系
category: frontend_style
scope:
    - '**'
source_files:
    - app/src/main/java/com/schedulecalendar/app/ui/theme/Theme.kt
    - app/src/main/java/com/schedulecalendar/app/ui/theme/Color.kt
    - app/src/main/java/com/schedulecalendar/app/ui/theme/Typography.kt
    - app/src/main/res/values/themes.xml
    - app/src/main/java/com/schedulecalendar/app/MainActivity.kt
    - app/src/main/java/com/schedulecalendar/app/ui/component/CommonComponents.kt
---

## 1. 系统/技术栈
- UI 框架：Android Jetpack Compose（声明式 UI）
- 设计系统：Material Design 3（Material3），通过 `MaterialTheme` 注入颜色、排版、形状等主题令牌
- 动态取色：Android 12+ 启用 `dynamicLightColorScheme` / `dynamicDarkColorScheme`，旧设备回退到自定义静态色板
- 状态栏/导航栏：`enableEdgeToEdge()` + AppCompat 透明主题，实现沉浸式边到边显示

## 2. 核心文件与包
- 主题入口：`app/src/main/java/com/schedulecalendar/app/ui/theme/Theme.kt` — `ScheduleCalendarTheme` 根 Composable，统一注入 `colorScheme`、`typography`
- 颜色定义：`app/src/main/java/com/schedulecalendar/app/ui/theme/Color.kt` — 主色（绿色系）、辅色（蓝色）、中性灰阶、语义色（错误/警告）、业务语义色（补贴/扣款/分类标签）、班次预设 18 色
- 排版定义：`app/src/main/java/com/schedulecalendar/app/ui/theme/Typography.kt` — Material3 Typography 各层级字号、字重、行高
- Activity 桥接主题：`app/src/main/res/values/themes.xml` — `Theme.ScheduleCalendar` 继承 `DayNight.NoActionBar`，状态栏/导航栏透明以配合 edge-to-edge
- 应用入口：`app/src/main/java/com/schedulecalendar/app/MainActivity.kt` — `setContent { ScheduleCalendarTheme { ... } }` 包裹整个应用 UI
- 公共组件库：`app/src/main/java/com/schedulecalendar/app/ui/component/CommonComponents.kt` — 顶部栏、统计卡片、月份导航、设置行、时间选择器、IME 自适应输入框等复用组件

## 3. 架构与约定
- **单一主题根**：所有页面通过 `ScheduleCalendarTheme` 包裹，确保全局颜色、字体一致；预览模式使用静态色板避免无 Context 崩溃
- **Material3 语义化配色**：组件一律通过 `MaterialTheme.colorScheme.*` 取值，不硬编码 Color，保证暗色/动态色自动适配
- **Typography 分层**：headline/title/body/label 四级体系，各 Screen 直接引用 `MaterialTheme.typography.*`，禁止在组件内重复定义 TextStyle
- **组件库集中管理**：通用 UI 片段（TopBar、StatCard、TimePickerField、ImeAdaptiveOutlinedTextField 等）集中在 `ui.component` 包，按功能命名并带详细注释说明行为与约束
- **Activity 层极简**：`themes.xml` 仅配置 NoActionBar 和透明系统栏，所有视觉样式下沉到 Compose 层

## 4. 约定与约束
- 颜色必须从 `theme/Color.kt` 中导出，禁止在业务代码中直接使用 `Color(0xFF...)` 硬编码（除 18 色班次数组外）
- 所有可组合项必须通过 `MaterialTheme.colorScheme` 获取前景/背景/边框色，确保暗色模式与动态取色生效
- 文本样式统一走 `MaterialTheme.typography` 对应层级，不得自行设定 fontSize/fontWeight
- 组件库中的 `stableLabelColors()` 强制 OutlinedTextField 的 label 始终常驻（focused/unfocused 同色），禁用浮动标签动画
- IME 交互统一使用 `ImeAdaptiveOutlinedTextField`，支持 Column 滚动与 LazyColumn 回调两种模式，避免键盘遮挡
- 时间选择器统一封装为 `TimePickerField` / `ExpandableTimePicker`，内部使用 Material3 `TimePicker` 对话框，格式固定为 "HH:mm" 24 小时制
- 班次颜色选择器限定 18 种预设色（ShiftPresetColors），采用两行 9 列布局，高饱和与低饱和交替排列以保证区分度