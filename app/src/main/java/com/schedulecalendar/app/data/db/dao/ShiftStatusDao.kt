// app/src/main/java/com/schedulecalendar/app/data/db/dao/ShiftStatusDao.kt
package com.schedulecalendar.app.data.db.dao

import androidx.room.*
import com.schedulecalendar.app.data.db.entity.ShiftStatusEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ShiftStatusDao {
    /** 仅查询有效（未归档）的状态 */
    @Query("SELECT * FROM shift_statuses WHERE archivedAt IS NULL ORDER BY builtIn DESC, name ASC")
    fun observeActive(): Flow<List<ShiftStatusEntity>>

    @Query("SELECT * FROM shift_statuses ORDER BY builtIn DESC, name ASC")
    fun observeAll(): Flow<List<ShiftStatusEntity>>

    @Query("SELECT * FROM shift_statuses ORDER BY builtIn DESC, name ASC")
    suspend fun getAll(): List<ShiftStatusEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ShiftStatusEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ShiftStatusEntity>)

    /** 归档项目（逻辑删除） */
    @Query("UPDATE shift_statuses SET archivedAt = :archivedAt WHERE id = :id")
    suspend fun archiveById(id: String, archivedAt: String)

    @Query("DELETE FROM shift_statuses WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM shift_statuses WHERE builtIn = 0")
    suspend fun deleteAllUserDefined()

    @Query("DELETE FROM shift_statuses")
    suspend fun deleteAll()
}
