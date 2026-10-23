// app/src/main/java/com/schedulecalendar/app/data/db/dao/CalcProfileDao.kt
package com.schedulecalendar.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.schedulecalendar.app.data.db.entity.CalcProfileEntity

@Dao
interface CalcProfileDao {

    @Query("SELECT * FROM calc_profiles ORDER BY effectiveFrom ASC")
    suspend fun getAll(): List<CalcProfileEntity>

    /** 列表展示用：最新生效的在前（UI 要求最新的在最上面） */
    @Query("SELECT * FROM calc_profiles ORDER BY effectiveFrom DESC")
    suspend fun getAllDesc(): List<CalcProfileEntity>

    @Query("SELECT * FROM calc_profiles WHERE effectiveFrom <= :date AND (effectiveTo IS NULL OR effectiveTo >= :date) ORDER BY effectiveFrom ASC LIMIT 1")
    suspend fun getEffectiveOn(date: String): CalcProfileEntity?

    @Query("SELECT * FROM calc_profiles WHERE effectiveFrom <= :to AND (effectiveTo IS NULL OR effectiveTo >= :from) ORDER BY effectiveFrom ASC")
    suspend fun getInRange(from: String, to: String): List<CalcProfileEntity>

    @Query("SELECT COUNT(*) FROM calc_profiles")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: CalcProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(profiles: List<CalcProfileEntity>)

    @Update
    suspend fun update(profile: CalcProfileEntity)

    @Query("DELETE FROM calc_profiles WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM calc_profiles")
    suspend fun deleteAll()
}
 
