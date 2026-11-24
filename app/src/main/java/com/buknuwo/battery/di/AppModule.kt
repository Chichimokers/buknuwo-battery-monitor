package com.buknuwo.battery.di

import android.content.Context
import androidx.room.Room
import com.buknuwo.battery.database.AppDatabase
import com.buknuwo.battery.telemetry.TelemetryCatalog
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Retention(AnnotationRetention.RUNTIME)
@Qualifier
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "buknuwo.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideRuleDao(db: AppDatabase) = db.alertRuleDao()
    @Provides fun provideStateDao(db: AppDatabase) = db.alertStateDao()
    @Provides fun provideEventDao(db: AppDatabase) = db.alertEventDao()
    @Provides fun provideSampleDao(db: AppDatabase) = db.telemetrySampleDao()

    @Provides
    @Singleton
    fun provideTelemetryCatalog(): TelemetryCatalog = TelemetryCatalog()

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
