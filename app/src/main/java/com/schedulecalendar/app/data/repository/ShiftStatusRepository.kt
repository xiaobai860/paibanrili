// app/src/main/java/com/schedulecalendar/app/data/repository/ShiftStatusRepository.kt
package com.schedulecalendar.app.data.repository

import com.schedulecalendar.app.data.db.dao.ShiftStatusDao
import com.schedulecalendar.app.domain.model.BUILTIN_STATUSES
import com.schedulecalendar.app.domain.model.ShiftStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShiftStatusRepository @Inject constructor(
    private val dao: ShiftStatusDao
) {
    /** 附加状态数据变更信号：写操作后发出，供小组件等全局监听者响应 */
    private val _changeSignal = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)
    val changeSignal: Flow<Unit> = _changeSignal.asSharedFlow()

    private suspend fun notifyChanged() { _changeSignal.tryEmit(Unit) }

    /** 观察有效（未归档）的状态 */
    fun observeActive(): Flow<List<ShiftStatus>> =
        dao.observeActive().map { list -> list.map { it.toDomain() } }

    /** 观察所有状态（仅数据库） */
    fun observeAll(): Flow<List<ShiftStatus>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    /** 观察所有状态（含内置：请假、调休），内置在最前，去重 */
    fun observeAllWithBuiltin(): Flow<List<ShiftStatus>> {
        val builtinIds = BUILTIN_STATUSES.map { it.id }.toSet()
        return dao.observeActive().map { list ->
            BUILTIN_STATUSES + list.filter { it.id !in builtinIds }.map { it.toDomain() }
        }
    }

    suspend fun getAll(): List<ShiftStatus> = dao.getAll().map { it.toDomain() }
    /** 状态总数（轻量指纹查询） */
    suspend fun countAll(): Int = dao.countAll()

    /** 获取所有状态（含内置），用于排班选择等场景 */
    suspend fun getAllWithBuiltin(): List<ShiftStatus> {
        val builtinIds = BUILTIN_STATUSES.map { it.id }.toSet()
        val dbList = dao.getAll().map { it.toDomain() }
        return BUILTIN_STATUSES + dbList.filter { it.id !in builtinIds }
    }

    /** 保证内置状态存在（首次启动调用） */
    suspend fun ensureBuiltins() {
        val existing = dao.getAll().map { it.id }.toSet()
        BUILTIN_STATUSES.filter { it.id !in existing }
            .forEach { dao.upsert(it.toEntity()) }
    }

    suspend fun save(item: ShiftStatus) {
        if (!item.builtIn) { dao.upsert(item.toEntity()); notifyChanged() }
    }
    /** 归档项目（逻辑删除） */
    suspend fun archive(id: String) { dao.archiveById(id, java.time.Instant.now().toString()); notifyChanged() }
    suspend fun delete(id: String) { dao.deleteById(id); notifyChanged() }
    suspend fun deleteAllUserDefined() { dao.deleteAllUserDefined(); notifyChanged() }
}
