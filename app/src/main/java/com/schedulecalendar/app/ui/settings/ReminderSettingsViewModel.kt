package com.schedulecalendar.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.schedulecalendar.app.data.prefs.AppPreferences
import com.schedulecalendar.app.reminder.ReminderScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 提醒设置状态
 */
data class ReminderSettingsState(
    val enabled: Boolean = false,
    val method: String = "alarm",            // "alarm" 或 "calendar"
    val reminderClockIn: Boolean = true,     // 提醒上班
    val reminderClockOut: Boolean = false,   // 提醒下班
    val clockInAdvanceMinutes: Int = 15,     // 上班提前分钟
    val clockOutAdvanceMinutes: Int = 0      // 下班提前分钟
)

/**
 * 提前时间预设选项（分钟），-1 表示自定义
 */
val ADVANCE_TIME_OPTIONS = listOf(15, 30, 60, -1)

/**
 * 上下班提醒设置 ViewModel
 * 管理提醒配置的读取、保存和触发
 */
@HiltViewModel
class ReminderSettingsViewModel @Inject constructor(
    private val prefs: AppPreferences,
    private val scheduler: ReminderScheduler
) : ViewModel() {

    private val _state = MutableStateFlow(ReminderSettingsState())
    val state: StateFlow<ReminderSettingsState> = _state.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            val enabled = prefs.getReminderEnabled()
            val method = prefs.getReminderMethod()
            val clockIn = prefs.getReminderClockIn()
            val clockOut = prefs.getReminderClockOut()
            val clockInMin = prefs.getReminderClockInMinutes()
            val clockOutMin = prefs.getReminderClockOutMinutes()

            _state.update {
                ReminderSettingsState(
                    enabled = enabled,
                    method = method,
                    reminderClockIn = clockIn,
                    reminderClockOut = clockOut,
                    clockInAdvanceMinutes = clockInMin,
                    clockOutAdvanceMinutes = clockOutMin
                )
            }
        }
    }

    fun toggleEnabled() {
        viewModelScope.launch {
            val newEnabled = !_state.value.enabled
            prefs.saveReminderEnabled(newEnabled)
            _state.update { it.copy(enabled = newEnabled) }
            rescheduleReminders()
        }
    }

    fun setMethod(method: String) {
        viewModelScope.launch {
            prefs.saveReminderMethod(method)
            _state.update { it.copy(method = method) }
            rescheduleReminders()
        }
    }

    fun toggleClockIn() {
        viewModelScope.launch {
            val newValue = !_state.value.reminderClockIn
            prefs.saveReminderClockIn(newValue)
            _state.update { it.copy(reminderClockIn = newValue) }
            rescheduleReminders()
        }
    }

    fun toggleClockOut() {
        viewModelScope.launch {
            val newValue = !_state.value.reminderClockOut
            prefs.saveReminderClockOut(newValue)
            _state.update { it.copy(reminderClockOut = newValue) }
            rescheduleReminders()
        }
    }

    fun setClockInAdvanceMinutes(minutes: Int) {
        viewModelScope.launch {
            prefs.saveReminderClockInMinutes(minutes)
            _state.update { it.copy(clockInAdvanceMinutes = minutes) }
            rescheduleReminders()
        }
    }

    fun setClockOutAdvanceMinutes(minutes: Int) {
        viewModelScope.launch {
            prefs.saveReminderClockOutMinutes(minutes)
            _state.update { it.copy(clockOutAdvanceMinutes = minutes) }
            rescheduleReminders()
        }
    }

    private fun rescheduleReminders() {
        viewModelScope.launch {
            scheduler.scheduleUpcomingReminders()
        }
    }
}
