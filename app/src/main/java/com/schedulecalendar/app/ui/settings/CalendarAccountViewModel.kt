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
     */
    fun loadAccounts() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                val accounts = calendarRepo.getAllAccounts()
                val disabledIds = prefs.getDisabledAccountIds()
                _state.update {
                    it.copy(accounts = accounts, disabledAccountIds = disabledIds, isLoading = false)
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
            val isCurrentlyDisabled = account.id in currentDisabled
            val newDisabledState = !isCurrentlyDisabled

            // 尝试同步系统日历
            calendarRepo.setAccountSync(account.id, !newDisabledState)

            // 更新本地禁用状态
            if (newDisabledState) {
                currentDisabled.add(account.id)
            } else {
                currentDisabled.remove(account.id)
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
}
