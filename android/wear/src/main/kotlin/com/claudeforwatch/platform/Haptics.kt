package com.claudeforwatch.platform

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Wrist haptics: a strong double pulse for permission prompts, a tick for confirmations. */
object Haptics {
    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    fun attention(context: Context) {
        vibrator(context)?.takeIf { it.hasVibrator() }
            ?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 120, 80, 120), -1))
    }

    fun tick(context: Context) {
        vibrator(context)?.takeIf { it.hasVibrator() }
            ?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
    }
}
