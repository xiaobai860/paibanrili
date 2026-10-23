// app/src/main/java/com/schedulecalendar/app/ui/settings/SettingsViewModel.kt
package com.schedulecalendar.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.schedulecalendar.app.data.prefs.AppPreferences
import com.schedulecalendar.app.data.repository.CalcProfileRepository
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
    /** 已创建新的计算口径档案（历史计算不受影响） */
    data class ProfileCreated(val name: String, val effectiveFrom: String) : SettingsUiEvent()
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
    private val statusRepo: ShiftStatusRepository,
    private val profileRepo: CalcProfileRepository
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
        // 当前生效口径（展示用；保存新档案后刷新）
        viewModelScope.launch {
            _currentProfile.value = runCatching { profileRepo.getCurrentProfile() }.getOrNull()
        }
    }

    fun saveSalaryConfig(cfg: SalaryConfig)            = viewModelScope.launch { prefs.saveSalaryConfig(cfg) }
    fun saveAttendConfig(cfg: AttendConfig)            = viewModelScope.launch { prefs.saveAttendConfig(cfg) }

    // ── 计算口径档案（改配置不改历史）──────────────────────────────────────

    /** 当前生效的口径档案（设置页展示用） */
    private val _currentProfile = MutableStateFlow<CalcProfile?>(null)
    val currentProfile = _currentProfile.asStateFlow()

    /**
     * 保存薪资配置 → **生成新口径档案**（而非覆盖当前配置）。
     *
     * 档案生效日之前的历史排班继续用旧口径 → 历史工时/薪资不变。
     * 同时把新值写回 DataStore（保持「当前现值」与最新档案一致，也供无档案时回退）。
     */
    fun saveSalaryAsProfile(cfg: SalaryConfig, effectiveFrom: String) = viewModelScope.launch {
        runCatching {
            val name = profileName(effectiveFrom, "调薪")
            profileRepo.createDerivedProfile(
                effectiveFrom = effectiveFrom, name = name, salary = cfg
            )
            prefs.saveSalaryConfig(cfg)
            _currentProfile.value = profileRepo.getCurrentProfile()
            _uiEvent.send(SettingsUiEvent.ProfileCreated(name, effectiveFrom))
        }.onFailure {
            _uiEvent.send(SettingsUiEvent.ShowError("保存失败：${it.message}"))
        }
    }

    /** 保存考勤配置 → 生成新口径档案（语义同 [saveSalaryAsProfile]） */
    fun saveAttendAsProfile(cfg: AttendConfig, effectiveFrom: String) = viewModelScope.launch {
        runCatching {
            val name = profileName(effectiveFrom, "考勤调整")
            profileRepo.createDerivedProfile(
                effectiveFrom = effectiveFrom, name = name, attend = cfg
            )
            prefs.saveAttendConfig(cfg)
            _currentProfile.value = profileRepo.getCurrentProfile()
            _uiEvent.send(SettingsUiEvent.ProfileCreated(name, effectiveFrom))
        }.onFailure {
            _uiEvent.send(SettingsUiEvent.ShowError("保存失败：${it.message}"))
        }
    }

    /** 口径名自动生成：生效年月 + 变更摘要，如「2026-10 调薪」 */
    private fun profileName(effectiveFrom: String, tag: String): String {
        val ym = effectiveFrom.take(7)
        return "$ym $tag"
    }
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
