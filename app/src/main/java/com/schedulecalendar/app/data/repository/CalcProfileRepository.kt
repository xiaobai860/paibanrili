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
    private val breakRepo: ShiftBreakRepository,
    private val scheduleRepo: ScheduleRepository
) {
    private val gson = Gson()

    companion object {
        /** 初始档案 id（固定，便于幂等判定与恢复兜底） */
        const val INITIAL_PROFILE_ID = "profile_initial"
        /** 初始档案生效日：覆盖全部历史排班 */
        const val INITIAL_EFFECTIVE_FROM = "2000-01-01"
    }

    suspend fun getAll(): List<CalcProfile> = dao.getAll().map { it.toDomain() }

    /** 列表展示用：最新生效的在前 */
    suspend fun getAllDesc(): List<CalcProfile> = dao.getAllDesc().map { it.toDomain() }

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
     * 基于当前生效档案**派生**一个新档案（用户改薪资/考勤时的入口）。
     *
     * 规则：**全量复制当前档案**再替换指定项 → 用户没改的项（另一类配置、休息时段）不会丢。
     * 新档案的生效日由用户选择；生效日之前的历史继续用旧档案 → 历史计算结果不变。
     */
    suspend fun createDerivedProfile(
        effectiveFrom: String,
        name: String,
        salary: SalaryConfig? = null,
        attend: AttendConfig? = null,
        breaks: List<ShiftBreak>? = null
    ): CalcProfile {
        val base = getEffectiveOn(effectiveFrom) ?: getCurrentProfile()
        val baseSalary = base?.salary ?: prefs.salaryConfigFlow.first()
        val baseAttend = base?.attend ?: prefs.attendConfigFlow.first()
        val baseBreaks = breaks ?: base?.breaks ?: breakRepo.getAll()
        return createProfile(
            name    = name,
            salary  = salary ?: baseSalary,
            attend  = attend ?: baseAttend,
            breaks  = baseBreaks,
            effectiveFrom = effectiveFrom
        )
    }

    /**
     * 新建档案并维护区间接力：
     * - 原本延伸到「至今」的档案，其 effectiveTo 收口为新档案生效日的前一天；
     * - 若存在**更晚生效**的档案，新档案的 effectiveTo 只能到它前一天（否则区间重叠）；
     * - 收口后区间变空的档案（from > to，说明它从未真正生效过，例如同一天内反复改配置）
     *   直接删除，避免留下"今天生效、昨天失效"这种倒挂区间污染列表。
     */
    suspend fun createProfile(
        name: String,
        salary: SalaryConfig,
        attend: AttendConfig,
        breaks: List<ShiftBreak>,
        effectiveFrom: String,
        note: String? = null
    ): CalcProfile {
        val all = dao.getAll().map { it.toDomain() }
        // 0) **相邻去重**：新档案内容与「紧邻的上一条」完全一致 → 不新建（只跟相邻的那条比，不跨条数）。
        //    上一条按（生效日, 创建时间）取最大 —— 新生效日是"今天"时它就是当前生效档案
        //    （effectiveTo == null）；此时它已覆盖新生效日，无需任何改动。
        //    ⚠️ 必须用 `<=`：若用 `<` 会排除当天生效的那条，导致"复制当前口径"永远走到新建分支。
        //    若上一条是历史档案（内容不同）则正常新建，由下面的收口逻辑维护接力。
        val prev = all.filter { it.effectiveFrom <= effectiveFrom }
            .maxWithOrNull(compareBy({ it.effectiveFrom }, { it.createdAt }))
        if (prev != null && prev.effectiveTo == null &&
            prev.salary == salary && prev.attend == attend && prev.breaks == breaks
        ) {
            return prev
        }
        // 1) 收口原本延伸到「至今」的档案（生效日 <= 新档案生效日的那些）
        //    - 生效日 **早于** 新档案 → to = 新档案生效日 − 1（正常接力）
        //    - 生效日 **等于** 新档案（同一天内多次改配置）→ to = 它自己的生效日，
        //      即区间收成「当天一天」，**保留记录不倒挂、不删除**（用户规则：数据不一样就生成一条）
        //    ⚠️ 只收口「生效日 <= 新档案生效日」的；更晚生效的档案由第 3 步处理，不能动。
        all.filter { it.effectiveTo == null && it.effectiveFrom <= effectiveFrom }
            .forEach {
                val closedTo = maxOf(it.effectiveFrom, dayBefore(effectiveFrom))
                dao.update(it.copy(effectiveTo = closedTo).toEntity())
            }
        // 2) 新档案的终点：若已有更晚生效的档案，到它前一天为止
        val nextFuture = dao.getAll()
            .filter { it.effectiveFrom > effectiveFrom }
            .minByOrNull { it.effectiveFrom }
        val profile = CalcProfile(
            id          = "profile_${System.currentTimeMillis()}",
            name        = name,
            salary      = salary,
            attend      = attend,
            breaks      = breaks,
            effectiveFrom = effectiveFrom,
            effectiveTo = nextFuture?.let { dayBefore(it.effectiveFrom) },
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

    /** 档案是否可安全删除的判定结果 */
    sealed class ProfileDeletability {
        object Deletable : ProfileDeletability()
        data class NotDeletable(val reason: String) : ProfileDeletability()
    }

    /**
     * 判断某档案能否**安全删除**。
     *
     * 允许删除的唯一情形：**今天（或将来）才生效，且该区间内还没有任何排班记录** ——
     * 那些日期本来就没有数据，回退到上一份口径不会让任何工时/薪资发生变化。
     * 其余情况一律不可删（删了等于让那段历史改用别的口径重算）。
     */
    suspend fun deletability(profile: CalcProfile): ProfileDeletability {
        // 规则（用户 2026-10-23 定）：**只允许删除当前生效的口径**。
        // 历史口径承载着那段时间的计算结果，删掉会让那些日期改用别的口径重算 → 一律不给删。
        if (profile.effectiveTo != null) {
            return ProfileDeletability.NotDeletable("历史口径不能删除（它承载着那段时间的计算结果）")
        }
        if (profile.id == INITIAL_PROFILE_ID) {
            return ProfileDeletability.NotDeletable("初始口径覆盖全部历史，不能删除")
        }
        return ProfileDeletability.Deletable
    }

    /**
     * 该口径的生效区间内已有多少条排班记录用它算过。
     * 仅用于删除前的**告知**（有数据时删除会让这些记录改用上一份口径重算）。
     */
    suspend fun affectedRecordCount(profile: CalcProfile): Int =
        runCatching { scheduleRepo.countRecordsSince(profile.effectiveFrom) }.getOrDefault(0)

    /**
     * 删除一条「今天才生效、区间内无数据」的口径，并把前一份口径的接力恢复为「至今」。
     *
     * 背景：新建档案时会把上一条的 effectiveTo 收口为「新档案生效日 − 1 天」，
     * 故删掉新档案后必须把上一条的 effectiveTo 恢复为 null，否则今天起会没有档案可用。
     */
    suspend fun deleteIfUnused(profile: CalcProfile): Boolean {
        if (deletability(profile) !is ProfileDeletability.Deletable) return false
        // 前一条 = 生效日不晚于它的档案里，按（生效日, 创建时间）取最大的一条。
        // 用 <= 是为了**同日场景**：删掉当前生效的那条后，同一天早些时候创建的那条接管。
        val prev = dao.getAll()
            .filter { it.effectiveFrom <= profile.effectiveFrom && it.id != profile.id }
            .maxWithOrNull(compareBy({ it.effectiveFrom }, { it.createdAt }))
        if (prev != null && prev.effectiveTo != null) {
            val next = dao.getAll()
                .filter { it.effectiveFrom > profile.effectiveFrom }
                .minByOrNull { it.effectiveFrom }
            // 接管区间：有下一条则到它前一天，否则延伸到「至今」
            dao.update(prev.copy(effectiveTo = next?.let { dayBefore(it.effectiveFrom) }))
        }
        dao.deleteById(profile.id)
        return true
    }

    /**
     * 接力自愈：只做一件事 —— 按（生效日, 创建时间）排序后**重设 effectiveTo**，
     * 保证「只有最后一条是 null（至今）」且相邻档案首尾相接。
     *
     * 用途：修复备份恢复带来的坏数据（旧版本导出时可能多条 effectiveTo == null）。
     *
     * ⚠️ **不删任何档案、不去重、不清空区间**：用户规则是「数据不一样就生成一条」，
     * 同日多条属于合法的历史记录（当天改过几次就留几条），计算侧靠 createdAt 取最新。
     * 因此自愈只修「谁该是当前生效」这个唯一的事实，不做任何数据取舍。
     */
    suspend fun repairRelay(): Int {
        val all = dao.getAll().sortedWith(compareBy({ it.effectiveFrom }, { it.createdAt }))
        if (all.size < 2) return 0
        var fixed = 0
        for (i in all.indices) {
            // 末条 → 至今；其余 → 下一条生效日 − 1，但**不早于自身生效日**
            // （同日多条时收成「当天一天」，保留记录不倒挂）
            val expected = all.getOrNull(i + 1)
                ?.let { maxOf(all[i].effectiveFrom, dayBefore(it.effectiveFrom)) }
            if (all[i].effectiveTo != expected) {
                dao.update(all[i].copy(effectiveTo = expected))
                fixed++
            }
        }
        return fixed
    }

    suspend fun deleteAll() { dao.deleteAll() }

    /** 整表替换（备份恢复用） */
    suspend fun replaceAll(profiles: List<CalcProfile>) {
        dao.deleteAll()
        if (profiles.isNotEmpty()) dao.upsertAll(profiles.map { it.toEntity() })
        // 备份里的档案可能来自不同版本（区间重叠/留空/倒挂都出现过），
        // 恢复后按同一套规则重算接力，避免"恢复完计算结果就变了"
        repairRelay()
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
 
