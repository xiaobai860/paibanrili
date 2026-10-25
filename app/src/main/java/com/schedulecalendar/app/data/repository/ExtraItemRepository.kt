// app/src/main/java/com/schedulecalendar/app/data/repository/ExtraItemRepository.kt
package com.schedulecalendar.app.data.repository

import com.schedulecalendar.app.data.db.dao.ExtraItemDao
import com.schedulecalendar.app.domain.model.ExtraItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map

@Singleton
class ExtraItemRepository @Inject constructor(
    private val dao: ExtraItemDao
) {
    /**
     * 数据变更信号：写操作后发出。
     * 与 ScheduleRepository/ShiftRepository 保持一致，供「修改补贴项目后刷新小组件」等场景监听
     * （此前该仓库无信号，WidgetSync 收不到补贴项目变更）。
     */
    private val _changeSignal = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)
    val changeSignal: Flow<Unit> = _changeSignal.asSharedFlow()

    private fun notifyChanged() = _changeSignal.notifyChangedOrLog("ExtraItemRepo")

    /** 观察有效（未归档）的项目，用于UI显示 */
    fun observeActive(): Flow<List<ExtraItem>> = dao.observeActive().map { it.map { e -> e.toDomain() } }
    
    /** 获取所有项目（含已归档），用于薪资计算查找历史金额 */
    suspend fun getAll(): List<ExtraItem> = dao.getAllIncludingArchived().map { it.toDomain() }
    /** 补贴扣款总数（轻量指纹查询） */
    suspend fun countAll(): Int = dao.countAll()
    suspend fun save(item: ExtraItem) { dao.upsert(item.toEntity()); notifyChanged() }
    /** 归档项目（逻辑删除） */
    suspend fun archive(id: String) { dao.archiveById(id, java.time.Instant.now().toString()); notifyChanged() }
    suspend fun delete(id: String) { dao.deleteById(id); notifyChanged() }
    suspend fun deleteAll() { dao.deleteAll(); notifyChanged() }
}
