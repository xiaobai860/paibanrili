// app/src/main/java/com/schedulecalendar/app/data/prefs/AppPreferences.kt
package com.schedulecalendar.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.InstanceCreator
import com.google.gson.reflect.TypeToken
import com.schedulecalendar.app.domain.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("app_config")

@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** 使用 InstanceCreator 确保 Gson 反序列化时保留 Kotlin data class 默认值 */
    private val gson = GsonBuilder()
        .registerTypeAdapter(DisplayScheme::class.java, InstanceCreator { DisplayScheme() })
        .registerTypeAdapter(SalaryConfig::class.java, InstanceCreator { SalaryConfig() })
        .registerTypeAdapter(AttendConfig::class.java, InstanceCreator { AttendConfig() })
        .registerTypeAdapter(ScheduleRule::class.java, InstanceCreator { ScheduleRule() })
        .create()

    companion object {
        val KEY_SALARY_CONFIG   = stringPreferencesKey("salary_config")
        val KEY_ATTEND_CONFIG   = stringPreferencesKey("attend_config")
        val KEY_SCHEDULE_RULE   = stringPreferencesKey("schedule_rule")
        val KEY_DISPLAY_SCHEMES = stringPreferencesKey("display_schemes")
        val KEY_WIDGET_DATA     = stringPreferencesKey("widget_data")
        val KEY_SHIFT_ORDER     = stringPreferencesKey("shift_order")
        val KEY_STATUS_ORDER    = stringPreferencesKey("status_order")
        val KEY_EXTRA_ORDER     = stringPreferencesKey("extra_order")
        val KEY_BREAK_ORDER     = stringPreferencesKey("break_order")
        val KEY_SHIFT_COLOR_INDEX  = intPreferencesKey("shift_color_index")
        val KEY_STATUS_COLOR_INDEX = intPreferencesKey("status_color_index")
        val KEY_APP_DATA_KEEP_COUNT    = intPreferencesKey("app_data_keep_count")
        val KEY_SHIFT_CONFIG_KEEP_COUNT = intPreferencesKey("shift_config_keep_count")
        val KEY_BACKUP_CUSTOM_PATH     = stringPreferencesKey("backup_custom_path")
        /** 应用数据自动备份最后执行日期（yyyyMMdd），用于"每天只备份一次"去重 */
        val KEY_LAST_APP_DATA_AUTO_BACKUP = stringPreferencesKey("last_app_data_auto_backup")
        /** 应用数据自动备份上次成功时的数据指纹（轻量 hashCode），未变则跳过整个 backup 流程 */
        val KEY_LAST_APP_DATA_BACKUP_FP = intPreferencesKey("last_app_data_backup_fp")
        // ── 上下班提醒配置 ──
        val KEY_REMINDER_ENABLED        = stringPreferencesKey("reminder_enabled")
        val KEY_REMINDER_METHOD          = stringPreferencesKey("reminder_method")
        val KEY_REMINDER_CLOCK_IN        = stringPreferencesKey("reminder_clock_in")
        val KEY_REMINDER_CLOCK_OUT       = stringPreferencesKey("reminder_clock_out")
        val KEY_REMINDER_CLOCK_IN_MINUTES = intPreferencesKey("reminder_clock_in_minutes")
        val KEY_REMINDER_CLOCK_OUT_MINUTES = intPreferencesKey("reminder_clock_out_minutes")
        val KEY_REMINDER_NOTIFY_BAR        = stringPreferencesKey("reminder_notify_bar")
        val KEY_REMINDER_INITIALIZED      = stringPreferencesKey("reminder_initialized")
        val KEY_DISABLED_ACCOUNT_IDS     = stringPreferencesKey("disabled_calendar_accounts")
        val KEY_ACCOUNT_CATEGORIES         = stringPreferencesKey("account_categories")
        val KEY_ACCOUNTS_INITIALIZED     = stringPreferencesKey("accounts_initialized")
        // ── 首次启动权限申请 ──
        val KEY_INITIAL_PERMISSIONS_DONE = stringPreferencesKey("initial_permissions_done")
    }

    // ── 薪资配置 ───────────────────────────────────
    val salaryConfigFlow: Flow<SalaryConfig> = context.dataStore.data.map { p ->
        p[KEY_SALARY_CONFIG]?.let { gson.fromJson(it, SalaryConfig::class.java) } ?: SalaryConfig()
    }
    suspend fun saveSalaryConfig(cfg: SalaryConfig) = context.dataStore.edit {
        it[KEY_SALARY_CONFIG] = gson.toJson(cfg)
    }

    // ── 考勤配置 ───────────────────────────────────
    val attendConfigFlow: Flow<AttendConfig> = context.dataStore.data.map { p ->
        p[KEY_ATTEND_CONFIG]?.let { gson.fromJson(it, AttendConfig::class.java) } ?: AttendConfig()
    }
    suspend fun saveAttendConfig(cfg: AttendConfig) = context.dataStore.edit {
        it[KEY_ATTEND_CONFIG] = gson.toJson(cfg)
    }

    // ── 排班规则 ───────────────────────────────────
    val scheduleRuleFlow: Flow<ScheduleRule?> = context.dataStore.data.map { p ->
        p[KEY_SCHEDULE_RULE]?.let { gson.fromJson(it, ScheduleRule::class.java) }
    }
    suspend fun saveScheduleRule(rule: ScheduleRule?) = context.dataStore.edit {
        it[KEY_SCHEDULE_RULE] = if (rule == null) "" else gson.toJson(rule)
    }

    // ── 显示方案 ───────────────────────────────────
    val displaySchemesFlow: Flow<List<DisplayScheme>> = context.dataStore.data.map { p ->
        p[KEY_DISPLAY_SCHEMES]?.let { json ->
            val type = object : TypeToken<List<DisplayScheme>>() {}.type
            runCatching {
                gson.fromJson<List<DisplayScheme>>(json, type)
            }.getOrNull() ?: listOf(DisplayScheme())
        } ?: listOf(DisplayScheme())
    }
    suspend fun saveDisplaySchemes(schemes: List<DisplayScheme>) = context.dataStore.edit {
        it[KEY_DISPLAY_SCHEMES] = gson.toJson(schemes)
    }

    // ── 小组件数据（供 Widget 读取）────────────────
    suspend fun saveWidgetData(json: String) = context.dataStore.edit {
        it[KEY_WIDGET_DATA] = json
    }
    /** 正确用法：用 .first() 读取当前快照，而非 edit{} */
    suspend fun getWidgetData(): String? =
        context.dataStore.data.first()[KEY_WIDGET_DATA]

    // ── 班次排序 ───────────────────────────────────
    /** 读取班次显示顺序（逗号分隔的 ID 列表） */
    suspend fun getShiftOrder(): List<String> =
        context.dataStore.data.first()[KEY_SHIFT_ORDER]
            ?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

    /** 保存班次显示顺序 */
    suspend fun saveShiftOrder(ids: List<String>) = context.dataStore.edit {
        it[KEY_SHIFT_ORDER] = ids.joinToString(",")
    }

    // ── 状态排序 ───────────────────────────────────
    /** 读取状态显示顺序 */
    suspend fun getStatusOrder(): List<String> =
        context.dataStore.data.first()[KEY_STATUS_ORDER]
            ?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

    /** 保存状态显示顺序 */
    suspend fun saveStatusOrder(ids: List<String>) = context.dataStore.edit {
        it[KEY_STATUS_ORDER] = ids.joinToString(",")
    }

    // ── 补贴扣款排序 ─────────────────────────────────
    /** 读取补贴扣款显示顺序 */
    suspend fun getExtraOrder(): List<String> =
        context.dataStore.data.first()[KEY_EXTRA_ORDER]
            ?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

    /** 保存补贴扣款显示顺序 */
    suspend fun saveExtraOrder(ids: List<String>) = context.dataStore.edit {
        it[KEY_EXTRA_ORDER] = ids.joinToString(",")
    }

    // ── 休息时段排序 ─────────────────────────────────
    /** 读取休息时段显示顺序 */
    suspend fun getBreakOrder(): List<String> =
        context.dataStore.data.first()[KEY_BREAK_ORDER]
            ?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

    /** 保存休息时段显示顺序 */
    suspend fun saveBreakOrder(ids: List<String>) = context.dataStore.edit {
        it[KEY_BREAK_ORDER] = ids.joinToString(",")
    }

    // ── 预设颜色索引 ─────────────────────────────────
    /** 读取班次预设颜色索引 */
    suspend fun getShiftColorIndex(): Int =
        context.dataStore.data.first()[KEY_SHIFT_COLOR_INDEX] ?: 0

    /** 保存班次预设颜色索引 */
    suspend fun saveShiftColorIndex(index: Int) = context.dataStore.edit {
        it[KEY_SHIFT_COLOR_INDEX] = index
    }

    /** 读取状态预设颜色索引 */
    suspend fun getStatusColorIndex(): Int =
        context.dataStore.data.first()[KEY_STATUS_COLOR_INDEX] ?: 0

    /** 保存状态预设颜色索引 */
    suspend fun saveStatusColorIndex(index: Int) = context.dataStore.edit {
        it[KEY_STATUS_COLOR_INDEX] = index
    }

    // ── 备份设置 ─────────────────────────────────
    /** 读取应用数据保留份数（默认5，0=禁用自动备份） */
    suspend fun getAppDataKeepCount(): Int =
        context.dataStore.data.first()[KEY_APP_DATA_KEEP_COUNT] ?: 5
    suspend fun saveAppDataKeepCount(count: Int) = context.dataStore.edit {
        it[KEY_APP_DATA_KEEP_COUNT] = count
    }
    /** 读取班次配置保留份数（默认10，0=禁用自动备份） */
    suspend fun getShiftConfigKeepCount(): Int =
        context.dataStore.data.first()[KEY_SHIFT_CONFIG_KEEP_COUNT] ?: 10
    suspend fun saveShiftConfigKeepCount(count: Int) = context.dataStore.edit {
        it[KEY_SHIFT_CONFIG_KEEP_COUNT] = count
    }
    /** 读取自定义备份路径（空=使用应用私有目录） */
    suspend fun getBackupCustomPath(): String =
        context.dataStore.data.first()[KEY_BACKUP_CUSTOM_PATH] ?: ""
    suspend fun saveBackupCustomPath(path: String) = context.dataStore.edit {
        it[KEY_BACKUP_CUSTOM_PATH] = path
    }

    /**
     * 应用数据自动备份最后执行日期（yyyyMMdd），用于"每天只备份一次"去重。
     * 避免 CalendarViewModel.init 每次重建（切 Tab）都产生新备份文件，导致当天累积多份。
     */
    suspend fun getLastAppDataAutoBackupDate(): String =
        context.dataStore.data.first()[KEY_LAST_APP_DATA_AUTO_BACKUP] ?: ""
    suspend fun setLastAppDataAutoBackupDate(date: String) = context.dataStore.edit {
        it[KEY_LAST_APP_DATA_AUTO_BACKUP] = date
    }

    /**
     * 应用数据自动备份上次成功时的数据指纹（轻量 hashCode）。
     * autoBackupAppData 入口计算当前数据指纹并与之比对，未变则跳过整个 backup 流程（节省 ~250ms 后台 IO）。
     */
    suspend fun getLastAppDataBackupFp(): Int =
        context.dataStore.data.first()[KEY_LAST_APP_DATA_BACKUP_FP] ?: 0
    suspend fun setLastAppDataBackupFp(fp: Int) = context.dataStore.edit {
        it[KEY_LAST_APP_DATA_BACKUP_FP] = fp
    }

    /** 清除所有 DataStore 键值对，恢复出厂默认值 */
    suspend fun clearAll() = context.dataStore.edit { it.clear() }

    // ── 上下班提醒配置 ───────────────────────────────────

    /** 提醒是否启用 */
    suspend fun getReminderEnabled(): Boolean =
        context.dataStore.data.first()[KEY_REMINDER_ENABLED] == "true"
    suspend fun saveReminderEnabled(enabled: Boolean) = context.dataStore.edit {
        it[KEY_REMINDER_ENABLED] = enabled.toString()
    }
    val reminderEnabledFlow: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_REMINDER_ENABLED] == "true"
    }

    /** 提醒方式："calendar"=日历提醒，"alarm"=闹钟提醒，"notify"=仅通知栏 */
    suspend fun getReminderMethod(): String =
        context.dataStore.data.first()[KEY_REMINDER_METHOD] ?: "notify"

    /**
     * 首次进入提醒设置时写入默认配置：启用提醒 + 方式「仅通知栏」+ 通知栏开启。
     * 仅在从未显式初始化过时才写入，避免覆盖用户后续主动关闭/修改。
     * 应在 loadSettings 最早调用。返回 true 表示本次为首次写入（需同步调度一次）。
     */
    suspend fun ensureReminderDefaults(): Boolean {
        val prefsData = context.dataStore.data.first()
        if (prefsData[KEY_REMINDER_INITIALIZED] == "true") return false
        context.dataStore.edit {
            it[KEY_REMINDER_ENABLED] = "true"
            it[KEY_REMINDER_METHOD] = "notify"
            it[KEY_REMINDER_NOTIFY_BAR] = "true"
            it[KEY_REMINDER_INITIALIZED] = "true"
        }
        return true
    }
    suspend fun saveReminderMethod(method: String) = context.dataStore.edit {
        it[KEY_REMINDER_METHOD] = method
    }
    val reminderMethodFlow: Flow<String> = context.dataStore.data.map {
        it[KEY_REMINDER_METHOD] ?: "alarm"
    }

    /** 提醒内容："both" / "clock_in" / "clock_out" */
    suspend fun getReminderClockIn(): Boolean =
        context.dataStore.data.first()[KEY_REMINDER_CLOCK_IN] != "false"
    suspend fun saveReminderClockIn(enabled: Boolean) = context.dataStore.edit {
        it[KEY_REMINDER_CLOCK_IN] = enabled.toString()
    }
    suspend fun getReminderClockOut(): Boolean =
        context.dataStore.data.first()[KEY_REMINDER_CLOCK_OUT] != "false"
    suspend fun saveReminderClockOut(enabled: Boolean) = context.dataStore.edit {
        it[KEY_REMINDER_CLOCK_OUT] = enabled.toString()
    }

    /** 提前提醒时间（分钟），上班/下班默认均为 15 分钟 */
    suspend fun getReminderClockInMinutes(): Int =
        context.dataStore.data.first()[KEY_REMINDER_CLOCK_IN_MINUTES] ?: 15
    suspend fun saveReminderClockInMinutes(minutes: Int) = context.dataStore.edit {
        it[KEY_REMINDER_CLOCK_IN_MINUTES] = minutes
    }
    suspend fun getReminderClockOutMinutes(): Int =
        context.dataStore.data.first()[KEY_REMINDER_CLOCK_OUT_MINUTES] ?: 15
    suspend fun saveReminderClockOutMinutes(minutes: Int) = context.dataStore.edit {
        it[KEY_REMINDER_CLOCK_OUT_MINUTES] = minutes
    }
    /** 通知栏提醒开关：是否通过通知栏推送提醒（默认开启，关闭后仅日历事件可见、不弹通知） */
    suspend fun getReminderNotifyBar(): Boolean =
        context.dataStore.data.first()[KEY_REMINDER_NOTIFY_BAR] != "false"
    suspend fun saveReminderNotifyBar(enabled: Boolean) = context.dataStore.edit {
        it[KEY_REMINDER_NOTIFY_BAR] = enabled.toString()
    }
    val reminderNotifyBarFlow: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_REMINDER_NOTIFY_BAR] != "false"
    }

    val reminderSettingsFlow = kotlinx.coroutines.flow.combine(
        reminderEnabledFlow, reminderMethodFlow
    ) { enabled, method ->
        ReminderSettingsSnapshot(enabled, method)
    }

    data class ReminderSettingsSnapshot(val enabled: Boolean, val method: String)

    /** 已禁用的日历账户 ID 列表 */
    suspend fun getDisabledAccountIds(): Set<Long> =
        context.dataStore.data.first()[KEY_DISABLED_ACCOUNT_IDS]
            ?.split(",")?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
    suspend fun saveDisabledAccountIds(ids: Set<Long>) = context.dataStore.edit {
        it[KEY_DISABLED_ACCOUNT_IDS] = ids.joinToString(",")
    }
    val disabledAccountIdsFlow: Flow<Set<Long>> = context.dataStore.data.map {
        it[KEY_DISABLED_ACCOUNT_IDS]
            ?.split(",")?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
    }

    /** 账户是否已完成首次初始化 */
    suspend fun isAccountsInitialized(): Boolean =
        context.dataStore.data.first()[KEY_ACCOUNTS_INITIALIZED] == "true"
    suspend fun setAccountsInitialized() = context.dataStore.edit {
        it[KEY_ACCOUNTS_INITIALIZED] = "true"
    }

    // ── 首次启动权限申请 ────────────────────────────
    /** 是否已完成首次权限申请（默认 false） */
    suspend fun isInitialPermissionsDone(): Boolean =
        context.dataStore.data.first()[KEY_INITIAL_PERMISSIONS_DONE] == "true"
    suspend fun setInitialPermissionsDone() = context.dataStore.edit {
        it[KEY_INITIAL_PERMISSIONS_DONE] = "true"
    }

    /** 账户分类映射：accountKey("calId:<id>") -> "schedule"|"anniversary" */
    suspend fun getAccountCategories(): Map<String, String> =
        context.dataStore.data.first()[KEY_ACCOUNT_CATEGORIES]
            ?.let { json ->
                try {
                    val type = object : TypeToken<Map<String, String>>() {}.type
                    gson.fromJson<Map<String, String>>(json, type)
                } catch (_: Exception) { emptyMap() }
            } ?: emptyMap()

    suspend fun saveAccountCategories(categories: Map<String, String>) = context.dataStore.edit {
        it[KEY_ACCOUNT_CATEGORIES] = gson.toJson(categories)
    }
    val accountCategoriesFlow: Flow<Map<String, String>> = context.dataStore.data.map {
        it[KEY_ACCOUNT_CATEGORIES]
            ?.let { json ->
                try {
                    val type = object : TypeToken<Map<String, String>>() {}.type
                    gson.fromJson<Map<String, String>>(json, type)
                } catch (_: Exception) { emptyMap() }
            } ?: emptyMap()
    }
}
