// app/src/main/java/com/schedulecalendar/app/domain/model/LunarCalendar.kt
package com.schedulecalendar.app.domain.model

import com.tyme.lunar.LunarDay as TymeLunarDay
import com.tyme.lunar.LunarMonth as TymeLunarMonth
import com.tyme.sixtycycle.SixtyCycle
import com.tyme.sixtycycle.SixtyCycleHour
import com.tyme.solar.SolarDay

/**
 * 公历转农历 / 黄历工具
 *
 * 历法换算与全部黄历字段（彭祖百忌、逐日胎神、二十八宿、建除十二值神、
 * 黄道黑道十二神、吉神宜趋/凶神宜忌、纳音五行、时辰吉凶）**统一由
 * cn.6tail:tyme4j 计算**，不再使用本地硬编码表 —— 该库依据国家标准
 * 《农历的编算和颁行》(GB/T 33661-2017) 与中国科学院紫金山天文台算法。
 *
 * 历史遗留：此前胎神/二十八宿/建除/神煞等为自研查表（部分为占位算法），
 * 与真实历法不符，已全部改为库计算；节气/数九/梅雨/七十二候同样取自库。
 */
object LunarCalendar {

    // ── 计算结果缓存 ─────────────────────────────────────────────────
    // tyme4j 的公历→农历换算涉及对象图遍历，属于 CPU 重计算。
    // 小组件（单页约 42 个日期格）与黄历详情页会高频重复调用同一日期，
    // 使用固定上限的 LRU 缓存避免重复计算、降低卡顿与电量消耗。
    private val lunarDayTextCache = object : LinkedHashMap<Triple<Int, Int, Int>, String>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<Int, Int, Int>, String>): Boolean = size > 256
    }
    private val fullHuangLiCache = object : LinkedHashMap<Triple<Int, Int, Int>, FullHuangLi>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<Int, Int, Int>, FullHuangLi>): Boolean = size > 128
    }

    private val lunarMonth = arrayOf("正","二","三","四","五","六","七","八","九","十","冬","腊")
    private val lunarDay = arrayOf(
        "初一","初二","初三","初四","初五","初六","初七","初八","初九","初十",
        "十一","十二","十三","十四","十五","十六","十七","十八","十九","二十",
        "廿一","廿二","廿三","廿四","廿五","廿六","廿七","廿八","廿九","三十"
    )

    data class LunarDate(
        val lunarYear: Int,
        val lunarMonth: Int,
        val lunarDay: Int,
        val isLeap: Boolean,
        val yearGanZhi: String,
        val monthGanZhi: String,
        val dayGanZhi: String,
        val zodiac: String,
        val dayText: String,
        val monthText: String
    )

    fun solarToLunar(year: Int, month: Int, day: Int): LunarDate {
        val solarDay = SolarDay.fromYmd(year, month, day)
        val lunarDay = solarDay.getLunarDay()
        val lunarMonthObj = lunarDay.getLunarMonth()
        val lunarYear = lunarMonthObj.getLunarYear().getYear()
        val lunarMonthWithLeap = lunarMonthObj.getMonthWithLeap()
        val lunarDayNum = lunarDay.getDay()
        val isLeap = lunarMonthObj.isLeap()
        val sixtyCycleDay = lunarDay.getSixtyCycleDay()
        val yearGanZhi = sixtyCycleDay.getYear().getName()
        val monthGanZhi = sixtyCycleDay.getMonth().getName()
        val dayGanZhi = sixtyCycleDay.getSixtyCycle().getName()
        val zodiac = sixtyCycleDay.getYear().getEarthBranch().getZodiac().getName()
        val monthText = if (isLeap) {
            "闰" + lunarMonth[kotlin.math.abs(lunarMonthWithLeap) - 1] + "月"
        } else {
            lunarMonth[lunarMonthWithLeap - 1] + "月"
        }
        val dayText = this.lunarDay[lunarDayNum - 1]

        return LunarDate(
            lunarYear = lunarYear,
            lunarMonth = kotlin.math.abs(lunarMonthWithLeap),
            lunarDay = lunarDayNum,
            isLeap = isLeap,
            yearGanZhi = yearGanZhi,
            monthGanZhi = monthGanZhi,
            dayGanZhi = dayGanZhi,
            zodiac = zodiac,
            dayText = dayText,
            monthText = monthText
        )
    }

    fun getLunarDayText(year: Int, month: Int, day: Int): String {
        val key = Triple(year, month, day)
        return lunarDayTextCache[key] ?: run {
            val l = solarToLunar(year, month, day)
            val text = if (l.lunarDay == 1) l.monthText else l.dayText
            lunarDayTextCache[key] = text
            text
        }
    }

    fun getMonthGanZhi(year: Int, month: Int, day: Int): String {
        val lunarDay = SolarDay.fromYmd(year, month, day).getLunarDay()
        return lunarDay.getSixtyCycleDay().getMonth().getName()
    }

    fun getDayGanZhi(year: Int, month: Int, day: Int): String {
        val lunarDay = SolarDay.fromYmd(year, month, day).getLunarDay()
        return lunarDay.getSixtyCycle().getName()
    }

    /** 星期，如「周一」；取自 tyme4j `SolarDay.getWeek()`（周一=1，与公历一致） */
    fun getWeekDayText(year: Int, month: Int, day: Int): String =
        "周" + SolarDay.fromYmd(year, month, day).getWeek().getName()

    /**
     * 农历转公历
     * @param lunarYear 农历年
     * @param lunarMonth 农历月（1-12）
     * @param lunarDay 农历日（1-30）
     * @param isLeapMonth 是否闰月
     * @return 公历日期数据类
     */
    data class SolarDate(val year: Int, val month: Int, val day: Int)

    fun lunarToSolar(lunarYear: Int, lunarMonth: Int, lunarDay: Int, isLeapMonth: Boolean = false): SolarDate {
        return try {
            val monthIndex = if (isLeapMonth) -lunarMonth else lunarMonth
            val ld = TymeLunarDay.fromYmd(lunarYear, monthIndex, lunarDay)
            val sd = ld.getSolarDay()
            val sm = sd.getSolarMonth()
            SolarDate(sm.getSolarYear().getYear(), sm.getIndexInYear() + 1, sd.getDay())
        } catch (e: Exception) {
            SolarDate(lunarYear, lunarMonth, lunarDay)
        }
    }

    // ── 黄历宜忌 ──────────────────────────────────────────────────

    data class HuangLiInfo(
        val level: String,
        val isGood: Boolean,
        val yi: List<String>,
        val ji: List<String>
    )

    /** 时辰吉凶信息 */
    data class ShiChen(
        val name: String,       // 生肖，如「鼠」
        val ganZhi: String,     // 干支，如「甲子」
        val startTime: String,  // 开始时间（小时，两位）
        val endTime: String,    // 结束时间（小时，两位）
        val isGood: Boolean     // 是否吉时（黄道）
    )

    /**
     * 完整黄历信息
     *
     * 说明：只保留**页面真正会显示**的字段。此前存在的 `wuXing`/`yearWuXing`/`monthWuXing`
     * （自研干支五行）、`jieQi`（与 [solarTerm] 完全重复）、`constellation`（星座）均为死字段，
     * 已删除。
     */
    data class FullHuangLi(
        val year: Int, val month: Int, val day: Int,
        val lunar: LunarDate,
        val solarTerm: String?,      // 节气
        val huangLi: HuangLiInfo,
        val chongSha: String,        // 冲煞
        val zhiShen: String,         // 值神（黄道黑道十二神）
        val xingXiu: String,         // 二十八宿
        val pengZu: String,          // 彭祖百忌
        val jiShen: List<String>,    // 吉神宜趋
        val xiongSha: List<String>,  // 凶神宜忌
        val zhiChu: String,          // 建除十二值神
        val festival: String?,       // 节日
        val naYinWuXing: String,     // 纳音五行
        val taiShen: String,         // 胎神
        val shiChen: List<ShiChen>,  // 12时辰吉凶
        val phenology: String?,      // 七十二候，如「蚯蚓结」
        val threePhenology: String?, // 三候，如「初候」
        val nineDay: String?,        // 数九，如「一九第3天」
        val dogDay: String?,         // 三伏，如「初伏第5天」（与数九时间互斥：冬数九、夏三伏）
        val plumRain: String?        // 梅雨，如「入梅第5天」/「出梅」
    )

    /**
     * 获取当日宜忌与吉凶
     *
     * `level` / `isGood` 取库提供的日级吉凶判定 —— **黄道黑道十二神**（`TwelveStar.getEcliptic()`），
     * 即传统黄历的「黄道吉日 / 黑道日」。
     *
     * ⚠️ 此前曾按「宜的条数 ≥ 7 算大吉、忌的条数 ≥ 7 算大凶」这类阈值自造等级，
     * 属自研伪算法（库中并无此概念），已废弃。
     */
    fun getHuangLiInfo(year: Int, month: Int, day: Int): HuangLiInfo {
        return try {
            val lunarDayObj = SolarDay.fromYmd(year, month, day).getLunarDay()
            val recommends = lunarDayObj.getRecommends().map { it.getName() }
            val avoids = lunarDayObj.getAvoids().map { it.getName() }
            val ecliptic = lunarDayObj.getTwelveStar().getEcliptic()
            HuangLiInfo(
                level = ecliptic.getName(),                       // 「黄道」/「黑道」
                isGood = ecliptic.getLuck().getName() == "吉",
                yi = recommends,
                ji = avoids
            )
        } catch (_: Exception) {
            HuangLiInfo("—", false, emptyList(), emptyList())
        }
    }

    /** 获取完整黄历信息（全部字段由 tyme4j 计算，结果缓存） */
    fun getFullHuangLi(year: Int, month: Int, day: Int): FullHuangLi {
        val key = Triple(year, month, day)
        fullHuangLiCache[key]?.let { return it }

        val dateStr = "%04d-%02d-%02d".format(year, month, day)
        val solarDay = SolarDay.fromYmd(year, month, day)
        val lunar = solarToLunar(year, month, day)
        val lunarDayObj = solarDay.getLunarDay()
        val sixtyCycleDay = solarDay.getSixtyCycleDay()
        val dayCycle = sixtyCycleDay.getSixtyCycle()

        val solarTerm = HolidayData.getSolarTerm(dateStr)
        val festival = HolidayData.getFullFestivalInfo(dateStr).firstOrNull()
        val huangLi = getHuangLiInfo(year, month, day)

        // 建除十二值神（由日支与月支推得）
        val zhiChu = lunarDayObj.getDuty().getName()
        // 黄道黑道十二神（俗称「值神」）
        val twelveStar = lunarDayObj.getTwelveStar()
        val zhiShen = "${twelveStar.getName()}（${twelveStar.getEcliptic().getName()}）"
        // 二十八宿：宿名 + 七曜 + 动物（如「角木蛟」），附吉凶
        val star = lunarDayObj.getTwentyEightStar()
        val xingXiu = buildString {
            append(star.getName())
            append(star.getSevenStar().getName())
            append(star.getAnimal().getName())
            append("（")
            append(star.getLuck().getName())
            append("）")
        }
        // 吉神宜趋 / 凶神宜忌（库按日神煞表拆分吉凶）
        val gods = lunarDayObj.getGods()
        val jiShen = gods.filter { it.getLuck().getName() == "吉" }.map { it.getName() }
        val xiongSha = gods.filter { it.getLuck().getName() == "凶" }.map { it.getName() }

        val chongSha = chongShaByDay(dayCycle)
        val pengZu = dayCycle.getPengZu().getName()
        val naYinWuXing = dayCycle.getSound().getName()
        val taiShen = lunarDayObj.getFetusDay().getName()
        val shiChen = buildShiChen(sixtyCycleDay.getHours())

        // 节气类延伸：七十二候 / 数九 / 三伏 / 梅雨
        val phenologyDay = solarDay.getPhenologyDay()
        val phenologyName = phenologyDay.getPhenology().getName()
        val threePhenologyName = phenologyDay.getPhenology().getThreePhenology().getName()
        val nineDayText = solarDay.getNineDay()?.toString()
        val dogDayText = solarDay.getDogDay()?.toString()
        val plumRainText = getPlumRainText(year, month, day)

        val result = FullHuangLi(
            year = year, month = month, day = day,
            lunar = lunar,
            solarTerm = solarTerm,
            huangLi = huangLi,
            chongSha = chongSha,
            zhiShen = zhiShen,
            xingXiu = xingXiu,
            pengZu = pengZu,
            jiShen = jiShen,
            xiongSha = xiongSha,
            zhiChu = zhiChu,
            festival = festival,
            naYinWuXing = naYinWuXing,
            taiShen = taiShen,
            shiChen = shiChen,
            phenology = phenologyName,
            threePhenology = threePhenologyName,
            nineDay = nineDayText,
            dogDay = dogDayText,
            plumRain = plumRainText
        )
        fullHuangLiCache[key] = result
        return result
    }

    // ── 节气类延伸 ────────────────────────────────────────────────

    /**
     * 梅雨提示文案
     *
     * 入梅 = 芒种后第 1 个丙日；出梅 = 小暑后第 1 个未日（tyme4j `SolarDay.getPlumRainDay()`）。
     *
     * @return 「今日入梅」/「梅雨天（入梅第N天）」/「今日出梅」；不在梅雨期返回 null
     */
    fun getPlumRainText(year: Int, month: Int, day: Int): String? = runCatching {
        val plumRainDay = SolarDay.fromYmd(year, month, day).getPlumRainDay() ?: return@runCatching null
        if (plumRainDay.getPlumRain().getIndex() == 0) {
            val s = plumRainDay.toString() // 「入梅第N天」
            if (s == "入梅第1天") "今日入梅" else "梅雨天（$s）"
        } else {
            "今日出梅"
        }
    }.getOrNull()

    // ── 辅助计算 ──────────────────────────────────────────────────

    /**
     * 构建 12 时辰吉凶
     *
     * tyme4j `SixtyCycleDay.getHours()` 固定按 **子→亥** 顺序返回 12 个干支时辰
     * （第 0 个为前一日 23:00 的早子时），吉凶取该时辰的黄道黑道十二神。
     */
    private fun buildShiChen(hours: List<SixtyCycleHour>): List<ShiChen> =
        hours.mapIndexed { index, hour ->
            val startHour = (index * 2 + 23) % 24
            ShiChen(
                name = hour.getSixtyCycle().getEarthBranch().getZodiac().getName(),
                ganZhi = hour.getSixtyCycle().getName(),
                startTime = "%02d".format(startHour),
                endTime = "%02d".format((startHour + 2) % 24),
                isGood = hour.getTwelveStar().getEcliptic().getName() == "黄道"
            )
        }

    /**
     * 冲煞：日支六冲的生肖，以及三合局决定的煞方（两项均直接取自 tyme4j）
     *
     * - 冲：`EarthBranch.getOpposite()`（六冲：子午、丑未、寅申、卯酉、辰戌、巳亥）
     * - 煞：`EarthBranch.getOminous()`（逢巳酉丑煞东、亥卯未煞西、申子辰煞南、寅午戌煞北）
     *
     * 例：庚寅日 → 「冲猴煞北」
     *
     * 说明：部分黄历会在括号里再标一个「相冲干支」，但库里没有该 API，
     * 且「同日干+相冲支」「天干相冲」等流派互不一致，自造即属自研 → **不显示**。
     */
    private fun chongShaByDay(dayCycle: SixtyCycle): String {
        val branch = dayCycle.getEarthBranch()
        return "冲${branch.getOpposite().getZodiac().getName()}煞${branch.getOminous().getName()}"
    }
}
