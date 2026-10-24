package com.schedulecalendar.app.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room 迁移守护测试（仪器测试，需真机/模拟器）。
 *
 * 作用：把「迁移后的表结构必须与实体声明一致」这条校验从**真机运行时**提前到自动化测试。
 * 背景：v6（计算口径档案）首次上真机时，迁移里多建了一个索引而实体未声明，
 * Room 判定 `Migration didn't properly handle: calc_profiles` → App 启动即崩溃，
 * 而编译期与单元测试都发现不了；补上这层测试后同类问题会在测试阶段暴露。
 *
 * 起点说明：本项目最早导出的 schema 是 **2.json**（没有 1.json），故从 version 2 起测。
 * schema JSON 通过 `app/build.gradle.kts` 的 sourceSets 映射进 androidTest 的 assets。
 */
@RunWith(AndroidJUnit4::class)
class MigrationsTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        databaseClass = AppDatabase::class.java,
        specs = emptyList(),
        openFactory = FrameworkSQLiteOpenHelperFactory()
    )

    /** 建一个指定版本的空库（runMigrationsAndValidate 前必须先建） */
    private fun create(version: Int, name: String) {
        helper.createDatabase(name, version).close()
    }

    /** 2 → 7 全量：逐条跑完并校验最终结构 */
    @Test
    fun migrateAllFrom2To7() {
        create(2, DB_ALL)
        helper.runMigrationsAndValidate(
            DB_ALL, 7, true,
            Migrations.MIGRATION_2_3, Migrations.MIGRATION_3_4,
            Migrations.MIGRATION_4_5, Migrations.MIGRATION_5_6,
            Migrations.MIGRATION_6_7
        )
    }

    @Test
    fun migrate2To3() {
        create(2, DB_23)
        helper.runMigrationsAndValidate(DB_23, 3, true, Migrations.MIGRATION_2_3)
    }

    @Test
    fun migrate3To4() {
        create(3, DB_34)
        helper.runMigrationsAndValidate(DB_34, 4, true, Migrations.MIGRATION_3_4)
    }

    @Test
    fun migrate4To5() {
        create(4, DB_45)
        helper.runMigrationsAndValidate(DB_45, 5, true, Migrations.MIGRATION_4_5)
    }

    /** 5 → 6：计算口径档案表 + effectiveFrom 索引（本次新增的迁移） */
    @Test
    fun migrate5To6() {
        create(5, DB_56)
        helper.runMigrationsAndValidate(DB_56, 6, true, Migrations.MIGRATION_5_6)
    }

    /**
     * 数据保留性：迁移**不得**动已有业务数据（本项目只新增表/列）。
     * 从 v5 建库并插入一条班次，迁移到 v6 后应仍查得到、且内容一致。
     */
    @Test
    fun migrationKeepsExistingRows() {
        val db: SupportSQLiteDatabase = helper.createDatabase(DB_KEEP, 5)
        db.execSQL(
            "INSERT OR REPLACE INTO shifts (id, name, color, startTime, endTime, normalWorkHours, builtIn, builtInType, linkedExtraIdsJson) " +
                "VALUES ('s1', 'day', '#FF0000', '09:00', '18:00', 8.0, 0, NULL, '[]')"
        )
        db.close()

        val migrated = helper.runMigrationsAndValidate(DB_KEEP, 6, true, Migrations.MIGRATION_5_6)
        migrated.query("SELECT name FROM shifts WHERE id = 's1'").use { c ->
            assert(c.moveToFirst()) { "迁移后原有班次记录丢失" }
            assert(c.getString(0) == "day") { "migration changed existing data" }
        }
        migrated.close()
    }

    /** 新增表可写：迁移后 calc_profiles 应可正常插入（档案机制的基本前提） */
    @Test
    fun calcProfilesUsableAfterMigration() {
        create(5, DB_WRITE)
        val db = helper.runMigrationsAndValidate(DB_WRITE, 6, true, Migrations.MIGRATION_5_6)
        db.execSQL(
            "INSERT INTO calc_profiles (id, name, salaryJson, attendJson, breaksJson, effectiveFrom, effectiveTo, note, createdAt) " +
                "VALUES ('p1', '初始口径', '{}', '{}', '[]', '2000-01-01', NULL, NULL, 1)"
        )
        db.query("SELECT COUNT(*) FROM calc_profiles WHERE id = 'p1'").use { c ->
            assert(c.moveToFirst())
            assert(c.getInt(0) == 1) { "迁移后 calc_profiles 不可写" }
        }
        db.close()
    }

    /** 6 → 7：结构不变，只做数据格式归一化 */
    @Test
    fun migrate6To7() {
        create(6, DB_67)
        helper.runMigrationsAndValidate(DB_67, 7, true, Migrations.MIGRATION_6_7)
    }

    /**
     * 6 → 7 的**数据格式归一化**行为：
     * 附加状态从旧「数组」格式改写成当前「单对象」格式，空数组 → null。
     * 这是「升级时把旧数据转换成新格式」的核心用例。
     */
    @Test
    fun migration6To7NormalizesAppliedStatusFormat() {
        val db: SupportSQLiteDatabase = helper.createDatabase(DB_NORM, 6)
        db.execSQL(
            "INSERT OR REPLACE INTO schedule_records " +
                "(date, type, shiftId, actualStartTime, actualEndTime, remark, extraItemIdsJson, appliedStatusesJson, " +
                "salaryMode, ignoreEarlyArrival, ignoreLateLeave, confirmEarlyOT, confirmLateOT) VALUES " +
                "('2026-01-05','SHIFT','s1',NULL,NULL,NULL,'[]','[{\"statusId\":\"s1\",\"startTime\":\"09:00\",\"endTime\":\"18:00\"}]',NULL,0,0,0,0)"
        )
        db.execSQL(
            "INSERT OR REPLACE INTO schedule_records " +
                "(date, type, shiftId, actualStartTime, actualEndTime, remark, extraItemIdsJson, appliedStatusesJson, " +
                "salaryMode, ignoreEarlyArrival, ignoreLateLeave, confirmEarlyOT, confirmLateOT) VALUES " +
                "('2026-01-06','SHIFT','s1',NULL,NULL,NULL,'[]','[]',NULL,0,0,0,0)"
        )
        db.close()

        val migrated = helper.runMigrationsAndValidate(DB_NORM, 7, true, Migrations.MIGRATION_6_7)
        migrated.query("SELECT appliedStatusesJson FROM schedule_records WHERE date = '2026-01-05'").use { c ->
            assert(c.moveToFirst())
            val json = c.getString(0)
            assert(!json.trim().startsWith("[")) { "旧数组格式未被归一化：$json" }
            // 必须仍是**合法 JSON**（子串长度算错会截断成非法 JSON，这里必须能解析）
            val parsed = com.google.gson.JsonParser.parseString(json)
            assert(parsed.isJsonObject) { "归一化后不是 JSON 对象：$json" }
            assert(parsed.asJsonObject.get("statusId").asString == "s1") { "归一化后丢了 statusId：$json" }
            assert(parsed.asJsonObject.get("startTime").asString == "09:00") { "归一化后丢了时间段：$json" }
        }
        migrated.query("SELECT appliedStatusesJson FROM schedule_records WHERE date = '2026-01-06'").use { c ->
            assert(c.moveToFirst())
            assert(c.getString(0) == "null") { "空数组未被归一化为 null" }
        }
        // 行数不能变（迁移只改内容不改数量）
        migrated.query("SELECT COUNT(*) FROM schedule_records").use { c ->
            assert(c.moveToFirst())
            assert(c.getInt(0) == 2) { "迁移改变了排班行数" }
        }
        migrated.close()
    }

    /**
     * v1 → 7：最早一版数据库（无导出 schema）走**动态重建**迁移。
     *
     * v1 的真实结构无从考据，因此迁移必须「探测旧列 → 能对上的拷贝 → 对不上的用默认值补齐」，
     * 并且要能把语义等价的旧列（workHours → normalWorkHours）映射过来。
     */
    @Test
    fun migrateV1LikeDatabaseTo7() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-v1"
        val file = ctx.getDatabasePath(name)
        file.parentFile?.mkdirs()
        file.delete()
        val raw = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)
        // v1 形态：班次表用 workHours，没有 linkedExtraIdsJson/archivedAt
        raw.execSQL(
            "CREATE TABLE shifts (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, color TEXT NOT NULL, " +
                "startTime TEXT NOT NULL, endTime TEXT NOT NULL, workHours REAL, builtIn INTEGER NOT NULL, builtInType TEXT)"
        )
        raw.execSQL(
            "INSERT INTO shifts VALUES ('s1','早班','#3b82f6','08:00','17:00',8.0,0,NULL)"
        )
        raw.execSQL(
            "CREATE TABLE schedule_records (date TEXT NOT NULL PRIMARY KEY, type TEXT NOT NULL, shiftId TEXT, " +
                "actualStartTime TEXT, actualEndTime TEXT, remark TEXT, extraItemIdsJson TEXT NOT NULL, " +
                "appliedStatusesJson TEXT NOT NULL, salaryMode TEXT, ignoreEarlyArrival INTEGER NOT NULL, " +
                "ignoreLateLeave INTEGER NOT NULL, confirmEarlyOT INTEGER NOT NULL, confirmLateOT INTEGER NOT NULL)"
        )
        raw.execSQL(
            "INSERT INTO schedule_records VALUES ('2026-01-05','SHIFT','s1',NULL,NULL,NULL,'[]','[]',NULL,0,0,0,0)"
        )
        raw.version = 1
        raw.close()

        val room = androidx.room.Room.databaseBuilder(ctx, AppDatabase::class.java, name)
            .addMigrations(*Migrations.ALL)
            .allowMainThreadQueries()
            .build()
        val migrated = room.openHelper.writableDatabase
        // 旧列 workHours 被映射到 normalWorkHours
        migrated.query("SELECT normalWorkHours FROM shifts WHERE id = 's1'").use { c ->
            assert(c.moveToFirst()) { "v1 班次在迁移后丢失" }
            assert(c.getDouble(0) == 8.0) { "v1 的 workHours 未映射到 normalWorkHours" }
        }
        migrated.query("SELECT COUNT(*) FROM schedule_records").use { c ->
            assert(c.moveToFirst())
            assert(c.getInt(0) == 1) { "v1 排班记录在迁移后丢失" }
        }
        // v2 之后新增的列必须存在且可用默认值
        migrated.query("SELECT linkedExtraIdsJson, archivedAt FROM shifts WHERE id = 's1'").use { c ->
            assert(c.moveToFirst())
            assert(c.getString(0) == "[]") { "缺失列未落默认值" }
        }
        room.close()
        file.delete()
    }

    companion object {
        private const val DB_ALL = "migration-all"
        private const val DB_23 = "migration-2-3"
        private const val DB_34 = "migration-3-4"
        private const val DB_45 = "migration-4-5"
        private const val DB_56 = "migration-5-6"
        private const val DB_67 = "migration-6-7"
        private const val DB_NORM = "migration-normalize"
        private const val DB_KEEP = "migration-keep"
        private const val DB_WRITE = "migration-write"
    }
}
