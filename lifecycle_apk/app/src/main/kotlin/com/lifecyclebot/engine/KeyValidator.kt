package com.lifecyclebot.engine

import com.lifecyclebot.network.SharedHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
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
        val status: String = if (isLive) "HEALTHY" else "UNHEALTHY",
    )

    private val verdicts = ConcurrentHashMap<String, Verdict>()

    /** Known dead default keys — auto-flagged at startup. */
    private val knownDeadDefaults = setOf(
        "sk-emergent-431Dd41D3F186C0E0B",   // Emergent Gemini default — invalid
        "hive-pattern-learn",                // Helius placeholder
        "",                                  // empty key
    )

    /** V5.9.1340 — true only for a Helius key that can actually hit the paid
     *  Enhanced API (api.helius.xyz/v0). The free placeholder "hive-pattern-learn"
     *  works for plain RPC getHealth but 401s on every Enhanced endpoint, which is
     *  the source of the chronic helius 4xx storm in ApiHealthMonitor. Enhanced-API
     *  callers (BundleDetector, InsiderTrackerAI, HeliusCreatorHistory) must gate on
     *  this so they don't fire guaranteed-401 requests. Plain-RPC callers are
     *  unaffected — they keep using the free key. */
    fun isUsableEnhancedHeliusKey(key: String?): Boolean =
        !key.isNullOrBlank() && key !in knownDeadDefaults

    /** Bootstrap: pre-flag known dead defaults so consumers gate off immediately. */
    fun preflightConfig(
        geminiKey: String?,
        heliusKey: String?,
        groqKey: String?,
        birdeyeKey: String?,
        walletAddress: String? = null,
        jupiterKey: String? = null,
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
            markDead("birdeye", 0, "blank key — no probe", "BIRDEYE_KEY_MISSING")
        }
        if (!heliusKey.isNullOrBlank()) {
            Thread({ probeHeliusRpc(heliusKey, walletAddress.orEmpty()) }, "KeyValidator-HeliusProbe").apply { isDaemon = true }.start()
        } else {
            markDead("helius", 0, "blank key — no probe", "HELIUS_KEY_MISSING")
        }
        if (!groqKey.isNullOrBlank()) {
            Thread({ probeGroqConfiguredModel(groqKey) }, "KeyValidator-GroqProbe").apply { isDaemon = true }.start()
        }
        if (!jupiterKey.isNullOrBlank()) {
            recordResult("jupiter", success = true, httpStatus = 200, error = "configured")
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
            verdicts[key] = Verdict(true, System.currentTimeMillis(), httpStatus, null, status = "${service.uppercase()}_HEALTHY")
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

    private fun markDead(service: String, httpStatus: Int, error: String?, status: String = "${service.uppercase()}_UNHEALTHY") {
        val key = service.lowercase()
        verdicts[key] = Verdict(false, System.currentTimeMillis(), httpStatus, error, status = status)
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
        verdicts.mapValues { (_, v) -> Triple(v.isLive, v.lastHttp, listOf(v.status, v.lastError).filter { !it.isNullOrBlank() }.joinToString(" ")) }

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun probeHeliusRpc(apiKey: String, walletAddress: String) {
        val service = "helius"
        if (apiKey.isBlank() || apiKey in knownDeadDefaults) {
            markDead(service, 0, "missing/default key", "HELIUS_KEY_MISSING")
            return
        }
        val url = "https://mainnet.helius-rpc.com/?api-key=$apiKey"
        val client = SharedHttpClient.builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
        fun rpc(method: String, params: JSONArray = JSONArray()): Pair<Int, JSONObject?> {
            val payload = JSONObject().put("jsonrpc", "2.0").put("id", method).put("method", method).put("params", params)
            val req = Request.Builder().url(url).post(payload.toString().toRequestBody(jsonMedia)).build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.code == 401) { markDead(service, 401, body.take(120), "HELIUS_AUTH_FAILED_401"); return resp.code to null }
                if (resp.code == 403) { markDead(service, 403, body.take(120), "HELIUS_FORBIDDEN_403"); return resp.code to null }
                if (resp.code == 429) { markDead(service, 429, body.take(120), "HELIUS_RATE_LIMIT_429"); return resp.code to null }
                if (!resp.isSuccessful) { markDead(service, resp.code, body.take(120), "HELIUS_RPC_ERROR"); return resp.code to null }
                val json = try { JSONObject(body) } catch (_: Throwable) { JSONObject() }
                if (json.has("error")) { markDead(service, resp.code, json.opt("error").toString().take(160), "HELIUS_RPC_ERROR"); return resp.code to json }
                return resp.code to json
            }
        }
        try {
            val h = rpc("getHealth")
            if (h.second == null) return
            val bh = rpc("getLatestBlockhash")
            if (bh.second == null) return
            val wallet = walletAddress.ifBlank { "11111111111111111111111111111111" }
            val bal = rpc("getBalance", JSONArray().put(wallet))
            if (bal.second == null) return
            val tokParams = JSONArray().put(wallet).put(JSONObject().put("programId", "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")).put(JSONObject().put("encoding", "jsonParsed"))
            val toks = rpc("getTokenAccountsByOwner", tokParams)
            if (toks.second == null) return
            verdicts[service] = Verdict(true, System.currentTimeMillis(), 200, "getHealth/getLatestBlockhash/getBalance/getTokenAccountsByOwner ok", "HELIUS_HEALTHY")
            try { ApiHealthMonitor.record("helius", 200, 0) } catch (_: Throwable) {}
        } catch (e: java.net.SocketTimeoutException) {
            markDead(service, 0, e.message, "HELIUS_TIMEOUT")
        } catch (e: Throwable) {
            markDead(service, 0, e.message, "HELIUS_RPC_ERROR")
        }
    }

    private fun probeGroqConfiguredModel(apiKey: String) {
        val service = "groq"
        val model = "openai/gpt-oss-20b"
        val client = SharedHttpClient.builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build()
        val payload = JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "ping")))
            .put("max_tokens", 1)
        val req = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.code == 429) {
                    verdicts[service] = Verdict(false, System.currentTimeMillis(), 429, body.take(160), "GROQ_RATE_LIMIT_429_NARRATIVE_DEGRADED")
                    return
                }
                recordResult(service, resp.isSuccessful, resp.code, body.take(160))
            }
        } catch (t: Throwable) {
            recordResult(service, false, 0, t.message)
        }
    }
}
