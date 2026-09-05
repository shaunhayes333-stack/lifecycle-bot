package com.lifecyclebot.network

import com.lifecyclebot.engine.ErrorLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * V5.0.6672 — Lightweight Kotlin LLM client for trade-decision validation
 * and exit-reasoning narration.
 *
 * Powered by a keyless fallback chain (Pollinations.ai → DuckDuckGo AI →
 * operator-supplied Groq/OpenRouter/Anthropic keys, in that order). No
 * API key is required for the bot to have LLM capability out of the box —
 * "LLM is gone" cannot recur because Pollinations is anonymous per-IP and
 * every Android device brings its own IP. Operator may still paste a
 * personal Anthropic key (sk-ant-…) into BotConfig.geminiApiKey and it
 * will be preferred whenever available.
 *
 * Two purpose-built helpers:
 *   validateTradeSignal(...)  → returns "PROCEED" / "BLOCK: <reason>" / "CAUTION: <note>"
 *   narrateExit(...)          → returns one short symbolic sentence describing the exit
 *
 * Neither is required for the bot to operate — both fail open: if every
 * provider errors out, validateTradeSignal returns "PROCEED" (let the
 * existing AI scoring layers decide) and narrateExit returns a default
 * string. No silent trade blocking on LLM downtime.
 */
object EmergentLlmClient {
    private const val TAG = "EmergentLlmClient"
    private const val DEFAULT_MODEL = "claude-sonnet-4-5-20250929"
    private const val DEFAULT_ENDPOINT = "https://api.anthropic.com/v1/messages"
    private const val DEFAULT_MAX_TOKENS = 256
    private const val ANTHROPIC_VERSION = "2023-06-01"

    @Volatile private var apiKey: String = ""
    @Volatile private var endpoint: String = DEFAULT_ENDPOINT
    @Volatile private var model: String = DEFAULT_MODEL
    // V5.0.6672 — always enabled. When apiKey is blank, runChat() routes to
    // KeylessLlmClient. When apiKey is set (sk-ant-…), we prefer the direct
    // Anthropic path for lower latency + higher quality.
    @Volatile private var enabled: Boolean = true

    private val httpClient: OkHttpClient by lazy {
        SharedHttpClient.builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    fun configure(
        apiKey: String,
        endpoint: String = DEFAULT_ENDPOINT,
        model: String = DEFAULT_MODEL,
    ) {
        this.apiKey = apiKey.trim()
        this.endpoint = endpoint.trim().ifBlank { DEFAULT_ENDPOINT }
        this.model = model.trim().ifBlank { DEFAULT_MODEL }
        this.enabled = true // V5.0.6672 — always on; falls back to keyless chain when no key
        if (this.apiKey.isNotBlank()) {
            ErrorLogger.info(TAG, "🧠 LLM enabled — Anthropic direct (model=${this.model}, endpoint=${this.endpoint.take(40)}…)")
        } else {
            ErrorLogger.info(TAG, "🧠 LLM enabled — keyless fallback chain (Pollinations → DuckDuckGo → operator keys)")
        }
    }

    fun isEnabled(): Boolean = enabled

    /**
     * Returns "PROCEED", "BLOCK: <short reason>", or "CAUTION: <note>".
     * Fails open to "PROCEED" on any error.
     */
    fun validateTradeSignal(
        symbol: String,
        side: String,
        confidence: Int,
        score: Int,
        traderTag: String,
        sizeSol: Double,
        recentPnlPct: Double,
    ): String {
        val sys = "You are a Solana trading bot's risk validator. Reply with EXACTLY one of: " +
                  "'PROCEED', 'BLOCK: <reason in 8 words>', or 'CAUTION: <note in 8 words>'. " +
                  "Block obviously bad signals (low conf + low score, recent string of losses, " +
                  "drain-pattern symbols). Otherwise PROCEED. Be terse."
        val user = "Signal: $symbol $side conf=$confidence% score=$score trader=$traderTag " +
                   "size=${"%.4f".format(sizeSol)}SOL recentPnL=${"%.1f".format(recentPnlPct)}%"
        return runChat(sys, user, maxTokens = 64) ?: "PROCEED"
    }

    /**
     * Returns a single short symbolic sentence narrating the exit. Fails
     * open to a default narration on any error.
     */
    fun narrateExit(
        symbol: String,
        reason: String,
        pnlPct: Double,
        holdMinutes: Int,
    ): String {
        val sys = "You narrate Solana trade exits. One short symbolic sentence (max 12 words), " +
                  "vivid imagery, no jargon. No quotes."
        val user = "Exit $symbol after ${holdMinutes}m, reason=$reason, pnl=${"%.1f".format(pnlPct)}%."
        return runChat(sys, user, maxTokens = 80) ?: "Exited $symbol on $reason (${"%.1f".format(pnlPct)}%)"
    }

    /**
     * V5.9.486 — fire-and-forget async narration. Invokes [callback] on a
     * background thread once the LLM responds (or with a fail-open default
     * if disabled / errored). Never blocks the caller.
     */
    fun narrateExitAsync(
        symbol: String,
        reason: String,
        pnlPct: Double,
        holdMinutes: Int,
        callback: (String) -> Unit,
    ) {
        Thread {
            try {
                val text = narrateExit(symbol, reason, pnlPct, holdMinutes)
                callback(text)
            } catch (_: Exception) {
                callback("Exited $symbol on $reason (${"%.1f".format(pnlPct)}%)")
            }
        }.apply { isDaemon = true }.start()
    }

    /** Generic single-turn chat. Returns null on any error (caller decides fallback). */
    fun runChat(system: String, user: String, maxTokens: Int = DEFAULT_MAX_TOKENS): String? {
        if (!enabled) return null
        // V5.0.6672 — when no Anthropic key is configured, route through the
        // keyless fallback chain (Pollinations → DuckDuckGo → operator keys).
        if (apiKey.isBlank()) {
            return KeylessLlmClient.runChat(system, user, maxTokens)
        }
        return try {
            val payload = JSONObject().apply {
                put("model", model)
                put("max_tokens", maxTokens)
                put("system", system)
                put("messages", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("content", user)
                }))
            }
            val req = Request.Builder()
                .url(endpoint)
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    ErrorLogger.warn(TAG, "LLM HTTP ${resp.code}: ${resp.body?.string()?.take(120)} — falling back to keyless")
                    // V5.0.6672 — on Anthropic failure, seamlessly fall back
                    // to the keyless chain instead of returning null. Keeps
                    // the pipeline speaking even if the operator key has
                    // rotated / rate-limited.
                    return KeylessLlmClient.runChat(system, user, maxTokens)
                }
                val body = resp.body?.string() ?: return null
                val j = JSONObject(body)
                val content = j.optJSONArray("content") ?: return null
                val first = content.optJSONObject(0) ?: return null
                first.optString("text", "").trim().ifBlank { null }
            }
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "LLM error: ${e.message?.take(120)} — falling back to keyless")
            KeylessLlmClient.runChat(system, user, maxTokens)
        }
    }
}
