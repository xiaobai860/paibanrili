package com.schedulecalendar.app.data.repository

import androidx.room.withTransaction
import com.schedulecalendar.app.data.db.AppDatabase
import com.schedulecalendar.app.data.prefs.AppPreferences
import com.schedulecalendar.app.domain.model.ExtraItem
import com.schedulecalendar.app.domain.model.Shift
import com.schedulecalendar.app.domain.model.ShiftBreak
import com.schedulecalendar.app.domain.model.ShiftStatus
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 废弃数据（「隐藏数据」）的扫描与清理。
 *
 * ## 什么是废弃数据
 * 本项目的「班次 / 附加状态 / 补贴扣款 / 休息时段」都是**逻辑删除**（`archivedAt` 非空）：
 * UI 只显示未归档的，但数据库里那几行还在 —— 于是「应用里 4 个时段，库里 8 行」就出现了。
 *
 * ## 两类必须分清
 * - **可清理**：已归档且**没有任何排班引用** → 纯垃圾，删了不影响任何历史结果；
 * - **必须保留**：已归档但**仍被历史排班引用** → 它是「历史锚点」
 *   （改动班次/状态/项目采用「归档旧记录 + 新编号」，历史排班仍指向旧编号，
 *   删掉它会让历史工时/薪资算不出来）。
 *
 * ## 休息时段的特殊性
 * 休息时段是唯一**没有引用锚点**的实体：历史排班不引用时段，
 * 历史口径由「计算口径档案」里冻结的 JSON 快照决定，读取路径一律用仅有效集合。
 * 因此**已归档的休息时段是纯垃圾，删除绝对安全**（不影响当前计算，也不影响历史计算）。
 */
@Singleton
class OrphanDataCleaner @Inject constructor(
    private val db: AppDatabase,
    private val scheduleRepo: ScheduleRepository,
    private val shiftRepo: ShiftRepository,
    private val statusRepo: ShiftStatusRepository,
    private val extraRepo: ExtraItemRepository,
    private val breakRepo: ShiftBreakRepository,
    private val calcProfileRepo: CalcProfileRepository,
    private val prefs: AppPreferences
) {

    /** 扫描结果 */
    data class Scan(
        /** 可清理：已归档且无引用 */
        val shifts: List<Shift> = emptyList(),
        val statuses: List<ShiftStatus> = emptyList(),
        val extras: List<ExtraItem> = emptyList(),
        val breaks: List<ShiftBreak> = emptyList(),
        /**
         * 可清理但**被历史档案的口径快照引用**的归档时段。
         *
         * 为什么也能删：档案里存的是**自包含的 JSON 快照**（breaksJson），
         * 历史计算读的是那份快照、不读这张表 → 物理删表行不会改变任何历史工时/薪资。
         * 单独分组只为让用户看清"它被哪份档案提到过"，避免误以为删了会毁历史。
         */
        val breaksInProfileSnapshots: List<ShiftBreak> = emptyList(),
        /** 必须保留：已归档但仍被历史排班引用 */
        val keptShifts: List<Shift> = emptyList(),
        val keptStatuses: List<ShiftStatus> = emptyList(),
        val keptExtras: List<ExtraItem> = emptyList()
    ) {
        /** 可清理的总条数 */
        val cleanableCount: Int get() =
            shifts.size + statuses.size + extras.size + breaks.size + breaksInProfileSnapshots.size

        /** 因历史引用而保留的总条数 */
        val keptCount: Int get() = keptShifts.size + keptStatuses.size + keptExtras.size
    }

    /**
     * 配置一致性自检：DataStore 现值、当前生效时段集合 与「当前生效口径档案」是否一致。
     *
     * 薪资/考勤配置只有两份存储：DataStore 现值（各一份，覆盖写）+ 口径档案里的冻结快照。
     * 它们**必须**保持一致，否则用户在设置页看到的值不参与计算（现值被旧档案盖住）。
     * 正常路径下「改配置 → 先建档案 → 再写现值」保证一致；
     * 只有「导入旧备份（不带档案）」这类外部写入才可能造成分裂。
     *
     * 休息时段同理：档案冻结的是**集合快照**，表里是**编辑中集合**，
     * 两者不一致时每次「按档案同步时段集合」都会把多出来的时段重新归档 →
     * 刚清理掉的废弃数据会**再出现**（同一行，不是新增行）。
     *
     * @return null = 全部一致；否则返回不一致的说明
     */
    suspend fun checkConfigConsistency(): String? {
        val problems = mutableListOf<String>()
        val cur = calcProfileRepo.getCurrentProfile()
        if (cur == null) {
            problems += "没有当前生效的口径档案（历史计算将回退到现值）"
        } else {
            val salary = prefs.salaryConfigFlow.first()
            val attend = prefs.attendConfigFlow.first()
            if (cur.salary != salary) problems += "薪资配置的现值与当前生效口径不一致（现值不参与计算）"
            if (cur.attend != attend) problems += "考勤配置的现值与当前生效口径不一致（现值不参与计算）"

            // 时段集合：表里的有效集合 vs 档案快照
            val activeIds = breakRepo.getAll().map { it.id }.toSet()
            val profileIds = cur.breaks.map { it.id }.toSet()
            val extra = activeIds - profileIds          // 表里有、口档里没有
            val missing = profileIds - activeIds        // 档案里有、表里没有
            if (extra.isNotEmpty()) {
                problems += "有 ${extra.size} 个休息时段不在当前生效口径里（随时可能被归档/恢复）"
            }
            if (missing.isNotEmpty()) {
                problems += "当前生效口径里的 ${missing.size} 个休息时段未在时段列表中生效"
            }
        }
        return if (problems.isEmpty()) null else problems.joinToString("；")
    }

    /**
     * 扫描废弃数据。
     *
     * ⚠️ 必须用**含归档**集合来筛「已归档」的项：用仅有效集合永远扫不到它们。
     */
    suspend fun scan(): Scan {        // 收集所有排班记录中引用的 shiftId / statusId / extraItemId
        // （走 referencedIds：只读必要列，不整表读、不映射全表对象）
        val (referencedShiftIds, referencedStatusIds, referencedExtraIds) = scheduleRepo.referencedIds()

        val allShifts = shiftRepo.getAll()
        val allStatuses = statusRepo.getAllIncludingArchivedWithBuiltin()
        val allExtras = extraRepo.getAll()
        val archivedBreaks = breakRepo.getAllWithArchived().filter { it.archivedAt != null }

        // 档案快照里出现过的时段 id（仅历史档案可能命中：当前生效档案的时段必然在有效集里）
        val breakIdsInProfiles = calcProfileRepo.getAll()
            .flatMap { it.breaks }
            .map { it.id }
            .toSet()

        return Scan(
            // 可清理：已归档 且 没有排班引用
            shifts = allShifts.filter { it.archivedAt != null && it.id !in referencedShiftIds },
            statuses = allStatuses.filter { it.archivedAt != null && it.id !in referencedStatusIds },
            extras = allExtras.filter { it.archivedAt != null && it.id !in referencedExtraIds },
            // 休息时段无引用锚点：已归档的全部可清；其中被档案快照提到的单独分组（删了同样安全）
            breaks = archivedBreaks.filter { it.id !in breakIdsInProfiles },
            breaksInProfileSnapshots = archivedBreaks.filter { it.id in breakIdsInProfiles },
            // 必须保留：已归档 但仍被历史排班引用
            keptShifts = allShifts.filter { it.archivedAt != null && it.id in referencedShiftIds },
            keptStatuses = allStatuses.filter { it.archivedAt != null && it.id in referencedStatusIds },
            keptExtras = allExtras.filter { it.archivedAt != null && it.id in referencedExtraIds }
        )
    }

    /**
     * 清理废弃数据（物理删除）。
     *
     * 整个清理放在单事务里：逐条删除中途失败会留下「删一半」且不可回滚的状态。
     *
     * @return 实际删除条数
     */
    suspend fun clean(): Int {
        val scan = scan()
        db.withTransaction {
            scan.shifts.forEach { shiftRepo.delete(it.id) }
            // 内置状态是代码常量、不在表内，删了会破坏引用
            scan.statuses.filter { !it.builtIn }.forEach { statusRepo.delete(it.id) }
            scan.extras.forEach { extraRepo.delete(it.id) }
            scan.breaks.forEach { breakRepo.delete(it.id) }
            // 被档案快照引用过的归档时段：快照是自包含 JSON，删表行不影响历史计算 → 同样可删
            scan.breaksInProfileSnapshots.forEach { breakRepo.delete(it.id) }
        }
        return scan.shifts.size +
            scan.statuses.count { !it.builtIn } +
            scan.extras.size +
            scan.breaks.size +
            scan.breaksInProfileSnapshots.size
    }
}

