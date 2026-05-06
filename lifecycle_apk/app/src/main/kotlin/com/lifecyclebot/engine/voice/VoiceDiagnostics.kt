package com.lifecyclebot.engine.voice

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.495z18 — Voice diagnostics counters.
 *
 * Singleton metrics surface read by the Voice Diagnostics screen.
 * All writes are atomic & lock-free — safe to call from any thread inside
 * the speech pipeline.
 */
object VoiceDiagnostics {

    @Volatile var lastPersonaId: String = "—"
    @Volatile var lastVoiceId: String = "—"
    @Volatile var lastModelId: String = "—"
    @Volatile var lastEventKey: String = "—"
    @Volatile var lastTtsRequestText: String = "—"
    @Volatile var lastTtsErrorCode: Int = 0
    @Volatile var lastTtsErrorMessage: String = ""
    @Volatile var lastSpokenAtMs: Long = 0L

    private val charactersUsedToday = AtomicLong(0)
    private val charactersUsedLifetime = AtomicLong(0)
    private val cacheHits = AtomicLong(0)
    private val cacheMisses = AtomicLong(0)
    private val sfxGenerated = AtomicInteger(0)
    private val ttsRequests = AtomicInteger(0)
    private val ttsFailures = AtomicInteger(0)
    private val fallbackVoiceUsed = AtomicInteger(0)
    private val forbiddenPhraseBlocks = AtomicInteger(0)
    private val redactionsTriggered = AtomicInteger(0)

    @Volatile private var dayStartMs: Long = 0L
    private fun rollDayIfNeeded() {
        val now = System.currentTimeMillis()
        val today = (now / 86_400_000L) * 86_400_000L
        if (today > dayStartMs) {
            dayStartMs = today
            charactersUsedToday.set(0)
        }
    }

    fun recordTtsRequest(personaId: String, voiceId: String, modelId: String, eventKey: String, text: String) {
        rollDayIfNeeded()
        lastPersonaId = personaId
        lastVoiceId = voiceId
        lastModelId = modelId
        lastEventKey = eventKey
        lastTtsRequestText = text.take(160)
        lastSpokenAtMs = System.currentTimeMillis()
        charactersUsedToday.addAndGet(text.length.toLong())
        charactersUsedLifetime.addAndGet(text.length.toLong())
        ttsRequests.incrementAndGet()
    }

    fun recordTtsError(code: Int, message: String) {
        lastTtsErrorCode = code
        lastTtsErrorMessage = message.take(240)
        ttsFailures.incrementAndGet()
    }

    fun recordCacheHit() { cacheHits.incrementAndGet() }
    fun recordCacheMiss() { cacheMisses.incrementAndGet() }
    fun recordSfxGenerated() { sfxGenerated.incrementAndGet() }
    fun recordFallbackVoice() { fallbackVoiceUsed.incrementAndGet() }
    fun recordForbiddenPhraseBlock() { forbiddenPhraseBlocks.incrementAndGet() }
    fun recordRedaction() { redactionsTriggered.incrementAndGet() }

    /** Estimated Creator credit cost. ElevenLabs Creator: ~1 credit ≈ 1 char. */
    fun estimatedCreditsToday(): Long = charactersUsedToday.get()

    fun cacheHitRate(): Double {
        val h = cacheHits.get().toDouble()
        val m = cacheMisses.get().toDouble()
        return if (h + m == 0.0) 0.0 else h / (h + m)
    }

    fun snapshot(): JSONObject = JSONObject().apply {
        put("last_persona_id", lastPersonaId)
        put("last_voice_id", lastVoiceId)
        put("last_model_id", lastModelId)
        put("last_event_key", lastEventKey)
        put("last_tts_request", lastTtsRequestText)
        put("last_tts_error_code", lastTtsErrorCode)
        put("last_tts_error_message", lastTtsErrorMessage)
        put("last_spoken_at_ms", lastSpokenAtMs)
        put("characters_used_today", charactersUsedToday.get())
        put("characters_used_lifetime", charactersUsedLifetime.get())
        put("estimated_credits_today", estimatedCreditsToday())
        put("cache_hits", cacheHits.get())
        put("cache_misses", cacheMisses.get())
        put("cache_hit_rate", cacheHitRate())
        put("sfx_generated", sfxGenerated.get())
        put("tts_requests", ttsRequests.get())
        put("tts_failures", ttsFailures.get())
        put("fallback_voice_used", fallbackVoiceUsed.get())
        put("forbidden_phrase_blocks", forbiddenPhraseBlocks.get())
        put("redactions_triggered", redactionsTriggered.get())
    }
}
