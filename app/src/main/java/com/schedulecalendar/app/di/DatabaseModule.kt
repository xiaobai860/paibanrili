// app/src/main/java/com/schedulecalendar/app/di/DatabaseModule.kt
package com.schedulecalendar.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.schedulecalendar.app.data.db.AppDatabase
import com.schedulecalendar.app.data.db.dao.ExtraItemDao
import com.schedulecalendar.app.data.db.dao.ScheduleRecordDao
import com.schedulecalendar.app.data.db.dao.ShiftBreakDao
import com.schedulecalendar.app.data.db.dao.ShiftDao
import com.schedulecalendar.app.data.db.dao.ShiftStatusDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, "schedule_calendar.db")
            // 不使用破坏性迁移：升级若未提供 Migration，Room 会直接抛异常（fail-fast）而非静默清空全部数据。
            // 以后每次提升 @Database(version=...) 都必须在下方 addMigrations(...) 提供对应迁移。
            .addMigrations()
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    super.onOpen(db)
                    AppDatabase.configure(db)   // 开启 WAL + synchronous=NORMAL
                }
            })
            .build()

    @Provides fun provideShiftDao(db: AppDatabase): ShiftDao = db.shiftDao()
    @Provides fun provideScheduleRecordDao(db: AppDatabase): ScheduleRecordDao = db.scheduleRecordDao()
    @Provides fun provideExtraItemDao(db: AppDatabase): ExtraItemDao = db.extraItemDao()
    @Provides fun provideShiftBreakDao(db: AppDatabase): ShiftBreakDao = db.shiftBreakDao()
    @Provides fun provideShiftStatusDao(db: AppDatabase): ShiftStatusDao = db.shiftStatusDao()
}
