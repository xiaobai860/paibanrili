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
 * 「删除/复制口径档案 → 时段集合回退」的残留行为（真机守护测试）。
 *
 * ## 场景（2026-10-24 用户现场复现）
 * 用户建了若干时段（每个都会生成一份含该时段的口径档案），
 * 后来把档案删到只剩「初始口径」——初始档案是**更早期**冻结的，
 * 快照里不含后来新建的那些时段 → 回退时它们被归档 → 出现废弃残留。
 *
 * ## 结论（本测试固化）
 * 1. 当**表里的有效时段集合 ⊋ 目标档案的快照集合**时，回退会把多出来的时段归档 → **产生残留**；
 * 2. 两者一致时，回退是幂等替换 → **不产生**残留（净增 0，不会越清越多）；
 * 3. 「一键对齐」（把现值 + 当前有效时段固化成今天生效的档案）能恢复这条不变式，
 *    对齐后回退不再产生残留。
 */
@RunWith(AndroidJUnit4::class)
class ProfileDeletionResidueTest {

    @Test
    fun 删除档案时按集合关系决定是否产生残留() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "profile-deletion-residue"
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
            // 初始：1 个时段 + 初始口径档案（快照只含这 1 个）
            breakRepo.save(ShiftBreak(id = "b1", label = "午休", startTime = "12:00", endTime = "13:00"))
            profileRepo.ensureInitialProfile()
            assertEquals("初始档案快照只含 1 个时段", 1, profileRepo.getCurrentProfile()?.breaks?.size)
            assertNull("初始状态一致", cleaner.checkConfigConsistency())

            // 后来新建了 2 个时段（各生成一份档案）
            profileRepo.saveBreakWithProfile(
                ShiftBreak(id = "b2", label = "晚餐", startTime = "18:00", endTime = "18:30"), today, "时段调整"
            )
            profileRepo.saveBreakWithProfile(
                ShiftBreak(id = "b3", label = "加餐", startTime = "21:00", endTime = "21:30"), today, "时段调整"
            )
            assertEquals("表里现有 3 个有效时段", 3, breakRepo.getAll().size)
            // 表里 3 个 vs 当前档案快照 3 个 → 一致
            assertNull("新建后应一致", cleaner.checkConfigConsistency())

            // 删掉档案直到只剩初始档案（初始档案快照里没有 b2/b3）
            var guard = 0
            while (true) {
                val cur = profileRepo.getCurrentProfile()
                if (cur == null || cur.id == CalcProfileRepository.INITIAL_PROFILE_ID) break
                assertTrue(profileRepo.deleteIfUnused(cur))
                val restored = profileRepo.getCurrentProfile()
                profileRepo.syncBreaksToTable(restored?.breaks ?: emptyList())
                guard++
                assertTrue(guard < 10)
            }
            assertEquals("最终只剩初始档案", 1, profileRepo.getAll().size)

            // 结论 1：回退到「不含 b2/b3」的初始档案 → b2/b3 被归档 → 产生 2 条残留
            assertEquals("有效时段回到初始档案那一份", 1, breakRepo.getAll().size)
            val residue = cleaner.scan()
            assertEquals("两条被归档的时段", 2, residue.breaks.size + residue.breaksInProfileSnapshots.size)
            assertNull(
                "同步后有效集合已收敛到档案快照 → 一致性检查应通过（残留是归档行，不是活跃不一致）",
                cleaner.checkConfigConsistency()
            )

            // 结论 2：清掉残留后，回退（再次同步）不会让它复现
            cleaner.clean()
            assertEquals("清理后无残留", 0, cleaner.scan().cleanableCount)
            profileRepo.syncBreaksToTable(profileRepo.getCurrentProfile()?.breaks ?: emptyList())
            assertEquals(
                "集合一致后再同步不应产生残留",
                0, cleaner.scan().cleanableCount
            )

            // 结论 3：「一键对齐」= 把现值 + 当前有效时段固化成今天生效的档案 → 不变式恢复
            val curBefore = profileRepo.getCurrentProfile()
            assertNotNull(curBefore)
            profileRepo.createProfile(
                name = "${today.take(7)} 口径对齐",
                salary = prefs.salaryConfigFlow.first(),
                attend = prefs.attendConfigFlow.first(),
                breaks = breakRepo.getAll(),
                effectiveFrom = today
            )
            profileRepo.getCurrentProfile()?.let { profileRepo.syncBreaksToTable(it.breaks) }
            assertNull("对齐后应一致", cleaner.checkConfigConsistency())
            assertEquals("对齐后无残留", 0, cleaner.scan().cleanableCount)
            // 归档残留在「对齐 + 清理」后归零，且不会因为再次同步而复现
            profileRepo.syncBreaksToTable(profileRepo.getCurrentProfile()?.breaks ?: emptyList())
            assertEquals("再次同步不应产生残留", 0, cleaner.scan().cleanableCount)
        }
        db.close()
        ctx.deleteDatabase(dbName)
    }
}
