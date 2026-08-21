// app/src/main/java/com/schedulecalendar/app/data/db/dao/ScheduleRecordDao.kt
package com.schedulecalendar.app.data.db.dao

import androidx.room.*
import com.schedulecalendar.app.data.db.entity.ScheduleRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduleRecordDao {
    @Query("SELECT * FROM schedule_records WHERE date LIKE :yearMonth || '%' ORDER BY date ASC")
    fun observeByMonth(yearMonth: String): Flow<List<ScheduleRecordEntity>>

    @Query("SELECT * FROM schedule_records WHERE date LIKE :yearMonth || '%' ORDER BY date ASC")
    suspend fun getByMonth(yearMonth: String): List<ScheduleRecordEntity>

    @Query("SELECT * FROM schedule_records WHERE date = :date")
    suspend fun getByDate(date: String): ScheduleRecordEntity?

    @Query("SELECT * FROM schedule_records WHERE date >= :from AND date <= :to ORDER BY date ASC")
    fun observeByRange(from: String, to: String): Flow<List<ScheduleRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: ScheduleRecordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(records: List<ScheduleRecordEntity>)

    @Query("DELETE FROM schedule_records WHERE date = :date")
    suspend fun deleteByDate(date: String)

    @Query("DELETE FROM schedule_records WHERE date >= :from AND date <= :to")
    suspend fun deleteByRange(from: String, to: String)

    @Query("SELECT * FROM schedule_records")
    suspend fun getAll(): List<ScheduleRecordEntity>

    @Query("DELETE FROM schedule_records")
    suspend fun deleteAll()

    /** 排班总数（轻量指纹查询，比 getAll 快 1000 倍） */
    @Query("SELECT COUNT(*) FROM schedule_records")
    suspend fun countAll(): Int

    /** 最近排班日期 yyyy-MM-dd（轻量指纹查询） */
    @Query("SELECT MAX(date) FROM schedule_records")
    suspend fun maxDate(): String?
}
