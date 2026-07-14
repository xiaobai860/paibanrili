// app/src/main/java/com/schedulecalendar/app/data/db/entity/ExtraItemEntity.kt
package com.schedulecalendar.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 附加补贴/扣款项目表（v2：移除旧 trigger/enabled/color 字段） */
@Entity(tableName = "extra_items")
data class ExtraItemEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,       // "allowance" | "deduction"
    val amount: Double,
    val archivedAt: String? = null  // 归档时间戳，null=有效，非null=已归档
)
