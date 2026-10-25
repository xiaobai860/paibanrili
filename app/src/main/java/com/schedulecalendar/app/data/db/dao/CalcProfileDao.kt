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
    @Query("SELECT * FROM calc_profiles ORDER BY effectiveFrom DESC, createdAt DESC")
    suspend fun getAllDesc(): List<CalcProfileEntity>

    // ⚠️ 同日生效的多条档案：按 createdAt DESC 取**最新创建**的那条。
    // 语义：同一天内多次改配置 → 最后一次才是用户当前意图（早先那些是当天曾生效过的中间态）。
    @Query("SELECT * FROM calc_profiles WHERE effectiveFrom <= :date AND (effectiveTo IS NULL OR effectiveTo >= :date) ORDER BY effectiveFrom ASC, createdAt DESC LIMIT 1")
    suspend fun getEffectiveOn(date: String): CalcProfileEntity?

    /**
     * 取落在区间内的档案。
     *
     * ⚠️ 排序必须与 [getEffectiveOn] 保持一致：`effectiveFrom ASC, createdAt DESC`。
     * 同一天生效的多条档案是**合法状态**（用户规则：数据不一样就生成一条），
     * 此时必须让**最新创建**的那条排在前面 —— 否则「按日分段」会先吃下旧档案、
     * 把当天判给旧口径，而「单日查询」走 [getEffectiveOn] 拿的是新口径
     * → 同一天工时/薪资两处不一致（历史上踩过同类坑）。
     */
    @Query("SELECT * FROM calc_profiles WHERE effectiveFrom <= :to AND (effectiveTo IS NULL OR effectiveTo >= :from) ORDER BY effectiveFrom ASC, createdAt DESC")
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
 
