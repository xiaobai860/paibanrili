// app/src/main/java/com/schedulecalendar/app/data/repository/ExtraItemRepository.kt
package com.schedulecalendar.app.data.repository

import com.schedulecalendar.app.data.db.dao.ExtraItemDao
import com.schedulecalendar.app.domain.model.ExtraItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExtraItemRepository @Inject constructor(
    private val dao: ExtraItemDao
) {
    /** 观察有效（未归档）的项目，用于UI显示 */
    fun observeActive(): Flow<List<ExtraItem>> = dao.observeActive().map { it.map { e -> e.toDomain() } }
    /** 观察所有项目（含已归档） */
    fun observeAll(): Flow<List<ExtraItem>> = dao.observeAll().map { it.map { e -> e.toDomain() } }
    /** 获取有效项目 */
    suspend fun getActive(): List<ExtraItem> = dao.getAll().filter { it.archivedAt == null }.map { it.toDomain() }
    /** 获取所有项目（含已归档），用于薪资计算查找历史金额 */
    suspend fun getAll(): List<ExtraItem> = dao.getAllIncludingArchived().map { it.toDomain() }
    suspend fun getAllIncludingArchived(): List<ExtraItem> = dao.getAllIncludingArchived().map { it.toDomain() }
    /** 补贴扣款总数（轻量指纹查询） */
    suspend fun countAll(): Int = dao.countAll()
    suspend fun save(item: ExtraItem) = dao.upsert(item.toEntity())
    suspend fun saveAll(items: List<ExtraItem>) = dao.upsertAll(items.map { it.toEntity() })
    /** 归档项目（逻辑删除） */
    suspend fun archive(id: String) = dao.archiveById(id, java.time.Instant.now().toString())
    suspend fun delete(id: String) = dao.deleteById(id)
    suspend fun deleteAll() = dao.deleteAll()
}
