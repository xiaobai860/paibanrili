// app/src/main/java/com/schedulecalendar/app/ui/shifts/ShiftsViewModel.kt
package com.schedulecalendar.app.ui.shifts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.schedulecalendar.app.data.prefs.AppPreferences
import com.schedulecalendar.app.data.repository.CalcProfileRepository
import com.schedulecalendar.app.data.repository.ExtraItemRepository
import com.schedulecalendar.app.data.repository.ShiftBreakRepository
import com.schedulecalendar.app.data.repository.ShiftRepository
import com.schedulecalendar.app.data.repository.ShiftStatusRepository
import com.schedulecalendar.app.domain.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.yield
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

// ── ShiftsViewModel ───────────────────────────────────────────────────────────

sealed class ShiftsUiEvent {
    data class NavigateToEditor(val shiftId: String?)  : ShiftsUiEvent()
    data class ShowDeleteConfirm(val shiftId: String)  : ShiftsUiEvent()
    data class ShowMessage(val msg: String)             : ShiftsUiEvent()
    data class ShowError(val msg: String)               : ShiftsUiEvent()
}

@HiltViewModel
class ShiftsViewModel @Inject constructor(
    private val shiftRepo: ShiftRepository,
    breakRepo: ShiftBreakRepository,
    private val statusRepo: ShiftStatusRepository,
    private val backupManager: com.schedulecalendar.app.ui.settings.BackupManager,
    private val prefs: AppPreferences,
    private val profileRepo: CalcProfileRepository
) : ViewModel() {

    val shifts: StateFlow<List<Shift>> = shiftRepo.observeAllWithBuiltin()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // 内存中的排序顺序（即时响应，无需等待 DataStore 读取）
    private val _shiftOrder = MutableStateFlow<List<String>>(emptyList())

    init {
        viewModelScope.launch {
            _shiftOrder.value = prefs.getShiftOrder()
            yield()
            _statusOrder.value = prefs.getStatusOrder()
            _breakOrder.value = prefs.getBreakOrder()
        }
        // 当班次列表变化时重新加载排序（覆盖 ShiftEditorViewModel 保存后的情况）
        viewModelScope.launch {
            shifts.drop(1).collect {
                _shiftOrder.value = prefs.getShiftOrder()
            }
        }
    }

    // 按用户排序后的班次列表（基于内存状态即时更新）
    val sortedShifts: StateFlow<List<Shift>> = shifts.combine(_shiftOrder) { shiftList, order ->
        if (order.isEmpty()) shiftList
        else {
            val byId = shiftList.associateBy { it.id }
            val ordered = order.mapNotNull { byId[it] }
            val remaining = shiftList.filter { it.id !in order.toSet() }
            ordered + remaining
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val globalBreaks: StateFlow<List<ShiftBreak>> = breakRepo.observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // 内存中的休息时段排序顺序
    private val _breakOrder = MutableStateFlow<List<String>>(emptyList())

    // 按用户排序后的休息时段列表
    val sortedBreaks: StateFlow<List<ShiftBreak>> = globalBreaks.combine(_breakOrder) { breakList, order ->
        if (order.isEmpty()) breakList
        else {
            val byId = breakList.associateBy { it.id }
            val ordered = order.mapNotNull { byId[it] }
            val remaining = breakList.filter { it.id !in order.toSet() }
            ordered + remaining
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val statuses: StateFlow<List<ShiftStatus>> = statusRepo.observeAllWithBuiltin()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // 内存中的状态排序顺序
    private val _statusOrder = MutableStateFlow<List<String>>(emptyList())

    // 按用户排序后的状态列表
    val sortedStatuses: StateFlow<List<ShiftStatus>> = statuses.combine(_statusOrder) { statusList, order ->
        if (order.isEmpty()) statusList
        else {
            val byId = statusList.associateBy { it.id }
            val ordered = order.mapNotNull { byId[it] }
            val remaining = statusList.filter { it.id !in order.toSet() }
            ordered + remaining
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _uiEvent = Channel<ShiftsUiEvent>(Channel.BUFFERED)
    val uiEvent          = _uiEvent.receiveAsFlow()

    // 状态预设颜色索引
    private val _statusColorIndex = MutableStateFlow(0)
    val statusColorIndex: StateFlow<Int> = _statusColorIndex.asStateFlow()

    init {
        viewModelScope.launch {
            _statusColorIndex.value = prefs.getStatusColorIndex()
        }
    }

    fun onAddClick()              = viewModelScope.launch { _uiEvent.send(ShiftsUiEvent.NavigateToEditor(null)) }
    fun onEditClick(id: String)   = viewModelScope.launch { _uiEvent.send(ShiftsUiEvent.NavigateToEditor(id)) }
    fun onDeleteClick(id: String) = viewModelScope.launch { _uiEvent.send(ShiftsUiEvent.ShowDeleteConfirm(id)) }
    fun deleteShift(id: String) = viewModelScope.launch {
        shiftRepo.archive(id)
        backupManager.autoBackupShiftConfig()
    }

    // ── 班次排序（即时响应 + 后台持久化）──────────────────────
    private fun updateOrder(newOrder: List<String>) {
        _shiftOrder.value = newOrder
        viewModelScope.launch { prefs.saveShiftOrder(newOrder) }
    }

    /** 上移班次 */
    fun moveShiftUp(id: String) {
        val current = sortedShifts.value
        val idx = current.indexOfFirst { it.id == id }
        if (idx <= 0) return
        val mutable = current.toMutableList()
        mutable.swap(idx, idx - 1)
        updateOrder(mutable.map { it.id })
    }

    /** 下移班次 */
    fun moveShiftDown(id: String) {
        val current = sortedShifts.value
        val idx = current.indexOfFirst { it.id == id }
        if (idx < 0 || idx >= current.size - 1) return
        val mutable = current.toMutableList()
        mutable.swap(idx, idx + 1)
        updateOrder(mutable.map { it.id })
    }

    private fun <T> MutableList<T>.swap(i: Int, j: Int) {
        val tmp = this[i]
        this[i] = this[j]
        this[j] = tmp
    }

    // ── 全局不计时段 CRUD ──────────────────────────────────────────
    /**
     * 保存休息时段。
     *
     * 实际落库与「生成新口径」的联动在 [CalcProfileRepository.saveBreakWithProfile] 里
     * （先建档后落库；建档失败抛异常 → 本次修改撤销）。
     *
     * ⚠️ 休息时段**没有引用锚点**，编辑时原地更新（id 不变），
     * 不做「归档旧记录 + 新 UUID」——那样每次编辑都会留下一条归档垃圾。
     */
    fun saveBreak(item: ShiftBreak) = viewModelScope.launch {
        val today = com.schedulecalendar.app.domain.util.DateFormats.date(java.time.LocalDate.now())
        runCatching {
            profileRepo.saveBreakWithProfile(
                item = item,
                today = today,
                profileName = "${today.take(7)} 时段调整"
            )
        }.onFailure {
            _uiEvent.send(ShiftsUiEvent.ShowError("保存失败：无法生成新口径，本次修改已撤销"))
            return@launch
        }
        // 新增项目时置顶（id 不变 → 排序列表无需改动）
        val currentOrder = _breakOrder.value
        if (item.id !in currentOrder) {
            val newOrder = listOf(item.id) + currentOrder
            _breakOrder.value = newOrder
            prefs.saveBreakOrder(newOrder)
        }
        backupManager.autoBackupShiftConfig()
        _uiEvent.send(ShiftsUiEvent.ShowMessage("已生成新口径（自 $today 起生效），历史月份仍按原时段计算"))
    }
    fun deleteBreak(id: String) = viewModelScope.launch {
        val today = com.schedulecalendar.app.domain.util.DateFormats.date(java.time.LocalDate.now())
        // 建档 + 归档落库都在仓库层：建档失败会抛异常 → 本次删除撤销
        runCatching {
            profileRepo.deleteBreakWithProfile(
                id = id,
                today = today,
                profileName = "${today.take(7)} 时段调整"
            )
        }.onFailure {
            _uiEvent.send(ShiftsUiEvent.ShowError("删除失败：无法生成新口径，本次删除已撤销"))
            return@launch
        }
        backupManager.autoBackupShiftConfig()
        _uiEvent.send(ShiftsUiEvent.ShowMessage("已生成新口径（自 $today 起生效），历史月份仍按原时段计算"))
    }

    // ── 状态类型 CRUD ──────────────────────────────────────────────
    fun saveStatus(item: ShiftStatus) = viewModelScope.launch {
        // 编辑时：归档旧记录 + 创建新ID的新记录
        val existing = statusRepo.getAll().find { it.id == item.id }
        val isEdit = existing != null && !existing.builtIn
        val newItem = if (isEdit) item.copy(id = UUID.randomUUID().toString()) else item
        if (isEdit) {
            statusRepo.archive(existing.id)
        }
        statusRepo.save(newItem)
        // 新增项目时置顶
        val currentOrder = _statusOrder.value
        if (newItem.id !in currentOrder) {
            val newOrder = listOf(newItem.id) + currentOrder
            _statusOrder.value = newOrder
            prefs.saveStatusOrder(newOrder)
            // 递增状态预设颜色索引
            val idx = _statusColorIndex.value
            val newIdx = (idx + 1) % com.schedulecalendar.app.ui.theme.ShiftPresetColors.size
            _statusColorIndex.value = newIdx
            prefs.saveStatusColorIndex(newIdx)
        }
        backupManager.autoBackupShiftConfig()
    }
    fun deleteStatus(id: String) = viewModelScope.launch {
        statusRepo.archive(id)
        backupManager.autoBackupShiftConfig()
    }

    // ── 状态排序 ───────────────────────────────────────────────────
    private fun updateStatusOrder(newOrder: List<String>) {
        _statusOrder.value = newOrder
        viewModelScope.launch { prefs.saveStatusOrder(newOrder) }
    }

    fun moveStatusUp(id: String) {
        val current = sortedStatuses.value
        val idx = current.indexOfFirst { it.id == id }
        if (idx <= 0) return
        val mutable = current.toMutableList()
        mutable.swap(idx, idx - 1)
        updateStatusOrder(mutable.map { it.id })
    }

    fun moveStatusDown(id: String) {
        val current = sortedStatuses.value
        val idx = current.indexOfFirst { it.id == id }
        if (idx < 0 || idx >= current.size - 1) return
        val mutable = current.toMutableList()
        mutable.swap(idx, idx + 1)
        updateStatusOrder(mutable.map { it.id })
    }

    // ── 休息时段排序 ───────────────────────────────────────────────
    private fun updateBreakOrder(newOrder: List<String>) {
        _breakOrder.value = newOrder
        viewModelScope.launch { prefs.saveBreakOrder(newOrder) }
    }

    fun moveBreakUp(id: String) {
        val current = sortedBreaks.value
        val idx = current.indexOfFirst { it.id == id }
        if (idx <= 0) return
        val mutable = current.toMutableList()
        mutable.swap(idx, idx - 1)
        updateBreakOrder(mutable.map { it.id })
    }

    fun moveBreakDown(id: String) {
        val current = sortedBreaks.value
        val idx = current.indexOfFirst { it.id == id }
        if (idx < 0 || idx >= current.size - 1) return
        val mutable = current.toMutableList()
        mutable.swap(idx, idx + 1)
        updateBreakOrder(mutable.map { it.id })
    }

    }

// ── ShiftEditorViewModel ──────────────────────────────────────────────────────

sealed class ShiftEditorUiEvent {
    object NavigateBack                   : ShiftEditorUiEvent()
    data class ShowError(val msg: String) : ShiftEditorUiEvent()
}

data class ShiftEditorState(
    val id: String               = UUID.randomUUID().toString(),
    val name: String             = "",
    val color: String            = "#059669",
    val startTime: String        = "",
    val endTime: String          = "",
    val normalWorkHours: String  = "8",
    val linkedExtraIds: List<String> = emptyList(),
    val allExtraItems: List<ExtraItem> = emptyList(),
    val allBreaks: List<ShiftBreak> = emptyList(),
    val errorMsg: String?        = null
)

@HiltViewModel
class ShiftEditorViewModel @Inject constructor(
    private val shiftRepo: ShiftRepository,
    private val extraRepo: ExtraItemRepository,
    private val breakRepo: ShiftBreakRepository,
    private val backupManager: com.schedulecalendar.app.ui.settings.BackupManager,
    private val prefs: AppPreferences
) : ViewModel() {

    private val _state   = MutableStateFlow(ShiftEditorState())
    val state            = _state.asStateFlow()

    private val _uiEvent = Channel<ShiftEditorUiEvent>(Channel.BUFFERED)
    val uiEvent          = _uiEvent.receiveAsFlow()

    init {
        viewModelScope.launch {
            val extras = extraRepo.getAll()
            val breaks = breakRepo.getAll()
            // 新建班次时按持久化索引选择预设颜色
            val colorIdx = prefs.getShiftColorIndex()
            val nextColor = com.schedulecalendar.app.ui.theme.ShiftPresetColors[colorIdx % com.schedulecalendar.app.ui.theme.ShiftPresetColors.size]
            _state.update { it.copy(allExtraItems = extras, allBreaks = breaks, color = nextColor) }
        }
    }

    fun load(shiftId: String) = viewModelScope.launch {
        val s = shiftRepo.getById(shiftId) ?: return@launch
        val extras = extraRepo.getAll()
        val breaks = breakRepo.getAll()
        _state.update {
            ShiftEditorState(
                id              = s.id,
                name            = s.name,
                color           = s.color,
                startTime       = s.startTime,
                endTime         = s.endTime,
                normalWorkHours = s.normalWorkHours.toString(),
                linkedExtraIds  = s.linkedExtraIds,
                allExtraItems   = extras,
                allBreaks       = breaks
            )
        }
    }

    fun update(block: ShiftEditorState.() -> ShiftEditorState) = _state.update { it.block() }

    fun toggleExtraLink(extraId: String) = _state.update { st ->
        val ids = st.linkedExtraIds.toMutableList()
        if (extraId in ids) ids.remove(extraId) else ids.add(extraId)
        st.copy(linkedExtraIds = ids)
    }

    fun save() {
        val s = _state.value
        if (s.name.isBlank()) {
            viewModelScope.launch { _uiEvent.send(ShiftEditorUiEvent.ShowError("班次名称不能为空")) }
            return
        }
        if (s.startTime.isBlank() || s.endTime.isBlank()) {
            viewModelScope.launch { _uiEvent.send(ShiftEditorUiEvent.ShowError("请输入完整的上下班时间")) }
            return
        }
        viewModelScope.launch {
            runCatching {
                // 防重名检查：仅与当前有效（未归档）的班次进行名称比对
                val allExisting = shiftRepo.getAllWithBuiltin()
                val duplicate = allExisting.any { it.archivedAt == null && it.id != s.id && it.name.equals(s.name, ignoreCase = true) }
                if (duplicate) {
                    _uiEvent.send(ShiftEditorUiEvent.ShowError("班次名称已存在，请修改后保存"))
                    return@launch
                }
                // 编辑时：归档旧记录 + 创建新ID的新记录
                val isEdit = shiftRepo.getAll().any { it.id == s.id && !it.builtIn }
                val newId = if (isEdit) UUID.randomUUID().toString() else s.id
                // 1) 先写排序到 DataStore
                val currentOrder = prefs.getShiftOrder()
                prefs.saveShiftOrder(listOf(newId) + currentOrder.filter { it != newId && it != s.id })
                // 2) 归档旧记录（触发 shifts flow）
                if (isEdit) {
                    shiftRepo.archive(s.id)
                }
                // 3) 保存新记录（触发 shifts flow）
                shiftRepo.save(Shift(
                    id              = newId,
                    name            = s.name,
                    color           = s.color,
                    startTime       = s.startTime,
                    endTime         = s.endTime,
                    normalWorkHours = s.normalWorkHours.toDoubleOrNull() ?: 8.0,
                    builtIn         = false,
                    linkedExtraIds  = s.linkedExtraIds
                ))
                // 递增班次预设颜色索引
                val colorIdx = prefs.getShiftColorIndex()
                prefs.saveShiftColorIndex((colorIdx + 1) % com.schedulecalendar.app.ui.theme.ShiftPresetColors.size)
                // 自动备份班次配置
                backupManager.autoBackupShiftConfig()
                _uiEvent.send(ShiftEditorUiEvent.NavigateBack)
            }.onFailure {
                _uiEvent.send(ShiftEditorUiEvent.ShowError("保存失败：${it.message}"))
            }
        }
    }
}
