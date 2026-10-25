// app/src/main/java/com/schedulecalendar/app/data/compat/BackupFormat.kt
package com.schedulecalendar.app.data.compat

import com.google.gson.JsonObject

/**
 * 备份文件的**格式探测**：只读出「这是哪种备份、什么格式版本」，不做任何格式转换。
 *
 * ## 定位（栅栏抬起后）
 * 栅栏已抬到 v7：备份只接受**当前格式**（应用数据 v5 / 班次配置 v7），
 * 低于该版本的文件由 [DataFormat.minSupportedBackupVersion] 明确拒绝，
 * 因此这里**不再需要**「任意旧版本 → 当前格式」的转换器（原 `BackupCompat`）。
 *
 * 剩下的两件事仍必须在这里做：
 * 1. **识别种类**——按顶层 key 集合判断（不按子串猜测，否则备注里带关键字就会误判）；
 * 2. **读取版本号**——版本闸门要靠它判断该文件是不是太老。
 */
object BackupFormat {

    /**
     * 判断备份种类。
     *
     * 除两个主特征外，还按**内容特征兜底**（文件被二次处理/套了一层壳时顶层 key 会变样）：
     * - 应用数据：`calcProfiles` / `salaryConfig` / `attendConfig` / `displaySchemes` / `accountCategories`；
     * - 班次配置：`shiftOrder` / `breakOrder` / `extraOrder` / `shiftColorIndex`（弱特征，仅在前者都不存在时用）。
     */
    fun detectKind(root: JsonObject): BackupKind? {
        if (root.has("scheduleRecords")) return BackupKind.APP_DATA
        if (root.has("shifts")) return BackupKind.SHIFT_CONFIG
        // ── 兜底：应用数据的强特征 ──
        if (root.has("calcProfiles") || root.has("salaryConfig") || root.has("attendConfig") ||
            root.has("displaySchemes") || root.has("accountCategories")
        ) return BackupKind.APP_DATA
        // ── 兜底：班次配置的弱特征 ──
        if (root.has("shiftOrder") || root.has("breakOrder") ||
            root.has("extraOrder") || root.has("shiftColorIndex")
        ) return BackupKind.SHIFT_CONFIG
        return null
    }

    /**
     * 尝试「解包」一层：某些工具会把 JSON 再套一层（如 `{"data": {...}}`），
     * 导致顶层 key 变成 "data" 而识别不出种类。
     *
     * @return 解包后的对象；若无需解包或解不出来则返回 null
     */
    fun unwrapOnce(root: JsonObject): JsonObject? {
        val entries = root.entrySet().toList()
        if (entries.size != 1) return null
        val value = entries.first().value
        if (!value.isJsonObject) return null
        val inner = value.asJsonObject
        return if (inner.has("scheduleRecords") || inner.has("shifts")) inner else null
    }

    /**
     * 读取备份的格式版本。
     *
     * @return 版本号；**字段缺失或非法返回 null** —— 调用方据此给出
     *         「文件缺少版本信息、无法确认格式」的可读提示，而不是谎报一个版本号。
     */
    fun detectVersion(root: JsonObject): Int? {
        val el = root.get("version") ?: return null
        if (!el.isJsonPrimitive) return null
        return runCatching { el.asJsonPrimitive.asInt }.getOrNull()
    }

    /**
     * **版本闸门**：判断某个备份文件能否被当前版本恢复。
     *
     * 纯函数（无 Android 依赖）→ 可被单测直接锁定策略。
     *
     * @param kind 备份种类
     * @param version 文件里的格式版本号（null = 缺少 version 字段）
     * @return null = 允许恢复；非 null = 拒绝原因（文案可直接展示给用户）
     */
    fun checkRestoreAllowed(kind: BackupKind, version: Int?): String? {
        if (version == null) {
            return "该备份文件缺少版本信息（version 字段），无法确认格式。" +
                "请选择本应用「存储与备份」导出的 .json 文件。"
        }
        val min = DataFormat.minSupportedBackupVersion(kind)
        if (version < min) {
            return "该备份为 v$version，已低于当前版本支持的最低格式（v$min）。" +
                "请先安装兼容版（${DataFormat.COMPAT_VERSION_LABEL}）导出备份，再升级到当前版本。"
        }
        return null
    }
}
