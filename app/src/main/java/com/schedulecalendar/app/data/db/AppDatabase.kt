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
    // v7 = 最终兼容版的数据格式（栅栏版本）：结构与 v6 相同，v6 的旧格式数据已由兼容版归一化。
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
        /** 数据库文件名（**唯一来源**：Room 构建、启动期版本探测都引用它，避免两处各写一份而错位）。 */
        const val DB_NAME = "schedule_calendar.db"

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
