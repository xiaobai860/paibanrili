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

    /** 清除所有 DataStore 键值对，恢复出厂默认值 */
    suspend fun clearAll() = context.dataStore.edit { it.clear() }
}
