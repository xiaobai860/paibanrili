// app/src/main/java/com/schedulecalendar/app/ui/detail/DetailViewModels.kt
package com.schedulecalendar.app.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.schedulecalendar.app.data.prefs.AppPreferences
import com.schedulecalendar.app.data.repository.ExtraItemRepository
import com.schedulecalendar.app.data.repository.ScheduleRepository
import com.schedulecalendar.app.data.repository.ShiftBreakRepository
import com.schedulecalendar.app.data.repository.ShiftRepository
import com.schedulecalendar.app.data.repository.ShiftStatusRepository
import com.schedulecalendar.app.domain.model.*
import com.schedulecalendar.app.ui.navigation.RouteHoursDetail
import com.schedulecalendar.app.ui.navigation.RouteScheduleDetail
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.time.LocalDate

// ── ScheduleDetail ViewModel ──────────────────────────────────────────────────

sealed class ScheduleDetailUiEvent {
    object NavigateBack                   : ScheduleDetailUiEvent()
    data class ShowError(val msg: String) : ScheduleDetailUiEvent()
}

data class ScheduleDetailState(
    val date: String                      = "",
    val shifts: List<Shift>               = emptyList(),
    val shiftStatuses: List<ShiftStatus>  = emptyList(),
    val extraItems: List<ExtraItem>       = emptyList(),
    val globalBreaks: List<ShiftBreak>    = emptyList(),
    val record: ScheduleRecord?           = null,
    /** 实时工时预览 */
    val previewHours: Double              = 0.0,
    /** 自动模式下的计薪方式标签（“工作日”/“周末”/“节假日”） */
    val autoModeLabel: String             = "",
    val loading: Boolean                  = true
)

@HiltViewModel
class ScheduleDetailViewModel @Inject constructor(
    private val shiftRepo: ShiftRepository,
    private val scheduleRepo: ScheduleRepository,
    private val shiftStatusRepo: ShiftStatusRepository,
    private val extraRepo: ExtraItemRepository,
    private val breakRepo: ShiftBreakRepository,
    private val prefs: AppPreferences,
    savedState: SavedStateHandle
) : ViewModel() {

    private val date     = savedState.toRoute<RouteScheduleDetail>().date
    private val _state   = MutableStateFlow(ScheduleDetailState(date = date))
    val state            = _state.asStateFlow()

    private val _uiEvent = Channel<ScheduleDetailUiEvent>(Channel.BUFFERED)
    val uiEvent          = _uiEvent.receiveAsFlow()

    init { load() }

    private fun load() = viewModelScope.launch {
        val rawShifts   = shiftRepo.getAllWithBuiltin()
        val rawStatuses = shiftStatusRepo.getAllWithBuiltin()
        val extras    = extraRepo.getAll()
        val breaks    = breakRepo.getAll()
        val record    = scheduleRepo.getByDate(date)
        // 过滤已归档的班次和状态，仅保留有效项
        val activeShifts   = rawShifts.filter { it.archivedAt == null }
        val activeStatuses = rawStatuses.filter { it.archivedAt == null }
        // 按用户自定义排序（与 ShiftsScreen / CalendarScreen 保持一致）
        val shiftOrder = prefs.getShiftOrder()
        val statusOrder = prefs.getStatusOrder()
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
        _state.update { it.copy(
            shifts        = shifts,
            shiftStatuses = shiftStatuses,
            extraItems    = extras,
            globalBreaks  = breaks,
            record        = record,
            loading       = false
        )}
        recalcPreview()
    }

    private fun recalcPreview() {
        val st = _state.value
        val rec = st.record ?: return
        viewModelScope.launch {
            val attendConfig = prefs.attendConfigFlow.first()
            val h = CalcUtils.calcDayHours(rec, date, st.shifts, st.globalBreaks, attendConfig)
            val autoMode = CalcUtils.autoSalaryMode(date)
            val label = when (autoMode) {
                SalaryMode.HOLIDAY -> "节假日"
                SalaryMode.WEEKEND -> "周末"
                SalaryMode.NORMAL  -> "工作日"
            }
            _state.update { it.copy(
                previewHours = CalcUtils.roundD2(h.normal + h.overtime + h.weekend + h.holiday),
                autoModeLabel = label
            ) }
        }
    }

    private fun updateRecord(block: ScheduleRecord.() -> ScheduleRecord) {
        _state.update { st ->
            val r = (st.record ?: ScheduleRecord(date)).block()
            st.copy(record = r)
        }
        recalcPreview()
    }

    fun setShift(shiftId: String?) = updateRecord { copy(shiftId = shiftId) }
    fun setActualStart(t: String)  = updateRecord { copy(actualStartTime = t.ifBlank { null }) }
    fun setActualEnd(t: String)    = updateRecord { copy(actualEndTime = t.ifBlank { null }) }
    fun setRemark(r: String)       = updateRecord { copy(remark = r.ifBlank { null }) }
    fun setSalaryMode(m: SalaryMode?) = updateRecord { copy(salaryMode = m) }
    fun setIgnoreEarlyArrival(v: Boolean) = updateRecord { copy(ignoreEarlyArrival = v) }
    fun setIgnoreLateLeave(v: Boolean)    = updateRecord { copy(ignoreLateLeave = v) }
    fun setConfirmEarlyOT(v: Boolean)     = updateRecord { copy(confirmEarlyOT = v) }
    fun setConfirmLateOT(v: Boolean)      = updateRecord { copy(confirmLateOT = v) }

    fun toggleStatus(statusId: String, startTime: String?, endTime: String?) {
        updateRecord {
            val existing = appliedStatus
            if (existing?.statusId == statusId) {
                // 取消选中
                copy(appliedStatus = null)
            } else {
                // 选中新状态（单选，替换已有）
                copy(appliedStatus = AppliedStatus(statusId, startTime, endTime))
            }
        }
    }

    fun updateStatusTime(statusId: String, startTime: String?, endTime: String?) {
        updateRecord {
            val existing = appliedStatus
            if (existing?.statusId == statusId) {
                copy(appliedStatus = existing.copy(startTime = startTime, endTime = endTime))
            } else {
                this
            }
        }
    }

    fun toggleExtraItem(extraId: String) {
        updateRecord {
            val ids = extraItemIds.toMutableList()
            if (extraId in ids) ids.remove(extraId) else ids.add(extraId)
            copy(extraItemIds = ids)
        }
    }

    fun save() = viewModelScope.launch {
        runCatching {
            val rec = _state.value.record ?: return@launch
            scheduleRepo.save(rec)
            _uiEvent.send(ScheduleDetailUiEvent.NavigateBack)
        }.onFailure {
            _uiEvent.send(ScheduleDetailUiEvent.ShowError("保存失败：${it.message}"))
        }
    }

    fun deleteRecord() = viewModelScope.launch {
        runCatching {
            scheduleRepo.delete(date)
            _state.update { it.copy(record = null, previewHours = 0.0) }
            _uiEvent.send(ScheduleDetailUiEvent.NavigateBack)
        }.onFailure {
            _uiEvent.send(ScheduleDetailUiEvent.ShowError("删除失败：${it.message}"))
        }
    }
}

// ── HoursDetail ViewModel ────────────────────────────────────────────────────

/** 工时明细类型 */
enum class HoursDetailType(val label: String) {
    ALL("完整工时"),
    LATE("迟到记录"),
    EARLY("早退记录"),
    REMARK("日程备注"),
    MISSED("漏打卡"),
    EXTRA("补贴扣款")
}

/** 单条工时明细展示数据 */
data class HoursDetailItem(
    val date: String,
    val shiftName: String?,
    val shiftColor: String?,
    /** 主要文字（根据类型不同含义不同） */
    val primaryText: String,
    /** 次要文字 */
    val secondaryText: String = "",
    /** 高亮值（迟到/早退时长等） */
    val highlightText: String = "",
    val isAlert: Boolean = false
)

data class HoursDetailState(
    val year: Int                          = 0,
    val month: Int                         = 0,
    val type: HoursDetailType              = HoursDetailType.ALL,
    val items: List<HoursDetailItem>       = emptyList(),
    val loading: Boolean                   = true
)

@HiltViewModel
class HoursDetailViewModel @Inject constructor(
    private val shiftRepo: ShiftRepository,
    private val scheduleRepo: ScheduleRepository,
    private val breakRepo: ShiftBreakRepository,
    private val shiftStatusRepo: ShiftStatusRepository,
    private val extraRepo: ExtraItemRepository,
    private val prefs: AppPreferences,
    savedState: SavedStateHandle
) : ViewModel() {

    private val year    = savedState.toRoute<RouteHoursDetail>().year
    private val month   = savedState.toRoute<RouteHoursDetail>().month
    private val typeStr = savedState.toRoute<RouteHoursDetail>().type
    private val detailType = when (typeStr) {
        "late"   -> HoursDetailType.LATE
        "early"  -> HoursDetailType.EARLY
        "remark" -> HoursDetailType.REMARK
        "missed" -> HoursDetailType.MISSED
        "extra"  -> HoursDetailType.EXTRA
        else     -> HoursDetailType.ALL
    }

    private val _state = MutableStateFlow(HoursDetailState(year, month, detailType))
    val state          = _state.asStateFlow()

    init { load() }

    private fun load() = viewModelScope.launch {
        val shifts      = shiftRepo.getAllWithBuiltin()
        val breaks      = breakRepo.getAll()
        val statuses    = shiftStatusRepo.getAllWithBuiltin()
        val extras      = extraRepo.getAll()
        val salaryConf  = prefs.salaryConfigFlow.first()
        val attendConf  = prefs.attendConfigFlow.first()
        val records     = scheduleRepo.getByMonth("%04d-%02d".format(year, month))
        val schedules   = records.associateBy { it.date }
        val details     = CalcUtils.getMonthScheduleDetails(year, month, schedules, shifts, breaks, extras, salaryConf, attendConf)

        val todayStr = "%04d-%02d-%02d".format(
            LocalDate.now().year, LocalDate.now().monthValue, LocalDate.now().dayOfMonth
        )

        val items = when (detailType) {
            HoursDetailType.LATE -> {
                details.filter { it.date <= todayStr && it.record != null }.mapNotNull { d ->
                    val rec = d.record ?: return@mapNotNull null
                    val shift = d.shift ?: return@mapNotNull null
                    val actualStart = rec.actualStartTime ?: return@mapNotNull null
                    val lateMinutes = calcLateMinutes(shift.startTime, actualStart, attendConf.lateToleranceMin)
                    if (lateMinutes <= 0) return@mapNotNull null
                    HoursDetailItem(
                        date = d.date,
                        shiftName = shift.name, shiftColor = shift.color,
                        primaryText = "打卡 $actualStart（计划 ${shift.startTime}）",
                        highlightText = "迟到 ${lateMinutes}分钟",
                        isAlert = lateMinutes >= (attendConf.lateAlertCount * attendConf.lateToleranceMin)
                    )
                }
            }
            HoursDetailType.EARLY -> {
                details.filter { it.date <= todayStr && it.record != null }.mapNotNull { d ->
                    val rec = d.record ?: return@mapNotNull null
                    val shift = d.shift ?: return@mapNotNull null
                    val actualEnd = rec.actualEndTime ?: return@mapNotNull null
                    val earlyMinutes = calcEarlyMinutes(shift.endTime, actualEnd, attendConf.earlyLeaveToleranceMin)
                    if (earlyMinutes <= 0) return@mapNotNull null
                    HoursDetailItem(
                        date = d.date,
                        shiftName = shift.name, shiftColor = shift.color,
                        primaryText = "打卡 $actualEnd（计划 ${shift.endTime}）",
                        highlightText = "早退 ${earlyMinutes}分钟",
                        isAlert = earlyMinutes >= (attendConf.earlyLeaveAlertCount * attendConf.earlyLeaveToleranceMin)
                    )
                }
            }
            HoursDetailType.REMARK -> {
                details.filter { it.date <= todayStr && !it.record?.remark.isNullOrBlank() }.map { d ->
                    HoursDetailItem(
                        date = d.date,
                        shiftName = d.shift?.name, shiftColor = d.shift?.color,
                        primaryText = d.record?.remark ?: ""
                    )
                }
            }
            HoursDetailType.MISSED -> {
                details.filter { it.date <= todayStr && it.record != null && it.shift != null }.mapNotNull { d ->
                    val rec = d.record ?: return@mapNotNull null
                    val shift = d.shift ?: return@mapNotNull null
                    if (shift.builtInType == "rest" || shift.builtInType == "swap") return@mapNotNull null
                    val missingStart = rec.actualStartTime == null
                    val missingEnd   = rec.actualEndTime == null
                    if (!missingStart && !missingEnd) return@mapNotNull null
                    val parts = buildList {
                        if (missingStart) add("上班未打卡")
                        if (missingEnd)   add("下班未打卡")
                    }
                    HoursDetailItem(
                        date = d.date,
                        shiftName = shift.name, shiftColor = shift.color,
                        primaryText = parts.joinToString("，"),
                        secondaryText = "班次 ${shift.startTime}～${shift.endTime}",
                        isAlert = true
                    )
                }
            }
            HoursDetailType.EXTRA -> {
                details.filter { it.extras.isNotEmpty() }.map { d ->
                    val subsidyTotal   = d.extras.filter { it.amount > 0 }.sumOf { it.amount }
                    val deductionTotal = d.extras.filter { it.amount < 0 }.sumOf { it.amount }
                    val parts = buildList {
                        if (subsidyTotal > 0)   add("补贴 +¥%.2f".format(subsidyTotal))
                        if (deductionTotal < 0) add("扣款 -¥%.2f".format(-deductionTotal))
                    }
                    HoursDetailItem(
                        date = d.date,
                        shiftName = d.shift?.name, shiftColor = d.shift?.color,
                        primaryText = parts.joinToString("，"),
                        secondaryText = d.extras.joinToString("、") { it.name },
                        highlightText = "共${d.extras.size}项"
                    )
                }
            }
            HoursDetailType.ALL -> {
                details.filter { it.record != null }.map { d ->
                    val total = d.normalHours + d.overtimeHours + d.weekendHours + d.holidayHours
                    HoursDetailItem(
                        date = d.date,
                        shiftName = d.shift?.name, shiftColor = d.shift?.color,
                        primaryText = if (total > 0) "工时 %.1fh".format(total) else d.record?.type?.name ?: "",
                        secondaryText = buildString {
                            if (d.overtimeHours > 0) append("加班%.1fh ".format(d.overtimeHours))
                            if (d.record?.remark?.isNotBlank() == true) append("备注")
                        },
                        highlightText = if (d.salary > 0) "¥%.0f".format(d.salary) else ""
                    )
                }
            }
        }

        _state.update { it.copy(items = items, loading = false) }
    }

    /** 计算迟到分钟数（超过容忍阈值才算），返回实际迟到分钟 */
    private fun calcLateMinutes(planStart: String, actualStart: String, toleranceMin: Int): Int {
        val p = planStart.toMinutes(); val a = actualStart.toMinutes()
        val diff = a - p
        return if (diff >= toleranceMin) diff else 0
    }

    /** 计算早退分钟数 */
    private fun calcEarlyMinutes(planEnd: String, actualEnd: String, toleranceMin: Int): Int {
        val p = planEnd.toMinutes(); val a = actualEnd.toMinutes()
        val diff = p - a
        return if (diff >= toleranceMin) diff else 0
    }

    private fun String.toMinutes(): Int {
        val parts = split(":")
        return (parts.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (parts.getOrNull(1)?.toIntOrNull() ?: 0)
    }
}

// ── ExtraItems ViewModel ──────────────────────────────────────────────────────

sealed class ExtraItemsUiEvent {
    data class ShowError(val msg: String) : ExtraItemsUiEvent()
}

@HiltViewModel
class ExtraItemsViewModel @Inject constructor(
    private val repo: ExtraItemRepository,
    private val prefs: AppPreferences
) : ViewModel() {

    /** UI 只显示有效（未归档）的项目 */
    val items: StateFlow<List<ExtraItem>> = repo.observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _uiEvent = Channel<ExtraItemsUiEvent>(Channel.BUFFERED)
    val uiEvent          = _uiEvent.receiveAsFlow()

    // ── 排序 ───────────────────────────────────────────
    private val _extraOrder = MutableStateFlow<List<String>>(emptyList())

    init {
        viewModelScope.launch {
            _extraOrder.value = prefs.getExtraOrder()
        }
    }

    val sortedItems: StateFlow<List<ExtraItem>> = items.combine(_extraOrder) { itemList, order ->
        if (order.isEmpty()) itemList
        else {
            val byId = itemList.associateBy { it.id }
            val ordered = order.mapNotNull { byId[it] }
            val remaining = itemList.filter { it.id !in order.toSet() }
            ordered + remaining
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun updateExtraOrder(newOrder: List<String>) {
        _extraOrder.value = newOrder
        viewModelScope.launch { prefs.saveExtraOrder(newOrder) }
    }

    fun moveExtraUp(id: String) {
        val current = sortedItems.value
        val idx = current.indexOfFirst { it.id == id }
        if (idx <= 0) return
        val mutable = current.toMutableList()
        mutable.swap(idx, idx - 1)
        updateExtraOrder(mutable.map { it.id })
    }

    fun moveExtraDown(id: String) {
        val current = sortedItems.value
        val idx = current.indexOfFirst { it.id == id }
        if (idx < 0 || idx >= current.size - 1) return
        val mutable = current.toMutableList()
        mutable.swap(idx, idx + 1)
        updateExtraOrder(mutable.map { it.id })
    }

    private fun <T> MutableList<T>.swap(i: Int, j: Int) {
        val tmp = this[i]
        this[i] = this[j]
        this[j] = tmp
    }

    /** 新增项目 */
    fun save(item: ExtraItem) = viewModelScope.launch {
        runCatching {
            repo.save(item)
            // 新增项目时置顶
            val currentOrder = _extraOrder.value
            if (item.id !in currentOrder) {
                val newOrder = listOf(item.id) + currentOrder
                _extraOrder.value = newOrder
                prefs.saveExtraOrder(newOrder)
            }
        }.onFailure { _uiEvent.send(ExtraItemsUiEvent.ShowError("\u4fdd\u5b58\u5931\u8d25\uff1a${it.message}")) }
    }

    /** 编辑项目：归档旧记录 + 创建新记录（新ID，保留历史金额） */
    fun saveAsReplacement(originalItem: ExtraItem, newItem: ExtraItem) = viewModelScope.launch {
        runCatching {
            repo.save(newItem)                          // 先创建新记录
            repo.archive(originalItem.id)               // 再归档旧记录
        }.onFailure { _uiEvent.send(ExtraItemsUiEvent.ShowError("\u4fdd\u5b58\u5931\u8d25\uff1a${it.message}")) }
    }

    /** 删除项目：归档（逻辑删除），保留历史引用 */
    fun delete(id: String) = viewModelScope.launch {
        runCatching { repo.archive(id) }
            .onFailure { _uiEvent.send(ExtraItemsUiEvent.ShowError("\u5220\u9664\u5931\u8d25\uff1a${it.message}")) }
    }
}

// ── DisplaySchemes ViewModel ──────────────────────────────────────────────────

@HiltViewModel
class DisplaySchemesViewModel @Inject constructor(
    private val prefs: AppPreferences
) : ViewModel() {

    val schemes: StateFlow<List<DisplayScheme>> = prefs.displaySchemesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(list: List<DisplayScheme>) = viewModelScope.launch { prefs.saveDisplaySchemes(list) }
    fun setActive(id: String) = viewModelScope.launch {
        val current = schemes.value
        val updated = current.map { it.copy(isActive = it.id == id) }
        // 确保目标方案被包含在持久化列表中（如注入的"预设方案"）
        val final = if (updated.any { it.id == id }) updated
                    else updated + DisplayScheme(id = id, name = "预设方案", isNoScheme = true, builtIn = true, isActive = true)
        prefs.saveDisplaySchemes(final)
    }
}
