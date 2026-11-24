package com.buknuwo.battery.alerts

import com.buknuwo.battery.alerts.model.AlertRule
import com.buknuwo.battery.alerts.model.NotificationSoundType
import com.buknuwo.battery.alerts.model.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NotificationSoundTest {

    @Test
    fun customSoundsGetDedicatedChannelsAndDefaultUsesPriority() {
        val base = AlertRule(id = "rule-123", name = "Prueba", description = "", priority = Priority.CRITICAL)

        val default = notificationChannelId(base)
        assertEquals("buknuwo_alert_critical", default)

        val custom = notificationChannelId(
            base.copy(
                notificationSoundType = NotificationSoundType.CUSTOM,
                notificationSoundUri = "content://media/external/audio/42"
            )
        )
        assertEquals("buknuwo_rule_rule-123", custom)
        assertNotEquals(default, custom)

        val systemAlarm = notificationChannelId(base.copy(notificationSoundType = NotificationSoundType.SYSTEM_ALARM))
        assertEquals(custom, systemAlarm)
    }
}
