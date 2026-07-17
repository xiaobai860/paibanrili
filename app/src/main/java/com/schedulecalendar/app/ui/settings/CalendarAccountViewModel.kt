package com.schedulecalendar.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.schedulecalendar.app.data.calendar.CalendarAccountInfo
import com.schedulecalendar.app.data.calendar.CalendarEventRepository
import com.schedulecalendar.app.data.prefs.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 日历账户管理状态
 */
data class CalendarAccountState(
    val accounts: List<CalendarAccountInfo> = emptyList(),
    val disabledAccountIds: Set<Long> = emptySet(),
    val accountCategories: Map<String, String> = emptyMap(),
    val isLoading: Boolean = true
)

/**
 * 日历账户管理 ViewModel
 * 管理系统日历账户的读取、禁用/启用等操作
 */
@HiltViewModel
class CalendarAccountViewModel @Inject constructor(
    private val calendarRepo: CalendarEventRepository,
    private val prefs: AppPreferences
) : ViewModel() {

    private val _state = MutableStateFlow(CalendarAccountState())
    val state: StateFlow<CalendarAccountState> = _state.asStateFlow()

    init {
        loadAccounts()
    }

    /**
     * 加载所有系统日历账户
     * 先确保应用日历账户已创建，再加载所有账户列表
     * 首次加载时自动禁用非应用账户
     */
    fun loadAccounts() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                // 确保应用日历账户已创建
                calendarRepo.getOrCreateLocalCalendarId()
                calendarRepo.getOrCreateAnniversaryCalendarId()
                calendarRepo.getOrCreateReminderCalendarId()
                val accounts = calendarRepo.getAllAccounts()
                var disabledIds = prefs.getDisabledAccountIds()
    
                // 首次加载：自动禁用非应用账户，并设置应用日历默认分类
                if (!prefs.isAccountsInitialized()) {
                    val nonAppCalIds = accounts
                        .filter { it.accountType != CalendarEventRepository.ACCOUNT_TYPE }
                        .flatMap { it.calendarIds }
                        .toSet()
                    // 默认禁用提醒账户（不显示在日历视图中）
                    val reminderCalId = calendarRepo.getOrCreateReminderCalendarId()
                    val reminderIds = if (reminderCalId != null) setOf(reminderCalId) else emptySet()
                    if (nonAppCalIds.isNotEmpty() || reminderIds.isNotEmpty()) {
                        disabledIds = disabledIds + nonAppCalIds + reminderIds
                        prefs.saveDisabledAccountIds(disabledIds)
                    }
                    // 应用自身日历默认分类：根据显示名称判断
                    val currentCategories = prefs.getAccountCategories().toMutableMap()
                    accounts.filter { it.accountType == CalendarEventRepository.ACCOUNT_TYPE }.forEach { acct ->
                        val key = calendarRepo.getAccountKey(acct)
                        val category = if (acct.displayName.contains("\u7eaa\u5ff5\u65e5")) "anniversary" else "schedule"
                        currentCategories[key] = category
                    }
                    prefs.saveAccountCategories(currentCategories)
                    prefs.setAccountsInitialized()
                }
    
                val categories = prefs.getAccountCategories()
                _state.update {
                    it.copy(
                        accounts = accounts,
                        disabledAccountIds = disabledIds,
                        accountCategories = categories,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    /**
     * 切换账户启用/禁用状态
     * 禁用后该账户的所有日程不再显示
     */
    fun toggleAccount(account: CalendarAccountInfo) {
        viewModelScope.launch {
            val currentDisabled = _state.value.disabledAccountIds.toMutableSet()
            val isCurrentlyDisabled = account.calendarIds.any { it in currentDisabled }
            val newDisabledState = !isCurrentlyDisabled

            // 尝试同步系统日历（使用第一个日历ID）
            calendarRepo.setAccountSync(account.id, !newDisabledState)

            // 更新本地禁用状态：添加/移除该账户的所有日历ID
            if (newDisabledState) {
                currentDisabled.addAll(account.calendarIds)
            } else {
                currentDisabled.removeAll(account.calendarIds)
            }
            val newDisabled = currentDisabled.toSet()
            _state.update {
                it.copy(disabledAccountIds = newDisabled)
            }
            // 持久化到 DataStore
            prefs.saveDisabledAccountIds(newDisabled)
        }
    }

    /**
     * 判断账户是否被禁用
     */
    fun isAccountDisabled(accountId: Long): Boolean {
        return accountId in _state.value.disabledAccountIds
    }

    /**
     * 设置账户分类
     * @param accountKey 账户键 ("calId:<id>")
     * @param category "schedule" | "anniversary" | null(清除分类，显示在两者中)
     */
    fun setAccountCategory(accountKey: String, category: String?) {
        viewModelScope.launch {
            val current = prefs.getAccountCategories().toMutableMap()
            if (category == null) {
                current.remove(accountKey)
            } else {
                current[accountKey] = category
            }
            prefs.saveAccountCategories(current)
            _state.update { it.copy(accountCategories = current) }
        }
    }

    /**
     * 获取账户的分类
     * @return "schedule" | "anniversary" | null
     */
    fun getAccountCategory(accountKey: String): String? {
        return _state.value.accountCategories[accountKey]
    }

    /**
     * 获取账户的分类 key
     * 所有日历统一使用 "calId:<id>"
     */
    fun getAccountKey(account: CalendarAccountInfo): String {
        return calendarRepo.getAccountKey(account)
    }
}
