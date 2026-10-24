package com.schedulecalendar.app.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.schedulecalendar.app.data.db.AppDatabase
import com.schedulecalendar.app.data.db.Migrations
import com.schedulecalendar.app.data.prefs.AppPreferences
import com.schedulecalendar.app.domain.model.ShiftBreak
import com.schedulecalendar.app.domain.util.DateFormats
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * 「休息时段 ↔ 计算口径档案」的生命周期与残留行为（真机守护测试）。
 *
 * 覆盖用户提问的完整场景：
 * **创建新时段 → 删除该时段 → 逐个删除口径档案（只留初始档案）→ 观察残留**。
 *
 * 断言的结论（与设计一致）：
 * 1. 创建时段：表里 +1 条**有效**行，无归档残留；
 * 2. 删除时段：表里 +1 条**归档**行（无引用锚点 → 属「已归档且无引用」的废弃数据）；
 * 3. 删除口径档案：档案减少、当前生效档案回退，时段表的有效集合回到目标档案那一份，
 *    **不新增**归档残留（syncBreaksToTable 是「先归档全部有效、再按档案集合保存」→ 净增 0）；
 * 4. 最终只剩初始档案时：残留 = 被删时段的 1 条归档行，且**可被「清理废弃数据」清掉**；
 * 5. 全程有效时段集合 = 初始档案冻结的那一份（计算口径不受残留影响）。
 */
@RunWith(AndroidJUnit4::class)
class BreakProfileLifecycleTest {

    @Test
    fun 创建删除时段并删除档案后的残留行为() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "break-profile-lifecycle"
        ctx.deleteDatabase(dbName)

        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, dbName)
            .addMigrations(*Migrations.ALL)
            .allowMainThreadQueries()
            .build()

        val breakRepo = ShiftBreakRepository(db.shiftBreakDao())
        val profileRepo = CalcProfileRepository(
            dao = db.calcProfileDao(),
            prefs = AppPreferences(ctx),
            breakRepo = breakRepo,
            scheduleRepo = ScheduleRepository(db.scheduleRecordDao())
        )
        val cleaner = OrphanDataCleaner(
            db = db,
            scheduleRepo = ScheduleRepository(db.scheduleRecordDao()),
            shiftRepo = ShiftRepository(db.shiftDao()),
            statusRepo = ShiftStatusRepository(db.shiftStatusDao()),
            extraRepo = ExtraItemRepository(db.extraItemDao()),
            breakRepo = breakRepo
        )

        val today = DateFormats.date(LocalDate.now())
        val sql = db.openHelper.writableDatabase

        runBlocking {
            // ── 初始状态：一个生效时段 + 初始口径档案（覆盖全部历史）────────
            val base = ShiftBreak(id = "b_base", label = "午休", startTime = "12:00", endTime = "13:00")
            breakRepo.save(base)
            profileRepo.ensureInitialProfile()
            assertEquals(1, profileRepo.getAll().size)
            assertEquals(0, cleaner.scan().breaks.size)

            // ── 环节 1：创建新时段 ───────────────────────────────────────
            val created = ShiftBreak(id = "b_new", label = "晚餐", startTime = "18:00", endTime = "18:30")
            profileRepo.saveBreakWithProfile(created, today, "时段调整")
            assertEquals("创建后有效时段应为 2", 2, breakRepo.getAll().size)
            assertEquals("创建时段不应产生归档残留", 0, cleaner.scan().breaks.size)
            assertEquals("创建后档案应为 2（初始 + 新增）", 2, profileRepo.getAll().size)

            // ── 环节 2：删除该时段 ───────────────────────────────────────
            profileRepo.deleteBreakWithProfile(created.id, today, "时段调整")
            assertEquals("删除后有效时段应为 1", 1, breakRepo.getAll().size)
            assertEquals(
                "删除时段应产生 1 条归档残留（无引用锚点 → 纯废弃数据）",
                1, cleaner.scan().breaks.size
            )

            // ── 环节 3：逐个删除档案，只留初始档案 ────────────────────────
            // 反复删除「当前生效且非初始」的档案，直到只剩初始档案
            val rounds = mutableListOf<Int>()   // 每轮删除后的归档残留数
            var guard = 0
            while (true) {
                val current = profileRepo.getCurrentProfile()
                if (current == null || current.id == CalcProfileRepository.INITIAL_PROFILE_ID) break
                assertTrue("档案应可删除", profileRepo.deleteIfUnused(current))
                // 与 UI 侧一致：删除档案后把该档案的时段集合同步回表
                val restored = profileRepo.getCurrentProfile()
                profileRepo.syncBreaksToTable(restored?.breaks ?: emptyList())
                rounds += cleaner.scan().breaks.size
                guard++
                assertTrue("不应陷入死循环", guard < 10)
            }
            // 中间态：回退到「含被删时段」的档案时，它会随档案一起复活 → 残留暂时归零；
            // 回退到不含它的初始档案时 → 残留重新出现（净增 0，不会越删越多）
            assertEquals("应经历两轮档案删除", 2, rounds.size)
            assertEquals("回退到含该时段的档案时残留归零", 0, rounds[0])
            assertEquals("回退到不含该时段的初始档案时残留重现", 1, rounds[1])

            assertEquals("最终只剩初始档案", 1, profileRepo.getAll().size)
            assertEquals(
                "最终生效档案必须是初始档案",
                CalcProfileRepository.INITIAL_PROFILE_ID,
                profileRepo.getCurrentProfile()?.id
            )

            // ── 结论：残留仍在，但可被清理，且不影响有效集合 ─────────────
            assertEquals(
                "最终状态：残留仍是那 1 条归档时段",
                1, cleaner.scan().breaks.size
            )
            assertEquals("有效时段集合应回到初始档案那一份", 1, breakRepo.getAll().size)
            assertEquals("有效时段内容应与初始档案冻结的一致", "午休", breakRepo.getAll().first().label)

            // ── 其他类型：口径档案**不冻结**班次/状态/补贴项 ──────────────
            // → 删除/复制档案不会给它们带来任何归档残留（时段是唯一被档案冻结的实体）
            sql.execSQL(
                "INSERT INTO shifts (id,name,color,startTime,endTime,normalWorkHours,builtIn,builtInType,linkedExtraIdsJson,archivedAt) " +
                    "VALUES ('s_x','旧班次','#111111','09:00','18:00',8.0,0,NULL,'[]','2026-01-01')"
            )
            val before = cleaner.scan()
            assertEquals("归档班次计为可清理（无引用）", 1, before.shifts.size)
            val curProfile = profileRepo.getCurrentProfile()
            profileRepo.syncBreaksToTable(curProfile?.breaks ?: emptyList())
            val after = cleaner.scan()
            assertEquals(
                "同步时段不应影响班次的归档状态",
                before.shifts.size, after.shifts.size
            )

            // ── 清理后：归档残留归零，有效数据不动 ───────────────────────
            // 计数 = 1 条归档时段 + 上面为对照插入的 1 条归档班次
            assertEquals("应清掉 1 条归档时段 + 1 条归档班次", 2, cleaner.clean())
            assertEquals("清理后无归档时段残留", 0, cleaner.scan().breaks.size)
            assertEquals("清理后无归档班次残留", 0, cleaner.scan().shifts.size)
            assertEquals("清理后有效时段仍为 1", 1, breakRepo.getAll().size)
            assertEquals("清理后档案数不变", 1, profileRepo.getAll().size)
        }
        db.close()
        ctx.deleteDatabase(dbName)
    }
}
