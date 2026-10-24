package com.schedulecalendar.app.ui.settings

import com.schedulecalendar.app.data.compat.BackupGson
import com.schedulecalendar.app.domain.model.AppliedStatus
import com.schedulecalendar.app.domain.model.CalcProfile
import com.schedulecalendar.app.domain.model.ExtraItem
import com.schedulecalendar.app.domain.model.SalaryConfig
import com.schedulecalendar.app.domain.model.ScheduleRecord
import com.schedulecalendar.app.domain.model.Shift
import com.schedulecalendar.app.domain.model.ShiftBreak
import com.schedulecalendar.app.domain.model.ShiftStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份反序列化守护测试：旧版本备份缺字段时必须落 **Kotlin 默认值**。
 *
 * 背景（2026-10-24 备份审查）：Gson 对无无参构造器的 Kotlin data class 用 Unsafe
 * 分配实例，缺失字段保持 JVM null——v1/v2 时代备份缺 ShiftStatus.startTime/endTime
 * （非空 + NOT NULL 列）会导致恢复直接撞 Room 约束失败。BackupGson 注册 InstanceCreator
 * 修复此事；这些测试锁住该行为，防止回归。
 *
 * 字段新增时间线（用于构造各时代备份）：
 * - v1/v2：ShiftStatus 无 startTime/endTime；Shift 无 linkedExtraIds/archivedAt
 * - v4：新增 calcProfiles（整表替换，缺字段保持现状）
 * - 2026-09：AppliedStatus 新增 isOvertime
 */
class BackupGsonTest {

    private val gson = BackupGson.instance

    @Test
    fun `v1时代班次状态缺时间段字段时落空串而非null`() {
        // v1/v2 备份原文：{"id":"s1","name":"外出","color":"#F97316","builtIn":false}
        val json = """{"id":"s1","name":"外出","color":"#F97316","builtIn":false}"""
        val st = gson.fromJson(json, ShiftStatus::class.java)
        assertEquals("", st.startTime)
        assertEquals("", st.endTime)
        assertNull(st.archivedAt)
        assertFalse(st.builtIn)
    }

    @Test
    fun `v1时代班次缺关联项目字段时落空列表`() {
        val json =
            """{"id":"sh1","name":"早班","color":"#3b82f6","startTime":"08:00","endTime":"17:00","builtIn":false}"""
        val shift = gson.fromJson(json, Shift::class.java)
        assertEquals(emptyList<String>(), shift.linkedExtraIds)
        assertNull(shift.archivedAt)
        assertEquals("早班", shift.name)
    }

    @Test
    fun `排班记录缺附加状态加班勾选时落false`() {
        // 2026-09 之前的备份 appliedStatus 无 isOvertime 字段
        val json =
            """{"date":"2026-01-05","shiftId":"sh1","appliedStatus":{"statusId":"s1","startTime":"09:00","endTime":"10:00"}}"""
        val record = gson.fromJson(json, ScheduleRecord::class.java)
        val status: AppliedStatus? = record.appliedStatus
        requireNotNull(status)
        assertFalse(status.isOvertime)
        assertEquals("09:00", status.startTime)
    }

    @Test
    fun `排班记录缺全部可选字段时仍可构造`() {
        val json = """{"date":"2026-01-05"}"""
        val record = gson.fromJson(json, ScheduleRecord::class.java)
        assertEquals("2026-01-05", record.date)
        assertEquals(emptyList<String>(), record.extraItemIds)
        assertNull(record.appliedStatus)
        assertFalse(record.ignoreEarlyArrival)
    }

    @Test
    fun `旧版口径档案缺嵌套配置时落默认值`() {
        val json =
            """{"id":"p1","name":"初始口径","effectiveFrom":"2000-01-01","effectiveTo":null}"""
        val p = gson.fromJson(json, CalcProfile::class.java)
        assertEquals(SalaryConfig(), p.salary)
        assertEquals(emptyList<ShiftBreak>(), p.breaks)
        assertEquals("2000-01-01", p.effectiveFrom)
        assertNull(p.effectiveTo)
    }

    @Test
    fun `休息时段与补贴项缺归档字段时落null`() {
        val breakJson = """{"id":"b1","label":"午休","startTime":"12:00","endTime":"13:00"}"""
        val br = gson.fromJson(breakJson, ShiftBreak::class.java)
        assertNull(br.archivedAt)

        val extraJson = """{"id":"e1","name":"全勤","type":"allowance","amount":300.0}"""
        val extra = gson.fromJson(extraJson, ExtraItem::class.java)
        assertNull(extra.archivedAt)
        assertEquals(300.0, extra.amount, 0.0001)
    }

    @Test
    fun `当前版本模型序列化后可原样反序列化`() {
        val shift = Shift(
            id = "sh1", name = "早班", color = "#3b82f6",
            startTime = "08:00", endTime = "17:00",
            normalWorkHours = 8.0, builtIn = false,
            linkedExtraIds = listOf("e1"), archivedAt = null
        )
        val roundTrip = gson.fromJson(gson.toJson(shift), Shift::class.java)
        assertEquals(shift, roundTrip)
        assertTrue(roundTrip.linkedExtraIds.contains("e1"))
    }
}
