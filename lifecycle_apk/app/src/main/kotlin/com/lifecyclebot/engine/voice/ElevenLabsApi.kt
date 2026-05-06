package com.lifecyclebot.engine.voice

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.network.SharedHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * V5.9.495z18 — Clean ElevenLabs Creator-tier API client.
 *
 * Wraps:
 *   • POST /v1/text-to-speech/{voice_id}                  — HTTP TTS (existing)
 *   • POST /v1/sound-generation                           — Sound effects (NEW)
 *   • POST /v1/voice-generation/create-previews           — Voice Design step 1
 *   • POST /v1/voice-generation/create-voice-from-preview — Voice Design step 2
 *   • GET  /v1/history?voice_id=&page_size=               — History (cache dedupe)
 *
 * No Android-specific code here — pure client. Returns raw bytes / parsed JSON.
 * VoiceManager handles caching, playback and error UI.
 */
object ElevenLabsApi {

    private const val TAG = "ElevenLabsApi"
    private const val BASE = "https://api.elevenlabs.io"

    private val http = SharedHttpClient.builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    data class TtsParams(
        val text: String,
        val voiceId: String,
        val modelId: String,
        val stability: Double,
        val similarityBoost: Double,
        val style: Double,
        val speakerBoost: Boolean,
        val speed: Double = 1.0,
    )

    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        data class Err(val code: Int, val message: String) : Result<Nothing>()
    }

    // ── TTS ───────────────────────────────────────────────────────────────────

    /**
     * Synthesise speech to an MP3 byte array.
     * Returns Err on any non-2xx response so caller can fall back / mute.
     */
    fun tts(apiKey: String, p: TtsParams): Result<ByteArray> {
        if (apiKey.isBlank()) return Result.Err(401, "ElevenLabs key missing")
        if (p.voiceId.isBlank()) return Result.Err(400, "voice_id missing")

        val voiceSettings = JSONObject().apply {
            put("stability", p.stability)
            put("similarity_boost", p.similarityBoost)
            put("style", p.style)
            put("use_speaker_boost", p.speakerBoost)
            // ElevenLabs accepts speed in voice_settings on v3 / multilingual.
            if (p.speed != 1.0) put("speed", p.speed)
        }
        val body = JSONObject().apply {
            put("text", p.text)
            put("model_id", p.modelId)
            put("voice_settings", voiceSettings)
        }.toString()

        val req = Request.Builder()
            .url("$BASE/v1/text-to-speech/${p.voiceId}?optimize_streaming_latency=3&output_format=mp3_44100_128")
            .addHeader("xi-api-key", apiKey)
            .addHeader("Accept", "audio/mpeg")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        return runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = resp.body?.string()?.take(400) ?: ""
                    Result.Err(resp.code, errBody)
                } else {
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    if (bytes.isEmpty()) Result.Err(500, "empty audio body")
                    else Result.Ok(bytes)
                }
            }
        }.getOrElse { e ->
            ErrorLogger.warn(TAG, "tts() exception: ${e.message}")
            Result.Err(-1, e.message ?: "exception")
        }
    }

    // ── Sound Effects ─────────────────────────────────────────────────────────

    data class SfxParams(
        val prompt: String,
        val durationSeconds: Double? = null,    // null = auto, max 22s
        val promptInfluence: Double = 0.5,      // 0..1, higher = stricter to prompt
    )

    /** Generate an SFX clip from a text prompt. Returns MP3 bytes. */
    fun sfx(apiKey: String, p: SfxParams): Result<ByteArray> {
        if (apiKey.isBlank()) return Result.Err(401, "ElevenLabs key missing")
        val body = JSONObject().apply {
            put("text", p.prompt)
            p.durationSeconds?.let { put("duration_seconds", it) }
            put("prompt_influence", p.promptInfluence)
        }.toString()
        val req = Request.Builder()
            .url("$BASE/v1/sound-generation")
            .addHeader("xi-api-key", apiKey)
            .addHeader("Accept", "audio/mpeg")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Result.Err(resp.code, resp.body?.string()?.take(400) ?: "")
                } else {
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    if (bytes.isEmpty()) Result.Err(500, "empty sfx body")
                    else Result.Ok(bytes)
                }
            }
        }.getOrElse { e ->
            ErrorLogger.warn(TAG, "sfx() exception: ${e.message}")
            Result.Err(-1, e.message ?: "exception")
        }
    }

    // ── Voice Design ──────────────────────────────────────────────────────────

    data class VoiceDesignParams(
        val prompt: String,                      // e.g. "older male, gravelly, theatrical"
        val sampleText: String,                  // 100-1000 chars; the preview reads this
        val gender: String? = null,              // "male" | "female"
        val accent: String? = null,              // "american" | "british" | etc.
        val accentStrength: Double = 1.0,
        val age: String? = null,                 // "young" | "middle_aged" | "old"
    )

    /** Step 1: ask ElevenLabs to generate up to 3 voice previews. Returns generated_voice_id list + audio bytes. */
    fun voiceDesignPreviews(apiKey: String, p: VoiceDesignParams): Result<List<VoicePreview>> {
        if (apiKey.isBlank()) return Result.Err(401, "ElevenLabs key missing")
        val body = JSONObject().apply {
            put("voice_description", p.prompt)
            put("text", p.sampleText)
            p.gender?.let { put("gender", it) }
            p.accent?.let { put("accent", it) }
            put("accent_strength", p.accentStrength)
            p.age?.let { put("age", it) }
        }.toString()
        val req = Request.Builder()
            .url("$BASE/v1/voice-generation/create-previews")
            .addHeader("xi-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Result.Err(resp.code, raw.take(400))
                } else {
                    val obj = JSONObject(raw)
                    val arr = obj.optJSONArray("previews") ?: return Result.Err(500, "no previews")
                    val out = mutableListOf<VoicePreview>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        out += VoicePreview(
                            generatedVoiceId = o.optString("generated_voice_id"),
                            audioBase64 = o.optString("audio_base_64"),
                            mediaType = o.optString("media_type", "audio/mpeg"),
                        )
                    }
                    Result.Ok(out)
                }
            }
        }.getOrElse { e ->
            ErrorLogger.warn(TAG, "voiceDesignPreviews() exception: ${e.message}")
            Result.Err(-1, e.message ?: "exception")
        }
    }

    /** Step 2: confirm a chosen preview into a permanent voice in the user's library. */
    fun voiceDesignSave(
        apiKey: String,
        generatedVoiceId: String,
        voiceName: String,
        voiceDescription: String,
        labels: Map<String, String> = emptyMap(),
    ): Result<String> {
        if (apiKey.isBlank()) return Result.Err(401, "ElevenLabs key missing")
        val body = JSONObject().apply {
            put("voice_name", voiceName)
            put("voice_description", voiceDescription)
            put("generated_voice_id", generatedVoiceId)
            if (labels.isNotEmpty()) {
                put("labels", JSONObject(labels))
            }
        }.toString()
        val req = Request.Builder()
            .url("$BASE/v1/voice-generation/create-voice-from-preview")
            .addHeader("xi-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) Result.Err(resp.code, raw.take(400))
                else {
                    val voiceId = JSONObject(raw).optString("voice_id")
                    if (voiceId.isBlank()) Result.Err(500, "no voice_id in response")
                    else Result.Ok(voiceId)
                }
            }
        }.getOrElse { e ->
            ErrorLogger.warn(TAG, "voiceDesignSave() exception: ${e.message}")
            Result.Err(-1, e.message ?: "exception")
        }
    }

    data class VoicePreview(
        val generatedVoiceId: String,
        val audioBase64: String,
        val mediaType: String,
    )

    // ── History dedupe ────────────────────────────────────────────────────────

    /**
     * Hash the generation request so we can match it against the per-call cache
     * key. Same hash → identical generation, skip the API call.
     */
    fun cacheKey(p: TtsParams): String {
        val raw = "${p.voiceId}|${p.modelId}|${p.stability}|${p.similarityBoost}|${p.style}|${p.speakerBoost}|${p.speed}|${p.text}"
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(40)
    }

    fun cacheKeySfx(p: SfxParams): String {
        val raw = "sfx|${p.durationSeconds}|${p.promptInfluence}|${p.prompt}"
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(40)
    }

    /**
     * Disk-cache helper. Caller passes a cacheDir + key; we write/read raw audio bytes.
     * The intent is: before calling tts()/sfx(), check `readCache(dir, key)`. If null,
     * call API, then `writeCache(dir, key, bytes)`. ElevenLabs' own server-side
     * History API is the secondary safety-net: re-downloads from history are free.
     */
    fun readCache(cacheDir: File, key: String): ByteArray? {
        val f = File(cacheDir, "$key.mp3")
        if (!f.exists() || f.length() <= 0) return null
        return runCatching { f.readBytes() }.getOrNull()
    }

    fun writeCache(cacheDir: File, key: String, bytes: ByteArray): File? {
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val f = File(cacheDir, "$key.mp3")
        return runCatching {
            FileOutputStream(f).use { it.write(bytes) }
            f
        }.getOrNull()
    }
}
