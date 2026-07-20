# 小组件打卡功能改造计划

## 设计决策

**数据持久化方案**: 使用 Hilt `EntryPointAccessors` 让小组件 ActionCallback 直接访问 Repository（无需启动 Activity），利用 `ApplicationContext` 从 `SingletonComponent` 获取依赖。

## 涉及文件

- `widget/ScheduleGlanceWidget.kt` - 数据模型、ActionCallback、UI 渲染
- `ui/calendar/CalendarViewModel.kt` - 活跃班次判定 + syncWidget() 增强
- `domain/model/CalcUtils.kt` - 可选：新增辅助函数

## 详细步骤

### 步骤 1：增强 ClockInWidgetData 数据模型

**文件**: `widget/ScheduleGlanceWidget.kt`

在 `ClockInWidgetData` 中新增字段：
- `shiftId: String = ""` - 当前班次ID（用于查找）
- `isBuiltInShift: Boolean = false` - 是否内置休息/调休班次
- `appliedStatusId: String = ""` - 附加状态ID
- `isBuiltInStatus: Boolean = false` - 附加状态是否内置（调休/请假）
- `showClockIn: Boolean = false` - 是否显示上班打卡按钮
- `showClockOut: Boolean = false` - 是否显示下班打卡按钮
- `hasClockIn: Boolean = false` - 是否已上班打卡
- `hasClockOut: Boolean = false` - 是否已下班打卡
- `clockInDate: String = ""` - 打卡写入的目标日期
- `widgetClockInTime: String = ""` - 本地存储的上班打卡时间（同步到widget）
- `widgetClockOutTime: String = ""` - 本地存储的下班打卡时间（同步到widget）

### 步骤 2：创建 Hilt EntryPoint

**文件**: `widget/ScheduleGlanceWidget.kt`（新增在文件末尾）

```kotlin
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetClockEntryPoint {
    fun scheduleRepository(): ScheduleRepository
    fun shiftRepository(): ShiftRepository
    fun shiftStatusRepository(): ShiftStatusRepository
}
```

### 步骤 3：活跃班次判定函数

**文件**: `ui/calendar/CalendarViewModel.kt`

新增私有函数 `findActiveShift()` 实现判定逻辑：

```
fun findActiveShift(shifts, schedules) → ActiveShift?
  1. today = LocalDate.now()
  2. 检查今天排班:
     - 获取 today 的 ScheduleRecord
     - 如存在且 shift 非 null 且非内置休息/调休，获取 shift 时间
     - 班次时间归一化（end < start → end += 1440）
     - 当前 timeToMin 在班次 [start-300, end+300] 范围内 → 视为活跃
     - 满足 showClockIn/Out 条件分别判定
  3. 如果今天未匹配:
     - 检查昨天排班: 班次时间偏移 -1440 后重新判定
     - 当前 timeToMin 在偏移后的 [start, end+300] 范围内 → 视为活跃
  4. 返回 ActiveShift(date, shift, showClockIn, showClockOut, ...)
```

定义 `ActiveShift` 数据结构：
```kotlin
data class ActiveShiftResult(
    val date: LocalDate,       // 打卡写入的目标日期
    val shift: Shift,
    val isActive: Boolean,
    val showClockIn: Boolean,
    val showClockOut: Boolean
)
```

**判定逻辑细化**：

showClockIn: 当前分钟 ≥ shift.start - 300 && 当前分钟 < shift.end（归一化后）
showClockOut: 当前分钟 ≥ shift.start && 当前分钟 ≤ shift.end + 300（归一化后）

昨天跨午夜班次判定：将昨天班次的起止分钟都 -1440 偏移到"今天时间轴"

### 步骤 4：增强 CalendarViewModel.syncWidget()

**文件**: `ui/calendar/CalendarViewModel.kt`

在 `syncWidget()` 中集成活跃班次判定：

```kotlin
private suspend fun syncWidget(shifts: List<Shift>, schedules: Map<String, ScheduleRecord>) {
    val today = LocalDate.now()
    val todayStr = ...
    
    // 1. 查找活跃班次
    val active = findActiveShift(shifts, schedules)
    
    // 2. 获取目标日期的排班记录
    val targetDate = active?.date ?: today
    val targetDateStr = "%04d-%02d-%02d".format(targetDate.year, targetDate.monthValue, targetDate.dayOfMonth)
    val targetRecord = schedules[targetDateStr]
    val targetShift = targetRecord?.shiftId?.let { id -> shifts.find { it.id == id } }
    
    // 3. 应用规则 1-4 确定按钮显示
    val isBuiltInShift = targetShift?.builtIn == true && 
        (targetShift?.builtInType == "rest" || targetShift?.builtInType == "swap")
    val hasCustomStatus = targetRecord?.appliedStatus != null && !isBuiltInStatus(targetRecord.appliedStatus.statusId)
    val hasBuiltInStatus = targetRecord?.appliedStatus != null && isBuiltInStatus(targetRecord.appliedStatus.statusId)
    
    // showClockIn / showClockOut 根据规则 + 活跃班次判定
    var showClockIn = active?.showClockIn ?: false
    var showClockOut = active?.showClockOut ?: false
    
    // 规则1：内置班次且无附加状态 → 不显示打卡按钮
    if (isBuiltInShift && !hasCustomStatus && !hasBuiltInStatus) {
        showClockIn = false
        showClockOut = false
    }
    // 规则4：内置班次 + 自定义附加状态 → 打卡数据写入附加状态时间
    // 已上班打卡 → 只显示下班卡；已下班打卡 → 隐藏
    // (这部分在 ClockInWidgetData 中通过 hasClockIn/hasClockOut 控制)
    
    // 4. 读取本地打卡状态
    val clockPrefs = context.getSharedPreferences(CLOCK_IN_PREFS, Context.MODE_PRIVATE)
    val savedDate = clockPrefs.getString(KEY_CLOCK_IN_DATE, "") ?: ""
    val actualStart = if (savedDate == targetDateStr) clockPrefs.getString(KEY_CLOCK_IN_TIME, "") ?: "" else ""
    val actualEnd = if (savedDate == targetDateStr) clockPrefs.getString(KEY_CLOCK_OUT_TIME, "") ?: "" else ""
    
    // 5. 构建 widgetData
    val widgetData = ClockInWidgetData(
        // ... 原有字段 ...
        shiftId = targetShift?.id ?: "",
        isBuiltInShift = isBuiltInShift,
        appliedStatusId = targetRecord?.appliedStatus?.statusId ?: "",
        isBuiltInStatus = hasBuiltInStatus,
        showClockIn = showClockIn,
        showClockOut = showClockOut,
        hasClockIn = actualStart.isNotEmpty(),
        hasClockOut = actualEnd.isNotEmpty(),
        clockInDate = targetDateStr,
        widgetClockInTime = actualStart,
        widgetClockOutTime = actualEnd
    )
    ScheduleGlanceWidget.updateWidgetData(context, widgetData)
}
```

### 步骤 5：重构 ClockInAction → 两个独立 Action

**文件**: `widget/ScheduleGlanceWidget.kt`

拆分原来的 `ClockInAction` 为两个独立的 ActionCallback：

**`WidgetClockInAction`** - 上班打卡
1. 获取 EntryPoint → scheduleRepository / shiftRepository
2. 从 Glance state 读取 ClockInWidgetData 获取 clockInDate / shiftId
3. 判断当前规则：
   - Rule 1/2/3（普通班次）：向 scheduleRecord.actualStartTime 写入当前时间
   - Rule 4（内置班次+自定义状态）：向 scheduleRecord.appliedStatus.startTime 写入当前时间
4. 更新 SharedPreferences（用于 widget 即时刷新）
5. 刷新 widget

**`WidgetClockOutAction`** - 下班打卡
1. 获取 EntryPoint → scheduleRepository
2. 从 Glance state 读取 ClockInWidgetData
3. 判断当前规则：
   - Rule 1/2/3（普通班次）：向 scheduleRecord.actualEndTime 写入当前时间
   - Rule 4（内置班次+自定义状态）：向 scheduleRecord.appliedStatus.endTime 写入当前时间
4. 更新 SharedPreferences
5. 刷新 widget

**关键：Rule 3 特殊处理**
- 先写入 actualStartTime/actualEndTime（打卡时间）
- 如果存在迟到/早退：计算迟到/早退时间段，自动填入 appliedStatus 的 startTime/endTime
- 具体规则：如果实际开始 > 班次开始（迟到），实际开始 - 班次开始 时段设为附加状态
- 如果实际结束 < 班次结束（早退），班次结束 - 实际结束 时段设为附加状态

### 步骤 6：更新 Widget UI 渲染

**文件**: `widget/ScheduleGlanceWidget.kt`

修改 `ClockInWidgetContent()` 中的按钮渲染逻辑：

```kotlin
// 按钮渲染逻辑（替代原有的 hasClockIn 三段式）
when {
    // 上班打卡可见
    data.showClockIn && !data.hasClockIn -> {
        显示 "上班卡" 按钮 → clickable(actionRunCallback<WidgetClockInAction>())
    }
    // 下班打卡可见（已上班、未下班或规则4要求显示）
    data.showClockOut && data.hasClockIn && !data.hasClockOut -> {
        显示 "下班卡" 按钮 → clickable(actionRunCallback<WidgetClockOutAction>())
    }
    // 已全部打卡 → 显示打卡完成状态（灰色不可再点或隐藏）
    data.hasClockIn && data.hasClockOut -> {
        显示 打卡完成 灰色状态
    }
    // 无打卡按钮（规则1：内置班次无附加状态）
    else -> {
        不显示按钮
    }
}
```

### 步骤 7：新增 isBuiltInStatus 辅助函数

**文件**: `widget/ScheduleGlanceWidget.kt` 或 `domain/model/Models.kt`

```kotlin
fun isBuiltInStatus(statusId: String): Boolean {
    return statusId == BUILTIN_STATUS_LEAVE || statusId == BUILTIN_STATUS_SWAP
}
```

### 步骤 8：版本号更新 + 构建安装

- 日期: 2026-07-20
- 版本: v2026072002 → v2026072003
- `assembleRelease` → `installRelease`

## 注意事项

1. **Clance参数传递**: WidgetClockInAction/WidgetClockOutAction 不需要 ActionParameters，通过 Glance DataStore 读取 ClockInWidgetData 获取目标信息
2. **数据一致性**: ActionCallback 中先写 DB 再更新 SharedPreferences，确保持久化优先
3. **异常处理**: 使用 runCatching 包装 DB 操作，失败时不影响 SharedPreferences 写入
