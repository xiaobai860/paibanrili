---
kind: design
name: 使用 Hilt EntryPoint 实现 Widget 直接访问 Repository
source: session
category: adr
---

# 使用 Hilt EntryPoint 实现 Widget 直接访问 Repository

_来源：d0a3cc0 → cdd68fd 提交周期内记录的编码计划——内容为规划时意图，实现可能滞后或有出入。_

**状态：** accepted

## 背景
Android Glance 小组件的 ActionCallback 运行在独立进程中，无法直接通过常规依赖注入获取应用层的 Repository 实例。传统方案需启动 Activity 或 Service 作为中介来执行数据写入，导致交互延迟高、用户体验割裂且系统资源开销大。

## 决策驱动
- 交互实时性（避免启动 Activity）
- 架构解耦（保持 Widget 逻辑轻量）
- 依赖复用（利用现有的 SingletonComponent）

## 备选方案
- **Hilt EntryPoint + ApplicationContext** — 优点：无需启动 UI 组件，直接在后台回调中获取 Repository 执行 DB 操作；利用 SingletonComponent 保证依赖生命周期一致；代码侵入性小。；缺点：需要手动管理 EntryPoint 的获取逻辑；在极少数进程被杀极端情况下可能需处理初始化边界。
- **启动 Activity/Service 中介** _（已否决）_ — 优点：符合传统 Android 组件通信模式，依赖获取路径标准。；缺点：点击打卡会弹出界面或启动服务，交互不直观；系统开销大；增加用户等待时间。

## 决策
在 `widget/ScheduleGlanceWidget.kt` 中定义 `@EntryPoint` 接口 `WidgetClockEntryPoint`，并通过 `EntryPointAccessors.fromApplication` 结合 `ApplicationContext` 在 `WidgetClockInAction` 和 `WidgetClockOutAction` 中直接获取 `ScheduleRepository` 等依赖。

## 影响
小组件打卡操作将不再触发界面跳转，实现静默、快速的数据持久化。Widget 模块对 Domain 层产生了直接的编译期依赖（通过 EntryPoint），但保持了运行时的解耦。需确保 Repository 的实现是线程安全的，因为 ActionCallback 可能在非主线程执行。