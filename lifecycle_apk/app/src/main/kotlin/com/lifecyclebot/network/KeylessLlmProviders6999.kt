package com.lifecyclebot.network

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.HealthAwareHttp
import com.lifecyclebot.engine.PipelineHealthCollector
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * V5.0.6999 — a genuinely keyless LLM council, discovered at runtime.
 *
 * WHY THIS EXISTS
 * ===============
 * Operator, twice: "its meant to be free and keyless and multi llm sourced ...
 * the aate stack should never be data or llm dry ever", then — after V5.0.6996
 * shipped — "llm is still gone dude not good enough. I asked you to find and
 * install and implement new free keyless providers!!!"
 *
 * They are right, and the reason 6996 did not land is worth stating plainly
 * rather than patching over: I added two Pollinations members and could not
 * check whether that service still answers anonymously. It had already moved
 * behind a paid tier once (that is documented in KeylessLlmClient's own 6678
 * header). So the fix was a guess, and — this is the part that made it
 * unfixable — the guess was UNMEASURABLE. Every provider in that class calls
 * `httpClient.newCall(...)` directly, so not one of them reports to
 * ApiHealthMonitor. When the operator's snapshot showed no `pollinations` row,
 * that was equally consistent with "never called", "called and failed" and
 * "worked fine", and nothing in the log could separate them.
 *
 * That is the same defect this codebase keeps finding in its own reasoning
 * layers (V5.0.6988's census, V5.0.6983's loop tick): a component whose health
 * is inferred rather than observed. A council you cannot see is a council you
 * cannot repair.
 *
 * SO THIS FILE CHANGES TWO THINGS
 * ===============================
 * 1. EVERY call goes through HealthAwareHttp with its own host label. The
 *    council now appears in the same health table as every data provider, with
 *    real sr/4xx/5xx counts. The next snapshot answers "is the LLM alive" as a
 *    measurement instead of an inference.
 *
 * 2. NO HARD-CODED MODEL IDS. The surface is OpenAI-compatible, so it publishes
 *    its own catalogue at `GET /v1/models`. Asking is strictly better than
 *    guessing a model string I cannot verify from here: the device finds out
 *    what is actually served, and a model being renamed or retired upstream
 *    stops being an outage. The seed list below is only what to try if the
 *    catalogue itself is unreachable.
 *
 * THE PROVIDER
 * ============
 * OVHcloud AI Endpoints exposes an OpenAI-compatible surface with a free
 * ANONYMOUS tier: no API key, no account, no signup — the operator's standing
 * requirement. Its published anonymous limit is ~2 requests per minute per IP
 * *per model*, which is the useful detail: distinct models carry distinct
 * budgets, so rotating across the catalogue turns one anonymous endpoint into
 * several independent council members rather than one throttled voice.
 *
 * DOCTRINE: fail-soft, never throws, returns null when it has nothing. A silent
 * council must degrade the caller to its safe default, never take the bot down.
 */
object KeylessLlmProviders6999 {

    private const val TAG = "KeylessLlm6999"

    /** OpenAI-compatible, anonymous tier. No key, no account. */
    private const val OVH_BASE = "https://oai.endpoints.kepler.ai.cloud.ovh.net/v1"
    private const val OVH_CHAT = "$OVH_BASE/chat/completions"
    private const val OVH_MODELS = "$OVH_BASE/models"

    /** Host label for ApiHealthMonitor — one row per surface, visible in the snapshot. */
    const val HOST_OVH = "ovh_llm_keyless"

    /**
     * Only used if the live catalogue cannot be read. Deliberately short and
     * deliberately not load-bearing: discovery is the real path.
     */
    private val SEED_MODELS = listOf(
        "Meta-Llama-3_3-70B-Instruct",
        "Mistral-Nemo-Instruct-2407",
        "Qwen3-32B",
    )

    /** Catalogue refresh interval. Models change rarely; asking hourly is plenty. */
    private const val CATALOG_TTL_MS = 6L * 60L * 60L * 1000L

    private val http by lazy {
        SharedHttpClient.builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    @Volatile private var catalog: List<String> = emptyList()
    @Volatile private var catalogAtMs: Long = 0L
    @Volatile private var rotation: Int = 0

    /**
     * The live model catalogue, refreshed at most every CATALOG_TTL_MS.
     *
     * A failed discovery falls back to the seed list rather than returning
     * empty — "I could not read the menu" must not mean "there is no food".
     */
    fun models(): List<String> {
        val now = System.currentTimeMillis()
        val cached = catalog
        if (cached.isNotEmpty() && now - catalogAtMs < CATALOG_TTL_MS) return cached
        val fetched = try { fetchCatalog() } catch (_: Throwable) { emptyList() }
        return if (fetched.isNotEmpty()) {
            catalog = fetched
            catalogAtMs = now
            try {
                PipelineHealthCollector.labelInc("LLM_KEYLESS_CATALOG_OK_6999")
                ErrorLogger.info(TAG, "keyless LLM catalogue: ${fetched.size} models discovered")
            } catch (_: Throwable) {}
            fetched
        } else {
            try { PipelineHealthCollector.labelInc("LLM_KEYLESS_CATALOG_SEEDED_6999") } catch (_: Throwable) {}
            if (cached.isNotEmpty()) cached else SEED_MODELS
        }
    }

    private fun fetchCatalog(): List<String> {
        val req = Request.Builder().url(OVH_MODELS)
            .header("Accept", "application/json")
            .header("User-Agent", "lifecycle-bot-android/6.0")
            .get().build()
        HealthAwareHttp.execute(http, req, host = HOST_OVH).use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
            val out = ArrayList<String>(data.length())
            for (i in 0 until data.length()) {
                val id = data.optJSONObject(i)?.optString("id", "")?.trim().orEmpty()
                if (id.isNotEmpty()) out.add(id)
            }
            return out
        }
    }

    /**
     * One chat turn against the next model in rotation.
     *
     * Rotation is what makes this a council rather than a single voice: the
     * anonymous budget is per-model, so consecutive calls landing on different
     * models do not contend for the same rate limit.
     */
    fun chat(system: String, user: String, maxTokens: Int): String? {
        val m = models()
        if (m.isEmpty()) return null
        val idx = ((rotation++) % m.size + m.size) % m.size
        return chatWithModel(m[idx], system, user, maxTokens)
    }

    fun chatWithModel(model: String, system: String, user: String, maxTokens: Int): String? {
        val payload = JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            put("temperature", 0.2)
            put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)),
            )
        }
        val req = Request.Builder().url(OVH_CHAT)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", "lifecycle-bot-android/6.0")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            // V5.0.7016 — waive the lockout only inside runChat's single forced
            // attempt, and tell an ApiBackoff refusal apart from a real empty
            // reply. Without the first half this member — the council's best
            // performer at 34% in the operator's 5.0.7012 table — would stay
            // locked out during the one call that exists to break the deadlock.
            // V5.0.7026 — the OVH member needs BOTH waivers too, and it is the
            // one that matters most: the operator's table has it at 28% with 10
            // successes, the best of the eight, while the chat saw none of them.
            // Without the probe header HostCircuitInterceptor blocks the forced
            // attempt before the wire and the waiver above achieves nothing.
            val forced7026 = KeylessLlmClient.isForcedAttempt7016()
            val outbound7026 = if (forced7026) {
                req.newBuilder().header(HostCircuitInterceptor.PROBE_HEADER_6976, "1").build()
            } else req
            HealthAwareHttp.execute(
                http, outbound7026, host = HOST_OVH,
                allowDuringLockout = forced7026,
            ).use { resp ->
                if (!resp.isSuccessful) {
                    if (HostCircuitInterceptor.isSyntheticBlock(resp)) {
                        try {
                            PipelineHealthCollector.labelInc("LLM_KEYLESS_OVH_REFUSED_BY_OWN_BACKOFF_7016")
                        } catch (_: Throwable) {}
                    }
                    return null
                }
                val body = resp.body?.string() ?: return null
                val text = JSONObject(body).optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content", "")?.trim()
                if (text.isNullOrBlank()) null else {
                    try { PipelineHealthCollector.labelInc("LLM_KEYLESS_OVH_OK_6999") } catch (_: Throwable) {}
                    text
                }
            }
        } catch (t: Throwable) {
            ErrorLogger.debug(TAG, "ovh chat failed model=$model: ${t.message?.take(140)}")
            null
        }
    }
}
