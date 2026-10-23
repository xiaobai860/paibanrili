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

    private suspend fun notifyChanged() = _refreshSignal.notifyChangedOrLog("ScheduleRepo")

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

    /** 排班总数（轻量指纹查询） */
    suspend fun countAll(): Int = dao.countAll()
    /** 最近排班日期 yyyy-MM-dd */
    suspend fun maxDate(): String? = dao.maxDate()
}
