// app/src/main/java/com/schedulecalendar/app/data/db/entity/ShiftEntity.kt
package com.schedulecalendar.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 班次表（v2：对齐新数据模型，移除旧的 workHours/isRest/excludePeriodsJson） */
@Entity(tableName = "shifts")
data class ShiftEntity(
    @PrimaryKey val id: String,
    val name: String,
    val color: String,
    val startTime: String,
    val endTime: String,
    /** 正常班时长（小时），null 表示不限制加班分界 */
    val normalWorkHours: Double?,
    val builtIn: Boolean = false,
    /** 内置类型：rest / swap / leave */
    val builtInType: String? = null,
    /** JSON 数组：关联的补贴/扣款 ID */
    val linkedExtraIdsJson: String = "[]",
    val archivedAt: String? = null  // null=有效, 非null=已归档
)
