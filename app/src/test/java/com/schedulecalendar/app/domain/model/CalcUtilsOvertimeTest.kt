// app/src/test/java/com/schedulecalendar/app/domain/model/CalcUtilsOvertimeTest.kt
package com.schedulecalendar.app.domain.model

import com.schedulecalendar.app.data.repository.parseAppliedStatus
import com.schedulecalendar.app.data.repository.toJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「计为加班」勾选框（[AppliedStatus.isOvertime]）的工时归类守护测试。
 *
 * 背景：加班原先**只能**靠内置「加班」状态的固定 ID 判定，用户必须造一个叫「加班」的附加状态。
 * 现在编辑页多了一个「计为加班」勾选框，**仅对当天生效**，任何自定义状态都能当加班用；
 * 并且加班工时不再一股脑塞进 overtime，而是**遵循当天计薪方式归类**：
 * 工作日 → 加班工时、周末 → 周末工时、节假日 → 节假日工时。
 *
 * 这些工时桶直接进薪资计算，算错就是钱错，所以逐条锁死。
 *
 * 2026-09-14 起：内置「加班」状态**已从 [BUILTIN_STATUSES] 下线**（新勾选框完全取代它，
 * 「加班」也从附加状态列表里消失）。加班判定自此**只认勾选框**，不再按任何状态 ID 兜底。
 */
class CalcUtilsOvertimeTest {

    /** overtimeGranMin=0 → 不做粒度取整，断言更直观 */
    private val attend = AttendConfig(
        overtimeGranMin = 0,
        normalWorkHoursPerDay = 8.0
    )

    private val customStatus = ShiftStatus(id = "st_outing", name = "外出", color = "#888888")

    /** 休息班 + 自定义附加状态时间段 09:00–18:00（9 小时，无休息扣减） */
    private fun restRecord(mode: SalaryMode?, isOvertime: Boolean, statusId: String = customStatus.id) =
        ScheduleRecord(
            date = "2026-09-13",
            shiftId = BUILTIN_SHIFT_REST,
            appliedStatus = AppliedStatus(statusId, "09:00", "18:00", isOvertime = isOvertime),
            salaryMode = mode
        )

    private fun restHours(mode: SalaryMode, isOvertime: Boolean) =
        CalcUtils.calcDayHours(
            restRecord(mode, isOvertime), "2026-09-13", emptyList(), emptyList(), attend
        )

    // ══════════════════════════════════════════════════════════════════
    // 一、勾选框 + 计薪方式 → 三档归类
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun restShift_flaggedOvertime_classifiesBySalaryMode() {
        // 工作日 → 加班工时
        restHours(SalaryMode.NORMAL, true).let {
            assertEquals("工作日加班应计入加班工时", 9.0, it.overtime, 0.001)
            assertEquals(0.0, it.weekend, 0.001)
            assertEquals(0.0, it.holiday, 0.001)
        }
        // 周末 → 周末工时
        restHours(SalaryMode.WEEKEND, true).let {
            assertEquals("周末加班应计入周末工时", 9.0, it.weekend, 0.001)
            assertEquals(0.0, it.overtime, 0.001)
            assertEquals(0.0, it.holiday, 0.001)
        }
        // 节假日 → 节假日工时
        restHours(SalaryMode.HOLIDAY, true).let {
            assertEquals("节假日加班应计入节假日工时", 9.0, it.holiday, 0.001)
            assertEquals(0.0, it.overtime, 0.001)
            assertEquals(0.0, it.weekend, 0.001)
        }
    }

    /** 不勾选 → 不算加班：休息班的工作日时段进「正常工时」（旧行为不变） */
    @Test
    fun restShift_withoutFlag_isNotOvertime() {
        restHours(SalaryMode.NORMAL, false).let {
            assertEquals("未勾选应计为正常工时", 9.0, it.normal, 0.001)
            assertEquals("未勾选不应产生加班工时", 0.0, it.overtime, 0.001)
        }
    }

    /** 内置「加班」状态已下线：不能再出现在可选状态列表里（已由「计为加班」勾选框取代） */
    @Test
    fun builtinOvertimeStatus_isRetiredFromSelectableList() {
        // 字面量固定 ID，防止有人把「加班」重新加回内置列表
        assertFalse(
            "内置「加班」状态应已从 BUILTIN_STATUSES 移除",
            BUILTIN_STATUSES.any { it.id == "__builtin_status_overtime__" }
        )
        assertTrue(
            "可选内置状态不应再有 reportType=overtime 的项",
            BUILTIN_STATUSES.none { it.reportType == "overtime" }
        )
    }

    /** 加班判定**只认勾选框**：已下线的内置「加班」状态 ID 不再参与判定 */
    @Test
    fun countsAsOvertime_onlyFromCheckbox() {
        assertTrue(AppliedStatus("st_outing", isOvertime = true).countsAsOvertime)
        assertFalse("未勾选即不算加班", AppliedStatus("st_outing", isOvertime = false).countsAsOvertime)
        assertFalse(
            "已下线的内置「加班」状态 ID 不应再被认作加班",
            AppliedStatus("__builtin_status_overtime__").countsAsOvertime
        )
    }

    // ══════════════════════════════════════════════════════════════════
    // 二、普通班次：加班状态的时间段不从正常工时里扣减
    // ══════════════════════════════════════════════════════════════════

    private val shift = Shift(
        id = "shift_day", name = "白班",
        startTime = "08:00", endTime = "17:00",   // 9 小时
        normalWorkHours = 8.0
    )

    private fun shiftHours(isOvertime: Boolean) = CalcUtils.calcDayHours(
        ScheduleRecord(
            date = "2026-09-13",
            type = ScheduleType.SHIFT,
            shiftId = shift.id,
            appliedStatus = AppliedStatus(customStatus.id, "18:00", "20:00", isOvertime = isOvertime),
            salaryMode = SalaryMode.NORMAL
        ),
        "2026-09-13", listOf(shift), emptyList(), attend
    )

    @Test
    fun normalShift_flaggedOvertimeStatusTime_isNotDeductedFromWorked() {
        // 勾选 → 状态时间段是额外工时，班次 9h 全额保留：正常 8h + 加班 1h
        shiftHours(true).let {
            assertEquals(8.0, it.normal, 0.001)
            assertEquals(1.0, it.overtime, 0.001)
        }
        // 未勾选 → 状态时间段从工时中扣减：9h - 2h = 7h
        shiftHours(false).let {
            assertEquals(7.0, it.normal, 0.001)
            assertEquals(0.0, it.overtime, 0.001)
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 三、持久化：新字段向后兼容（因此不需要 Room 迁移）
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun appliedStatusJson_roundTripsOvertimeFlag() {
        val s = AppliedStatus("st_x", "09:00", "18:00", isOvertime = true)
        assertEquals(s, parseAppliedStatus(s.toJson()))
    }

    @Test
    fun appliedStatusJson_legacyWithoutField_parsesAsNotOvertime() {
        // 改造前写入的 JSON（没有 isOvertime 字段）
        val legacy = parseAppliedStatus("""{"statusId":"st_x","startTime":"09:00","endTime":"18:00"}""")
        assertEquals(AppliedStatus("st_x", "09:00", "18:00", isOvertime = false), legacy)
        assertFalse("旧数据必须解析为非加班", legacy!!.countsAsOvertime)
    }
}
