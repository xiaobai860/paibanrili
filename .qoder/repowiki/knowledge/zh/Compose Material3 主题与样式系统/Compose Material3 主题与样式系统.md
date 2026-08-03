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
    - app/src/main/res/values/themes.xml
---

本项目采用 Jetpack Compose + Material Design 3（Material3）作为前端 UI 框架，通过集中式主题文件统一管理颜色、排版和暗色模式，实现视觉一致性。核心架构如下：

**1. 设计系统与工具链**
- UI 框架：Jetpack Compose + Material3
- 主题入口：`ScheduleCalendarTheme` 组合函数，封装 Light/Dark 双主题与 Android 12+ 动态取色（Material You）
- 传统 XML 主题仅用于 Activity 桥接（`Theme.ScheduleCalendar`），状态栏/导航栏设为透明以配合 `enableEdgeToEdge()`

**2. 设计令牌（Design Tokens）**
- **颜色体系**（`Color.kt`）：按语义分层定义
  - 主色调：绿色系（Green700/Green600/Green100/Green50）
  - 辅色：蓝色（Blue600/Blue100）
  - 中性色：Gray900→Gray50 六级灰度
  - 语义色：错误红、警告橙、白/黑
  - 业务语义：节假日红、休息灰、补贴绿、扣款红、分类标签三色
  - 状态角标：早退橙、备注青
  - 班次预设：18 色高区分度调色板（2 行×9 列，暖冷交替）
- **排版体系**（`Typography.kt`）：Material3 标准层级（headline/title/body/label），统一字号与行高

**3. 主题应用约定**
- 所有 Composable 通过 `MaterialTheme.colorScheme.*` 和 `MaterialTheme.typography.*` 访问样式
- 预览环境使用静态色板避免 `dynamicColorScheme` 崩溃（`LocalInspectionMode` 检测）
- 组件内不硬编码颜色值，全部引用主题令牌

**4. 资源组织**
- Compose 主题代码位于 `app/src/main/java/com/schedulecalendar/app/ui/theme/`
- 传统资源（图标、启动图、字符串）位于 `res/` 目录
- 无 CSS/SCSS/Tailwind 等 Web 样式技术