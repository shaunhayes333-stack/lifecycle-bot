package com.lifecyclebot.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * SoundManager
 *
 * Plays synthesised sounds for trading events. ALL audio + haptic work
 * runs on a dedicated background HandlerThread — never the main thread.
 *
 * V5.9.675 — DOZE-WAKE BACKLOG FIX. Pipeline-Health dump showed 85 main-
 * thread samples spent inside SoundManager.makeTone / vibratePattern /
 * playNewToken$lambda when the screen woke and the bot ripped through
 * the queued PumpPortal backlog. ToneGenerator's native_setup is a heavy
 * AudioFlinger Binder IPC; instantiating a fresh one per intake on the
 * UI thread is what stalled the looper. Three fixes:
 *
 *   1. Dedicated background HandlerThread for ALL playback + vibration.
 *   2. Cached single ToneGenerator instance, lazy-built once per process.
 *   3. 1-second throttle on playNewToken() — meme intake fires several
 *      per second and the audible cue is only useful at human-rate.
 *
 * All other sounds (buy/sell/milestone/siren) are rare and meaningful;
 * they remain un-throttled but still run off-main.
 */
class SoundManager(private val ctx: Context) {

    // V5.9.675 — dedicated background thread for ALL sound work. Lower
    // priority than the main thread so a flood of intake events can never
    // starve the UI even on contended schedulers.
    private val soundThread: HandlerThread = HandlerThread(
        "AATE-Sound",
        Process.THREAD_PRIORITY_BACKGROUND
    ).also { it.start() }
    private val soundHandler: Handler = Handler(soundThread.looper)

    @Volatile private var enabled = true

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

    // V5.9.675 — cached ToneGenerator instance. ToneGenerator.native_setup()
    // is a Binder IPC into AudioFlinger; allocating a fresh one per call
    // was the actual main-thread stall fingerprint in the 7h dump.
    @Volatile private var cachedTone: ToneGenerator? = null
    private val toneLock = Any()

    private fun tone(): ToneGenerator? {
        val existing = cachedTone
        if (existing != null) return existing
        synchronized(toneLock) {
            val again = cachedTone
            if (again != null) return again
            return try {
                ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85).also { cachedTone = it }
            } catch (_: Exception) { null }
        }
    }

    // V5.9.675 — throttle for high-frequency playNewToken (1 per second max).
    @Volatile private var lastNewTokenSoundMs: Long = 0L
    private val newTokenThrottleMs: Long = 1_000L

    // Sound IDs for custom clips (loaded lazily)
    private var woohooSoundId: Int = -1
    private var awesomeSoundId: Int = -1
    private var aplusAlertSoundId: Int = -1
    private var soundsLoaded = false

    init {
        loadCustomSounds()
        latestInstance = this
    }

    /** V5.9.359 — Public hot-reload for the Persona Studio MP3 swap. */
    fun reloadCustomSounds() {
        try {
            if (woohooSoundId > 0) soundPool.unload(woohooSoundId)
            if (awesomeSoundId > 0) soundPool.unload(awesomeSoundId)
            if (aplusAlertSoundId > 0) soundPool.unload(aplusAlertSoundId)
        } catch (_: Exception) {}
        woohooSoundId = -1
        awesomeSoundId = -1
        aplusAlertSoundId = -1
        loadCustomSounds()
    }

    companion object {
        @Volatile
        private var latestInstance: SoundManager? = null

        fun reloadActiveCustomSounds() {
            try { latestInstance?.reloadCustomSounds() } catch (_: Exception) {}
        }
    }

    private fun loadCustomSounds() {
        try {
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
            soundsLoaded = false
            ErrorLogger.debug("SoundManager", "Custom sounds not available, using default tones")
        }
    }

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
        soundHandler.post {
            if (soundsLoaded && woohooSoundId > 0) {
                soundPool.play(woohooSoundId, 1.0f, 1.0f, 1, 0, 1.0f)
                vibratePattern(longArrayOf(0, 50, 30, 100))
            } else {
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
        soundHandler.post {
            if (soundsLoaded && awesomeSoundId > 0) {
                soundPool.play(awesomeSoundId, 1.0f, 1.0f, 1, 0, 1.0f)
                vibratePattern(longArrayOf(0, 100, 50, 100, 50, 100))
            } else {
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
    fun playCashRegister() {
        if (!enabled) return
        soundHandler.post {
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
        soundHandler.post {
            when {
                gainPct >= 200 -> {
                    playSequence(listOf(
                        Pair(ToneGenerator.TONE_PROP_BEEP,  100),
                        Pair(ToneGenerator.TONE_PROP_BEEP2, 100),
                        Pair(ToneGenerator.TONE_PROP_ACK,   100),
                        Pair(ToneGenerator.TONE_PROP_BEEP,  200),
                    ), delayMs = 80)
                    vibratePattern(longArrayOf(0, 80, 40, 80, 40, 200))
                }
                gainPct >= 100 -> {
                    playSequence(listOf(
                        Pair(ToneGenerator.TONE_PROP_BEEP2, 120),
                        Pair(ToneGenerator.TONE_PROP_ACK,   180),
                    ), delayMs = 80)
                    vibratePattern(longArrayOf(0, 60, 40, 120))
                }
                gainPct >= 50 -> {
                    tone()?.startTone(ToneGenerator.TONE_PROP_ACK, 150)
                    vibratePattern(longArrayOf(0, 80))
                }
            }
        }
    }

    // ── Warning siren (loss / stop loss triggered) ────────────────────
    fun playWarningSiren() {
        if (!enabled) return
        soundHandler.post {
            playSequence(listOf(
                Pair(ToneGenerator.TONE_SUP_ERROR,      200),
                Pair(ToneGenerator.TONE_PROP_NACK,      200),
                Pair(ToneGenerator.TONE_SUP_ERROR,      300),
            ), delayMs = 150)
            vibratePattern(longArrayOf(0, 100, 50, 100, 50, 200))
        }
    }

    // ── New token alert (Pump.fun launch detected) ────────────────────
    // V5.9.675 — throttled to ≤1/sec. Pump.fun fires several intakes per
    // second; queueing thousands of beeps was the visible UI-freeze symptom
    // when the screen woke after Doze. Drops are silent and intentional.
    fun playNewToken() {
        if (!enabled) return
        val now = System.currentTimeMillis()
        if (now - lastNewTokenSoundMs < newTokenThrottleMs) return
        lastNewTokenSoundMs = now
        soundHandler.post {
            tone()?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            vibratePattern(longArrayOf(0, 40, 20, 40))
        }
    }

    // ── Safety block alert - "Awesome!" ────────────────────────────────
    fun playSafetyBlock() {
        if (!enabled) return
        soundHandler.post {
            if (soundsLoaded && awesomeSoundId > 0) {
                soundPool.play(awesomeSoundId, 1.0f, 1.0f, 1, 0, 1.0f)
            } else {
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
        soundHandler.post {
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
        soundHandler.post {
            playSequence(listOf(
                Pair(ToneGenerator.TONE_SUP_ERROR,  300),
                Pair(ToneGenerator.TONE_PROP_NACK,  300),
            ), delayMs = 200)
            vibratePattern(longArrayOf(0, 200, 100, 200))
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────
    // V5.9.675 — both helpers now run inside the soundHandler.post block,
    // i.e. on the AATE-Sound thread. They no longer touch the main looper.

    private fun playSequence(tones: List<Pair<Int, Int>>, delayMs: Long) {
        var offset = 0L
        tones.forEach { (toneId, duration) ->
            soundHandler.postDelayed({
                tone()?.startTone(toneId, duration)
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
