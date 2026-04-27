package com.lifecyclebot.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
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
 * and SoundPool. Also supports custom audio clips for fun reactions!
 *
 * PROFIT SELL  → Classic cash register: ascending ding sequence
 * LOSS/STOP    → Warning siren: descending wail
 * MILESTONE    → Escalating tones at 50%, 100%, 200% gain while holding
 * NEW TOKEN    → Short alert ping (Pump.fun WebSocket new token detected)
 * SAFETY BLOCK → Low buzzer (token blocked by safety checker)
 * 
 * CUSTOM SOUNDS (add your own MP3s to res/raw/):
 * BUY TOKEN    → Homer Simpson "Woohoo!" (res/raw/woohoo.mp3)
 * BLOCK TOKEN  → "Awesome!" (res/raw/awesome.mp3)
 *
 * All sounds respect the device's volume and Do Not Disturb settings.
 * Can be muted from settings.
 */
class SoundManager(private val ctx: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var enabled = true
    
    // SoundPool for custom audio clips
    private val soundPool: SoundPool by lazy {
        SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
    }
    
    // Sound IDs for custom clips (loaded lazily)
    private var woohooSoundId: Int = -1
    private var awesomeSoundId: Int = -1
    private var aplusAlertSoundId: Int = -1
    private var soundsLoaded = false
    
    init {
        // Try to load custom sounds if they exist
        loadCustomSounds()
    }
    
    private fun loadCustomSounds() {
        try {
            // V5.9.350: Persona Studio can swap built-in sounds for user-picked
            // MP3s stored at filesDir/custom_sounds/<slot>.mp3. We check for a
            // valid user file FIRST; fall back to res/raw/<slot> if absent.
            val customDir = java.io.File(ctx.filesDir, "custom_sounds")

            fun tryLoadSlot(slot: String): Int {
                val customFile = java.io.File(customDir, "$slot.mp3")
                if (customFile.exists() && customFile.length() > 0) {
                    try {
                        val id = soundPool.load(customFile.absolutePath, 1)
                        if (id > 0) {
                            ErrorLogger.info("SoundManager", "🎵 Loaded CUSTOM $slot.mp3 (${customFile.length() / 1024}KB)")
                            return id
                        }
                    } catch (e: Exception) {
                        ErrorLogger.warn("SoundManager", "custom $slot load failed: ${e.message}")
                    }
                }
                val resId = ctx.resources.getIdentifier(slot, "raw", ctx.packageName)
                return if (resId != 0) soundPool.load(ctx, resId, 1) else -1
            }

            woohooSoundId     = tryLoadSlot("woohoo")
            awesomeSoundId    = tryLoadSlot("awesome")
            aplusAlertSoundId = tryLoadSlot("aplus_alert")

            soundsLoaded = woohooSoundId > 0 || awesomeSoundId > 0 || aplusAlertSoundId > 0
            if (soundsLoaded) {
                ErrorLogger.info("SoundManager", "Custom sounds loaded! 🎵 Woohoo!")
            }
        } catch (e: Exception) {
            // Sounds not found - will use default tones
            soundsLoaded = false
            ErrorLogger.debug("SoundManager", "Custom sounds not available, using default tones")
        }
    }

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
    
    // ── BUY TOKEN - Homer's "Woohoo!" ──────────────────────────────────
    fun playBuySound() {
        if (!enabled) return
        mainHandler.post {
            if (soundsLoaded && woohooSoundId > 0) {
                soundPool.play(woohooSoundId, 1.0f, 1.0f, 1, 0, 1.0f)
                vibratePattern(longArrayOf(0, 50, 30, 100))
            } else {
                // Fallback: happy ascending tones
                playSequence(listOf(
                    Pair(ToneGenerator.TONE_PROP_BEEP, 80),
                    Pair(ToneGenerator.TONE_PROP_BEEP2, 100),
                    Pair(ToneGenerator.TONE_PROP_ACK, 120),
                ), delayMs = 50)
                vibratePattern(longArrayOf(0, 50, 30, 100))
            }
        }
    }
    
    // ── BLOCK TOKEN - "Awesome!" ──────────────────────────────────────
    fun playBlockSound() {
        if (!enabled) return
        mainHandler.post {
            if (soundsLoaded && awesomeSoundId > 0) {
                soundPool.play(awesomeSoundId, 1.0f, 1.0f, 1, 0, 1.0f)
                vibratePattern(longArrayOf(0, 100, 50, 100, 50, 100))
            } else {
                // Fallback: descending "nope" tones
                playSequence(listOf(
                    Pair(ToneGenerator.TONE_SUP_ERROR, 150),
                    Pair(ToneGenerator.TONE_PROP_NACK, 150),
                    Pair(ToneGenerator.TONE_PROP_NACK, 200),
                ), delayMs = 100)
                vibratePattern(longArrayOf(0, 100, 50, 100, 50, 100))
            }
        }
    }

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

    // ── Safety block alert - "Awesome!" ────────────────────────────────
    fun playSafetyBlock() {
        if (!enabled) return
        mainHandler.post {
            if (soundsLoaded && awesomeSoundId > 0) {
                // 🎵 "Awesome!"
                soundPool.play(awesomeSoundId, 1.0f, 1.0f, 1, 0, 1.0f)
            } else {
                // Fallback tones
                playSequence(listOf(
                    Pair(ToneGenerator.TONE_PROP_NACK, 150),
                    Pair(ToneGenerator.TONE_PROP_NACK, 150),
                ), delayMs = 120)
            }
            vibratePattern(longArrayOf(0, 80, 40, 80))
        }
    }

    // ── A+ SETUP ALERT ────────────────────────────────────────────────
    fun playAplusAlert() {
        if (!enabled) return
        mainHandler.post {
            if (soundsLoaded && aplusAlertSoundId > 0) {
                soundPool.play(aplusAlertSoundId, 1.0f, 1.0f, 1, 0, 1.0f)
            } else if (soundsLoaded && woohooSoundId > 0) {
                soundPool.play(woohooSoundId, 1.0f, 1.0f, 1, 0, 1.0f)
            } else {
                playSequence(listOf(
                    Pair(ToneGenerator.TONE_PROP_BEEP, 100),
                    Pair(ToneGenerator.TONE_PROP_BEEP2, 100),
                    Pair(ToneGenerator.TONE_PROP_ACK, 150),
                ), delayMs = 60)
            }
            vibratePattern(longArrayOf(0, 100, 50, 100, 50, 200))
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
