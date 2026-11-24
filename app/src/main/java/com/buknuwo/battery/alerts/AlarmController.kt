package com.buknuwo.battery.alerts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import com.buknuwo.battery.alerts.model.AlertRule
import com.buknuwo.battery.alerts.model.SoundType
import com.buknuwo.battery.alerts.model.VibrationPatternKind
import com.buknuwo.battery.di.ApplicationScope
import com.buknuwo.battery.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Owns sound and vibration resources. Every start has a matching, bounded stop. */
@Singleton
class AlarmController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope
) {

    private var mediaPlayer: MediaPlayer? = null
    private var soundOwner: String? = null
    private val soundJobs = mutableMapOf<String, Job>()

    fun start(rule: AlertRule) {
        scope.launch {
            val prefs = settings.settings.first()
            if (prefs.alertsMuted) return@launch
            playSound(rule, prefs.alertMasterVolume, rule.id)
            vibrate(rule)
            soundJobs[rule.id]?.cancel()
            soundJobs[rule.id] = scope.launch {
                delay(rule.soundDurationMs.coerceAtLeast(500))
                if (soundOwner == rule.id) stopSound()
            }
        }
    }

    /** Used by the manual test screen; not connected to battery telemetry. */
    fun preview(rule: AlertRule) = start(rule.copy(id = "preview_${System.currentTimeMillis()}"))

    fun stop(ruleId: String) {
        soundJobs.remove(ruleId)?.cancel()
        if (soundOwner == ruleId) stopSound()
        stopVibration()
    }

    fun stopAll() {
        soundJobs.values.forEach { it.cancel() }
        soundJobs.clear()
        stopSound()
        stopVibration()
    }

    private fun playSound(rule: AlertRule, masterVolume: Float, owner: String) {
        if (rule.soundType == SoundType.NONE) return
        val uri = when (rule.soundType) {
            SoundType.SYSTEM_DEFAULT -> Settings.System.DEFAULT_NOTIFICATION_URI
            SoundType.SYSTEM_ALARM -> Settings.System.DEFAULT_ALARM_ALERT_URI
            SoundType.SYSTEM_NOTIFICATION -> Settings.System.DEFAULT_NOTIFICATION_URI
            SoundType.CUSTOM_URI -> rule.soundUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            SoundType.NONE -> null
        } ?: return

        stopSound()
        runCatching {
            MediaPlayer().apply {
                setDataSource(context, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = rule.repeatSoundWhileActive
                setOnPreparedListener { player ->
                    val volume = (masterVolume * rule.soundVolume).coerceIn(0f, 1f)
                    player.setVolume(volume, volume)
                    player.start()
                }
                setOnErrorListener { _, _, _ -> stopSound(); true }
                prepareAsync()
            }.also {
                mediaPlayer = it
                soundOwner = owner
            }
        }.onFailure { stopSound() }
    }

    private fun stopSound() {
        runCatching {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                reset()
                release()
            }
        }
        mediaPlayer = null
        soundOwner = null
    }

    private fun vibrate(rule: AlertRule) {
        val pattern: LongArray = when (rule.vibrationPattern) {
            VibrationPatternKind.NONE -> return
            VibrationPatternKind.SHORT -> longArrayOf(0, 180)
            VibrationPatternKind.LONG -> longArrayOf(0, 800)
            VibrationPatternKind.DOUBLE -> longArrayOf(0, 200, 180, 200)
            VibrationPatternKind.TRIPLE -> longArrayOf(0, 160, 140, 160, 140, 160)
            VibrationPatternKind.CUSTOM -> rule.customVibrationPattern?.takeIf { it.size >= 2 } ?: return
        }
        val (waveform, repeat) = if (rule.vibrationRepeat < 0) {
            pattern to -1 // -1 == vibrate indefinitely until cancelled
        } else {
            val expanded = buildList {
                repeat(rule.vibrationRepeat + 1) { addAll(pattern.toList()) }
            }.toLongArray()
            expanded to -1
        }
        vibrateWith(waveform, repeat)
    }

    private fun vibrateWith(pattern: LongArray, repeat: Int) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(VibratorManager::class.java)
                manager?.defaultVibrator?.vibrate(VibrationEffect.createWaveform(pattern, repeat))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, repeat))
            }
        }
    }

    private fun stopVibration() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator?.cancel()
            } else {
                @Suppress("DEPRECATION")
                (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.cancel()
            }
        }
    }
}
