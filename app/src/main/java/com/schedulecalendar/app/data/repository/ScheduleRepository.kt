// app/src/main/java/com/schedulecalendar/app/data/repository/ScheduleRepository.kt
package com.schedulecalendar.app.data.repository

import com.schedulecalendar.app.data.db.dao.ScheduleRecordDao
import com.schedulecalendar.app.domain.model.ScheduleRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScheduleRepository @Inject constructor(
    private val dao: ScheduleRecordDao
) {
    /** 数据变更信号：写操作后发出，供 ViewModel 响应刷新 */
    private val _refreshSignal = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)
    val refreshSignal: Flow<Unit> = _refreshSignal.asSharedFlow()

    private fun notifyChanged() = _refreshSignal.notifyChangedOrLog("ScheduleRepo")

    /**
     * 把 "yyyy-MM" 转成该月的闭区间日期范围 [start, end]（"yyyy-MM-01" ~ "yyyy-MM-31"）。
     * 用范围查询替代 `LIKE 'yyyy-MM%'`：LIKE 大小写不敏感、无法走索引，会在最大的表上全表扫描。
     * @throws IllegalArgumentException 格式非法时（防止通配符注入）
     */
    private fun monthRange(yearMonth: String): Pair<String, String> {
        require(yearMonth.matches(Regex("""^\d{4}-\d{2}$"""))) { "bad yearMonth: $yearMonth" }
        return "$yearMonth-01" to "$yearMonth-31"
    }

    suspend fun getByMonth(yearMonth: String): List<ScheduleRecord> {
        val (from, to) = monthRange(yearMonth)
        return dao.getByMonth(from, to).map { it.toDomain() }
    }

    /** 某日及之后的排班条数（判断某口径区间内是否已产生数据） */
    suspend fun countRecordsSince(from: String): Int = dao.countSince(from)

    fun observeByRange(from: String, to: String): Flow<List<ScheduleRecord>> =
        dao.observeByRange(from, to).map { list -> list.map { it.toDomain() } }

    /** 一次性取区间排班（避免逐日 getByDate 造成 N 次 DB 往返，如提醒窗口 11 天） */
    suspend fun getByRange(from: String, to: String): List<ScheduleRecord> =
        dao.getByRange(from, to).map { it.toDomain() }

    suspend fun getByDate(date: String): ScheduleRecord? = dao.getByDate(date)?.toDomain()
    suspend fun save(record: ScheduleRecord) { dao.upsert(record.toEntity()); notifyChanged() }
    suspend fun saveAll(records: List<ScheduleRecord>) { dao.upsertAll(records.map { it.toEntity() }); notifyChanged() }
    suspend fun delete(date: String) { dao.deleteByDate(date); notifyChanged() }

    /** 批量删除（单事务 + 单次变更信号）：批量清除排班用，避免 N 次事务与 N 轮组件刷新 */
    suspend fun deleteMany(dates: List<String>) {
        if (dates.isEmpty()) return
        dao.deleteByDates(dates); notifyChanged()
    }
    suspend fun deleteAll() { dao.deleteAll(); notifyChanged() }

    suspend fun getAll(): List<ScheduleRecord> = dao.getAll().map { it.toDomain() }

    /**
     * 排班中被引用的三类 ID 集合（班次 / 附加状态 / 补贴项）。
     * 供「废弃数据清理」判断哪些已归档项仍被引用；
     * 比 [getAll] 少读大量列、少建整表领域对象（排班上千条时差别明显）。
     */
    suspend fun referencedIds(): Triple<Set<String>, Set<String>, Set<String>> {
        val shiftIds = dao.distinctShiftIds().toSet()
        val statusIds = HashSet<String>()
        val extraIds = HashSet<String>()
        for ((appliedStatusesJson, extraItemIdsJson) in dao.getReferenceColumns()) {
            parseReferencedIds(appliedStatusesJson, extraItemIdsJson, statusIds, extraIds)
        }
        return Triple(shiftIds, statusIds, extraIds)
    }

    /** 排班总数（轻量指纹查询） */
    suspend fun countAll(): Int = dao.countAll()
    /** 最近排班日期 yyyy-MM-dd */
    suspend fun maxDate(): String? = dao.maxDate()
}

/**
 * 从排班记录的引用列 JSON 中解析「被引用的附加状态 ID / 补贴项 ID」。
 *
 * 独立成纯函数是为了能写单元测试 —— 这段逻辑直接决定「废弃数据清理」会不会
 * 误删仍被历史排班引用的状态/补贴项（2026-10-23 就踩过一次「按数组解析单对象」的坑，
 * 导致状态引用集合恒为空、已归档状态被误判为无引用）。
 *
 * 约定：解析失败一律抛 [IllegalStateException]，绝不静默跳过 ——
 * 静默跳过等于把该行的引用当成「没有引用」，清理时就会删掉历史数据仍在用的项。
 */
internal fun parseReferencedIds(
    appliedStatusesJson: String,
    extraItemIdsJson: String,
    statusIds: MutableSet<String>,
    extraIds: MutableSet<String>
) {
    // 附加状态：DB 里存的是「单对象」格式（{"statusId":..}），旧数据是数组格式，
    // 统一用 [parseAppliedStatus] 解析（两种格式都兼容）
    val statusJson = appliedStatusesJson.trim()
    if (statusJson.isNotEmpty() && statusJson != "null" && statusJson != "[]") {
        val st = parseAppliedStatus(statusJson)
            ?: throw IllegalStateException("排班引用数据解析失败（附加状态）：$statusJson")
        if (st.statusId.isNotBlank()) statusIds.add(st.statusId)
    }
    // 补贴项：JSON 数组 ["id1","id2"]；解析失败同样中止，绝不静默丢引用
    val extraJson = extraItemIdsJson.trim()
    if (extraJson.isNotEmpty() && extraJson != "null" && extraJson != "[]") {
        runCatching {
            com.google.gson.JsonParser.parseString(extraJson).asJsonArray.forEach { el ->
                val id = el.asString
                if (id.isNotBlank()) extraIds.add(id)
            }
        }.getOrElse {
            throw IllegalStateException("排班引用数据解析失败（补贴项）：$extraJson")
        }
    }
}
