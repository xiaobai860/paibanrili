// app/src/main/java/com/schedulecalendar/app/ui/salary/SalaryViewModel.kt
package com.schedulecalendar.app.ui.salary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.schedulecalendar.app.data.prefs.AppPreferences
import com.schedulecalendar.app.data.repository.ExtraItemRepository
import com.schedulecalendar.app.data.repository.ScheduleRepository
import com.schedulecalendar.app.data.repository.ShiftBreakRepository
import com.schedulecalendar.app.data.repository.ShiftRepository
import com.schedulecalendar.app.domain.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

sealed class SalaryUiEvent {
    data class ShowError(val message: String) : SalaryUiEvent()
}

/** 薪资趋势数据点 */
data class MonthlySalaryTrend(
    val label: String,
    val value: Double,
    val year: Int,
    val month: Int
)

data class SalaryUiState(
    val year: Int                              = LocalDate.now().year,
    val month: Int                             = LocalDate.now().monthValue,
    /** 实际已到账薪资（历史月=全月，当前月=≤今天，未来月=0） */
    val actual: SalarySummary                  = SalarySummary(),
    /** 预计薪资（历史月=null，当前月=>今天，未来月=全月） */
    val future: SalarySummary?                 = null,
    /** 全月排班估算（用于顶部卡片显示） */
    val fullEstimate: SalarySummary            = SalarySummary(),
    /** 每日明细 */
    val details: List<DayScheduleDetail>       = emptyList(),
    /** 近8个月薪资趋势 */
    val trend: List<MonthlySalaryTrend>        = emptyList(),
    val loading: Boolean                       = true
)

@HiltViewModel
class SalaryViewModel @Inject constructor(
    private val shiftRepo: ShiftRepository,
    private val scheduleRepo: ScheduleRepository,
    private val breakRepo: ShiftBreakRepository,
    private val extraItemRepo: ExtraItemRepository,
    private val prefs: AppPreferences
) : ViewModel() {

    private val _state   = MutableStateFlow(SalaryUiState())
    val state            = _state.asStateFlow()

    private val _uiEvent = Channel<SalaryUiEvent>(Channel.BUFFERED)
    val uiEvent          = _uiEvent.receiveAsFlow()

    private var loadJob: Job? = null

    init {
        // 排班数据变更时自动刷新
        viewModelScope.launch {
            scheduleRepo.refreshSignal.collect { reload() }
        }
    }

    fun reload() { loadMonth(_state.value.year, _state.value.month) }

    private fun loadMonth(year: Int, month: Int) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            // 仅首次加载（details为空）时显示loading spinner，后续刷新保持现有内容
            if (_state.value.details.isEmpty()) {
                _state.update { it.copy(loading = true) }
            }
            runCatching {
                val shifts       = shiftRepo.getAllWithBuiltin()
                val breaks       = breakRepo.getAll()
                val extraItems   = extraItemRepo.getAll()
                val salaryConf   = prefs.salaryConfigFlow.first()
                val attendConf   = prefs.attendConfigFlow.first()
                val records      = scheduleRepo.getByMonth("%04d-%02d".format(year, month))
                val schedules    = records.associateBy { it.date }

                val today    = LocalDate.now()
                val todayStr = "%04d-%02d-%02d".format(today.year, today.monthValue, today.dayOfMonth)
                val isCurrentMonth = year == today.year && month == today.monthValue
                val isFutureMonth  = year > today.year ||
                    (year == today.year && month > today.monthValue)

                // 实际薪资
                val actual = if (isFutureMonth) SalarySummary(
                    baseSalary = salaryConf.baseSalary, basePerformance = salaryConf.basePerformance)
                else CalcUtils.calcMonthSalary(year, month, schedules, shifts, breaks, extraItems, salaryConf, attendConf) {
                    if (isCurrentMonth) it <= todayStr else true
                }
                // 预计薪资（不含底薪/绩效，仅工时部分）
                val future = when {
                    !isCurrentMonth && !isFutureMonth -> null
                    isFutureMonth -> CalcUtils.calcMonthSalary(year, month, schedules, shifts, breaks, extraItems, salaryConf, attendConf)
                    else -> CalcUtils.calcMonthSalary(year, month, schedules, shifts, breaks, extraItems, salaryConf, attendConf) { it > todayStr }
                }
                // 全月估算
                val fullEstimate = CalcUtils.calcMonthSalary(year, month, schedules, shifts, breaks, extraItems, salaryConf, attendConf)

                // 每日明细
                val details = CalcUtils.getMonthScheduleDetails(year, month, schedules, shifts, breaks, extraItems, salaryConf, attendConf)

                // 趋势
                val trend = buildTrend(year, month, shifts, breaks, extraItems, salaryConf, attendConf)

                _state.update { it.copy(
                    year         = year,
                    month        = month,
                    actual       = actual,
                    future       = future,
                    fullEstimate = fullEstimate,
                    details      = details,
                    trend        = trend,
                    loading      = false
                )}
            }.onFailure {
                _state.update { it.copy(loading = false) }
                _uiEvent.send(SalaryUiEvent.ShowError("薪资计算失败：${it.message}"))
            }
        }
    }

    private suspend fun buildTrend(
        year: Int, month: Int,
        shifts: List<Shift>, breaks: List<ShiftBreak>,
        extraItems: List<ExtraItem>, salaryConf: SalaryConfig, attendConf: AttendConfig
    ): List<MonthlySalaryTrend> {
        val result = mutableListOf<MonthlySalaryTrend>()
        for (i in 7 downTo 0) {
            var m = month - i; var y = year
            if (m <= 0) { m += 12; y -= 1 }
            val recs   = scheduleRepo.getByMonth("%04d-%02d".format(y, m))
            val schMap = recs.associateBy { it.date }
            val value  = if (schMap.isEmpty()) 0.0
            else CalcUtils.calcMonthSalary(y, m, schMap, shifts, breaks, extraItems, salaryConf, attendConf).totalSalary
            result.add(MonthlySalaryTrend("${m}月", value, y, m))
        }
        return result
    }

    fun goToPrevMonth() { val s = _state.value; if (s.month == 1) loadMonth(s.year - 1, 12) else loadMonth(s.year, s.month - 1) }
    fun goToNextMonth() { val s = _state.value; if (s.month == 12) loadMonth(s.year + 1, 1)  else loadMonth(s.year, s.month + 1) }
    fun goToMonth(year: Int, month: Int) { loadMonth(year, month) }
}
