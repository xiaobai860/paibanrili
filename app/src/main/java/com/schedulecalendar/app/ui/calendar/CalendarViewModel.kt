// app/src/main/java/com/schedulecalendar/app/ui/calendar/CalendarViewModel.kt
package com.schedulecalendar.app.ui.calendar

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.schedulecalendar.app.data.calendar.CalendarEventInfo
import com.schedulecalendar.app.data.calendar.CalendarEventRepository
import com.schedulecalendar.app.data.prefs.AppPreferences
import com.schedulecalendar.app.data.repository.ExtraItemRepository
import com.schedulecalendar.app.data.repository.ScheduleRepository
import com.schedulecalendar.app.data.repository.ShiftBreakRepository
import com.schedulecalendar.app.data.repository.ShiftRepository
import com.schedulecalendar.app.data.repository.ShiftStatusRepository
import com.schedulecalendar.app.domain.model.*
import com.schedulecalendar.app.widget.CalendarGlanceWidget
import com.schedulecalendar.app.widget.CalendarWidgetDay
import com.schedulecalendar.app.widget.CalendarWidgetInfo
import com.schedulecalendar.app.widget.ClockInWidgetData
import com.schedulecalendar.app.widget.ScheduleGlanceWidget
import com.schedulecalendar.app.widget.isBuiltInStatus
import java.time.LocalTime
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

// ── 待办中心数据 ───────────────────────────────────────────────────────────────

data class TodoItem(
    val date: String,
    val type: TodoType,
    val label: String,
    val shiftName: String = "",
    val shiftTime: String = "",
    val clockTime: String = "",
    val overtimeMinutes: Int = 0,
    val actualTime: String = "",
    /** 内置附加状态名称（如"请假"、"调休"），空=普通班次 */
    val statusLabel: String = ""
)

enum class TodoType {
    MISSED_CLOCK_IN, MISSED_CLOCK_OUT,
    FILLED_CLOCK_IN, FILLED_CLOCK_OUT,
    PENDING_EARLY_OT, PENDING_LATE_OT,
    CONFIRMED_EARLY_OT, CONFIRMED_LATE_OT,
    IGNORED_EARLY_OT, IGNORED_LATE_OT
}

// ── CalendarUiState ───────────────────────────────────────────────────────────

sealed class CalendarUiEvent {
    data class NavigateToDetail(val date: String) : CalendarUiEvent()
    data class ShowMessage(val msg: String)        : CalendarUiEvent()
    data class ShowError(val msg: String)          : CalendarUiEvent()
}

data class CalendarUiState(
    val year: Int                              = LocalDate.now().year,
    val month: Int                             = LocalDate.now().monthValue,
    /** 仅当前有效的班次（用于选择界面，如 BatchToolbar） */
    val shifts: List<Shift>                    = emptyList(),
    /** 完整班次列表（含已归档，用于日历网格历史数据展示查找） */
    val allShifts: List<Shift>                 = emptyList(),
    val schedules: Map<String, ScheduleRecord> = emptyMap(),
    val displayScheme: DisplayScheme           = DisplayScheme(
        id = NO_SCHEME_ID, name = "预设方案", isNoScheme = true, builtIn = true, isActive = true
    ),
    val scheduleRule: ScheduleRule?            = null,
    /** 当月每日工时详情（格内展示用） */
    val dayDetails: Map<String, DayScheduleDetail> = emptyMap(),
    /** 待办中心条目 */
    val todos: List<TodoItem>                  = emptyList(),
    val loading: Boolean                       = true,
    /** 批量操作模式 */
    val batchMode: Boolean                     = false,
    val batchSelected: Set<String>             = emptySet(),
    /** 当前选中的日期（用于详情展示） */
    val selectedDate: String?                  = null,
    /** 附加补贴/扣款项目列表 */
    val extraItems: List<ExtraItem>            = emptyList(),
    /** 仅当前有效的附加状态（用于选择界面） */
    val shiftStatuses: List<ShiftStatus>       = emptyList(),
    /** 完整附加状态列表（含已归档，用于日历网格历史数据展示查找） */
    val allShiftStatuses: List<ShiftStatus>    = emptyList(),
    /** 复制排班模式 */
    val copyMode: Boolean                      = false,
    /** 复制排班阶段：1=选择源日期，2=选择目标起始位置 */
    val copyPhase: Int                         = 1,
    /** 复制排班：源日期范围（连续） */
    val copySourceStart: String?               = null,
    val copySourceEnd: String?                 = null,
    val copySourceDates: Set<String>           = emptySet(),
    /** 复制排班：目标起始日期 */
    val copyTargetDate: String?                = null,
    /** 清除排班模式 */
    val deleteMode: Boolean                    = false,
    /** 选中日期的纪念日与日程事件 */
    val selectedDateEvents: List<CalendarEventInfo> = emptyList(),
    /** 有日历事件（日程/纪念日）的日期集合（用于 DayCell 显示事件指示点） */
    val datesWithEvents: Set<String> = emptySet()
)

/** 活跃班次判定结果 */
data class ActiveShiftResult(
    val date: LocalDate,       // 打卡写入的目标日期
    val shift: Shift,
    val showClockIn: Boolean,
    val showClockOut: Boolean
)

@HiltViewModel
class CalendarViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shiftRepo: ShiftRepository,
    private val scheduleRepo: ScheduleRepository,
    private val breakRepo: ShiftBreakRepository,
    private val extraRepo: ExtraItemRepository,
    private val statusRepo: ShiftStatusRepository,
    private val backupManager: com.schedulecalendar.app.ui.settings.BackupManager,
    private val prefs: AppPreferences,
    private val calendarEventRepo: CalendarEventRepository
) : ViewModel() {

    private val _state   = MutableStateFlow(CalendarUiState())
    val state            = _state.asStateFlow()

    private val _uiEvent = Channel<CalendarUiEvent>(Channel.BUFFERED)
    val uiEvent          = _uiEvent.receiveAsFlow()

    /** 当前月份数据收集 Job，切月时取消旧 Job 再启新 Job，防止 collector 累积泄漏 */
    private var collectJob: Job? = null

    /** Widget 同步去抖 Job：数据变化后延迟合并写盘，避免每次 collect 都立即同步小组件 */
    private var widgetSyncJob: Job? = null

    init {
        loadCurrentMonth()
        // 延迟执行非关键初始化，避免阻塞首帧渲染
        viewModelScope.launch {
            kotlinx.coroutines.delay(500)
            backupManager.autoBackupAppData()
        }
        viewModelScope.launch {
            kotlinx.coroutines.delay(1000)
            calendarEventRepo.getOrCreateLocalCalendarId()
        }
    }

    private fun loadCurrentMonth() {
        collectJob?.cancel()          // 取消上一次月份的无限收集协程
        collectJob = viewModelScope.launch {
            val s = _state.value

            // 计算日历网格覆盖的完整日期范围（含跨月填充日期）
            val ym = YearMonth.of(s.year, s.month)
            val daysInMonth = ym.lengthOfMonth()
            val firstDow = (LocalDate.of(s.year, s.month, 1).dayOfWeek.value + 6) % 7  // 周一=0..周日=6
            val totalCells = firstDow + daysInMonth
            val totalRows = (totalCells + 6) / 7
            val remainingInLastRow = totalRows * 7 - totalCells
            // 上月（滑动联动用，需加载完整月份数据）
            val prevYM = if (s.month == 1) YearMonth.of(s.year - 1, 12) else YearMonth.of(s.year, s.month - 1)
            // 下月（滑动联动用，需加载完整月份数据）
            val nextYM = if (s.month == 12) YearMonth.of(s.year + 1, 1) else YearMonth.of(s.year, s.month + 1)
            // 数据范围：上月1日 ~ 下月末日（覆盖完整相邻月份，支持滑动时显示）
            val rangeFrom = "%04d-%02d-01".format(prevYM.year, prevYM.monthValue)
            val rangeTo   = "%04d-%02d-%02d".format(nextYM.year, nextYM.monthValue, nextYM.lengthOfMonth())

            combine(
                shiftRepo.observeAll().distinctUntilChanged(),
                scheduleRepo.observeByRange(rangeFrom, rangeTo).distinctUntilChanged(),
                prefs.displaySchemesFlow.distinctUntilChanged(),
                prefs.scheduleRuleFlow.distinctUntilChanged()
            ) { shifts, records, schemes, rule ->
                Triple(shifts, records, Pair(schemes, rule))
            }.collect { (rawShifts, records, pair) ->
                val (schemes, rule) = pair
                // 将数据加载与重计算整体移到后台线程，避免首次/切换 Tab 时阻塞主线程导致卡顿。
                // 所有输入（Room 查询结果、SharedPreferences）均为内存数据，线程安全；_state.update 为原子操作可跨线程。
                withContext(Dispatchers.Default) {
                    val schedules  = records.associateBy { it.date }
                    val scheme     = schemes.firstOrNull { it.isActive } ?: DisplayScheme(
                        id = NO_SCHEME_ID, name = "预设方案", isNoScheme = true, builtIn = true, isActive = true
                    )
                    val breaks     = breakRepo.getAll()
                    val extraItems = extraRepo.getAll()
                    val rawStatuses = statusRepo.getAllWithBuiltin()
                    val salaryConf = prefs.salaryConfigFlow.first()
                    val attendConf = prefs.attendConfigFlow.first()
                    // 按用户自定义排序（与 ShiftsScreen 保持一致），并过滤已归档项
                    val shiftOrder = prefs.getShiftOrder()
                    val statusOrder = prefs.getStatusOrder()
                    val activeShifts = rawShifts.filter { it.archivedAt == null }
                    val activeStatuses = rawStatuses.filter { it.archivedAt == null }
                    val shifts = if (shiftOrder.isEmpty()) activeShifts else {
                        val byId = activeShifts.associateBy { it.id }
                        val ordered = shiftOrder.mapNotNull { byId[it] }
                        val remaining = activeShifts.filter { it.id !in shiftOrder.toSet() }
                        ordered + remaining
                    }
                    val shiftStatuses = if (statusOrder.isEmpty()) activeStatuses else {
                        val byId = activeStatuses.associateBy { it.id }
                        val ordered = statusOrder.mapNotNull { byId[it] }
                        val remaining = activeStatuses.filter { it.id !in statusOrder.toSet() }
                        ordered + remaining
                    }
                    // 完整列表用于历史数据展示查找（含已归档项）
                    val allShifts = rawShifts
                    val allShiftStatuses = rawStatuses
                    // 计算当月详情（使用完整列表，确保历史归档班次也能正确计算）
                    val curDetails = CalcUtils.getMonthScheduleDetails(
                        s.year, s.month, schedules, allShifts, breaks, extraItems, salaryConf, attendConf
                    ).associateBy { it.date }
                    // 计算完整相邻月份详情（支持滑动联动时显示相邻月份网格）
                    val prevDetails = CalcUtils.getMonthScheduleDetails(
                        prevYM.year, prevYM.monthValue, schedules, allShifts, breaks, extraItems, salaryConf, attendConf
                    ).associateBy { it.date }
                    val nextDetails = CalcUtils.getMonthScheduleDetails(
                        nextYM.year, nextYM.monthValue, schedules, allShifts, breaks, extraItems, salaryConf, attendConf
                    ).associateBy { it.date }
                    val allDetails = curDetails + prevDetails + nextDetails
                    val todos      = buildTodos(s.year, s.month, schedules, allShifts, attendConf)
                    _state.update { it.copy(
                        shifts         = shifts,
                        allShifts      = allShifts,
                        schedules      = schedules,
                        displayScheme  = scheme,
                        scheduleRule   = rule,
                        dayDetails     = allDetails,
                        todos          = todos,
                        extraItems     = extraItems,
                        shiftStatuses  = shiftStatuses,
                        allShiftStatuses = allShiftStatuses,
                        loading        = false
                    )}
                    // Widget 同步（去抖合并 + 后台线程，避免每次数据变化立即写盘、避免占主线程）
                    widgetSyncJob?.cancel()
                    widgetSyncJob = viewModelScope.launch(Dispatchers.Default) {
                        kotlinx.coroutines.delay(150)
                        syncWidget(allShifts, schedules)
                        syncCalendarWidget(
                            year = s.year, month = s.month,
                            allShifts = allShifts, schedules = schedules,
                            allShiftStatuses = allShiftStatuses,
                            selectedDate = _state.value.selectedDate
                        )
                    }
                }
                // 加载选中日期的纪念日与日程（首次加载或切月后）
                val selDate = _state.value.selectedDate ?: "%04d-%02d-%02d".format(s.year, s.month, LocalDate.now().dayOfMonth)
                loadSelectedDateEvents(selDate)
                // 加载当月有日历事件（日程/纪念日）的日期集合
                loadDatesWithEvents(rangeFrom, rangeTo)
            }
        }
    }

    /** 计算本月（历史日期）的待办事项 */
    private fun buildTodos(
        year: Int, month: Int,
        schedules: Map<String, ScheduleRecord>,
        shifts: List<Shift>,
        attendConfig: AttendConfig
    ): List<TodoItem> {
        val today      = LocalDate.now()
        val todayY     = today.year
        val todayM     = today.monthValue
        val todayD     = today.dayOfMonth
        val todos      = mutableListOf<TodoItem>()
        val daysInMonth = YearMonth.of(year, month).lengthOfMonth()

        for (d in 1..daysInMonth) {
            // 只检查历史日期（不含今天及未来）
            if (year > todayY) break
            if (year == todayY && month > todayM) break  // 当前年份但月份在未来，整月跳过
            if (year == todayY && month == todayM && d >= todayD) break

            val dateStr = "%04d-%02d-%02d".format(year, month, d)
            val record  = schedules[dateStr]
            val shift   = record?.shiftId?.let { id -> shifts.find { it.id == id } }
            if (shift == null || shift.builtInType == "rest" || shift.builtInType == "swap") continue

            val sn = shift.name
            val st = if (shift.startTime.isNotEmpty() && shift.endTime.isNotEmpty()) "${shift.startTime}-${shift.endTime}" else ""
            val stStart = shift.startTime
            val stEnd = shift.endTime
            // 检测是否有内置附加状态（请假/调休）
            val applied = record.appliedStatus
            val isBuiltIn = applied != null && (applied.statusId == BUILTIN_STATUS_LEAVE || applied.statusId == BUILTIN_STATUS_SWAP)
            val stLabel = if (isBuiltIn) {
                if (applied!!.statusId == BUILTIN_STATUS_LEAVE) "请假" else "调休"
            } else ""
            // 内置状态：检查 appliedStatus 时间；普通：检查 actualStartTime/actualEndTime（空字符串也视为未填写）
            val startFilled = if (isBuiltIn) !applied?.startTime.isNullOrEmpty() else !record.actualStartTime.isNullOrEmpty()
            val endFilled   = if (isBuiltIn) !applied?.endTime.isNullOrEmpty() else !record.actualEndTime.isNullOrEmpty()
            val startVal    = if (isBuiltIn) applied?.startTime else record.actualStartTime
            val endVal      = if (isBuiltIn) applied?.endTime else record.actualEndTime
            // 漏打卡检测：有班次但缺少打卡记录，上班/下班分别独立判断
            if (!startFilled) {
                todos.add(TodoItem(dateStr, TodoType.MISSED_CLOCK_IN, "上班漏打卡", shiftName = sn, shiftTime = stStart, statusLabel = stLabel))
            } else {
                todos.add(TodoItem(dateStr, TodoType.FILLED_CLOCK_IN, "上班已补录", shiftName = sn, shiftTime = stStart, clockTime = startVal ?: "", statusLabel = stLabel))
            }
            if (!endFilled) {
                todos.add(TodoItem(dateStr, TodoType.MISSED_CLOCK_OUT, "下班漏打卡", shiftName = sn, shiftTime = stEnd, statusLabel = stLabel))
            } else {
                todos.add(TodoItem(dateStr, TodoType.FILLED_CLOCK_OUT, "下班已补录", shiftName = sn, shiftTime = stEnd, clockTime = endVal ?: "", statusLabel = stLabel))
            }

            // 加班待确认：早到/晚退分别独立判断（与小程序逻辑一致）
            // 早到加班待确认：有实际上班时间 & 比班次早 & 未忽略早到 & 未确认早到加班
            if (!record.actualStartTime.isNullOrEmpty() && !record.ignoreEarlyArrival && !record.confirmEarlyOT) {
                val earlyMin = CalcUtils.timeToMin(shift.startTime) - CalcUtils.timeToMin(record.actualStartTime)
                val grain = attendConfig.overtimeGranMin
                if (earlyMin >= grain) {
                    todos.add(TodoItem(dateStr, TodoType.PENDING_EARLY_OT, "早到加班待确认", shiftName = sn, shiftTime = st, overtimeMinutes = earlyMin, actualTime = record.actualStartTime))
                }
            } else if (record.confirmEarlyOT) {
                val earlyMin = CalcUtils.timeToMin(shift.startTime) - CalcUtils.timeToMin(record.actualStartTime ?: shift.startTime)
                todos.add(TodoItem(dateStr, TodoType.CONFIRMED_EARLY_OT, "已确认早到加班", shiftName = sn, shiftTime = st, overtimeMinutes = maxOf(0, earlyMin), actualTime = record.actualStartTime ?: ""))
            } else if (record.ignoreEarlyArrival) {
                val earlyMin = CalcUtils.timeToMin(shift.startTime) - CalcUtils.timeToMin(record.actualStartTime ?: shift.startTime)
                todos.add(TodoItem(dateStr, TodoType.IGNORED_EARLY_OT, "忽略早到加班", shiftName = sn, shiftTime = st, overtimeMinutes = maxOf(0, earlyMin), actualTime = record.actualStartTime ?: ""))
            }
            // 晚退加班待确认：有实际下班时间 & 比班次晚 & 未忽略晚退 & 未确认晚退加班
            if (!record.actualEndTime.isNullOrEmpty() && !record.ignoreLateLeave && !record.confirmLateOT) {
                val sS = CalcUtils.timeToMin(shift.startTime)
                val (_, normSE) = CalcUtils.normRange(sS, CalcUtils.timeToMin(shift.endTime))
                val (_, normAE) = CalcUtils.normRange(sS, CalcUtils.timeToMin(record.actualEndTime))
                val lateMin = normAE - normSE
                val grain = attendConfig.overtimeGranMin
                if (lateMin >= grain) {
                    todos.add(TodoItem(dateStr, TodoType.PENDING_LATE_OT, "晚退加班待确认", shiftName = sn, shiftTime = st, overtimeMinutes = lateMin, actualTime = record.actualEndTime))
                }
            } else if (record.confirmLateOT) {
                val sS = CalcUtils.timeToMin(shift.startTime)
                val (_, normSE) = CalcUtils.normRange(sS, CalcUtils.timeToMin(shift.endTime))
                val (_, normAE) = CalcUtils.normRange(sS, CalcUtils.timeToMin(record.actualEndTime ?: shift.endTime))
                val lateMin = normAE - normSE
                todos.add(TodoItem(dateStr, TodoType.CONFIRMED_LATE_OT, "已确认晚退加班", shiftName = sn, shiftTime = st, overtimeMinutes = maxOf(0, lateMin), actualTime = record.actualEndTime ?: ""))
            } else if (record.ignoreLateLeave) {
                val sS = CalcUtils.timeToMin(shift.startTime)
                val (_, normSE) = CalcUtils.normRange(sS, CalcUtils.timeToMin(shift.endTime))
                val (_, normAE) = CalcUtils.normRange(sS, CalcUtils.timeToMin(record.actualEndTime ?: shift.endTime))
                val lateMin = normAE - normSE
                todos.add(TodoItem(dateStr, TodoType.IGNORED_LATE_OT, "忽略晚退加班", shiftName = sn, shiftTime = st, overtimeMinutes = maxOf(0, lateMin), actualTime = record.actualEndTime ?: ""))
            }
        }

        return todos
    }

    // ── 月份切换 ──────────────────────────────────────────────────────

    fun goToPrevMonth() {
        val s = _state.value
        val (y, m) = if (s.month == 1) s.year - 1 to 12 else s.year to s.month - 1
        // 不清除旧数据，避免切换闪烁；新数据到达后自动替换
        _state.update { it.copy(year = y, month = m) }
        loadCurrentMonth()
    }

    fun goToNextMonth() {
        val s = _state.value
        val (y, m) = if (s.month == 12) s.year + 1 to 1 else s.year to s.month + 1
        _state.update { it.copy(year = y, month = m) }
        loadCurrentMonth()
    }


    /** Lightweight month update for pager swipe (no data reload) */
    fun updateDisplayMonth(year: Int, month: Int) {
        _state.update { it.copy(year = year, month = month) }
        loadCurrentMonth()
    }
    fun goToToday() {
        val today = LocalDate.now()
        val todayStr = "%04d-%02d-%02d".format(today.year, today.monthValue, today.dayOfMonth)
        val s = _state.value
        val sameMonth = s.year == today.year && s.month == today.monthValue
        if (sameMonth) {
            // 同月：仅更新选中日期，不触发loading，避免闪烁
            _state.update { it.copy(selectedDate = todayStr) }
            loadSelectedDateEvents(todayStr)
        } else {
            // 跨月：不清除旧数据，避免闪烁
            _state.update { it.copy(year = today.year, month = today.monthValue, selectedDate = todayStr) }
            loadSelectedDateEvents(todayStr)
            loadCurrentMonth()
        }
    }

    fun goToMonth(year: Int, month: Int) {
        _state.update { it.copy(year = year, month = month, loading = true, todos = emptyList(), dayDetails = emptyMap()) }
        loadCurrentMonth()
    }

    /** 跳转到指定日期并自动点击该日期 */
    fun goToDay(year: Int, month: Int, day: Int) {
        val dateStr = "%04d-%02d-%02d".format(year, month, day)
        // 先跳转到目标月份
        if (year != _state.value.year || month != _state.value.month) {
            _state.update { it.copy(year = year, month = month, loading = true, todos = emptyList(), dayDetails = emptyMap()) }
            loadCurrentMonth()
        }
        // 跳转到详情页
        viewModelScope.launch { _uiEvent.send(CalendarUiEvent.NavigateToDetail(dateStr)) }
    }

    // ── 日期操作 ──────────────────────────────────────────────────────

    fun onDayClick(date: String) {
        val st = _state.value
        if (st.batchMode || st.deleteMode) {
            val sel = st.batchSelected.toMutableSet()
            if (date in sel) sel.remove(date) else sel.add(date)
            _state.update { it.copy(batchSelected = sel) }
        } else {
            // 仅更新选中日期，不触发导航
            _state.update { it.copy(selectedDate = date) }
            loadSelectedDateEvents(date)
        }
    }

    /** 加载选中日期的纪念日与日程事件（仅显示已启用账户的事件） */
    private fun loadSelectedDateEvents(date: String) {
        viewModelScope.launch {
            val events = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val disabledIds = prefs.getDisabledAccountIds()
                    calendarEventRepo.getEventsForDate(date)
                        .filter { it.calendarId !in disabledIds }
                }
            } catch (_: Exception) { emptyList() }
            _state.update { it.copy(selectedDateEvents = events) }
        }
    }

    /**
     * 加载指定范围内有日历事件（日程/纪念日）的日期集合
     * 用于 DayCell 显示事件指示点
     */
    private fun loadDatesWithEvents(rangeFrom: String, rangeTo: String) {
        viewModelScope.launch {
            val datesWithEvents = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val disabledIds = prefs.getDisabledAccountIds()
                    val fromDate = try { java.time.LocalDate.parse(rangeFrom) } catch (_: Exception) { null }
                    val toDate = try { java.time.LocalDate.parse(rangeTo) } catch (_: Exception) { null }
                    if (fromDate == null || toDate == null) {
                        emptySet<String>()
                    } else {
                        // 一次性按月区间在 ContentProvider 端过滤，避免 getAllEvents() 全量拉取
                        val rangeStartMs = fromDate.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                        val rangeEndMs = toDate.plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                        val events = calendarEventRepo.getAllEventsInRange(rangeStartMs, rangeEndMs)
                            .filter { it.calendarId !in disabledIds }
                        val dates = mutableSetOf<String>()
                        for (event in events) {
                            val eventDate = try {
                                java.time.Instant.ofEpochMilli(event.dtStart)
                                    .atZone(java.time.ZoneId.systemDefault())
                                    .toLocalDate()
                            } catch (_: Exception) { continue }
                            if (!eventDate.isBefore(fromDate) && !eventDate.isAfter(toDate)) {
                                dates.add(eventDate.toString())
                            }
                            // 处理年度重复纪念日：检查今年是否落在范围内
                            if (event.rrule?.contains("FREQ=YEARLY") == true) {
                                for (year in fromDate.year..toDate.year) {
                                    val thisYearDate = try {
                                        java.time.LocalDate.of(year, eventDate.monthValue, eventDate.dayOfMonth)
                                    } catch (_: Exception) { continue }
                                    if (!thisYearDate.isBefore(fromDate) && !thisYearDate.isAfter(toDate)) {
                                        dates.add(thisYearDate.toString())
                                    }
                                }
                            }
                        }
                        dates
                    }
                }
            } catch (_: Exception) { emptySet() }
            _state.update { it.copy(datesWithEvents = datesWithEvents) }
        }
    }

    fun clockIn(date: String, time: String) = viewModelScope.launch {
        val rec = (scheduleRepo.getByDate(date) ?: ScheduleRecord(date))
        scheduleRepo.save(rec.copy(actualStartTime = time))
        _uiEvent.send(CalendarUiEvent.ShowMessage("已记录上班打卡 $time"))
        syncClockInWidget()
    }

    fun clockOut(date: String, time: String) = viewModelScope.launch {
        // 跨午夜修正：打卡时间早于班次结束时间，视为前一天班次的下班打卡
        val rec = scheduleRepo.getByDate(date)
        val shift = rec?.shiftId?.let { id -> shiftRepo.getById(id) }
        val actualTargetDate = if (shift != null && shift.endTime.isNotEmpty()) {
            val timeMin = CalcUtils.timeToMin(time)
            val endMin = CalcUtils.timeToMin(shift.endTime)
            if (timeMin < endMin) {
                // 打卡时间早于班次结束 → 跨午夜，归属前一天
                val prevDate = try {
                    java.time.LocalDate.parse(date).minusDays(1).toString()
                } catch (_: Exception) { date }
                prevDate
            } else date
        } else date

        val targetRec = scheduleRepo.getByDate(actualTargetDate) ?: ScheduleRecord(actualTargetDate)
        scheduleRepo.save(targetRec.copy(actualEndTime = time))
        _uiEvent.send(CalendarUiEvent.ShowMessage("已记录下班打卡 $time"))
        syncClockInWidget()
    }

    /** 补填漏打卡时间：内置附加状态写入 appliedStatus（并 clamp 到班次范围），普通写入 actualTime */
    fun fillMissedClock(date: String, startTime: String?, endTime: String?) = viewModelScope.launch {
        val rec = scheduleRepo.getByDate(date) ?: ScheduleRecord(date)
        val applied = rec.appliedStatus
        val isBuiltIn = applied != null && (applied.statusId == BUILTIN_STATUS_LEAVE || applied.statusId == BUILTIN_STATUS_SWAP)
        val updated = if (isBuiltIn) {
            // 获取班次时间段作为约束边界
            val shift = rec.shiftId?.let { id -> shiftRepo.getById(id) }
            val shiftStart = shift?.startTime?.takeIf { it.isNotEmpty() }
            val shiftEnd   = shift?.endTime?.takeIf { it.isNotEmpty() }
            fun clampTime(t: String, boundary: String?, isStart: Boolean): String {
                if (boundary == null) return t
                val tMin = CalcUtils.timeToMin(t)
                val bMin = CalcUtils.timeToMin(boundary)
                return if (isStart && tMin < bMin) boundary
                       else if (!isStart && tMin > bMin) boundary
                       else t
            }
            val clampedStart = startTime?.ifBlank { null }?.let { clampTime(it, shiftStart, true) }
            val clampedEnd   = endTime?.ifBlank { null }?.let { clampTime(it, shiftEnd, false) }
            val newStatus = applied.copy(
                startTime = clampedStart ?: applied.startTime,
                endTime   = clampedEnd ?: applied.endTime
            )
            rec.copy(appliedStatus = newStatus)
        } else {
            // 跨午夜修正：下班打卡时间早于班次结束时间，视为前一天班次
            var finalStartTime = startTime?.ifBlank { null } ?: rec.actualStartTime
            var finalEndTime   = endTime?.ifBlank { null } ?: rec.actualEndTime
            var targetDate     = date

            if (endTime != null && endTime.isNotBlank()) {
                val shift = rec.shiftId?.let { id -> shiftRepo.getById(id) }
                if (shift != null && shift.endTime.isNotEmpty()) {
                    val timeMin = CalcUtils.timeToMin(endTime)
                    val endMin  = CalcUtils.timeToMin(shift.endTime)
                    if (timeMin < endMin) {
                        // 打卡时间早于班次结束 → 跨午夜，归属前一天
                        val prevDate = try {
                            java.time.LocalDate.parse(date).minusDays(1).toString()
                        } catch (_: Exception) { date }
                        targetDate = prevDate
                        finalEndTime = endTime
                    }
                }
            }

            val targetRec = scheduleRepo.getByDate(targetDate) ?: ScheduleRecord(targetDate)
            targetRec.copy(
                actualStartTime = if (targetDate == date) finalStartTime else targetRec.actualStartTime,
                actualEndTime   = if (targetDate == date) finalEndTime else finalEndTime
            )
        }
        scheduleRepo.save(updated)
        _uiEvent.send(CalendarUiEvent.ShowMessage("已补填 $date 打卡记录"))
    }

    /** 确认早到加班 */
    fun confirmEarlyOvertime(date: String) = viewModelScope.launch {
        val rec = scheduleRepo.getByDate(date) ?: return@launch
        scheduleRepo.save(rec.copy(confirmEarlyOT = true))
        _uiEvent.send(CalendarUiEvent.ShowMessage("已确认 $date 早到加班"))
    }

    /** 确认晚退加班 */
    fun confirmLateOvertime(date: String) = viewModelScope.launch {
        val rec = scheduleRepo.getByDate(date) ?: return@launch
        scheduleRepo.save(rec.copy(confirmLateOT = true))
        _uiEvent.send(CalendarUiEvent.ShowMessage("已确认 $date 晚退加班"))
    }

    /** 忽略早到加班 */
    fun ignoreEarlyArrival(date: String) = viewModelScope.launch {
        val rec = scheduleRepo.getByDate(date) ?: return@launch
        scheduleRepo.save(rec.copy(ignoreEarlyArrival = true))
        _uiEvent.send(CalendarUiEvent.ShowMessage("已忽略 $date 早到加班"))
    }

    /** 忽略晚退加班 */
    fun ignoreLateLeave(date: String) = viewModelScope.launch {
        val rec = scheduleRepo.getByDate(date) ?: return@launch
        scheduleRepo.save(rec.copy(ignoreLateLeave = true))
        _uiEvent.send(CalendarUiEvent.ShowMessage("已忽略 $date 晚退加班"))
    }

    // ── 撤销操作 ────────────────────────────────────────────────────

    /** 撤销上班补录：内置状态清除 appliedStatus.startTime，普通清除 actualStartTime */
    fun unfillMissedClockIn(date: String) = viewModelScope.launch {
        val rec = scheduleRepo.getByDate(date) ?: return@launch
        val applied = rec.appliedStatus
        val isBuiltIn = applied != null && (applied.statusId == BUILTIN_STATUS_LEAVE || applied.statusId == BUILTIN_STATUS_SWAP)
        if (isBuiltIn) {
            scheduleRepo.save(rec.copy(appliedStatus = applied!!.copy(startTime = null)))
        } else {
            scheduleRepo.save(rec.copy(actualStartTime = null))
        }
        _uiEvent.send(CalendarUiEvent.ShowMessage("已撤销 $date 上班打卡"))
    }

    /** 撤销下班补录：内置状态清除 appliedStatus.endTime，普通清除 actualEndTime */
    fun unfillMissedClockOut(date: String) = viewModelScope.launch {
        val rec = scheduleRepo.getByDate(date) ?: return@launch
        val applied = rec.appliedStatus
        val isBuiltIn = applied != null && (applied.statusId == BUILTIN_STATUS_LEAVE || applied.statusId == BUILTIN_STATUS_SWAP)
        if (isBuiltIn) {
            scheduleRepo.save(rec.copy(appliedStatus = applied!!.copy(endTime = null)))
        } else {
            scheduleRepo.save(rec.copy(actualEndTime = null))
        }
        _uiEvent.send(CalendarUiEvent.ShowMessage("已撤销 $date 下班打卡"))
    }

    /** 撤销确认早到加班 */
    fun unconfirmEarlyOvertime(date: String) = viewModelScope.launch {
        val rec = scheduleRepo.getByDate(date) ?: return@launch
        scheduleRepo.save(rec.copy(confirmEarlyOT = false))
        _uiEvent.send(CalendarUiEvent.ShowMessage("已撤销 $date 早到加班确认"))
    }

    /** 撤销确认晚退加班 */
    fun unconfirmLateOvertime(date: String) = viewModelScope.launch {
        val rec = scheduleRepo.getByDate(date) ?: return@launch
        scheduleRepo.save(rec.copy(confirmLateOT = false))
        _uiEvent.send(CalendarUiEvent.ShowMessage("已撤销 $date 晚退加班确认"))
    }

    /** 撤销忽略早到加班 */
    fun unignoreEarlyArrival(date: String) = viewModelScope.launch {
        val rec = scheduleRepo.getByDate(date) ?: return@launch
        scheduleRepo.save(rec.copy(ignoreEarlyArrival = false))
        _uiEvent.send(CalendarUiEvent.ShowMessage("已撤销 $date 早到加班忽略"))
    }

    /** 撤销忽略晚退加班 */
    fun unignoreLateLeave(date: String) = viewModelScope.launch {
        val rec = scheduleRepo.getByDate(date) ?: return@launch
        scheduleRepo.save(rec.copy(ignoreLateLeave = false))
        _uiEvent.send(CalendarUiEvent.ShowMessage("已撤销 $date 晚退加班忽略"))
    }

    fun applyRule(overwrite: Boolean = false) = viewModelScope.launch {
        val s    = _state.value
        val rule = s.scheduleRule ?: return@launch
        runCatching {
            val results = applyScheduleRule(rule, s.year, s.month, s.schedules, overwrite)
            scheduleRepo.saveAll(results)
            _uiEvent.send(CalendarUiEvent.ShowMessage("已应用排班规则，共 ${results.size} 天"))
        }.onFailure {
            _uiEvent.send(CalendarUiEvent.ShowError("应用排班规则失败：${it.message}"))
        }
    }

    /** 将 ScheduleRule 应用到当月 */
    private fun applyScheduleRule(
        rule: ScheduleRule,
        year: Int, month: Int,
        existing: Map<String, ScheduleRecord>,
        overwrite: Boolean
    ): List<ScheduleRecord> {
        if (rule.shiftIds.isEmpty()) return emptyList()
        val ym   = YearMonth.of(year, month)
        val days = ym.lengthOfMonth()
        val results = mutableListOf<ScheduleRecord>()

        // 解析起始日期（用于连续循环模式）
        val startDate: LocalDate? = runCatching {
            val p = rule.startDate.split("-")
            LocalDate.of(p[0].toInt(), p[1].toInt(), p[2].toInt())
        }.getOrNull()

        for (d in 1..days) {
            val dateStr = "%04d-%02d-%02d".format(year, month, d)
            if (!overwrite && existing.containsKey(dateStr)) continue

            val idx = if (rule.independentCycle || startDate == null) {
                // 每月独立循环：月内第几天 + 起始偏移
                (d - 1 + rule.startOffset) % rule.shiftIds.size
            } else {
                // 从 startDate 连续往后算
                val dayDate  = LocalDate.of(year, month, d)
                val diffDays = startDate.until(dayDate).days
                val raw      = (diffDays + rule.startOffset) % rule.shiftIds.size
                if (raw < 0) raw + rule.shiftIds.size else raw
            }

            val shiftId = rule.shiftIds[idx]
            results.add((existing[dateStr] ?: ScheduleRecord(dateStr)).copy(shiftId = shiftId))
        }
        return results
    }

    // ── 批量操作 ──────────────────────────────────────────────────────

    /** 退出所有操作模式 */
    fun exitAllModes() {
        _state.update { it.copy(
            batchMode = false,
            batchSelected = emptySet(),
            copyMode = false,
            copyPhase = 1,
            copySourceStart = null,
            copySourceEnd = null,
            copySourceDates = emptySet(),
            copyTargetDate = null,
            deleteMode = false
        ) }
    }

    /** 进入批量排班模式 */
    fun enterBatchMode() {
        exitAllModes()
        _state.update { it.copy(batchMode = true) }
    }

    /** 进入清除排班模式 */
    fun enterDeleteMode() {
        exitAllModes()
        _state.update { it.copy(deleteMode = true) }
    }

    fun toggleBatchMode() = _state.update { st ->
        st.copy(batchMode = !st.batchMode, batchSelected = emptySet())
    }

    fun batchApplyShift(shiftId: String, statusId: String? = null) = viewModelScope.launch {
        val sel = _state.value.batchSelected
        if (sel.isEmpty()) return@launch
        // 查找目标班次，获取默认关联项目
        val shift = _state.value.allShifts.find { it.id == shiftId }
        val linkedIds = shift?.linkedExtraIds ?: emptyList()
        val records = sel.map { date ->
            val existing = _state.value.schedules[date] ?: ScheduleRecord(date)
            val merged = if (linkedIds.isEmpty()) existing.extraItemIds
                         else (existing.extraItemIds + linkedIds).distinct()
            existing.copy(
                shiftId = shiftId,
                appliedStatus = statusId?.let { AppliedStatus(it) },
                extraItemIds = merged
            )
        }
        scheduleRepo.saveAll(records)
        _state.update { it.copy(batchMode = false, batchSelected = emptySet()) }
        _uiEvent.send(CalendarUiEvent.ShowMessage("已批量设置 ${sel.size} 天排班"))
    }

    fun batchDelete() = viewModelScope.launch {
        val sel = _state.value.batchSelected
        if (sel.isEmpty()) return@launch
        sel.forEach { scheduleRepo.delete(it) }
        exitAllModes()
        _uiEvent.send(CalendarUiEvent.ShowMessage("已清除 ${sel.size} 天排班"))
        loadCurrentMonth()
    }

    /** 全选当月所有日期（不限有无排班） */
    fun batchSelectAll() {
        val s = _state.value
        val daysInMonth = getDaysInMonth(s.year, s.month)
        val allDates = (1..daysInMonth).map { "%04d-%02d-%02d".format(s.year, s.month, it) }.toSet()
        _state.update { it.copy(batchSelected = allDates) }
    }

    /** 取消全选 */
    fun batchClearSelection() {
        _state.update { it.copy(batchSelected = emptySet()) }
    }

    /** 添加单个日期到选择集 */
    fun batchAddToSelection(date: String) {
        val sel = _state.value.batchSelected.toMutableSet()
        sel.add(date)
        _state.update { it.copy(batchSelected = sel) }
    }

    /** 复制排班模式：添加单个日期到源日期集合 */
    fun copyAddToSelection(date: String) {
        val st = _state.value
        if (st.copyPhase != 1) return
        val dates = st.copySourceDates.toMutableSet()
        dates.add(date)
        _state.update { it.copy(copySourceDates = dates) }
    }

    // ── 复制排班操作 ──────────────────────────────────────────────

    /** 进入复制排班模式 */
    fun enterCopyMode() {
        exitAllModes()
        _state.update { it.copy(
            copyMode = true,
            copyPhase = 1
        ) }
    }

    /** 退出复制排班模式 */
    fun exitCopyMode() {
        exitAllModes()
    }

    /** 清除复制排班的选择 */
    fun copyClearSelection() {
        _state.update { it.copy(
            copySourceStart = null,
            copySourceEnd = null,
            copySourceDates = emptySet()
        ) }
    }

    /** 阶段一：选择源日期（连续范围选择） */
    fun copySourceClick(date: String) {
        val st = _state.value
        if (st.copyPhase != 1) return

        if (st.copySourceStart == null) {
            // 第一次点击：设置起始日期
            _state.update { it.copy(
                copySourceStart = date,
                copySourceEnd = null,
                copySourceDates = setOf(date)
            ) }
        } else if (st.copySourceEnd == null) {
            // 第二次点击：设置结束日期并填充范围
            val start = st.copySourceStart
            val sorted = listOf(start, date).sorted()
            val startDate = LocalDate.parse(sorted[0])
            val endDate = LocalDate.parse(sorted[1])
            val dates = mutableSetOf<String>()
            var current = startDate
            while (!current.isAfter(endDate)) {
                dates.add("%04d-%02d-%02d".format(current.year, current.monthValue, current.dayOfMonth))
                current = current.plusDays(1)
            }
            _state.update { it.copy(
                copySourceStart = sorted[0],
                copySourceEnd = sorted[1],
                copySourceDates = dates
            ) }
        } else {
            // 已有完整范围，重新开始选择
            _state.update { it.copy(
                copySourceStart = date,
                copySourceEnd = null,
                copySourceDates = setOf(date)
            ) }
        }
    }

    /** 进入阶段二：选择目标起始位置 */
    fun copyEnterPhase2() {
        _state.update { it.copy(copyPhase = 2, copyTargetDate = null) }
    }

    /** 阶段二：选择目标起始日期 */
    fun copyTargetClick(date: String) {
        if (_state.value.copyPhase != 2) return
        _state.update { it.copy(copyTargetDate = date) }
    }

    /** 返回阶段一 */
    fun copyBackToPhase1() {
        _state.update { it.copy(
            copyPhase = 1,
            copyTargetDate = null
        ) }
    }

    /** 执行复制操作 */
    fun copyExecute() = viewModelScope.launch {
        val st = _state.value
        if (st.copyPhase != 2 || st.copyTargetDate == null || st.copySourceDates.isEmpty()) return@launch

        val sourceDates = st.copySourceDates.sorted()
        val targetStart = LocalDate.parse(st.copyTargetDate)
        val sourceStart = LocalDate.parse(sourceDates.first())

        // 获取源日期的排班记录
        val sourceRecords = sourceDates.mapNotNull { date ->
            scheduleRepo.getByDate(date)?.let { it to date }
        }

        if (sourceRecords.isEmpty()) {
            _uiEvent.send(CalendarUiEvent.ShowMessage("源日期无排班数据"))
            return@launch
        }

        // 计算偏移量并复制
        val results = mutableListOf<ScheduleRecord>()
        for ((record, srcDate) in sourceRecords) {
            val srcLocalDate = LocalDate.parse(srcDate)
            val daysOffset = sourceStart.until(srcLocalDate).days
            val dstDate = targetStart.plusDays(daysOffset.toLong())
            val dstDateStr = "%04d-%02d-%02d".format(dstDate.year, dstDate.monthValue, dstDate.dayOfMonth)
            results.add(record.copy(date = dstDateStr))
        }

        scheduleRepo.saveAll(results)
        exitAllModes()
        _uiEvent.send(CalendarUiEvent.ShowMessage("已复制 ${results.size} 天排班"))
        loadCurrentMonth()
    }

    /**
     * 批量范围复制：将 [srcYear/srcMonth] 的排班数据复制到 [dstYear/dstMonth]
     * 若 overwrite=true 则覆盖目标月已有排班，否则跳过
     */
    fun batchCopyMonth(srcYear: Int, srcMonth: Int, dstYear: Int, dstMonth: Int, overwrite: Boolean = false) =
        viewModelScope.launch {
            val srcRecords = scheduleRepo.getByMonth("%04d-%02d".format(srcYear, srcMonth))
            if (srcRecords.isEmpty()) {
                _uiEvent.send(CalendarUiEvent.ShowMessage("来源月份无排班数据"))
                return@launch
            }
            val dstExisting = scheduleRepo.getByMonth("%04d-%02d".format(dstYear, dstMonth))
                .associateBy { it.date }.toMutableMap()
            val srcDays  = getDaysInMonth(srcYear, srcMonth)
            val dstDays  = getDaysInMonth(dstYear, dstMonth)
            val results  = mutableListOf<ScheduleRecord>()
            for (d in 1..minOf(srcDays, dstDays)) {
                val srcDate = "%04d-%02d-%02d".format(srcYear, srcMonth, d)
                val dstDate = "%04d-%02d-%02d".format(dstYear, dstMonth, d)
                if (!overwrite && dstExisting.containsKey(dstDate)) continue
                val src = srcRecords.find { it.date == srcDate } ?: continue
                results.add(src.copy(date = dstDate))
            }
            if (results.isEmpty()) {
                _uiEvent.send(CalendarUiEvent.ShowMessage("目标月份已有排班，无可复制内容"))
                return@launch
            }
            scheduleRepo.saveAll(results)
            _uiEvent.send(CalendarUiEvent.ShowMessage("已复制 ${results.size} 天排班至 ${dstYear}年${dstMonth}月"))
            // 如果复制到当前显示月，刷新
            if (dstYear == _state.value.year && dstMonth == _state.value.month) loadCurrentMonth()
        }

    private fun getDaysInMonth(year: Int, month: Int): Int =
        YearMonth.of(year, month).lengthOfMonth()

    // ── Widget 同步 ───────────────────────────────────────────────────

    private suspend fun syncCalendarWidget(
        year: Int, month: Int,
        allShifts: List<Shift>,
        schedules: Map<String, ScheduleRecord>,
        allShiftStatuses: List<ShiftStatus>,
        selectedDate: String?
    ) {
        val today = LocalDate.now()
        val ym = YearMonth.of(year, month)
        val daysInMonth = ym.lengthOfMonth()
        val firstDow = (LocalDate.of(year, month, 1).dayOfWeek.value + 6) % 7  // 周一=0..周日=6

        val shiftMap = allShifts.associateBy { it.id }
        val statusMap = (BUILTIN_STATUSES + allShiftStatuses).associateBy { it.id }

        val days = (1..daysInMonth).map { d ->
            val dateStr = "%04d-%02d-%02d".format(year, month, d)
            val record = schedules[dateStr]
            val shift = record?.shiftId?.let { shiftMap[it] }
            val appliedSt = record?.appliedStatus?.let { statusMap[it.statusId] }
            CalendarWidgetDay(
                day = d,
                dateStr = dateStr,
                shiftName = shift?.name ?: "",
                shiftColor = shift?.color ?: "",
                statusName = appliedSt?.name ?: ""
            )
        }

        val totalCells = firstDow + daysInMonth
        val totalRows = (totalCells + 6) / 7

        val data = CalendarWidgetInfo(
            year = year,
            month = month,
            days = days,
            weekStartOffset = firstDow,
            totalRows = totalRows
        )
        CalendarGlanceWidget.updateWidgetData(context, data)
    }

   /**
     * 查找当前活跃班次
     * 今天优先 → 其次昨天跨午夜夜班
     */
    private fun findActiveShift(
        shifts: List<Shift>,
        schedules: Map<String, ScheduleRecord>
    ): ActiveShiftResult? {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        val now = LocalTime.now()
        val nowMin = now.hour * 60 + now.minute

        // 第一步：检查今天
        val todayStr = "%04d-%02d-%02d".format(today.year, today.monthValue, today.dayOfMonth)
        val todayRecord = schedules[todayStr]
        if (todayRecord?.type == ScheduleType.SHIFT && todayRecord.shiftId != null) {
            val shift = shifts.find { it.id == todayRecord.shiftId }
            if (shift != null && shift.startTime.isNotEmpty() && shift.endTime.isNotEmpty()) {
                val ss = CalcUtils.timeToMin(shift.startTime)
                val se = CalcUtils.timeToMin(shift.endTime)
                val (_, normE) = CalcUtils.normRange(ss, se)

                val showClockIn = nowMin >= (ss - 300) && nowMin < normE
                val showClockOut = nowMin >= ss && nowMin <= (normE + 300)

                if (showClockIn || showClockOut) {
                    return ActiveShiftResult(today, shift, showClockIn, showClockOut)
                }
            }
        }

        // 第二步：检查昨天（针对跨午夜夜班的下班打卡）
        val yesterdayStr = "%04d-%02d-%02d".format(yesterday.year, yesterday.monthValue, yesterday.dayOfMonth)
        val yesterdayRecord = schedules[yesterdayStr]
        if (yesterdayRecord?.type == ScheduleType.SHIFT && yesterdayRecord.shiftId != null) {
            val shift = shifts.find { it.id == yesterdayRecord.shiftId }
            if (shift != null && shift.startTime.isNotEmpty() && shift.endTime.isNotEmpty()) {
                // 偏移到"今天时间轴"：起止分钟都 -1440
                val ss = CalcUtils.timeToMin(shift.startTime) - 1440
                val se = CalcUtils.timeToMin(shift.endTime) - 1440
                val (_, normE) = CalcUtils.normRange(ss, se)

                val showClockOut = nowMin >= ss && nowMin <= (normE + 300)

                // 昨天的活跃班次只考虑下班打卡
                if (showClockOut) {
                    return ActiveShiftResult(yesterday, shift, false, true)
                }
            }
        }

        return null
    }

    private suspend fun syncWidget(shifts: List<Shift>, schedules: Map<String, ScheduleRecord>) {
        val today = LocalDate.now()
        val todayStr = "%04d-%02d-%02d".format(today.year, today.monthValue, today.dayOfMonth)
        val tomorrow = today.plusDays(1)
        val tomorrowStr = "%04d-%02d-%02d".format(tomorrow.year, tomorrow.monthValue, tomorrow.dayOfMonth)

        // 1. 查找活跃班次
        val active = findActiveShift(shifts, schedules)

        // 2. 确定目标排班记录和班次
        val targetDate = active?.date ?: today
        val targetDateStr = "%04d-%02d-%02d".format(targetDate.year, targetDate.monthValue, targetDate.dayOfMonth)
        val targetRecord = schedules[targetDateStr]
        val targetShift = targetRecord?.shiftId?.let { id -> shifts.find { it.id == id } }

        val tomorrowShift = schedules[tomorrowStr]?.shiftId?.let { id -> shifts.find { it.id == id } }

        // 3. 应用规则 1-4：判断是否内置班次 + 附加状态类型
        val isBuiltInShift = targetShift?.builtIn == true &&
                (targetShift?.builtInType == "rest" || targetShift?.builtInType == "swap")
        val hasAppliedStatus = targetRecord?.appliedStatus != null
        val hasBuiltInStatus = hasAppliedStatus && isBuiltInStatus(targetRecord!!.appliedStatus!!.statusId)
        val hasCustomStatus = hasAppliedStatus && !hasBuiltInStatus

        var showClockIn = active?.showClockIn ?: false
        var showClockOut = active?.showClockOut ?: false

        when {
            // 规则1：内置班次且无自定义附加状态 → 不显示打卡按钮
            isBuiltInShift && !hasCustomStatus -> {
                showClockIn = false
                showClockOut = false
            }
            // 规则4：内置班次 + 自定义附加状态 → 显示打卡按钮
            isBuiltInShift && hasCustomStatus -> {
                showClockIn = true
                showClockOut = true
            }
        }

        // 4. 读取附加状态名
        val statusName = targetRecord?.appliedStatus?.let { applied ->
            (BUILTIN_STATUSES + state.value.allShiftStatuses).find { it.id == applied.statusId }?.name
        } ?: ""

        // 5. 读取本地打卡状态（SharedPreferences）
        val clockPrefs = context.getSharedPreferences("clock_in_widget_prefs", Context.MODE_PRIVATE)
        val savedDate = clockPrefs.getString("clock_in_date", "") ?: ""
        val actualStart = if (savedDate == targetDateStr) clockPrefs.getString("clock_in_time", "") ?: "" else ""
        val actualEnd = if (savedDate == targetDateStr) clockPrefs.getString("clock_out_time", "") ?: "" else ""

        val hasClockedIn = actualStart.isNotEmpty()
        val hasClockedOut = actualEnd.isNotEmpty()

        // 6. 根据打卡进度调整按钮显示
        when {
            // 规则4：内置班次 + 自定义状态 → 已下班打卡后隐藏全部按钮
            isBuiltInShift && hasCustomStatus && hasClockedOut -> {
                showClockIn = false
                showClockOut = false
            }
            // 规则4：内置班次 + 自定义状态 → 已上班未下班 → 只显示下班卡
            isBuiltInShift && hasCustomStatus && hasClockedIn && !hasClockedOut -> {
                showClockIn = false
                showClockOut = true
            }
            // 规则2/3：普通班次 → 已全部打卡 → 隐藏按钮
            !isBuiltInShift && hasClockedIn && hasClockedOut -> {
                showClockIn = false
                showClockOut = false
            }
            // 规则2/3：普通班次 → 已上班未下班 → 只显示下班卡
            !isBuiltInShift && hasClockedIn && !hasClockedOut -> {
                showClockIn = false
                showClockOut = true
            }
        }

        val widgetData = ClockInWidgetData(
            shiftName          = targetShift?.name ?: "",
            startTime          = targetShift?.startTime ?: "",
            endTime            = targetShift?.endTime ?: "",
            tomorrowShiftName  = tomorrowShift?.name ?: "",
            actualStartTime    = actualStart,
            actualEndTime      = actualEnd,
            shiftColor         = targetShift?.color ?: "#059669",
            statusName         = statusName,
            shiftId            = targetShift?.id ?: "",
            isBuiltInShift     = isBuiltInShift,
            appliedStatusId    = targetRecord?.appliedStatus?.statusId ?: "",
            isBuiltInStatus    = hasBuiltInStatus,
            showClockIn        = showClockIn,
            showClockOut       = showClockOut,
            hasClockIn         = hasClockedIn,
            hasClockOut        = hasClockedOut,
            clockInDate        = targetDateStr,
            widgetClockInTime  = actualStart,
            widgetClockOutTime = actualEnd
        )
        ScheduleGlanceWidget.updateWidgetData(context, widgetData)
    }

    private suspend fun syncClockInWidget() {
        val s = _state.value
        syncWidget(s.allShifts, s.schedules)
    }
}
