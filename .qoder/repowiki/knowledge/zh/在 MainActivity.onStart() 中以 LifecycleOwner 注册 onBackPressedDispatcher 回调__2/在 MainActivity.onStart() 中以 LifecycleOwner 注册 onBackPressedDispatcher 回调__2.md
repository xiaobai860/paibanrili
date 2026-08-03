---
kind: design
name: 在 MainActivity.onStart() 中以 LifecycleOwner 注册 onBackPressedDispatcher 回调
source: session
category: adr
---

# 在 MainActivity.onStart() 中以 LifecycleOwner 注册 onBackPressedDispatcher 回调

_来源：756f38d → 4cdc979 提交周期内记录的编码计划——内容为规划时意图，实现可能滞后或有出入。_

**状态：** accepted

## 背景
Tab 页面返回键行为异常：多次进入/退出后出现僵尸回调，导致按返回键无法正确退出或行为错乱。经过 10+ 次尝试发现根因是 `addCallback(callback)` 不带 LifecycleOwner 时 `remove()` 无效，回调会永久累积在 dispatcher 中；而使用 `DisposableEffect` 注册又受 MIUI/Android 版本差异影响，生命周期时序不可靠。

## 决策驱动
- 回调必须可被真正移除避免内存泄漏
- 注册时机必须在 NavController 之后以保证责任链顺序
- 在不同 Android 版本和厂商 ROM 上行为一致

## 备选方案
- **无参 addCallback(cb) + onStart 中 remove/add** _（已否决）_ — 优点：实现简单；缺点：remove() 是空操作，每轮 onStart 遗留一个 enable 被锁死的僵尸回调，最终导致返回键失效
- **DisposableEffect 中使用 addCallback(LifecycleOwner, cb)** _（已否决）_ — 优点：自动管理生命周期；缺点：与 NavController 的 observer 注册时序因系统版本差异不稳定，在某些 ROM 上顺序不可控
- **onStart() 中用 addCallback(this@MainActivity, cb) 注册** — 优点：LifecycleAwareCancellable 使 remove() 真正生效；ON_START 时立即加入 dispatcher，保证在 NavController 回调之后；责任链反向遍历时我们的回调最先执行；缺点：需要手动在 onStart 中先 remove 再 add，逻辑稍显繁琐

## 决策
在 MainActivity.onStart() 中调用 backPressedCallback?.remove() 清理旧回调后，以 `onBackPressedDispatcher.addCallback(this@MainActivity, cb)` 方式重新注册，确保 LifecycleAwareCancellable 能正确从 dispatcher 移除自身，且注册时机稳定位于 NavController 回调之后。

## 影响
解决了僵尸回调导致的返回键异常；同时修正了 AppNavHost.kt 中 Tab onClick 导航的 popUpTo 目标为 `findStartDestination().id` 而非 `startDestinationRoute`，保留 startDestination 作为栈底锚点，配合 launchSingleTop 和 restoreState 保持正确的导航栈语义。