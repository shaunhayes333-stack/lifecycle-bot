package com.lifecyclebot.engine

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayList
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object VoiceManager {

    private const val TAG = "VoiceManager"
    private const val PREFS = "voice_prefs_v3"

    private const val KEY_MUTED = "muted"

    private const val KEY_REMOTE_URL = "tts_api_url"
    private const val KEY_REMOTE_KEY = "tts_api_key"
    private const val KEY_REMOTE_MODEL = "tts_model_default"

    // V5.9.361 — ElevenLabs second backend for richer character voices.
    private const val KEY_BACKEND          = "tts_backend"          // "auto" | "openai" | "elevenlabs"
    private const val KEY_ELEVEN_KEY       = "tts_eleven_key"
    private const val KEY_ELEVEN_MODEL     = "tts_eleven_model"
    private const val DEFAULT_ELEVEN_MODEL = "eleven_turbo_v2_5"
    // One-time seed of the user's ElevenLabs key on first boot. Stored in
    // SharedPrefs (device-local, never round-tripped to the repo). User can
    // rotate via Settings sheet at any time.
    private const val ELEVEN_SEED_KEY = "sk_2468d0f6f4ba96ef5877a9db238f3b277e3899905b27fe90"
    private const val ELEVEN_SEED_FLAG = "tts_eleven_seeded_v361"
    private const val DEFAULT_BACKEND_AUTO = "auto"

    private const val KEY_LOCAL_ENABLED = "tts_local_enabled"
    private const val KEY_LOCAL_ENGINE = "tts_local_engine"
    private const val KEY_LOCAL_MODEL_DIR = "tts_local_model_dir"

    private const val DEFAULT_REMOTE_URL = "https://integrations.emergentagent.com/llm/audio/speech"
    private const val DEFAULT_REMOTE_MODEL = "tts-1-hd"   // V5.9.88 — proxy only supports tts-1 and tts-1-hd
    private const val DEFAULT_LOCAL_ENGINE = "sherpa"

    private var appCtx: Context? = null

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var ttsReady = false

    private val ttsInitLock = Any()

    @Volatile
    private var mediaPlayer: MediaPlayer? = null

    private val generation = AtomicLong(0L)

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    sealed class VoiceSpec {
        data class Android(
            val localeTag: String,
            val pitch: Float = 1.0f,
            val speed: Float = 1.0f,
            val preferredHints: List<String> = emptyList()
        ) : VoiceSpec()

        data class Remote(
            val voice: String,
            val model: String = DEFAULT_REMOTE_MODEL,
            val speed: Double = 1.0,
            val fallbackLocaleTag: String = "en-US",
            /**
             * V5.9.87: gpt-4o-mini-tts style-instruction prompt. Tells the
             * model *how* to deliver the line (accent, tempo, emotion, vocal
             * timbre) — this is what actually makes personas sound distinct,
             * far more than voice-id or speed alone.
             */
            val instructions: String = ""
        ) : VoiceSpec()

        data class LocalSherpa(
            val modelDir: String,
            val voiceName: String = "",
            val speakerId: Int = 0,
            val speed: Float = 1.0f,
            val fallbackLocaleTag: String = "en-US"
        ) : VoiceSpec()

        /**
         * V5.9.361 — ElevenLabs character-voice backend. Each persona maps to
         * a stable voice id. We use the cheapest "turbo" model for free-tier
         * friendliness; voice_settings let us dial the persona delivery.
         */
        data class ElevenLabs(
            val voiceId: String,
            val modelId: String = DEFAULT_ELEVEN_MODEL,
            val stability: Double = 0.45,
            val similarityBoost: Double = 0.85,
            val style: Double = 0.30,
            val speakerBoost: Boolean = true,
            val fallbackLocaleTag: String = "en-US",
        ) : VoiceSpec()
    }

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        ErrorLogger.info(TAG, "🔊 VoiceManager init (muted=${isMuted(ctx)})")
        // V5.9.120: one-shot purge of any stale per-persona override keys
        // from earlier versions (V5.9.87 tried `fable`/`echo`, V5.9.88 tried
        // `gpt-4o-mini-tts` model, etc.). Those keys silently win over the
        // current correct defaults and cause 'Cleetus sounds female' bugs.
        // The purge is idempotent and runs once per app version.
        try { purgeStaleVoiceOverridesOnce(ctx) } catch (_: Throwable) {}
        // V5.9.361 — one-time seed of the ElevenLabs API key into SharedPrefs.
        try { seedElevenLabsKeyOnce(ctx) } catch (_: Throwable) {}
    }

    /** V5.9.361 — Mirror the user's Gemini/Universal LLM key into the
     *  remote-TTS key slot if the latter is blank. The default remote URL
     *  already points at the Emergent universal-key proxy; once the same
     *  key is present in the TTS slot, all the existing per-persona OpenAI
     *  voice mappings (Cleetus → onyx + Florida-redneck instructions etc.)
     *  finally take effect. Without this mirror the bot was silently
     *  falling back to Android TTS — a single default female voice — which
     *  is why every persona sounded the same. */
    fun ensureRemoteKeyMirroredFromGemini(ctx: Context, geminiKey: String) {
        if (geminiKey.isBlank()) return
        val prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_REMOTE_KEY, "")?.trim().orEmpty()
        if (existing.isBlank()) {
            prefs.edit().putString(KEY_REMOTE_KEY, geminiKey).apply()
            ErrorLogger.info(TAG, "🔊 Mirrored LLM key into TTS slot — per-persona OpenAI voices now active.")
        }
    }

    private fun seedElevenLabsKeyOnce(ctx: Context) {
        val prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(ELEVEN_SEED_FLAG, false)) return
        val current = prefs.getString(KEY_ELEVEN_KEY, "")?.trim().orEmpty()
        if (current.isBlank()) {
            prefs.edit()
                .putString(KEY_ELEVEN_KEY, ELEVEN_SEED_KEY)
                .putBoolean(ELEVEN_SEED_FLAG, true)
                .apply()
            ErrorLogger.info(TAG, "🔊 Seeded ElevenLabs key (one-time). Rotate via Settings sheet.")
        } else {
            prefs.edit().putBoolean(ELEVEN_SEED_FLAG, true).apply()
        }
    }

    /** V5.9.361 — public ElevenLabs key accessor (Settings UI uses these). */
    fun getElevenLabsKey(ctx: Context): String =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ELEVEN_KEY, "")?.trim().orEmpty()

    fun setElevenLabsKey(ctx: Context, key: String) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ELEVEN_KEY, key.trim()).apply()
    }

    /** Backend selector: "auto" | "openai" | "elevenlabs". */
    fun setBackend(ctx: Context, backend: String) {
        val v = backend.trim().lowercase().takeIf { it in setOf("auto", "openai", "elevenlabs") } ?: "auto"
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_BACKEND, v).apply()
    }

    fun getBackend(ctx: Context): String =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BACKEND, DEFAULT_BACKEND_AUTO)?.trim()?.lowercase() ?: DEFAULT_BACKEND_AUTO


    private fun purgeStaleVoiceOverridesOnce(ctx: Context) {
        val prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val purgeKey = "voice_overrides_purged_v5_9_120"
        if (prefs.getBoolean(purgeKey, false)) return

        val editor = prefs.edit()
        // Personalities that MUST be male — nuke any stale per-persona voice
        // override so the current male defaults in
        // defaultRemoteVoiceForPersona() take effect.
        val maleOnly = listOf(
            "aate", "irishman", "batman", "gentleman", "frasier",
            "wallstreet", "cockney", "cowboy", "hunter_s",
            "narrator", "pirate", "cleetus", "peter"
        )
        var purged = 0
        maleOnly.forEach { pid ->
            val key = "tts_voice_$pid"
            if (prefs.contains(key)) {
                val stale = prefs.getString(key, null)
                // Known-bad feminine-leaning voices we've shipped by accident
                if (stale in setOf("nova", "shimmer", "fable", "echo", "coral")) {
                    editor.remove(key)
                    purged++
                }
            }
        }
        // Also purge any stale model pointer that breaks the proxy
        val model = prefs.getString(KEY_REMOTE_MODEL, null)
        if (model != null && model !in ALLOWED_TTS_MODELS) {
            editor.putString(KEY_REMOTE_MODEL, DEFAULT_REMOTE_MODEL)
            purged++
        }
        editor.putBoolean(purgeKey, true).apply()
        if (purged > 0) {
            ErrorLogger.info(TAG, "🔊 Purged $purged stale voice override(s) — male personas will now use correct OpenAI voices")
        }
    }

    fun isMuted(ctx: Context): Boolean {
        val c = appCtx ?: ctx.applicationContext.also { appCtx = it }
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_MUTED, true)
    }

    fun toggleMute(ctx: Context): Boolean {
        val c = appCtx ?: ctx.applicationContext.also { appCtx = it }
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val newMuted = !prefs.getBoolean(KEY_MUTED, true)
        prefs.edit().putBoolean(KEY_MUTED, newMuted).apply()

        if (newMuted) {
            stop()
        }

        ErrorLogger.info(TAG, "🔊 Mute toggled → muted=$newMuted")
        return newMuted
    }

    fun stop() {
        generation.incrementAndGet()

        try {
            tts?.stop()
        } catch (_: Throwable) {
        }

        try {
            mediaPlayer?.stop()
        } catch (_: Throwable) {
        }

        try {
            mediaPlayer?.reset()
        } catch (_: Throwable) {
        }

        try {
            mediaPlayer?.release()
        } catch (_: Throwable) {
        }

        mediaPlayer = null
    }

    fun shutdown() {
        stop()

        try {
            tts?.shutdown()
        } catch (_: Throwable) {
        }

        tts = null
        ttsReady = false
    }

    fun speak(text: String, persona: Personalities.Persona?) {
        val ctx = appCtx ?: return
        if (isMuted(ctx)) return
        if (text.isBlank()) return

        val clean = cleanForSpeech(text)
        if (clean.isBlank()) return

        val token = generation.incrementAndGet()
        stopMediaOnly()

        val personaId = persona?.id ?: "aate"

        // V5.9.120: if the user has dropped a signature-phrase MP3/WAV into
        // assets/phrases/{personaId}/{slug}.{mp3|wav}, AND the text we're
        // about to speak is a close match for that phrase (exact/contains),
        // play the recording directly. Bypasses TTS entirely for iconic
        // one-liners like "Hell yeah brother" / "Arrr matey" / "Hehehehehe".
        // If no asset matches, fall through to normal TTS.
        if (maybePlaySignaturePhrase(ctx, personaId, clean, token)) return

        val spec = resolveVoiceSpec(ctx, personaId)

        when (spec) {
            is VoiceSpec.LocalSherpa -> speakLocalSherpa(clean, spec, token)
            is VoiceSpec.Remote -> speakRemote(clean, spec, token)
            is VoiceSpec.ElevenLabs -> speakElevenLabs(clean, spec, token)
            is VoiceSpec.Android -> speakAndroid(clean, spec, token)
        }
    }

    /**
     * V5.9.120 — signature-phrase asset pipeline.
     *
     * Checks assets/phrases/{personaId}/ for files whose filename slug
     * matches (stripped, lowercased) the start or full body of `text`. If
     * a match exists AND the text is short enough to be just the phrase
     * (≤ 60 chars), copy the asset to cache and play it. Returns true if
     * the phrase played.
     *
     * The folder structure is user-controlled — download any free WAV/MP3
     * from the internet, name it e.g. "hell_yeah_brother.mp3", drop it in
     * assets/phrases/cleetus/, and it plays whenever the persona says
     * "hell yeah brother".
     */
    private fun maybePlaySignaturePhrase(
        ctx: Context,
        personaId: String,
        text: String,
        token: Long
    ): Boolean {
        if (text.length > 80) return false
        val norm = text.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (norm.isBlank()) return false

        val folder = "phrases/$personaId"
        val names = try {
            ctx.assets.list(folder)?.toList() ?: emptyList()
        } catch (_: Throwable) { return false }
        if (names.isEmpty()) return false

        val match = names.firstOrNull { filename ->
            val slug = filename.substringBeforeLast('.').lowercase(Locale.US)
                .replace('_', ' ')
                .replace('-', ' ')
                .replace(Regex("\\s+"), " ")
                .trim()
            slug.isNotBlank() && (norm == slug || norm.startsWith(slug) || norm.contains(slug))
        } ?: return false

        return try {
            val outFile = File(ctx.cacheDir, "tts_phrase_${token}_${match}")
            ctx.assets.open("$folder/$match").use { input ->
                FileOutputStream(outFile).use { out -> input.copyTo(out) }
            }
            ErrorLogger.info(TAG, "🔊 Playing signature phrase asset: $folder/$match")
            playAudioFile(outFile, token)
            true
        } catch (t: Throwable) {
            ErrorLogger.warn(TAG, "Signature phrase play failed ($match): ${t.message}")
            false
        }
    }

    fun previewPersona(
        personaId: String,
        sampleText: String = "Voice check. This is how I currently sound."
    ) {
        val ctx = appCtx ?: return
        if (isMuted(ctx)) return

        val clean = cleanForSpeech(sampleText)
        if (clean.isBlank()) return

        val token = generation.incrementAndGet()
        stopMediaOnly()

        when (val spec = resolveVoiceSpec(ctx, personaId)) {
            is VoiceSpec.LocalSherpa -> speakLocalSherpa(clean, spec, token)
            is VoiceSpec.Remote -> speakRemote(clean, spec, token)
            is VoiceSpec.ElevenLabs -> speakElevenLabs(clean, spec, token)
            is VoiceSpec.Android -> speakAndroid(clean, spec, token)
        }
    }

    private fun resolveVoiceSpec(ctx: Context, personaId: String): VoiceSpec {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val overrideLocale = prefs.getString("tts_locale_$personaId", null)?.trim().orEmpty()
        val overrideRemoteVoice = prefs.getString("tts_voice_$personaId", null)?.trim().orEmpty()

        val localEnabled = prefs.getBoolean(KEY_LOCAL_ENABLED, false)
        val localEngine = prefs.getString(KEY_LOCAL_ENGINE, DEFAULT_LOCAL_ENGINE)?.trim().orEmpty()
        val localModelDir = prefs.getString(KEY_LOCAL_MODEL_DIR, "")?.trim().orEmpty()
        val localVoice = prefs.getString("tts_local_voice_$personaId", null)?.trim().orEmpty()
        val localSpeakerId = prefs.getInt("tts_local_speaker_$personaId", defaultLocalSpeakerForPersona(personaId))
        val localSpeed = prefs.getFloat("tts_local_speed_$personaId", defaultLocalSpeedForPersona(personaId))

        if (
            localEnabled &&
            localEngine.equals("sherpa", true) &&
            localModelDir.isNotBlank() &&
            LocalSherpaBridge.isAvailable(ctx, localModelDir)
        ) {
            return VoiceSpec.LocalSherpa(
                modelDir = localModelDir,
                voiceName = if (localVoice.isNotBlank()) localVoice else defaultLocalVoiceForPersona(personaId),
                speakerId = localSpeakerId,
                speed = localSpeed.coerceIn(0.6f, 1.4f),
                fallbackLocaleTag = if (overrideLocale.isNotBlank()) overrideLocale else defaultLocaleForPersona(personaId)
            )
        }

        val remoteConfigured = isRemoteConfigured(ctx)
        // V5.9.361 — backend routing:
        //   auto       → ElevenLabs if key present, else OpenAI proxy.
        //   elevenlabs → force ElevenLabs (fallback to OpenAI/Android if no key).
        //   openai     → force OpenAI proxy.
        val backend = prefs.getString(KEY_BACKEND, DEFAULT_BACKEND_AUTO)?.trim()?.lowercase() ?: DEFAULT_BACKEND_AUTO
        val elevenKey = prefs.getString(KEY_ELEVEN_KEY, "")?.trim().orEmpty()
        val useEleven = elevenKey.isNotBlank() && (backend == "elevenlabs" || backend == DEFAULT_BACKEND_AUTO)
        if (useEleven) {
            return VoiceSpec.ElevenLabs(
                voiceId = defaultElevenLabsVoiceForPersona(personaId),
                modelId = prefs.getString(KEY_ELEVEN_MODEL, DEFAULT_ELEVEN_MODEL)?.trim()?.ifBlank { DEFAULT_ELEVEN_MODEL } ?: DEFAULT_ELEVEN_MODEL,
                stability = defaultElevenStabilityForPersona(personaId),
                similarityBoost = 0.85,
                style = defaultElevenStyleForPersona(personaId),
                speakerBoost = true,
                fallbackLocaleTag = if (overrideLocale.isNotBlank()) overrideLocale else defaultLocaleForPersona(personaId),
            )
        }

        if (remoteConfigured) {
            val remoteVoice = if (overrideRemoteVoice.isNotBlank()) {
                overrideRemoteVoice
            } else {
                defaultRemoteVoiceForPersona(personaId)
            }

            return VoiceSpec.Remote(
                voice = remoteVoice,
                model = sanitizedTtsModel(prefs.getString(KEY_REMOTE_MODEL, DEFAULT_REMOTE_MODEL)),
                speed = defaultRemoteSpeedForPersona(personaId),
                fallbackLocaleTag = if (overrideLocale.isNotBlank()) overrideLocale else defaultLocaleForPersona(personaId),
                instructions = defaultRemoteInstructionsForPersona(personaId)
            )
        }

        return defaultAndroidVoiceSpec(
            personaId = personaId,
            overrideLocale = overrideLocale
        )
    }

    private fun isRemoteConfigured(ctx: Context): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val apiUrl = prefs.getString(KEY_REMOTE_URL, DEFAULT_REMOTE_URL)?.trim().orEmpty()
        val apiKey = prefs.getString(KEY_REMOTE_KEY, "")?.trim().orEmpty()
        return apiUrl.isNotBlank() && apiKey.isNotBlank()
    }

    private fun defaultLocaleForPersona(personaId: String): String {
        return when (personaId) {
            "irishman" -> "en-IE"
            "gentleman", "cockney", "pirate" -> "en-GB"
            "waifu" -> "en-US"
            "cleetus", "peter" -> "en-US"
            else -> "en-US"
        }
    }

    private val ALLOWED_TTS_MODELS = setOf("tts-1", "tts-1-hd")

    /**
     * V5.9.88: Emergent proxy rejects anything outside `tts-1`/`tts-1-hd`.
     * If a stale SharedPreferences entry still points to `gpt-4o-mini-tts`
     * (from V5.9.87), coerce it back to the default so requests stop silently
     * 4xx-ing and falling through to Android TTS.
     */
    private fun sanitizedTtsModel(stored: String?): String {
        val s = stored?.trim().orEmpty()
        return if (s in ALLOWED_TTS_MODELS) s else DEFAULT_REMOTE_MODEL
    }


    private fun defaultRemoteVoiceForPersona(personaId: String): String {
        // V5.9.89 — Restricted to the voices that are UNAMBIGUOUSLY MALE in
        // the OpenAI TTS lineup. Previous map used `fable` and `echo` — both
        // are androgynous-to-feminine in practice, which is why male
        // personas like irishman / gentleman kept coming out female.
        //
        // Safe male voices: onyx (deep), ash (articulate), alloy (neutral).
        // Distinct personas same-voice are differentiated by speed (0.72..1.22).
        // `sage` stays only on zen (genuinely neutral contemplative voice).
        // `nova` stays only on waifu (the single female persona).
        return when (personaId) {
            "aate"       -> "alloy"   // neutral male default
            "irishman"   -> "alloy"   // lively neutral male (accent via speed lift)
            "batman"     -> "onyx"    // deepest gravelly male
            "gentleman"  -> "onyx"    // deep British-leaning male
            "frasier"    -> "ash"     // articulate educated male
            "wallstreet" -> "ash"     // articulate fast male
            "zen"        -> "sage"    // neutral contemplative
            "cockney"    -> "alloy"   // neutral male, fast
            "cowboy"     -> "onyx"    // deep Texas drawl male
            "hunter_s"   -> "ash"     // articulate manic male
            "narrator"   -> "onyx"    // deep documentary baritone
            "pirate"     -> "onyx"    // deep authoritative male
            "waifu"      -> "nova"    // bright female (the ONLY female)
            "cleetus"    -> "onyx"    // V5.9.120: was `ash` (androgynous in practice) — onyx is unambiguously deep-male for Florida redneck
            "peter"      -> "ash"     // cartoon-dad articulate male
            else         -> "alloy"
        }
    }

    private fun defaultRemoteSpeedForPersona(personaId: String): Double {
        // V5.9.89 — speed is the main differentiator since we collapsed to
        // 3 male voices. Wider spread (0.72..1.22) than before.
        return when (personaId) {
            "aate"       -> 1.00
            "irishman"   -> 1.12   // lively lilt
            "batman"     -> 0.72   // slowest, brooding
            "gentleman"  -> 0.90   // measured
            "frasier"    -> 1.00
            "wallstreet" -> 1.22   // fastest
            "zen"        -> 0.78
            "cockney"    -> 1.15   // fast patter
            "cowboy"     -> 0.80   // slow drawl
            "hunter_s"   -> 1.20   // manic
            "narrator"   -> 0.88
            "pirate"     -> 0.98
            "waifu"      -> 1.14
            "cleetus"    -> 1.12
            "peter"      -> 1.06
            else         -> 1.0
        }
    }

    /**
     * V5.9.361 — ElevenLabs stock-voice mapping per persona. These are
     * pre-licensed voices on every ElevenLabs account; no cloning, no
     * impersonation. Picked to match the *vibe* of each persona archetype.
     * User can override per-persona via SharedPrefs key tts_eleven_voice_<pid>.
     */
    private fun defaultElevenLabsVoiceForPersona(personaId: String): String {
        val ctx = appCtx
        if (ctx != null) {
            val override = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("tts_eleven_voice_$personaId", null)?.trim().orEmpty()
            if (override.isNotBlank()) return override
        }
        // Stock ElevenLabs voice IDs (Adam/Antoni/Bella/etc) — stable, on every account.
        return when (personaId) {
            "aate"       -> "pNInz6obpgDQGcFmaJgB" // Adam — clean, deep American male
            "irishman"   -> "D38z5RcWu1voky8WS1ja" // Fin  — Irish sailor cadence
            "batman"     -> "2EiwWnXFnvU5JabPnv8n" // Clyde — gravelly, war-veteran rasp
            "gentleman"  -> "onwK4e9ZLuTAKqWW03F9" // Daniel — polished British news anchor
            "frasier"    -> "ZQe5CZNOzWyzPSCn5a3c" // James — older British, articulate
            "wallstreet" -> "TxGEqnHWrfWFTfGW9XjX" // Josh — energetic, fast American male
            "zen"        -> "GBv7mTt0atIp3Br8iCZE" // Thomas — calm meditation male
            "cockney"    -> "CYw3kZ02Hs0563khs1Fj" // Dave  — British conversational
            "cowboy"     -> "29vD33N1CtxCmqQRPOHJ" // Drew  — well-rounded American narrator
            "hunter_s"   -> "yoZ06aMxZJJ28mfd3POQ" // Sam   — raspy American male
            "narrator"   -> "29vD33N1CtxCmqQRPOHJ" // Drew  — narrator default
            "pirate"     -> "ODq5zmih8GrVes37Dizd" // Patrick — old shouty male, perfect pirate
            "waifu"      -> "jsCqWAovK2LkecY7zXl4" // Freya — expressive young female
            "cleetus"    -> "yoZ06aMxZJJ28mfd3POQ" // Sam   — raspy southern-leaning American — best Cleetus archetype available without impersonation
            "peter"      -> "TxGEqnHWrfWFTfGW9XjX" // Josh  — energetic dad-energy male
            else         -> "pNInz6obpgDQGcFmaJgB" // Adam fallback
        }
    }

    private fun defaultElevenStabilityForPersona(personaId: String): Double = when (personaId) {
        "batman", "zen", "narrator", "gentleman" -> 0.65   // calmer, less variation
        "wallstreet", "hunter_s", "cleetus", "peter", "cockney" -> 0.30  // more variation, dynamic
        else -> 0.45
    }

    private fun defaultElevenStyleForPersona(personaId: String): Double = when (personaId) {
        "cleetus", "peter", "wallstreet", "hunter_s", "pirate" -> 0.55  // exaggerated character delivery
        "batman", "zen", "gentleman", "narrator" -> 0.15                 // measured
        else -> 0.30
    }

    private fun defaultRemoteInstructionsForPersona(personaId: String): String {
        return when (personaId) {
            "aate" -> """
                Voice: neutral, slightly synthetic, warm but composed. Mid-pitch American male.
                Cadence: even, deliberate, confident.
                Delivery: calm professional operator. Minimal emotion. No theatrics.
            """.trimIndent()

            "irishman" -> """
                Accent: warm Dublin / west-coast Irish lilt — lyrical, playful, musical
                rise-and-fall on every sentence. Pronounce 'th' softly, roll some Rs.
                Emotion: cheeky, optimistic, a bit tipsy on luck. Smile in the voice.
                Signature quirks: occasional laugh-in-throat, drawn-out vowels on
                'aaaay' and 'noooow'. Never shout; stay warm and bright.
            """.trimIndent()

            "batman" -> """
                Voice: extremely deep, gravelly, barely-above-a-whisper baritone. Adult
                male, late 30s. Think brooding vigilante growl — chest-voice, lots of
                vocal fry. Each sentence ends flat or descending, never upbeat.
                Cadence: slow, clipped, dangerous. Long silences between thoughts.
                Emotion: controlled menace with a crack of grief underneath.
            """.trimIndent()

            "gentleman" -> """
                Accent: upper-class Received Pronunciation British male, Oxford/Cambridge
                vintage. Precise consonants, rounded vowels, slightly nasal.
                Cadence: measured, unhurried, like narrating a black-tie documentary.
                Emotion: amused, dry, faintly condescending but never rude. Occasional
                understated chuckle. Think David Attenborough crossed with Jeeves.
            """.trimIndent()

            "frasier" -> """
                Voice: educated East-coast American male, mid-40s. Warm baritone with a
                theatrical, slightly pompous lilt. Over-enunciate multisyllabic words.
                Cadence: professorial, stops for self-congratulation, occasional sigh.
                Emotion: affectionate condescension and love of wine. Think Harvard-
                trained psychiatrist who'd rather be at the opera.
            """.trimIndent()

            "wallstreet" -> """
                Voice: aggressive New York trading floor male, late 30s. Fast, sharp,
                slight Long Island edge. Clipped consonants, barked commands.
                Cadence: machine-gun tempo, interrupts himself, swallows word endings.
                Emotion: caffeine-wired, predatory, laughing at his own bets. Never
                calm for more than a sentence. Some barely-contained swagger.
            """.trimIndent()

            "zen" -> """
                Voice: soft neutral-male guru, ageless. Breathy, low volume, almost
                whispered. Every sentence ends on a gentle downward note.
                Cadence: very slow, lots of pauses, as if chosen from a still lake.
                Emotion: serene, patient, amused at the absurdity of markets. Speak
                as if meditating out loud.
            """.trimIndent()

            "cockney" -> """
                Accent: working-class East London / Cockney male, 30s. Dropped Hs,
                glottal stops on Ts ('bu'ah', 'righ'), elongated 'oi' diphthongs,
                cheeky rising terminals.
                Cadence: fast, patter-style, lots of aside jokes.
                Emotion: loud, friendly, bit of a wide-boy. Think pub regular who
                always has a story. Laugh into the mic occasionally.
            """.trimIndent()

            "cowboy" -> """
                Accent: deep Texas / West Texas drawl. Male, 50s. Slow vowels, dropped
                Gs ('ridin'', 'fixin''), chesty resonance, gravel at the bottom.
                Cadence: unhurried, gunslinger pace. Long breath between phrases.
                Emotion: laconic, wry, seen it all. Faint dry chuckle on close calls.
                Think Sam Elliott reading the market like a trail report.
            """.trimIndent()

            "hunter_s" -> """
                Voice: gonzo journalist male, 40s, American. Manic, rapid-fire, slightly
                unhinged but still literate. Lots of emphasis spikes mid-sentence.
                Cadence: machine-gun bursts separated by sudden theatrical pauses.
                Emotion: paranoid, amphetamine-fueled, defiant, funny. Like reading
                fear-and-loathing out loud at 2 AM. Growl the consonants.
            """.trimIndent()

            "narrator" -> """
                Voice: iconic deep warm American baritone, late 60s. Think master
                documentary narrator. Velvety chest resonance, perfect diction.
                Cadence: slow, honeyed, each sentence arcs and lands softly.
                Emotion: knowing, kind, mildly amused. Every pause is deliberate.
                Do NOT impersonate any specific real person — channel the archetype.
            """.trimIndent()

            "pirate" -> """
                Voice: weather-beaten British pirate captain male, 40s. Gruff, theatrical,
                salty. Slightly drunk. Rolled Rs on 'arrr' and 'rrready'.
                Cadence: big swings, shouty highs on approvals, low growls on threats.
                Emotion: swashbuckling bravado, a fondness for chaos, genuine warmth
                toward loyal crew. Laugh out loud at danger.
            """.trimIndent()

            "waifu" -> """
                Voice: cute young-adult female, bright high-pitched Japanese-English
                anime-heroine energy. Breathy smile on every phrase.
                Cadence: fast, excitable, lots of rising intonation, giggles mid-line.
                Emotion: clingy-affectionate girlfriend — hearts in the voice. Soft
                pouts on disappointments, squeals on wins. Call the user 'senpai'
                and 'darling' whenever natural. Never creepy, always adoring.
            """.trimIndent()

            "cleetus" -> """
                Accent: loud Florida / Southern redneck American male, 30s, motorsport
                YouTuber energy. Strong Southern drawl with occasional twang breaks.
                Cadence: bursty, shouty highs, dragged vowels on 'yeaaaah' and 'rooowdy'.
                Emotion: pure race-day hype — permanently excited, always about to
                laugh. Treat breakdowns like punchlines. Big grin in the voice.
                Do NOT impersonate any specific real creator — channel the archetype.
            """.trimIndent()

            "peter" -> """
                Voice: goofy American dad male, 30s, nasal Rhode-Island Boston twang.
                Think cartoon-husband-voice, slightly high-pitched for a man,
                chuckling through half his sentences.
                Cadence: meandering, tangents mid-thought, 'heheheheh' laugh intro
                every few lines. Over-emphasis on stupid words.
                Emotion: cheerful idiot, loving, short attention span. Channel the
                archetype — do NOT impersonate any specific real creator.
            """.trimIndent()

            else -> ""
        }
    }

    private fun defaultLocalVoiceForPersona(personaId: String): String {
        return when (personaId) {
            "narrator" -> "narrator"
            "zen" -> "soft"
            "wallstreet" -> "fast"
            "batman" -> "deep"
            "waifu" -> "soft"
            "cleetus" -> "fast"
            "peter" -> "default"
            else -> "default"
        }
    }

    private fun defaultLocalSpeakerForPersona(personaId: String): Int {
        return when (personaId) {
            "narrator" -> 0
            "zen" -> 1
            "wallstreet" -> 2
            "batman" -> 3
            "waifu" -> 4
            "cleetus" -> 5
            "peter" -> 6
            else -> 0
        }
    }

    private fun defaultLocalSpeedForPersona(personaId: String): Float {
        return when (personaId) {
            "wallstreet" -> 1.08f
            "zen" -> 0.84f
            "narrator" -> 0.90f
            "batman" -> 0.86f
            "waifu" -> 1.06f
            "cleetus" -> 0.96f
            "peter" -> 1.04f
            else -> 1.0f
        }
    }

    private fun defaultAndroidVoiceSpec(
        personaId: String,
        overrideLocale: String
    ): VoiceSpec.Android {
        // V5.9.87: Android-TTS fallback mapping. Default to *male* voice
        // preferences; waifu is the single female. Pitch/speed are tuned
        // to roughly match the OpenAI gpt-4o-mini-tts persona profiles.
        return when (personaId) {
            "irishman" -> VoiceSpec.Android(
                localeTag = if (overrideLocale.isNotBlank()) overrideLocale else "en-IE",
                pitch = 1.02f,
                speed = 1.05f,
                preferredHints = listOf("male", "ireland", "irish", "en-ie")
            )

            "gentleman" -> VoiceSpec.Android(
                localeTag = if (overrideLocale.isNotBlank()) overrideLocale else "en-GB",
                pitch = 0.94f,
                speed = 0.94f,
                preferredHints = listOf("male", "gb", "uk", "british", "en-gb")
            )

            "cockney" -> VoiceSpec.Android(
                localeTag = if (overrideLocale.isNotBlank()) overrideLocale else "en-GB",
                pitch = 1.02f,
                speed = 1.10f,
                preferredHints = listOf("male", "gb", "uk", "british", "en-gb")
            )

            "batman" -> VoiceSpec.Android(
                localeTag = if (overrideLocale.isNotBlank()) overrideLocale else "en-US",
                pitch = 0.68f,
                speed = 0.78f,
                preferredHints = listOf("male", "us", "en-us")
            )

            "frasier" -> VoiceSpec.Android(
                localeTag = if (overrideLocale.isNotBlank()) overrideLocale else "en-US",
                pitch = 0.94f,
                speed = 0.98f,
                preferredHints = listOf("male", "us", "en-us")
            )

            "wallstreet" -> VoiceSpec.Android(
                localeTag = if (overrideLocale.isNotBlank()) overrideLocale else "en-US",
                pitch = 1.04f,
                speed = 1.18f,
                preferredHints = listOf("male", "us", "en-us")
            )

            "zen" -> VoiceSpec.Android(
                localeTag = if (overrideLocale.isNotBlank()) overrideLocale else "en-US",
                pitch = 0.90f,
                speed = 0.82f,
                preferredHints = listOf("male", "us", "en-us")
            )

            "cowboy" -> VoiceSpec.Android(
                localeTag = if (overrideLocale.isNotBlank()) overrideLocale else "en-US",
                pitch = 0.80f,
                speed = 0.86f,
                preferredHints = listOf("male", "us", "en-us")
            )

            "hunter_s" -> VoiceSpec.Android(
                localeTag = if (overrideLocale.isNotBlank()) overrideLocale else "en-US",
                pitch = 1.06f,
                speed = 1.12f,
                preferredHints = listOf("male", "us", "en-us")
            )

            "narrator" -> VoiceSpec.Android(
                localeTag = if (overrideLocale.isNotBlank()) overrideLocale else "en-US",
                pitch = 0.78f,
                speed = 0.90f,
                preferredHints = listOf("male", "us", "en-us")
            )

            "pirate" -> VoiceSpec.Android(
                localeTag = if (overrideLocale.isNotBlank()) overrideLocale else "en-GB",
                pitch = 0.88f,
                speed = 0.96f,
                preferredHints = listOf("male", "gb", "uk", "british", "en-gb")
            )

            "waifu" -> VoiceSpec.Android(
                localeTag = if (overrideLocale.isNotBlank()) overrideLocale else "en-US",
                pitch = 1.38f,
                speed = 1.08f,
                preferredHints = listOf("female", "us", "en-us")
            )

            "cleetus" -> VoiceSpec.Android(
                localeTag = if (overrideLocale.isNotBlank()) overrideLocale else "en-US",
                pitch = 0.98f,
                speed = 1.04f,
                preferredHints = listOf("male", "us", "en-us")
            )

            "peter" -> VoiceSpec.Android(
                localeTag = if (overrideLocale.isNotBlank()) overrideLocale else "en-US",
                pitch = 1.08f,
                speed = 1.06f,
                preferredHints = listOf("male", "us", "en-us")
            )

            else -> VoiceSpec.Android(
                localeTag = if (overrideLocale.isNotBlank()) overrideLocale else "en-US",
                pitch = 0.96f,
                speed = 1.0f,
                preferredHints = listOf("male", "us", "en-us")
            )
        }
    }

    // Android TTS

    private fun ensureTts() {
        if (tts != null) return

        synchronized(ttsInitLock) {
            if (tts != null) return
            val ctx = appCtx ?: return

            tts = TextToSpeech(ctx) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (!ttsReady) {
                    ErrorLogger.warn(TAG, "Android TTS init failed status=$status")
                    return@TextToSpeech
                }

                try {
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            ErrorLogger.debug(TAG, "Android TTS start: $utteranceId")
                        }

                        override fun onDone(utteranceId: String?) {
                            ErrorLogger.debug(TAG, "Android TTS done: $utteranceId")
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            ErrorLogger.warn(TAG, "Android TTS error: $utteranceId")
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            ErrorLogger.warn(TAG, "Android TTS error: $utteranceId code=$errorCode")
                        }
                    })
                } catch (t: Throwable) {
                    ErrorLogger.warn(TAG, "Failed to attach TTS listener: ${t.message}")
                }
            }
        }
    }

    private fun speakAndroid(text: String, spec: VoiceSpec.Android, token: Long) {
        ensureTts()

        Thread {
            val deadline = System.currentTimeMillis() + 2500L
            while (!ttsReady && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                }
            }

            if (!ttsReady) {
                ErrorLogger.warn(TAG, "Android TTS not ready after wait")
                return@Thread
            }

            if (token != generation.get()) return@Thread

            try {
                val engine = tts ?: return@Thread
                val locale = Locale.forLanguageTag(spec.localeTag)

                val langResult = engine.setLanguage(locale)
                if (langResult == TextToSpeech.LANG_MISSING_DATA ||
                    langResult == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    engine.setLanguage(Locale.forLanguageTag("en-US"))
                    ErrorLogger.warn(TAG, "Locale ${spec.localeTag} unavailable, using en-US")
                }

                val bestVoice = pickBestInstalledVoice(engine, locale, spec.preferredHints)
                if (bestVoice != null) {
                    try {
                        engine.voice = bestVoice
                        ErrorLogger.debug(TAG, "Selected Android voice=${bestVoice.name}")
                    } catch (t: Throwable) {
                        ErrorLogger.warn(TAG, "Setting Android voice failed: ${t.message}")
                    }
                }

                engine.setPitch(spec.pitch.coerceIn(0.6f, 1.4f))
                engine.setSpeechRate(spec.speed.coerceIn(0.7f, 1.3f))

                val utteranceId = "aate-${token}-${System.currentTimeMillis()}"
                engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            } catch (t: Throwable) {
                ErrorLogger.warn(TAG, "Android speak failed: ${t.message}")
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun pickBestInstalledVoice(
        engine: TextToSpeech,
        locale: Locale,
        hints: List<String>
    ): android.speech.tts.Voice? {
        return try {
            val available = engine.voices ?: return null
            val voices = ArrayList(available)
            val localeTag = locale.toLanguageTag().toLowerCase(Locale.US)

            val exactLocale = voices.filter {
                it.locale?.toLanguageTag()?.equals(locale.toLanguageTag(), true) == true
            }

            val byHints = exactLocale.firstOrNull { voice ->
                val haystack = (voice.name + " " + (voice.locale?.toLanguageTag() ?: ""))
                    .toLowerCase(Locale.US)
                hints.any { haystack.contains(it.toLowerCase(Locale.US)) }
            }

            byHints
                ?: exactLocale.firstOrNull { !it.isNetworkConnectionRequired }
                ?: exactLocale.firstOrNull()
                ?: voices.firstOrNull {
                    it.locale?.language?.equals(locale.language, true) == true &&
                        !it.isNetworkConnectionRequired
                }
                ?: voices.firstOrNull {
                    val tag = it.locale?.toLanguageTag()?.toLowerCase(Locale.US) ?: ""
                    tag.contains(localeTag.substringBefore("-"))
                }
        } catch (_: Throwable) {
            null
        }
    }

    // Local sherpa bridge

    private fun speakLocalSherpa(text: String, spec: VoiceSpec.LocalSherpa, token: Long) {
        val ctx = appCtx ?: return
        val trimmed = text.take(1500)

        Thread {
            try {
                if (token != generation.get()) return@Thread

                val wavBytes = LocalSherpaBridge.synthesize(
                    context = ctx,
                    modelDir = spec.modelDir,
                    text = trimmed,
                    voiceName = spec.voiceName,
                    speakerId = spec.speakerId,
                    speed = spec.speed
                )

                if (wavBytes == null || wavBytes.isEmpty()) {
                    ErrorLogger.warn(TAG, "Local sherpa TTS returned no audio, falling back")
                    speakRemoteOrAndroidFallback(trimmed, spec.fallbackLocaleTag, token)
                    return@Thread
                }

                if (token != generation.get()) return@Thread

                val outFile = File(ctx.cacheDir, "tts_local_${token}_${UUID.randomUUID()}.wav")
                FileOutputStream(outFile).use { it.write(wavBytes) }

                playAudioFile(outFile, token)
            } catch (t: Throwable) {
                ErrorLogger.warn(TAG, "Local sherpa TTS failed: ${t.message}")
                speakRemoteOrAndroidFallback(trimmed, spec.fallbackLocaleTag, token)
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun speakRemoteOrAndroidFallback(
        text: String,
        fallbackLocaleTag: String,
        token: Long
    ) {
        val ctx = appCtx ?: return
        if (isRemoteConfigured(ctx)) {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            speakRemote(
                text = text,
                spec = VoiceSpec.Remote(
                    voice = "alloy",
                    model = sanitizedTtsModel(prefs.getString(KEY_REMOTE_MODEL, DEFAULT_REMOTE_MODEL)),
                    speed = 1.0,
                    fallbackLocaleTag = fallbackLocaleTag
                ),
                token = token
            )
        } else {
            speakAndroid(
                text = text,
                spec = VoiceSpec.Android(localeTag = fallbackLocaleTag),
                token = token
            )
        }
    }

    private object LocalSherpaBridge {

        fun isAvailable(context: Context, modelDir: String): Boolean {
            return try {
                val clazz = Class.forName("com.lifecyclebot.engine.SherpaTtsBridge")
                val method = clazz.getMethod(
                    "isAvailable",
                    Context::class.java,
                    String::class.java
                )
                val result = method.invoke(null, context, modelDir)
                result as? Boolean ?: false
            } catch (_: Throwable) {
                false
            }
        }

        fun synthesize(
            context: Context,
            modelDir: String,
            text: String,
            voiceName: String,
            speakerId: Int,
            speed: Float
        ): ByteArray? {
            return try {
                val clazz = Class.forName("com.lifecyclebot.engine.SherpaTtsBridge")
                val method = clazz.getMethod(
                    "synthesize",
                    Context::class.java,
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType
                )
                method.invoke(
                    null,
                    context,
                    modelDir,
                    text,
                    voiceName,
                    speakerId,
                    speed
                ) as? ByteArray
            } catch (_: Throwable) {
                null
            }
        }
    }

    // Remote TTS

    private fun speakRemote(text: String, spec: VoiceSpec.Remote, token: Long) {
        val ctx = appCtx ?: return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val apiUrl = prefs.getString(KEY_REMOTE_URL, DEFAULT_REMOTE_URL)?.trim().orEmpty()
        val apiKey = prefs.getString(KEY_REMOTE_KEY, "")?.trim().orEmpty()

        if (apiUrl.isBlank() || apiKey.isBlank()) {
            ErrorLogger.warn(TAG, "Remote TTS missing URL/key, falling back to Android")
            speakAndroid(
                text = text,
                spec = VoiceSpec.Android(localeTag = spec.fallbackLocaleTag),
                token = token
            )
            return
        }

        val trimmed = text.take(1200)

        Thread {
            try {
                if (token != generation.get()) return@Thread

                val body = JSONObject()
                body.put("model", spec.model)
                body.put("voice", spec.voice)
                body.put("input", trimmed)
                body.put("response_format", "mp3")
                body.put("speed", spec.speed)
                // V5.9.87: style-instruction prompt (gpt-4o-mini-tts) —
                // main driver of persona voice distinctness.
                if (spec.instructions.isNotBlank()) {
                    body.put("instructions", spec.instructions)
                }

                val req = Request.Builder()
                    .url(apiUrl)
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                http.newCall(req).execute().use { resp ->
                    if (token != generation.get()) return@use

                    if (!resp.isSuccessful) {
                        val err = resp.body?.string().orEmpty().take(300)
                        ErrorLogger.warn(TAG, "Remote TTS HTTP ${resp.code}: $err")
                        speakAndroid(
                            text = trimmed,
                            spec = VoiceSpec.Android(localeTag = spec.fallbackLocaleTag),
                            token = token
                        )
                        return@use
                    }

                    val bytes = resp.body?.bytes()
                    if (bytes == null || bytes.isEmpty()) {
                        ErrorLogger.warn(TAG, "Remote TTS empty body")
                        speakAndroid(
                            text = trimmed,
                            spec = VoiceSpec.Android(localeTag = spec.fallbackLocaleTag),
                            token = token
                        )
                        return@use
                    }

                    if (token != generation.get()) return@use

                    val outFile = File(ctx.cacheDir, "tts_remote_${token}_${UUID.randomUUID()}.mp3")
                    outFile.writeBytes(bytes)

                    playAudioFile(outFile, token)
                }
            } catch (t: Throwable) {
                ErrorLogger.warn(TAG, "Remote TTS failed: ${t.message}")
                speakAndroid(
                    text = trimmed,
                    spec = VoiceSpec.Android(localeTag = spec.fallbackLocaleTag),
                    token = token
                )
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    // V5.9.361 — ElevenLabs character-voice backend.
    private fun speakElevenLabs(text: String, spec: VoiceSpec.ElevenLabs, token: Long) {
        val ctx = appCtx ?: return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val apiKey = prefs.getString(KEY_ELEVEN_KEY, "")?.trim().orEmpty()
        if (apiKey.isBlank()) {
            // No key — fall through to OpenAI proxy if configured, else Android TTS.
            val openAiUrl = prefs.getString(KEY_REMOTE_URL, DEFAULT_REMOTE_URL)?.trim().orEmpty()
            val openAiKey = prefs.getString(KEY_REMOTE_KEY, "")?.trim().orEmpty()
            if (openAiUrl.isNotBlank() && openAiKey.isNotBlank()) {
                speakRemote(
                    text = text,
                    spec = VoiceSpec.Remote(
                        voice = "alloy",
                        model = sanitizedTtsModel(prefs.getString(KEY_REMOTE_MODEL, DEFAULT_REMOTE_MODEL)),
                        speed = 1.0,
                        fallbackLocaleTag = spec.fallbackLocaleTag,
                    ),
                    token = token,
                )
            } else {
                speakAndroid(text, VoiceSpec.Android(localeTag = spec.fallbackLocaleTag), token)
            }
            return
        }

        val trimmed = text.take(1200)
        val url = "https://api.elevenlabs.io/v1/text-to-speech/${spec.voiceId}?output_format=mp3_44100_128"

        Thread {
            try {
                if (token != generation.get()) return@Thread
                val voiceSettings = JSONObject().apply {
                    put("stability", spec.stability)
                    put("similarity_boost", spec.similarityBoost)
                    put("style", spec.style)
                    put("use_speaker_boost", spec.speakerBoost)
                }
                val body = JSONObject().apply {
                    put("text", trimmed)
                    put("model_id", spec.modelId)
                    put("voice_settings", voiceSettings)
                }
                val req = Request.Builder()
                    .url(url)
                    .header("xi-api-key", apiKey)
                    .header("Accept", "audio/mpeg")
                    .header("Content-Type", "application/json")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                http.newCall(req).execute().use { resp ->
                    if (token != generation.get()) return@use
                    if (!resp.isSuccessful) {
                        val err = resp.body?.string().orEmpty().take(300)
                        ErrorLogger.warn(TAG, "ElevenLabs HTTP ${resp.code}: $err — falling back to OpenAI/Android")
                        // 401 = bad key, 429 = quota — fall back gracefully.
                        val openAiUrl = prefs.getString(KEY_REMOTE_URL, DEFAULT_REMOTE_URL)?.trim().orEmpty()
                        val openAiKey = prefs.getString(KEY_REMOTE_KEY, "")?.trim().orEmpty()
                        if (openAiUrl.isNotBlank() && openAiKey.isNotBlank()) {
                            speakRemote(
                                text = trimmed,
                                spec = VoiceSpec.Remote(
                                    voice = defaultRemoteVoiceForPersona("aate"),
                                    model = sanitizedTtsModel(prefs.getString(KEY_REMOTE_MODEL, DEFAULT_REMOTE_MODEL)),
                                    speed = 1.0,
                                    fallbackLocaleTag = spec.fallbackLocaleTag,
                                ),
                                token = token,
                            )
                        } else {
                            speakAndroid(trimmed, VoiceSpec.Android(localeTag = spec.fallbackLocaleTag), token)
                        }
                        return@use
                    }
                    val bytes = resp.body?.bytes()
                    if (bytes == null || bytes.isEmpty()) {
                        ErrorLogger.warn(TAG, "ElevenLabs empty body")
                        speakAndroid(trimmed, VoiceSpec.Android(localeTag = spec.fallbackLocaleTag), token)
                        return@use
                    }
                    if (token != generation.get()) return@use
                    val outFile = File(ctx.cacheDir, "tts_eleven_${token}_${UUID.randomUUID()}.mp3")
                    outFile.writeBytes(bytes)
                    playAudioFile(outFile, token)
                }
            } catch (t: Throwable) {
                ErrorLogger.warn(TAG, "ElevenLabs request failed: ${t.message}")
                speakAndroid(text, VoiceSpec.Android(localeTag = spec.fallbackLocaleTag), token)
            }
        }.start()
    }



    private fun playAudioFile(file: File, token: Long) {
        if (token != generation.get()) {
            try {
                file.delete()
            } catch (_: Throwable) {
            }
            return
        }

        try {
            stopMediaOnly()

            val mp = MediaPlayer()
            mediaPlayer = mp
            mp.setDataSource(file.absolutePath)

            mp.setOnCompletionListener {
                try {
                    it.release()
                } catch (_: Throwable) {
                }

                if (mediaPlayer === it) {
                    mediaPlayer = null
                }

                try {
                    file.delete()
                } catch (_: Throwable) {
                }
            }

            mp.setOnErrorListener { player, what, extra ->
                ErrorLogger.warn(TAG, "MediaPlayer error $what/$extra")

                try {
                    player.release()
                } catch (_: Throwable) {
                }

                if (mediaPlayer === player) {
                    mediaPlayer = null
                }

                try {
                    file.delete()
                } catch (_: Throwable) {
                }

                true
            }

            mp.prepare()

            if (token != generation.get()) {
                try {
                    mp.release()
                } catch (_: Throwable) {
                }

                if (mediaPlayer === mp) {
                    mediaPlayer = null
                }

                try {
                    file.delete()
                } catch (_: Throwable) {
                }
                return
            }

            mp.start()
        } catch (t: Throwable) {
            ErrorLogger.warn(TAG, "playAudioFile failed: ${t.message}")
            try {
                file.delete()
            } catch (_: Throwable) {
            }
        }
    }

    private fun stopMediaOnly() {
        try {
            mediaPlayer?.stop()
        } catch (_: Throwable) {
        }

        try {
            mediaPlayer?.reset()
        } catch (_: Throwable) {
        }

        try {
            mediaPlayer?.release()
        } catch (_: Throwable) {
        }

        mediaPlayer = null
    }

    private fun cleanForSpeech(raw: String): String {
        var s = raw
        s = s.replace(Regex("```[\\s\\S]*?```"), " ")
        s = s.replace(Regex("`([^`]*)`"), "$1")
        s = s.replace(Regex("\\[([^\\]]+)]\\(([^)]+)\\)"), "$1")
        s = s.replace(Regex("https?://\\S+"), "link")
        s = s.replace("&", " and ")
        s = s.replace("@", " at ")
        s = s.replace(Regex("[*_#>`~]"), " ")
        s = s.replace(Regex("\\b([A-Z]{2,})\\b")) { match ->
            match.value.toCharArray().joinToString(" ") { it.toString() }
        }
        s = s.replace(Regex("\\s+"), " ").trim()
        return s
    }
}