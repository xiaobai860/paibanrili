// app/src/main/java/com/schedulecalendar/app/di/DatabaseModule.kt
package com.schedulecalendar.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.schedulecalendar.app.data.db.AppDatabase
import com.schedulecalendar.app.data.db.dao.CalcProfileDao
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
        Room.databaseBuilder(ctx, AppDatabase::class.java, AppDatabase.DB_NAME)
            // 不使用破坏性迁移：升级若未提供 Migration，Room 会直接抛异常（fail-fast）而非静默清空全部数据。
            // 栅栏已抬到 v7（最终兼容版的数据格式），当前只有单一 schema，无需登记历史迁移；
            // 今后提升 @Database(version=...) 时，只需补**一条**从 v7 到新版本的迁移。
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
    @Provides fun provideCalcProfileDao(db: AppDatabase): CalcProfileDao = db.calcProfileDao()
}
