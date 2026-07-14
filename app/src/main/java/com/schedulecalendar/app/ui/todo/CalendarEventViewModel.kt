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
import android.util.Log
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    /** 原始事件列表（账户过滤后、纪念日过滤前，供纪念日标签页使用） */
    private val _rawEvents = MutableStateFlow<List<CalendarEventInfo>>(emptyList())

    /** 纪念日列表（FREQ=YEARLY 的循环事件） */
    val anniversaries: StateFlow<List<CalendarEventInfo>> = _rawEvents
        .let { src ->
            MutableStateFlow<List<CalendarEventInfo>>(emptyList()).also { flow ->
                viewModelScope.launch {
                    src.collect { events ->
                        flow.value = events.filter {
                            it.rrule?.contains("FREQ=YEARLY") == true
                        }
                    }
                }
            }
        }

    /** 按 ID 加载的单个事件（供编辑页面使用） */
    private val _singleEvent = MutableStateFlow<CalendarEventInfo?>(null)
    val singleEvent: StateFlow<CalendarEventInfo?> = _singleEvent.asStateFlow()

    private val _isSingleLoading = MutableStateFlow(true)
    val isSingleLoading: StateFlow<Boolean> = _isSingleLoading.asStateFlow()

    private var observer: android.database.ContentObserver? = null

    init {
        checkPermission()
        // 只在有权限时加载账户列表和事件
        if (_state.value.hasPermission) {
            viewModelScope.launch {
                val accountsList = try {
                    withContext(Dispatchers.IO) {
                        // 先确保应用日历账户已创建，避免竞态导致账户列表为空
                        calendarRepo.getOrCreateLocalCalendarId()
                        calendarRepo.getAllAccounts()
                    }
                } catch (e: SecurityException) {
                    Log.e("CalendarEventVM", "init loadAccounts SecurityException", e)
                    emptyList()
                }
                _accounts.value = accountsList
            }
            loadEvents()
            startObserving()
        }
    }

    /**
     * 加载可用日历账户（异步）
     * 添加权限检查，避免无权限时查询崩溃
     */
    fun loadAccounts() {
        if (!_state.value.hasPermission) {
            checkPermission()
            if (!_state.value.hasPermission) return
        }
        viewModelScope.launch {
            val accountsList = try {
                withContext(Dispatchers.IO) {
                    calendarRepo.getOrCreateLocalCalendarId()
                    calendarRepo.getAllAccounts()
                }
            } catch (e: SecurityException) {
                Log.e("CalendarEventVM", "loadAccounts SecurityException", e)
                emptyList()
            }
            _accounts.value = accountsList
        }
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
     * 添加权限检查和异常捕获，避免 SecurityException 导致闪退
     */
    fun loadEvents() {
        if (!_state.value.hasPermission) {
            checkPermission()
            if (!_state.value.hasPermission) return
        }

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }

            try {
                // 查询所有可见事件（不限制时间范围）
                val allEvents = withContext(Dispatchers.IO) {
                    calendarRepo.getAllEvents()
                }
                // 过滤已禁用账户的事件
                val disabledAccounts = prefs.getDisabledAccountIds()
                val events = if (disabledAccounts.isEmpty()) allEvents
                    else allEvents.filter { event ->
                        // 通过日历ID查找对应的账户，判断是否被禁用
                        !isAccountDisabled(event.calendarId, disabledAccounts)
                    }
                // 保存原始事件（供纪念日标签页使用）
                _rawEvents.value = events
                // 过滤掉纪念日事件（标题以"纪念日: "开头且FREQ=YEARLY）
                val filteredEvents = events.filter { event ->
                    !(event.title.startsWith("\u7eaa\u5ff5\u65e5: ") &&
                      event.rrule?.contains("FREQ=YEARLY") == true)
                }
                _state.update {
                    it.copy(events = filteredEvents, isLoading = false)
                }
            } catch (e: SecurityException) {
                Log.e("CalendarEventVM", "loadEvents SecurityException", e)
                _state.update { it.copy(isLoading = false, events = emptyList()) }
            } catch (e: Exception) {
                Log.e("CalendarEventVM", "loadEvents failed", e)
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
     * 添加权限检查，避免无权限时操作
     */
    fun selectEvent(event: CalendarEventInfo) {
        // 检查权限，无权限时不显示详情弹窗
        if (!_state.value.hasPermission) {
            checkPermission()
            if (!_state.value.hasPermission) {
                Log.w("CalendarEventVM", "selectEvent skipped: no permission")
                return
            }
        }
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
     * 根据 ID 直接加载单个日历事件（不依赖全部事件列表）
     * 添加权限检查，避免 SecurityException 导致闪退
     */
    fun loadEventById(eventId: Long) {
        // 先检查权限，无权限时不执行查询
        if (!_state.value.hasPermission) {
            checkPermission()
            if (!_state.value.hasPermission) {
                Log.w("CalendarEventVM", "loadEventById($eventId) skipped: no permission")
                _isSingleLoading.value = false
                return
            }
        }
        _isSingleLoading.value = true
        viewModelScope.launch {
            val event = try {
                withContext(Dispatchers.IO) {
                    calendarRepo.getEventById(eventId)
                }
            } catch (e: SecurityException) {
                Log.e("CalendarEventVM", "loadEventById($eventId) SecurityException", e)
                null
            } catch (e: Exception) {
                Log.e("CalendarEventVM", "loadEventById($eventId) failed", e)
                null
            }
            Log.d("CalendarEventVM", "loadEventById($eventId) -> ${event?.title ?: "null"}")
            _singleEvent.value = event
            _isSingleLoading.value = false
        }
    }

    /**
     * 创建新的日历事件（异步执行，避免主线程阻塞）
     */
    fun createEventAsync(
        title: String,
        description: String?,
        dtStart: Long,
        dtEnd: Long,
        allDay: Boolean = false,
        location: String? = null,
        calendarId: Long? = null,
        rrule: String? = null,
        reminderMinutes: Int? = null,
        colorHex: String? = null,
        onResult: (Boolean) -> Unit
    ) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                calendarRepo.createEvent(
                    title, description, dtStart, dtEnd, allDay, location,
                    calendarId, rrule, reminderMinutes, colorHex
                )
            }
            Log.d("CalendarEventVM", "createEvent result=$result")
            val success = result > 0
            if (success) loadEvents()
            onResult(success)
        }
    }

    /**
     * 创建新的日历事件（同步，保留兼容）
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
