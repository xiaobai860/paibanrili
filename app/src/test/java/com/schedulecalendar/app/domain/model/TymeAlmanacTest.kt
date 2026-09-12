// app/src/test/java/com/schedulecalendar/app/domain/model/TymeAlmanacTest.kt
package com.schedulecalendar.app.domain.model

import com.tyme.solar.SolarDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 历法数据对账测试（本项目 vs tyme4j）
 *
 * ## 为什么需要
 * 项目的**法定节假日 / 调休补班**是手工维护的硬编码表，没有第三方库兜底，
 * 一旦抄错会静默算错工资；而**节气 / 节日 / 黄历**虽已改为 tyme4j 计算，
 * 也需要守护「确实用上了库」以及「本地补充项没丢」。
 * tyme4j 内置了国务院公告的法定节假日数据（2001-12-29 ~ 2026-10-09），
 * 正好可作为独立第三方基准做对账。
 *
 * ## 设计原则
 * **以本项目自研表为主**：本测试只做「对账 / 报警」，不把库数据接进生产逻辑
 * （库的数据有明确截止日，2027+ 无数据，无法支撑 `MAX_COVERED_YEAR = 2030`）。
 */
class TymeAlmanacTest {

    private fun fmt(d: LocalDate): String = "%04d-%02d-%02d".format(d.year, d.monthValue, d.dayOfMonth)

    /** tyme4j `LegalHoliday` 内置公告数据的最后一天（其常量 DATA 以 2026-10-10 补班收尾） */
    private val LIB_DATA_END = LocalDate.of(2026, 10, 9)

    // ══════════════════════════════════════════════════════════════════
    // 一、法定节假日 / 调休补班：与 tyme4j 官方公告数据对账
    // ══════════════════════════════════════════════════════════════════

    /**
     * 在库数据覆盖范围内（2024-01-01 ~ 2026-10-09），
     * 本项目的「放假 / 补班 / 都不是」判定必须与 tyme4j 内置的国务院公告数据完全一致。
     */
    @Test
    fun statutoryHolidays_matchTyme4jOfficialNotice() {
        val mismatches = mutableListOf<String>()
        var d = LocalDate.of(2024, 1, 1)
        val end = LocalDate.of(2026, 12, 31)
        var compared = 0
        while (!d.isAfter(end)) {
            val s = fmt(d)
            val lib = SolarDay.fromYmd(d.year, d.monthValue, d.dayOfMonth).getLegalHoliday()
            val ourIsOff = HolidayData.isLegalHoliday(s)
            val ourIsMakeup = HolidayData.isMakeupDay(s)
            val ourLabel = if (ourIsOff) "休" else if (ourIsMakeup) "班" else "无记录"
            if (lib != null) {
                compared++
                val libIsOff = !lib.isWork() // 库：休
                if (libIsOff != ourIsOff || (!libIsOff && !ourIsMakeup)) {
                    mismatches += "$s 库=${if (libIsOff) "休" else "班"}(${lib.getName()}) 本项目=$ourLabel"
                }
            } else if (!d.isAfter(LIB_DATA_END)) {
                // 库数据覆盖范围内却查无记录 → 官方普通日，本项目也不得标记
                compared++
                if (ourIsOff || ourIsMakeup) {
                    mismatches += "$s 库=普通日 本项目=$ourLabel"
                }
            }
            d = d.plusDays(1)
        }
        assertTrue("未能对账（库无数据），测试失去意义", compared > 100)
        assertTrue(
            "法定节假日数据与 tyme4j 内置官方公告不一致（共 ${mismatches.size} 处）：\n" +
                mismatches.joinToString("\n"),
            mismatches.isEmpty()
        )
    }

    /** `holidayNames` 的键必须恰好等于 `holidays ∪ transferWorkdays`，不多不少 */
    @Test
    fun holidayNames_coverExactlyTheHolidaySets() {
        val expected = mutableSetOf<String>()
        var d = LocalDate.of(HolidayData.MIN_COVERED_YEAR, 1, 1)
        val end = LocalDate.of(HolidayData.MAX_COVERED_YEAR, 12, 31)
        while (!d.isAfter(end)) {
            val s = fmt(d)
            if (HolidayData.isLegalHoliday(s) || HolidayData.isMakeupDay(s)) expected += s
            d = d.plusDays(1)
        }
        val actual = HolidayData.holidayNameKeys()
        assertEquals(
            "holidayNames 键集合与 holidays ∪ transferWorkdays 不符：" +
                "缺失=${(expected - actual).sorted()} 多余=${(actual - expected).sorted()}",
            expected,
            actual
        )
    }

    /** 名称里的「补班」后缀必须与 `transferWorkdays` 严格一一对应 */
    @Test
    fun holidayNames_makeupSuffixMatchesTransferWorkdays() {
        for (s in HolidayData.holidayNameKeys()) {
            val name = HolidayData.getHolidayName(s) ?: continue
            assertEquals(
                "$s 的名称为「$name」，与 isMakeupDay(${HolidayData.isMakeupDay(s)}) 不一致",
                HolidayData.isMakeupDay(s),
                name.endsWith("补班")
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 二、二十四节气：确认已脱离硬编码表、任意年份可用
    // ══════════════════════════════════════════════════════════════════

    private val allTerms = listOf(
        "冬至", "小寒", "大寒", "立春", "雨水", "惊蛰", "春分", "清明",
        "谷雨", "立夏", "小满", "芒种", "夏至", "小暑", "大暑", "立秋",
        "处暑", "白露", "秋分", "寒露", "霜降", "立冬", "小雪", "大雪"
    )

    @Test
    fun solarTerm_knownDates() {
        assertEquals("小寒", HolidayData.getSolarTerm("2024-01-06"))
        assertEquals("立春", HolidayData.getSolarTerm("2024-02-04"))
        assertEquals("清明", HolidayData.getSolarTerm("2026-04-05"))
        assertEquals("冬至", HolidayData.getSolarTerm("2026-12-22"))
        // 相邻日不是节气
        assertEquals(null, HolidayData.getSolarTerm("2026-04-06"))
        // 非法输入不抛异常
        assertEquals(null, HolidayData.getSolarTerm("bad-input"))
    }

    /** 原硬编码表只覆盖 2024–2030；改为 tyme4j 后 2050 年也必须完整正确 */
    @Test
    fun solarTerm_worksBeyondHardcodedRange() {
        val found = mutableListOf<String>()
        var d = LocalDate.of(2050, 1, 1)
        while (d.year == 2050) {
            HolidayData.getSolarTerm(fmt(d))?.let { found += it }
            d = d.plusDays(1)
        }
        assertEquals("2050 年应恰好有 24 个节气日", 24, found.size)
        assertEquals("2050 年节气集合应为完整 24 节气", allTerms.toSet(), found.toSet())
    }

    // ══════════════════════════════════════════════════════════════════
    // 三、节日：库为主 + 本地补充项未丢失
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun traditionalFestivals_fromLibrary() {
        assertEquals("春节", HolidayData.getTraditionalFestival("2024-02-10"))
        assertEquals("元宵节", HolidayData.getTraditionalFestival("2024-02-24"))
        assertEquals("端午节", HolidayData.getTraditionalFestival("2024-06-10"))
        assertEquals("中秋节", HolidayData.getTraditionalFestival("2024-09-17"))
        assertEquals(null, HolidayData.getTraditionalFestival("2024-03-15"))
    }

    /** 库（LunarFestival）未收录小年，必须由本地补充兜住 */
    @Test
    fun minorNewYear_isSuppliedLocally() {
        var northern: String? = null
        var southern: String? = null
        var d = LocalDate.of(2025, 1, 1)
        while (d.year == 2025 && d.monthValue <= 2) {
            val lunar = LunarCalendar.solarToLunar(d.year, d.monthValue, d.dayOfMonth)
            if (lunar.lunarMonth == 12 && !lunar.isLeap) {
                if (lunar.lunarDay == 23) northern = fmt(d)
                if (lunar.lunarDay == 24) southern = fmt(d)
            }
            d = d.plusDays(1)
        }
        assertNotNull("2025 年初应能找到农历腊月廿三", northern)
        assertNotNull("2025 年初应能找到农历腊月廿四", southern)
        assertEquals("北方小年", HolidayData.getTraditionalFestival(northern!!))
        assertEquals("南方小年", HolidayData.getTraditionalFestival(southern!!))
    }

    /** 库（SolarFestival）未收录情人节/愚人节/圣诞节，必须由本地补充兜住 */
    @Test
    fun internationalFestivals_libraryPlusLocalSupplements() {
        // 库内置
        assertEquals("元旦", HolidayData.getInternationalFestival("2026-01-01"))
        assertEquals("妇女节", HolidayData.getInternationalFestival("2026-03-08"))
        assertEquals("国庆节", HolidayData.getInternationalFestival("2026-10-01"))
        // 本地补充
        assertEquals("情人节", HolidayData.getInternationalFestival("2026-02-14"))
        assertEquals("愚人节", HolidayData.getInternationalFestival("2026-04-01"))
        assertEquals("圣诞节", HolidayData.getInternationalFestival("2026-12-25"))
        // 非节日
        assertEquals(null, HolidayData.getInternationalFestival("2026-02-15"))
    }

    /** 清明节 / 冬至节 不应与节气重名重复展示 */
    @Test
    fun fullFestivalInfo_deduplicatesTermFestivals() {
        val qingming = HolidayData.getFullFestivalInfo("2026-04-05")
        assertTrue("应包含节气「清明」", qingming.contains("清明"))
        assertFalse("不应重复展示「清明节」", qingming.contains("清明节"))

        val dongzhi = HolidayData.getFullFestivalInfo("2026-12-22")
        assertTrue(dongzhi.contains("冬至"))
        assertFalse(dongzhi.contains("冬至节"))
    }

    // ══════════════════════════════════════════════════════════════════
    // 四、黄历字段：确认来自 tyme4j 且取值合法
    // ══════════════════════════════════════════════════════════════════

    private val dutyNames = listOf("建", "除", "满", "平", "定", "执", "破", "危", "成", "收", "开", "闭")
    private val twelveStarNames = listOf("青龙", "明堂", "天刑", "朱雀", "金匮", "天德", "白虎", "玉堂", "天牢", "玄武", "司命", "勾陈")
    private val twentyEightNames = listOf(
        "角", "亢", "氐", "房", "心", "尾", "箕", "斗", "牛", "女",
        "虚", "危", "室", "壁", "奎", "娄", "胃", "昴", "毕", "觜",
        "参", "井", "鬼", "柳", "星", "张", "翼", "轸"
    )

    @Test
    fun huangLi_fieldsAreLegalAndLibraryBacked() {
        val h = LunarCalendar.getFullHuangLi(2026, 9, 13)

        // 建除十二值神
        assertTrue("建除取值非法：${h.zhiChu}", dutyNames.contains(h.zhiChu))
        // 值神 = 黄道黑道十二神 + 黄/黑道
        assertTrue("值神取值非法：${h.zhiShen}", twelveStarNames.any { h.zhiShen.startsWith(it) })
        assertTrue("值神缺黄道/黑道：${h.zhiShen}", h.zhiShen.endsWith("（黄道）") || h.zhiShen.endsWith("（黑道）"))
        // 二十八宿
        assertTrue("星宿取值非法：${h.xingXiu}", twentyEightNames.any { h.xingXiu.startsWith(it) })
        assertTrue("星宿缺吉凶：${h.xingXiu}", h.xingXiu.endsWith("（吉）") || h.xingXiu.endsWith("（凶）"))
        // 彭祖百忌为「天干忌(8字) + 空格 + 地支忌(8字)」，如「庚不经络织机虚张 寅不祭祀神鬼不尝」
        val pengZuParts = h.pengZu.split(" ")
        assertEquals("彭祖百忌应为两段：${h.pengZu}", 2, pengZuParts.size)
        assertTrue(
            "彭祖百忌格式异常：${h.pengZu}",
            pengZuParts.all { it.length == 8 && it.contains("不") }
        )
        // 胎神非空（库返回形如「占门炉 外正南」）
        assertTrue("胎神为空", h.taiShen.isNotBlank())
        // 冲煞形如「冲马煞北」，两项均来自库
        assertTrue("冲煞格式异常：${h.chongSha}", Regex("^冲.煞[南东北西]$").matches(h.chongSha))
        // 宜忌非空
        assertTrue("宜为空", h.huangLi.yi.isNotEmpty())
        // 日级吉凶 = 库的「黄道黑道十二神」，而非按条数自造的等级
        assertTrue("日吉凶等级异常：${h.huangLi.level}", h.huangLi.level == "黄道" || h.huangLi.level == "黑道")
        assertEquals("isGood 应与黄道判定一致", h.huangLi.level == "黄道", h.huangLi.isGood)
        assertTrue(
            "值神与日吉凶应同源：${h.zhiShen} / ${h.huangLi.level}",
            h.zhiShen.contains(h.huangLi.level)
        )
        // 纳音五行
        assertTrue(
            "纳音五行异常：${h.naYinWuXing}",
            listOf("金", "火", "木", "土", "水").any { h.naYinWuXing.endsWith(it) }
        )
    }

    /**
     * 冲煞逐日校验（2026 全年）
     *
     * 独立按两条规则反推，而不是复述实现：
     * 1. 冲的生肖 = 日支 + 6（六冲）
     * 2. 煞方 = 三合局，日支 % 4 → 南 / 东 / 北 / 西
     */
    @Test
    fun chongSha_isConsistentAcrossYear() {
        val animals = "鼠牛虎兔龙蛇马羊猴鸡狗猪"
        val branches = "子丑寅卯辰巳午未申酉戌亥"
        val shaByZhiIndex = listOf("南", "东", "北", "西")

        var d = LocalDate.of(2026, 1, 1)
        var checked = 0
        while (d.year == 2026) {
            val h = LunarCalendar.getFullHuangLi(d.year, d.monthValue, d.dayOfMonth)
            val match = Regex("^冲(.)煞(.)$").find(h.chongSha)
            assertNotNull("${d} 冲煞格式异常：${h.chongSha}", match)
            val (animal, sha) = match!!.destructured

            val dayCycle = SolarDay.fromYmd(d.year, d.monthValue, d.dayOfMonth)
                .getSixtyCycleDay().getSixtyCycle().getName()
            val zhiIndex = branches.indexOf(dayCycle[1])

            assertEquals("${d}（${dayCycle}日）冲的生肖应为六冲", animals[(zhiIndex + 6) % 12].toString(), animal)
            assertEquals("${d}（${dayCycle}日）煞方应为三合局", shaByZhiIndex[zhiIndex % 4], sha)
            checked++
            d = d.plusDays(1)
        }
        assertEquals("应校验完整 2026 年", 365, checked)
    }

    /** 12 时辰必须按「子→亥」顺序、时间区间正确、干支合法 */
    @Test
    fun shiChen_isTwelveHoursInCorrectOrder() {
        val h = LunarCalendar.getFullHuangLi(2026, 9, 13)
        assertEquals("应为 12 个时辰", 12, h.shiChen.size)

        val branches = listOf("子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥")
        val expectedStart = intArrayOf(23, 1, 3, 5, 7, 9, 11, 13, 15, 17, 19, 21)
        h.shiChen.forEachIndexed { i, sc ->
            assertEquals("第 $i 个时辰的干支地支应为「${branches[i]}」", branches[i], sc.ganZhi[1].toString())
            assertEquals("第 $i 个时辰起始时间错误", "%02d".format(expectedStart[i]), sc.startTime)
            assertEquals("第 $i 个时辰结束时间错误", "%02d".format((expectedStart[i] + 2) % 24), sc.endTime)
        }
        // 首尾：子时 23-01，亥时 21-23
        assertEquals("23", h.shiChen.first().startTime)
        assertEquals("21", h.shiChen.last().startTime)
        assertEquals("23", h.shiChen.last().endTime)
    }

    /** 星期取自库 `SolarDay.getWeek()`，逐日与 JDK 公历星期交叉验证 */
    @Test
    fun weekDay_matchesJdk() {
        val expected = mapOf(
            DayOfWeek.MONDAY to "周一", DayOfWeek.TUESDAY to "周二", DayOfWeek.WEDNESDAY to "周三",
            DayOfWeek.THURSDAY to "周四", DayOfWeek.FRIDAY to "周五", DayOfWeek.SATURDAY to "周六",
            DayOfWeek.SUNDAY to "周日"
        )
        var d = LocalDate.of(2026, 1, 1)
        while (d.year == 2026) {
            assertEquals(
                "$d 星期与公历不符",
                expected[d.dayOfWeek],
                LunarCalendar.getWeekDayText(d.year, d.monthValue, d.dayOfMonth)
            )
            d = d.plusDays(1)
        }
    }

    /**
     * 三伏（初伏 / 中伏 / 末伏）：总长 30 或 40 天、分段顺序正确、且与数九时间互斥
     */
    @Test
    fun dogDay_spansSummerAndNeverOverlapsNineDay() {
        val days = mutableListOf<String>()
        var firstDate = ""
        var lastDate = ""
        var d = LocalDate.of(2026, 1, 1)
        while (d.year == 2026) {
            val h = LunarCalendar.getFullHuangLi(d.year, d.monthValue, d.dayOfMonth)
            h.dogDay?.let {
                if (days.isEmpty()) firstDate = fmt(d)
                lastDate = fmt(d)
                days += it
                assertEquals("${d} 三伏与数九不应同时出现", null, h.nineDay)
            }
            d = d.plusDays(1)
        }
        assertTrue("三伏总长应为 30 或 40 天，实际 ${days.size}", days.size == 30 || days.size == 40)
        assertTrue("文案应形如「初伏第N天」", days.all { Regex("^[初中末]伏第\\d+天$").matches(it) })
        assertEquals("首日应为初伏第1天", "初伏第1天", days.first())
        assertTrue("前 10 天应为初伏", days.take(10).all { it.startsWith("初伏") })
        assertTrue("后 10 天应为末伏", days.takeLast(10).all { it.startsWith("末伏") })
        assertTrue("中间应为中伏", days.drop(10).dropLast(10).all { it.startsWith("中伏") })
        assertTrue("初伏应在 7 月（$firstDate）", firstDate.startsWith("2026-07"))
        assertTrue("末伏应在 8 月（$lastDate）", lastDate.startsWith("2026-08"))
    }

    /**
     * 三伏的时间关系（决定 UI 布局能否共用一行）
     *
     * - 与**数九严格互斥**（三伏 7~8 月、数九 12~3 月）→ 黄历页两者共用一格是安全的；
     * - 与**梅雨不保证互斥**（都在 6~8 月，可能重叠或首尾相接）→ 梅雨必须保持独立一行。
     *
     * 扫描 2020~2040 年的 6~9 月（三伏与梅雨只可能出现在这段区间），
     * 结果通过 system-out 打印，便于核对。
     */
    @Test
    fun dogDay_neverOverlapsNineDay_butRelationshipWithPlumRainIsEmpirical() {
        var dogDays = 0
        var overlapWithNine = 0
        var overlapWithPlum = 0
        val yearsWithPlumOverlap = mutableListOf<Int>()

        for (year in 2020..2040) {
            var hadPlumOverlap = false
            var d = LocalDate.of(year, 6, 1)
            while (d.year == year && d.monthValue <= 9) {
                val sd = SolarDay.fromYmd(d.year, d.monthValue, d.dayOfMonth)
                if (sd.getDogDay() != null) {
                    dogDays++
                    if (sd.getNineDay() != null) overlapWithNine++
                    if (sd.getPlumRainDay() != null) {
                        overlapWithPlum++
                        hadPlumOverlap = true
                    }
                }
                d = d.plusDays(1)
            }
            if (hadPlumOverlap) yearsWithPlumOverlap += year
        }

        assertEquals("三伏与数九必须严格互斥（否则共用一个格子会丢信息）", 0, overlapWithNine)
        assertTrue("应扫到足够多的三伏天，实际 $dogDays", dogDays > 500)
        println(
            "[三伏 vs 数九/梅雨] 三伏共 $dogDays 天；与数九重叠 $overlapWithNine 天；" +
                "与梅雨重叠 $overlapWithPlum 天；出现三伏-梅雨重叠的年份：$yearsWithPlumOverlap"
        )
    }

    /** 七十二候 / 数九 / 三伏 / 梅雨：取值必须落在库的合法域内 */
    @Test
    fun phenologyAndNineDay_haveLegalValues() {
        // 9/13 处于「白露」之后，应能取到候名与三候
        val h = LunarCalendar.getFullHuangLi(2026, 9, 13)
        assertNotNull("9/13 应能取到七十二候", h.phenology)
        assertTrue("三候取值非法：${h.threePhenology}", listOf("初候", "二候", "三候").contains(h.threePhenology))

        // 冬季应处于数九期间
        val winter = LunarCalendar.getFullHuangLi(2027, 1, 10)
        assertNotNull("1/10 应处于数九期间", winter.nineDay)
        assertTrue("数九格式异常：${winter.nineDay}", Regex("^[一二三四五六七八九]九第\\d+天$").matches(winter.nineDay!!))

        // 冬季属数九、非三伏
        assertEquals("1/10 不应处于三伏期间", null, winter.dogDay)

        // 夏季非数九期间应为 null
        val summer = LunarCalendar.getFullHuangLi(2026, 7, 15)
        assertEquals("7/15 不应处于数九期间", null, summer.nineDay)
    }

    /** 梅雨提示与四季边界 */
    @Test
    fun plumRain_textIsSaneAcrossYear() {
        var d = LocalDate.of(2026, 1, 1)
        var inRainDays = 0
        while (d.year == 2026) {
            val t = LunarCalendar.getPlumRainText(d.year, d.monthValue, d.dayOfMonth)
            if (t != null) {
                inRainDays++
                assertTrue("梅雨文案异常：$t", t == "今日入梅" || t == "今日出梅" || t.startsWith("梅雨天（入梅第"))
            }
            d = d.plusDays(1)
        }
        // 梅雨季一般为 6 月中~7 月上，约 20~35 天
        assertTrue("2026 年梅雨天数为 $inRainDays，超出合理范围", inRainDays in 15..45)
    }
}
