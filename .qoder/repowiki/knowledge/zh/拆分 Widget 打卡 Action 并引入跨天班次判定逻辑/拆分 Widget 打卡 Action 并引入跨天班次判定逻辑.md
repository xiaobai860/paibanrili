---
kind: design
name: 拆分 Widget 打卡 Action 并引入跨天班次判定逻辑
source: session
category: adr
---

# 拆分 Widget 打卡 Action 并引入跨天班次判定逻辑

_来源：d0a3cc0 → cdd68fd 提交周期内记录的编码计划——内容为规划时意图，实现可能滞后或有出入。_

**状态：** accepted

## 背景
原有的单一 `ClockInAction` 无法区分上班/下班场景，且缺乏对“跨午夜班次”（如昨晚开始、今早结束）的支持，导致用户在非当天日期的活跃班次中无法正确打卡。同时，内置休息/调休班次的打卡规则复杂，需在 UI 层精确控制按钮显隐。

## 决策驱动
- 业务准确性（支持跨天班次）
- UI 状态清晰性（分离上下班状态）
- 规则可维护性（内置 vs 自定义状态分离）

## 备选方案
- **拆分为 WidgetClockInAction 和 WidgetClockOutAction** — 优点：职责单一，便于分别处理上班/下班的业务规则（如 Rule 3 迟到早退自动计算）；UI 渲染逻辑更简洁；支持独立的错误处理。；缺点：增加了 Action 类的数量；需在 Glance State 中维护更细致的状态字段（showClockIn/Out, hasClockIn/Out）。
- **保留单一 Action 并通过参数区分** _（已否决）_ — 优点：类数量少。；缺点：内部逻辑分支复杂，难以维护；难以针对上下班不同规则进行独立扩展。

## 决策
1. 将原 Action 拆分为 `WidgetClockInAction` 和 `WidgetClockOutAction`。2. 在 `CalendarViewModel` 中新增 `findActiveShift()`，通过时间归一化（end < start 则 +1440）和昨天班次偏移（-1440）算法，精准判定当前是否处于活跃班次窗口。3. 根据判定结果及班次类型（内置/自定义状态）动态计算 `showClockIn/Out` 并更新 Widget UI。

## 影响
Widget 现在能正确处理跨天班次（如夜班）的打卡逻辑。UI 状态机变得更加复杂（需处理 show/has 的组合），但提升了用户体验的准确性。对于内置休息/调休班次，除非有自定义附加状态，否则默认隐藏打卡按钮，避免了无效操作。