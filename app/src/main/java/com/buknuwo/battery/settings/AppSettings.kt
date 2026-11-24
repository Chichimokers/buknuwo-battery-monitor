package com.buknuwo.battery.settings

import com.buknuwo.battery.telemetry.MosBitMapping

data class AppSettings(
    val targetAddress: String? = null,
    val targetName: String? = null,
    val autoStartOnBoot: Boolean = false,
    val pollIntervalMs: Long = 1_500L,
    val requestTimeoutMs: Long = 2_000L,
    val reconnectEnabled: Boolean = true,
    val maxReconnectAttempts: Int = 8,
    val watchdogTimeoutMs: Long = 12_000L,
    val alertMasterVolume: Float = 0.8f,
    val alertsMuted: Boolean = false,
    val showOnLockScreen: Boolean = false,
    val chartRangeMs: Long = 15 * 60_000L,
    val historyRetentionDays: Int = 30,
    val mosMapping: MosBitMapping = MosBitMapping()
)
