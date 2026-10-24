package com.schedulecalendar.app.data.repository

import androidx.room.withTransaction
import com.schedulecalendar.app.data.db.AppDatabase
import com.schedulecalendar.app.domain.model.ExtraItem
import com.schedulecalendar.app.domain.model.Shift
import com.schedulecalendar.app.domain.model.ShiftBreak
import com.schedulecalendar.app.domain.model.ShiftStatus
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
    private val breakRepo: ShiftBreakRepository
) {

    /** 扫描结果 */
    data class Scan(
        /** 可清理：已归档且无引用 */
        val shifts: List<Shift> = emptyList(),
        val statuses: List<ShiftStatus> = emptyList(),
        val extras: List<ExtraItem> = emptyList(),
        val breaks: List<ShiftBreak> = emptyList(),
        /** 必须保留：已归档但仍被历史排班引用 */
        val keptShifts: List<Shift> = emptyList(),
        val keptStatuses: List<ShiftStatus> = emptyList(),
        val keptExtras: List<ExtraItem> = emptyList()
    ) {
        /** 可清理的总条数 */
        val cleanableCount: Int get() = shifts.size + statuses.size + extras.size + breaks.size

        /** 因历史引用而保留的总条数 */
        val keptCount: Int get() = keptShifts.size + keptStatuses.size + keptExtras.size
    }

    /**
     * 扫描废弃数据。
     *
     * ⚠️ 必须用**含归档**集合来筛「已归档」的项：用仅有效集合永远扫不到它们。
     */
    suspend fun scan(): Scan {
        // 收集所有排班记录中引用的 shiftId / statusId / extraItemId
        // （走 referencedIds：只读必要列，不整表读、不映射全表对象）
        val (referencedShiftIds, referencedStatusIds, referencedExtraIds) = scheduleRepo.referencedIds()

        val allShifts = shiftRepo.getAll()
        val allStatuses = statusRepo.getAllIncludingArchivedWithBuiltin()
        val allExtras = extraRepo.getAll()
        val allBreaks = breakRepo.getAllWithArchived()

        return Scan(
            // 可清理：已归档 且 没有排班引用
            shifts = allShifts.filter { it.archivedAt != null && it.id !in referencedShiftIds },
            statuses = allStatuses.filter { it.archivedAt != null && it.id !in referencedStatusIds },
            extras = allExtras.filter { it.archivedAt != null && it.id !in referencedExtraIds },
            // 休息时段无引用锚点：已归档的全部可清
            breaks = allBreaks.filter { it.archivedAt != null },
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
        }
        return scan.shifts.size +
            scan.statuses.count { !it.builtIn } +
            scan.extras.size +
            scan.breaks.size
    }
}

