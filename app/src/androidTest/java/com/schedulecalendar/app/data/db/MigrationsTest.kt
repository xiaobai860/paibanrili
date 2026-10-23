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

    /** 2 → 6 全量：逐条跑完并校验最终结构 */
    @Test
    fun migrateAllFrom2To6() {
        create(2, DB_ALL)
        helper.runMigrationsAndValidate(
            DB_ALL, 6, true,
            Migrations.MIGRATION_2_3, Migrations.MIGRATION_3_4,
            Migrations.MIGRATION_4_5, Migrations.MIGRATION_5_6
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

    companion object {
        private const val DB_ALL = "migration-all"
        private const val DB_23 = "migration-2-3"
        private const val DB_34 = "migration-3-4"
        private const val DB_45 = "migration-4-5"
        private const val DB_56 = "migration-5-6"
        private const val DB_KEEP = "migration-keep"
        private const val DB_WRITE = "migration-write"
    }
}
