// app/src/main/java/com/schedulecalendar/app/ui/calendar/CalendarViewModel.kt
package com.schedulecalendar.app.ui.calendar
import com.schedulecalendar.app.domain.util.AppLog
import com.schedulecalendar.app.domain.util.LogTags

import com.schedulecalendar.app.domain.util.DateFormats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.schedulecalendar.app.data.calendar.CalendarEventInfo
import com.schedulecalendar.app.data.calendar.CalendarEventRepository
import com.schedulecalendar.app.data.prefs.AppPreferences
import com.schedulecalendar.app.data.repository.ExtraItemRepository
import com.schedulecalendar.app.data.repository.ScheduleRepository
import com.schedulecalendar.app.data.repository.ShiftRepository
import com.schedulecalendar.app.data.repository.ShiftStatusRepository
import com.schedulecalendar.app.domain.model.*
import com.schedulecalendar.app.widget.applyS4StatusRange
import com.schedulecalendar.app.widget.isBuiltInStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

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
    /** 内置附加状态名称（如"请假"、"调休"、"加班"），空=普通班次 */
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

@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val shiftRepo: ShiftRepository,
    private val scheduleRepo: ScheduleRepository,
    private val extraRepo: ExtraItemRepository,
    private val statusRepo: ShiftStatusRepository,
    private val backupManager: com.schedulecalendar.app.ui.settings.BackupManager,
    private val prefs: AppPreferences,
    private val calendarEventRepo: CalendarEventRepository,
    private val calcInputs: com.schedulecalendar.app.data.repository.CalcInputResolver
) : ViewModel() {

    private val _state   = MutableStateFlow(CalendarUiState())
    val state            = _state.asStateFlow()

    private val _uiEvent = Channel<CalendarUiEvent>(Channel.BUFFERED)
    val uiEvent          = _uiEvent.receiveAsFlow()

    /** 当前月份数据收集 Job，切月时取消旧 Job 再启新 Job，防止 collector 累积泄漏 */
    private var collectJob: Job? = null

    init {
        loadCurrentMonth()
        // 延迟执行非关键初始化，必须放到 Dispatchers.IO，
        // 否则 backupManager.autoBackupAppData() 内的全量 Room 查询 + Gson + file.writeText
        // 以及 calendarEventRepo.getOrCreateLocalCalendarId() 内的 AccountManager/ContentResolver 同步 Binder
        // 都会阻塞主线程造成卡顿（已在 OPPO UISlowBinder 与 GC freed 59MB 中证实）。
        viewModelScope.launch(Dispatchers.IO) {
            delay(500.milliseconds)
            backupManager.autoBackupAppData()
        }
        viewModelScope.launch(Dispatchers.IO) {
            delay(1000.milliseconds)
            calendarEventRepo.getOrCreateLocalCalendarId()
        }
    }

    private fun loadCurrentMonth() {
        collectJob?.cancel()          // 取消上一次月份的无限收集协程
        collectJob = viewModelScope.launch {
            val s = _state.value

            // 计算日历网格覆盖的完整日期范围（含跨月填充日期）
            // 上月（滑动联动用，需加载完整月份数据）
            val prevYM = if (s.month == 1) YearMonth.of(s.year - 1, 12) else YearMonth.of(s.year, s.month - 1)
            // 下月（滑动联动用，需加载完整月份数据）
            val nextYM = if (s.month == 12) YearMonth.of(s.year + 1, 1) else YearMonth.of(s.year, s.month + 1)
            // 数据范围：上月1日 ~ 下月末日（覆盖完整相邻月份，支持滑动时显示）
            val rangeFrom = DateFormats.monthStart(prevYM.year, prevYM.monthValue)
            val rangeTo   = DateFormats.date(nextYM.year, nextYM.monthValue, nextYM.lengthOfMonth())

            combine(
                shiftRepo.observeAll().distinctUntilChanged(),
                scheduleRepo.observeByRange(rangeFrom, rangeTo).distinctUntilChanged(),
                prefs.displaySchemesFlow.distinctUntilChanged(),
                prefs.scheduleRuleFlow.distinctUntilChanged()
            ) { shifts, records, schemes, rule ->
                Triple(shifts, records, Pair(schemes, rule))
                // conflate：连续打卡/批量排班会连发多次，中间状态直接丢弃，
                // 避免排队跑多轮「3 个月 ×逐日工时薪资」重算（只需最终结果）
            }.conflate().collect { (rawShifts, records, pair) ->
                val (schemes, rule) = pair
                // 将数据加载与重计算整体移到后台线程，避免首次/切换 Tab 时阻塞主线程导致卡顿。
                // 所有输入（Room 查询结果、SharedPreferences）均为内存数据，线程安全；_state.update 为原子操作可跨线程。
                withContext(Dispatchers.Default) {
                    val schedules  = records.associateBy { it.date }
                    val scheme     = schemes.firstOrNull { it.isActive } ?: DisplayScheme(
                        id = NO_SCHEME_ID, name = "预设方案", isNoScheme = true, builtIn = true, isActive = true
                    )
                    val extraItems = extraRepo.getAll()
                    // ⚠️ 必须取「含归档」的状态：历史排班引用的自定义状态可能已归档，
                    // 若用仅有效集合，buildTodos 解析不出状态名（待办标签为空）、
                    // DayCell 也渲染不出历史格子上的状态标签。activeStatuses 会再过滤归档项。
                    val rawStatuses = statusRepo.getAllIncludingArchivedWithBuiltin()
                    // 配置与排序一次读盘（原先 4 次 first() = 4 次文件读 + 4 次整份反序列化）
                    val calcCtx    = prefs.loadCalcContext()
                    val segments  = calcInputs.segmentsInRange(rangeFrom, rangeTo)
                    val inputsFor = segments.inputsLookup(calcInputs.fallbackInputs())
                    val inputsFp  = segments.inputsFingerprint()
                    val attendConf = calcCtx.attendConfig
                    // 按用户自定义排序（与 ShiftsScreen 保持一致），并过滤已归档项
                    val shiftOrder = calcCtx.shiftOrder
                    val statusOrder = calcCtx.statusOrder
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
                    // 计算当月详情（使用完整列表，确保历史归档班次也能正确计算）
                    val curDetails = CalcUtils.getMonthScheduleDetailsByDate(
                        s.year, s.month, schedules, allShifts, extraItems, inputsFor, inputsFp
                    ).associateBy { it.date }
                    // 计算完整相邻月份详情（支持滑动联动时显示相邻月份网格）
                    val prevDetails = CalcUtils.getMonthScheduleDetailsByDate(
                        prevYM.year, prevYM.monthValue, schedules, allShifts, extraItems, inputsFor, inputsFp
                    ).associateBy { it.date }
                    val nextDetails = CalcUtils.getMonthScheduleDetailsByDate(
                        nextYM.year, nextYM.monthValue, schedules, allShifts, extraItems, inputsFor, inputsFp
                    ).associateBy { it.date }
                    val allDetails = curDetails + prevDetails + nextDetails
                    val todos      = buildTodos(s.year, s.month, schedules, allShifts, attendConf, rawStatuses)
                    // 切月竞态防护：若本次收集协程已被取消，则不回写状态，
                    // 避免被取消的旧月份计算完成后覆盖新月份的结果。
                    if (isActive) _state.update { it.copy(
                        shifts         = shifts,
                        allShifts      = allShifts,
                        schedules      = schedules,
                        displayScheme  = scheme,
                        scheduleRule   = rule,
                        dayDetails     = allDetails,
                        todos          = todos,
                        extraItems     = extraItems,
                        shiftStatuses  = shiftStatuses,
                        allShiftStatuses = rawStatuses,
                        loading        = false
                    )}
                    // Widget 同步由 ScheduleApp 全局数据变更信号统一驱动（任何排班/班次/状态落库后 400ms 自动同步）。
                    // 不再在此处额外同步：避免「ViewModel 同步 + 全局信号」双路 update 在部分 OEM 桌面上被丢弃。
                }
                // 加载选中日期的纪念日与日程（首次加载或切月后）
                val selDate = _state.value.selectedDate ?: DateFormats.date(s.year, s.month, LocalDate.now().dayOfMonth)
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
        attendConfig: AttendConfig,
        statuses: List<ShiftStatus>
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

            val dateStr = DateFormats.date(year, month, d)
            val record  = schedules[dateStr]
            val shift   = record?.shiftId?.let { id -> shifts.find { it.id == id } }
            if (shift == null) continue

            val sn = shift.name
            val applied = record.appliedStatus
            val isBuiltIn = applied != null && (applied.statusId == BUILTIN_STATUS_LEAVE || applied.statusId == BUILTIN_STATUS_SWAP)
            // 解析附加状态名称（内置请假/调休 + 自定义状态），供待办项显示
            val stLabel = when {
                applied == null -> ""
                applied.statusId == BUILTIN_STATUS_LEAVE -> "请假"
                applied.statusId == BUILTIN_STATUS_SWAP -> "调休"
                else -> statuses.find { it.id == applied.statusId }?.name ?: ""
            }

            // S1/S3：休息/调休班次（无时间段）
            if (shift.builtInType == "rest" || shift.builtInType == "swap") {
                if (applied == null) continue   // S1：无附加状态 → 无待办
                // S3：附加状态时间段即打卡记录；"已打上班卡但未打下班卡"且日期早于昨天（已过期超过一天）→ 漏打卡待补录
                val stFilled = !applied.startTime.isNullOrEmpty()
                val etFilled = !applied.endTime.isNullOrEmpty()
                val pastCutoff = LocalDate.parse(dateStr).isBefore(today.minusDays(1))
                if (stFilled && !etFilled && pastCutoff) {
                    todos.add(TodoItem(dateStr, TodoType.MISSED_CLOCK_OUT, "下班漏打卡", shiftName = sn, statusLabel = stLabel))
                } else if (stFilled && etFilled) {
                    todos.add(TodoItem(dateStr, TodoType.FILLED_CLOCK_OUT, "下班已补录", shiftName = sn, clockTime = applied.endTime, statusLabel = stLabel))
                }
                continue
            }

            val st = if (shift.startTime.isNotEmpty() && shift.endTime.isNotEmpty()) "${shift.startTime}-${shift.endTime}" else ""
            val stStart = shift.startTime
            val stEnd = shift.endTime
            // 内置状态：检查 appliedStatus 时间；普通：检查 actualStartTime/actualEndTime（空字符串也视为未填写）
            val startFilled = if (isBuiltIn) !applied.startTime.isNullOrEmpty() else !record.actualStartTime.isNullOrEmpty()
            val endFilled   = if (isBuiltIn) !applied.endTime.isNullOrEmpty() else !record.actualEndTime.isNullOrEmpty()
            val startVal    = if (isBuiltIn) applied.startTime else record.actualStartTime
            val endVal      = if (isBuiltIn) applied.endTime else record.actualEndTime
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
                    todos.add(TodoItem(dateStr, TodoType.PENDING_EARLY_OT, "早到加班待确认", shiftName = sn, shiftTime = st, overtimeMinutes = earlyMin, actualTime = record.actualStartTime, statusLabel = stLabel))
                }
            } else if (record.confirmEarlyOT) {
                val earlyMin = CalcUtils.timeToMin(shift.startTime) - CalcUtils.timeToMin(record.actualStartTime ?: shift.startTime)
                todos.add(TodoItem(dateStr, TodoType.CONFIRMED_EARLY_OT, "已确认早到加班", shiftName = sn, shiftTime = st, overtimeMinutes = maxOf(0, earlyMin), actualTime = record.actualStartTime ?: "", statusLabel = stLabel))
            } else if (record.ignoreEarlyArrival) {
                val earlyMin = CalcUtils.timeToMin(shift.startTime) - CalcUtils.timeToMin(record.actualStartTime ?: shift.startTime)
                todos.add(TodoItem(dateStr, TodoType.IGNORED_EARLY_OT, "忽略早到加班", shiftName = sn, shiftTime = st, overtimeMinutes = maxOf(0, earlyMin), actualTime = record.actualStartTime ?: "", statusLabel = stLabel))
            }
            // 晚退加班待确认：有实际下班时间 & 比班次晚 & 未忽略晚退 & 未确认晚退加班
            if (!record.actualEndTime.isNullOrEmpty() && !record.ignoreLateLeave && !record.confirmLateOT) {
                val sS = CalcUtils.timeToMin(shift.startTime)
                val (_, normSE) = CalcUtils.normRange(sS, CalcUtils.timeToMin(shift.endTime))
                val (_, normAE) = CalcUtils.normRange(sS, CalcUtils.timeToMin(record.actualEndTime))
                val lateMin = normAE - normSE
                val grain = attendConfig.overtimeGranMin
                if (lateMin >= grain) {
                    todos.add(TodoItem(dateStr, TodoType.PENDING_LATE_OT, "晚退加班待确认", shiftName = sn, shiftTime = st, overtimeMinutes = lateMin, actualTime = record.actualEndTime, statusLabel = stLabel))
                }
            } else if (record.confirmLateOT) {
                val sS = CalcUtils.timeToMin(shift.startTime)
                val (_, normSE) = CalcUtils.normRange(sS, CalcUtils.timeToMin(shift.endTime))
                val (_, normAE) = CalcUtils.normRange(sS, CalcUtils.timeToMin(record.actualEndTime ?: shift.endTime))
                val lateMin = normAE - normSE
                todos.add(TodoItem(dateStr, TodoType.CONFIRMED_LATE_OT, "已确认晚退加班", shiftName = sn, shiftTime = st, overtimeMinutes = maxOf(0, lateMin), actualTime = record.actualEndTime ?: "", statusLabel = stLabel))
            } else if (record.ignoreLateLeave) {
                val sS = CalcUtils.timeToMin(shift.startTime)
                val (_, normSE) = CalcUtils.normRange(sS, CalcUtils.timeToMin(shift.endTime))
                val (_, normAE) = CalcUtils.normRange(sS, CalcUtils.timeToMin(record.actualEndTime ?: shift.endTime))
                val lateMin = normAE - normSE
                todos.add(TodoItem(dateStr, TodoType.IGNORED_LATE_OT, "忽略晚退加班", shiftName = sn, shiftTime = st, overtimeMinutes = maxOf(0, lateMin), actualTime = record.actualEndTime ?: "", statusLabel = stLabel))
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
        val todayStr = DateFormats.date(today)
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
                withContext(Dispatchers.IO) {
                    val disabledIds = prefs.getDisabledAccountIds()
                    calendarEventRepo.getEventsForDate(date)
                        .filter { it.calendarId !in disabledIds }
                }
            } catch (e: Exception) {
                // 记日志：静默清空会让「明明有事件却显示无事件」无从排查
                AppLog.w(LogTags.CALENDAR_VIEW_MODEL, "loadSelectedDateEvents failed: $date", e)
                emptyList()
            }
            // 回写前校验仍是当前选中日期：慢查询返回时用户可能已切到别的日期，
            // 直接回写会让别的日期显示旧日期的事件
            if (_state.value.selectedDate == date) {
                _state.update { it.copy(selectedDateEvents = events) }
            }
        }
    }

    /**
     * 加载指定范围内有日历事件（日程/纪念日）的日期集合
     * 用于 DayCell 显示事件指示点
     */
    private fun loadDatesWithEvents(rangeFrom: String, rangeTo: String) {
        viewModelScope.launch {
            val datesWithEvents = try {
                withContext(Dispatchers.IO) {
                    val disabledIds = prefs.getDisabledAccountIds()
                    val fromDate = try { LocalDate.parse(rangeFrom) } catch (_: Exception) { null }
                    val toDate = try { LocalDate.parse(rangeTo) } catch (_: Exception) { null }
                    if (fromDate == null || toDate == null) {
                        emptySet()
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
                            // 处理年度重复纪念日：检查今年是否落在范围内（复用仓库层公共函数）
                            if (calendarEventRepo.isYearlyRecurring(event)) {
                                for (year in fromDate.year..toDate.year) {
                                    val thisYearDate = calendarEventRepo.yearlyOccurrenceInYear(event, year)
                                        ?: continue
                                    if (!thisYearDate.isBefore(fromDate) && !thisYearDate.isAfter(toDate)) {
                                        dates.add(thisYearDate.toString())
                                    }
                                }
                            }
                        }
                        dates
                    }
                }
            } catch (e: Exception) {
                AppLog.w(LogTags.CALENDAR_VIEW_MODEL, "loadDatesWithEvents failed", e)
                // 失败时保留上一次结果：直接清空会让日历格上的事件指示点全部消失，误导用户
                return@launch
            }
            _state.update { it.copy(datesWithEvents = datesWithEvents) }
        }
    }

    fun clockIn(date: String, time: String) = viewModelScope.launch {
        var rec = scheduleRepo.getByDate(date) ?: ScheduleRecord(date)
        val shift = rec.shiftId?.let { id -> shiftRepo.getById(id) }
        val isRestSwap = shift?.builtIn == true && (shift.builtInType == "rest" || shift.builtInType == "swap")
        val ap = rec.appliedStatus
        val isLeaveSwap = ap != null && isBuiltInStatus(ap.statusId)
        rec = when {
            // S3：休息/调休 + 附加状态 → 写入附加状态开始时间
            isRestSwap && ap != null -> rec.copy(appliedStatus = ap.copy(startTime = time))
            // S4：正常班 + 请假/调休 → 写实际上班时间 + 重算附加状态时间段
            !isRestSwap && isLeaveSwap && shift != null -> {
                val grain = prefs.attendConfigFlow.first().overtimeGranMin
                applyS4StatusRange(rec.copy(actualStartTime = time), shift, grain)
            }
            // S2 / S5：写实际上班时间
            else -> rec.copy(actualStartTime = time)
        }
        scheduleRepo.save(rec)
        _uiEvent.send(CalendarUiEvent.ShowMessage("已记录上班打卡 $time"))
        // 组件同步由 ScheduleApp 全局变更信号统一驱动（落库后 400ms 自动更新）
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
                    LocalDate.parse(date).minusDays(1).toString()
                } catch (_: Exception) { date }
                prevDate
            } else date
        } else date

        var targetRec = scheduleRepo.getByDate(actualTargetDate) ?: ScheduleRecord(actualTargetDate)
        val targetShift = targetRec.shiftId?.let { id -> shiftRepo.getById(id) }
        val isRestSwap = targetShift?.builtIn == true && (targetShift.builtInType == "rest" || targetShift.builtInType == "swap")
        val ap = targetRec.appliedStatus
        val isLeaveSwap = ap != null && isBuiltInStatus(ap.statusId)
        targetRec = when {
            // S3：休息/调休 + 附加状态 → 写入附加状态结束时间（支持跨天加班）
            isRestSwap && ap != null -> targetRec.copy(appliedStatus = ap.copy(endTime = time))
            // S4：正常班 + 请假/调休 → 写实际下班时间 + 重算附加状态时间段
            !isRestSwap && isLeaveSwap && targetShift != null -> {
                val grain = prefs.attendConfigFlow.first().overtimeGranMin
                applyS4StatusRange(targetRec.copy(actualEndTime = time), targetShift, grain)
            }
            // S2 / S5：写实际下班时间（允许未打上班卡直接打下班卡）
            else -> targetRec.copy(actualEndTime = time)
        }
        scheduleRepo.save(targetRec)
        _uiEvent.send(CalendarUiEvent.ShowMessage("已记录下班打卡 $time"))
        // 组件同步由 ScheduleApp 全局变更信号统一驱动（落库后 400ms 自动更新）
    }

    /** 补填漏打卡时间：内置附加状态写入 appliedStatus（并 clamp 到班次范围），普通写入 actualTime */
    fun fillMissedClock(date: String, startTime: String?, endTime: String?) = viewModelScope.launch {
        val rec = scheduleRepo.getByDate(date) ?: ScheduleRecord(date)
        val applied = rec.appliedStatus
        val shift = rec.shiftId?.let { id -> shiftRepo.getById(id) }
        val isRestSwap = shift?.builtIn == true && (shift.builtInType == "rest" || shift.builtInType == "swap")
        // 填充附加状态时间段的限定条件：
        //  - 内置请假/调休：按状态判定（原逻辑）
        //  - 加班：仅当班次为休息/调休（无具体时间段）时才填充，普通班次不进入此分支
        val isBuiltInStatus = applied != null && (applied.statusId == BUILTIN_STATUS_LEAVE || applied.statusId == BUILTIN_STATUS_SWAP)
        val isOvertimeOnRestSwap = applied?.countsAsOvertime == true && isRestSwap
        val fillStatusTime = isBuiltInStatus || isOvertimeOnRestSwap
        val updated = if (fillStatusTime) {
            // 获取班次时间段作为约束边界
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
            val finalStartTime = startTime?.ifBlank { null } ?: rec.actualStartTime
            var finalEndTime   = endTime?.ifBlank { null } ?: rec.actualEndTime
            var targetDate     = date

            if (!endTime.isNullOrBlank()) {
                if (shift != null && shift.endTime.isNotEmpty()) {
                    val timeMin = CalcUtils.timeToMin(endTime)
                    val endMin  = CalcUtils.timeToMin(shift.endTime)
                    if (timeMin < endMin) {
                        // 打卡时间早于班次结束 → 跨午夜，归属前一天
                        val prevDate = try {
                            LocalDate.parse(date).minusDays(1).toString()
                        } catch (_: Exception) { date }
                        targetDate = prevDate
                        finalEndTime = endTime
                    }
                }
            }

            if (targetDate != date && !startTime.isNullOrBlank()) {
                // 跨午夜：上班时间属当天、下班时间属前一天，必须分别落到两条记录。
                // 原实现只把下班时间写到前一天，上班时间被静默丢弃
                scheduleRepo.save(rec.copy(actualStartTime = startTime))
                val prevRec = scheduleRepo.getByDate(targetDate) ?: ScheduleRecord(targetDate)
                prevRec.copy(actualEndTime = endTime ?: prevRec.actualEndTime)
            } else {
                val targetRec = scheduleRepo.getByDate(targetDate) ?: ScheduleRecord(targetDate)
                targetRec.copy(
                    actualStartTime = if (targetDate == date) finalStartTime else targetRec.actualStartTime,
                    actualEndTime   = finalEndTime
                )
            }
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

    /** 撤销上班补录：内置请假/调休状态、或休息/调休上的加班 → 清除 appliedStatus.startTime；普通班次清除 actualStartTime */
    fun unfillMissedClockIn(date: String) = viewModelScope.launch {
        val rec = scheduleRepo.getByDate(date) ?: return@launch
        val applied = rec.appliedStatus
        val shift = rec.shiftId?.let { id -> shiftRepo.getById(id) }
        val isRestSwap = shift?.builtIn == true && (shift.builtInType == "rest" || shift.builtInType == "swap")
        val fillStatusTime = applied != null && (
            applied.statusId == BUILTIN_STATUS_LEAVE || applied.statusId == BUILTIN_STATUS_SWAP ||
            (applied.countsAsOvertime && isRestSwap)
        )
        if (fillStatusTime) {
            scheduleRepo.save(rec.copy(appliedStatus = applied.copy(startTime = null)))
        } else {
            scheduleRepo.save(rec.copy(actualStartTime = null))
        }
        _uiEvent.send(CalendarUiEvent.ShowMessage("已撤销 $date 上班打卡"))
    }

    /** 撤销下班补录：内置请假/调休状态、或休息/调休上的加班 → 清除 appliedStatus.endTime；普通班次清除 actualEndTime */
    fun unfillMissedClockOut(date: String) = viewModelScope.launch {
        val rec = scheduleRepo.getByDate(date) ?: return@launch
        val applied = rec.appliedStatus
        val shift = rec.shiftId?.let { id -> shiftRepo.getById(id) }
        val isRestSwap = shift?.builtIn == true && (shift.builtInType == "rest" || shift.builtInType == "swap")
        val fillStatusTime = applied != null && (
            applied.statusId == BUILTIN_STATUS_LEAVE || applied.statusId == BUILTIN_STATUS_SWAP ||
            (applied.countsAsOvertime && isRestSwap)
        )
        if (fillStatusTime) {
            scheduleRepo.save(rec.copy(appliedStatus = applied.copy(endTime = null)))
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
        // 单事务批量删除：原先逐日 delete 会产生 N 次写事务 + N 次 refreshSignal
        //（触发 N 轮小组件同步与提醒重排），且中途失败会留下部分删除
        scheduleRepo.deleteMany(sel.toList())
        exitAllModes()
        _uiEvent.send(CalendarUiEvent.ShowMessage("已清除 ${sel.size} 天排班"))
        loadCurrentMonth()
    }

    /** 取消全选 */
    fun batchClearSelection() {
        _state.update { it.copy(batchSelected = emptySet()) }
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
                dates.add(DateFormats.date(current))
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
            val dstDateStr = DateFormats.date(dstDate)
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
            val srcRecords = scheduleRepo.getByMonth(DateFormats.yearMonth(srcYear, srcMonth))
            if (srcRecords.isEmpty()) {
                _uiEvent.send(CalendarUiEvent.ShowMessage("来源月份无排班数据"))
                return@launch
            }
            val dstExisting = scheduleRepo.getByMonth(DateFormats.yearMonth(dstYear, dstMonth))
                .associateBy { it.date }.toMutableMap()
            val srcDays  = getDaysInMonth(srcYear, srcMonth)
            val dstDays  = getDaysInMonth(dstYear, dstMonth)
            val results  = mutableListOf<ScheduleRecord>()
            for (d in 1..minOf(srcDays, dstDays)) {
                val srcDate = DateFormats.date(srcYear, srcMonth, d)
                val dstDate = DateFormats.date(dstYear, dstMonth, d)
                if (!overwrite && dstExisting.containsKey(dstDate)) continue
                val src = srcRecords.find { it.date == srcDate } ?: continue
                results.add(src.copy(date = dstDate))
            }
            if (results.isEmpty()) {
                _uiEvent.send(CalendarUiEvent.ShowMessage("目标月份已有排班，无可复制内容"))
                return@launch
            }
            scheduleRepo.saveAll(results)
            val truncatedDays = (srcDays - dstDays).coerceAtLeast(0)
            val msg = buildString {
                append("已复制 ${results.size} 天排班至 ${dstYear}年${dstMonth}月")
                // 31 天复制到 2 月会静默丢 3 天，必须让用户知道
                if (overwrite && truncatedDays > 0) append("（目标月较短，源月有 $truncatedDays 天未复制）")
            }
            _uiEvent.send(CalendarUiEvent.ShowMessage(msg))
            // 如果复制到当前显示月，刷新
            if (dstYear == _state.value.year && dstMonth == _state.value.month) loadCurrentMonth()
        }

    private fun getDaysInMonth(year: Int, month: Int): Int =
        YearMonth.of(year, month).lengthOfMonth()
}
