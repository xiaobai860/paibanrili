---
kind: design
name: 使用 Hilt EntryPoint 让小组件 ActionCallback 直接访问 Repository
source: session
category: adr
---

# 使用 Hilt EntryPoint 让小组件 ActionCallback 直接访问 Repository

_来源：e7fa8b2 → d09a0a9 提交周期内记录的编码计划——内容为规划时意图，实现可能滞后或有出入。_

**状态：** accepted

## 背景
小组件 ScheduleGlanceWidget 的 ActionCallback 运行在 Glance 环境中，无法通过常规依赖注入获取 Repository。需要在不启动 Activity 的情况下让 Widget 能够直接调用 scheduleRepository、shiftRepository 等依赖进行打卡数据写入。

## 决策驱动
- 无需启动 Activity 即可访问依赖
- 避免在 Widget 中重复实现业务逻辑
- 保持与主应用一致的依赖管理

## 备选方案
- **Hilt EntryPointAccessors + SingletonComponent** — 优点：无需启动进程，直接从 Glance 环境获取已存在的单例依赖；符合 Android 官方推荐的小组件依赖注入方式；缺点：需要显式声明 @EntryPoint 接口；对依赖的生命周期有要求
- **在 ActionCallback 中手动创建数据库实例** _（已否决）_ — 优点：简单直接；缺点：绕过依赖注入，导致代码重复和难以测试；无法复用已有的 Repository 逻辑

## 决策
在 widget/ScheduleGlanceWidget.kt 中定义 WidgetClockEntryPoint，通过 @EntryPoint + @InstallIn(SingletonComponent::class) 暴露 ScheduleRepository、ShiftRepository、ShiftStatusRepository，ActionCallback 中使用 EntryPointAccessors.getApplicationContext(context, WidgetClockEntryPoint::class.java) 获取依赖。

## 影响
小组件可以直接操作数据库而不需要启动 Activity，减少了进程间通信开销。但需要注意依赖必须在 SingletonComponent 中可用，且要处理可能的空指针异常（如依赖未正确初始化）。