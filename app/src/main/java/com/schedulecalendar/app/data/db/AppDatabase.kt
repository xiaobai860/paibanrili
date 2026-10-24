// app/src/main/java/com/schedulecalendar/app/data/db/AppDatabase.kt
package com.schedulecalendar.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.schedulecalendar.app.data.db.dao.CalcProfileDao
import com.schedulecalendar.app.data.db.dao.ExtraItemDao
import com.schedulecalendar.app.data.db.dao.ScheduleRecordDao
import com.schedulecalendar.app.data.db.dao.ShiftBreakDao
import com.schedulecalendar.app.data.db.dao.ShiftDao
import com.schedulecalendar.app.data.db.dao.ShiftStatusDao
import com.schedulecalendar.app.data.db.entity.CalcProfileEntity
import com.schedulecalendar.app.data.db.entity.ExtraItemEntity
import com.schedulecalendar.app.data.db.entity.ScheduleRecordEntity
import com.schedulecalendar.app.data.db.entity.ShiftBreakEntity
import com.schedulecalendar.app.data.db.entity.ShiftEntity
import com.schedulecalendar.app.data.db.entity.ShiftStatusEntity

@Database(
    entities = [
        ShiftEntity::class,
        ScheduleRecordEntity::class,
        ExtraItemEntity::class,
        ShiftBreakEntity::class,
        ShiftStatusEntity::class,
        CalcProfileEntity::class
    ],
    // v7 = 中转兼容版：结构与 v6 相同，但 MIGRATION_6_7 会把旧格式数据归一化到当前格式。
    // 版本号必须与 DataFormat.CURRENT 保持一致（见 data/compat/DataFormat.kt）。
    version = 7,
    exportSchema = true   // 导出 schema 至 app/schemas/，便于版本迁移追踪
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun shiftDao(): ShiftDao
    abstract fun scheduleRecordDao(): ScheduleRecordDao
    abstract fun extraItemDao(): ExtraItemDao
    abstract fun shiftBreakDao(): ShiftBreakDao
    abstract fun shiftStatusDao(): ShiftStatusDao
    abstract fun calcProfileDao(): CalcProfileDao

    companion object {
        /**
         * 开启 WAL（write-ahead logging）日志模式：
         * - 读写并发能力提升（读不阻塞写、写不阻塞读），显著提升日历频繁读取时的响应。
         * - 注意：Room 在 API 16+ 默认即为 WAL，此处显式设置以防御厂商定制 ROM 回退为 TRUNCATE。
         */
        fun configure(db: SupportSQLiteDatabase) {
            db.query("PRAGMA journal_mode=WAL").close()
            db.query("PRAGMA synchronous=NORMAL").close()
        }
    }
}
