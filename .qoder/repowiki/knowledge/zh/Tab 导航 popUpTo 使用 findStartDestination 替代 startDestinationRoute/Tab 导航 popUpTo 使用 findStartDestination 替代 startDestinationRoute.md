---
kind: design
name: Tab 导航 popUpTo 使用 findStartDestination 替代 startDestinationRoute
source: session
category: adr
---

# Tab 导航 popUpTo 使用 findStartDestination 替代 startDestinationRoute

_来源：367f6d7 → 366011c 提交周期内记录的编码计划——内容为规划时意图，实现可能滞后或有出入。_

**状态：** accepted

## 背景
Tab onClick 导航时使用 `popUpTo(navController.graph.startDestinationRoute ?: return@navigate)` 在某些情况下可能导致栈底锚点不准确，因为 startDestinationRoute 是字符串而 findStartDestination() 返回的是 Destination ID。

## 决策驱动
- 保持 Tab 作为栈底锚点的稳定性
- 遵循 Google Navigation 组件推荐模式
- 避免字符串路由与 Destination ID 混用带来的歧义

## 备选方案
- **popUpTo(startDestinationRoute)** _（已否决）_ — 优点：语义直观；缺点：startDestinationRoute 是字符串，可能与实际 Destination ID 不一致；某些路由配置下可能为空回退
- **popUpTo(findStartDestination().id)** — 优点：直接引用当前图的实际起始 Destination ID；Google 官方示例推荐写法；与 launchSingleTop + restoreState 配合可稳定保留 Tab 为栈底；缺点：稍显冗长

## 决策
将 `popUpTo(navController.graph.startDestinationRoute)` 替换为 `popUpTo(navController.graph.findStartDestination().id)`，同时保留 `launchSingleTop = true` 和 `restoreState = true`。

## 影响
Tab 始终作为导航栈底锚点，从任意子页面返回时能准确回到对应 Tab 而非销毁重建；与返回键修复后的生命周期管理形成一致的栈行为。