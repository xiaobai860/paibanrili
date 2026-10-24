package com.schedulecalendar.app.data.compat

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.schedulecalendar.app.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 造一个 v6 旧库（**临时路径**），供「兼容闸门」的整条升级链路在真机上跑一遍。
 *
 * ## 为什么不在真实路径直接建
 * 闸门的回滚底牌（[LegacyDbSnapshot] 快照 → BackupManager 恢复）走的是**注入的应用单例 Room**，
 * 只能作用于应用真实库路径；而测试进程里应用自己的 Room 已经把该路径的库打开并创建为 v7，
 * 进程内无法替换（Room 打开时发现 user_version 不匹配会要求迁移）。
 * 因此改为：测试只负责**造库**，由外部在进程停止后把文件放到真实路径。
 *
 * ## 用法
 * 1. 运行本测试 → 生成 `databases/compat-v6-source.db`（v6，含归档时段与旧数组格式状态）；
 * 2. `adb pull` 取回该文件 → `am force-stop` 停进程 → `run-as cp` 覆盖到
 *    `databases/schedule_calendar.db`；
 * 3. `adb shell am start ...` → 闸门真实跑一遍，日志见 logcat 标签 `CompatGate`。
 */
@RunWith(AndroidJUnit4::class)
class CompatGateUpgradeFlowTest {

    @Test
    fun 生成v6旧库样本() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val sourceName = "compat-v6-source.db"
        ctx.getDatabasePath(sourceName).parentFile?.mkdirs()
        ctx.deleteDatabase(sourceName)

        val helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AppDatabase::class.java
        )
        val db: SupportSQLiteDatabase = helper.createDatabase(sourceName, 6)
        assertEquals("样本库必须是 v6", 6, db.version)

        // 一个班次
        db.execSQL(
            "INSERT OR REPLACE INTO shifts (id, name, color, startTime, endTime, normalWorkHours, " +
                "builtIn, builtInType, linkedExtraIdsJson, archivedAt) " +
                "VALUES ('s1','早班','#3b82f6','08:00','17:00',8.0,0,NULL,'[]',NULL)"
        )
        // 两个休息时段：其中一个已归档（回归：归档项不得让行数校验误判）
        db.execSQL(
            "INSERT OR REPLACE INTO shift_breaks (id, label, startTime, endTime, archivedAt) " +
                "VALUES ('b1','午休','12:00','13:00',NULL)"
        )
        db.execSQL(
            "INSERT OR REPLACE INTO shift_breaks (id, label, startTime, endTime, archivedAt) " +
                "VALUES ('b2','旧的用餐时段','18:00','18:30','2026-01-01')"
        )
        // 一条排班：附加状态用旧「数组」格式
        db.execSQL(
            "INSERT OR REPLACE INTO schedule_records (date, type, shiftId, actualStartTime, actualEndTime, " +
                "remark, extraItemIdsJson, appliedStatusesJson, salaryMode, ignoreEarlyArrival, " +
                "ignoreLateLeave, confirmEarlyOT, confirmLateOT) VALUES " +
                "('2026-01-05','SHIFT','s1',NULL,NULL,NULL,'[]'," +
                "'[{\"statusId\":\"st1\",\"startTime\":\"09:00\",\"endTime\":\"18:00\"}]',NULL,0,0,0,0)"
        )
        db.close()
    }

    /**
     * 造一个**引用数据损坏**的 v6 旧库：让迁移后校验必然失败，
     * 用于真机验证「升级失败 → 自动回滚 → 数据回到升级前」这条安全网。
     *
     * 损坏点：appliedStatusesJson 写成非法 JSON（`{oops`）——
     * 6→7 的归一化只处理「数组 → 对象」，不会碰它 → 迁移后校验必然报错。
     */
    @Test
    fun 生成引用损坏的v6旧库样本() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val sourceName = "compat-v6-broken.db"
        ctx.getDatabasePath(sourceName).parentFile?.mkdirs()
        ctx.deleteDatabase(sourceName)

        val helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AppDatabase::class.java
        )
        val db: SupportSQLiteDatabase = helper.createDatabase(sourceName, 6)
        assertEquals("样本库必须是 v6", 6, db.version)

        db.execSQL(
            "INSERT OR REPLACE INTO shifts (id, name, color, startTime, endTime, normalWorkHours, " +
                "builtIn, builtInType, linkedExtraIdsJson, archivedAt) " +
                "VALUES ('s1','早班','#3b82f6','08:00','17:00',8.0,0,NULL,'[]',NULL)"
        )
        db.execSQL(
            "INSERT OR REPLACE INTO shift_breaks (id, label, startTime, endTime, archivedAt) " +
                "VALUES ('b1','午休','12:00','13:00',NULL)"
        )
        // ⚠️ 非法 JSON：迁移后校验必然判错 → 触发回滚
        db.execSQL(
            "INSERT OR REPLACE INTO schedule_records (date, type, shiftId, actualStartTime, actualEndTime, " +
                "remark, extraItemIdsJson, appliedStatusesJson, salaryMode, ignoreEarlyArrival, " +
                "ignoreLateLeave, confirmEarlyOT, confirmLateOT) VALUES " +
                "('2026-01-05','SHIFT','s1',NULL,NULL,NULL,'[]','{oops',NULL,0,0,0,0)"
        )
        db.close()
    }
}

