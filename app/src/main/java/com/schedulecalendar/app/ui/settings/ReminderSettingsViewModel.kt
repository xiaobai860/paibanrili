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
    val notifyBar: Boolean = true,           // 通知栏提醒开关（默认开启）
    val notifyBarLocked: Boolean = false,     // 总开关开启时通知栏提醒被强制锁定为开启
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
            // 总开关关闭时通知栏提醒可通过自身开关单独控制；总开关开启时强制开启且锁定
            val notifyBarLocked = enabled
            val notifyBar = if (notifyBarLocked) true else prefs.getReminderNotifyBar()

            _state.update {
                ReminderSettingsState(
                    enabled = enabled,
                    method = method,
                    reminderClockIn = clockIn,
                    reminderClockOut = clockOut,
                    clockInAdvanceMinutes = clockInMin,
                    clockOutAdvanceMinutes = clockOutMin,
                    notifyBar = notifyBar,
                    notifyBarLocked = notifyBarLocked
                )
            }
        }
    }

    fun toggleEnabled() {
        viewModelScope.launch {
            val newEnabled = !_state.value.enabled
            prefs.saveReminderEnabled(newEnabled)
            if (newEnabled) {
                // 开启总开关后，通知栏提醒默认开启且不可关闭
                prefs.saveReminderNotifyBar(true)
                _state.update { it.copy(enabled = newEnabled, notifyBar = true, notifyBarLocked = true) }
            } else {
                _state.update { it.copy(enabled = newEnabled, notifyBarLocked = false) }
            }
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
                _state.update { it.copy(pendingCalendarPermission = true) }
                return
            }
        }
        viewModelScope.launch {
            prefs.saveReminderMethod(method)
            _state.update { it.copy(method = method) }
        }
    }

    /**
     * 日历权限已授予后调用，保存方法设置
     */
    fun onCalendarPermissionGranted() {
        _state.update { it.copy(pendingCalendarPermission = false) }
        viewModelScope.launch {
            prefs.saveReminderMethod("calendar")
            _state.update { it.copy(method = "calendar") }
        }
    }

    /**
     * 日历权限被拒绝，取消切换
     */
    fun onCalendarPermissionDenied() {
        _state.update { it.copy(pendingCalendarPermission = false) }
    }

    fun toggleClockIn() {
        viewModelScope.launch {
            val newValue = !_state.value.reminderClockIn
            prefs.saveReminderClockIn(newValue)
            _state.update { it.copy(reminderClockIn = newValue) }
        }
    }

    fun toggleClockOut() {
        viewModelScope.launch {
            val newValue = !_state.value.reminderClockOut
            prefs.saveReminderClockOut(newValue)
            _state.update { it.copy(reminderClockOut = newValue) }
        }
    }

    fun setClockInAdvanceMinutes(minutes: Int) {
        viewModelScope.launch {
            prefs.saveReminderClockInMinutes(minutes)
            _state.update { it.copy(clockInAdvanceMinutes = minutes) }
        }
    }

    fun setClockOutAdvanceMinutes(minutes: Int) {
        viewModelScope.launch {
            prefs.saveReminderClockOutMinutes(minutes)
            _state.update { it.copy(clockOutAdvanceMinutes = minutes) }
        }
    }

    fun toggleNotifyBar() {
        // 总开关开启时通知栏提醒被锁定，不允许切换
        if (_state.value.notifyBarLocked) return
        viewModelScope.launch {
            val newVal = !_state.value.notifyBar
            prefs.saveReminderNotifyBar(newVal)
            _state.update { it.copy(notifyBar = newVal) }
        }
    }

    /**
     * 退出页面时统一应用：根据当前设置执行一次调度
     * - 总开关关闭 → 取消所有提醒
     * - 闹钟模式 → 清理日历事件 + 重新设置闹钟
     * - 日历模式 → 重新创建日历提醒事件
     */
    fun applyChanges() {
        viewModelScope.launch {
            val s = _state.value
            if (!s.enabled) {
                scheduler.cancelAllReminders()
                scheduler.forceCleanupCalendarReminders()
            } else if (s.method == "alarm") {
                // 切换为闹钟模式时清理日历事件
                scheduler.forceCleanupCalendarReminders()
                scheduler.scheduleUpcomingReminders()
            } else {
                scheduler.scheduleUpcomingReminders()
            }
        }
    }
}
