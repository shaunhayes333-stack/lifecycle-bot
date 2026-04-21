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

    private const val KEY_LOCAL_ENABLED = "tts_local_enabled"
    private const val KEY_LOCAL_ENGINE = "tts_local_engine"
    private const val KEY_LOCAL_MODEL_DIR = "tts_local_model_dir"

    private const val DEFAULT_REMOTE_URL = "https://integrations.emergentagent.com/llm/audio/speech"
    private const val DEFAULT_REMOTE_MODEL = "gpt-4o-mini-tts"
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
            val fallbackLocaleTag: String = "en-US"
        ) : VoiceSpec()

        data class LocalSherpa(
            val modelDir: String,
            val voiceName: String = "",
            val speakerId: Int = 0,
            val speed: Float = 1.0f,
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
        val spec = resolveVoiceSpec(ctx, personaId)

        when (spec) {
            is VoiceSpec.LocalSherpa -> speakLocalSherpa(clean, spec, token)
            is VoiceSpec.Remote -> speakRemote(clean, spec, token)
            is VoiceSpec.Android -> speakAndroid(clean, spec, token)
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
        if (remoteConfigured) {
            val remoteVoice = if (overrideRemoteVoice.isNotBlank()) {
                overrideRemoteVoice
            } else {
                defaultRemoteVoiceForPersona(personaId)
            }

            return VoiceSpec.Remote(
                voice = remoteVoice,
                model = prefs.getString(KEY_REMOTE_MODEL, DEFAULT_REMOTE_MODEL) ?: DEFAULT_REMOTE_MODEL,
                speed = defaultRemoteSpeedForPersona(personaId),
                fallbackLocaleTag = if (overrideLocale.isNotBlank()) overrideLocale else defaultLocaleForPersona(personaId)
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

    private fun defaultRemoteVoiceForPersona(personaId: String): String {
        return when (personaId) {
            "batman", "hunter_s" -> "onyx"
            "frasier", "narrator" -> "fable"
            "wallstreet" -> "ash"
            "zen" -> "sage"
            "pirate" -> "echo"
            "cowboy" -> "alloy"
            "waifu" -> "shimmer"
            "cleetus" -> "ash"
            "peter" -> "echo"
            else -> "alloy"
        }
    }

    private fun defaultRemoteSpeedForPersona(personaId: String): Double {
        return when (personaId) {
            "wallstreet" -> 1.12
            "zen" -> 0.88
            "narrator" -> 0.92
            "cowboy" -> 0.90
            "batman" -> 0.86
            "hunter_s" -> 1.04
            "waifu" -> 1.08
            "cleetus" -> 0.97
            "peter" -> 1.05
            else -> 1.0
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
        return when (personaId) {
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

            "batman" -> VoiceSpec.Android(
                localeTag = if (overrideLocale.isNotBlank()) overrideLocale else "en-US",
                pitch = 0.78f,
                speed = 0.86f,
                preferredHints = listOf("us", "en-us")
            )

            "frasier" -> VoiceSpec.Android(
                localeTag = if (overrideLocale.isNotBlank()) overrideLocale else "en-US",
                pitch = 0.95f,
                speed = 0.93f,
                preferredHints = listOf("us", "en-us")
            )

            "wallstreet" -> VoiceSpec.Android(
                localeTag = if (overrideLocale.isNotBlank()) overrideLocale else "en-US",
                pitch = 1.02f,
                speed = 1.08f,
                preferredHints = listOf("us", "en-us")
            )

            "zen" -> VoiceSpec.Android(
                localeTag = if (overrideLocale.isNotBlank()) overrideLocale else "en-US",
                pitch = 0.92f,
                speed = 0.84f,
                preferredHints = listOf("us", "en-us")
            )

            "cowboy" -> VoiceSpec.Android(
                localeTag = if (overrideLocale.isNotBlank()) overrideLocale else "en-US",
                pitch = 0.92f,
                speed = 0.90f,
                preferredHints = listOf("us", "en-us")
            )

            "hunter_s" -> VoiceSpec.Android(
                localeTag = if (overrideLocale.isNotBlank()) overrideLocale else "en-US",
                pitch = 1.02f,
                speed = 1.05f,
                preferredHints = listOf("us", "en-us")
            )

            "narrator" -> VoiceSpec.Android(
                localeTag = if (overrideLocale.isNotBlank()) overrideLocale else "en-US",
                pitch = 0.93f,
                speed = 0.90f,
                preferredHints = listOf("us", "en-us")
            )

            "pirate" -> VoiceSpec.Android(
                localeTag = if (overrideLocale.isNotBlank()) overrideLocale else "en-GB",
                pitch = 0.96f,
                speed = 0.98f,
                preferredHints = listOf("gb", "uk", "british", "en-gb")
            )

            "waifu" -> VoiceSpec.Android(
                localeTag = if (overrideLocale.isNotBlank()) overrideLocale else "en-US",
                pitch = 1.32f,
                speed = 1.08f,
                preferredHints = listOf("female", "us", "en-us")
            )

            "cleetus" -> VoiceSpec.Android(
                localeTag = if (overrideLocale.isNotBlank()) overrideLocale else "en-US",
                pitch = 0.94f,
                speed = 0.96f,
                preferredHints = listOf("us", "male", "en-us")
            )

            "peter" -> VoiceSpec.Android(
                localeTag = if (overrideLocale.isNotBlank()) overrideLocale else "en-US",
                pitch = 1.10f,
                speed = 1.04f,
                preferredHints = listOf("us", "male", "en-us")
            )

            else -> VoiceSpec.Android(
                localeTag = if (overrideLocale.isNotBlank()) overrideLocale else "en-US",
                pitch = 0.98f,
                speed = 1.0f,
                preferredHints = listOf("us", "en-us")
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
                    model = prefs.getString(KEY_REMOTE_MODEL, DEFAULT_REMOTE_MODEL) ?: DEFAULT_REMOTE_MODEL,
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