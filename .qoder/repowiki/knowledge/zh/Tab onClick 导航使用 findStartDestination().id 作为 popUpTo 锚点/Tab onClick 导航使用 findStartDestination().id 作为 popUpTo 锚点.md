---
kind: design
name: Tab onClick 导航使用 findStartDestination().id 作为 popUpTo 锚点
source: session
category: adr
---

# Tab onClick 导航使用 findStartDestination().id 作为 popUpTo 锚点

_来源：366011c → 4954d64 提交周期内记录的编码计划——内容为规划时意图，实现可能滞后或有出入。_

**状态：** accepted

## 背景
Tab 切换时导航栈清理策略不一致：之前使用 `navController.graph.startDestinationRoute` 配合 `inclusive = true` 弹出到起始路由，但某些场景下无法正确保留 startDestination 作为栈底锚点。

## 决策驱动
- 遵循 Google 官方推荐模式
- 明确以 startDestination 为栈底锚点
- 保持 launchSingleTop 和 restoreState 语义不变

## 备选方案
- **popUpTo(startDestinationRoute) + inclusive = true** _（已否决）_ — 优点：直观地弹出到起始路由；缺点：在某些导航图配置下行为不确定，可能误删中间目标
- **popUpTo(findStartDestination().id)** — 优点：Google 标准 API，语义清晰；始终定位到当前 graph 的实际 start destination；保留 saveState 恢复状态；缺点：需要理解 id 与 route 的区别

## 决策
将 AppNavHost.kt 中 Tab onClick 的 popUpTo 目标改为 `navController.graph.findStartDestination().id`，并移除 `inclusive = true`，同时保持 `launchSingleTop = true` 和 `restoreState = true` 不变。

## 影响
导航栈始终以 findStartDestination 返回的 Destination ID 为锚点进行清理，行为与 Navigation Compose 官方示例一致，避免了因 route 字符串解析带来的不确定性。