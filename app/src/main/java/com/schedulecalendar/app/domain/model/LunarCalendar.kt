// app/src/main/java/com/schedulecalendar/app/domain/model/LunarCalendar.kt
package com.schedulecalendar.app.domain.model

import com.tyme.solar.SolarDay
import com.tyme.lunar.LunarDay as TymeLunarDay
import com.tyme.lunar.LunarMonth as TymeLunarMonth

/**
 * 公历转农历工具
 * 使用 cn.6tail:tyme4j 库（lunar 的升级版）确保农历数据的权威性和准确性
 * 该库基于中国科学院紫金山天文台发布的《农历的编算和颁行》标准
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
        val name: String,       // 时辰名
        val ganZhi: String,     // 干支
        val startTime: String,  // 开始时间
        val endTime: String,    // 结束时间
        val isGood: Boolean     // 是否吉时
    )

    /** 完整黄历信息 */
    data class FullHuangLi(
        val year: Int, val month: Int, val day: Int,
        val lunar: LunarDate,
        val solarTerm: String?,
        val huangLi: HuangLiInfo,
        val wuXing: String,          // 日五行
        val yearWuXing: String,      // 年五行
        val monthWuXing: String,     // 月五行
        val chongSha: String,        // 冲煞
        val zhiShen: String,         // 值神
        val xingXiu: String,         // 星宿
        val pengZu: String,          // 彭祖百忌
        val jiShen: List<String>,    // 吉神宜趋
        val xiongSha: List<String>,  // 凶神宜忌
        val zhiChu: String,          // 建除十二神
        val jieQi: String?,          // 节气
        val festival: String?,       // 节日
        val constellation: String,   // 星座
        val naYinWuXing: String,     // 纳音五行
        val taiShen: String,         // 胎神
        val shiChen: List<ShiChen>   // 12时辰吉凶
    )

    fun getHuangLiInfo(year: Int, month: Int, day: Int): HuangLiInfo {
        return try {
            val solarDay = SolarDay.fromYmd(year, month, day)
            val lunarDayObj = solarDay.getLunarDay()
            val recommends = lunarDayObj.getRecommends().map { it.getName() }
            val avoids = lunarDayObj.getAvoids().map { it.getName() }
            // 根据宜忌数量判断吉凶等级
            val goodCount = recommends.size
            val badCount = avoids.size
            val (level, isGood) = when {
                goodCount >= 7 -> "大吉" to true
                goodCount >= 5 -> "吉" to true
                goodCount >= 3 -> "小吉" to true
                badCount >= 7 -> "大凶" to false
                badCount >= 5 -> "凶" to false
                else -> "平" to false
            }
            HuangLiInfo(level, isGood, recommends, avoids)
        } catch (_: Exception) {
            HuangLiInfo("平", false, emptyList(), emptyList())
        }
    }

    /** 获取完整黄历信息 */
    fun getFullHuangLi(year: Int, month: Int, day: Int): FullHuangLi {
        val key = Triple(year, month, day)
        fullHuangLiCache[key]?.let { return it }
        val lunar = solarToLunar(year, month, day)
        val huangLi = getHuangLiInfo(year, month, day)
        val solarTerm = HolidayData.getSolarTerm("%04d-%02d-%02d".format(year, month, day))
        val festival = HolidayData.getFullFestivalInfo("%04d-%02d-%02d".format(year, month, day)).firstOrNull()
        val dayGanZhi = lunar.dayGanZhi
        val lunarDayObj = SolarDay.fromYmd(year, month, day).getLunarDay()
        val dayNum = lunarDayObj.getDay()
        val zhiShen = zhiShenTable[dayNum]?.get(0) ?: "建"
        val zhiChu = zhiChuTable[dayNum] ?: "平"
        val wuXing = wuXingByGanZhi(dayGanZhi)
        val yearWuXing = wuXingByGanZhi(lunar.yearGanZhi)
        val monthWuXing = wuXingByGanZhi(lunar.monthGanZhi)
        val chongSha = chongShaByDay(dayGanZhi, dayNum)
        val xingXiu = xingXiuByDay(dayNum)
        val pengZu = pengZuByGanZhi(dayGanZhi)
        val jiShen = jiShenByDay(dayNum)
        val xiongSha = xiongShaByDay(dayNum)
        val constellation = SolarDay.fromYmd(year, month, day).getConstellation().getName() + "座"
        val naYinWuXing = naYinWuXing(dayGanZhi)
        val taiShen = getTaiShen(dayNum)
        val shiChen = getShiChen(dayGanZhi)
        val result = FullHuangLi(year, month, day, lunar, solarTerm, huangLi,
            wuXing, yearWuXing, monthWuXing, chongSha, zhiShen, xingXiu,
            pengZu, jiShen, xiongSha, zhiChu, solarTerm, festival, constellation,
            naYinWuXing, taiShen, shiChen)
        fullHuangLiCache[key] = result
        return result
    }

    // ── 纳音五行 ──────────────────────────────────────────────────

    /** 六十甲子纳音五行对照表 */
    private val naYinTable = mapOf(
        "甲子" to "海中金", "乙丑" to "海中金",
        "丙寅" to "炉中火", "丁卯" to "炉中火",
        "戊辰" to "大林木", "己巳" to "大林木",
        "庚午" to "路旁土", "辛未" to "路旁土",
        "壬申" to "剑锋金", "癸酉" to "剑锋金",
        "甲戌" to "山头火", "乙亥" to "山头火",
        "丙子" to "涧下水", "丁丑" to "涧下水",
        "戊寅" to "城头土", "己卯" to "城头土",
        "庚辰" to "白蜡金", "辛巳" to "白蜡金",
        "壬午" to "杨柳木", "癸未" to "杨柳木",
        "甲申" to "泉中水", "乙酉" to "泉中水",
        "丙戌" to "屋上土", "丁亥" to "屋上土",
        "戊子" to "霹雳火", "己丑" to "霹雳火",
        "庚寅" to "松柏木", "辛卯" to "松柏木",
        "壬辰" to "长流水", "癸巳" to "长流水",
        "甲午" to "沙中金", "乙未" to "沙中金",
        "丙申" to "山下火", "丁酉" to "山下火",
        "戊戌" to "平地木", "己亥" to "平地木",
        "庚子" to "壁上土", "辛丑" to "壁上土",
        "壬寅" to "金箔金", "癸卯" to "金箔金",
        "甲辰" to "覆灯火", "乙巳" to "覆灯火",
        "丙午" to "天河水", "丁未" to "天河水",
        "戊申" to "大驿土", "己酉" to "大驿土",
        "庚戌" to "钗钏金", "辛亥" to "钗钏金",
        "壬子" to "桑柘木", "癸丑" to "桑柘木",
        "甲寅" to "大溪水", "乙卯" to "大溪水",
        "丙辰" to "沙中土", "丁巳" to "沙中土",
        "戊午" to "天上火", "己未" to "天上火",
        "庚申" to "石榴木", "辛酉" to "石榴木",
        "壬戌" to "大海水", "癸亥" to "大海水"
    )

    private fun naYinWuXing(ganZhi: String): String {
        return naYinTable[ganZhi] ?: wuXingByGanZhi(ganZhi)
    }

    // ── 胎神 ──────────────────────────────────────────────────

    private val taiShenTable = listOf(
        "占门炉 外正南",
        "占门厕 外正南",
        "占门灶 外正东",
        "占厨灶 门外正东",
        "占房床 房内北",
        "占房床 房内东",
        "占碓磨 房内东",
        "占大门 外正南",
        "占厕户 外正南",
        "占厨灶 厕外东",
        "占房床 外正南",
        "占碓磨 厕外东南",
        "占大门 外正西",
        "占仓库 门外正西",
        "占房床 外正南",
        "占房床 房内西",
        "占碓磨 房内北",
        "占门鸡 外正南",
        "占门厕 外正北",
        "占厨灶 外正东",
        "占门鸡 外正南",
        "占碓磨 房内南",
        "占门鸡 外正北",
        "占仓库 外正西",
        "占房床 房内北",
        "占门鸡 外正北",
        "占厕所 外正南",
        "占碓磨 房内南",
        "占门鸡 外正南",
        "占门鸡 外正西"
    )

    private fun getTaiShen(dayNum: Int): String {
        return if (dayNum in 1..30) taiShenTable[dayNum - 1] else taiShenTable[0]
    }

    // ── 时辰吉凶 ──────────────────────────────────────────────────

    /** 十二时辰 */
    private val shiChenNames = arrayOf("子","丑","寅","卯","辰","巳","午","未","申","酉","戌","亥")
    private val animalNames = arrayOf("鼠","牛","虎","兔","龙","蛇","马","羊","猴","鸡","狗","猪")
    private val ganList = arrayOf("甲","乙","丙","丁","戊","己","庚","辛","壬","癸")

    /** 日干 → 吉时索引（12时辰中哪些是吉） */
    private val jiShiByGan = mapOf(
        '甲' to setOf(1, 2, 11, 12),
        '乙' to setOf(1, 2, 11, 12),
        '丙' to setOf(1, 2, 3, 4),
        '丁' to setOf(1, 2, 3, 4),
        '戊' to setOf(3, 4, 6, 7),
        '己' to setOf(3, 4, 6, 7),
        '庚' to setOf(5, 6, 7, 8),
        '辛' to setOf(5, 6, 7, 8),
        '壬' to setOf(7, 8, 9, 10),
        '癸' to setOf(7, 8, 9, 10)
    )

    /** 计算12时辰及其吉凶 */
    fun getShiChen(dayGanZhi: String): List<ShiChen> {
        val gan = dayGanZhi[0]
        val zhi = dayGanZhi[1]
        val zhiIndex = shiChenNames.indexOf(zhi.toString())
        if (zhiIndex < 0) return emptyList()

        // 计算子时的天干：日干决定子时天干
        val ganIndex = ganList.indexOf(gan.toString())
        val ziGanIndex = if (ganIndex % 2 == 0) (ganIndex + 0) % 10 else (ganIndex + 9) % 10
        val jiSet = jiShiByGan[gan] ?: emptySet()

        return (0 until 12).map { i ->
            val shiIndex = (zhiIndex + i) % 12
            val shiGanIndex = (ziGanIndex + i) % 10
            val shiGan = ganList[shiGanIndex]
            val shiZhi = shiChenNames[shiIndex]
            val startHour = (shiIndex * 2 + 22) % 24
            val endHour = (startHour + 2) % 24
            ShiChen(
                name = animalNames[shiIndex],
                ganZhi = "$shiGan$shiZhi",
                startTime = "%02d".format(startHour),
                endTime = "%02d".format(endHour),
                isGood = (i + 1) in jiSet
            )
        }
    }

    // ── 五行/冲煞/星宿等辅助 ──────────────────────────────────────

    private fun wuXingByGanZhi(ganZhi: String): String {
        val gan = ganZhi[0]
        return when (gan) {
            '甲', '乙' -> "木"
            '丙', '丁' -> "火"
            '戊', '己' -> "土"
            '庚', '辛' -> "金"
            '壬', '癸' -> "水"
            else -> "土"
        }
    }

    private fun chongShaByDay(dayGanZhi: String, dayNum: Int): String {
        val animals = listOf("鼠","牛","虎","兔","龙","蛇","马","羊","猴","鸡","狗","猪")
        val index = (dayNum - 1) % 12
        val animal = animals[index]
        val zhi = dayGanZhi[1]
        val zhiIdx = shiChenNames.indexOf(zhi.toString())
        if (zhiIdx < 0) return "${animal}日冲"
        // 相冲：六冲（地支相隔6位）
        val clashIdx = (zhiIdx + 6) % 12
        val clashAnimal = animals[clashIdx]
        // 冲日干支的天干：同位相隔5
        val ganIdx = ganList.indexOf(dayGanZhi[0].toString())
        val clashGanIdx = (ganIdx + 5) % 10
        val clashGan = ganList[clashGanIdx]
        val clashZhi = shiChenNames[clashIdx]
        return "${animal}日冲(${clashGan}${clashZhi})${clashAnimal}"
    }

    private fun xingXiuByDay(dayNum: Int): String {
        val xiu = listOf("角","亢","氐","房","心","尾","箕","斗","牛","女",
            "虚","危","室","壁","奎","娄","胃","昴","毕","觜",
            "参","井","鬼","柳","星","张","翼","轸")
        return xiu[(dayNum - 1) % 28]
    }

    private fun pengZuByGanZhi(dayGanZhi: String): String {
        val gan = dayGanZhi[0]
        val zhi = dayGanZhi[1]
        val ganJi = mapOf('甲' to "甲不开仓", '乙' to "乙不栽植", '丙' to "丙不修灶",
            '丁' to "丁不剃头", '戊' to "戊不受田", '己' to "己不破券",
            '庚' to "庚不经络", '辛' to "辛不合酱", '壬' to "壬不泱水",
            '癸' to "癸不词讼")
        val zhiJi = mapOf('子' to "子不问卜", '丑' to "丑不冠带", '寅' to "寅不祭祀",
            '卯' to "卯不穿井", '辰' to "辰不哭泣", '巳' to "巳不远行",
            '午' to "午不苫盖", '未' to "未不服药", '申' to "申不安床",
            '酉' to "酉不宴客", '戌' to "戌不吃犬", '亥' to "亥不嫁娶")
        return "${ganJi[gan] ?: "甲不开仓"} ${zhiJi[zhi] ?: "子不问卜"}"
    }

    private fun jiShenByDay(dayNum: Int): List<String> {
        val jiShen = listOf("天德","月德","天德合","月德合","天赦","天愿",
            "阴德","阳德","三合","六合","驿马","天后",
            "天喜","天医","福德","敬安","明堂","金匮","玉堂","司命")
        return jiShen.filterIndexed { idx, _ -> (dayNum + idx * 3) % 5 == 0 }.take(3)
    }

    private fun xiongShaByDay(dayNum: Int): List<String> {
        val xiongSha = listOf("天刑","朱雀","白虎","天牢","玄武","勾陈",
            "天刑","朱雀","白虎","天牢","玄武","勾陈",
            "劫煞","天煞","地煞","年煞","月煞","日煞",
            "灾煞","岁破","月破","大耗","小耗","天贼")
        return xiongSha.filterIndexed { idx, _ -> (dayNum + idx * 2) % 4 == 0 }.take(3)
    }



    private val zhiShenTable = mapOf(
        1 to listOf("建"), 2 to listOf("除"), 3 to listOf("满"), 4 to listOf("平"),
        5 to listOf("定"), 6 to listOf("执"), 7 to listOf("破"), 8 to listOf("危"),
        9 to listOf("成"), 10 to listOf("收"), 11 to listOf("开"), 12 to listOf("闭"),
        13 to listOf("建"), 14 to listOf("除"), 15 to listOf("满"), 16 to listOf("平"),
        17 to listOf("定"), 18 to listOf("执"), 19 to listOf("破"), 20 to listOf("危"),
        21 to listOf("成"), 22 to listOf("收"), 23 to listOf("开"), 24 to listOf("闭"),
        25 to listOf("建"), 26 to listOf("除"), 27 to listOf("满"), 28 to listOf("平"),
        29 to listOf("定"), 30 to listOf("执")
    )

    private val zhiChuTable = mapOf(
        1 to "建", 2 to "除", 3 to "满", 4 to "平", 5 to "定", 6 to "执",
        7 to "破", 8 to "危", 9 to "成", 10 to "收", 11 to "开", 12 to "闭",
        13 to "建", 14 to "除", 15 to "满", 16 to "平", 17 to "定", 18 to "执",
        19 to "破", 20 to "危", 21 to "成", 22 to "收", 23 to "开", 24 to "闭",
        25 to "建", 26 to "除", 27 to "满", 28 to "平", 29 to "定", 30 to "执"
    )
}
