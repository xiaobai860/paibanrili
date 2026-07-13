// app/src/main/java/com/schedulecalendar/app/data/repository/ShiftBreakRepository.kt
package com.schedulecalendar.app.data.repository

import com.schedulecalendar.app.data.db.dao.ShiftBreakDao
import com.schedulecalendar.app.domain.model.ShiftBreak
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShiftBreakRepository @Inject constructor(
    private val dao: ShiftBreakDao
) {
    /** 观察有效（未归档）的时段 */
    fun observeActive(): Flow<List<ShiftBreak>> = dao.observeActive().map { it.map { e -> e.toDomain() } }
    fun observeAll(): Flow<List<ShiftBreak>> = dao.observeAll().map { it.map { e -> e.toDomain() } }
    suspend fun getAll(): List<ShiftBreak> = dao.getAll().map { it.toDomain() }
    suspend fun getAllWithArchived(): List<ShiftBreak> = dao.getAllWithArchived().map { it.toDomain() }
    suspend fun save(item: ShiftBreak) = dao.upsert(item.toEntity())
    suspend fun saveAll(items: List<ShiftBreak>) = dao.upsertAll(items.map { it.toEntity() })
    /** 归档项目（逻辑删除） */
    suspend fun archive(id: String) = dao.archiveById(id, java.time.Instant.now().toString())
    suspend fun delete(id: String) = dao.deleteById(id)
    suspend fun deleteAll() = dao.deleteAll()
}
