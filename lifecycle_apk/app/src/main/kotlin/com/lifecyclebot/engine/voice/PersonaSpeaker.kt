package com.lifecyclebot.engine.voice

import android.content.Context
import android.media.MediaPlayer
import com.lifecyclebot.engine.ErrorLogger
import java.io.File
import kotlin.concurrent.thread

/**
 * V5.9.495z18 — Persona-aware speakEvent entry point.
 *
 * Glue layer that ties every component together:
 *   1. PersonalityEventRouter picks the persona + model tier
 *   2. PersonalityVoiceRegistry resolves the merged VoiceProfile (defaults + overrides)
 *   3. VoiceTextProcessor sanitizes the text (emoji strip, mint redact, ticker dict, emotion tags)
 *   4. ElevenLabsApi cache-checks → calls TTS → caches MP3
 *   5. SFX cue is fired in parallel (lazy-generated on first hit)
 *   6. MediaPlayer plays the resulting MP3
 *   7. VoiceDiagnostics records everything
 *
 * Designed to be the ONLY entry point for event-driven voice. The legacy
 * VoiceManager.speak(text, persona) path still works for free-form chat.
 */
object PersonaSpeaker {

    private const val TAG = "PersonaSpeaker"
    private const val TTS_CACHE_DIR = "voice_tts_cache"
    private const val LIVE_ALERT_WORD_CAP = 20

    @Volatile private var nowPlaying: MediaPlayer? = null

    /**
     * Speak an event. Returns true if a TTS request was *issued* (cache miss
     * → API), false if cached/muted/blocked.
     *
     * @param ctx       app context
     * @param event     PersonalityEventRouter.Event identifier
     * @param dynamic   the runtime sentence (e.g. "BONK +25% on partial take")
     *                  — used when the event has no fixedText
     * @param apiKey    ElevenLabs API key (resolved by VoiceManager from prefs)
     */
    fun speakEvent(
        ctx: Context,
        event: PersonalityEventRouter.Event,
        dynamic: String? = null,
        apiKey: String,
    ): Boolean {
        // ── Mode + quiet hours gating ────────────────────────────────────
        val mode = PersonalityVoiceRegistry.voiceMode(ctx)
        if (mode == PersonalityVoiceRegistry.VoiceMode.MUTED) {
            ErrorLogger.debug(TAG, "muted — skipping ${event.key()}")
            return false
        }
        if (mode == PersonalityVoiceRegistry.VoiceMode.CRITICAL_ONLY &&
            !PersonalityEventRouter.playsInCriticalOnly(event)
        ) {
            return false
        }
        if (PersonalityVoiceRegistry.isWithinQuietHours(ctx)) {
            // Still play CRITICAL events through quiet hours.
            if (event.severity != PersonalityEventRouter.Severity.CRITICAL) {
                ErrorLogger.debug(TAG, "quiet hours — skipping ${event.key()}")
                return false
            }
        }

        // ── Persona resolution ──────────────────────────────────────────
        val fullPersonality = mode == PersonalityVoiceRegistry.VoiceMode.FULL_PERSONALITY
        val personaId = PersonalityEventRouter.pickPersonaId(event, fullPersonality)
        if (!PersonalityVoiceRegistry.isEnabled(ctx, personaId)) {
            ErrorLogger.debug(TAG, "persona $personaId disabled — skipping ${event.key()}")
            return false
        }
        val profile = PersonalityVoiceRegistry.getOrDefault(ctx, personaId)

        // ── Model tier pick ─────────────────────────────────────────────
        // Operator toggle: Flash for live, Premium for demos.
        val effectiveTier = when (event.tier) {
            PersonalityEventRouter.ModelTier.PREMIUM   ->
                if (PersonalityVoiceRegistry.usePremiumForDemos(ctx))
                    PersonalityEventRouter.ModelTier.PREMIUM
                else PersonalityEventRouter.ModelTier.FAST
            PersonalityEventRouter.ModelTier.FAST      ->
                if (PersonalityVoiceRegistry.useFlashForLive(ctx))
                    PersonalityEventRouter.ModelTier.FAST
                else PersonalityEventRouter.ModelTier.NARRATION
            PersonalityEventRouter.ModelTier.NARRATION ->
                PersonalityEventRouter.ModelTier.NARRATION
        }
        val modelId = PersonalityEventRouter.resolveModelId(profile, effectiveTier)

        // ── Voice ID resolution + fallback ─────────────────────────────
        val voiceId = profile.defaultVoiceId.takeIf { it.isNotBlank() }
            ?: profile.backupVoiceId.takeIf { it.isNotBlank() }.also {
                if (it != null) VoiceDiagnostics.recordFallbackVoice()
            }
        if (voiceId.isNullOrBlank()) {
            ErrorLogger.warn(TAG,
                "no voice_id configured for persona ${profile.personaId} — open Voice Settings to assign one")
            return false
        }

        // ── Text shaping ────────────────────────────────────────────────
        val rawText = event.fixedText ?: dynamic.orEmpty()
        if (rawText.isBlank()) return false

        val emotionTagsAllowed = modelId == PersonalityVoiceRegistry.Models.PREMIUM ||
                                  modelId == PersonalityVoiceRegistry.Models.NARRATION
        val wordCap = if (event.severity == PersonalityEventRouter.Severity.ROUTINE ||
                          event.severity == PersonalityEventRouter.Severity.NOTICE)
            LIVE_ALERT_WORD_CAP else 0  // alerts/critical untrimmed

        val processed = VoiceTextProcessor.process(
            input = rawText,
            liveAlertCap = wordCap,
            emotionTagged = emotionTagsAllowed,
            profile = profile,
        )
        if (processed.text.isBlank()) {
            VoiceDiagnostics.recordForbiddenPhraseBlock()
            ErrorLogger.warn(TAG, "text emptied after sanitization (forbidden bank?): ${rawText.take(80)}")
            return false
        }
        if (processed.wasRedacted) VoiceDiagnostics.recordRedaction()

        // ── Daily credit guard ──────────────────────────────────────────
        val dayCharsUsed = VoiceDiagnostics.estimatedCreditsToday()
        if (dayCharsUsed + processed.text.length > profile.creditBudgetPolicy.maxCharsPerDay) {
            ErrorLogger.warn(TAG,
                "daily char budget exceeded for ${profile.personaId} " +
                "($dayCharsUsed + ${processed.text.length} > ${profile.creditBudgetPolicy.maxCharsPerDay})")
            return false
        }
        if (processed.text.length > profile.creditBudgetPolicy.maxCharsPerEvent) {
            ErrorLogger.debug(TAG,
                "event char overage for ${profile.personaId}, trimming")
        }
        val finalText = processed.text.take(profile.creditBudgetPolicy.maxCharsPerEvent)

        // ── Cache check + TTS dispatch (off-thread) ─────────────────────
        thread(name = "PersonaSpeaker-${event.key()}") {
            try {
                runEvent(ctx, event, profile, voiceId, modelId, finalText, apiKey)
            } catch (e: Throwable) {
                ErrorLogger.warn(TAG, "speakEvent thread err: ${e.message}")
                VoiceDiagnostics.recordTtsError(-1, e.message ?: "thread err")
            }
        }
        return true
    }

    private fun runEvent(
        ctx: Context,
        event: PersonalityEventRouter.Event,
        profile: PersonalityVoiceRegistry.VoiceProfile,
        voiceId: String,
        modelId: String,
        text: String,
        apiKey: String,
    ) {
        VoiceDiagnostics.recordTtsRequest(profile.personaId, voiceId, modelId, event.key(), text)

        val params = ElevenLabsApi.TtsParams(
            text = text,
            voiceId = voiceId,
            modelId = modelId,
            stability = profile.defaultStability,
            similarityBoost = profile.defaultSimilarityBoost,
            style = profile.defaultStyle,
            speakerBoost = profile.speakerBoostEnabled,
            speed = profile.defaultSpeed,
        )

        val cacheDir = File(ctx.filesDir, TTS_CACHE_DIR).apply { if (!exists()) mkdirs() }
        val cacheKey = ElevenLabsApi.cacheKey(params)
        val cachedBytes = if (profile.cachePolicy != PersonalityVoiceRegistry.CachePolicy.NONE) {
            ElevenLabsApi.readCache(cacheDir, cacheKey)
        } else null

        val audioFile: File? = when {
            cachedBytes != null -> {
                VoiceDiagnostics.recordCacheHit()
                File(cacheDir, "$cacheKey.mp3").takeIf { it.exists() }
            }
            else -> {
                VoiceDiagnostics.recordCacheMiss()
                when (val r = ElevenLabsApi.tts(apiKey, params)) {
                    is ElevenLabsApi.Result.Ok  -> {
                        if (profile.cachePolicy != PersonalityVoiceRegistry.CachePolicy.NONE) {
                            ElevenLabsApi.writeCache(cacheDir, cacheKey, r.value)
                        } else {
                            // ephemeral: write to private temp file
                            File.createTempFile("eltts", ".mp3", ctx.cacheDir).apply {
                                writeBytes(r.value)
                            }
                        }
                    }
                    is ElevenLabsApi.Result.Err -> {
                        VoiceDiagnostics.recordTtsError(r.code, r.message)
                        null
                    }
                }
            }
        }

        // Fire SFX cue (parallel, fire-and-forget) — only on notable events.
        triggerSfxIfMapped(ctx, event, apiKey)

        audioFile?.let { play(it) }
    }

    private fun play(file: File) {
        if (!file.exists() || file.length() <= 0) return
        try {
            stop()
            val mp = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { runCatching { release() } }
                prepare()
                start()
            }
            nowPlaying = mp
        } catch (e: Throwable) {
            ErrorLogger.warn(TAG, "MediaPlayer error: ${e.message}")
        }
    }

    fun stop() {
        nowPlaying?.let { mp ->
            runCatching { if (mp.isPlaying) mp.stop() }
            runCatching { mp.release() }
        }
        nowPlaying = null
    }

    // ── SFX routing ──────────────────────────────────────────────────────────

    private fun triggerSfxIfMapped(
        ctx: Context,
        event: PersonalityEventRouter.Event,
        apiKey: String,
    ) {
        val sfx = sfxForEvent(event) ?: return
        thread(name = "Sfx-${sfx.name}", isDaemon = true) {
            val f = VoiceSfxLibrary.generateOrFetch(ctx, apiKey, sfx) ?: return@thread
            VoiceDiagnostics.recordSfxGenerated()
            // Play SFX a beat AFTER the TTS so it doesn't clash with the voice.
            try {
                Thread.sleep(150)
                MediaPlayer().apply {
                    setDataSource(f.absolutePath)
                    setOnCompletionListener { runCatching { release() } }
                    prepare()
                    start()
                }
            } catch (e: Throwable) {
                ErrorLogger.debug(TAG, "sfx play err: ${e.message}")
            }
        }
    }

    private fun sfxForEvent(e: PersonalityEventRouter.Event): VoiceSfxLibrary.Sfx? = when (e) {
        PersonalityEventRouter.Event.TAKE_PROFIT_HIT                 -> VoiceSfxLibrary.Sfx.TP_HIT
        PersonalityEventRouter.Event.STOP_LOSS_HIT                   -> VoiceSfxLibrary.Sfx.STOP_LOSS
        PersonalityEventRouter.Event.BUY_ATTEMPT_STARTED             -> null
        PersonalityEventRouter.Event.TARGET_TOKEN_BUY_CONFIRMED      -> VoiceSfxLibrary.Sfx.BUY_CONFIRMED
        PersonalityEventRouter.Event.SELL_CONFIRMED                  -> VoiceSfxLibrary.Sfx.SELL_CONFIRMED
        PersonalityEventRouter.Event.FDG_PASS_STRONG                 -> VoiceSfxLibrary.Sfx.FDG_PASS_STRONG
        PersonalityEventRouter.Event.USDC_BRIDGE_COMPLETED           -> VoiceSfxLibrary.Sfx.BRIDGE_OK
        PersonalityEventRouter.Event.TARGET_TOKEN_MISSING_AFTER_BRIDGE -> VoiceSfxLibrary.Sfx.BRIDGE_FAIL
        PersonalityEventRouter.Event.WALLET_HOLDS_UNMANAGED_TOKEN    -> VoiceSfxLibrary.Sfx.UNMANAGED_TOKEN
        PersonalityEventRouter.Event.SELL_FAILED                     -> VoiceSfxLibrary.Sfx.WALLET_MISMATCH
        PersonalityEventRouter.Event.RUGCHECK_REJECTION              -> VoiceSfxLibrary.Sfx.RUG_AVOIDED
        PersonalityEventRouter.Event.TOXIC_MODE_FREEZE               -> VoiceSfxLibrary.Sfx.TOXIC_FREEZE
        PersonalityEventRouter.Event.CRITICAL_FAULT                  -> VoiceSfxLibrary.Sfx.CRITICAL_FAULT
        PersonalityEventRouter.Event.TRADING_HALTED                  -> VoiceSfxLibrary.Sfx.TRADING_HALTED
        PersonalityEventRouter.Event.DAILY_SUMMARY                   -> VoiceSfxLibrary.Sfx.DAILY_CLOSE
        PersonalityEventRouter.Event.INVESTOR_DEMO                   -> VoiceSfxLibrary.Sfx.DEMO_OUTRO
        PersonalityEventRouter.Event.ACTIVE_POSITION_RESTORED        -> null
        PersonalityEventRouter.Event.TOKEN_DISCOVERED                -> null
        PersonalityEventRouter.Event.SELL_ATTEMPT_STARTED            -> null
    }
}
