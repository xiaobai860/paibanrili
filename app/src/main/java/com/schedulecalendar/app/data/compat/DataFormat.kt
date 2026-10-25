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
 * ## 兼容栅栏（fence）已抬起：只维护最终兼容版及以后
 *
 * [COMPAT_FENCE] = 7 = 最后一个「带历史兼容包袱」的版本（数据格式 v7）所产出的格式版本。
 * 2026-10-25 起，本项目**只支持最终兼容版 224 / 2026102503 及以后版本的数据**：
 *
 * | 轴 | 最低可接受版本 | 低于它的数据 |
 * |---|---|---|
 * | 数据库 schema | v7（= fence） | 启动即被 [CompatGate] 判为 [CompatGate.State.Unsupported] 并阻断界面 |
 * | 应用数据备份 | v5（= 当前格式） | 恢复时**直接拒绝**并提示「请先在兼容版导出再升级」 |
 * | 班次配置备份 | v7（= 当前格式） | 同上 |
 *
 * 由此带来的**已删除**内容（历史兼容层，随 fence 抬起一并移除）：
 * - Room 迁移链 v1→v7（`Migrations`）——现在只有 v7 一种 schema，无需迁移；
 * - `BackupCompat` 的「任意旧版本 → 当前格式」转换器；
 * - 迁移前只读快照 `LegacyDbSnapshot` 与基于它的自动回滚；
 * - 读路径上的旧格式分支（附加状态数组形态、枚举 `valueOf` 兜底等）。
 *
 * 之所以敢这样切：旧格式兼容的成本随版本数线性增长，而正确性风险
 * （历史口径/工时被静默改写）远大于「让极少数老用户手动导出再升级」的成本。
 * 刻意的行为是**明确拒绝**（而不是「打开了但算错」）。
 *
 * ## 今后如何再抬高栅栏
 * 下一次需要支持比 v7 更老的数据时，才需要重新引入转换层；正常演进只需：
 * 1. 提升 [CURRENT]（数据库 schema）与对应备份格式版本号；
 * 2. 只写 **一条** 从上一版本到新版本的迁移，登记进 Room；
 * 3. 保持 [MIN_SUPPORTED_*] 不变（继续以 fence 为底线）。
 */
object DataFormat {

    /** 当前数据格式版本（必须与 [com.schedulecalendar.app.data.db.AppDatabase] 的 version 一致）。 */
    const val CURRENT = 7

    // ── 兼容栅栏：后续版本可接受的最低数据版本 ──────────────────────────

    /**
     * 兼容栅栏 = **最终兼容版所产出的数据格式版本**。
     *
     * 数据格式版本 ≥ [COMPAT_FENCE] 的数据（即「已经过最终兼容版升级过的数据」）
     * 是本项目唯一需要支持的格式；低于它的数据一律明确拒绝。
     */
    const val COMPAT_FENCE = 7

    /** 可被本版本直接升级的最低数据库版本。 */
    const val MIN_SUPPORTED_DB_VERSION = COMPAT_FENCE

    // ── 备份格式版本号 ────────────────────────────────────────────────

    /** 「应用数据」备份格式版本。 */
    const val APP_DATA_BACKUP = 5

    /** 「班次配置」备份格式版本。 */
    const val SHIFT_CONFIG_BACKUP = 7

    /** 可被本版本恢复的最低「应用数据」备份格式版本。 */
    const val MIN_SUPPORTED_APP_DATA_BACKUP = APP_DATA_BACKUP

    /** 可被本版本恢复的最低「班次配置」备份格式版本。 */
    const val MIN_SUPPORTED_SHIFT_CONFIG_BACKUP = SHIFT_CONFIG_BACKUP

    /**
     * 最终兼容版的版本标识。
     *
     * 栅栏抬起后，老数据的用户唯一出路是「装回这个兼容版 → 导出备份 → 升级到新版本」，
     * 因此所有「数据太老」的提示都要指名道姓地告诉他装哪个版本。
     */
    const val COMPAT_VERSION_LABEL = "224 / 2026102503"

    /**
     * 取某类备份在当前版本下允许的最低格式版本（版本闸门）。
     * 低于该版本的备份**直接拒绝恢复**，而不是「尽力解析」——
     * 尽力解析的结果是「看起来恢复了但历史算错」，比明确拒绝危险得多。
     */
    fun minSupportedBackupVersion(kind: BackupKind): Int = when (kind) {
        BackupKind.APP_DATA -> MIN_SUPPORTED_APP_DATA_BACKUP
        BackupKind.SHIFT_CONFIG -> MIN_SUPPORTED_SHIFT_CONFIG_BACKUP
    }

    /** 闸门对数据库版本的决策结果 */
    enum class DbVersionDecision {
        /** 全新安装：没有旧数据要处理 */
        FRESH_INSTALL,

        /** 版本受支持：可以打开 */
        OPEN,

        /** 版本低于栅栏：必须拒绝打开（装回兼容版导出数据） */
        UNSUPPORTED
    }

    /**
     * 闸门决策（**纯函数**，可被单测直接锁定）。
     *
     * @param fileVersion 数据库文件的 `PRAGMA user_version`；null = 文件不存在（全新安装）
     */
    fun decideByDbVersion(fileVersion: Int?): DbVersionDecision = when {
        fileVersion == null -> DbVersionDecision.FRESH_INSTALL
        fileVersion < MIN_SUPPORTED_DB_VERSION -> DbVersionDecision.UNSUPPORTED
        else -> DbVersionDecision.OPEN
    }
}
