// app/src/main/java/com/schedulecalendar/app/data/compat/LegacyDbSnapshot.kt
package com.schedulecalendar.app.data.compat

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.google.gson.Gson
import com.schedulecalendar.app.data.repository.parseAppliedStatus
import com.schedulecalendar.app.domain.model.AttendConfig
import com.schedulecalendar.app.domain.model.CalcProfile
import com.schedulecalendar.app.domain.model.ExtraItem
import com.schedulecalendar.app.domain.model.SalaryConfig
import com.schedulecalendar.app.domain.model.ScheduleRecord
import com.schedulecalendar.app.domain.model.ScheduleType
import com.schedulecalendar.app.domain.model.SalaryMode
import com.schedulecalendar.app.domain.model.Shift
import com.schedulecalendar.app.domain.model.ShiftBreak
import com.schedulecalendar.app.domain.model.ShiftStatus
import java.io.File

/**
 * 迁移前快照：把旧版本数据库只读抓取成当前格式的备份 JSON。
 *
 * Room 的迁移本身是事务性的（失败会回滚），但「迁移成功、数据却不对」这种问题
 * Room 管不了。因此升级流程需要一份迁移前的数据副本：迁移后校验不通过时用它还原。
 */
object LegacyDbSnapshot {

    /** 快照内容 */
    data class Snapshot(
        /** 当前格式的应用数据 JSON（可直接走既有恢复逻辑还原） */
        val json: String,
        /** 旧库的 schema 版本（PRAGMA user_version） */
        val dbVersion: Int,
        /** 旧库各表行数（迁移后校验的基准） */
        val counts: Counts
    )

    /** 各表行数 */
    data class Counts(
        val records: Int = 0,
        val shifts: Int = 0,
        val statuses: Int = 0,
        val breaks: Int = 0,
        val extras: Int = 0,
        val profiles: Int = 0
    )

    /** 数据库文件名（与 DatabaseModule 保持一致） */
    const val DB_NAME = "schedule_calendar.db"

    /**
     * 抓取旧库快照。
     *
     * @param context 应用上下文
     * @param dbFile  旧库文件（不存在则返回 null，表示全新安装，无需快照）
     * @return 快照；读取失败时返回 null（不阻断升级，只是失去回滚能力）
     */
    fun capture(context: Context, dbFile: File): Snapshot? {
        if (!dbFile.exists()) return null
        return runCatching {
            val copy = copyDb(context, dbFile)
            val db = SQLiteDatabase.openDatabase(copy.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                val version = db.rawQuery("PRAGMA user_version", null).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else 0
                }
                val records = readRows(db, "schedule_records").map { recordFrom(it) }
                val shifts = readRows(db, "shifts").map { shiftFrom(it) }
                val statuses = readRows(db, "shift_statuses").map { statusFrom(it) }
                val breaks = readRows(db, "shift_breaks").map { breakFrom(it) }
                val extras = readRows(db, "extra_items").map { extraFrom(it) }
                val profiles = readRows(db, "calc_profiles").map { profileFrom(it) }

                val payload = AppDataBackup(
                    version = DataFormat.APP_DATA_BACKUP,
                    exportTime = "",
                    scheduleRecords = records,
                    shifts = shifts,
                    globalBreaks = breaks,
                    shiftStatuses = statuses,
                    extraItems = extras,
                    // ⚠️ 旧库（v6 之前）没有口径档案表 → 必须留 null（=「字段缺失，不覆盖」），
                    // 若写成空列表，回滚会把迁移后新建的初始档案一并删掉 → 历史计算失去口径。
                    calcProfiles = profiles.ifEmpty { null }
                )
                Snapshot(
                    json = Gson().toJson(payload),
                    dbVersion = version,
                    counts = Counts(
                        records = records.size,
                        shifts = shifts.size,
                        statuses = statuses.size,
                        breaks = breaks.size,
                        extras = extras.size,
                        profiles = profiles.size
                    )
                )
            } finally {
                db.close()
                copy.delete()
            }
        }.getOrNull()
    }

    /** 把库文件（含 -wal/-shm）复制到缓存目录，避免干扰线上库 */
    private fun copyDb(context: Context, dbFile: File): File {
        val dir = File(context.cacheDir, "db_snapshot").also { it.mkdirs() }
        val target = File(dir, "snapshot_${System.currentTimeMillis()}.db")
        dbFile.copyTo(target, overwrite = true)
        // WAL 模式下的已提交数据可能还在 -wal 里，一并复制才能读到完整内容
        File(dbFile.path + "-wal").takeIf { it.exists() }?.copyTo(File(target.path + "-wal"), overwrite = true)
        File(dbFile.path + "-shm").takeIf { it.exists() }?.copyTo(File(target.path + "-shm"), overwrite = true)
        return target
    }

    /** 读一张表的全部行（表不存在时返回空表） */
    private fun readRows(db: SQLiteDatabase, table: String): List<RowSnapshot> {
        val out = mutableListOf<RowSnapshot>()
        runCatching {
            db.rawQuery("SELECT * FROM `$table`", null).use { c ->
                while (c.moveToNext()) out.add(RowSnapshot(c))
            }
        }
        return out
    }

    // ── 行 → 领域对象 ───────────────────────────────────────────────────

    private fun recordFrom(row: RowSnapshot): ScheduleRecord = ScheduleRecord(
        date = row.str("date") ?: "",
        type = runCatching { ScheduleType.valueOf(row.str("type") ?: "SHIFT") }
            .getOrDefault(ScheduleType.SHIFT),
        shiftId = row.str("shiftId"),
        actualStartTime = row.str("actualStartTime"),
        actualEndTime = row.str("actualEndTime"),
        remark = row.str("remark"),
        extraItemIds = row.str("extraItemIdsJson")?.let { parseIdList(it) } ?: emptyList(),
        appliedStatus = row.str("appliedStatusesJson")?.let { parseAppliedStatus(it) },
        salaryMode = row.str("salaryMode")?.let {
            runCatching { SalaryMode.valueOf(it) }.getOrNull()
        },
        ignoreEarlyArrival = row.bool("ignoreEarlyArrival"),
        ignoreLateLeave = row.bool("ignoreLateLeave"),
        confirmEarlyOT = row.bool("confirmEarlyOT"),
        confirmLateOT = row.bool("confirmLateOT")
    )

    private fun shiftFrom(row: RowSnapshot): Shift = Shift(
        id = row.str("id") ?: "",
        name = row.str("name") ?: "",
        color = row.str("color") ?: "#3b82f6",
        startTime = row.str("startTime") ?: "08:00",
        endTime = row.str("endTime") ?: "17:00",
        normalWorkHours = row.dbl("normalWorkHours") ?: row.dbl("workHours"),
        builtIn = row.bool("builtIn"),
        builtInType = row.str("builtInType"),
        linkedExtraIds = row.str("linkedExtraIdsJson")?.let { parseIdList(it) } ?: emptyList(),
        archivedAt = row.str("archivedAt")
    )

    private fun statusFrom(row: RowSnapshot): ShiftStatus = ShiftStatus(
        id = row.str("id") ?: "",
        name = row.str("name") ?: "",
        color = row.str("color") ?: "#F97316",
        builtIn = row.bool("builtIn"),
        reportType = row.str("reportType"),
        // v1/v2 没有时间段列：与 MIGRATION_2_3 / BackupCompat 填同样的默认值
        startTime = row.str("startTime") ?: "09:00",
        endTime = row.str("endTime") ?: "18:00",
        archivedAt = row.str("archivedAt")
    )

    private fun breakFrom(row: RowSnapshot): ShiftBreak = ShiftBreak(
        id = row.str("id") ?: "",
        label = row.str("label") ?: "",
        startTime = row.str("startTime") ?: "",
        endTime = row.str("endTime") ?: "",
        archivedAt = row.str("archivedAt")
    )

    private fun extraFrom(row: RowSnapshot): ExtraItem = ExtraItem(
        id = row.str("id") ?: "",
        name = row.str("name") ?: "",
        type = row.str("type") ?: "allowance",
        amount = row.dbl("amount") ?: 0.0,
        archivedAt = row.str("archivedAt")
    )

    private fun profileFrom(row: RowSnapshot): CalcProfile = CalcProfile(
        id = row.str("id") ?: "",
        name = row.str("name") ?: "初始口径",
        salary = row.str("salaryJson")?.let { parseJson(it, SalaryConfig::class.java) } ?: SalaryConfig(),
        attend = row.str("attendJson")?.let { parseJson(it, AttendConfig::class.java) } ?: AttendConfig(),
        breaks = row.str("breaksJson")?.let { parseBreakList(it) } ?: emptyList(),
        effectiveFrom = row.str("effectiveFrom") ?: "2000-01-01",
        effectiveTo = row.str("effectiveTo"),
        note = row.str("note"),
        createdAt = row.long("createdAt")
    )

    // ── 解析小工具 ───────────────────────────────────────────────────────

    private inline fun <reified T> parseJson(json: String, type: Class<T>): T? =
        runCatching { BackupGson.instance.fromJson(json, type) }.getOrNull()

    /** 解析 ["id1","id2"] 形式的 ID 列表 */
    private fun parseIdList(json: String): List<String> = runCatching {
        com.google.gson.JsonParser.parseString(json).asJsonArray.mapNotNull { el ->
            if (el.isJsonPrimitive) el.asString.takeIf { it.isNotBlank() } else null
        }
    }.getOrDefault(emptyList())

    private fun parseBreakList(json: String): List<ShiftBreak> = runCatching {
        val type = com.google.gson.reflect.TypeToken.getParameterized(
            List::class.java, ShiftBreak::class.java
        ).type
        BackupGson.instance.fromJson<List<ShiftBreak>>(json, type) ?: emptyList()
    }.getOrDefault(emptyList())

    /**
     * 行快照：把 Cursor 的一行固定下来（Cursor 关闭后仍可读）。
     * 逐列按**列名**取值，缺列一律返回 null，由调用方用默认值兜底——
     * 这样同一段代码能兼容 v1~v6 各不相同的列集合。
     */
    private class RowSnapshot(cursor: Cursor) {
        private val values = HashMap<String, String?>()

        init {
            cursor.columnNames.forEachIndexed { i, name ->
                values[name] = if (cursor.isNull(i)) null else cursor.getString(i)
            }
        }

        fun str(name: String): String? = values[name]?.takeIf { it.isNotEmpty() }

        fun dbl(name: String): Double? = values[name]?.toDoubleOrNull()

        fun bool(name: String): Boolean = when (val v = values[name]) {
            null -> false
            else -> v == "1" || v.equals("true", ignoreCase = true)
        }

        fun long(name: String): Long = values[name]?.toLongOrNull() ?: 0L
    }
}
