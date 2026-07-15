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
                // 确保应用日历账户已创建（AccountManager 注册 + Calendar Provider 创建）
                calendarRepo.getOrCreateLocalCalendarId()
                val accounts = calendarRepo.getAllAccounts()
                var disabledIds = prefs.getDisabledAccountIds()
                val categories = prefs.getAccountCategories()

                // 首次加载：自动禁用除本应用外的所有日历账户，并设置应用账户默认为“纪念日”分类
                if (!prefs.isAccountsInitialized()) {
                    val nonAppCalIds = accounts
                        .filter { it.accountType != com.schedulecalendar.app.data.calendar.CalendarEventRepository.ACCOUNT_TYPE }
                        .flatMap { it.calendarIds }
                        .toSet()
                    if (nonAppCalIds.isNotEmpty()) {
                        disabledIds = disabledIds + nonAppCalIds
                        prefs.saveDisabledAccountIds(disabledIds)
                    }
                    // 应用自身账户默认分类为“纪念日”（包含日程和纪念日两个日历）
                    val appAccountKey = "${com.schedulecalendar.app.data.calendar.CalendarEventRepository.ACCOUNT_NAME}|${com.schedulecalendar.app.data.calendar.CalendarEventRepository.ACCOUNT_TYPE}"
                    val currentCategories = prefs.getAccountCategories().toMutableMap()
                    currentCategories[appAccountKey] = "anniversary"
                    prefs.saveAccountCategories(currentCategories)
                    prefs.setAccountsInitialized()
                }

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
     * @param accountKey 账户键 ("accountName|accountType")
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
}
