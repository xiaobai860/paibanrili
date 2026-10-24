package com.schedulecalendar.app.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.schedulecalendar.app.data.db.AppDatabase
import com.schedulecalendar.app.data.db.Migrations
import com.schedulecalendar.app.data.prefs.AppPreferences
import com.schedulecalendar.app.domain.model.ShiftBreak
import com.schedulecalendar.app.domain.util.DateFormats
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * 「口径档案的删除 / 相邻去重 / 复制」三条路径的**物理数据行为**（真机守护测试）。
 *
 * ## 回答两个容易误解的问题
 * 1. **删除口径档案会不会「标记为废弃却没物理删」？**
 *    不会：`calc_profiles` 表**没有 archivedAt 字段**，不存在归档标记。
 *    删除走 `DELETE FROM calc_profiles WHERE id=?` → 行彻底消失；
 *    不可删的（历史口径 / 初始口径）则**一行都不动**（连标记都没有）。
 * 2. **「相邻重复口径合并成一个」会不会产生废弃未引用数据？**
 *    这个**去重动作本身不会**：它只是不新建档案，不写任何表行 → 档案数不变、零归档行。
 *    但「复制口径」这个用户操作会在**时段层面**产生：
 *    `syncBreaksToTable(源档案快照)` 会把「当前有效时段里不在该快照中的」那些归档，
 *    条数精确等于 |当前有效时段集合 \ 源档案.breaks|。
 */
@RunWith(AndroidJUnit4::class)
class ProfileDeleteAndCopyTest {

    @Test
    fun 删除是真删除_去重不建档_复制的残留条数可预测() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "profile-delete-copy"
        ctx.deleteDatabase(dbName)

        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, dbName)
            .addMigrations(*Migrations.ALL)
            .allowMainThreadQueries()
            .build()
        val prefs = AppPreferences(ctx)
        val breakRepo = ShiftBreakRepository(db.shiftBreakDao())
        val profileRepo = CalcProfileRepository(
            dao = db.calcProfileDao(),
            prefs = prefs,
            breakRepo = breakRepo,
            scheduleRepo = ScheduleRepository(db.scheduleRecordDao())
        )
        val cleaner = OrphanDataCleaner(
            db = db,
            scheduleRepo = ScheduleRepository(db.scheduleRecordDao()),
            shiftRepo = ShiftRepository(db.shiftDao()),
            statusRepo = ShiftStatusRepository(db.shiftStatusDao()),
            extraRepo = ExtraItemRepository(db.extraItemDao()),
            breakRepo = breakRepo,
            calcProfileRepo = profileRepo,
            prefs = prefs
        )
        val today = DateFormats.date(LocalDate.now())

        runBlocking {
            // 初始：1 个时段 + 初始口径档案
            breakRepo.save(ShiftBreak(id = "b1", label = "午休", startTime = "12:00", endTime = "13:00"))
            profileRepo.ensureInitialProfile()
            val initial = profileRepo.getAll().single()

            // 造一份「今天生效」的档案（可删）
            profileRepo.createProfile(
                name = "调薪",
                salary = prefs.salaryConfigFlow.first().copy(baseSalary = 9000.0),
                attend = prefs.attendConfigFlow.first(),
                breaks = breakRepo.getAll(),
                effectiveFrom = today
            )
            val deletable = profileRepo.getCurrentProfile()!!
            assertTrue(
                "当前生效档案应可删",
                profileRepo.deletability(deletable) is CalcProfileRepository.ProfileDeletability.Deletable
            )

            // ── 问题 1：删除 → 物理 DELETE，行彻底消失 ────────────────────
            val rowsBefore = countProfileRows(db)
            val countBefore = profileRepo.countAll()
            assertTrue(profileRepo.deleteIfUnused(deletable))
            assertEquals("档案数应减 1", countBefore - 1, profileRepo.countAll())
            assertEquals("表里的行应真的少一行（物理 DELETE，不是标记）", rowsBefore - 1, countProfileRows(db))
            assertNull("被删档案不应还能查到", profileRepo.getAll().firstOrNull { it.id == deletable.id })

            // 不可删的档案（初始口径 / 历史口径）→ 完全不动，连标记都没有
            val rowsBeforeKeep = countProfileRows(db)
            assertTrue(
                "初始口径应不可删",
                profileRepo.deletability(initial) is CalcProfileRepository.ProfileDeletability.NotDeletable
            )
            assertTrue("不可删时 deleteIfUnused 必须返回 false", !profileRepo.deleteIfUnused(initial))
            assertEquals("不可删时表里的行数不变（既没删也没标记）", rowsBeforeKeep, countProfileRows(db))
            assertNotNull("不可删的档案仍在表里", profileRepo.getAll().firstOrNull { it.id == initial.id })

            // ── 问题 2：相邻重复合并 → 不新建档案，且不产生任何归档残留 ──
            val residueBefore = cleaner.scan().cleanableCount
            val countBefore2 = profileRepo.countAll()
            val cur = profileRepo.getCurrentProfile()!!
            // 用与当前生效档案**完全相同**的内容再创建一次 → 应触发相邻去重
            val reused = profileRepo.createProfile(
                name = "重复内容",
                salary = cur.salary,
                attend = cur.attend,
                breaks = cur.breaks,
                effectiveFrom = today
            )
            assertEquals("相邻重复应被合并：不新建档案", countBefore2, profileRepo.countAll())
            assertEquals("去重命中时返回的就是原来那条", cur.id, reused.id)
            assertEquals("去重本身不产生归档残留", residueBefore, cleaner.scan().cleanableCount)

            // ── 问题 2 的另一半：复制（源 ⊊ 当前有效时段）→ 残留条数可预测 ──
            // 造一份「时段更多」的当前口径（3 个），再复制「只有 b1」的旧口径回去
            breakRepo.save(ShiftBreak(id = "b2", label = "晚餐", startTime = "18:00", endTime = "18:30"))
            breakRepo.save(ShiftBreak(id = "b3", label = "加餐", startTime = "21:00", endTime = "21:30"))
            profileRepo.createProfile(
                name = "含三个时段的口径",
                salary = cur.salary,
                attend = cur.attend,
                breaks = breakRepo.getAll(),
                effectiveFrom = today
            )
            assertEquals("当前有效时段 3 个", 3, breakRepo.getAll().size)
            val richProfile = profileRepo.getCurrentProfile()!!
            assertEquals("当前生效口径应含 3 个时段", 3, richProfile.breaks.size)

            // 复制「只有 b1」的初始口径 → syncBreaksToTable 会把 b2、b3 归档 → 恰好 2 条残留
            val residueBeforeCopy = cleaner.scan().cleanableCount
            profileRepo.syncBreaksToTable(initial.breaks)
            val residueAfterCopy = cleaner.scan().cleanableCount
            assertEquals(
                "残留条数 = |当前有效时段集合 \\ 源快照| = 3 - 1 = 2",
                residueBeforeCopy + 2, residueAfterCopy
            )
            assertEquals("有效时段回到源快照那一份", 1, breakRepo.getAll().size)

            // 残留是「已归档 + 无引用」→ 可被一键清理；清理后不影响档案与有效集合
            assertTrue("残留应可清理", cleaner.clean() >= 2)
            assertEquals("清理后无残留", 0, cleaner.scan().cleanableCount)
            assertEquals("清理后有效时段仍为 1", 1, breakRepo.getAll().size)
            assertTrue("清理不影响档案", profileRepo.countAll() >= 1)
        }
        db.close()
        ctx.deleteDatabase(dbName)
    }

    /** 直接数表里的行数：证明「物理删除」而不是「打标记」 */
    private fun countProfileRows(db: AppDatabase): Int {
        var n = 0
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM calc_profiles").use { c ->
            if (c.moveToFirst()) n = c.getInt(0)
        }
        return n
    }
}
