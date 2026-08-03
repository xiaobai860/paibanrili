---
kind: design
name: Tab 导航 popUpTo 使用 findStartDestination().id 替代 startDestinationRoute
source: session
category: adr
---

# Tab 导航 popUpTo 使用 findStartDestination().id 替代 startDestinationRoute

_来源：eff95f8 → 367f6d7 提交周期内记录的编码计划——内容为规划时意图，实现可能滞后或有出入。_

**状态：** accepted

## 背景
Tab onClick 导航时使用 `startDestinationRoute` 作为 popUpTo 锚点不够健壮，因为 route 字符串可能变化或为 null，而 `findStartDestination().id` 是 Google 官方推荐的标准模式。

## 决策驱动
- 遵循 Navigation Compose 官方最佳实践
- 避免依赖可能变化的 route 字符串
- 保持 launchSingleTop + restoreState 的组合语义

## 备选方案
- **popUpTo(startDestinationRoute) 带 inclusive=true** _（已否决）_ — 优点：直观地弹出到起始路由；缺点：依赖 route 字符串存在且不变；inclusive=true 会删除栈底本身，不符合保留栈底的意图
- **popUpTo(findStartDestination().id) 不带 inclusive** — 优点：Google 官方标准写法；以 destination id 为锚点更稳定；不移除栈底，符合 Tab 切换语义；缺点：需要理解 id 与 route 的区别

## 决策
将 AppNavHost.kt 中 Tab onClick 的 popUpTo 目标改为 `navController.graph.findStartDestination().id`，去掉 inclusive=true，保留 launchSingleTop=true 和 restoreState=true。

## 影响
Tab 切换时能正确保留 startDestination 作为栈底锚点，同时维持单例 top 和状态恢复的行为。