// app/src/main/java/com/schedulecalendar/app/data/db/Migrations.kt
package com.schedulecalendar.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room 数据库迁移（1→5）。
 *
 * 历史演进全部为「新增可空列」，因此迁移只需 `ALTER TABLE ... ADD COLUMN`，
 * 不涉及数据转换，无数据丢失风险：
 * - v2→v3：shift_statuses 增加 startTime / endTime（附加状态时间段）
 * - v3→v4：extra_items 增加 archivedAt（归档）
 * - v4→v5：shifts / shift_breaks / shift_statuses 增加 archivedAt（归档）
 *
 * ⚠️ 维护约定：每次提升 [AppDatabase] 的 version，必须在 [DatabaseModule] 的
 * `addMigrations(...)` 中登记对应跳变的迁移，否则老用户升级会直接抛
 * `IllegalStateException: A migration from X to Y was required but not found`。
 */
object Migrations {

    /** 2 → 3：附加状态支持时间段 */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `shift_statuses` ADD COLUMN `startTime` TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE `shift_statuses` ADD COLUMN `endTime` TEXT DEFAULT NULL")
        }
    }

    /** 3 → 4：补贴扣款项目支持归档 */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `extra_items` ADD COLUMN `archivedAt` INTEGER DEFAULT NULL")
        }
    }

    /** 4 → 5：班次 / 休息时段 / 附加状态支持归档 */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `shifts` ADD COLUMN `archivedAt` INTEGER DEFAULT NULL")
            db.execSQL("ALTER TABLE `shift_breaks` ADD COLUMN `archivedAt` INTEGER DEFAULT NULL")
            db.execSQL("ALTER TABLE `shift_statuses` ADD COLUMN `archivedAt` INTEGER DEFAULT NULL")
        }
    }

    /** 5 → 6：新增计算口径档案表（薪资/考勤/休息时段的带生效日快照） */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `calc_profiles` (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `salaryJson` TEXT NOT NULL,
                    `attendJson` TEXT NOT NULL,
                    `breaksJson` TEXT NOT NULL,
                    `effectiveFrom` TEXT NOT NULL,
                    `effectiveTo` TEXT,
                    `note` TEXT,
                    `createdAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `idx_calc_profiles_from` ON `calc_profiles` (`effectiveFrom`)")
        }
    }

    /** 全部迁移，按版本顺序注册 */
    val ALL = arrayOf(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
}
