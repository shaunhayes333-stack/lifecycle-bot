package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.855 — Passive API key validator.
 *
 * Problem: Several keys ship dead by default — operator-confirmed via live
 * probe (memory #146):
 *   - sk-emergent-431Dd41D3F186C0E0B (Gemini default) → API_KEY_INVALID
 *   - "hive-pattern-learn" (Helius placeholder) → 401 on Enhanced API
 *
 * When a consumer (BotBrain LLM analysis, NarrativeDetector, GeminiCopilot)
 * calls these keys, the request burns latency + a network round-trip on every
 * candidate just to fail. KeyValidator caches the live/dead verdict so
 * consumers can short-circuit cheaply.
 *
 * USAGE
 * -----
 *   if (!KeyValidator.isLive("gemini")) return null   // skip cleanly
 *   KeyValidator.recordResult("gemini", success=false, http=401)
 *
 * DOCTRINE
 * - Pure observability + caching. Doesn't make probes itself — relies on
 *   consumers to .recordResult() after every HTTP call.
 * - Optimistically returns TRUE for unknown services (fail-open) so a
 *   never-probed service isn't gated off by a cold cache.
 * - Auto-clears DEAD state after 30 min (in case operator rotates a key
 *   or service comes back).
 *
 * Self-heal flow:
 *   probe → recordResult(false, 401) → isLive=false for 30 min
 *   user updates key → BotConfig save → caller invalidates() → next call
 *   probes again → recordResult(true, 200) → isLive=true.
 */
object KeyValidator {
    private const val TAG = "KeyValidator"
    private const val DEAD_TTL_MS = 30 * 60_000L   // 30 min — re-probe after

    private data class Verdict(
        val isLive: Boolean,
        val timestampMs: Long,
        val lastHttp: Int,
        val lastError: String?,
    )

    private val verdicts = ConcurrentHashMap<String, Verdict>()

    /** Known dead default keys — auto-flagged at startup. */
    private val knownDeadDefaults = setOf(
        "sk-emergent-431Dd41D3F186C0E0B",   // Emergent Gemini default — invalid
        "hive-pattern-learn",                // Helius placeholder
        "",                                  // empty key
    )

    /** Bootstrap: pre-flag known dead defaults so consumers gate off immediately. */
    fun preflightConfig(
        geminiKey: String?,
        heliusKey: String?,
        groqKey: String?,
        birdeyeKey: String?,
    ) {
        if (geminiKey != null && geminiKey in knownDeadDefaults) {
            markDead("gemini", 401, "default placeholder key")
        }
        if (heliusKey != null && heliusKey in knownDeadDefaults) {
            markDead("helius", 401, "default placeholder key")
        }
        if (groqKey != null && groqKey.isBlank()) {
            markDead("groq", 0, "blank key — no probe")
        }
        if (birdeyeKey != null && birdeyeKey.isBlank()) {
            markDead("birdeye", 0, "blank key — no probe")
        }
    }

    /**
     * Returns false ONLY when we have an active DEAD verdict that's still
     * within TTL. Returns true for unknown services (fail-open).
     */
    fun isLive(service: String): Boolean {
        val v = verdicts[service.lowercase()] ?: return true
        val age = System.currentTimeMillis() - v.timestampMs
        if (!v.isLive && age < DEAD_TTL_MS) return false
        // DEAD verdict expired — clear and treat as unknown
        if (!v.isLive) verdicts.remove(service.lowercase())
        return true
    }

    /** Consumer reports an HTTP outcome. status<300 = live, >=400 = dead. */
    fun recordResult(service: String, success: Boolean, httpStatus: Int = 0, error: String? = null) {
        val key = service.lowercase()
        if (success) {
            // Live verdict — clear any dead flag
            verdicts[key] = Verdict(true, System.currentTimeMillis(), httpStatus, null)
        } else {
            // Auth-class failures (401/403/invalid_api_key) are sticky DEAD.
            // 5xx/timeout etc are transient — don't gate the service off for them.
            val isAuthFailure = httpStatus == 401 || httpStatus == 403 ||
                (error?.contains("invalid", ignoreCase = true) == true) ||
                (error?.contains("API_KEY", ignoreCase = true) == true) ||
                (error?.contains("forbidden", ignoreCase = true) == true)
            if (isAuthFailure) markDead(service, httpStatus, error)
            // else: leave verdict unchanged (treat as transient)
        }
    }

    private fun markDead(service: String, httpStatus: Int, error: String?) {
        val key = service.lowercase()
        verdicts[key] = Verdict(false, System.currentTimeMillis(), httpStatus, error)
        try {
            ErrorLogger.info(TAG, "🔑❌ $service flagged DEAD (http=$httpStatus, err=${error?.take(60)})")
        } catch (_: Throwable) {}
    }

    /** Explicitly clear verdict — call when operator rotates a key. */
    fun invalidate(service: String) {
        verdicts.remove(service.lowercase())
    }

    /** Snapshot for UniverseHealthActivity / debug surface. */
    fun snapshot(): Map<String, Triple<Boolean, Int, String?>> =
        verdicts.mapValues { (_, v) -> Triple(v.isLive, v.lastHttp, v.lastError) }
}
