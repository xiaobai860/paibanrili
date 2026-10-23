// app/src/main/java/com/schedulecalendar/app/domain/model/CalcProfile.kt
package com.schedulecalendar.app.domain.model

/**
 * 计算口径档案（Config Profile）。
 *
 * 作用：把「会影响统计计算、但不挂在任何排班记录上」的三类参数收进一份带生效日的档案：
 * - 薪资配置 [SalaryConfig]
 * - 考勤配置 [AttendConfig]
 * - 全局休息时段集合 [breaks]
 *
 * 约定：
 * - 档案按 [effectiveFrom] 接力，相邻档案区间不重叠、不留洞；[effectiveTo] 为 null 表示至今。
 * - 计算某天时使用**当天生效**的档案 → 改动配置不会追溯改变历史计算结果。
 * - 档案不可物理删除（历史可能仍指向它），只能归档（= 把 effectiveTo 设为归档日−1）。
 * - 班次 / 附加状态 / 补贴扣款**不在档案内**：它们有引用锚点（排班记录存 id），
 *   「引用即版本」能更精确地表达「这条记录用的就是这一版」。
 */
data class CalcProfile(
    val id: String,
    /** 展示名，如「2026-10 调薪」 */
    val name: String,
    val salary: SalaryConfig,
    val attend: AttendConfig,
    /** 该口径下的全局休息时段集合（只含有效时段） */
    val breaks: List<ShiftBreak>,
    /** 生效日 yyyy-MM-dd（含） */
    val effectiveFrom: String,
    /** 失效日 yyyy-MM-dd（不含），null = 至今 */
    val effectiveTo: String? = null,
    val note: String? = null,
    val createdAt: Long = 0L
) {
    /**
     * 是否是「该日期生效」的档案。
     *
     * 区间语义为**左闭右闭** [effectiveFrom, effectiveTo]：
     * 接力时旧档案的 to 会被设为新档案生效日的前一天，两个档案首尾相接、既不重叠也不留空洞。
     * （若用左闭右开，新档案生效日的前一天会变成无人命中的空洞日。）
     */
    fun isEffectiveOn(date: String): Boolean =
        date >= effectiveFrom && (effectiveTo == null || date <= effectiveTo)
}

/**
 * 从档案列表中找出某天生效的档案（区间接力语义：effectiveFrom ≤ date < effectiveTo）。
 *
 * 独立成纯函数便于单元测试 —— 计算链路「按日选口径」的正确性直接决定历史结果是否稳定。
 */
fun List<CalcProfile>.effectiveOn(date: String): CalcProfile? =
    firstOrNull { it.isEffectiveOn(date) }

