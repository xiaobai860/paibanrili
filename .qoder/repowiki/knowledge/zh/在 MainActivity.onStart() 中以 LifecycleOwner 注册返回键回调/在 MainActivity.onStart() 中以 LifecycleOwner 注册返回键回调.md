---
kind: design
name: 在 MainActivity.onStart() 中以 LifecycleOwner 注册返回键回调
source: session
category: adr
---

# 在 MainActivity.onStart() 中以 LifecycleOwner 注册返回键回调

_来源：4954d64 → 756f38d 提交周期内记录的编码计划——内容为规划时意图，实现可能滞后或有出入。_

**状态：** accepted

## 背景
Tab 页面按返回键的行为长期异常：多次尝试（10+ 次）后定位到根因是 `onBackPressedDispatcher.addCallback(cb)` 未传入 `LifecycleOwner`，导致 `cb.remove()` 成为空操作，每轮 `onStart()` 都遗留一个 enable 被锁死的僵尸回调；而改用 `DisposableEffect` + `addCallback(LifecycleOwner, cb)` 又因 MIUI/Android 版本差异导致与 NavController 内部 observer 的注册时序不可靠。

## 决策驱动
- 确保 remove() 真正从 dispatcher 移除回调
- 保证自定义回调注册顺序稳定在 NavController 之后
- 兼容不同厂商 ROM 的生命周期时序差异

## 备选方案
- **无参 addCallback(callback)** _（已否决）_ — 优点：写法简单；缺点：remove() 不生效，产生僵尸回调累积
- **在 DisposableEffect 中用 addCallback(LifecycleOwner, callback) 注册** _（已否决）_ — 优点：自动生命周期管理；缺点：注册时机受系统版本影响，无法保证晚于 NavController 内部 observer
- **在 onStart() 中用 addCallback(this@MainActivity, callback) 注册并先 remove() 再 add** — 优点：LifecycleAwareCancellable 立即 ON_START 加入 dispatcher，remove() 可真正清理旧回调，时序确定可靠；缺点：需在 Activity 生命周期方法中手动维护

## 决策
在 `MainActivity.onStart()` 中调用 `backPressedCallback?.remove()` 清理旧回调后，再用 `onBackPressedDispatcher.addCallback(this@MainActivity, cb)` 注册新回调，使自定义回调以 `LifecycleAwareCancellable` 形式加入责任链尾部，从而在 Tab 页直接 finish()。

## 影响
彻底消除僵尸回调导致的返回行为错乱；同时配合 `AppNavHost.kt` 中将 `popUpTo(navController.graph.startDestinationRoute)` 改为 `popUpTo(navController.graph.findStartDestination().id)`，保留 startDestination 作为栈底锚点，避免 popUpTo 误删导航栈底。