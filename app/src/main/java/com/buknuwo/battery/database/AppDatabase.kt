package com.buknuwo.battery.database

import androidx.room.Database
import androidx.room.RoomDatabase
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
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun alertRuleDao(): AlertRuleDao
    abstract fun alertStateDao(): AlertStateDao
    abstract fun alertEventDao(): AlertEventDao
    abstract fun telemetrySampleDao(): TelemetrySampleDao
}
