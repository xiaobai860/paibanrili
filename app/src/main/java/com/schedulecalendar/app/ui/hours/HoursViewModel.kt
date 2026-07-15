// app/src/main/java/com/schedulecalendar/app/ui/hours/HoursViewModel.kt
package com.schedulecalendar.app.ui.hours

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.schedulecalendar.app.data.prefs.AppPreferences
import com.schedulecalendar.app.data.repository.ExtraItemRepository
import com.schedulecalendar.app.data.repository.ScheduleRepository
import com.schedulecalendar.app.data.repository.ShiftBreakRepository
import com.schedulecalendar.app.data.repository.ShiftRepository
import com.schedulecalendar.app.data.repository.ShiftStatusRepository
import com.schedulecalendar.app.domain.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** 工时页一次性 UI 事件 */
sealed class HoursUiEvent {
    data class NavigateToDetail(val year: Int, val month: Int, val type: String) : HoursUiEvent()
    data class ShowError(val message: String)                                     : HoursUiEvent()
}

/** 月工时趋势数据点 */
data class MonthlyHoursTrend(
    val label: String,
    val normal: Double,
    val overtime: Double,
    val total: Double
)

data class HoursUiState(
    val year: Int                                  = LocalDate.now().year,
    val month: Int                                 = LocalDate.now().monthValue,
    /** 实际工时（历史月=全月，当前月=≤今天，未来月=0） */
    val actual: HoursSummary                       = HoursSummary(),
    /** 预计工时（历史月=null，当前月=>今天，未来月=全月） */
    val future: HoursSummary?                      = null,
    /** 当月每日明细（仅有排班记录的天） */
    val details: List<DayScheduleDetail>           = emptyList(),
    /** 近8个月趋势 */
    val trend: List<MonthlyHoursTrend>             = emptyList(),
    /** 考勤配置（用于迟到阈值提示） */
    val attendConfig: AttendConfig                 = AttendConfig(),
    val loading: Boolean                           = true
)

@HiltViewModel
class HoursViewModel @Inject constructor(
    private val shiftRepo: ShiftRepository,
    private val scheduleRepo: ScheduleRepository,
    private val breakRepo: ShiftBreakRepository,
    private val statusRepo: ShiftStatusRepository,
    private val extraRepo: ExtraItemRepository,
    private val prefs: AppPreferences
) : ViewModel() {

    private val _state   = MutableStateFlow(HoursUiState())
    val state            = _state.asStateFlow()

    private val _uiEvent = Channel<HoursUiEvent>(Channel.BUFFERED)
    val uiEvent          = _uiEvent.receiveAsFlow()

    private var loadJob: Job? = null

    init {
        // 排班数据变更时自动刷新
        viewModelScope.launch {
            scheduleRepo.refreshSignal.collect { reload() }
        }
    }

    fun reload() { loadMonth(_state.value.year, _state.value.month) }

    fun navigateToDetail(type: String) {
        val s = _state.value
        viewModelScope.launch { _uiEvent.send(HoursUiEvent.NavigateToDetail(s.year, s.month, type)) }
    }

    private fun loadMonth(year: Int, month: Int) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            // 仅首次加载（actual为空）时显示loading spinner，后续刷新保持现有内容
            if (_state.value.details.isEmpty()) {
                _state.update { it.copy(loading = true) }
            }
            runCatching {
                val shifts       = shiftRepo.getAllWithBuiltin()
                val breaks       = breakRepo.getAll()
                val statuses     = statusRepo.getAllWithBuiltin()
                val extraItems   = extraRepo.getAll()
                val salaryConf   = prefs.salaryConfigFlow.first()
                val attendConf   = prefs.attendConfigFlow.first()
                val records      = scheduleRepo.getByMonth("%04d-%02d".format(year, month))
                val schedules    = records.associateBy { it.date }

                val today    = LocalDate.now()
                val todayStr = "%04d-%02d-%02d".format(today.year, today.monthValue, today.dayOfMonth)
                val isCurrentMonth = year == today.year && month == today.monthValue
                val isFutureMonth  = year > today.year ||
                    (year == today.year && month > today.monthValue)

                // 实际：历史月=全月，当前月=≤今天，未来月=空
                val actual = if (isFutureMonth) HoursSummary()
                else CalcUtils.calcMonthHours(year, month, schedules, shifts, breaks, statuses, attendConf) {
                    if (isCurrentMonth) it <= todayStr else true
                }
                // 预计：历史月=null，当前月=>今天，未来月=全月
                val future = when {
                    !isCurrentMonth && !isFutureMonth -> null
                    isFutureMonth -> CalcUtils.calcMonthHours(year, month, schedules, shifts, breaks, statuses, attendConf)
                    else -> CalcUtils.calcMonthHours(year, month, schedules, shifts, breaks, statuses, attendConf) { it > todayStr }
                }

                // 每日明细
                val details = CalcUtils.getMonthScheduleDetails(year, month, schedules, shifts, breaks, extraItems, salaryConf, attendConf)

                // 近8个月趋势（不含未来月）
                val trend = buildTrend(year, month, shifts, breaks, statuses, attendConf)

                _state.update { it.copy(
                    year        = year,
                    month       = month,
                    actual      = actual,
                    future      = future,
                    details     = details,
                    trend       = trend,
                    attendConfig = attendConf,
                    loading     = false
                )}
            }.onFailure {
                _state.update { it.copy(loading = false) }
                _uiEvent.send(HoursUiEvent.ShowError("加载工时失败：${it.message}"))
            }
        }
    }

    private suspend fun buildTrend(
        year: Int, month: Int,
        shifts: List<Shift>, breaks: List<ShiftBreak>,
        statuses: List<ShiftStatus>, attendConf: AttendConfig
    ): List<MonthlyHoursTrend> {
        val result = mutableListOf<MonthlyHoursTrend>()
        for (i in 7 downTo 0) {
            var m = month - i; var y = year
            if (m <= 0) { m += 12; y -= 1 }
            val recs      = scheduleRepo.getByMonth("%04d-%02d".format(y, m))
            val schMap    = recs.associateBy { it.date }
            if (schMap.isEmpty()) { result.add(MonthlyHoursTrend("${m}月", 0.0, 0.0, 0.0)); continue }
            val summary   = CalcUtils.calcMonthHours(y, m, schMap, shifts, breaks, statuses, attendConf)
            val ot        = CalcUtils.roundD2(summary.overtimeHours + summary.weekendHours + summary.holidayHours)
            result.add(MonthlyHoursTrend("${m}月", summary.normalHours, ot, CalcUtils.roundD2(summary.totalHours)))
        }
        return result
    }

    fun goToPrevMonth() { val s = _state.value; if (s.month == 1) loadMonth(s.year - 1, 12) else loadMonth(s.year, s.month - 1) }
    fun goToNextMonth() { val s = _state.value; if (s.month == 12) loadMonth(s.year + 1, 1)  else loadMonth(s.year, s.month + 1) }
    fun goToMonth(year: Int, month: Int) { loadMonth(year, month) }
}
