---
kind: design
name: 使用带 LifecycleOwner 的 addCallback 注册返回键回调
source: session
category: adr
---

# 使用带 LifecycleOwner 的 addCallback 注册返回键回调

_来源：eff95f8 → 367f6d7 提交周期内记录的编码计划——内容为规划时意图，实现可能滞后或有出入。_

**状态：** accepted

## 背景
Tab 页面按返回键无法正确退出，经过 10+ 次尝试后发现：无 LifecycleOwner 的 `addCallback(cb)` 调用中 `remove()` 是空操作，导致僵尸回调累积在 dispatcher；而 `DisposableEffect` 中注册的时序又受 MIUI/Android 版本差异影响不可靠。

## 决策驱动
- 确保 remove() 真正从 dispatcher 移除回调
- 生命周期时序在不同 ROM/Android 版本上稳定可靠
- 避免僵尸回调导致的 enable 状态锁死

## 备选方案
- **addCallback(callback) 无 LifecycleOwner** _（已否决）_ — 优点：API 简单；缺点：remove() 不生效，回调永远留在 dispatcher 中形成僵尸回调
- **在 DisposableEffect 中使用 addCallback(LifecycleOwner, callback)** _（已否决）_ — 优点：自动绑定生命周期；缺点：与 NavController 内部 observer 注册顺序因系统版本差异不可靠，时序不稳定
- **在 onStart() 中使用 addCallback(this@MainActivity, callback)** — 优点：onStart 时 lifecycle 已是 STARTED，LifecycleAwareCancellable 立即加入 dispatcher，remove() 通过 LifecycleAwareCancellable.cancel() 真正从 dispatcher 移除，责任链顺序可控（navCb 在前，ourCb 在后）；缺点：需要手动管理 onStart/onStop 中的 remove/add 时机

## 决策
在 MainActivity.onStart() 中通过 addCallback(this@MainActivity, cb) 注册返回键回调，配合 backPressedCallback?.remove() 清理旧回调，确保 ourCb 排在 navCb 之后、反向遍历时最先执行，根据 isOnTabPage 决定是否 finish() 退出。

## 影响
解决了 Tab 页面返回直接退出的问题，同时保持子页面逐层返回后再退出的行为。但需确保每次 onStart 都先 remove 再 add，避免重复注册。