// app/src/main/java/com/schedulecalendar/app/data/db/dao/ShiftBreakDao.kt
package com.schedulecalendar.app.data.db.dao

import androidx.room.*
import com.schedulecalendar.app.data.db.entity.ShiftBreakEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ShiftBreakDao {
    /** 仅查询有效（未归档）的时段 */
    @Query("SELECT * FROM shift_breaks WHERE archivedAt IS NULL ORDER BY startTime ASC")
    fun observeActive(): Flow<List<ShiftBreakEntity>>

    @Query("SELECT * FROM shift_breaks ORDER BY startTime ASC")
    fun observeAll(): Flow<List<ShiftBreakEntity>>

    @Query("SELECT * FROM shift_breaks WHERE archivedAt IS NULL ORDER BY startTime ASC")
    suspend fun getAll(): List<ShiftBreakEntity>

    @Query("SELECT * FROM shift_breaks ORDER BY startTime ASC")
    suspend fun getAllWithArchived(): List<ShiftBreakEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ShiftBreakEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ShiftBreakEntity>)

    /** 归档项目（逻辑删除） */
    @Query("UPDATE shift_breaks SET archivedAt = :archivedAt WHERE id = :id")
    suspend fun archiveById(id: String, archivedAt: String)

    @Query("DELETE FROM shift_breaks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM shift_breaks")
    suspend fun deleteAll()
}
