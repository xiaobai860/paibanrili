// app/src/main/java/com/schedulecalendar/app/data/db/dao/ExtraItemDao.kt
package com.schedulecalendar.app.data.db.dao

import androidx.room.*
import com.schedulecalendar.app.data.db.entity.ExtraItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExtraItemDao {
    /** 仅查询有效（未归档）的项目 */
    @Query("SELECT * FROM extra_items WHERE archivedAt IS NULL ORDER BY name ASC")
    fun observeActive(): Flow<List<ExtraItemEntity>>

    /** 查询所有项目（含已归档），用于薪资计算时查找历史金额 */
    @Query("SELECT * FROM extra_items ORDER BY name ASC")
    fun observeAll(): Flow<List<ExtraItemEntity>>

    @Query("SELECT * FROM extra_items ORDER BY name ASC")
    suspend fun getAll(): List<ExtraItemEntity>

    /** 查询所有项目（含已归档），用于薪资计算 */
    @Query("SELECT * FROM extra_items")
    suspend fun getAllIncludingArchived(): List<ExtraItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ExtraItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ExtraItemEntity>)

    /** 归档项目（逻辑删除） */
    @Query("UPDATE extra_items SET archivedAt = :archivedAt WHERE id = :id")
    suspend fun archiveById(id: String, archivedAt: String)

    @Query("DELETE FROM extra_items WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM extra_items")
    suspend fun deleteAll()

    /** 补贴扣款总数（轻量指纹查询） */
    @Query("SELECT COUNT(*) FROM extra_items")
    suspend fun countAll(): Int
}
