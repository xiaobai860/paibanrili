// app/src/main/java/com/schedulecalendar/app/data/repository/ShiftBreakRepository.kt
package com.schedulecalendar.app.data.repository
import com.schedulecalendar.app.domain.util.AppLog
import com.schedulecalendar.app.domain.util.LogTags

import com.schedulecalendar.app.data.db.dao.ShiftBreakDao
import com.schedulecalendar.app.domain.model.ShiftBreak
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map

@Singleton
class ShiftBreakRepository @Inject constructor(
    private val dao: ShiftBreakDao
) {
    /**
     * 数据变更信号：写操作后发出。
     * 与 ScheduleRepository/ShiftRepository 保持一致，供「修改休息时段后刷新小组件」等场景监听
     * （此前该仓库无信号，WidgetSync 收不到休息时段变更）。
     */
    private val _changeSignal = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)
    val changeSignal: Flow<Unit> = _changeSignal.asSharedFlow()

    private fun notifyChanged() {
        if (!_changeSignal.tryNotifyChanged()) {
            AppLog.w(LogTags.SHIFT_BREAK_REPOSITORY, "changeSignal emit dropped")
        }
    }

    /** 观察有效（未归档）的时段 */
    fun observeActive(): Flow<List<ShiftBreak>> = dao.observeActive().map { it.map { e -> e.toDomain() } }
    suspend fun getAll(): List<ShiftBreak> = dao.getAll().map { it.toDomain() }
    /** 休息时段总数（轻量指纹查询） */
    suspend fun countAll(): Int = dao.countAll()
    suspend fun getAllWithArchived(): List<ShiftBreak> = dao.getAllWithArchived().map { it.toDomain() }
    suspend fun save(item: ShiftBreak) { dao.upsert(item.toEntity()); notifyChanged() }
    /** 归档项目（逻辑删除） */
    suspend fun archive(id: String) { dao.archiveById(id, java.time.Instant.now().toString()); notifyChanged() }
    suspend fun delete(id: String) { dao.deleteById(id); notifyChanged() }
    suspend fun deleteAll() { dao.deleteAll(); notifyChanged() }
}
