package com.schedulecalendar.app.domain.util

import java.time.LocalDate
import java.time.YearMonth

/**
 * 日期字符串格式化（全项目唯一实现）。
 *
 * 之前 `"%04d-%02d-%02d".format(year, month, day)` 散落在 30+ 处（ui / widget / domain 都有），
 * 一旦某处漏写补零（`"%"d-%d-%d"`）或把参数顺序写反（月/日互换），生成的字符串就无法与
 * 数据库里的 `yyyy-MM-dd` 匹配（Map 查不到、字典序比较错位），且这类错误极难发现。
 *
 * 收口到这里后：
 * - 格式只有一份定义，改格式/加时区只需改这里；
 * - 传 [LocalDate] 的重载从类型上消除了参数顺序写错的可能。
 */
object DateFormats {

    /** [LocalDate] → "yyyy-MM-dd" */
    fun date(d: LocalDate): String =
        "%04d-%02d-%02d".format(d.year, d.monthValue, d.dayOfMonth)

    /** (年, 月, 日) → "yyyy-MM-dd"，用于只有三个整数（如 YearMonth + 日序号）的场景 */
    fun date(year: Int, month: Int, day: Int): String =
        "%04d-%02d-%02d".format(year, month, day)

    /** [LocalDate] → "yyyy-MM" */
    fun yearMonth(d: LocalDate): String =
        "%04d-%02d".format(d.year, d.monthValue)

    /** (年, 月) → "yyyy-MM" */
    fun yearMonth(year: Int, month: Int): String =
        "%04d-%02d".format(year, month)

    /** [YearMonth] → "yyyy-MM" */
    fun yearMonth(ym: YearMonth): String =
        "%04d-%02d".format(ym.year, ym.monthValue)

    /** (年, 月) → 该月 1 号 "yyyy-MM-01"（区间起点用） */
    fun monthStart(year: Int, month: Int): String =
        "%04d-%02d-%02d".format(year, month, 1)
}
