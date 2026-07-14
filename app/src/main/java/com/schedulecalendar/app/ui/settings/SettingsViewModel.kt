// app/src/main/java/com/schedulecalendar/app/ui/settings/SettingsViewModel.kt
package com.schedulecalendar.app.ui.settings

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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 设置页一次性 UI 事件 */
sealed class SettingsUiEvent {
    object ShowClearConfirm               : SettingsUiEvent()
    object DataCleared                    : SettingsUiEvent()
    data class ShowError(val msg: String) : SettingsUiEvent()
}

data class SettingsUiState(
    val salaryConfig: SalaryConfig          = SalaryConfig(),
    val attendConfig: AttendConfig          = AttendConfig(),
    val scheduleRule: ScheduleRule?         = null,
    val displaySchemes: List<DisplayScheme> = listOf(DisplayScheme()),
    val loading: Boolean                    = true
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: AppPreferences,
    private val shiftRepo: ShiftRepository,
    private val scheduleRepo: ScheduleRepository,
    private val extraItemRepo: ExtraItemRepository,
    private val breakRepo: ShiftBreakRepository,
    private val statusRepo: ShiftStatusRepository
) : ViewModel() {

    private val _state   = MutableStateFlow(SettingsUiState())
    val state            = _state.asStateFlow()

    val shifts: StateFlow<List<Shift>> = shiftRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _uiEvent = Channel<SettingsUiEvent>(Channel.BUFFERED)
    val uiEvent          = _uiEvent.receiveAsFlow()

    init {
        viewModelScope.launch {
            combine(
                prefs.salaryConfigFlow,
                prefs.attendConfigFlow,
                prefs.scheduleRuleFlow,
                prefs.displaySchemesFlow
            ) { sc, ac, rule, schemes ->
                SettingsUiState(sc, ac, rule, schemes, false)
            }.collect { _state.value = it }
        }
    }

    fun saveSalaryConfig(cfg: SalaryConfig)            = viewModelScope.launch { prefs.saveSalaryConfig(cfg) }
    fun saveAttendConfig(cfg: AttendConfig)            = viewModelScope.launch { prefs.saveAttendConfig(cfg) }
    fun saveScheduleRule(rule: ScheduleRule?)          = viewModelScope.launch { prefs.saveScheduleRule(rule) }
    fun saveDisplaySchemes(schemes: List<DisplayScheme>) = viewModelScope.launch { prefs.saveDisplaySchemes(schemes) }

    /** 显示清空确认对话框（由 Screen 处理跳转） */
    fun requestClearAll() = viewModelScope.launch { _uiEvent.send(SettingsUiEvent.ShowClearConfirm) }

    /** 确认后执行清空 */
    fun clearAllData() {
        viewModelScope.launch {
            runCatching {
                shiftRepo.deleteAll()
                scheduleRepo.deleteAll()
                extraItemRepo.deleteAll()
                breakRepo.deleteAll()
                statusRepo.deleteAllUserDefined()
                prefs.clearAll()
                _uiEvent.send(SettingsUiEvent.DataCleared)
            }.onFailure {
                _uiEvent.send(SettingsUiEvent.ShowError("清空失败：${it.message}"))
            }
        }
    }
}
