// app/src/main/java/com/schedulecalendar/app/data/compat/BackupPayloads.kt
package com.schedulecalendar.app.data.compat

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.InstanceCreator
import com.google.gson.annotations.SerializedName
import com.schedulecalendar.app.domain.model.AppliedStatus
import com.schedulecalendar.app.domain.model.AttendConfig
import com.schedulecalendar.app.domain.model.CalcProfile
import com.schedulecalendar.app.domain.model.DataRowConfig
import com.schedulecalendar.app.domain.model.DisplayScheme
import com.schedulecalendar.app.domain.model.ExtraItem
import com.schedulecalendar.app.domain.model.SalaryConfig
import com.schedulecalendar.app.domain.model.ScheduleRecord
import com.schedulecalendar.app.domain.model.ScheduleRule
import com.schedulecalendar.app.domain.model.Shift
import com.schedulecalendar.app.domain.model.ShiftBreak
import com.schedulecalendar.app.domain.model.ShiftStatus

/** 备份文件的种类（决定用哪套格式版本号与恢复逻辑） */
enum class BackupKind {
    /** 「应用数据_*.json」：排班 + 班次 + 状态 + 补贴 + 偏好 + 口径档案 */
    APP_DATA,

    /** 「班次配置_*.json」：仅班次相关配置（可分享/导入到其它设备） */
    SHIFT_CONFIG
}

/**
 * 完整 AppData 备份包（当前格式 v5）
 *
 * ⚠️ 列表字段用 `List<...>?` 而非默认 `emptyList()`，以便区分
 * 「备份中确实没有该字段（旧版备份 / 手工编辑）」与「备份中该列表为空」。
 * 恢复时：字段缺失 → 保持现状不动；字段存在 → 全量替换。
 *
 * 本类是**当前格式**的定义；历史格式（v1~v4）的差异全部由 [BackupCompat] 在
 * 「读进来」时抹平，因此这里不需要（也不应该）再出现任何历史分支。
 */
internal data class AppDataBackup(
    @SerializedName("version") val version: Int = DataFormat.APP_DATA_BACKUP,
    @SerializedName("exportTime") val exportTime: String = "",
    @SerializedName("scheduleRecords") val scheduleRecords: List<ScheduleRecord>? = null,
    @SerializedName("shifts") val shifts: List<Shift>? = null,
    @SerializedName("globalBreaks") val globalBreaks: List<ShiftBreak>? = null,
    @SerializedName("shiftStatuses") val shiftStatuses: List<ShiftStatus>? = null,
    @SerializedName("extraItems") val extraItems: List<ExtraItem>? = null,
    @SerializedName("salaryConfig") val salaryConfig: SalaryConfig? = null,
    @SerializedName("attendConfig") val attendConfig: AttendConfig? = null,
    @SerializedName("scheduleRule") val scheduleRule: ScheduleRule? = null,
    @SerializedName("displaySchemes") val displaySchemes: List<DisplayScheme>? = null,
    @SerializedName("disabledAccountIds") val disabledAccountIds: List<Long>? = null,
    @SerializedName("accountCategories") val accountCategories: Map<String, String>? = null,
    @SerializedName("accountsInitialized") val accountsInitialized: Boolean? = null,
    @SerializedName("reminderEnabled") val reminderEnabled: Boolean? = null,
    @SerializedName("reminderMethod") val reminderMethod: String? = null,
    @SerializedName("reminderClockIn") val reminderClockIn: Boolean? = null,
    @SerializedName("reminderClockOut") val reminderClockOut: Boolean? = null,
    @SerializedName("reminderClockInMinutes") val reminderClockInMinutes: Int? = null,
    @SerializedName("reminderClockOutMinutes") val reminderClockOutMinutes: Int? = null,
    @SerializedName("reminderNotifyBar") val reminderNotifyBar: Boolean? = null,
    /** 计算口径档案（薪资/考勤/休息时段的带生效日快照） */
    @SerializedName("calcProfiles") val calcProfiles: List<CalcProfile>? = null,
    @SerializedName("shiftOrder") val shiftOrder: List<String>? = null,
    @SerializedName("statusOrder") val statusOrder: List<String>? = null,
    @SerializedName("extraOrder") val extraOrder: List<String>? = null,
    @SerializedName("breakOrder") val breakOrder: List<String>? = null,
    @SerializedName("shiftColorIndex") val shiftColorIndex: Int? = null,
    @SerializedName("statusColorIndex") val statusColorIndex: Int? = null
)

/**
 * 班次配置备份包（当前格式 v7）
 *
 * ⚠️ 可空性约定同 [AppDataBackup]：列表字段为 `List<...>?`，
 * 「字段缺失（旧版备份）→ 保持现状」与「字段存在但为空 → 全量替换」必须能区分开。
 */
internal data class ShiftConfigBackup(
    @SerializedName("version") val version: Int = DataFormat.SHIFT_CONFIG_BACKUP,
    @SerializedName("exportTime") val exportTime: String = "",
    @SerializedName("shifts") val shifts: List<Shift>? = null,
    @SerializedName("globalBreaks") val globalBreaks: List<ShiftBreak>? = null,
    @SerializedName("shiftStatuses") val shiftStatuses: List<ShiftStatus>? = null,
    @SerializedName("extraItems") val extraItems: List<ExtraItem>? = null,
    @SerializedName("shiftOrder") val shiftOrder: List<String>? = null,
    @SerializedName("statusOrder") val statusOrder: List<String>? = null,
    @SerializedName("extraOrder") val extraOrder: List<String>? = null,
    @SerializedName("breakOrder") val breakOrder: List<String>? = null,
    @SerializedName("shiftColorIndex") val shiftColorIndex: Int? = null,
    @SerializedName("statusColorIndex") val statusColorIndex: Int? = null
)

/**
 * 备份反序列化专用 Gson：与 `AppPreferences` 同一策略——为所有领域模型注册 [InstanceCreator]，
 * 保证**旧备份缺字段时落 Kotlin 默认值**（"" / emptyList / false / 0.0）而非 JVM null。
 *
 * ⚠️ 为什么必须注册：Gson 对「无无参构造器」的 Kotlin data class 用 Unsafe 分配实例，
 * JSON 里缺失的字段保持 JVM 默认值（对象字段 = null）。v1/v2 时代的备份缺
 * `ShiftStatus.startTime/endTime`（非空 + NOT NULL 列）、`Shift.linkedExtraIds`（非空列表）
 * 等字段，不注册会导致这些备份恢复时撞 Room 约束直接失败，或把 null 泄漏进计算链路。
 *
 * 注册后行为：JSON 中存在的字段照常反射赋值；缺失的字段保留此处给定的默认值
 * （与 Kotlin data class 的默认参数一致）。
 *
 * 注：兼容版把「旧格式 → 当前格式」的转换集中在 [BackupCompat]（JSON 层面），
 * 本 Gson 只是**最后一道兜底**，保证即使转换漏了字段也不会 NPE。
 */
internal object BackupGson {
    val instance: Gson = GsonBuilder()
        .registerTypeAdapter(DisplayScheme::class.java, InstanceCreator { DisplayScheme() })
        .registerTypeAdapter(DataRowConfig::class.java, InstanceCreator { DataRowConfig() })
        .registerTypeAdapter(SalaryConfig::class.java, InstanceCreator { SalaryConfig() })
        .registerTypeAdapter(AttendConfig::class.java, InstanceCreator { AttendConfig() })
        .registerTypeAdapter(ScheduleRule::class.java, InstanceCreator { ScheduleRule() })
        .registerTypeAdapter(Shift::class.java, InstanceCreator { Shift(id = "", name = "") })
        .registerTypeAdapter(ShiftStatus::class.java, InstanceCreator { ShiftStatus(id = "", name = "", color = "") })
        .registerTypeAdapter(ShiftBreak::class.java, InstanceCreator { ShiftBreak(id = "", label = "", startTime = "", endTime = "") })
        .registerTypeAdapter(ExtraItem::class.java, InstanceCreator { ExtraItem(id = "", name = "", type = "", amount = 0.0) })
        .registerTypeAdapter(ScheduleRecord::class.java, InstanceCreator { ScheduleRecord(date = "") })
        .registerTypeAdapter(AppliedStatus::class.java, InstanceCreator { AppliedStatus(statusId = "") })
        .registerTypeAdapter(CalcProfile::class.java, InstanceCreator {
            CalcProfile(
                id = "", name = "",
                salary = SalaryConfig(), attend = AttendConfig(),
                breaks = emptyList(), effectiveFrom = ""
            )
        })
        .create()
}
