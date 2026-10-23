// app/src/main/java/com/schedulecalendar/app/data/repository/CalcProfileRepository.kt
package com.schedulecalendar.app.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.schedulecalendar.app.data.db.dao.CalcProfileDao
import com.schedulecalendar.app.data.db.entity.CalcProfileEntity
import com.schedulecalendar.app.data.prefs.AppPreferences
import com.schedulecalendar.app.domain.model.AttendConfig
import com.schedulecalendar.app.domain.model.CalcProfile
import com.schedulecalendar.app.domain.model.SalaryConfig
import com.schedulecalendar.app.domain.model.ShiftBreak
import com.schedulecalendar.app.domain.util.DateFormats
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 计算口径档案仓库。
 *
 * 核心不变式：
 * 1. **任一日期最多命中一个档案**（区间由 effectiveFrom 接力推导，不允许空洞/重叠）；
 * 2. 档案只增不改（已生效的档案不可编辑，只能追加新档案）→ 历史口径永不被改写；
 * 3. 初始档案生效日为 2000-01-01，覆盖全部历史数据。
 */
@Singleton
class CalcProfileRepository @Inject constructor(
    private val dao: CalcProfileDao,
    private val prefs: AppPreferences,
    private val breakRepo: ShiftBreakRepository
) {
    private val gson = Gson()

    companion object {
        /** 初始档案 id（固定，便于幂等判定与恢复兜底） */
        const val INITIAL_PROFILE_ID = "profile_initial"
        /** 初始档案生效日：覆盖全部历史排班 */
        const val INITIAL_EFFECTIVE_FROM = "2000-01-01"
    }

    suspend fun getAll(): List<CalcProfile> = dao.getAll().map { it.toDomain() }

    /** 取某天生效的档案（没有则 null，调用方需回退到当前 DataStore 现值） */
    suspend fun getEffectiveOn(date: String): CalcProfile? = dao.getEffectiveOn(date)?.toDomain()

    /** 取区间内涉及的档案（按生效日升序，通常 1~2 条） */
    suspend fun getInRange(from: String, to: String): List<CalcProfile> =
        dao.getInRange(from, to).map { it.toDomain() }

    /** 今天生效的档案（设置页「当前口径」卡片用） */
    suspend fun getCurrentProfile(): CalcProfile? =
        getEffectiveOn(DateFormats.date(LocalDate.now()))

    /**
     * 幂等创建初始档案。
     *
     * 内容必须与**迁移时刻**的 DataStore 现值 + 当时的有效休息时段完全一致，
     * 否则迁移后历史月份的工时/薪资会变（这是本改造最关键的验收点）。
     */
    suspend fun ensureInitialProfile(): Boolean {
        if (dao.count() > 0) return false
        val profile = CalcProfile(
            id          = INITIAL_PROFILE_ID,
            name        = "初始口径",
            salary      = prefs.salaryConfigFlow.first(),
            attend      = prefs.attendConfigFlow.first(),
            breaks      = breakRepo.getAll(),
            effectiveFrom = INITIAL_EFFECTIVE_FROM,
            effectiveTo = null,
            note        = "系统迁移时创建，包含迁移前的全部配置",
            createdAt   = System.currentTimeMillis()
        )
        dao.upsert(profile.toEntity())
        return true
    }

    /**
     * 新建档案并维护区间接力：
     * - 生效日之前「正在生效」的那条档案，其 effectiveTo 收口为新档案生效日的前一天；
     * - 新档案 effectiveTo = null（接续到下一个档案生效前）。
     */
    suspend fun createProfile(
        name: String,
        salary: SalaryConfig,
        attend: AttendConfig,
        breaks: List<ShiftBreak>,
        effectiveFrom: String,
        note: String? = null
    ): CalcProfile {
        val current = dao.getEffectiveOn(effectiveFrom)
        if (current != null && current.effectiveTo == null) {
            dao.update(current.copy(effectiveTo = dayBefore(effectiveFrom)))
        }
        val profile = CalcProfile(
            id          = "profile_${System.currentTimeMillis()}",
            name        = name,
            salary      = salary,
            attend      = attend,
            breaks      = breaks,
            effectiveFrom = effectiveFrom,
            effectiveTo = null,
            note        = note,
            createdAt   = System.currentTimeMillis()
        )
        dao.upsert(profile.toEntity())
        return profile
    }

    /**
     * 归档档案（= 停用）。
     * 若归档的是「当前正在生效」的档案，会自动以归档日接续一份同内容副本，
     * 避免出现「某段日期没有任何口径可用」的空档。
     */
    suspend fun archiveProfile(id: String, atDate: String): Boolean {
        val target = dao.getAll().firstOrNull { it.id == id } ?: return false
        if (target.id == INITIAL_PROFILE_ID) return false
        val wasCurrent = target.effectiveTo == null
        dao.update(target.copy(effectiveTo = dayBefore(atDate)))
        if (wasCurrent) {
            dao.upsert(
                target.copy(
                    id            = "${target.id}_succ_${System.currentTimeMillis()}",
                    name          = target.name,
                    effectiveFrom = atDate,
                    effectiveTo   = null,
                    createdAt     = System.currentTimeMillis()
                )
            )
        }
        return true
    }

    /** 整表替换（备份恢复用） */
    suspend fun replaceAll(profiles: List<CalcProfile>) {
        dao.deleteAll()
        if (profiles.isNotEmpty()) dao.upsertAll(profiles.map { it.toEntity() })
    }

    private fun dayBefore(date: String): String =
        runCatching { LocalDate.parse(date).minusDays(1).toString() }.getOrDefault(date)

    // ── 映射 ────────────────────────────────────────────────────────────────
    private fun CalcProfileEntity.toDomain(): CalcProfile = CalcProfile(
        id            = id,
        name          = name,
        salary        = runCatching { gson.fromJson(salaryJson, SalaryConfig::class.java) }.getOrNull() ?: SalaryConfig(),
        attend        = runCatching { gson.fromJson(attendJson, AttendConfig::class.java) }.getOrNull() ?: AttendConfig(),
        breaks        = runCatching {
            val type = object : TypeToken<List<ShiftBreak>>() {}.type
            gson.fromJson<List<ShiftBreak>>(breaksJson, type) ?: emptyList()
        }.getOrDefault(emptyList()),
        effectiveFrom = effectiveFrom,
        effectiveTo   = effectiveTo,
        note          = note,
        createdAt     = createdAt
    )

    private fun CalcProfile.toEntity(): CalcProfileEntity = CalcProfileEntity(
        id            = id,
        name          = name,
        salaryJson    = gson.toJson(salary),
        attendJson    = gson.toJson(attend),
        breaksJson    = gson.toJson(breaks),
        effectiveFrom = effectiveFrom,
        effectiveTo   = effectiveTo,
        note          = note,
        createdAt     = createdAt
    )
}
 
