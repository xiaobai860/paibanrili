// app/src/main/java/com/schedulecalendar/app/data/compat/BackupCompat.kt
package com.schedulecalendar.app.data.compat

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 备份格式转换器：**任意历史版本备份 → 当前格式**。
 *
 * ## 定位
 * 兼容版（中转版）的核心：不是「兼容读取旧格式」，而是**读进来的同时就把旧数据改写成当前格式**。
 * 转换完成后下游（恢复逻辑、领域模型、计算链路）只需处理一种格式，
 * 后续版本因此可以彻底删掉历史分支。
 *
 * ## 转换发生在 JSON 层而不是领域模型层
 * 领域模型（Gson → data class）无法表达历史差异：
 * - 旧备份的附加状态字段名是复数 `appliedStatuses`（数组），当前是单数 `appliedStatus`（对象）；
 * - 旧备份的 `appliedStatus` 也可能是数组（早期实现）；
 * - 旧备份缺字段 / 多字段，无法靠 data class 的默认值区分「缺失」与「空」。
 * 因此转换在 JSON 树层做，产出**规范化的当前格式 JSON**，再交给 [BackupGson] 解析。
 *
 * ## 铁律：格式转换不得改变语义
 * 转换只做「形状归一」（数组→对象、补默认值），**绝不改写业务语义**。
 * 例如引用了已下线的内置「加班」状态的历史记录，转换后仍保留原 statusId 与 isOvertime=false ——
 * 因为 2026-09-14 下线该内置状态时项目已定「旧数据不做迁移」，
 * 若在本次升级里「顺手修正」，历史工时/薪资就会变，违反「升级前后同月工时薪资逐分相同」这条底线。
 * 受影响的记录由调用方以 [UpgradeResult.notes] 告知用户，由用户自行决定是否调整。
 */
object BackupCompat {

    /** 转换结果 */
    data class UpgradeResult(
        /** 备份种类 */
        val kind: BackupKind,
        /** 源格式版本 */
        val fromVersion: Int,
        /** 目标格式版本 */
        val toVersion: Int,
        /** 规范化后的当前格式 JSON */
        val json: String,
        /** 转换说明（可展示给用户：这条旧备份被转换了什么） */
        val notes: List<String>
    )

    /** 排班类型合法值 */
    private val SCHEDULE_TYPES = setOf("SHIFT", "LEAVE", "SWAP", "REST")

    /** 计薪方式合法值 */
    private val SALARY_MODES = setOf("NORMAL", "WEEKEND", "HOLIDAY")

    /** 附加状态默认时间段（与 DB 迁移 MIGRATION_2_3 的填充值保持一致） */
    private const val DEFAULT_STATUS_START = "09:00"
    private const val DEFAULT_STATUS_END = "18:00"

    /** 初始口径的生效日（覆盖全部历史排班） */
    private const val INITIAL_EFFECTIVE_FROM = "2000-01-01"

    // ── 探测 ─────────────────────────────────────────────────────────────

    /**
     * 判断备份种类。
     * 按**顶层 key 集合**判断（不按子串猜测），否则备注里带关键字就会误判。
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

    /** 读取备份的格式版本；字段缺失/非法一律按 v1 处理（最老格式，交给转换器兜底） */
    fun detectVersion(root: JsonObject): Int {
        val el = root.get("version") ?: return 1
        if (!el.isJsonPrimitive) return 1
        return runCatching { el.asJsonPrimitive.asInt }.getOrNull() ?: 1
    }

    /** 当前版本下该种类备份的格式版本号 */
    fun currentVersion(kind: BackupKind): Int = when (kind) {
        BackupKind.APP_DATA -> DataFormat.APP_DATA_BACKUP
        BackupKind.SHIFT_CONFIG -> DataFormat.SHIFT_CONFIG_BACKUP
    }

    // ── 转换主入口 ───────────────────────────────────────────────────────

    /**
     * 把任意历史版本备份升级为当前格式。
     *
     * @throws IllegalArgumentException JSON 无法解析或不是可识别的备份格式
     */
    fun upgrade(json: String): UpgradeResult {
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
            ?: throw IllegalArgumentException("JSON 解析失败")
        // 套了一层壳（如 {"data":{...}}）→ 先解包再判定
        val effectiveRoot = unwrapOnce(root) ?: root
        val kind = detectKind(effectiveRoot) ?: throw IllegalArgumentException("无法识别的备份格式")
        val from = detectVersion(effectiveRoot)
        val notes = mutableListOf<String>()
        val out = when (kind) {
            BackupKind.APP_DATA -> canonicalizeAppData(effectiveRoot, from, notes)
            BackupKind.SHIFT_CONFIG -> canonicalizeShiftConfig(effectiveRoot, from, notes)
        }
        return UpgradeResult(kind, from, currentVersion(kind), out.toString(), notes)
    }

    // ── 应用数据 ─────────────────────────────────────────────────────────

    private fun canonicalizeAppData(root: JsonObject, fromVersion: Int, notes: MutableList<String>): JsonObject {
        val out = JsonObject()
        out.addProperty("version", DataFormat.APP_DATA_BACKUP)
        out.addProperty("exportTime", root.stringOrNull("exportTime") ?: "")

        // v1/v2 时代的备份只序列化最近 12 个月排班（旧实现 (-11..0)），
        // 而恢复是「整表替换」→ 更早历史在备份里本就不存在、恢复后直接消失，必须显式告知用户
        if (fromVersion <= 2) {
            notes += "该备份为旧版本格式（v$fromVersion），仅含最近 12 个月排班，更早的历史已不在备份中"
        }

        // 排班记录
        root.arrayOrNull("scheduleRecords")?.let { arr ->
            val list = JsonArray()
            arr.forEach { el ->
                if (el.isJsonObject) list.add(canonicalRecord(el.asJsonObject, notes))
            }
            out.add("scheduleRecords", list)
        }

        // 配置类集合
        copyArray(root, "shifts", out) { canonicalShift(it) }
        copyArray(root, "shiftStatuses", out) { canonicalStatus(it) }
        copyArray(root, "globalBreaks", out) { canonicalBreak(it) }
        copyArray(root, "extraItems", out) { canonicalExtra(it) }

        // 偏好与规则（结构自 v1 起未变 → 原样透传；缺失即「不覆盖」）
        for (key in PASSTHROUGH_KEYS) {
            root.get(key)?.let { out.add(key, it) }
        }

        // 计算口径档案（应用数据备份 v4 才引入）：
        // - 来源版本 < 4 → 备份里本来就没有这个字段 → 用备份中的薪资/考勤/时段合成初始档案，
        //   否则恢复后历史日期没有口径可用 → 工时/薪资会回退到 DataStore 现值而算错；
        // - 来源版本 ≥ 4 但字段缺失 → 视为「不覆盖」，**不合成**
        //   （内部快照就是这种形状：它不该动用户已有的口径档案）。
        val profiles = root.arrayOrNull("calcProfiles")
        when {
            profiles != null -> {
                val arr = JsonArray()
                profiles.forEach { el -> if (el.isJsonObject) arr.add(canonicalProfile(el.asJsonObject)) }
                out.add("calcProfiles", arr)
            }
            fromVersion < 4 -> {
                out.add("calcProfiles", JsonArray().apply { add(synthesizedProfile(root, fromVersion)) })
                notes += "旧备份不含计算口径档案，已按备份中的薪资/考勤/休息时段生成初始口径" +
                    "（生效日 $INITIAL_EFFECTIVE_FROM），历史计算口径保持不变"
            }
            else -> Unit   // 字段缺失 = 不覆盖
        }
        return out
    }

    // ── 班次配置 ─────────────────────────────────────────────────────────

    private fun canonicalizeShiftConfig(root: JsonObject, fromVersion: Int, notes: MutableList<String>): JsonObject {
        val out = JsonObject()
        out.addProperty("version", DataFormat.SHIFT_CONFIG_BACKUP)
        out.addProperty("exportTime", root.stringOrNull("exportTime") ?: "")
        copyArray(root, "shifts", out) { canonicalShift(it) }
        copyArray(root, "shiftStatuses", out) { canonicalStatus(it) }
        copyArray(root, "globalBreaks", out) { canonicalBreak(it) }
        copyArray(root, "extraItems", out) { canonicalExtra(it) }
        for (key in PASSTHROUGH_KEYS) {
            root.get(key)?.let { out.add(key, it) }
        }
        return out
    }

    /** 偏好类字段：结构自 v1 起未变，原样透传（缺失即「不覆盖」） */
    private val PASSTHROUGH_KEYS = listOf(
        "salaryConfig", "attendConfig", "scheduleRule", "displaySchemes",
        "disabledAccountIds", "accountCategories", "accountsInitialized",
        "reminderEnabled", "reminderMethod", "reminderClockIn", "reminderClockOut",
        "reminderClockInMinutes", "reminderClockOutMinutes", "reminderNotifyBar",
        "shiftOrder", "statusOrder", "extraOrder", "breakOrder",
        "shiftColorIndex", "statusColorIndex"
    )

    // ── 逐项规范化 ───────────────────────────────────────────────────────

    private fun canonicalRecord(obj: JsonObject, notes: MutableList<String>): JsonObject {
        val out = JsonObject()
        out.addProperty("date", obj.stringOrNull("date") ?: "")
        out.addProperty("type", normalizeEnum(obj.stringOrNull("type"), SCHEDULE_TYPES, "SHIFT")!!)
        obj.stringOrNull("shiftId")?.let { out.addProperty("shiftId", it) }
        obj.stringOrNull("actualStartTime")?.let { out.addProperty("actualStartTime", it) }
        obj.stringOrNull("actualEndTime")?.let { out.addProperty("actualEndTime", it) }
        obj.stringOrNull("remark")?.let { out.addProperty("remark", it) }
        out.add("extraItemIds", canonicalIdList(obj.get("extraItemIds")))
        val applied = canonicalAppliedStatus(obj, notes)
        out.add("appliedStatus", applied ?: JsonNull.INSTANCE)
        normalizeEnum(obj.stringOrNull("salaryMode"), SALARY_MODES, null)
            ?.let { out.addProperty("salaryMode", it) }
        out.addProperty("ignoreEarlyArrival", obj.boolOrDefault("ignoreEarlyArrival", false))
        out.addProperty("ignoreLateLeave", obj.boolOrDefault("ignoreLateLeave", false))
        out.addProperty("confirmEarlyOT", obj.boolOrDefault("confirmEarlyOT", false))
        out.addProperty("confirmLateOT", obj.boolOrDefault("confirmLateOT", false))
        return out
    }

    /**
     * 附加状态规范化。
     *
     * 覆盖三种历史形状：
     * 1. `appliedStatus` 为对象（当前格式，也是 v3+ 备份的样子）
     * 2. `appliedStatus` 为数组（早期实现）
     * 3. `appliedStatuses` 为数组（更早的字段名，**当前模型根本没有这个字段**，
     *    若不处理，Gson 会静默丢弃 → 附加状态整个丢失）
     *
     * 数组取第一个元素（与既有读取逻辑 `parseAppliedStatus` 的 firstOrNull 行为一致，
     * 保证转换前后行为相同）；多于一个时在 notes 里提示。
     */
    private fun canonicalAppliedStatus(obj: JsonObject, notes: MutableList<String>): JsonElement? {
        var raw = obj.get("appliedStatus")
        if (raw == null || raw.isJsonNull) {
            val legacy = obj.get("appliedStatuses")
            if (legacy != null && !legacy.isJsonNull && legacy.isJsonArray) {
                notes += "旧备份的附加状态字段名（appliedStatuses）已转换为当前格式（appliedStatus）"
                raw = legacy
            }
        }
        if (raw == null || raw.isJsonNull) return null
        if (raw.isJsonArray) {
            val arr = raw.asJsonArray
            if (arr.size() > 1) {
                notes += "旧备份中有 ${arr.size()} 个附加状态，当前格式只支持 1 个，已保留第一个"
            }
            val first = arr.firstOrNull() ?: return null
            if (!first.isJsonObject) return null
            return canonicalAppliedStatusObject(first.asJsonObject)
        }
        if (!raw.isJsonObject) return null
        return canonicalAppliedStatusObject(raw.asJsonObject)
    }

    private fun canonicalAppliedStatusObject(o: JsonObject): JsonElement? {
        val statusId = o.stringOrNull("statusId") ?: return null
        if (statusId.isBlank()) return null
        val out = JsonObject()
        out.addProperty("statusId", statusId)
        o.stringOrNull("startTime")?.let { out.addProperty("startTime", it) }
        o.stringOrNull("endTime")?.let { out.addProperty("endTime", it) }
        // 旧备份无 isOvertime 字段 → false（与「旧数据不做迁移」口径一致，不擅自改成 true）
        out.addProperty("isOvertime", o.boolOrDefault("isOvertime", false))
        return out
    }

    private fun canonicalShift(obj: JsonObject): JsonObject {
        val out = JsonObject()
        out.addProperty("id", obj.stringOrNull("id") ?: "")
        out.addProperty("name", obj.stringOrNull("name") ?: "")
        out.addProperty("color", obj.stringOrNull("color") ?: "#3b82f6")
        out.addProperty("startTime", obj.stringOrNull("startTime") ?: "08:00")
        out.addProperty("endTime", obj.stringOrNull("endTime") ?: "17:00")
        obj.get("normalWorkHours")
            ?.takeIf { it.isJsonPrimitive && !it.asJsonPrimitive.isBoolean }
            ?.let { out.add("normalWorkHours", it) }
        out.addProperty("builtIn", obj.boolOrDefault("builtIn", false))
        obj.stringOrNull("builtInType")?.let { out.addProperty("builtInType", it) }
        out.add("linkedExtraIds", canonicalIdList(obj.get("linkedExtraIds")))
        obj.stringOrNull("archivedAt")?.let { out.addProperty("archivedAt", it) }
        return out
    }

    private fun canonicalStatus(obj: JsonObject): JsonObject {
        val out = JsonObject()
        out.addProperty("id", obj.stringOrNull("id") ?: "")
        out.addProperty("name", obj.stringOrNull("name") ?: "")
        out.addProperty("color", obj.stringOrNull("color") ?: "#F97316")
        out.addProperty("builtIn", obj.boolOrDefault("builtIn", false))
        obj.stringOrNull("reportType")?.let { out.addProperty("reportType", it) }
        // v1/v2 备份没有时间段字段 → 填与 DB 迁移 MIGRATION_2_3 相同的默认值
        out.addProperty("startTime", obj.stringOrNull("startTime") ?: DEFAULT_STATUS_START)
        out.addProperty("endTime", obj.stringOrNull("endTime") ?: DEFAULT_STATUS_END)
        obj.stringOrNull("archivedAt")?.let { out.addProperty("archivedAt", it) }
        return out
    }

    private fun canonicalBreak(obj: JsonObject): JsonObject {
        val out = JsonObject()
        out.addProperty("id", obj.stringOrNull("id") ?: "")
        out.addProperty("label", obj.stringOrNull("label") ?: "")
        out.addProperty("startTime", obj.stringOrNull("startTime") ?: "")
        out.addProperty("endTime", obj.stringOrNull("endTime") ?: "")
        obj.stringOrNull("archivedAt")?.let { out.addProperty("archivedAt", it) }
        return out
    }

    private fun canonicalExtra(obj: JsonObject): JsonObject {
        val out = JsonObject()
        out.addProperty("id", obj.stringOrNull("id") ?: "")
        out.addProperty("name", obj.stringOrNull("name") ?: "")
        out.addProperty("type", obj.stringOrNull("type") ?: "allowance")
        out.addProperty("amount", obj.doubleOrDefault("amount", 0.0))
        obj.stringOrNull("archivedAt")?.let { out.addProperty("archivedAt", it) }
        return out
    }

    private fun canonicalProfile(obj: JsonObject): JsonObject {
        val out = JsonObject()
        out.addProperty("id", obj.stringOrNull("id") ?: "profile_initial")
        out.addProperty("name", obj.stringOrNull("name") ?: "初始口径")
        out.add("salary", obj.objectOrParsed("salary"))
        out.add("attend", obj.objectOrParsed("attend"))
        out.add("breaks", obj.arrayOrNull("breaks") ?: JsonArray())
        out.addProperty("effectiveFrom", obj.stringOrNull("effectiveFrom") ?: INITIAL_EFFECTIVE_FROM)
        obj.stringOrNull("effectiveTo")?.let { out.addProperty("effectiveTo", it) }
        obj.stringOrNull("note")?.let { out.addProperty("note", it) }
        out.addProperty("createdAt", obj.longOrDefault("createdAt", 0L))
        return out
    }

    /** 由旧备份的薪资/考勤/时段合成初始口径档案（保证旧备份恢复后历史计算口径可用） */
    private fun synthesizedProfile(root: JsonObject, fromVersion: Int): JsonObject {
        val out = JsonObject()
        out.addProperty("id", "profile_initial")
        out.addProperty("name", "初始口径")
        out.add("salary", root.get("salaryConfig")?.let { asObjectOrParsed(it) } ?: JsonObject())
        out.add("attend", root.get("attendConfig")?.let { asObjectOrParsed(it) } ?: JsonObject())
        out.add("breaks", root.get("globalBreaks")?.let { asObjectOrParsed(it) } ?: JsonArray())
        out.addProperty("effectiveFrom", INITIAL_EFFECTIVE_FROM)
        out.add("effectiveTo", JsonNull.INSTANCE)
        out.addProperty("note", "由 v$fromVersion 旧备份转换生成")
        out.addProperty("createdAt", System.currentTimeMillis())
        return out
    }

    // ── JSON 小工具 ──────────────────────────────────────────────────────

    /** 复制数组字段：源缺失 → 目标也缺失（保持「字段缺失 = 不覆盖」语义）；源存在 → 逐项规范化 */
    private inline fun copyArray(
        root: JsonObject,
        key: String,
        out: JsonObject,
        transform: (JsonObject) -> JsonObject
    ) {
        val arr = root.arrayOrNull(key) ?: return
        val list = JsonArray()
        arr.forEach { el -> if (el.isJsonObject) list.add(transform(el.asJsonObject)) }
        out.add(key, list)
    }

    /** ID 列表规范化：只保留非空字符串 */
    private fun canonicalIdList(el: JsonElement?): JsonArray {
        val out = JsonArray()
        if (el == null || el.isJsonNull) return out
        if (el.isJsonArray) {
            el.asJsonArray.forEach { item ->
                if (item.isJsonPrimitive) {
                    val s = item.asString
                    if (s.isNotBlank()) out.add(s)
                }
            }
        } else if (el.isJsonPrimitive) {
            // 极老格式可能是逗号分隔字符串
            el.asString.split(",").forEach { s -> if (s.isNotBlank()) out.add(s.trim()) }
        }
        return out
    }

    /** 枚举值规范化：不在白名单内 → 用兜底值（null 表示丢弃该字段） */
    private fun normalizeEnum(value: String?, allowed: Set<String>, fallback: String?): String? =
        if (value != null && value in allowed) value else fallback
}
