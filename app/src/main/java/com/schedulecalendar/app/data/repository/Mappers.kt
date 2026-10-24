// app/src/main/java/com/schedulecalendar/app/data/repository/Mappers.kt
package com.schedulecalendar.app.data.repository

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.schedulecalendar.app.data.db.entity.ExtraItemEntity
import com.schedulecalendar.app.data.db.entity.ScheduleRecordEntity
import com.schedulecalendar.app.data.db.entity.ShiftBreakEntity
import com.schedulecalendar.app.data.db.entity.ShiftEntity
import com.schedulecalendar.app.data.db.entity.ShiftStatusEntity
import com.schedulecalendar.app.domain.model.*

/** Entity <-> Domain 映射扩展函数（对齐 v2 数据模型） */

private val gson = Gson()

// ── Shift ─────────────────────────────────────────────────────────────

fun ShiftEntity.toDomain(): Shift {
    val ids: List<String> = gson.fromJson(
        linkedExtraIdsJson, object : TypeToken<List<String>>() {}.type
    ) ?: emptyList()
    return Shift(
        id             = id,
        name           = name,
        color          = color,
        startTime      = startTime,
        endTime        = endTime,
        normalWorkHours = normalWorkHours,
        builtIn        = builtIn,
        builtInType    = builtInType,
        linkedExtraIds = ids,
        archivedAt     = archivedAt
    )
}

fun Shift.toEntity(): ShiftEntity = ShiftEntity(
    id               = id,
    name             = name,
    color            = color,
    startTime        = startTime,
    endTime          = endTime,
    normalWorkHours  = normalWorkHours,
    builtIn          = builtIn,
    builtInType      = builtInType,
    linkedExtraIdsJson = gson.toJson(linkedExtraIds),
    archivedAt       = archivedAt
)

// ── ShiftBreak ────────────────────────────────────────────────────────

fun ShiftBreakEntity.toDomain() = ShiftBreak(id, label, startTime, endTime, archivedAt)
fun ShiftBreak.toEntity()       = ShiftBreakEntity(id, label, startTime, endTime, archivedAt)

// ── ShiftStatus ───────────────────────────────────────────────────────

fun ShiftStatusEntity.toDomain() = ShiftStatus(id, name, color, builtIn, reportType, startTime, endTime, archivedAt)
fun ShiftStatus.toEntity()       = ShiftStatusEntity(id, name, color, builtIn, reportType, startTime, endTime, archivedAt)

// ── AppliedStatus (JSON helper) ───────────────────────────────────────

/**
 * 附加状态在 Room `appliedStatusesJson` 列里的 JSON 形状。
 *
 * ⚠️ **字段名是数据库数据格式的一部分**（已写进用户设备的历史数据），
 *    绝不能被 R8 混淆 —— 否则 release 包读历史排班时附加状态会全部读成默认值（静默丢失）。
 *    双保险：proguard 精确 keep（见 proguard-rules.pro）+ 这里的 @SerializedName 钉死名字
 *    （注解值与历史数据里的 key 完全一致 → 向后兼容）。
 *
 * ⚠️ 可见性必须是 **internal 而不是 private**：proguard 规则按类全名引用它，
 *    private 类在 IDE 的 proguard 检查里会报「Unresolved class name」（R8 本身不介意）。
 */
internal data class AppliedStatusJson(
    @SerializedName("statusId") val statusId: String,
    @SerializedName("startTime") val startTime: String?,
    @SerializedName("endTime") val endTime: String?,
    /** 旧数据无此字段 → Gson 反序列化后为 false，天然向后兼容 */
    @SerializedName("isOvertime") val isOvertime: Boolean = false
)

/**
 * 解析单个附加状态：兼容旧版数组格式（取第一个）和新版单对象格式
 *
 * ⚠️ 数组分支是**兼容版专属的历史包袱**：
 * 兼容版的迁移（MIGRATION_6_7）已把库里的旧数组格式改写成单对象，
 * 备份侧也由 [com.schedulecalendar.app.data.compat.BackupCompat] 在读入时统一转换，
 * 因此新数据一律是单对象。后续版本（fence 之后）可删除数组分支。
 */
fun parseAppliedStatus(json: String): AppliedStatus? {
    val trimmed = json.trim()
    if (trimmed.isEmpty() || trimmed == "null" || trimmed == "\"\"") return null
    // 旧格式：数组 [{...}]
    if (trimmed.startsWith("[")) {
        val list: List<AppliedStatusJson> = gson.fromJson(
            trimmed, object : TypeToken<List<AppliedStatusJson>>() {}.type
        ) ?: emptyList()
        return list.firstOrNull()?.let { AppliedStatus(it.statusId, it.startTime, it.endTime, it.isOvertime) }
    }
    // 新格式：单对象 {...}
    return try {
        val obj = gson.fromJson(trimmed, AppliedStatusJson::class.java)
        AppliedStatus(obj.statusId, obj.startTime, obj.endTime, obj.isOvertime)
    } catch (_: Exception) { null }
}

fun AppliedStatus?.toJson(): String =
    if (this != null) gson.toJson(AppliedStatusJson(statusId, startTime, endTime, isOvertime))
    else "null"

// ── ScheduleRecord ────────────────────────────────────────────────────

fun ScheduleRecordEntity.toDomain(): ScheduleRecord {
    val extraIds: List<String> = gson.fromJson(
        extraItemIdsJson, object : TypeToken<List<String>>() {}.type
    ) ?: emptyList()
    val status = parseAppliedStatus(appliedStatusesJson)
    return ScheduleRecord(
        date               = date,
        type               = runCatching { ScheduleType.valueOf(type) }.getOrDefault(ScheduleType.SHIFT),
        shiftId            = shiftId,
        actualStartTime    = actualStartTime,
        actualEndTime      = actualEndTime,
        remark             = remark,
        extraItemIds       = extraIds,
        appliedStatus     = status,
        salaryMode         = salaryMode?.let { runCatching { SalaryMode.valueOf(it) }.getOrNull() },
        ignoreEarlyArrival = ignoreEarlyArrival,
        ignoreLateLeave    = ignoreLateLeave,
        confirmEarlyOT     = confirmEarlyOT,
        confirmLateOT      = confirmLateOT
    )
}

fun ScheduleRecord.toEntity(): ScheduleRecordEntity = ScheduleRecordEntity(
    date               = date,
    type               = type.name,
    shiftId            = shiftId,
    actualStartTime    = actualStartTime,
    actualEndTime      = actualEndTime,
    remark             = remark,
    extraItemIdsJson   = gson.toJson(extraItemIds),
    appliedStatusesJson = appliedStatus.toJson(),
    salaryMode         = salaryMode?.name,
    ignoreEarlyArrival = ignoreEarlyArrival,
    ignoreLateLeave    = ignoreLateLeave,
    confirmEarlyOT     = confirmEarlyOT,
    confirmLateOT      = confirmLateOT
)

// ── ExtraItem ─────────────────────────────────────────────────────────

fun ExtraItemEntity.toDomain() = ExtraItem(id, name, type, amount, archivedAt)
fun ExtraItem.toEntity()       = ExtraItemEntity(id, name, type, amount, archivedAt)
