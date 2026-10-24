package com.schedulecalendar.app.data.compat

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import com.schedulecalendar.app.data.db.AppDatabase
import com.schedulecalendar.app.data.db.Migrations
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 兼容层「迁移前快照 → 迁移 → 迁移后校验」的真机闭环测试。
 *
 * 覆盖两段此前从未在真实数据库上跑过的代码：
 * - [LegacyDbSnapshot]：只读抓取旧库 → 当前格式 JSON（回滚用的底牌）；
 * - [DataIntegrityChecker]：行数/JSON/口径三类校验，以及「坏数据必须被拦住」。
 */
@RunWith(AndroidJUnit4::class)
class CompatSnapshotAndCheckTest {

    @Test
    fun 快照与校验在真实数据库上闭环() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AppDatabase::class.java
        )
        val dbName = "compat-snapshot-check"

        // ── 1) 造一个 v6 旧库（附加状态用旧「数组」格式）────────────────
        val db6: SupportSQLiteDatabase = helper.createDatabase(dbName, 6)
        db6.execSQL(
            "INSERT OR REPLACE INTO shifts (id, name, color, startTime, endTime, normalWorkHours, " +
                "builtIn, builtInType, linkedExtraIdsJson, archivedAt) " +
                "VALUES ('s1','早班','#3b82f6','08:00','17:00',8.0,0,NULL,'[]',NULL)"
        )
        db6.execSQL(
            "INSERT OR REPLACE INTO schedule_records (date, type, shiftId, actualStartTime, actualEndTime, " +
                "remark, extraItemIdsJson, appliedStatusesJson, salaryMode, ignoreEarlyArrival, " +
                "ignoreLateLeave, confirmEarlyOT, confirmLateOT) VALUES " +
                "('2026-01-05','SHIFT','s1',NULL,NULL,NULL,'[]'," +
                "'[{\"statusId\":\"st1\",\"startTime\":\"09:00\",\"endTime\":\"18:00\"}]',NULL,0,0,0,0)"
        )
        db6.close()

        // ── 2) 迁移前快照（真实执行）───────────────────────────────────
        val snapshot = LegacyDbSnapshot.capture(ctx, ctx.getDatabasePath(dbName))
        assertNotNull("快照抓取失败", snapshot)
        val snap = snapshot!!
        assertEquals(6, snap.dbVersion)
        assertEquals(1, snap.counts.records)
        assertEquals(1, snap.counts.shifts)
        assertEquals(0, snap.counts.profiles)

        // 快照本身已是**当前格式**：附加状态是单对象，而不是旧数组
        val root = JsonParser.parseString(snap.json).asJsonObject
        val record = root.getAsJsonArray("scheduleRecords")[0].asJsonObject
        assertFalse("快照里仍留着旧字段名 appliedStatuses", record.has("appliedStatuses"))
        assertEquals(
            "st1",
            record.get("appliedStatus").asJsonObject.get("statusId").asString
        )

        // ── 3) 用 Room 打开同一个库 → 触发迁移到 v7 ────────────────────
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, dbName)
            .addMigrations(*Migrations.ALL)
            .allowMainThreadQueries()
            .build()
        db.openHelper.writableDatabase

        val checker = DataIntegrityChecker(
            scheduleRecordDao = db.scheduleRecordDao(),
            shiftDao = db.shiftDao(),
            shiftStatusDao = db.shiftStatusDao(),
            shiftBreakDao = db.shiftBreakDao(),
            extraItemDao = db.extraItemDao(),
            calcProfileDao = db.calcProfileDao()
        )

        runBlocking {
            // ── 4) 缺口径档案必须判为错误（否则历史薪资会悄悄算错）──────
            val noProfile = checker.check(snap.counts)
            assertFalse("缺计算口径档案时应当判为错误", noProfile.ok)
            assertTrue(
                "错误信息应指出口径档案缺失，实际：${noProfile.errors}",
                noProfile.errors.any { it.contains("计算口径档案") }
            )

            // 补一份初始口径（生产环境由 ensureInitialProfile 负责）
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO calc_profiles (id, name, salaryJson, attendJson, breaksJson, " +
                    "effectiveFrom, effectiveTo, note, createdAt) " +
                    "VALUES ('p1','初始口径','{}','{}','[]','2000-01-01',NULL,NULL,1)"
            )

            // ── 5) 补齐后校验通过，且旧数组已归一化为单对象 ─────────────
            val report = checker.check(snap.counts)
            assertTrue(report.summary(), report.ok)
            db.openHelper.writableDatabase
                .query("SELECT appliedStatusesJson FROM schedule_records WHERE date = '2026-01-05'")
                .use { c ->
                    assertTrue(c.moveToFirst())
                    val json = c.getString(0)
                    assertFalse("旧数组格式未被归一化：$json", json.trim().startsWith("["))
                    val obj = JsonParser.parseString(json).asJsonObject
                    assertEquals("st1", obj.get("statusId").asString)
                    assertEquals("09:00", obj.get("startTime").asString)
                }

            // ── 6) 归档数据不得让行数校验误判 ─────────────────────────────
            // 回归：2026-10-24 真机升级时，校验器拿「原始行数」比「仅有效行数」，
            // 把用户的 4 个归档时段算成了「迁移丢了数据」→ 升级被误判失败。
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO shift_breaks (id, label, startTime, endTime, archivedAt) " +
                    "VALUES ('b2','归档时段','10:00','10:30','2026-01-01')"
            )
            val afterArchive = checker.check(null)
            assertTrue(
                "归档时段不应被判为错误：${afterArchive.errors}",
                afterArchive.ok
            )

            // ── 7) 负例：数据被改坏时校验必须报错（行数对不上）──────────
            db.openHelper.writableDatabase.execSQL("DELETE FROM schedule_records WHERE date = '2026-01-05'")
            val afterDelete = checker.check(snap.counts)
            assertFalse("行数对不上时应当判为错误", afterDelete.ok)
            assertTrue(
                "错误信息应指出行数异常，实际：${afterDelete.errors}",
                afterDelete.errors.any { it.contains("排班记录数异常") }
            )
        }

        // ── 8) 真实快照必须能被转换器原样接受（回滚底牌的最后一道关口）──
        // 回归：升级失败时的回滚走的是 restoreFromJson(快照JSON)，
        // 若转换器在真实快照上报错，回滚会静默失败 → 用户数据停在半迁移状态。
        val upgraded = BackupCompat.upgrade(snap.json)
        val upgradedRoot = JsonParser.parseString(upgraded.json).asJsonObject
        assertEquals(
            snap.counts.records,
            upgradedRoot.getAsJsonArray("scheduleRecords").size()
        )

        db.close()
    }
}

