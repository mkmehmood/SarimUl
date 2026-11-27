package com.example.util

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.example.data.model.HapticIntensity
import com.example.data.model.SoundIntensity

class FeedbackManager(private val context: Context) {
  private var hapticIntensity: HapticIntensity = HapticIntensity.MEDIUM
  private var soundIntensity: SoundIntensity = SoundIntensity.STANDARD
  private val toneLock = Any()
  private var cachedToneGenerator: ToneGenerator? = null
  private var cachedVolume: Int = -1

  fun updateSettings(haptic: HapticIntensity, sound: SoundIntensity) {
    hapticIntensity = haptic
    soundIntensity = sound
  }

  fun release() {
    synchronized(toneLock) {
      try { cachedToneGenerator?.release() } catch (_: Exception) {}
      cachedToneGenerator = null
      cachedVolume = -1
    }
  }

  fun performTapFeedback(hapticOverride: HapticIntensity? = null, soundOverride: SoundIntensity? = null) {
    triggerHaptic(hapticOverride ?: hapticIntensity)
    triggerSound(soundOverride ?: soundIntensity)
  }

  fun triggerHaptic(intensity: HapticIntensity = hapticIntensity): String {
    if (intensity == HapticIntensity.DISABLED) return "Haptic disabled"
    return try {
      val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
      } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
      }
      if (vibrator == null || !vibrator.hasVibrator()) {
        return "Vibrator hardware unavailable on this device"
      }
      val durationMs = when (intensity) {
        HapticIntensity.DISABLED -> 0L
        HapticIntensity.LIGHT -> 14L
        HapticIntensity.MEDIUM -> 28L
        HapticIntensity.STRONG -> 55L
        HapticIntensity.EXTREME -> 95L
      }
      val amplitude = when (intensity) {
        HapticIntensity.DISABLED -> 0
        HapticIntensity.LIGHT -> 70
        HapticIntensity.MEDIUM -> 150
        HapticIntensity.STRONG -> 220
        HapticIntensity.EXTREME -> 255
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
      } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(durationMs)
      }
      "Triggered ${intensity.label} (${durationMs}ms)"
    } catch (e: Exception) {
      "Haptic test skipped: ${e.message ?: "error"}"
    }
  }

  fun triggerSound(intensity: SoundIntensity = soundIntensity): String {
    if (intensity == SoundIntensity.MUTED) return "Sound muted"
    return try {
      val volume = when (intensity) {
        SoundIntensity.MUTED -> 0
        SoundIntensity.SOFT -> 25
        SoundIntensity.STANDARD -> 55
        SoundIntensity.LOUD -> 82
        SoundIntensity.EXTREME -> 100
      }
      synchronized(toneLock) {
        if (cachedToneGenerator == null || cachedVolume != volume) {
          try { cachedToneGenerator?.release() } catch (_: Exception) {}
          cachedToneGenerator = ToneGenerator(AudioManager.STREAM_SYSTEM, volume)
          cachedVolume = volume
        }
        cachedToneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 45)
      }
      "Played ${intensity.label} tone ($volume%)"
    } catch (e: Exception) {
      "Sound test skipped: ${e.message ?: "error"}"
    }
  }
}
