package com.schedulecalendar.app.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.schedulecalendar.app.data.prefs.AppPreferences
import com.schedulecalendar.app.reminder.ReminderScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
    val clockOutAdvanceMinutes: Int = 0,     // 下班提前分钟
    val pendingCalendarPermission: Boolean = false // 是否需要请求日历权限
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
    @ApplicationContext private val context: Context,
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
        if (method == "calendar") {
            // 切换到日历提醒前检查权限
            val hasReadPermission = ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_CALENDAR
            ) == PackageManager.PERMISSION_GRANTED
            val hasWritePermission = ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_CALENDAR
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasReadPermission || !hasWritePermission) {
                // 标记需要请求权限，等权限授予后再保存
                _state.update { it.copy(pendingCalendarPermission = true) }
                return
            }
        }
        // 权限已授予或非日历模式，直接保存
        saveMethod(method)
    }

    /**
     * 日历权限已授予后调用，保存方法并重新调度
     */
    fun onCalendarPermissionGranted() {
        _state.update { it.copy(pendingCalendarPermission = false) }
        saveMethod("calendar")
    }

    /**
     * 日历权限被拒绝，取消切换
     */
    fun onCalendarPermissionDenied() {
        _state.update { it.copy(pendingCalendarPermission = false) }
    }

    private fun saveMethod(method: String) {
        viewModelScope.launch {
            val oldMethod = _state.value.method
            prefs.saveReminderMethod(method)
            _state.update { it.copy(method = method) }
            // 切换为闹钟模式时立即清理日历事件
            if (oldMethod == "calendar" && method == "alarm") {
                scheduler.forceCleanupCalendarReminders()
            }
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
