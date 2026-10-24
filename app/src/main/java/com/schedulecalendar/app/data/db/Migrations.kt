// app/src/main/java/com/schedulecalendar/app/data/db/Migrations.kt
package com.schedulecalendar.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room 数据库迁移（1→7）。
 *
 * ## 本文件在兼容版（中转版）中的定位
 * 兼容版接受**任意历史版本**的数据库：v1（最早一版）~ v6 都能一路升到 v7，
 * 中间不需要用户手动导出/导入。
 *
 * ## 迁移分两类
 * - **结构迁移**（v1→v2、v2→v3、v3→v4、v4→v5、v5→v6）：表/列增减；
 * - **数据格式迁移**（v6→v7）：结构不变，但把旧格式的**数据内容**改写成当前格式
 *   （附加状态 JSON 从「数组」改写为「单对象」等），让后续版本不必再背兼容分支。
 *
 * ⚠️ 维护约定：每次提升 [AppDatabase] 的 version，必须在 [DatabaseModule] 的
 * `addMigrations(...)` 中登记对应跳变的迁移，否则老用户升级会直接抛
 * `IllegalStateException: A migration from X to Y was required but not found`。
 */
object Migrations {

    /** 2 → 3：附加状态支持时间段 */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `shift_statuses_new` (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `color` TEXT NOT NULL,
                    `builtIn` INTEGER NOT NULL,
                    `reportType` TEXT,
                    `startTime` TEXT NOT NULL,
                    `endTime` TEXT NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL(
                "INSERT OR REPLACE INTO `shift_statuses_new` " +
                    "(id, name, color, builtIn, reportType, startTime, endTime) " +
                    "SELECT id, name, color, builtIn, reportType, '09:00', '18:00' FROM `shift_statuses`"
            )
            db.execSQL("DROP TABLE `shift_statuses`")
            db.execSQL("ALTER TABLE `shift_statuses_new` RENAME TO `shift_statuses`")
        }
    }

    /** 3 → 4：补贴扣款项目支持归档 */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `extra_items` ADD COLUMN `archivedAt` TEXT DEFAULT NULL")
        }
    }

    /** 4 → 5：班次 / 休息时段 / 附加状态支持归档 */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `shifts` ADD COLUMN `archivedAt` TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE `shift_breaks` ADD COLUMN `archivedAt` TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE `shift_statuses` ADD COLUMN `archivedAt` TEXT DEFAULT NULL")
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

    /**
     * 1 → 2：**动态重建**最早一版（v1）数据库。
     *
     * ## 为什么必须动态
     * v1 是本项目最早发布的版本，当时还没开 `exportSchema`，**没有 1.json 可参考**，
     * 它的列集合只能从代码注释推断（班次表当时是 `workHours/isRest/excludePeriodsJson`）。
     * 与其猜一个可能错的固定 DDL，不如**运行时探测旧库真实列**再重建：
     * - 目标列在旧表里存在 → 直接拷贝；
     * - 不存在 → 用该列的默认值兜底（保证 NOT NULL 约束不炸）；
     * - 存在语义等价的旧列（如 `workHours` → `normalWorkHours`）→ 优先用旧列的值。
     *
     * 这样无论 v1 真实结构是哪一种，迁移都能**安全落地**（宁可少几个字段，也不能炸库）。
     *
     * ## 产出
     * 迁移后严格等于 v2 的表结构，后续 v2→v3→…→v7 才能继续走标准链路。
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            rebuildTable(
                db, "shifts",
                listOf(
                    Col("id", "TEXT NOT NULL", "''"),
                    Col("name", "TEXT NOT NULL", "''"),
                    Col("color", "TEXT NOT NULL", "'#3b82f6'"),
                    Col("startTime", "TEXT NOT NULL", "'08:00'"),
                    Col("endTime", "TEXT NOT NULL", "'17:00'"),
                    Col("normalWorkHours", "REAL", "NULL", aliases = listOf("workHours")),
                    Col("builtIn", "INTEGER NOT NULL", "0"),
                    Col("builtInType", "TEXT", "NULL"),
                    Col("linkedExtraIdsJson", "TEXT NOT NULL", "'[]'")
                )
            )
            rebuildTable(
                db, "schedule_records",
                listOf(
                    Col("date", "TEXT NOT NULL", "''"),
                    Col("type", "TEXT NOT NULL", "'SHIFT'"),
                    Col("shiftId", "TEXT", "NULL"),
                    Col("actualStartTime", "TEXT", "NULL"),
                    Col("actualEndTime", "TEXT", "NULL"),
                    Col("remark", "TEXT", "NULL"),
                    Col("extraItemIdsJson", "TEXT NOT NULL", "'[]'"),
                    Col("appliedStatusesJson", "TEXT NOT NULL", "'null'"),
                    Col("salaryMode", "TEXT", "NULL"),
                    Col("ignoreEarlyArrival", "INTEGER NOT NULL", "0"),
                    Col("ignoreLateLeave", "INTEGER NOT NULL", "0"),
                    Col("confirmEarlyOT", "INTEGER NOT NULL", "0"),
                    Col("confirmLateOT", "INTEGER NOT NULL", "0")
                )
            )
            rebuildTable(
                db, "extra_items",
                listOf(
                    Col("id", "TEXT NOT NULL", "''"),
                    Col("name", "TEXT NOT NULL", "''"),
                    Col("type", "TEXT NOT NULL", "'allowance'"),
                    Col("amount", "REAL NOT NULL", "0.0")
                )
            )
            rebuildTable(
                db, "shift_breaks",
                listOf(
                    Col("id", "TEXT NOT NULL", "''"),
                    Col("label", "TEXT NOT NULL", "''"),
                    Col("startTime", "TEXT NOT NULL", "''"),
                    Col("endTime", "TEXT NOT NULL", "''")
                )
            )
            rebuildTable(
                db, "shift_statuses",
                listOf(
                    Col("id", "TEXT NOT NULL", "''"),
                    Col("name", "TEXT NOT NULL", "''"),
                    Col("color", "TEXT NOT NULL", "'#F97316'"),
                    Col("builtIn", "INTEGER NOT NULL", "0"),
                    Col("reportType", "TEXT", "NULL")
                )
            )
        }
    }

    /**
     * 6 → 7：**数据格式归一化**（表结构不变，只改数据内容）。
     *
     * 目标：把库里遗留的旧格式数据改写成当前格式，让后续版本不必再背兼容分支。
     *
     * ## 做了什么
     * 1. `schedule_records.appliedStatusesJson`：
     *    旧格式是「数组」`[{"statusId":..}]`，当前格式是「单对象」`{"statusId":..}`，
     *    无状态时是 `null`。
     *    → 单元素数组改写为对象；空数组/空串改写为 `null`；
     *    **多于一个元素的数组保持原样**（宁可保留旧格式，也不能改坏 JSON：
     *    解析旧数组的兼容分支仍在，保留即可被正确读取）。
     * 2. `schedule_records.extraItemIdsJson`：空串/`null` → `[]`（当前格式的「无补贴」）。
     *
     * ## 刻意不做的事
     * - **不**改写任何业务语义（例如把引用了已下线内置「加班」状态的历史记录改成别的）——
     *   历史工时/薪资必须逐分不变；
     * - **不**删除任何行（行数是迁移后校验的基准）。
     */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 附加状态：单元素数组 → 单对象
            // 取子串长度 = 整串长度 - 2（去掉首尾的 '[' 与 ']'）
            db.execSQL(
                """
                UPDATE `schedule_records`
                SET `appliedStatusesJson` = substr(trim(`appliedStatusesJson`), 2, length(trim(`appliedStatusesJson`)) - 2)
                WHERE substr(trim(`appliedStatusesJson`), 1, 1) = '['
                  AND substr(trim(`appliedStatusesJson`), 2, 1) = '{'
                  AND (length(`appliedStatusesJson`) - length(replace(`appliedStatusesJson`, '{', ''))) = 1
                """.trimIndent()
            )
            // 附加状态：空数组 / 空串 → null
            db.execSQL(
                """
                UPDATE `schedule_records`
                SET `appliedStatusesJson` = 'null'
                WHERE trim(replace(`appliedStatusesJson`, ' ', '')) IN ('', '[]')
                """.trimIndent()
            )
            // 补贴项：空串 / null → 空数组
            db.execSQL(
                """
                UPDATE `schedule_records`
                SET `extraItemIdsJson` = '[]'
                WHERE trim(replace(`extraItemIdsJson`, ' ', '')) IN ('', 'null')
                """.trimIndent()
            )
        }
    }

    /** 全部迁移，按版本顺序注册 */
    val ALL = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7
    )

    // ── 动态重建工具（仅 v1→v2 用） ─────────────────────────────────────

    /**
     * 目标列定义。
     *
     * @param name 列名
     * @param ddl 列定义（类型 + 约束）
     * @param fallback 源表缺该列时使用的 SQL 表达式（必须是合法 SQLite 表达式）
     * @param aliases 语义等价的旧列名（源表存在时优先取它的值）
     */
    private data class Col(
        val name: String,
        val ddl: String,
        val fallback: String,
        val aliases: List<String> = emptyList()
    )

    /**
     * 按目标列定义重建一张表：探测旧表真实列 → 拷贝能对上的列 → 用兜底值补齐其余列。
     *
     * 旧表不存在时直接建一张空表（v1 里某张表可能压根没有）。
     */
    private fun rebuildTable(
        db: SupportSQLiteDatabase,
        table: String,
        columns: List<Col>
    ) {
        val existing = db.query("PRAGMA table_info(`$table`)").use { c ->
            val idx = c.getColumnIndexOrThrow("name")
            buildList { while (c.moveToNext()) add(c.getString(idx)) }
        }
        val defs = columns.joinToString(", ") { "`${it.name}` ${it.ddl}" }
        db.execSQL("CREATE TABLE `${table}_compat` ($defs, PRIMARY KEY(`${columns.first().name}`))")
        if (existing.isNotEmpty()) {
            val selectParts = columns.joinToString(", ") { col ->
                val source = col.aliases.firstOrNull { it in existing } ?: col.name
                if (source in existing) "`$source`" else col.fallback
            }
            val names = columns.joinToString(", ") { "`${it.name}`" }
            db.execSQL("INSERT OR REPLACE INTO `${table}_compat` ($names) SELECT $selectParts FROM `$table`")
            db.execSQL("DROP TABLE `$table`")
        }
        db.execSQL("ALTER TABLE `${table}_compat` RENAME TO `$table`")
    }
}
