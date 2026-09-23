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
 * Problem: Missing or placeholder keys must be rejected without burning a
 * network call for every candidate.
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
        "hive-pattern-learn",
        "",
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
        if (geminiKey != null && geminiKey.isBlank()) {
            // V5.0.7262 — say what is true. Operator 5.0.7261 had just
            // entered a new Gemini key and read "default placeholder key";
            // the saved config's Gemini field was EMPTY, which is a
            // different problem (the key did not persist, or was entered
            // in another field) and needs a different action.
            markDead("gemini", 0, "GEMINI_KEY_BLANK_IN_SAVED_CONFIG — Settings > Gemini/AI key field is empty; re-enter and Apply", "GEMINI_KEY_MISSING")
        } else if (geminiKey != null && geminiKey in knownDeadDefaults) {
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
        // V5.0.7262 — probe the KEY, not a generation.
        //
        // The old probe asked PRIMARY_MODEL for one token of "ping". On the
        // operator's 5.0.7261 device Groq answered http=400 "Failed to
        // generate JSON. Please adjust your prompt", and this marked the key
        // GROQ_UNHEALTHY — after which NarrativeDetector and every other
        // KeyValidator.isLive("groq") reader stopped using a key that was
        // authenticating perfectly well. A 400 from chat/completions is a
        // verdict on the prompt or the model; 401/403 are the verdicts on the
        // key. GET /openai/v1/models exercises exactly the thing this probe
        // is for — does this key authenticate — with no model, no prompt and
        // no daily-budget bucket in the way. KeylessLlmClient reads the same
        // endpoint for its model ladder (§7262).
        val client = SharedHttpClient.builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build()
        val req = Request.Builder()
            .url("https://api.groq.com/openai/v1/models")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "application/json")
            .get()
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.code == 429) {
                    verdicts[service] = Verdict(false, System.currentTimeMillis(), 429, body.take(160), "GROQ_RATE_LIMIT_429_NARRATIVE_DEGRADED")
                    return
                }
                val models7262 = try {
                    JSONObject(body).optJSONArray("data")?.length() ?: 0
                } catch (_: Throwable) { 0 }
                recordResult(
                    service, resp.isSuccessful, resp.code,
                    if (resp.isSuccessful) "models=$models7262 (auth-only probe, §7262)" else body.take(160),
                )
            }
        } catch (t: Throwable) {
            recordResult(service, false, 0, t.message)
        }
    }
}
