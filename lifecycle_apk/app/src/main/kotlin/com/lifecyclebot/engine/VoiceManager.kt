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
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * VoiceManager — rewritten.
 *
 * Fixes:
 * - Android TTS job state now ends on utterance completion, not immediately after speak()
 * - Remote playback uses unique temp files instead of one shared filename
 * - Persona fallback preserves intended locale instead of always dropping to en-US
 * - Android voice selection now tries to pick an actual matching installed voice
 * - Remote persona voices support per-persona override IDs from SharedPreferences
 *
 * SharedPreferences (voice_prefs_v2):
 * - muted : Boolean
 * - tts_api_url : String
 * - tts_api_key : String
 * - tts_model_default : String
 * - tts_voice_<personaId> : String
 * - tts_locale_<personaId> : String
 */
object VoiceManager {

    private const val TAG = "VoiceManager"
    private const val PREFS = "voice_prefs_v2"

    private const val KEY_MUTED = "muted"
    private const val KEY_API_URL = "tts_api_url"
    private const val KEY_API_KEY = "tts_api_key"
    private const val KEY_DEFAULT_MODEL = "tts_model_default"

    // Default proxy URL. Move key + URL into prefs/settings.
    private const val DEFAULT_TTS_URL = "https://integrations.emergentagent.com/llm/audio/speech"
    private const val DEFAULT_MODEL = "gpt-4o-mini-tts"

    private var appCtx: Context? = null

    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private val ttsInitLock = Any()

    @Volatile private var mediaPlayer: MediaPlayer? = null
    private val generation = AtomicLong(0L)

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
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
            val model: String = DEFAULT_MODEL,
            val speed: Double = 1.0,
            val fallbackLocaleTag: String = "en-US"
        ) : VoiceSpec()
    }

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        ErrorLogger.info(TAG, "🔊 VoiceManager init (muted=${isMuted(ctx)})")
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
        if (newMuted) stop()
        ErrorLogger.info(TAG, "🔊 Mute toggled → muted=$newMuted")
        return newMuted
    }

    fun stop() {
        generation.incrementAndGet()

        try { tts?.stop() } catch (_: Throwable) {}

        try { mediaPlayer?.stop() } catch (_: Throwable) {}
        try { mediaPlayer?.reset() } catch (_: Throwable) {}
        try { mediaPlayer?.release() } catch (_: Throwable) {}
        mediaPlayer = null
    }

    fun shutdown() {
        stop()
        try { tts?.shutdown() } catch (_: Throwable) {}
        tts = null
        ttsReady = false
    }

    fun speak(text: String, persona: Personalities.Persona?) {
        val ctx = appCtx ?: return
        if (text.isBlank()) return
        if (isMuted(ctx)) return

        val clean = cleanForSpeech(text)
        if (clean.isBlank()) return

        val token = generation.incrementAndGet()
        stopMediaOnly()

        val personaId = persona?.id ?: "aate"
        val spec = resolveVoiceSpec(ctx, personaId)

        when (spec) {
            is VoiceSpec.Android -> speakAndroid(clean, spec, token)
            is VoiceSpec.Remote -> speakRemote(clean, spec, token)
        }
    }

    fun previewPersona(personaId: String, sampleText: String = "Voice check. This is how I currently sound.") {
        val ctx = appCtx ?: return
        if (isMuted(ctx)) return
        val clean = cleanForSpeech(sampleText)
        val token = generation.incrementAndGet()
        stopMediaOnly()

        when (val spec = resolveVoiceSpec(ctx, personaId)) {
            is VoiceSpec.Android -> speakAndroid(clean, spec, token)
            is VoiceSpec.Remote -> speakRemote(clean, spec, token)
        }
    }

    private fun resolveVoiceSpec(ctx: Context, personaId: String): VoiceSpec {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val overrideVoice = prefs.getString("tts_voice_$personaId", null)?.trim().orEmpty()
        val overrideLocale = prefs.getString("tts_locale_$personaId", null)?.trim().orEmpty()

        if (overrideVoice.isNotBlank()) {
            return VoiceSpec.Remote(
                voice = overrideVoice,
                model = prefs.getString(KEY_DEFAULT_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL,
                speed = when (personaId) {
                    "wallstreet" -> 1.12
                    "zen" -> 0.88
                    "narrator" -> 0.92
                    "cowboy" -> 0.90
                    else -> 1.0
                },
                fallbackLocaleTag = if (overrideLocale.isNotBlank()) overrideLocale else defaultLocaleForPersona(personaId)
            )
        }

        return when (personaId) {
            "aate" -> VoiceSpec.Remote(
                voice = "alloy",
                model = prefs.getString(KEY_DEFAULT_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL,
                speed = 1.0,
                fallbackLocaleTag = "en-US"
            )

            "irishman" -> VoiceSpec.Android(
                localeTag = if (overrideLocale.isNotBlank()) overrideLocale else "en-IE",
                pitch = 0.98f,
                speed = 1.03f,
                preferredHints = listOf("ireland", "irish", "en-ie")
            )

            "gentleman" -> VoiceSpec.Android(
                localeTag = if (overrideLocale.isNotBlank()) overrideLocale else "en-GB",
                pitch = 0.96f,
                speed = 0.94f,
                preferredHints = listOf("gb", "uk", "british", "en-gb")
            )

            "cockney" -> VoiceSpec.Android(
                localeTag = if (overrideLocale.isNotBlank()) overrideLocale else "en-GB",
                pitch = 1.06f,
                speed = 1.06f,
                preferredHints = listOf("gb", "uk", "british", "en-gb")
            )

            "batman" -> VoiceSpec.Remote(
                voice = "onyx",
                model = prefs.getString(KEY_DEFAULT_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL,
                speed = 0.88,
                fallbackLocaleTag = "en-US"
            )

            "frasier" -> VoiceSpec.Remote(
                voice = "fable",
                model = prefs.getString(KEY_DEFAULT_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL,
                speed = 0.95,
                fallbackLocaleTag = "en-US"
            )

            "wallstreet" -> VoiceSpec.Remote(
                voice = "ash",
                model = prefs.getString(KEY_DEFAULT_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL,
                speed = 1.12,
                fallbackLocaleTag = "en-US"
            )

            "zen" -> VoiceSpec.Remote(
                voice = "sage",
                model = prefs.getString(KEY_DEFAULT_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL,
                speed = 0.88,
                fallbackLocaleTag = "en-US"
            )

            "cowboy" -> VoiceSpec.Remote(
                voice = "alloy",
                model = prefs.getString(KEY_DEFAULT_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL,
                speed = 0.90,
                fallbackLocaleTag = "en-US"
            )

            "hunter_s" -> VoiceSpec.Remote(
                voice = "onyx",
                model = prefs.getString(KEY_DEFAULT_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL,
                speed = 1.04,
                fallbackLocaleTag = "en-US"
            )

            "narrator" -> VoiceSpec.Remote(
                voice = "fable",
                model = prefs.getString(KEY_DEFAULT_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL,
                speed = 0.92,
                fallbackLocaleTag = "en-US"
            )

            "pirate" -> VoiceSpec.Remote(
                voice = "echo",
                model = prefs.getString(KEY_DEFAULT_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL,
                speed = 1.00,
                fallbackLocaleTag = "en-GB"
            )

            else -> VoiceSpec.Remote(
                voice = "alloy",
                model = prefs.getString(KEY_DEFAULT_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL,
                speed = 1.0,
                fallbackLocaleTag = "en-US"
            )
        }
    }

    private fun defaultLocaleForPersona(personaId: String): String {
        return when (personaId) {
            "irishman" -> "en-IE"
            "gentleman", "cockney", "pirate" -> "en-GB"
            "aate", "batman", "frasier", "wallstreet", "zen", "cowboy", "hunter_s", "narrator" -> "en-US"
            else -> "en-US"
        }
    }

    // Android TTS

    private fun ensureTts() {
        if (tts != null) return

        synchronized(ttsInitLock) {
            if (tts != null) return
            val ctx = appCtx ?: return

            tts = TextToSpeech(ctx) { status ->
                ttsReady = (status == TextToSpeech.SUCCESS)
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
                try { Thread.sleep(50) } catch (_: InterruptedException) {}
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
                    langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
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
            val voices = engine.voices ?: return null
            val langTag = locale.toLanguageTag().lowercase(Locale.US)

            val exactLocale = voices.filter { it.locale?.toLanguageTag()?.equals(locale.toLanguageTag(), true) == true }
            val byHints = exactLocale.firstOrNull { voice ->
                val hay = "${voice.name} ${voice.locale?.toLanguageTag()}".lowercase(Locale.US)
                hints.any { hay.contains(it.lowercase(Locale.US)) }
            }

            byHints
                ?: exactLocale.firstOrNull { !it.isNetworkConnectionRequired }
                ?: exactLocale.firstOrNull()
                ?: voices.firstOrNull {
                    it.locale?.language?.equals(locale.language, true) == true &&
                        !it.isNetworkConnectionRequired
                }
                ?: voices.firstOrNull {
                    it.locale?.toLanguageTag()?.lowercase(Locale.US)?.contains(langTag.substringBefore("-")) == true
                }
        } catch (_: Throwable) {
            null
        }
    }

    // Remote TTS

    private fun speakRemote(text: String, spec: VoiceSpec.Remote, token: Long) {
        val ctx = appCtx ?: return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val apiUrl = prefs.getString(KEY_API_URL, DEFAULT_TTS_URL)?.trim().orEmpty()
        val apiKey = prefs.getString(KEY_API_KEY, "")?.trim().orEmpty()

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

                val body = JSONObject().apply {
                    put("model", spec.model)
                    put("voice", spec.voice)
                    put("input", trimmed)
                    put("response_format", "mp3")
                    put("speed", spec.speed)
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

                    val outFile = File(ctx.cacheDir, "tts_${token}_${UUID.randomUUID()}.mp3")
                    outFile.writeBytes(bytes)

                    playMp3(outFile, token)
                }
            } catch (t: Throwable) {
                ErrorLogger.warn(TAG, "Remote TTS failed: ${t.message}")
                try {
                    speakAndroid(
                        text = trimmed,
                        spec = VoiceSpec.Android(localeTag = spec.fallbackLocaleTag),
                        token = token
                    )
                } catch (_: Throwable) {}
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun playMp3(file: File, token: Long) {
        if (token != generation.get()) {
            try { file.delete() } catch (_: Throwable) {}
            return
        }

        try {
            stopMediaOnly()

            val mp = MediaPlayer()
            mediaPlayer = mp

            mp.setDataSource(file.absolutePath)

            mp.setOnCompletionListener {
                try { it.release() } catch (_: Throwable) {}
                if (mediaPlayer === it) mediaPlayer = null
                try { file.delete() } catch (_: Throwable) {}
            }

            mp.setOnErrorListener { player, what, extra ->
                ErrorLogger.warn(TAG, "MediaPlayer error $what/$extra")
                try { player.release() } catch (_: Throwable) {}
                if (mediaPlayer === player) mediaPlayer = null
                try { file.delete() } catch (_: Throwable) {}
                true
            }

            mp.prepare()

            if (token != generation.get()) {
                try { mp.release() } catch (_: Throwable) {}
                if (mediaPlayer === mp) mediaPlayer = null
                try { file.delete() } catch (_: Throwable) {}
                return
            }

            mp.start()
        } catch (t: Throwable) {
            ErrorLogger.warn(TAG, "playMp3 failed: ${t.message}")
            try { file.delete() } catch (_: Throwable) {}
        }
    }

    private fun stopMediaOnly() {
        try { mediaPlayer?.stop() } catch (_: Throwable) {}
        try { mediaPlayer?.reset() } catch (_: Throwable) {}
        try { mediaPlayer?.release() } catch (_: Throwable) {}
        mediaPlayer = null
    }

    // Sanitisation

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