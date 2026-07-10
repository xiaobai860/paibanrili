// app/src/main/java/com/schedulecalendar/app/data/db/entity/ShiftBreakEntity.kt
package com.schedulecalendar.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 全局不计入工时时段表（午休、用餐等） */
@Entity(tableName = "shift_breaks")
data class ShiftBreakEntity(
    @PrimaryKey val id: String,
    val label: String,
    val startTime: String,  // HH:mm
    val endTime: String,    // HH:mm
    val archivedAt: String? = null  // null=有效, 非null=已归档
)
