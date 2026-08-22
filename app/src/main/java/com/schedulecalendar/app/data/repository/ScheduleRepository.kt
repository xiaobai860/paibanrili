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

    private suspend fun notifyChanged() { _refreshSignal.tryEmit(Unit) }
    fun observeByMonth(yearMonth: String): Flow<List<ScheduleRecord>> =
        dao.observeByMonth(yearMonth).map { list -> list.map { it.toDomain() } }

    suspend fun getByMonth(yearMonth: String): List<ScheduleRecord> =
        dao.getByMonth(yearMonth).map { it.toDomain() }

    fun observeByRange(from: String, to: String): Flow<List<ScheduleRecord>> =
        dao.observeByRange(from, to).map { list -> list.map { it.toDomain() } }

    suspend fun getByDate(date: String): ScheduleRecord? = dao.getByDate(date)?.toDomain()
    suspend fun save(record: ScheduleRecord) { dao.upsert(record.toEntity()); notifyChanged() }
    suspend fun saveAll(records: List<ScheduleRecord>) { dao.upsertAll(records.map { it.toEntity() }); notifyChanged() }
    suspend fun delete(date: String) { dao.deleteByDate(date); notifyChanged() }
    suspend fun deleteRange(from: String, to: String) { dao.deleteByRange(from, to); notifyChanged() }
    suspend fun deleteAll() { dao.deleteAll(); notifyChanged() }

    suspend fun getAll(): List<ScheduleRecord> = dao.getAll().map { it.toDomain() }

    /** 排班总数（轻量指纹查询） */
    suspend fun countAll(): Int = dao.countAll()
    /** 最近排班日期 yyyy-MM-dd */
    suspend fun maxDate(): String? = dao.maxDate()
}
