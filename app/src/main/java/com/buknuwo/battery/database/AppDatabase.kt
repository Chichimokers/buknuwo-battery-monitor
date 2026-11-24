package com.buknuwo.battery.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter fun stringFromInt(value: Int?): String? = value?.toString()
    @TypeConverter fun intFromString(value: String?): Int? = value?.toIntOrNull()
}

@Database(
    entities = [
        AlertRuleEntity::class,
        ConditionEntity::class,
        AlertStateEntity::class,
        AlertEventEntity::class,
        TelemetrySampleEntity::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun alertRuleDao(): AlertRuleDao
    abstract fun alertStateDao(): AlertStateDao
    abstract fun alertEventDao(): AlertEventDao
    abstract fun telemetrySampleDao(): TelemetrySampleDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE alert_rules ADD COLUMN notificationSoundType TEXT NOT NULL DEFAULT 'PRIORITY_DEFAULT'")
                db.execSQL("ALTER TABLE alert_rules ADD COLUMN notificationSoundUri TEXT")
            }
        }
    }
}
