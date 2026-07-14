// app/src/main/java/com/schedulecalendar/app/data/db/dao/ShiftDao.kt
package com.schedulecalendar.app.data.db.dao

import androidx.room.*
import com.schedulecalendar.app.data.db.entity.ShiftEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ShiftDao {
    /** 仅查询有效（未归档）的班次 */
    @Query("SELECT * FROM shifts WHERE archivedAt IS NULL ORDER BY name ASC")
    fun observeActive(): Flow<List<ShiftEntity>>

    @Query("SELECT * FROM shifts ORDER BY name ASC")
    fun observeAll(): Flow<List<ShiftEntity>>

    @Query("SELECT * FROM shifts ORDER BY name ASC")
    suspend fun getAll(): List<ShiftEntity>

    @Query("SELECT * FROM shifts WHERE id = :id")
    suspend fun getById(id: String): ShiftEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(shift: ShiftEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(shifts: List<ShiftEntity>)

    /** 归档项目（逻辑删除） */
    @Query("UPDATE shifts SET archivedAt = :archivedAt WHERE id = :id")
    suspend fun archiveById(id: String, archivedAt: String)

    @Delete
    suspend fun delete(shift: ShiftEntity)

    @Query("DELETE FROM shifts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM shifts")
    suspend fun deleteAll()
}
