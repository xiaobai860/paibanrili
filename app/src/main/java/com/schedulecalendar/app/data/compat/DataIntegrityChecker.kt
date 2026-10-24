package com.schedulecalendar.app.data.compat

import com.google.gson.JsonParser
import com.schedulecalendar.app.data.db.dao.CalcProfileDao
import com.schedulecalendar.app.data.db.dao.ExtraItemDao
import com.schedulecalendar.app.data.db.dao.ScheduleRecordDao
import com.schedulecalendar.app.data.db.dao.ShiftBreakDao
import com.schedulecalendar.app.data.db.dao.ShiftDao
import com.schedulecalendar.app.data.db.dao.ShiftStatusDao
import com.schedulecalendar.app.domain.model.BUILTIN_SHIFTS
import com.schedulecalendar.app.domain.model.BUILTIN_STATUSES
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 迁移 / 恢复后的数据校验。
 *
 * 「升级成功」不等于「数据正确」。真正的风险有三类：
 * 1. 数据丢了（行数对不上）——迁移脚本写错、动态重建漏拷贝；
 * 2. 数据坏了（JSON 解析失败）——旧格式残留、归一化脚本改坏了字符串；
 * 3. 口径断了（没有计算口径档案）——历史工时/薪资会回退到 DataStore 现值而算错。
 *
 * 前两类是错误（必须回滚），第三类同样是错误（历史计算会变）。
 * 「引用了已删除的班次/状态」这类历史遗留只报警告——它不影响数据可用性，
 * 且属于用户既有状态，不该在升级时当成失败。
 *
 * ⚠️ 口径铁律：所有行数都用**原始表行数**（DAO 的 COUNT(*)），
 * 与 [LegacyDbSnapshot] 抓快照时的口径严格一致；不可改用仓库层的 getAll()
 * （它会过滤归档项、叠加内置常量 → 两侧不可比 → 误判「迁移丢数据」）。
 */
@Singleton
class DataIntegrityChecker @Inject constructor(
    private val scheduleRecordDao: ScheduleRecordDao,
    private val shiftDao: ShiftDao,
    private val shiftStatusDao: ShiftStatusDao,
    private val shiftBreakDao: ShiftBreakDao,
    private val extraItemDao: ExtraItemDao,
    private val calcProfileDao: CalcProfileDao
) {

    /** 校验结论 */
    data class Report(
        /** 阻断性问题（必须回滚） */
        val errors: List<String>,
        /** 提示性问题（不阻断，但要告知用户） */
        val warnings: List<String>
    ) {
        /** 是否通过 */
        val ok: Boolean get() = errors.isEmpty()

        /** 合并成一句可展示给用户的话 */
        fun summary(): String = buildString {
            if (errors.isNotEmpty()) append("错误：").append(errors.joinToString("；"))
            if (warnings.isNotEmpty()) {
                if (isNotEmpty()) append("；")
                append("提示：").append(warnings.joinToString("；"))
            }
        }
    }

    /**
     * 执行校验。
     *
     * @param expected 迁移前的行数基准（null = 本次不是迁移场景，例如备份恢复）
     */
    suspend fun check(expected: LegacyDbSnapshot.Counts?): Report {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // ⚠️ 行数一律取**原始表行数**（COUNT(*)），与迁移前快照的口径严格一致。
        // 绝不能改用仓库层的 getAll()：那些接口会过滤归档项、还会叠加内置常量，
        // 与快照的原始行数不可比 → 会把「用户有归档数据」误判成「迁移丢了数据」
        // （2026-10-24 真机升级实测：休息时段 8 条被误报成 4 条，升级被错误判失败）。
        val records = scheduleRecordDao.countAll()
        val shiftCount = shiftDao.countAll()
        val statusCount = shiftStatusDao.countAll()
        val breakCount = shiftBreakDao.countAll()
        val extraCount = extraItemDao.countAll()
        val profiles = calcProfileDao.getAll()

        // ── 1. 行数必须与迁移前一致（迁移不得增删业务数据）────────────────
        if (expected != null) {
            if (records != expected.records) {
                errors += "排班记录数异常（迁移前 ${expected.records} 条，迁移后 $records 条）"
            }
            if (shiftCount != expected.shifts) {
                errors += "班次数异常（迁移前 ${expected.shifts} 条，迁移后 $shiftCount 条）"
            }
            if (statusCount != expected.statuses) {
                errors += "附加状态数异常（迁移前 ${expected.statuses} 条，迁移后 $statusCount 条）"
            }
            if (breakCount != expected.breaks) {
                errors += "休息时段数异常（迁移前 ${expected.breaks} 条，迁移后 $breakCount 条）"
            }
            if (extraCount != expected.extras) {
                errors += "补贴扣项数异常（迁移前 ${expected.extras} 条，迁移后 $extraCount 条）"
            }
        }

        // ── 2. 引用列必须是合法 JSON（坏数据会让整条查询链路抛异常）──────
        // 引用判定用**含归档**的全量 id：历史排班引用一个已归档的状态/时段是正常的，
        // 不算「悬空引用」，否则每条历史记录都会刷一条假警告。
        val shiftIds = shiftDao.getAll().map { it.id }.toSet() + BUILTIN_SHIFTS.map { it.id }
        val statusIds = shiftStatusDao.getAll().map { it.id }.toSet() + BUILTIN_STATUSES.map { it.id }
        val extraIds = extraItemDao.getAllIncludingArchived().map { it.id }.toSet()
        var badJson = 0
        var danglingShift = 0
        var danglingStatus = 0
        var danglingExtra = 0
        // 班次引用是独立列 → 用 DISTINCT 查询，不拉全表
        for (shiftId in scheduleRecordDao.distinctShiftIds()) {
            if (shiftId !in shiftIds) danglingShift++
        }
        for (row in scheduleRecordDao.getReferenceColumns()) {
            val statusJson = row.appliedStatusesJson.trim()
            if (statusJson.isNotEmpty() && statusJson != "null" && statusJson != "\"\"") {
                val parsed = runCatching { JsonParser.parseString(statusJson) }.getOrNull()
                when {
                    parsed == null -> badJson++
                    parsed.isJsonObject -> {
                        val id = parsed.asJsonObject.stringOrNull("statusId")
                        if (!id.isNullOrBlank() && id !in statusIds) danglingStatus++
                    }
                    parsed.isJsonArray -> {
                        // 旧数组格式仍可被读取，但元素里的引用同样要检查
                        parsed.asJsonArray.forEach { el ->
                            if (el.isJsonObject) {
                                val id = el.asJsonObject.stringOrNull("statusId")
                                if (!id.isNullOrBlank() && id !in statusIds) danglingStatus++
                            }
                        }
                    }
                }
            }
            val extraJson = row.extraItemIdsJson.trim()
            if (extraJson.isNotEmpty() && extraJson != "null") {
                val parsed = runCatching { JsonParser.parseString(extraJson) }.getOrNull()
                if (parsed == null || !parsed.isJsonArray) {
                    badJson++
                } else {
                    parsed.asJsonArray.forEach { el ->
                        if (el.isJsonPrimitive && el.asString.isNotBlank() && el.asString !in extraIds) {
                            danglingExtra++
                        }
                    }
                }
            }
        }
        if (badJson > 0) {
            errors += "有 $badJson 处引用数据格式损坏（附加状态/补贴项无法解析）"
        }
        if (danglingShift > 0) {
            warnings += "有 $danglingShift 处排班引用了已删除或已下线的班次"
        }
        if (danglingStatus > 0) {
            warnings += "有 $danglingStatus 处排班引用了已删除或已下线的附加状态"
        }
        if (danglingExtra > 0) {
            warnings += "有 $danglingExtra 处排班引用了已删除的补贴扣项"
        }

        // ── 3. 计算口径必须存在且区间不重叠（否则历史计算会算错）─────────
        if (profiles.isEmpty()) {
            errors += "缺少计算口径档案（历史工时/薪资无法按日取到口径）"
        } else {
            val sorted = profiles.sortedWith(compareBy({ it.effectiveFrom }, { it.createdAt }))
            var overlap = 0
            for (i in 0 until sorted.size - 1) {
                val cur = sorted[i]
                val next = sorted[i + 1]
                val curEnd = cur.effectiveTo
                if (curEnd != null && curEnd >= next.effectiveFrom) overlap++
            }
            if (overlap > 0) {
                errors += "有 $overlap 处计算口径区间重叠（同一日期可能命中多份口径）"
            }
        }

        return Report(errors, warnings)
    }
}
