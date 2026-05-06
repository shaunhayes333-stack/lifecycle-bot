package com.lifecyclebot.engine.voice

import android.content.Context
import com.lifecyclebot.engine.ErrorLogger
import java.io.File

/**
 * V5.9.495z18 — Sound effects library.
 *
 * Pre-defined event SFX descriptions. Each event has a text prompt that's
 * sent to the ElevenLabs Sound Generation API on first use; the resulting
 * MP3 is cached on disk and reused forever (lazy generation, zero credits
 * on subsequent plays).
 *
 * The prompts are deliberately concrete and short — ElevenLabs SFX is
 * sensitive to crisp descriptions.
 */
object VoiceSfxLibrary {

    private const val TAG = "VoiceSfxLibrary"
    private const val SFX_SUBDIR = "voice_sfx_cache"

    enum class Sfx(val prompt: String, val durationSec: Double = 1.5) {
        TP_HIT("Crisp cash register chime, satisfying ding, mechanical, short", 1.5),
        STOP_LOSS("Soft hollow descending tone, neutral, no drama", 1.2),
        BUY_CONFIRMED("Quick rocket whoosh launching upward, clean, modern UI sound", 1.4),
        SELL_CONFIRMED("Single positive UI confirmation chime, two-note ascending", 1.0),
        FDG_PASS_STRONG("Strong success chord, modern fintech app, ascending", 1.6),
        BRIDGE_OK("Short modem-style handshake completion, friendly", 1.3),
        BRIDGE_FAIL("Short stern alert buzz, single tone, not alarming", 1.2),
        WALLET_MISMATCH("Dry single radar ping with subtle echo", 1.2),
        UNMANAGED_TOKEN("Sonar pulse, low frequency, single hit", 1.3),
        RUG_AVOIDED("Kraken sea monster low rumble, far away, two seconds", 2.0),
        TOXIC_FREEZE("Cold ice forming sound, soft crystal", 1.6),
        MILESTONE("Triumphant fanfare, short, victorious", 2.5),
        DAILY_OPEN("Soft morning chime, two warm bells", 1.4),
        DAILY_CLOSE("Calm evening chime, single deep bell", 1.6),
        CRITICAL_FAULT("Robotic warning klaxon, two-tone, short blast", 1.5),
        TRADING_HALTED("Heavy steel door closing, mechanical thud", 1.6),
        SCAN_TICK("Tiny digital tick, very short, low volume", 0.4),
        REPLAY_INTRO("Vinyl record start, brief crackle, then go", 1.8),
        DEMO_OUTRO("Soft synth swell ending on a major chord", 2.5),
        VICTORY_HORN("Stadium air-horn, single short blast", 1.0),
        ;

        fun cacheKey(): String = "sfx_" + name.lowercase()
    }

    fun cacheDir(ctx: Context): File =
        File(ctx.filesDir, SFX_SUBDIR).apply { if (!exists()) mkdirs() }

    /** Returns cached file or null. */
    fun cached(ctx: Context, sfx: Sfx): File? {
        val f = File(cacheDir(ctx), "${sfx.cacheKey()}.mp3")
        return if (f.exists() && f.length() > 0) f else null
    }

    /**
     * Lazy generate this SFX via ElevenLabs Sound Generation API and cache to disk.
     * Returns the cached file or null on failure (caller should fall back to silence).
     */
    fun generateOrFetch(ctx: Context, apiKey: String, sfx: Sfx): File? {
        cached(ctx, sfx)?.let { return it }
        if (apiKey.isBlank()) {
            ErrorLogger.debug(TAG, "skipped SFX gen for ${sfx.name}: API key missing")
            return null
        }
        val params = ElevenLabsApi.SfxParams(
            prompt = sfx.prompt,
            durationSeconds = sfx.durationSec,
            promptInfluence = 0.55,
        )
        val res = ElevenLabsApi.sfx(apiKey, params)
        return when (res) {
            is ElevenLabsApi.Result.Ok -> {
                val out = File(cacheDir(ctx), "${sfx.cacheKey()}.mp3")
                runCatching {
                    out.writeBytes(res.value)
                    ErrorLogger.info(TAG, "🔊 SFX generated: ${sfx.name} (${res.value.size} bytes)")
                    out
                }.getOrNull()
            }
            is ElevenLabsApi.Result.Err -> {
                ErrorLogger.warn(TAG, "SFX gen failed [${sfx.name}]: ${res.code} ${res.message.take(120)}")
                null
            }
        }
    }
}
