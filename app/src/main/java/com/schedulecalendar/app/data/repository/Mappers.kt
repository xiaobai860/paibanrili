// app/src/main/java/com/schedulecalendar/app/data/repository/Mappers.kt
package com.schedulecalendar.app.data.repository

import com.google.gson.Gson
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

private data class AppliedStatusJson(
    val statusId: String,
    val startTime: String?,
    val endTime: String?
)

/** 解析单个附加状态：兼容旧版数组格式（取第一个）和新版单对象格式 */
fun parseAppliedStatus(json: String): AppliedStatus? {
    val trimmed = json.trim()
    if (trimmed.isEmpty() || trimmed == "null" || trimmed == "\"\"") return null
    // 旧格式：数组 [{...}]
    if (trimmed.startsWith("[")) {
        val list: List<AppliedStatusJson> = gson.fromJson(
            trimmed, object : TypeToken<List<AppliedStatusJson>>() {}.type
        ) ?: emptyList()
        return list.firstOrNull()?.let { AppliedStatus(it.statusId, it.startTime, it.endTime) }
    }
    // 新格式：单对象 {...}
    return try {
        val obj = gson.fromJson(trimmed, AppliedStatusJson::class.java)
        AppliedStatus(obj.statusId, obj.startTime, obj.endTime)
    } catch (_: Exception) { null }
}

fun AppliedStatus?.toJson(): String =
    if (this != null) gson.toJson(AppliedStatusJson(statusId, startTime, endTime))
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
