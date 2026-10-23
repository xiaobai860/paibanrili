// app/src/main/java/com/schedulecalendar/app/data/db/entity/CalcProfileEntity.kt
package com.schedulecalendar.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 计算口径档案的表结构。
 *
 * 三类参数（薪资 / 考勤 / 休息时段）以 JSON 快照形式存放：
 * 快照而非外键，是为了**冻结历史口径**——外部配置后续如何变化都不影响这份快照。
 */
@Entity(tableName = "calc_profiles")
data class CalcProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val salaryJson: String,
    val attendJson: String,
    val breaksJson: String,
    /** 生效日 yyyy-MM-dd（含） */
    val effectiveFrom: String,
    /** 失效日 yyyy-MM-dd（不含），null = 至今 */
    val effectiveTo: String?,
    val note: String?,
    val createdAt: Long
)

