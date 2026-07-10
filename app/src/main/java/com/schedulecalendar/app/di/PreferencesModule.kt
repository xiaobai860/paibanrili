// app/src/main/java/com/schedulecalendar/app/di/PreferencesModule.kt
package com.schedulecalendar.app.di

import android.content.Context
import com.schedulecalendar.app.data.prefs.AppPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PreferencesModule {

    @Provides @Singleton
    fun provideAppPreferences(@ApplicationContext ctx: Context): AppPreferences =
        AppPreferences(ctx)
}
