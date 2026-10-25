package com.schedulecalendar.app.data.compat

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.schedulecalendar.app.data.db.dao.CalcProfileDao
import com.schedulecalendar.app.data.db.dao.ExtraItemDao
import com.schedulecalendar.app.data.db.dao.ScheduleRecordDao
import com.schedulecalendar.app.data.db.dao.ShiftDao
import com.schedulecalendar.app.data.db.dao.ShiftStatusDao
import com.schedulecalendar.app.domain.model.BUILTIN_SHIFTS
import com.schedulecalendar.app.domain.model.BUILTIN_STATUSES
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 恢复后的数据校验。
 *
 * 「恢复成功」不等于「数据正确」。真正的风险有三类：
 * 1. 数据坏了（JSON 解析失败）——坏数据会让整条查询链路抛异常；
 * 2. 引用断了（引用了已删除/已下线的班次/状态/补贴项）；
 * 3. 口径断了（没有计算口径档案）——历史工时/薪资会回退到 DataStore 现值而算错。
 *
 * 「数据坏了」与「口径断了」是**错误**（必须让用户知道恢复没成功）；
 * 「引用了已删除的班次/状态」只报警告——它不影响数据可用性，
 * 且属于用户既有状态，不该在恢复时当成失败。
 *
 * ⚠️ 栅栏已抬起：不再有「迁移前 vs 迁移后」的行数比对（历史迁移链已删除），
 * 因此本类不再需要「迁移前行数基准」参数。
 */
@Singleton
class DataIntegrityChecker @Inject constructor(
    private val scheduleRecordDao: ScheduleRecordDao,
    private val shiftDao: ShiftDao,
    private val shiftStatusDao: ShiftStatusDao,
    private val extraItemDao: ExtraItemDao,
    private val calcProfileDao: CalcProfileDao
) {

    /** 校验结论 */
    data class Report(
        /** 阻断性问题（恢复结果不可信） */
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
     * 校验的是**恢复结果自身是否自洽**（引用列可解析、口径档案存在且不重叠），
     * 不再与任何「迁移前基准」比对——历史迁移链已随栅栏抬起删除。
     */
    suspend fun check(): Report {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // ── 1. 引用列必须是合法 JSON（坏数据会让整条查询链路抛异常）──────
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
        for ((appliedStatusesJson, extraItemIdsJson) in scheduleRecordDao.getReferenceColumns()) {
            val statusJson = appliedStatusesJson.trim()
            if (statusJson.isNotEmpty() && statusJson != "null" && statusJson != "\"\"") {
                val parsed = runCatching { JsonParser.parseString(statusJson) }.getOrNull()
                when {
                    parsed == null -> badJson++
                    // 当前格式的附加状态恒为**单对象**（旧数组形态已在栅栏抬起时从读路径移除）
                    parsed.isJsonObject -> {
                        val id = parsed.asJsonObject.stringOrNull("statusId")
                        if (!id.isNullOrBlank() && id !in statusIds) danglingStatus++
                    }
                }
            }
            val extraJson = extraItemIdsJson.trim()
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

        // ── 2. 计算口径必须存在且区间不重叠（否则历史计算会算错）─────────
        val profiles = calcProfileDao.getAll()
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

/** 取字符串值；缺失 / null / 非原始类型 → null */
private fun JsonObject.stringOrNull(key: String): String? {
    val el = get(key) ?: return null
    if (el.isJsonNull || !el.isJsonPrimitive) return null
    return el.asString
}
