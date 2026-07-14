// app/src/main/java/com/schedulecalendar/app/data/repository/ScheduleRepository.kt
package com.schedulecalendar.app.data.repository

import com.schedulecalendar.app.data.db.dao.ScheduleRecordDao
import com.schedulecalendar.app.domain.model.ScheduleRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScheduleRepository @Inject constructor(
    private val dao: ScheduleRecordDao
) {
    fun observeByMonth(yearMonth: String): Flow<List<ScheduleRecord>> =
        dao.observeByMonth(yearMonth).map { list -> list.map { it.toDomain() } }

    suspend fun getByMonth(yearMonth: String): List<ScheduleRecord> =
        dao.getByMonth(yearMonth).map { it.toDomain() }

    fun observeByRange(from: String, to: String): Flow<List<ScheduleRecord>> =
        dao.observeByRange(from, to).map { list -> list.map { it.toDomain() } }

    suspend fun getByDate(date: String): ScheduleRecord? = dao.getByDate(date)?.toDomain()
    suspend fun save(record: ScheduleRecord) = dao.upsert(record.toEntity())
    suspend fun saveAll(records: List<ScheduleRecord>) = dao.upsertAll(records.map { it.toEntity() })
    suspend fun delete(date: String) = dao.deleteByDate(date)
    suspend fun deleteRange(from: String, to: String) = dao.deleteByRange(from, to)
    suspend fun deleteAll() = dao.deleteAll()

    suspend fun getAll(): List<ScheduleRecord> = dao.getAll().map { it.toDomain() }
}
