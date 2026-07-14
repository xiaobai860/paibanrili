// app/src/main/java/com/schedulecalendar/app/data/db/entity/ShiftStatusEntity.kt
package com.schedulecalendar.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 班次状态类型表（请假、外出、培训等） */
@Entity(tableName = "shift_statuses")
data class ShiftStatusEntity(
    @PrimaryKey val id: String,
    val name: String,
    val color: String,
    val builtIn: Boolean = false,
    val reportType: String? = null,  // "leave" | "swap" | null
    val startTime: String = "",
    val endTime: String   = "",
    val archivedAt: String? = null  // null=有效, 非null=已归档
)
