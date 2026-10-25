package com.schedulecalendar.app.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.schedulecalendar.app.data.db.AppDatabase
import com.schedulecalendar.app.data.prefs.AppPreferences
import com.schedulecalendar.app.domain.model.AttendConfig
import com.schedulecalendar.app.domain.model.SalaryConfig
import com.schedulecalendar.app.domain.util.DateFormats
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * 薪资/考勤配置的存储一致性守护测试。
 *
 * ## 为什么这类数据不会出现「废弃残留」
 * 薪资与考勤配置**没有归档机制**：DataStore 里各只有一份现值（覆盖写），
 * 不存在「应用里看不见、库里多一行」的历史版本。
 * 它们的第二份存储是**口径档案里的冻结快照**——那是历史口径（必需），不是垃圾。
 *
 * ## 真正要守的不变式
 * 「DataStore 现值 == 当前生效口径档案的快照」。
 * 否则用户在设置页看到的薪资不参与计算（被旧档案盖住）→ 两份真相分裂。
 * 正常路径（改配置 → 先建档案 → 再写现值）保证一致；
 * 只有「导入不带档案的旧备份」可能造成分裂 → 恢复时必须对齐。
 */
@RunWith(AndroidJUnit4::class)
class SalaryAttendConsistencyTest {

    @Test
    fun currentValueStaysConsistentWithProfileAfterSalaryOrAttendChange() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "salary-attend-consistency"
        ctx.deleteDatabase(dbName)

        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, dbName)
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
            // 初始档案建好后，现值与档案必须一致
            profileRepo.ensureInitialProfile()
            assertNull("初始状态应一致", cleaner.checkConfigConsistency())

            // 改薪资（等价于设置页的 saveSalaryAsProfile：先建档案 → 再写现值）
            val newSalary = SalaryConfig(baseSalary = 8000.0, normalRate = 25.0)
            profileRepo.createDerivedProfile(effectiveFrom = today, name = "调薪", salary = newSalary)
            prefs.saveSalaryConfig(newSalary)
            assertNull("改薪后现值与口径应一致", cleaner.checkConfigConsistency())
            assertEquals("当前生效档案的薪资应等于现值", newSalary, profileRepo.getCurrentProfile()?.salary)

            // 改考勤
            val newAttend = AttendConfig(overtimeGranMin = 15)
            profileRepo.createDerivedProfile(effectiveFrom = today, name = "考勤调整", attend = newAttend)
            prefs.saveAttendConfig(newAttend)
            assertNull("改考勤后现值与口径应一致", cleaner.checkConfigConsistency())

            // 历史档案必须保留（承载历史计算），且不被当成可清理的废弃数据
            assertTrue("应存在多份档案", profileRepo.getAll().size >= 2)
            val historical = profileRepo.getAll().first { it.effectiveTo != null }
            assertNotNull("历史档案应存在", historical)
            // 历史档案不可删除（删了历史月份会改用别的口径重算）
            assertTrue(
                "历史档案不应可删除",
                profileRepo.deletability(historical) is CalcProfileRepository.ProfileDeletability.NotDeletable
            )
            // 清理废弃数据不会碰到档案
            val before = profileRepo.getAll().size
            cleaner.clean()
            assertEquals("清理废弃数据不影响档案", before, profileRepo.getAll().size)
        }
        db.close()
        ctx.deleteDatabase(dbName)
    }
}
