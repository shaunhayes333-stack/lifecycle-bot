package com.lifecyclebot.engine

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V5.9.75 — VoiceManager.
 *
 * Speaks LLM replies using:
 *  (a) Android built-in TextToSpeech for regional accents (en-IE / en-GB /
 *      en-AU / en-US) — free, offline once locale is installed.
 *  (b) OpenAI TTS through the Emergent universal proxy for stylised voices
 *      (Morgan Freeman narration, Batman gravel, Frasier storyteller…).
 *
 * Persona → voice routing is declared in personaVoice().
 *
 * Mute state is persisted in SharedPreferences. Default = MUTED so the
 * bot never surprises a user with random speech on first launch.
 */
object VoiceManager {

    private const val TAG = "VoiceManager"
    private const val PREFS = "voice_prefs_v1"
    private const val KEY_MUTED = "muted"
    private const val EMERGENT_TTS_URL = "https://integrations.emergentagent.com/llm/audio/speech"
    private const val EMERGENT_KEY = "sk-emergent-431Dd41D3F186C0E0B"

    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private val ttsInitLock = Any()

    private var mediaPlayer: MediaPlayer? = null
    private val currentJob = AtomicBoolean(false)

    private var appCtx: Context? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    // ─── Strategy ────────────────────────────────────────────────────────
    sealed class VoiceSpec {
        /** Android built-in TTS. locale = e.g. "en-IE", "en-GB". */
        data class Android(val locale: String, val pitch: Float = 1.0f, val speed: Float = 1.0f) : VoiceSpec()

        /** OpenAI TTS via Emergent proxy. */
        data class OpenAI(val voice: String, val speed: Double = 1.0, val hd: Boolean = false) : VoiceSpec()
    }

    /** Persona → voice strategy. */
    private fun personaVoice(personaId: String): VoiceSpec = when (personaId) {
        "aate"        -> VoiceSpec.Android(locale = "en-US")
        "irishman"    -> VoiceSpec.Android(locale = "en-IE", speed = 1.05f)
        "batman"      -> VoiceSpec.OpenAI(voice = "onyx", speed = 0.92, hd = true)
        "gentleman"   -> VoiceSpec.Android(locale = "en-GB", speed = 0.95f)
        "frasier"     -> VoiceSpec.OpenAI(voice = "fable", speed = 0.98, hd = true)
        "wallstreet"  -> VoiceSpec.OpenAI(voice = "ash", speed = 1.15)
        "zen"         -> VoiceSpec.OpenAI(voice = "sage", speed = 0.85)
        "cockney"     -> VoiceSpec.Android(locale = "en-GB", pitch = 1.1f, speed = 1.1f)
        "cowboy"      -> VoiceSpec.OpenAI(voice = "alloy", speed = 0.88)
        "hunter_s"    -> VoiceSpec.OpenAI(voice = "onyx", speed = 1.1)
        "narrator"    -> VoiceSpec.OpenAI(voice = "fable", speed = 0.9, hd = true)
        "pirate"      -> VoiceSpec.OpenAI(voice = "echo", speed = 1.05)
        else          -> VoiceSpec.Android(locale = "en-US")
    }

    // ─── Public API ─────────────────────────────────────────────────────
    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        // Lazy-init Android TTS on first speak to avoid startup cost.
        ErrorLogger.info(TAG, "🔊 VoiceManager init (muted=${isMuted(ctx)})")
    }

    fun isMuted(ctx: Context): Boolean {
        val c = appCtx ?: ctx.applicationContext.also { appCtx = it }
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_MUTED, true)
    }

    /** Returns the new muted state. */
    fun toggleMute(ctx: Context): Boolean {
        val c = appCtx ?: ctx.applicationContext.also { appCtx = it }
        val newMuted = !isMuted(c)
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_MUTED, newMuted).apply()
        if (newMuted) stop()
        ErrorLogger.info(TAG, "🔊 Mute toggled → muted=$newMuted")
        return newMuted
    }

    fun stop() {
        try { tts?.stop() } catch (_: Throwable) {}
        try { mediaPlayer?.stop() } catch (_: Throwable) {}
        try { mediaPlayer?.release() } catch (_: Throwable) {}
        mediaPlayer = null
        currentJob.set(false)
    }

    fun speak(text: String, persona: Personalities.Persona?) {
        val ctx = appCtx ?: return
        if (text.isBlank()) return
        if (isMuted(ctx)) return

        // Strip markdown / excessive punctuation so TTS sounds natural.
        val clean = cleanForSpeech(text)
        if (clean.isBlank()) return

        // Serial: interrupt previous speech.
        stop()
        currentJob.set(true)

        val spec = personaVoice(persona?.id ?: "aate")
        when (spec) {
            is VoiceSpec.Android -> speakAndroid(clean, spec)
            is VoiceSpec.OpenAI  -> speakOpenAI(clean, spec)
        }
    }

    // ─── Android TTS ────────────────────────────────────────────────────
    private fun speakAndroid(text: String, spec: VoiceSpec.Android) {
        val ctx = appCtx ?: return
        ensureTts()
        val locale = Locale.forLanguageTag(spec.locale)

        Thread {
            // Wait up to 2s for TTS engine to initialise.
            val deadline = System.currentTimeMillis() + 2000
            while (!ttsReady && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(50) } catch (_: InterruptedException) {}
            }
            if (!ttsReady) {
                ErrorLogger.warn(TAG, "Android TTS not ready after 2s — skipping")
                currentJob.set(false)
                return@Thread
            }

            try {
                val engine = tts ?: return@Thread
                val res = engine.setLanguage(locale)
                if (res == TextToSpeech.LANG_MISSING_DATA ||
                    res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    // Fall back to en-US.
                    engine.setLanguage(Locale.US)
                    ErrorLogger.debug(TAG, "Locale ${spec.locale} unavailable, fell back to en-US")
                }
                engine.setPitch(spec.pitch)
                engine.setSpeechRate(spec.speed)
                engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "aate-${System.currentTimeMillis()}")
            } catch (e: Exception) {
                ErrorLogger.warn(TAG, "Android TTS speak failed: ${e.message}")
            } finally {
                currentJob.set(false)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun ensureTts() {
        if (tts != null) return
        synchronized(ttsInitLock) {
            if (tts != null) return
            val ctx = appCtx ?: return
            tts = TextToSpeech(ctx) { status ->
                ttsReady = (status == TextToSpeech.SUCCESS)
                if (!ttsReady) {
                    ErrorLogger.warn(TAG, "Android TTS init failed status=$status")
                }
            }
        }
    }

    // ─── OpenAI TTS via Emergent proxy ─────────────────────────────────
    private fun speakOpenAI(text: String, spec: VoiceSpec.OpenAI) {
        val ctx = appCtx ?: return
        // Text limit 4096 per OpenAI; be safer and split on first 900 for responsiveness.
        val trimmed = if (text.length > 900) text.substring(0, 900) + "…" else text

        Thread {
            try {
                val body = JSONObject().apply {
                    put("model", if (spec.hd) "tts-1-hd" else "tts-1")
                    put("voice", spec.voice)
                    put("input", trimmed)
                    put("response_format", "mp3")
                    put("speed", spec.speed)
                }
                val req = Request.Builder()
                    .url(EMERGENT_TTS_URL)
                    .header("Authorization", "Bearer $EMERGENT_KEY")
                    .header("Content-Type", "application/json")
                    .post(body.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()

                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        ErrorLogger.warn(TAG,
                            "OpenAI TTS HTTP ${resp.code} — falling back to Android TTS")
                        // Fallback so the user still hears the reply.
                        speakAndroid(trimmed, VoiceSpec.Android(locale = "en-US"))
                        return@Thread
                    }
                    val bytes = resp.body?.bytes()
                    if (bytes == null || bytes.isEmpty()) {
                        ErrorLogger.warn(TAG, "OpenAI TTS empty body — Android fallback")
                        speakAndroid(trimmed, VoiceSpec.Android(locale = "en-US"))
                        return@Thread
                    }

                    val cacheFile = File(ctx.cacheDir, "tts_latest.mp3")
                    cacheFile.writeBytes(bytes)

                    val mp = MediaPlayer()
                    mediaPlayer = mp
                    mp.setDataSource(cacheFile.absolutePath)
                    mp.setOnCompletionListener {
                        currentJob.set(false)
                        try { it.release() } catch (_: Throwable) {}
                        if (mediaPlayer === it) mediaPlayer = null
                    }
                    mp.setOnErrorListener { _, what, extra ->
                        ErrorLogger.warn(TAG, "MediaPlayer error $what/$extra")
                        currentJob.set(false)
                        true
                    }
                    mp.prepare()
                    mp.start()
                }
            } catch (e: Exception) {
                ErrorLogger.warn(TAG, "OpenAI TTS failed: ${e.message} — Android fallback")
                try { speakAndroid(trimmed, VoiceSpec.Android(locale = "en-US")) } catch (_: Throwable) {}
            }
        }.apply { isDaemon = true }.start()
    }

    // ─── Text sanitisation ──────────────────────────────────────────────
    private fun cleanForSpeech(raw: String): String {
        // Strip markdown code fences, asterisks, backticks, URLs.
        var s = raw
        s = s.replace(Regex("```[\\s\\S]*?```"), " ")
        s = s.replace(Regex("`([^`]*)`"), "$1")
        s = s.replace(Regex("[*_#>]"), "")
        s = s.replace(Regex("https?://\\S+"), "link")
        s = s.replace(Regex("\\s+"), " ").trim()
        return s
    }

    fun shutdown() {
        stop()
        try { tts?.shutdown() } catch (_: Throwable) {}
        tts = null
        ttsReady = false
    }
}
