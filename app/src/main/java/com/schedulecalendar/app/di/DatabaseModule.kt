// app/src/main/java/com/schedulecalendar/app/di/DatabaseModule.kt
package com.schedulecalendar.app.di

import android.content.Context
import androidx.room.Room
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
            .fallbackToDestructiveMigration(dropAllTables = true)   // Room 2.7+ 新签名：v1→v2 破坏性迁移
            .build()

    @Provides fun provideShiftDao(db: AppDatabase): ShiftDao = db.shiftDao()
    @Provides fun provideScheduleRecordDao(db: AppDatabase): ScheduleRecordDao = db.scheduleRecordDao()
    @Provides fun provideExtraItemDao(db: AppDatabase): ExtraItemDao = db.extraItemDao()
    @Provides fun provideShiftBreakDao(db: AppDatabase): ShiftBreakDao = db.shiftBreakDao()
    @Provides fun provideShiftStatusDao(db: AppDatabase): ShiftStatusDao = db.shiftStatusDao()
}
