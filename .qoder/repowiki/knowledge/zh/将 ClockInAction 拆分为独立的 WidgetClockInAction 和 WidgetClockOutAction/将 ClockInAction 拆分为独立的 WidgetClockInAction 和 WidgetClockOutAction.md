---
kind: design
name: 将 ClockInAction 拆分为独立的 WidgetClockInAction 和 WidgetClockOutAction
source: session
category: adr
---

# 将 ClockInAction 拆分为独立的 WidgetClockInAction 和 WidgetClockOutAction

_来源：e7fa8b2 → d09a0a9 提交周期内记录的编码计划——内容为规划时意图，实现可能滞后或有出入。_

**状态：** accepted

## 背景
原有的 ClockInAction 需要同时处理上班和下班两种打卡场景，导致逻辑复杂且难以维护。需要为小组件的上班打卡和下班打卡分别提供独立的 ActionCallback。

## 决策驱动
- 职责单一，便于维护
- 减少条件分支判断
- 每个 Action 只关注一种打卡类型

## 备选方案
- **拆分为两个独立 ActionCallback** — 优点：职责清晰，各自处理一种打卡类型；参数传递更明确；UI 渲染逻辑更直观；缺点：代码量略有增加
- **保留单一 Action 并通过参数区分类型** _（已否决）_ — 优点：代码集中；缺点：复杂的 if-else 分支；参数耦合度高；不利于后续扩展

## 决策
将原来的 ClockInAction 拆分为 WidgetClockInAction（负责向 scheduleRecord.actualStartTime 或 appliedStatus.startTime 写入）和 WidgetClockOutAction（负责向 scheduleRecord.actualEndTime 或 appliedStatus.endTime 写入），两者都从 Glance state 读取 ClockInWidgetData 获取目标日期和班次信息。

## 影响
UI 渲染逻辑简化为 when 表达式根据 showClockIn/showClockOut/hasClockIn/hasClockOut 字段决定显示哪个按钮。Rule 3（迟到早退自动填充附加状态）的逻辑在两个 Action 中分别处理。