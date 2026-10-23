// app/src/main/java/com/schedulecalendar/app/data/db/dao/ScheduleRecordDao.kt
package com.schedulecalendar.app.data.db.dao

import androidx.room.*
import com.schedulecalendar.app.data.db.entity.ScheduleRecordEntity
import kotlinx.coroutines.flow.Flow

// 用范围查询替代 `LIKE 'yyyy-MM%'`：SQLite 默认 LIKE 大小写不敏感，前缀 LIKE 无法走索引，
// 而 schedule_records 是最大的表，LIKE 会导致每月一次全表扫描。
// 范围查询可直接利用 date 主键索引做区间扫描，同时天然免疫 `%` / `_` 通配符注入。
/**
 * 排班记录中「被引用信息」的投影（只取需要的两列）。
 *
 * 废弃数据清理只需要「哪些班次/状态/补贴项被排班引用」，
 * 整表读取所有列并映射成领域对象（getAll）代价大、内存占用高。
 * 注意：附加状态与补贴项以 JSON 字符串存储，无法用 SQL DISTINCT，
 * 因此这里只取这两列交给上层解析。
 */
data class ScheduleReferenceColumns(
    val appliedStatusesJson: String,
    val extraItemIdsJson: String
)

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

    /** 被引用的班次 ID（去重）：班次存在独立列，可直接 DISTINCT */
    @Query("SELECT DISTINCT shiftId FROM schedule_records WHERE shiftId IS NOT NULL")
    suspend fun distinctShiftIds(): List<String>

    /** 只取附加状态/补贴项两列 JSON（这两列是 JSON 文本，无法用 SQL DISTINCT） */
    @Query("SELECT appliedStatusesJson, extraItemIdsJson FROM schedule_records")
    suspend fun getReferenceColumns(): List<ScheduleReferenceColumns>

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
