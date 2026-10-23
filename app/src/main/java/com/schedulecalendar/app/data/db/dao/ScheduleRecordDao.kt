// app/src/main/java/com/schedulecalendar/app/data/db/dao/ScheduleRecordDao.kt
package com.schedulecalendar.app.data.db.dao

import androidx.room.*
import com.schedulecalendar.app.data.db.entity.ScheduleRecordEntity
import kotlinx.coroutines.flow.Flow

// 用范围查询替代 `LIKE 'yyyy-MM%'`：SQLite 默认 LIKE 大小写不敏感，前缀 LIKE 无法走索引，
// 而 schedule_records 是最大的表，LIKE 会导致每月一次全表扫描。
// 范围查询可直接利用 date 主键索引做区间扫描，同时天然免疫 `%` / `_` 通配符注入。
@Dao
interface ScheduleRecordDao {
    @Query("SELECT * FROM schedule_records WHERE date >= :rangeStart AND date <= :rangeEnd ORDER BY date ASC")
    suspend fun getByMonth(rangeStart: String, rangeEnd: String): List<ScheduleRecordEntity>

    @Query("SELECT * FROM schedule_records WHERE date = :date")
    suspend fun getByDate(date: String): ScheduleRecordEntity?

    @Query("SELECT * FROM schedule_records WHERE date >= :from AND date <= :to ORDER BY date ASC")
    fun observeByRange(from: String, to: String): Flow<List<ScheduleRecordEntity>>

    @Query("SELECT * FROM schedule_records WHERE date >= :from AND date <= :to ORDER BY date ASC")
    suspend fun getByRange(from: String, to: String): List<ScheduleRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: ScheduleRecordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(records: List<ScheduleRecordEntity>)

    @Query("DELETE FROM schedule_records WHERE date = :date")
    suspend fun deleteByDate(date: String)

    /**
     * 批量按日期删除（单事务）。
     * 逐日调用 deleteByDate 会产生 N 次写事务，且中途失败会留下部分删除的状态。
     */
    @Transaction
    suspend fun deleteByDates(dates: List<String>) {
        dates.forEach { deleteByDate(it) }
    }

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
