package com.buknuwo.battery.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AlertRuleDao {
    @Query("SELECT * FROM alert_rules ORDER BY name")
    fun observeRules(): Flow<List<AlertRuleEntity>>

    @Query("SELECT * FROM alert_rules WHERE ruleId = :id")
    suspend fun getRule(id: String): AlertRuleEntity?

    @Upsert
    suspend fun upsertRule(rule: AlertRuleEntity)

    @Query("DELETE FROM alert_rules WHERE ruleId = :id")
    suspend fun deleteRule(id: String)

    @Query("SELECT * FROM alert_conditions WHERE ruleId = :ruleId ORDER BY orderIndex")
    suspend fun conditionsFor(ruleId: String): List<ConditionEntity>

    @Query("SELECT * FROM alert_conditions")
    suspend fun allConditions(): List<ConditionEntity>

    @Query("DELETE FROM alert_conditions WHERE ruleId = :id")
    suspend fun deleteConditions(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConditions(conditions: List<ConditionEntity>)

    @Transaction
    suspend fun replaceConditions(ruleId: String, conditions: List<ConditionEntity>) {
        deleteConditions(ruleId)
        insertConditions(conditions)
    }
}

@Dao
interface AlertStateDao {
    @Query("SELECT * FROM alert_states")
    suspend fun all(): List<AlertStateEntity>

    @Query("SELECT * FROM alert_states WHERE ruleId = :id")
    suspend fun get(id: String): AlertStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(state: AlertStateEntity)

    @Query("DELETE FROM alert_states WHERE ruleId = :id")
    suspend fun delete(id: String)
}

@Dao
interface AlertEventDao {
    @Insert
    suspend fun insert(event: AlertEventEntity)

    @Query("SELECT * FROM alert_events ORDER BY timestampMs DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AlertEventEntity>>

    @Query("SELECT * FROM alert_events WHERE ruleId = :ruleId ORDER BY timestampMs DESC LIMIT :limit")
    fun observeForRule(ruleId: String, limit: Int): Flow<List<AlertEventEntity>>

    @Query("DELETE FROM alert_events WHERE timestampMs < :before")
    suspend fun deleteOlderThan(before: Long)
}

@Dao
interface TelemetrySampleDao {
    @Insert
    suspend fun insert(sample: TelemetrySampleEntity)

    @Insert
    suspend fun insertAll(samples: List<TelemetrySampleEntity>)

    @Query("SELECT * FROM telemetry_samples WHERE timestampMs >= :since ORDER BY timestampMs ASC LIMIT :limit")
    suspend fun since(since: Long, limit: Int): List<TelemetrySampleEntity>

    @Query("SELECT * FROM telemetry_samples ORDER BY timestampMs DESC LIMIT 1")
    suspend fun latest(): TelemetrySampleEntity?

    @Query("DELETE FROM telemetry_samples WHERE timestampMs < :before")
    suspend fun deleteOlderThan(before: Long)
}
