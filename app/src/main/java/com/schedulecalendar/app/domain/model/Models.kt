// app/src/main/java/com/schedulecalendar/app/domain/model/Models.kt
package com.schedulecalendar.app.domain.model

// ── 内置班次 & 状态类型常量 ─────────────────────────────────────────
const val BUILTIN_SHIFT_REST  = "__builtin_rest__"
const val BUILTIN_SHIFT_SWAP  = "__builtin_swap__"
const val BUILTIN_SHIFT_LEAVE = "__builtin_leave__"
const val BUILTIN_STATUS_LEAVE = "__builtin_status_leave__"
const val BUILTIN_STATUS_SWAP  = "__builtin_status_swap__"
const val BUILTIN_SCHEME_ID   = "__builtin_scheme_work__"
const val NO_SCHEME_ID        = "__no_scheme__"

/** 全局不计入工时时段（如午休、用餐），对所有班次生效 */
data class ShiftBreak(
    val id: String,
    val label: String,       // 名称，如"午休"
    val startTime: String,   // HH:mm
    val endTime: String,     // HH:mm
    val archivedAt: String? = null  // null=有效, 非null=已归档
)

/** 班次状态类型（请假、外出、培训等用户自定义） */
data class ShiftStatus(
    val id: String,
    val name: String,
    val color: String,
    val builtIn: Boolean     = false,
    /** 映射到报表类型：leave=请假工时, swap=调休工时 */
    val reportType: String?  = null,   // "leave" | "swap" | null
    /** 可选：开始时间（HH:mm），为空则不限制 */
    val startTime: String    = "",
    /** 可选：结束时间（HH:mm），为空则不限制 */
    val endTime: String      = "",
    val archivedAt: String?  = null  // null=有效, 非null=已归档
)

/** 排班记录中已应用的状态（时间段可选；不填则仅标记状态，不扣除工时） */
data class AppliedStatus(
    val statusId: String,
    val startTime: String?   = null,  // HH:mm，可选
    val endTime: String?     = null   // HH:mm，可选
)

/** 排班类型 */
enum class ScheduleType { SHIFT, LEAVE, SWAP, REST }

/** 薪资计算方式 */
enum class SalaryMode { NORMAL, WEEKEND, HOLIDAY }

/** 班次 */
data class Shift(
    val id: String,
    val name: String,
    val color: String            = "#3b82f6",
    val startTime: String        = "08:00",
    val endTime: String          = "17:00",
    /** 正常班时长（小时）；超出此时长的工时计为加班；null 则全部计为正常工时 */
    val normalWorkHours: Double? = null,
    val builtIn: Boolean         = false,
    /** 内置班次对应的类型 */
    val builtInType: String?     = null,  // "rest" | "swap" | "leave"
    /** 默认关联的补贴/扣款项目 ID（最多3个），排班时自动预选 */
    val linkedExtraIds: List<String> = emptyList(),
    val archivedAt: String?      = null  // null=有效, 非null=已归档
)

/** 内置班次列表 */
val BUILTIN_SHIFTS = listOf(
    Shift(id = BUILTIN_SHIFT_REST,  name = "\u4f11\u606f", color = "#6B7280", builtIn = true, builtInType = "rest"),
    Shift(id = BUILTIN_SHIFT_SWAP,  name = "\u8c03\u4f11", color = "#78716C", builtIn = true, builtInType = "swap"),
    Shift(id = BUILTIN_SHIFT_LEAVE, name = "\u8bf7\u5047", color = "#F43F5E", builtIn = true, builtInType = "leave"),
)

/** 内置状态类型列表 */
val BUILTIN_STATUSES = listOf(
    ShiftStatus(id = BUILTIN_STATUS_LEAVE, name = "\u8bf7\u5047", color = "#F43F5E", builtIn = true, reportType = "leave"),
    ShiftStatus(id = BUILTIN_STATUS_SWAP,  name = "\u8c03\u4f11", color = "#78716C", builtIn = true, reportType = "swap"),
)

/** 每日排班记录 */
data class ScheduleRecord(
    val date: String,                           // yyyy-MM-dd
    val type: ScheduleType              = ScheduleType.SHIFT,
    val shiftId: String?                = null,
    val actualStartTime: String?        = null, // HH:mm
    val actualEndTime: String?          = null, // HH:mm
    val remark: String?                 = null,
    val extraItemIds: List<String>      = emptyList(),
    val appliedStatus: AppliedStatus? = null,
    /** 手动指定计薪方式；null 则按日期自动判断 */
    val salaryMode: SalaryMode?         = null,
    /** 忽略早到产生的加班 */
    val ignoreEarlyArrival: Boolean     = false,
    /** 忽略晚退产生的加班 */
    val ignoreLateLeave: Boolean        = false,
    /** 确认早到加班计入薪资 */
    val confirmEarlyOT: Boolean         = false,
    /** 确认晚退加班计入薪资 */
    val confirmLateOT: Boolean          = false
)

/** 附加补贴/扣款项目 */
data class ExtraItem(
    val id: String,
    val name: String,
    val type: String,       // "allowance" | "deduction"
    val amount: Double,
    val archivedAt: String? = null  // null=有效, 非null=已归档
)

/** 薪资配置（统一制度） */
data class SalaryConfig(
    val baseSalary: Double          = 0.0,   // 基础底薪（元/月）
    val basePerformance: Double     = 0.0,   // 基础绩效（元/月）
    val normalRate: Double          = 0.0,   // 正常时薪（元/小时）
    val overtimeRate: Double        = 0.0,   // 加班时薪（元/小时）
    val weekendRate: Double         = 0.0,   // 周末时薪（元/小时）
    val holidayRate: Double         = 0.0,   // 节假日时薪（元/小时）
    val normalMonthlyHours: Double  = 0.0,   // 月标准工时（用于时薪反算）
    val socialInsurance: Double     = 0.0,   // 社保扣款（元/月）
    val incomeTax: Double           = 0.0    // 个人所得税（元/月）
)

/** 考勤规则配置 */
data class AttendConfig(
    /** 加班计量粒度（分钟），默认 30 */
    val overtimeGranMin: Int                = 30,
    /** 迟到容忍时长（分钟），默认 0 */
    val lateToleranceMin: Int               = 0,
    /** 早退容忍时长（分钟），默认 0 */
    val earlyLeaveToleranceMin: Int         = 0,
    /** 当月迟到次数提醒阈值，0 表示不提醒 */
    val lateAlertCount: Int                 = 0,
    /** 当月早退次数提醒阈值，0 表示不提醒 */
    val earlyLeaveAlertCount: Int           = 0,
    /** 正常班标准时长（小时），超出部分计为加班 */
    val normalWorkHoursPerDay: Double       = 0.0,
    /** 迟到扣款（元/分钟），默认 0 */
    val lateDeductionPerMin: Double         = 0.0,
    /** 早退扣款（元/分钟），默认 0 */
    val earlyLeaveDeductionPerMin: Double   = 0.0
)

/** 日历每个格子可展示的数据项类型 */
enum class DisplayItemType(val label: String, val desc: String, val defaultColor: String? = null) {
    TOTAL_HOURS("当天总工作时长", "正常班+加班合计工时"),
    WORK_HOURS("正班工时", "正常班工时（不含加班）"),
    OVERTIME_HOURS("加班时长", "加班工时"),
    DAILY_INCOME("当日总收入", "含补贴扣款"),
    NORMAL_INCOME("正班收入", "正常工时薪资"),
    OVERTIME_INCOME("加班收入", "加班工时薪资"),
    SHIFT("班次", "当前日期的班次名称", "#3b82f6"),
    STATUS("附加状态", "附加状态名称（如请假、调休）", "#F97316")
}

/** 该类型是否具有预设标签颜色（选择后自动锁定颜色，禁用颜色选择器） */
val DisplayItemType.isSpecialType: Boolean get() = defaultColor != null

/** 数据行配置（每行最多2个数据项，支持左右独立背景色） */
data class DataRowConfig(
    val items: List<DisplayItemType?>  = emptyList(),
    /** 左侧数据项背景色（十六进制字符串，如"#FF0000"） */
    val backgroundColorLeft: String?   = null,
    /** 右侧数据项背景色（十六进制字符串，如"#FF0000"） */
    val backgroundColorRight: String?  = null
)

/** 日历显示方案 */
data class DisplayScheme(
    val id: String                      = BUILTIN_SCHEME_ID,
    val name: String                    = "预设",
    val isNoScheme: Boolean             = false,
    val dataRows: List<DataRowConfig>   = listOf(
        DataRowConfig(items = listOf(DisplayItemType.WORK_HOURS, DisplayItemType.OVERTIME_HOURS)),
        DataRowConfig(),
        DataRowConfig(),
        DataRowConfig()
    ),
    val builtIn: Boolean                = false,
    val isActive: Boolean               = false
)

/**
 * 排班循环规则（保存在 DataStore）
 * startDate：循环起点日期 yyyy-MM-dd
 * shiftIds：依次循环的班次ID列表
 * startOffset：首月从列表第几个班次开始（0-based）
 * independentCycle：true=每月重新从 startOffset 开始；false=从 startDate 连续往后算
 */
data class ScheduleRule(
    val startDate: String           = "",
    val shiftIds: List<String>      = emptyList(),
    val startOffset: Int            = 0,
    val independentCycle: Boolean   = false
)

/** 内置显示方案 */
val BUILTIN_DISPLAY_SCHEME = DisplayScheme(
    id      = BUILTIN_SCHEME_ID,
    name    = "预设",
    dataRows = listOf(
        DataRowConfig(items = listOf(DisplayItemType.WORK_HOURS, DisplayItemType.OVERTIME_HOURS)),
        DataRowConfig(),
        DataRowConfig(),
        DataRowConfig()
    ),
    builtIn = true
)

/** 根据背景色明度计算文字颜色（白色或黑色） */
fun textColorForBackground(bgColor: String): String {
    return try {
        val hex = bgColor.removePrefix("#")
        val r = Integer.parseInt(hex.substring(0, 2), 16)
        val g = Integer.parseInt(hex.substring(2, 4), 16)
        val b = Integer.parseInt(hex.substring(4, 6), 16)
        // 计算相对亮度（YIQ公式）
        val brightness = (r * 299 + g * 587 + b * 114) / 1000
        if (brightness > 128) "#000000" else "#FFFFFF"
    } catch (_: Exception) {
        "#000000" // 默认黑色
    }
}

/** 工时统计 */
data class HoursSummary(
    val normalHours: Double         = 0.0,
    val overtimeHours: Double       = 0.0,
    val weekendHours: Double        = 0.0,
    val holidayHours: Double        = 0.0,
    val leaveDays: Int              = 0,
    val swapDays: Int               = 0,
    val restDays: Int               = 0,
    val totalHours: Double          = 0.0,
    val lateCount: Int              = 0,
    val earlyLeaveCount: Int        = 0,
    val leaveStatusHours: Double    = 0.0,
    val swapStatusHours: Double     = 0.0
)

/** 每日排班+工时+薪资明细（用于工时/薪资页每日明细展示） */
data class DayScheduleDetail(
    val date: String,
    val record: ScheduleRecord?         = null,
    val shift: Shift?                   = null,
    val normalHours: Double             = 0.0,
    val overtimeHours: Double           = 0.0,
    val weekendHours: Double            = 0.0,
    val holidayHours: Double            = 0.0,
    val salary: Double                  = 0.0,
    val normalSalary: Double            = 0.0,
    val overtimeSalary: Double          = 0.0,
    val extras: List<ExtraItem>         = emptyList()
)

/** 月薪资汇总（对齐小程序 SalarySummary） */
data class SalarySummary(
    val baseSalary: Double          = 0.0,
    val basePerformance: Double     = 0.0,
    val normalSalary: Double        = 0.0,
    val overtimeSalary: Double      = 0.0,
    val weekendSalary: Double       = 0.0,
    val holidaySalary: Double       = 0.0,
    val totalSubsidy: Double        = 0.0,
    val totalDeduction: Double      = 0.0,
    val socialInsurance: Double     = 0.0,  // 社保扣款
    val incomeTax: Double           = 0.0,  // 个人所得税
    val totalSalary: Double         = 0.0   // 实发工资（已扣社保/税）
)

/** 日期节假日信息 */
data class HolidayInfo(
    val name: String,
    val isHoliday: Boolean,
    val isMakeupDay: Boolean        = false
)
