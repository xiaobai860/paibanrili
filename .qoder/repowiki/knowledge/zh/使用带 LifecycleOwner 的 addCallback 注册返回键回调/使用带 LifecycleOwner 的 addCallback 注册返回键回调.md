---
kind: design
name: 使用带 LifecycleOwner 的 addCallback 注册返回键回调
source: session
category: adr
---

# 使用带 LifecycleOwner 的 addCallback 注册返回键回调

_来源：367f6d7 → 366011c 提交周期内记录的编码计划——内容为规划时意图，实现可能滞后或有出入。_

**状态：** accepted

## 背景
Tab 页面返回键行为在 MIUI/不同 Android 版本上表现不一致，多次尝试后定位到根因：无 LifecycleOwner 的 `addCallback(cb)` 注册的回调无法通过 `remove()` 真正从 dispatcher 移除，导致每轮 onStart 累积僵尸回调并锁死 enable 状态。

## 决策驱动
- 回调生命周期必须与 Activity 严格绑定
- remove() 必须能真正清理 dispatcher 中的回调
- 避免跨版本时序差异导致的竞态

## 备选方案
- **addCallback(callback) 无 LifecycleOwner** _（已否决）_ — 优点：API 更简单；缺点：remove() 是空操作，回调永远留在 dispatcher 中；每次 onStart 残留一个已 enable 的僵尸回调
- **DisposableEffect 中用 addCallback(LifecycleOwner, callback)** _（已否决）_ — 优点：理论上随 effect 销毁自动移除；缺点：注册时机晚于 NavController 的 observer，且 MIUI/Android 版本对 ON_START 触发顺序不可靠，仍可能产生时序问题
- **onStart() 中 addCallback(this@MainActivity, cb)** — 优点：LifecycleAwareCancellable 立即 ON_START → 加入 dispatcher；remove() 调用 cancel() 可从 dispatcher 真正移除；责任链顺序可控（navCb 在前，我们的回调在后）；缺点：需手动管理 onStart/onStop 的 remove/add 对称性

## 决策
在 MainActivity.onStart() 中使用 `onBackPressedDispatcher.addCallback(this@MainActivity, cb)` 注册回调，配合 `backPressedCallback?.remove()` 在 onStop 前清理，确保回调随 Activity 生命周期正确注册和移除。

## 影响
解决了僵尸回调累积导致的返回键失效问题；责任链变为 [..., navCb, ourCb]，反向遍历时我们的回调优先判断 isOnTabPage 决定是否 finish() 退出；需要保证 onStart/onStop 的 remove/add 成对出现。