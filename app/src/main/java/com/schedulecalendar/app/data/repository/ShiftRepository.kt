// app/src/main/java/com/schedulecalendar/app/data/repository/ShiftRepository.kt
package com.schedulecalendar.app.data.repository
import com.schedulecalendar.app.domain.util.AppLog
import com.schedulecalendar.app.domain.util.LogTags

import com.schedulecalendar.app.data.db.dao.ShiftDao
import com.schedulecalendar.app.domain.model.BUILTIN_SHIFTS
import com.schedulecalendar.app.domain.model.Shift
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShiftRepository @Inject constructor(
    private val dao: ShiftDao
) {
    /** 班次数据变更信号：写操作后发出，供小组件等全局监听者响应 */
    private val _changeSignal = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)
    val changeSignal: Flow<Unit> = _changeSignal.asSharedFlow()

    private fun notifyChanged() {
        if (!_changeSignal.tryNotifyChanged()) {
            AppLog.w(LogTags.SHIFT_REPOSITORY, "changeSignal emit dropped")
        }
    }

    /** 观察有效（未归档）的班次 */
    fun observeActive(): Flow<List<Shift>> = dao.observeActive().map { list -> list.map { it.toDomain() } }
    /** 观察所有班次（含已归档 + 内置班次，用于日历网格历史数据展示查找） */
    fun observeAll(): Flow<List<Shift>> = dao.observeAll().map { list ->
        val builtinIds = BUILTIN_SHIFTS.map { it.id }.toSet()
        BUILTIN_SHIFTS + list.filter { it.id !in builtinIds }.map { it.toDomain() }
    }
    suspend fun getAll(): List<Shift> = dao.getAll().map { it.toDomain() }
    /** 班次总数（轻量指纹查询） */
    suspend fun countAll(): Int = dao.countAll()

    /**
     * 获取**有效（未归档）**班次 + 内置班次，用于排班选择、小组件数据匹配等场景。
     *
     * ⚠️ 历史命名坑：旧名 [getAllWithBuiltin] 实际返回含已归档班次，而 [observeAllWithBuiltin]
     * 只返回有效班次，同名 API 语义不一致，导致调用方（尤其小组件）可能匹配到已归档班次。
     * 现已统一为「仅有效班次」；如需含归档，请用 [getAllIncludingArchivedWithBuiltin]。
     */
    suspend fun getAllWithBuiltin(): List<Shift> {
        val builtinIds = BUILTIN_SHIFTS.map { it.id }.toSet()
        return BUILTIN_SHIFTS + dao.observeActive().first()
            .map { it.toDomain() }
            .filter { it.id !in builtinIds }
    }

    /** 获取所有班次（**含已归档**）+ 内置班次，仅用于历史数据查找 */
    suspend fun getAllIncludingArchivedWithBuiltin(): List<Shift> =
        BUILTIN_SHIFTS + dao.getAll().map { it.toDomain() }

    fun observeAllWithBuiltin(): Flow<List<Shift>> {
        val builtinIds = BUILTIN_SHIFTS.map { it.id }.toSet()
        return dao.observeActive().map { list ->
            BUILTIN_SHIFTS + list.filter { it.id !in builtinIds }.map { it.toDomain() }
        }
    }

    suspend fun getById(id: String): Shift? =
        BUILTIN_SHIFTS.find { it.id == id } ?: dao.getById(id)?.toDomain()

    /**
     * 保存班次。
     * @return true=已写入；false=被跳过（内置班次不可写，或列表为空）。
     *         内置班次静默跳过会让调用方误以为保存成功，故明确返回结果。
     */
    suspend fun save(shift: Shift): Boolean {
        if (shift.builtIn) {
            AppLog.w(LogTags.SHIFT_REPOSITORY, "skip save of builtIn shift: ${shift.id}")
            return false
        }
        dao.upsert(shift.toEntity()); notifyChanged()
        return true
    }

    suspend fun saveAll(shifts: List<Shift>): Boolean {
        val list = shifts.filter { !it.builtIn }.map { it.toEntity() }
        if (list.isEmpty()) return false
        dao.upsertAll(list); notifyChanged()
        return true
    }
    /** 归档项目（逻辑删除） */
    suspend fun archive(id: String) { dao.archiveById(id, java.time.Instant.now().toString()); notifyChanged() }
    suspend fun delete(id: String) { dao.deleteById(id); notifyChanged() }
    suspend fun deleteAll() { dao.deleteAll(); notifyChanged() }
}
