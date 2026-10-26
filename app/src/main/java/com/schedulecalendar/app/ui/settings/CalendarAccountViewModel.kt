package com.schedulecalendar.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.schedulecalendar.app.data.calendar.CalendarAccountInfo
import com.schedulecalendar.app.data.calendar.CalendarEventRepository
import com.schedulecalendar.app.data.prefs.AppPreferences
import com.schedulecalendar.app.domain.util.AppLog
import com.schedulecalendar.app.domain.util.LogTags
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 日历账户管理状态
 */
data class CalendarAccountState(
    val accounts: List<CalendarAccountInfo> = emptyList(),
    val disabledAccountIds: Set<Long> = emptySet(),
    /** 用户**手动设置过**的分类（未设置的不在此）；写回存储用它，避免默认值污染存储 */
    val accountCategories: Map<String, String> = emptyMap(),
    /**
     * **生效**分类 = 用户设置过的 + 未设置者的默认兜底（默认按「日程」）。
     *
     * UI 必须显示这个，而不是原始 map —— 否则会出现「界面写未分类、
     * 实际按日程走」的自相矛盾（用户看到的与实际行为对不上）。
     */
    val effectiveCategories: Map<String, String> = emptyMap(),
    val isLoading: Boolean = true
)

/**
 * 应用自身三个日历的**精确 ID**。
 *
 * 用 ID 而不是显示名做判定：显示名可能被用户或 ROM 改动，
 * 一旦对不上，应用账户就会被误判成「外部账户」（分类错、排序掉到后面）。
 */
private data class AppCalendarIds(
    val schedule: Long?,
    val anniversary: Long?,
    val reminder: Long?
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
     *
     * ## 默认值如何落地（老用户也能拿到正确默认）
     * 产品期望的默认态是：**只打开应用自己的「日程」「纪念日」两个账户**，
     * 「提醒」账户默认隐藏，且两者分别是日程分类 / 纪念日分类。
     *
     * - **禁用状态**（隐藏外部账户、隐藏提醒账户）：保持**一次性**语义
     *   （`accountsInitialized`），避免覆盖用户后续的手动启用/禁用；
     *   另加一条兜底：**禁用集合为空**说明默认从未生效（用户没配置过）→ 补齐默认值。
     * - **分类**：改为**幂等补齐** —— 每次加载时，**只对还没有分类条目的应用日历**补默认值，
     *   已有条目一律不碰。这样老用户升级后自动拿到正确默认，
     *   而用户手动改过的分类（含手动改成「未分类」）不会被覆盖。
     */
    fun loadAccounts() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                // ⚠️ 这几个调用内部是 ContentResolver / AccountManager 的**同步 Binder IPC**
                // （创建日历、查询账户）→ 必须在 IO 线程，否则主线程卡顿（系统账户多时可达数百 ms）。
                val (accounts, appCalIds) = withContext(Dispatchers.IO) {
                    // 先确保三个应用日历都已创建，再取账户列表（否则新建的日历不在列表里）
                    val schedule = calendarRepo.getOrCreateLocalCalendarId()
                    val anniversary = calendarRepo.getOrCreateAnniversaryCalendarId()
                    val reminder = calendarRepo.getOrCreateReminderCalendarId()
                    calendarRepo.getAllAccounts() to AppCalendarIds(schedule, anniversary, reminder)
                }
                var disabledIds = prefs.getDisabledAccountIds()

                // 「排班日历-提醒」是应用为「系统闹钟提醒」自建的**载体日历**，不是用户要管理的账户
                // → 从列表里**直接隐藏**：用户看不到它，也就没有"手动启用"这回事。
                val visibleAccounts = accounts.filter { acct ->
                    acct.calendarIds.none { it == appCalIds.reminder }
                }

                // ── 默认禁用：所有外部账户 + 应用自己的「提醒」账户 ──
                // （提醒账户虽已隐藏，这里保持禁用只是双保险：别处若按 disabledIds 过滤日历也能挡住）
                val nonAppCalIds = accounts
                    .filter { it.accountType != CalendarEventRepository.ACCOUNT_TYPE }
                    .flatMap { it.calendarIds }
                    .toSet()
                val reminderIds = appCalIds.reminder?.let { setOf(it) } ?: emptySet()
                // 首次初始化，或禁用集合为空（= 默认从未生效，用户没配置过）
                val shouldApplyDefaults = !prefs.isAccountsInitialized() || disabledIds.isEmpty()
                if (shouldApplyDefaults) {
                    val toDisable = nonAppCalIds + reminderIds
                    if (toDisable.isNotEmpty() && !disabledIds.containsAll(toDisable)) {
                        disabledIds = disabledIds + toDisable
                        prefs.saveDisabledAccountIds(disabledIds)
                    }
                }
                if (!prefs.isAccountsInitialized()) {
                    prefs.setAccountsInitialized()
                }

                // ── 默认分类：幂等补齐（只补没有条目的，不覆盖用户设置）──
                val currentCategories = prefs.getAccountCategories().toMutableMap()
                var categoriesChanged = false
                visibleAccounts.filter { it.accountType == CalendarEventRepository.ACCOUNT_TYPE }.forEach { acct ->
                    val key = calendarRepo.getAccountKey(acct)
                    // 已有条目 = 用户（或旧版本）已设置过 → 绝不覆盖
                    if (currentCategories.containsKey(key)) return@forEach
                    val calId = acct.calendarIds.firstOrNull()
                    val default = when (calId) {
                        appCalIds.anniversary -> CalendarEventRepository.CATEGORY_ANNIVERSARY
                        appCalIds.schedule -> CalendarEventRepository.CATEGORY_SCHEDULE
                        else -> null // 「提醒」日历不参与分类
                    }
                    if (default != null) {
                        currentCategories[key] = default
                        categoriesChanged = true
                    }
                }
                if (categoriesChanged) prefs.saveAccountCategories(currentCategories)
    
                val categories = prefs.getAccountCategories()
                val effectiveCategories = effectiveCategoriesOf(categories, visibleAccounts)
                _state.update {
                    it.copy(
                        accounts = sortAccounts(visibleAccounts, appCalIds),
                        disabledAccountIds = disabledIds,
                        accountCategories = categories,
                        effectiveCategories = effectiveCategories,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                // 静默吞掉会让「日历账户页一直空白」无从排查 → 必须留下原因
                AppLog.e(LogTags.CALENDAR_ACCOUNT_VIEW_MODEL, "loadAccounts failed", e)
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
            // ⚠️ 必须同时更新**生效分类**：用户改完分类，界面要立刻反映，
            // 否则 effectiveCategories 仍是旧值（chip 选中态不跟着变）
            _state.update { st ->
                st.copy(
                    accountCategories = current,
                    effectiveCategories = effectiveCategoriesOf(current, st.accounts)
                )
            }
        }
    }

    /**
     * 生效分类 = 用户设置过的 + 未设置者的默认兜底（默认「日程」）。
     *
     * 读路径（事项页的分类映射）与 UI（分类 chip 的选中态）必须用**同一个**口径，
     * 否则会出现「界面写未分类、实际按日程走」的自相矛盾。
     */
    private fun effectiveCategoriesOf(
        raw: Map<String, String>,
        accounts: List<CalendarAccountInfo>
    ): Map<String, String> = buildMap {
        putAll(raw)
        accounts.forEach { acct ->
            val key = calendarRepo.getAccountKey(acct)
            if (!containsKey(key)) put(key, CalendarEventRepository.DEFAULT_CATEGORY)
        }
    }

    /**
     * 账户排序：应用自己的三个日历固定在最前（**日程 → 纪念日 → 提醒**），外部账户在后。
     *
     * 用**精确日历 ID**判定顺序，而不是显示名 —— 显示名一旦被改动，
     * 应用账户就会掉到外部账户后面去。
     */
    private fun sortAccounts(
        accounts: List<CalendarAccountInfo>,
        appCalIds: AppCalendarIds
    ): List<CalendarAccountInfo> {
        val order = buildMap {
            appCalIds.schedule?.let { put(it, 0) }
            appCalIds.anniversary?.let { put(it, 1) }
            appCalIds.reminder?.let { put(it, 2) }
        }
        // sortedWith 是稳定排序：同档位保持原有相对顺序
        return accounts.sortedWith(
            compareBy { acct ->
                val calId = acct.calendarIds.firstOrNull()
                when {
                    calId != null && order.containsKey(calId) -> order.getValue(calId)
                    acct.accountType == CalendarEventRepository.ACCOUNT_TYPE -> 50
                    else -> 100
                }
            }
        )
    }

    /**
     * 获取账户的分类 key
     * 所有日历统一使用 "calId:<id>"
     */
    fun getAccountKey(account: CalendarAccountInfo): String {
        return calendarRepo.getAccountKey(account)
    }
}
