---
kind: frontend_style
name: Compose Material3 主题与视觉设计系统
category: frontend_style
scope:
    - '**'
source_files:
    - app/src/main/java/com/schedulecalendar/app/ui/theme/Color.kt
    - app/src/main/java/com/schedulecalendar/app/ui/theme/Theme.kt
    - app/src/main/java/com/schedulecalendar/app/ui/theme/Typography.kt
    - app/src/main/res/values/themes.xml
    - app/src/main/java/com/schedulecalendar/app/MainActivity.kt
---

该 Android 应用采用 **Jetpack Compose + Material Design 3** 作为前端样式体系，通过统一的 Theme 模块管理颜色、排版和暗色模式，实现一致的视觉风格。

### 1. 使用的系统与工具
- **UI 框架**：Jetpack Compose（声明式 UI）
- **设计系统**：Material Design 3（MaterialComponents for Compose），使用 `MaterialTheme` 提供 ColorScheme、Typography、Shape 等设计令牌
- **动态取色**：Android 12+ 支持 Material You 动态配色（`dynamicLightColorScheme` / `dynamicDarkColorScheme`）
- **传统主题桥接**：`themes.xml` 中定义 AppCompat 主题，仅用于 Activity 透明状态栏/导航栏，所有界面由 Compose 控制

### 2. 核心文件与包
- `app/src/main/java/com/schedulecalendar/app/ui/theme/Color.kt` — 定义全部设计令牌：主色调（绿色系）、辅色（蓝色）、中性灰阶、语义色（错误/警告）、节假日/休息专用色，以及 18 种高区分度班次预设色
- `app/src/main/java/com/schedulecalendar/app/ui/theme/Theme.kt` — `ScheduleCalendarTheme` 根主题组件，封装明/暗色色板、动态取色逻辑、Preview 兼容处理
- `app/src/main/java/com/schedulecalendar/app/ui/theme/Typography.kt` — 统一字体规范（headline/body/label 三级字号与行高）
- `app/src/main/res/values/themes.xml` — AppCompat 桥接主题，设置透明系统栏以配合 `enableEdgeToEdge()`
- `app/src/main/java/com/schedulecalendar/app/MainActivity.kt` — 应用入口，在最外层包裹 `ScheduleCalendarTheme { ... }`

### 3. 架构与约定
- **单一主题入口**：所有 Screen 都嵌套在 `ScheduleCalendarTheme` 之下，通过 `MaterialTheme.colorScheme.*` 和 `MaterialTheme.typography.*` 访问设计令牌，禁止硬编码颜色值
- **明暗双主题**：LightColors 与 DarkColors 分别定义，默认跟随系统暗色模式；Android 12+ 优先使用系统壁纸动态取色，降级回静态色板
- **预览安全**：`LocalInspectionMode.current` 检测 Preview 环境，避免动态取色在无 Activity Context 时崩溃
- **语义化命名**：颜色按用途分组（主色/辅色/中性/语义/业务），如 `Green700`、`RedError`、`HolidayRed`、`ShiftPresetColors` 列表
- **Material3 语义色**：广泛使用 `colorScheme.primary`、`onSurfaceVariant`、`error`、`outline` 等语义令牌，而非具体色值

### 4. 开发者应遵循的规则
- **禁止硬编码颜色**：所有颜色必须从 `MaterialTheme.colorScheme` 或 `theme/Color.kt` 中的命名常量获取
- **使用语义色**：背景用 `background`/`surface`，文字用 `onBackground`/`onSurface`，强调用 `primary`/`secondary`，错误用 `error`/`onError`
- **排版统一**：文本样式使用 `MaterialTheme.typography` 的预定义层级（headlineLarge → labelSmall），不要自定义 fontSize/fontWeight
- **暗色模式适配**：新增颜色时必须同时考虑明/暗色板下的对比度和可读性
- **动态取色兼容**：在 Preview 或非 Android 12+ 设备上应回退到静态色板，确保预览正常显示
- **班次颜色扩展**：新增班次预设色时，保持 18 色方案的高区分度原则，避免相邻色相近