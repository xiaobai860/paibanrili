// app/src/main/java/com/schedulecalendar/app/data/repository/CalcInputResolver.kt
package com.schedulecalendar.app.data.repository

import com.schedulecalendar.app.data.prefs.AppPreferences
import com.schedulecalendar.app.domain.model.CalcInputs
import com.schedulecalendar.app.domain.model.CalcSegment
import com.schedulecalendar.app.domain.model.buildSegments
import com.schedulecalendar.app.domain.model.toInputs
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 计算输入解析器：决定「某一天用哪套薪资/考勤/休息时段参数」。
 *
 * 规则：
 * 1. 优先使用**当天生效的配置档案**（[CalcProfileRepository]）；
 * 2. 没有档案（首次升级尚未初始化 / 查询失败）时**回退到当前 DataStore 现值**，
 *    保证改造前后行为完全一致（不会因为档案机制导致历史数值突变）。
 */
@Singleton
class CalcInputResolver @Inject constructor(
    private val profileRepo: CalcProfileRepository,
    private val prefs: AppPreferences,
    private val breakRepo: ShiftBreakRepository
) {
    /** 某日生效的计算输入 */
    suspend fun inputsFor(date: String): CalcInputs {
        val profile = runCatching { profileRepo.getEffectiveOn(date) }.getOrNull()
        return profile?.toInputs() ?: fallbackInputs()
    }

    /** 当前现值（无档案时的兜底口径） */
    suspend fun fallbackInputs(): CalcInputs = CalcInputs(
        salary = prefs.salaryConfigFlow.first(),
        attend = prefs.attendConfigFlow.first(),
        breaks = breakRepo.getAll()
    )

    /**
     * 把区间按档案切成若干段（每段带自己的口径）。
     * 区间内无档案时返回整段（用现值），行为与改造前一致。
     */
    suspend fun segmentsInRange(from: String, to: String): List<CalcSegment> {
        val profiles = runCatching { profileRepo.getInRange(from, to) }.getOrNull().orEmpty()
        return buildSegments(from, to, profiles, fallbackInputs())
    }
}

