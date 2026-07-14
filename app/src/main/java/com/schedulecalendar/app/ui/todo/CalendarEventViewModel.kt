package com.schedulecalendar.app.ui.todo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.schedulecalendar.app.data.calendar.CalendarAccountInfo
import com.schedulecalendar.app.data.calendar.CalendarEventInfo
import com.schedulecalendar.app.data.calendar.CalendarEventRepository
import com.schedulecalendar.app.data.prefs.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 日程标签页状态
 */
data class CalendarEventState(
    val events: List<CalendarEventInfo> = emptyList(),
    val hasPermission: Boolean = false,
    val isLoading: Boolean = true,
    val selectedEvent: CalendarEventInfo? = null,
    val showEditDialog: Boolean = false,
    val showDeleteDialog: Boolean = false
)

/**
 * 日程事件 ViewModel
 * 管理系统日历事件的读取、编辑、删除和实时监听
 */
@HiltViewModel
class CalendarEventViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val calendarRepo: CalendarEventRepository,
    private val prefs: AppPreferences
) : ViewModel() {

    private val _state = MutableStateFlow(CalendarEventState())
    val state: StateFlow<CalendarEventState> = _state.asStateFlow()

    private val _accounts = MutableStateFlow<List<CalendarAccountInfo>>(emptyList())
    val accounts: StateFlow<List<CalendarAccountInfo>> = _accounts.asStateFlow()

    private var observer: android.database.ContentObserver? = null

    init {
        checkPermission()
        loadAccounts()
        if (_state.value.hasPermission) {
            loadEvents()
            startObserving()
        }
    }

    /**
     * 加载可用日历账户
     */
    fun loadAccounts() {
        _accounts.value = calendarRepo.getAllAccounts()
    }

    /**
     * 检查日历读取权限
     */
    fun checkPermission() {
        val has = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        _state.update { it.copy(hasPermission = has) }
    }

    /**
     * 加载所有日历事件
     */
    fun loadEvents() {
        if (!_state.value.hasPermission) return

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }

            try {
                // 查询所有可见事件（不限制时间范围）
                val allEvents = calendarRepo.getAllEvents()
                // 过滤已禁用账户的事件
                val disabledAccounts = prefs.getDisabledAccountIds()
                val events = if (disabledAccounts.isEmpty()) allEvents
                    else allEvents.filter { event ->
                        // 通过日历ID查找对应的账户，判断是否被禁用
                        !isAccountDisabled(event.calendarId, disabledAccounts)
                    }
                _state.update {
                    it.copy(events = events, isLoading = false)
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    /**
     * 开始监听系统日历变化
     */
    private fun startObserving() {
        observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                loadEvents()
            }
        }
        context.contentResolver.registerContentObserver(
            CalendarContract.Events.CONTENT_URI,
            true,
            observer!!
        )
    }

    override fun onCleared() {
        super.onCleared()
        observer?.let {
            context.contentResolver.unregisterContentObserver(it)
        }
    }

    /**
     * 选择一个事件查看详情
     */
    fun selectEvent(event: CalendarEventInfo) {
        _state.update {
            it.copy(selectedEvent = event, showEditDialog = true)
        }
    }

    /**
     * 关闭详情弹窗
     */
    fun dismissDetailDialog() {
        _state.update {
            it.copy(selectedEvent = null, showEditDialog = false)
        }
    }

    /**
     * 显示删除确认弹窗
     */
    fun showDeleteConfirm(event: CalendarEventInfo) {
        _state.update {
            it.copy(selectedEvent = event, showDeleteDialog = true, showEditDialog = false)
        }
    }

    /**
     * 关闭删除弹窗
     */
    fun dismissDeleteDialog() {
        _state.update {
            it.copy(selectedEvent = null, showDeleteDialog = false)
        }
    }

    /**
     * 创建新的日历事件
     */
    fun createEvent(
        title: String,
        description: String?,
        dtStart: Long,
        dtEnd: Long,
        allDay: Boolean = false,
        location: String? = null,
        calendarId: Long? = null,
        rrule: String? = null,
        reminderMinutes: Int? = null,
        colorHex: String? = null
    ): Boolean {
        val result = calendarRepo.createEvent(
            title, description, dtStart, dtEnd, allDay, location,
            calendarId, rrule, reminderMinutes, colorHex
        )
        if (result > 0) {
            loadEvents()
            return true
        }
        return false
    }

    /**
     * 更新日历事件
     */
    fun updateEvent(updatedEvent: CalendarEventInfo) {
        viewModelScope.launch {
            val success = calendarRepo.updateEvent(updatedEvent)
            if (success) {
                loadEvents()
            }
            _state.update {
                it.copy(showEditDialog = false, selectedEvent = null)
            }
        }
    }

    /**
     * 删除日历事件
     */
    fun deleteEvent(eventId: Long) {
        viewModelScope.launch {
            val success = calendarRepo.deleteEvent(eventId)
            if (success) {
                loadEvents()
            }
            _state.update {
                it.copy(showDeleteDialog = false, selectedEvent = null)
            }
        }
    }

    /**
     * 判断日历ID对应的账户是否被禁用
     * disabledAccountIds 存储的是日历ID（第一个关联日历的ID）
     */
    private fun isAccountDisabled(calendarId: Long, disabledIds: Set<Long>): Boolean {
        // 简单匹配：禁用ID列表中包含此日历ID
        return calendarId in disabledIds
    }
}
