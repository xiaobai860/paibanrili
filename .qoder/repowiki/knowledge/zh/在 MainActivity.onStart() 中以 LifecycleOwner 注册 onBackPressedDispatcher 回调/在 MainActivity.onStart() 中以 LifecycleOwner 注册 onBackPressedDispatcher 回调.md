---
kind: design
name: 在 MainActivity.onStart() 中以 LifecycleOwner 注册 onBackPressedDispatcher 回调
source: session
category: adr
---

# 在 MainActivity.onStart() 中以 LifecycleOwner 注册 onBackPressedDispatcher 回调

_来源：366011c → 4954d64 提交周期内记录的编码计划——内容为规划时意图，实现可能滞后或有出入。_

**状态：** accepted

## 背景
Tab 页面返回键行为异常：多次按返回无法退出，且存在僵尸回调累积。经过十余次尝试后发现根因是 `addCallback(callback)` 不带 LifecycleOwner 时 `remove()` 为空操作，导致每轮 onStart 遗留一个 enable 被锁死的回调；而改用 `DisposableEffect` + `addCallback(LifecycleOwner, callback)` 又因 MIUI/Android 版本差异导致生命周期时序不可靠，无法保证后于 NavController 的 observer 注册。

## 决策驱动
- 回调 remove() 必须真正生效
- 与 NavController 回调的注册顺序可预测
- 兼容不同厂商 ROM 的生命周期时序

## 备选方案
- **onStart() + addCallback(callback) 无 LifecycleOwner** _（已否决）_ — 优点：实现简单；缺点：remove() 不生效，产生僵尸回调；每轮 onStart 累积一个锁死回调
- **DisposableEffect + addCallback(LifecycleOwner, callback)** _（已否决）_ — 优点：自动生命周期管理；缺点：在不同 Android/MIUI 版本上时序不稳定，无法保证晚于 NavController 注册
- **onStart() + addCallback(this@MainActivity, callback)** — 优点：LifecycleAwareCancellable 立即 ON_START 加入 dispatcher；remove() 通过 LifecycleAwareCancellable.cancel() 真正从 dispatcher 移除；时序稳定可靠；缺点：需手动调用 remove() 清理

## 决策
在 MainActivity.onStart() 中调用 `onBackPressedDispatcher.addCallback(this@MainActivity, cb)`，利用 LifecycleAwareCancellable 确保回调在 STARTED 阶段立即加入 dispatcher（位于 NavController 回调之后），并通过 `cb.remove()` 真正从 dispatcher 移除，避免僵尸回调累积。

## 影响
责任链变为 [..., navCb, ourCb]，反向遍历时 ourCb 最先命中，当 isOnTabPage=true 则直接 finish() 退出；子页面逐层返回回到 Tab 后再按返回才退出应用。移除了对 DisposableEffect 的依赖，降低了对系统生命周期时序的脆弱性假设。