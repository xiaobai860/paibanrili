// app/src/main/java/com/schedulecalendar/app/data/repository/ShiftRepository.kt
package com.schedulecalendar.app.data.repository

import com.schedulecalendar.app.data.db.dao.ShiftDao
import com.schedulecalendar.app.domain.model.BUILTIN_SHIFTS
import com.schedulecalendar.app.domain.model.Shift
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShiftRepository @Inject constructor(
    private val dao: ShiftDao
) {
    /** 观察有效（未归档）的班次 */
    fun observeActive(): Flow<List<Shift>> = dao.observeActive().map { list -> list.map { it.toDomain() } }
    /** 观察所有班次（含已归档 + 内置班次，用于日历网格历史数据展示查找） */
    fun observeAll(): Flow<List<Shift>> = dao.observeAll().map { list ->
        val builtinIds = BUILTIN_SHIFTS.map { it.id }.toSet()
        BUILTIN_SHIFTS + list.filter { it.id !in builtinIds }.map { it.toDomain() }
    }
    suspend fun getAll(): List<Shift> = dao.getAll().map { it.toDomain() }

    /** 获取所有班次，含内置（休息/调休/请假），用于排班选择 */
    suspend fun getAllWithBuiltin(): List<Shift> = BUILTIN_SHIFTS + dao.getAll().map { it.toDomain() }
    fun observeAllWithBuiltin(): Flow<List<Shift>> {
        val builtinIds = BUILTIN_SHIFTS.map { it.id }.toSet()
        return dao.observeActive().map { list ->
            BUILTIN_SHIFTS + list.filter { it.id !in builtinIds }.map { it.toDomain() }
        }
    }

    suspend fun getById(id: String): Shift? =
        BUILTIN_SHIFTS.find { it.id == id } ?: dao.getById(id)?.toDomain()

    suspend fun save(shift: Shift) { if (!shift.builtIn) dao.upsert(shift.toEntity()) }
    suspend fun saveAll(shifts: List<Shift>) =
        dao.upsertAll(shifts.filter { !it.builtIn }.map { it.toEntity() })
    /** 归档项目（逻辑删除） */
    suspend fun archive(id: String) = dao.archiveById(id, java.time.Instant.now().toString())
    suspend fun delete(id: String) = dao.deleteById(id)
    suspend fun deleteAll() = dao.deleteAll()
}
