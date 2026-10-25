// app/src/main/java/com/schedulecalendar/app/domain/model/CalcInputs.kt
package com.schedulecalendar.app.domain.model

/**
 * 某一天生效的「计算输入」。
 *
 * 涵盖三类**不挂在排班记录上**的参数：薪资配置、考勤配置、全局休息时段。
 * 它们来自「配置档案」（见 [CalcProfile]），按生效日接力 → 改配置不影响历史计算。
 */
data class CalcInputs(
    val salary: SalaryConfig,
    val attend: AttendConfig,
    val breaks: List<ShiftBreak>,
    /** 来源档案 id；null = 回退到当前 DataStore 现值（尚无档案时的兼容路径） */
    val profileId: String? = null
)

/** 计算区间的一段：日期区间 + 该段生效的输入 */
data class CalcSegment(
    /** 起始日（含） yyyy-MM-dd */
    val from: String,
    /** 结束日（含） yyyy-MM-dd */
    val to: String,
    val inputs: CalcInputs
)

/**
 * 把 [from]~[to] 按档案切成若干段（纯函数，便于单测）。
 *
 * 规则：
 * - 档案区间为**左闭右闭** [effectiveFrom, effectiveTo]，相邻档案首尾相接；
 * - 段与段之间不重叠、不留空洞；
 * - 落在所有档案之外的日期用 [fallback]（当前现值）兜底；
 * - 无档案时返回整段（行为与改造前完全一致）。
 */
fun buildSegments(
    from: String,
    to: String,
    profiles: List<CalcProfile>,
    fallback: CalcInputs
): List<CalcSegment> {
    if (from > to) return emptyList()
    if (profiles.isEmpty()) return listOf(CalcSegment(from, to, fallback))

    val segs = mutableListOf<CalcSegment>()
    var cursor = from
    // 排序规则必须与 CalcProfileDao.getEffectiveOn / getInRange 一致：
    // effectiveFrom 升序；**同一天生效的多条档案按 createdAt 降序（最新创建的在前）**。
    // 同日多条是合法状态（用户规则：数据不一样就生成一条），若顺序反了，
    // 「按日分段」会把当天判给旧口径，而「单日查询」拿到的是新口径 → 同一天金额两处不同。
    for (p in profiles.sortedWith(compareBy<CalcProfile> { it.effectiveFrom }.thenByDescending { it.createdAt })) {
        if (cursor > to) break
        val segFrom = maxOf(cursor, p.effectiveFrom)
        val segTo = minOf(to, p.effectiveTo ?: to)
        if (segFrom > segTo) continue
        segs += CalcSegment(segFrom, segTo, p.toInputs())
        cursor = nextDay(segTo)
    }
    // 尾部兜底：档案未覆盖到的日期用现值（正常情况下不会走到这里）
    if (cursor <= to) segs += CalcSegment(cursor, to, fallback)
    return segs
}

/** 档案 → 计算输入 */
fun CalcProfile.toInputs(): CalcInputs = CalcInputs(
    salary = salary,
    attend = attend,
    breaks = breaks,
    profileId = id
)

/**
 * 口径集合指纹（参与月度缓存 key）。
 *
 * 档案变化 / 回退段的现值变化 / 分段边界变化 → 指纹都会变 → 月度缓存正确失效。
 */
fun List<CalcSegment>.inputsFingerprint(): Long =
    fold(1L) { acc, s ->
        val key = s.inputs.profileId?.hashCode()?.toLong()
            ?: "${s.inputs.salary}|${s.inputs.attend}|${s.inputs.breaks}".hashCode().toLong()
        acc * 31 + key + s.from.hashCode().toLong() + s.to.hashCode().toLong()
    }

/**
 * 把分段结果转成「按日期取输入」的查表函数。
 *
 * 作用：**避免逐日查库**（一个月的日期逐个查档案会是 28~31 次 DB 往返）。
 * 段数通常只有 1~2 个，线性查找即可。
 */
fun List<CalcSegment>.inputsLookup(fallback: CalcInputs): (String) -> CalcInputs {
    val segs = this
    return { date ->
        segs.firstOrNull { date >= it.from && date <= it.to }?.inputs ?: fallback
    }
}

/** 日期加一天（yyyy-MM-dd → yyyy-MM-dd），非法日期原样返回 */
internal fun nextDay(date: String): String =
    runCatching { java.time.LocalDate.parse(date).plusDays(1).toString() }.getOrDefault(date)
 
