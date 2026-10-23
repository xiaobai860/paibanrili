// app/src/main/java/com/schedulecalendar/app/data/repository/ShiftStatusRepository.kt
package com.schedulecalendar.app.data.repository

import com.schedulecalendar.app.data.db.dao.ShiftStatusDao
import com.schedulecalendar.app.domain.model.BUILTIN_STATUSES
import com.schedulecalendar.app.domain.model.ShiftStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
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

    private suspend fun notifyChanged() = _changeSignal.notifyChangedOrLog("ShiftStatusRepo")

    /** 观察有效（未归档）的状态 */
    fun observeActive(): Flow<List<ShiftStatus>> =
        dao.observeActive().map { list -> list.map { it.toDomain() } }

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

    /**
     * 获取**有效（未归档）**状态 + 内置状态，用于排班选择、小组件数据匹配等场景。
     * ⚠️ 历史命名坑：旧实现返回含已归档状态，与 [observeAllWithBuiltin]（仅有效）语义不一致，
     * 已统一为「仅有效状态」；如需含归档请用 [getAllIncludingArchivedWithBuiltin]。
     */
    suspend fun getAllWithBuiltin(): List<ShiftStatus> {
        val builtinIds = BUILTIN_STATUSES.map { it.id }.toSet()
        return BUILTIN_STATUSES + dao.observeActive().first()
            .map { it.toDomain() }
            .filter { it.id !in builtinIds }
    }

    /** 获取所有状态（**含已归档**）+ 内置状态，仅用于历史数据查找 */
    suspend fun getAllIncludingArchivedWithBuiltin(): List<ShiftStatus> {
        val builtinIds = BUILTIN_STATUSES.map { it.id }.toSet()
        return BUILTIN_STATUSES + dao.getAll().map { it.toDomain() }.filter { it.id !in builtinIds }
    }

    /** 保证内置状态存在（首次启动调用） */
    suspend fun ensureBuiltins() {
        val existing = dao.getAll().map { it.id }.toSet()
        // 用 upsertAll 单次批量写入，避免首次启动 N 次独立写事务
        val missing = BUILTIN_STATUSES.filter { it.id !in existing }
        if (missing.isNotEmpty()) dao.upsertAll(missing.map { it.toEntity() })
    }

    /**
     * 保存状态。
     * @return true=已写入；false=被跳过（内置状态不可写）。
     */
    suspend fun save(item: ShiftStatus): Boolean {
        if (item.builtIn) {
            android.util.Log.w("ShiftStatusRepo", "skip save of builtIn status: ${item.id}")
            return false
        }
        dao.upsert(item.toEntity()); notifyChanged()
        return true
    }
    /** 归档项目（逻辑删除） */
    suspend fun archive(id: String) { dao.archiveById(id, java.time.Instant.now().toString()); notifyChanged() }
    suspend fun delete(id: String) { dao.deleteById(id); notifyChanged() }
    suspend fun deleteAllUserDefined() { dao.deleteAllUserDefined(); notifyChanged() }
}
