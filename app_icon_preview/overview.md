# 排班日历 · 应用图标设计说明

## 交付形式
**纯 Android 自适应图标（Adaptive Icon），仅使用 XML，不含任何 PNG / SVG / WebP 资源。**
应用 `res/` 目录下未引入任何位图或矢量图文件，启动图标完全由以下三套 drawable XML 构成。

## 关键资源（全部为 XML）
- `app/src/main/res/drawable/ic_app_schedule_background.xml`（背景层：紫调渐变）
- `app/src/main/res/drawable/ic_app_schedule_foreground.xml`（前景层：白日历卡 + 紫月份条 + 琥珀「今日」+ 青「班次条」）
- `app/src/main/res/drawable/ic_app_schedule_mono.xml`（单色层：供 Material You 主题图标使用）
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` 与 `ic_launcher_round.xml`（引用上述三层，无需改动）

## 设计说明
- 保留「日历」品类识别度：白卡 + 紫色月份条。
- 琥珀圆点 = 今天；青色班次条 = 一段工作时段。
- 所有元素控制在 66dp 安全区，适配圆形 / 方圆形 / 圆角矩形等 OEM 遮罩。
- 仅用矢量 XML，矢量可无损缩放，构建体积小、适配 Dark/Theme 取色。

## 预览方式
- `preview.html`：浏览器内联 SVG 自包含展示页（启动器场景、遮罩形态、Material You 主题、色板），不依赖任何外部 png/svg 文件。
- 设备上查看请直接构建 APK；自适应图标在 Android 8.0（minSdk 26）及以上原生支持。
