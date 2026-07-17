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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
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
    val prefs: AppPreferences
) : ViewModel() {

    private val _state = MutableStateFlow(CalendarEventState())
    val state: StateFlow<CalendarEventState> = _state.asStateFlow()

    private val _accounts = MutableStateFlow<List<CalendarAccountInfo>>(emptyList())
    val accounts: StateFlow<List<CalendarAccountInfo>> = _accounts.asStateFlow()

    /** 账户分类映射：calendarId -> "schedule"|"anniversary" */
    private val accountCategoriesFlow = MutableStateFlow<Map<Long, String>>(emptyMap())

    /** 应用自身账户的所有日历ID（包含日程日历和纪念日日历） */
    private val _appCalendarIds = MutableStateFlow<Set<Long>>(emptySet())

    /** 纪念日专用日历ID */
    private var anniversaryCalendarId: Long? = null

    /** 日程专用日历ID */
    private var scheduleCalendarId: Long? = null

    /** 原始事件列表（已过滤禁用账户，供日程和纪念日标签页使用） */
    private val _rawEvents = MutableStateFlow<List<CalendarEventInfo>>(emptyList())

    /** 纪念日列表（只显示纪念日事件，响应分类变化） */
    val anniversaries: StateFlow<List<CalendarEventInfo>> =
        combine(_rawEvents, accountCategoriesFlow, _appCalendarIds) { events, categories, appCalIds ->
            events.filter { event ->
                if (event.calendarId in appCalIds) {
                    val isAnniversaryCal = anniversaryCalendarId != null && event.calendarId == anniversaryCalendarId
                    val isLegacyAnniversary = event.title.startsWith("纪念日: ") &&
                        event.rrule?.contains("FREQ=YEARLY") == true
                    isAnniversaryCal || isLegacyAnniversary
                } else {
                    val category = categories[event.calendarId]
                    category == "anniversary"
                }
            }
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** 按 ID 加载的单个事件（供编辑页面使用） */
    private val _singleEvent = MutableStateFlow<CalendarEventInfo?>(null)
    val singleEvent: StateFlow<CalendarEventInfo?> = _singleEvent.asStateFlow()

    private val _isSingleLoading = MutableStateFlow(true)
    val isSingleLoading: StateFlow<Boolean> = _isSingleLoading.asStateFlow()

    private var observer: android.database.ContentObserver? = null

    init {
        checkPermission()
        // 响应式更新日程列表：排除纪念日事件
        viewModelScope.launch {
            combine(_rawEvents, accountCategoriesFlow, _appCalendarIds) { events, categories, appCalIds ->
                events.filter { event ->
                    if (event.calendarId in appCalIds) {
                        // 应用自身账户：纪念日日历的事件不显示在日程
                        // 同时兼容旧数据：标题以"纪念日: "开头且有FREQ=YEARLY的也不显示
                        val isAnniversaryCal = anniversaryCalendarId != null && event.calendarId == anniversaryCalendarId
                        val isLegacyAnniversary = event.title.startsWith("纪念日: ") &&
                            event.rrule?.contains("FREQ=YEARLY") == true
                        // 保留非纪念日事件（过滤掉纪念日事件）
                        !(isAnniversaryCal || isLegacyAnniversary)
                    } else {
                        // 外部账户：按分类过滤
                        val category = categories[event.calendarId]
                        category != "anniversary"
                    }
                }
            }.collect { filtered ->
                _state.update { it.copy(events = filtered) }
            }
        }
        // 只在有权限时加载账户列表和事件
        if (_state.value.hasPermission) {
            viewModelScope.launch {
                val accountsList = try {
                    withContext(Dispatchers.IO) {
                        calendarRepo.getOrCreateLocalCalendarId()
                        anniversaryCalendarId = calendarRepo.getOrCreateAnniversaryCalendarId()
                        calendarRepo.getAllAccounts()
                    }
                } catch (e: SecurityException) {
                    emptyList()
                }
                _accounts.value = accountsList
                updateAppCalendarIds(accountsList)
                rebuildCategoryMapping()
                loadEvents()
                startObserving()
            }
        }
        // 监听账户分类和禁用状态变化，自动刷新
        viewModelScope.launch {
            combine(
                prefs.accountCategoriesFlow,
                prefs.disabledAccountIdsFlow
            ) { _, _ -> Unit }
            .drop(1) // 跳过首次发射（init 已处理初始加载）
            .collect {
                rebuildCategoryMapping()
                loadEvents()
            }
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
                    anniversaryCalendarId = calendarRepo.getOrCreateAnniversaryCalendarId()
                    calendarRepo.getAllAccounts()
                }
            } catch (e: SecurityException) {
                emptyList()
            }
            _accounts.value = accountsList
            updateAppCalendarIds(accountsList)
            rebuildCategoryMapping()
        }
    }

    /**
     * 获取日程专用日历ID
     * 供 AddCalendarEventScreen 创建日程时使用
     */
    suspend fun getScheduleCalendarId(): Long? {
        if (scheduleCalendarId == null) {
            scheduleCalendarId = withContext(Dispatchers.IO) {
                calendarRepo.getOrCreateLocalCalendarId()
            }
        }
        return scheduleCalendarId
    }

    /**
     * 获取纪念日专用日历ID
     * 供 AddAnniversaryScreen 创建纪念日时使用
     */
    suspend fun getAnniversaryCalendarId(): Long? {
        if (anniversaryCalendarId == null) {
            anniversaryCalendarId = withContext(Dispatchers.IO) {
                calendarRepo.getOrCreateAnniversaryCalendarId()
            }
        }
        return anniversaryCalendarId
    }

    /**
     * 根据账户列表更新应用自身日历ID集合
     */
    private fun updateAppCalendarIds(accounts: List<CalendarAccountInfo>) {
        val appCalIds = accounts
            .filter { it.accountType == CalendarEventRepository.ACCOUNT_TYPE }
            .flatMap { it.calendarIds }
            .toSet()
        _appCalendarIds.value = appCalIds
    }

    /**
     * 根据当前账户列表和分类偏好，重建 calendarId -> category 映射
     */
    private suspend fun rebuildCategoryMapping() {
        val categories = prefs.getAccountCategories()
        val calIdToCategory = mutableMapOf<Long, String>()
        _accounts.value.forEach { acct ->
            val key = calendarRepo.getAccountKey(acct)
            val category = categories[key]
            if (category != null) {
                acct.calendarIds.forEach { calId ->
                    calIdToCategory[calId] = category
                }
            }
        }
        accountCategoriesFlow.value = calIdToCategory
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
                val allEvents = withContext(Dispatchers.IO) {
                    calendarRepo.getAllEvents()
                }
                val disabledAccounts = prefs.getDisabledAccountIds()
                val events = if (disabledAccounts.isEmpty()) allEvents
                    else allEvents.filter { event ->
                        !isAccountDisabled(event.calendarId, disabledAccounts)
                    }
                _rawEvents.value = events
                rebuildCategoryMapping()
                _state.update { it.copy(isLoading = false) }
            } catch (e: SecurityException) {
                _state.update { it.copy(isLoading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    /**
     * 开始监听系统日历变化
     */
    private fun startObserving() {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        observer = object : android.database.ContentObserver(handler) {
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
                null
            } catch (e: Exception) {
                null
            }
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
            val success = result > 0
            if (success) loadEvents()
            onResult(success)
        }
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
     * 切换事件分类（日程 ↔ 纪念日）
     * 纪念日特征：标题以"纪念日: "开头 且 rrule包含FREQ=YEARLY
     */
    fun changeEventCategory(event: CalendarEventInfo, toAnniversary: Boolean) {
        viewModelScope.launch {
            val updatedEvent = if (toAnniversary) {
                // 转为纪念日：添加前缀 + 添加年度重复
                val newTitle = if (event.title.startsWith("纪念日: ")) event.title
                    else "纪念日: ${event.title}"
                val newRrule = if (event.rrule?.contains("FREQ=YEARLY") == true) event.rrule
                    else "FREQ=YEARLY"
                event.copy(title = newTitle, rrule = newRrule)
            } else {
                // 转为日程：移除前缀 + 移除年度重复
                val newTitle = event.title.removePrefix("纪念日: ")
                val newRrule = event.rrule?.replace("FREQ=YEARLY", "")?.trim()?.ifEmpty { null }
                event.copy(title = newTitle, rrule = newRrule)
            }
            val success = calendarRepo.updateEvent(updatedEvent)
            if (success) {
                loadEvents()
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
