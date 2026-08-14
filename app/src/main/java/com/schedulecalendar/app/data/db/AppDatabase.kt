// app/src/main/java/com/schedulecalendar/app/data/db/AppDatabase.kt
package com.schedulecalendar.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.schedulecalendar.app.data.db.dao.ExtraItemDao
import com.schedulecalendar.app.data.db.dao.ScheduleRecordDao
import com.schedulecalendar.app.data.db.dao.ShiftBreakDao
import com.schedulecalendar.app.data.db.dao.ShiftDao
import com.schedulecalendar.app.data.db.dao.ShiftStatusDao
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
        ShiftStatusEntity::class
    ],
    version = 5,
    exportSchema = true   // 导出 schema 至 app/schemas/，便于版本迁移追踪
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun shiftDao(): ShiftDao
    abstract fun scheduleRecordDao(): ScheduleRecordDao
    abstract fun extraItemDao(): ExtraItemDao
    abstract fun shiftBreakDao(): ShiftBreakDao
    abstract fun shiftStatusDao(): ShiftStatusDao

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
