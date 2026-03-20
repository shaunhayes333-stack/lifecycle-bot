package com.lifecyclebot.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Build

/**
 * SoundManager
 *
 * Plays synthesised sounds for trading events using Android's ToneGenerator
 * and SoundPool. No audio files needed — all sounds are generated programmatically.
 *
 * PROFIT SELL  → Classic cash register: ascending ding sequence
 * LOSS/STOP    → Warning siren: descending wail
 * MILESTONE    → Escalating tones at 50%, 100%, 200% gain while holding
 * NEW TOKEN    → Short alert ping (Pump.fun WebSocket new token detected)
 * SAFETY BLOCK → Low buzzer (token blocked by safety checker)
 *
 * All sounds respect the device's volume and Do Not Disturb settings.
 * Can be muted from settings.
 */
class SoundManager(private val ctx: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var enabled = true

    // ToneGenerator for simple synthesised tones
    private fun makeTone(): ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
    } catch (_: Exception) { null }

    // Vibrator for haptic feedback
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
            ?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    fun setEnabled(v: Boolean) { enabled = v }

    // ── Cash register (profitable sell) ──────────────────────────────
    // Classic "cha-ching" — ascending notes then a long ring
    fun playCashRegister() {
        if (!enabled) return
        mainHandler.post {
            playSequence(listOf(
                Pair(ToneGenerator.TONE_PROP_BEEP,  80),
                Pair(ToneGenerator.TONE_PROP_BEEP2, 80),
                Pair(ToneGenerator.TONE_PROP_ACK,   150),
            ), delayMs = 60)
            vibratePattern(longArrayOf(0, 50, 30, 80, 30, 120))
        }
    }

    // Big win bonus sounds — escalating with profit size
    fun playMilestone(gainPct: Double) {
        if (!enabled) return
        mainHandler.post {
            when {
                gainPct >= 200 -> {
                    // 200%+ — triple ding sequence
                    playSequence(listOf(
                        Pair(ToneGenerator.TONE_PROP_BEEP,  100),
                        Pair(ToneGenerator.TONE_PROP_BEEP2, 100),
                        Pair(ToneGenerator.TONE_PROP_ACK,   100),
                        Pair(ToneGenerator.TONE_PROP_BEEP,  200),
                    ), delayMs = 80)
                    vibratePattern(longArrayOf(0, 80, 40, 80, 40, 200))
                }
                gainPct >= 100 -> {
                    // 100%+ — double ding
                    playSequence(listOf(
                        Pair(ToneGenerator.TONE_PROP_BEEP2, 120),
                        Pair(ToneGenerator.TONE_PROP_ACK,   180),
                    ), delayMs = 80)
                    vibratePattern(longArrayOf(0, 60, 40, 120))
                }
                gainPct >= 50 -> {
                    // 50%+ — single high ding
                    makeTone()?.startTone(ToneGenerator.TONE_PROP_ACK, 150)
                    vibratePattern(longArrayOf(0, 80))
                }
            }
        }
    }

    // ── Warning siren (loss / stop loss triggered) ────────────────────
    // Descending wail — two falling notes
    fun playWarningSiren() {
        if (!enabled) return
        mainHandler.post {
            playSequence(listOf(
                Pair(ToneGenerator.TONE_SUP_ERROR,      200),
                Pair(ToneGenerator.TONE_PROP_NACK,      200),
                Pair(ToneGenerator.TONE_SUP_ERROR,      300),
            ), delayMs = 150)
            vibratePattern(longArrayOf(0, 100, 50, 100, 50, 200))
        }
    }

    // ── New token alert (Pump.fun launch detected) ────────────────────
    fun playNewToken() {
        if (!enabled) return
        mainHandler.post {
            makeTone()?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            vibratePattern(longArrayOf(0, 40, 20, 40))
        }
    }

    // ── Safety block alert ────────────────────────────────────────────
    fun playSafetyBlock() {
        if (!enabled) return
        mainHandler.post {
            playSequence(listOf(
                Pair(ToneGenerator.TONE_PROP_NACK, 150),
                Pair(ToneGenerator.TONE_PROP_NACK, 150),
            ), delayMs = 120)
            vibratePattern(longArrayOf(0, 80, 40, 80))
        }
    }

    // ── Circuit breaker triggered ─────────────────────────────────────
    fun playCircuitBreaker() {
        if (!enabled) return
        mainHandler.post {
            playSequence(listOf(
                Pair(ToneGenerator.TONE_SUP_ERROR,  300),
                Pair(ToneGenerator.TONE_PROP_NACK,  300),
            ), delayMs = 200)
            vibratePattern(longArrayOf(0, 200, 100, 200))
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun playSequence(tones: List<Pair<Int, Int>>, delayMs: Long) {
        var offset = 0L
        tones.forEach { (tone, duration) ->
            mainHandler.postDelayed({
                makeTone()?.startTone(tone, duration)
            }, offset)
            offset += duration + delayMs
        }
    }

    private fun vibratePattern(pattern: LongArray) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, -1)
            }
        } catch (_: Exception) {}
    }
}
