// app/src/main/java/com/schedulecalendar/app/data/db/entity/ScheduleRecordEntity.kt
package com.schedulecalendar.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 每日排班记录表 */
@Entity(tableName = "schedule_records")
data class ScheduleRecordEntity(
    @PrimaryKey val date: String,           // yyyy-MM-dd
    val type: String = "SHIFT",             // ScheduleType name
    val shiftId: String? = null,
    val actualStartTime: String? = null,    // HH:mm
    val actualEndTime: String? = null,      // HH:mm
    val remark: String? = null,
    /** JSON：List<String>（ExtraItem ID 列表） */
    val extraItemIdsJson: String = "[]",
    /** JSON：AppliedStatusJson 单对象（statusId + startTime? + endTime?）；无附加状态时为 "null" */
    val appliedStatusesJson: String = "null",
    val salaryMode: String? = null,         // SalaryMode name 或 null
    val ignoreEarlyArrival: Boolean = false,
    val ignoreLateLeave: Boolean = false,
    val confirmEarlyOT: Boolean = false,
    val confirmLateOT: Boolean = false
)
