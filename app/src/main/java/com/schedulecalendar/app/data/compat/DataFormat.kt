// app/src/main/java/com/schedulecalendar/app/data/compat/DataFormat.kt
package com.schedulecalendar.app.data.compat

/**
 * 数据格式版本治理（**唯一真相源**）。
 *
 * ## 为什么要单独一层
 * 过去「版本」散落在三处：Room 的 `@Database(version=)`、备份 JSON 里的 `version` 字段、
 * 以及各处的 `if (backup.version <= 2)` 兼容分支。三者互不约束，改一处忘另一处就会出现
 * 「能读但算错」的静默数据问题。本对象把三者收敛到一处，并明确「谁能被读」的策略。
 *
 * ## 三条版本轴
 * | 轴 | 常量 | 含义 |
 * |---|---|---|
 * | 数据库 schema | [CURRENT] / [MIN_SUPPORTED_DB_VERSION] | Room `@Database(version=)`，与本对象必须一致 |
 * | 应用数据备份 | [APP_DATA_BACKUP] / [MIN_SUPPORTED_APP_DATA_BACKUP] | 「应用数据_*.json」的 `version` |
 * | 班次配置备份 | [SHIFT_CONFIG_BACKUP] / [MIN_SUPPORTED_SHIFT_CONFIG_BACKUP] | 「班次配置_*.json」的 `version` |
 *
 * ## 本版本（中转兼容版）的定位
 * 当前版本 = **兼容版**：它接受**任意历史版本**的数据（DB v1 起、两种备份格式 v1 起），
 * 并在升级过程中把旧数据**转换成当前数据格式**（不是「兼容读取」，而是「读进来就改写成新格式」）。
 *
 * 转换完成后，下游代码只需处理一种格式 → 后续版本可以把历史包袱全部丢掉。
 *
 * ## fence（兼容栅栏）——后续版本如何切掉历史兼容
 * [COMPAT_FENCE] = 兼容版自身的版本号 = **后续版本可接受的最低数据版本**。
 * 后续版本（> [COMPAT_FENCE]）发布时只需做三件事即可彻底移除历史兼容：
 * 1. 把 [MIN_SUPPORTED_DB_VERSION] / [MIN_SUPPORTED_APP_DATA_BACKUP] /
 *    [MIN_SUPPORTED_SHIFT_CONFIG_BACKUP] 抬到 [COMPAT_FENCE] 对应值；
 * 2. 把 [LEGACY_COMPAT_ENABLED] 置 false（R8 会剔除全部 legacy 分支）；
 * 3. 按 docs/数据兼容与迁移策略.md 的「清理清单」删除旧迁移与旧格式转换代码。
 *
 * 抬到 fence 之后的行为：**老数据不再被接受**（数据库直接拒绝打开、备份直接拒绝恢复），
 * 而不是「打开了但算错」。这是刻意的：兼容旧格式的成本会随版本数线性增长，
 * 而正确性风险（历史口径/工时静默变化）远大于「让极少数老用户手动导出再升级」的成本。
 */
object DataFormat {

    /** 当前数据格式版本（必须与 [com.schedulecalendar.app.data.db.AppDatabase] 的 version 一致）。 */
    const val CURRENT = 7

    // ── 「谁可以被读」的策略（兼容版 = 全开）────────────────────────────

    /** 可被本版本直接升级的最低数据库版本。兼容版放开到 v1（v1 走动态兜底迁移）。 */
    const val MIN_SUPPORTED_DB_VERSION = 1

    /** 可被本版本恢复的最低「应用数据」备份格式版本。 */
    const val MIN_SUPPORTED_APP_DATA_BACKUP = 1

    /** 可被本版本恢复的最低「班次配置」备份格式版本。 */
    const val MIN_SUPPORTED_SHIFT_CONFIG_BACKUP = 1

    // ── fence：后续版本切掉历史兼容的边界 ──────────────────────────────

    /**
     * 兼容栅栏 = **兼容版自身的版本号**。
     *
     * 含义：数据格式版本 ≥ [COMPAT_FENCE] 的数据（即「经过兼容版升级过的数据」）
     * 是后续版本唯一需要支持的格式。后续版本把上面的 MIN_* 抬到本值，
     * 即可拒绝一切更老的数据格式。
     */
    const val COMPAT_FENCE = 7

    /**
     * 旧格式兼容总开关。
     *
     * - `true`（兼容版）：所有 legacy 分支（多格式解析、旧枚举兜底、旧备份转换）均生效；
     * - `false`（后续版本）：代码里所有 `if (DataFormat.LEGACY_COMPAT_ENABLED)` 分支被 R8 剔除，
     *   同时可按清理清单删掉 legacy 代码本体。
     *
     * 之所以做成编译期常量而不是运行时开关：运行时开关会让旧分支永远留在 APK 里
     * （既不省体积也不省性能），而兼容能力的去留应该是**版本级决策**。
     */
    const val LEGACY_COMPAT_ENABLED = true

    // ── 备份格式版本号 ────────────────────────────────────────────────

    /** 「应用数据」备份格式版本。v5 = 兼容版新增（v4 及更早由 [BackupCompat] 转换过来）。 */
    const val APP_DATA_BACKUP = 5

    /** 「班次配置」备份格式版本。v7 = 兼容版新增（v6 及更早由 [BackupCompat] 转换过来）。 */
    const val SHIFT_CONFIG_BACKUP = 7

    /**
     * 取某类备份在当前版本下允许的最低格式版本（版本闸门）。
     * 低于该版本的备份**直接拒绝恢复**，而不是「尽力解析」——
     * 尽力解析的结果是「看起来恢复了但历史算错」，比明确拒绝危险得多。
     */
    fun minSupportedBackupVersion(kind: BackupKind): Int = when (kind) {
        BackupKind.APP_DATA -> MIN_SUPPORTED_APP_DATA_BACKUP
        BackupKind.SHIFT_CONFIG -> MIN_SUPPORTED_SHIFT_CONFIG_BACKUP
    }
}
