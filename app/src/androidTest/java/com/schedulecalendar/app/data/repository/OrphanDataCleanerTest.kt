package com.schedulecalendar.app.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.schedulecalendar.app.data.db.AppDatabase
import com.schedulecalendar.app.data.db.Migrations
import com.schedulecalendar.app.data.prefs.AppPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 废弃数据扫描/清理的真机守护测试。
 *
 * 锁住三条规则：
 * 1. **已归档的休息时段是纯垃圾** → 可清理（它没有引用锚点，不参与任何计算）；
 * 2. **已归档但仍被历史排班引用的项必须保留**（历史锚点，删了历史工时/薪资会算错）；
 * 3. **未归档（生效中）的数据一律不碰**。
 */
@RunWith(AndroidJUnit4::class)
class OrphanDataCleanerTest {

    @Test
    fun 只清理已归档且无引用的数据() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "orphan-cleaner-test"
        ctx.deleteDatabase(dbName)

        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, dbName)
            .addMigrations(*Migrations.ALL)
            .allowMainThreadQueries()
            .build()
        val sql = db.openHelper.writableDatabase
        // 生效中的时段 / 班次（不能碰）
        sql.execSQL("INSERT INTO shift_breaks (id,label,startTime,endTime,archivedAt) VALUES ('b1','午休','12:00','13:00',NULL)")
        sql.execSQL("INSERT INTO shifts (id,name,color,startTime,endTime,normalWorkHours,builtIn,builtInType,linkedExtraIdsJson,archivedAt) VALUES ('s1','早班','#111111','08:00','17:00',8.0,0,NULL,'[]',NULL)")
        // 已归档的时段 / 班次（无引用 → 可清）
        sql.execSQL("INSERT INTO shift_breaks (id,label,startTime,endTime,archivedAt) VALUES ('b2','旧时段','18:00','18:30','2026-01-01')")
        sql.execSQL("INSERT INTO shifts (id,name,color,startTime,endTime,normalWorkHours,builtIn,builtInType,linkedExtraIdsJson,archivedAt) VALUES ('s2','旧班次','#222222','09:00','18:00',8.0,0,NULL,'[]','2026-01-01')")
        // 已归档但被历史排班引用的班次（必须保留）
        sql.execSQL("INSERT INTO shifts (id,name,color,startTime,endTime,normalWorkHours,builtIn,builtInType,linkedExtraIdsJson,archivedAt) VALUES ('s3','历史班次','#333333','09:00','18:00',8.0,0,NULL,'[]','2026-01-01')")
        sql.execSQL(
            "INSERT INTO schedule_records (date,type,shiftId,actualStartTime,actualEndTime,remark," +
                "extraItemIdsJson,appliedStatusesJson,salaryMode,ignoreEarlyArrival,ignoreLateLeave,confirmEarlyOT,confirmLateOT) " +
                "VALUES ('2026-01-05','SHIFT','s3',NULL,NULL,NULL,'[]','null',NULL,0,0,0,0)"
        )

        val breakRepo = ShiftBreakRepository(db.shiftBreakDao())
        val cleaner = OrphanDataCleaner(
            db = db,
            scheduleRepo = ScheduleRepository(db.scheduleRecordDao()),
            shiftRepo = ShiftRepository(db.shiftDao()),
            statusRepo = ShiftStatusRepository(db.shiftStatusDao()),
            extraRepo = ExtraItemRepository(db.extraItemDao()),
            breakRepo = breakRepo,
            calcProfileRepo = CalcProfileRepository(
                dao = db.calcProfileDao(),
                prefs = AppPreferences(ctx),
                breakRepo = breakRepo,
                scheduleRepo = ScheduleRepository(db.scheduleRecordDao())
            ),
            prefs = AppPreferences(ctx)
        )

        runBlocking {
            val scan = cleaner.scan()
            // 可清理：归档时段 1 + 归档且无引用的班次 1
            assertEquals("可清理的时段数", 1, scan.breaks.size)
            assertEquals("可清理的班次数", 1, scan.shifts.size)
            // 必须保留：被历史排班引用的归档班次
            assertEquals("必须保留的班次数", 1, scan.keptShifts.size)
            assertTrue(scan.keptShifts.any { it.id == "s3" })

            val deleted = cleaner.clean()
            assertEquals("应删除 2 条", 2, deleted)

            // 复查：生效中的数据与被引用的归档项都还在
            sql.query("SELECT COUNT(*) FROM shift_breaks").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("生效中的时段必须保留", 1, c.getInt(0))
            }
            sql.query("SELECT COUNT(*) FROM shifts").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("生效班次 + 被引用的历史班次", 2, c.getInt(0))
            }
            // 再扫一次：已无可清理项
            assertEquals(0, cleaner.scan().cleanableCount)
        }
        db.close()
        ctx.deleteDatabase(dbName)
    }
}

