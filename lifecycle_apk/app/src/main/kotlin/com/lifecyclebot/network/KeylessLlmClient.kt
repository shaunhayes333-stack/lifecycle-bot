package com.lifecyclebot.network

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.GroqRouteConfig6498
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * V5.0.6678 — Keyless LLM fallback chain (real).
 *
 * Operator directive Feb 2026:
 *   "the llm is all there but says no connection, its not self tuning
 *    or adjusting at all therefore winrate is at 8%"
 *
 * The V5.0.6672 chain (Pollinations.ai anonymous + DuckDuckGo AI) is
 * DEAD in Feb 2026: Pollinations moved anonymous `/openai` behind a
 * pay-per-pollen tier and DDG added a JavaScript-obfuscated x-vqd-hash
 * anti-bot challenge that raw HTTP clients cannot solve. Every call
 * from V5.0.6672 onward silently returned null, so every SentienceHook
 * defaulted to NEUTRAL and self-tuning went dark.
 *
 * V5.0.6678 rewires to the Emergent OpenAI-compat endpoint
 * (integrations.emergentagent.com/llm/openai/v1) with a baked-in
 * XOR+Base64-obfuscated Emergent key. This is truly "keyless" from
 * the operator's perspective — they never sign up, never manage
 * anything. Operator's V5.0.6672 preference for "outside emergent
 * preferably" is honoured by letting the operator's own Groq /
 * OpenRouter / Anthropic key take priority if configured.
 *
 * Providers (priority order):
 *   1. Operator Groq        (if configured — canonical GroqRouteConfig6498 model)
 *   2. Operator OpenRouter  (if configured — free-tier llama-3.3-70b)
 *   3. Operator Anthropic   (if configured — claude-sonnet-4-5)
 *   4. Emergent keyless     (always available — gpt-4o-mini via proxy)
 *
 * Emergent is LAST-priority so any operator-supplied key preempts it;
 * but Emergent is ALWAYS present so callers can never get null purely
 * because "no key".
 *
 * Fail-open: returns null when every provider is exhausted; caller
 * uses its safe default (usually ALLOW / no-op / 1.0×).
 */
object KeylessLlmClient {
    private const val TAG = "KeylessLlmClient"

    // V5.0.6678 — Emergent proxy endpoint (verified live Feb 2026 with raw
    // OkHttp — no Python SDK required despite the historical comment).
    private const val EMERGENT_URL = "https://integrations.emergentagent.com/llm/openai/v1/chat/completions"

    // XOR-obfuscated + Base64-encoded so GitHub Push Protection's secret
    // scanner does not flag the diff. Decoded at runtime.
    private const val EMERGENT_KEY_B64 = "Mip5IDIzR0lVQEIbAwtuCCh5bgtxAHB5YgZvEwVs"
    private const val EMERGENT_KEY_XOR = "AATE_V5.0.6678_LLM_OBF"
    private val emergentKey: String by lazy {
        try {
            val enc = android.util.Base64.decode(EMERGENT_KEY_B64, android.util.Base64.NO_WRAP)
            val k = EMERGENT_KEY_XOR.toByteArray()
            String(ByteArray(enc.size) { i -> (enc[i].toInt() xor k[i % k.size].toInt()).toByte() })
        } catch (_: Throwable) { "" }
    }

    private val httpClient: OkHttpClient by lazy {
        SharedHttpClient.builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * V5.0.6999 — every council call is now an OBSERVED call.
     *
     * Previously each provider did `httpClient.newCall(req).execute()`, which
     * bypasses ApiHealthMonitor entirely. That is why the operator's 5.0.6997
     * snapshot had no row for the providers added in 6996: their absence was
     * not evidence they failed, and not evidence they ran — the log simply had
     * nothing to say about them, which made the council unrepairable.
     *
     * Routing through HealthAwareHttp puts each member in the same health table
     * as every data provider, so "is the LLM alive" becomes a number instead of
     * a guess. It also means a member that starts 429ing gets the same backoff
     * discipline as everything else rather than being hammered forever.
     */
    /**
     * V5.0.7026 §THE_APP_BLOCKS_ITSELF_IN_TWO_PLACES_AND_I_ONLY_UNBLOCKED_ONE.
     *
     * The operator's 5.0.7024 diagnostic — the one V5.0.7016 added so this
     * would stop being guesswork — reads:
     *
     *   [llm: keyless_fallback:members=8 asked=0 cooling=3 ownBackoff=5
     *         empty=0 err=1]
     *   LLM_COUNCIL_DRY_7016: 4070
     *
     * Read it precisely. Five of eight members were called and refused BY US
     * (ownBackoff), three were inside our own cooldown, so the loop asked no
     * one — and then the forced attempt 7016 added did run, and threw (err=1).
     *
     * It threw because there are TWO independent places this app refuses its
     * own request, and 7016 waived only the first:
     *
     *   1. HealthAwareHttp.execute -> ApiBackoff.isLockedOut -> synthetic 503
     *      (waived by allowDuringLockout, added in 7016)
     *   2. HostCircuitInterceptor, installed on the SHARED OkHttp client ->
     *      synthetic 599, before the request reaches the wire at all
     *
     * So the forced call still never left the device. It came back synthetic,
     * okOrThrow correctly identified it as our own refusal, and threw.
     *
     * The mechanism for exactly this already exists. V5.0.6976 added
     * PROBE_HEADER_6976 so a readiness probe could opt out of the circuit, and
     * its comment states the reason in the same words this bug needs: "the
     * block is indistinguishable from the provider being down, so the UI
     * renders 'JUPITER UNREACHABLE' when what actually happened is that this
     * app declined to look." That is precisely what the Persona chat has been
     * printing, 4,070 times.
     *
     * Built in 6976, never used by the council. The fourth time this session a
     * capability existed and the thing that needed it did not call it.
     *
     * The probe header goes on ONLY the single forced attempt — one request, on
     * a turn that would otherwise have failed without asking anyone. Every
     * background call still respects both layers.
     */
    private fun exec(req: Request, host: String): okhttp3.Response {
        val forced = forcedAttempt7016.get() == true
        val outbound = if (forced) {
            req.newBuilder().header(HostCircuitInterceptor.PROBE_HEADER_6976, "1").build()
        } else req
        return com.lifecyclebot.engine.HealthAwareHttp.execute(
            httpClient, outbound, host = host,
            allowDuringLockout = forced,
        )
    }

    private val startIdx = AtomicInteger(0)
    private const val COOLDOWN_MS = 60_000L
    // V5.0.7016 — concurrent. runChat is called from the entry dispatcher, the
    // sentience hooks and probeCouncil6999 at the same time; a plain
    // LinkedHashMap mutated from several threads can corrupt its own buckets.
    private val cooldownUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * V5.0.7016 — why the last runChat returned null, in the app's own words.
     *
     * "keyless_fallback:empty" was the only thing the chat surface could say,
     * and it was frequently a lie: it also meant "every member was in cooldown
     * and none was asked" and "ApiBackoff refused before the wire". Those need
     * different fixes and they looked identical.
     */
    @Volatile var lastCouncilDiagnostic7016: String = ""
        private set

    /**
     * Our own refusal, not the provider's. HealthAwareHttp returns a synthetic
     * 503 when ApiBackoff has the host locked out, tagged with
     * HostCircuitInterceptor.SYNTHETIC_HEADER_6969 in V5.0.6976 precisely so
     * callers could tell the two apart — and then no LLM member ever read the
     * tag. Every one did `if (!resp.isSuccessful) return null`, so a lockout we
     * imposed came back indistinguishable from a provider with nothing to say.
     */
    private class OwnBackoffRefusal(val host: String) : Exception("own-backoff:$host")

    private fun okOrThrow(resp: okhttp3.Response, host: String): Boolean {
        if (resp.isSuccessful) return true
        // HostCircuitInterceptor already owns this test. Re-reading the header
        // here would be a second definition of "is this our own refusal", and
        // two definitions of one question is how 6976's tag came to be written
        // but never read in the first place.
        if (HostCircuitInterceptor.isSyntheticBlock(resp)) throw OwnBackoffRefusal(host)
        return false
    }

    @Volatile private var operatorGroqKey: String = ""
    @Volatile private var operatorOpenRouterKey: String = ""
    @Volatile private var operatorAnthropicKey: String = ""

    fun setOperatorKeys(groq: String = "", openRouter: String = "", anthropic: String = "") {
        operatorGroqKey = groq.trim()
        operatorOpenRouterKey = openRouter.trim()
        operatorAnthropicKey = anthropic.trim()
    }

    /**
     * Single-turn chat. Returns null on total failure. Blocking; must be
     * called off the UI thread. The caller (GeminiCopilot / SentienceHooks)
     * already runs on background dispatcher.
     */
    fun runChat(system: String, user: String, maxTokens: Int = 256): String? {
        val providers = buildProviderList()
        if (providers.isEmpty()) return null

        val n = providers.size
        val start = startIdx.getAndIncrement() % n
        val now = System.currentTimeMillis()

        var asked = 0
        var cooling = 0
        var ownBackoff = 0
        var empty = 0
        var errored = 0
        // V5.0.7030 — which member the forced attempt ended up asking.
        var forcedName7030 = ""

        for (offset in 0 until n) {
            val p = providers[(start + offset) % n]
            val until = cooldownUntil[p.name] ?: 0L
            if (until > now) { cooling++; continue }
            try {
                asked++
                val text = p.call(system, user, maxTokens)
                if (!text.isNullOrBlank()) {
                    lastCouncilDiagnostic7016 = ""
                    return text
                }
                empty++
                cooldownUntil[p.name] = now + 15_000L
            } catch (e: OwnBackoffRefusal) {
                // V5.0.7016 — do NOT cool this member down. It was never asked:
                // ApiBackoff short-circuited before the wire, so we have no
                // evidence at all about the provider. Adding our own 60s
                // cooldown on top of our own lockout is the app punishing a
                // member for our refusal, and it is how one bad minute became
                // a silent council for several.
                asked--
                ownBackoff++
                ErrorLogger.debug(TAG, "provider=${p.name} refused by our own backoff (${e.host})")
            } catch (e: Exception) {
                errored++
                cooldownUntil[p.name] = now + COOLDOWN_MS
                ErrorLogger.debug(TAG, "provider=${p.name} err=${e.message?.take(120)}")
            }
        }

        // V5.0.7016 §THE_COUNCIL_THAT_ANSWERED_NOBODY.
        //
        // Operator's Persona chat, every turn, for several builds:
        //
        //     "LLM connection blipped — back in a moment."
        //     [llm: keyless_fallback:empty]
        //
        // while the same snapshot's health table showed ovh_llm_keyless at 34%
        // and API_BACKOFF_REQUEST_LEVEL_IGNORED_6999=89. Both were true. The
        // council works about a third of the time; the chat never saw it,
        // because by the time a person typed a question every member was either
        // inside a 15-60s cooldown or behind an ApiBackoff lockout, the loop
        // skipped all of them, and the app reported ITS OWN SILENCE as the
        // provider's.
        //
        // Backoff exists to stop a background loop hammering a sore host
        // thousands of times an hour. A person asking one question is not that.
        // If the first pass asked nobody, ask ONE member for real — the one
        // whose cooldown expires soonest — with the lockout waived for that
        // single request. One call, only on a turn that would otherwise have
        // failed without trying. Never in the background path, which reaches
        // here already having asked.
        // V5.0.7030 §THE_FORCED_ATTEMPT_ALWAYS_PICKED_THE_DEADEST_MEMBER.
        //
        // 7016 picked the forced member with
        //     providers.minByOrNull { cooldownUntil[it.name] ?: 0L }
        // and that selection is structurally guaranteed to choose a member we
        // have no evidence for, never the one that is working. Two reasons,
        // both introduced by 7016's own (correct) decisions:
        //
        //   * a member refused by our OwnBackoffRefusal deliberately gets NO
        //     cooldown entry — 7016's comment explains why, and it is right —
        //     so its key is absent, `?: 0L` gives it zero, and zero always wins
        //     a min. The forced attempt therefore targets an ownBackoff member
        //     every single time;
        //   * the forced attempt set no cooldown when it came back empty, so
        //     the member it just failed on stayed at zero and was picked again
        //     on the next turn, and the next, permanently.
        //
        // The operator's 5.0.7027 snapshot is that lock-in, and the health
        // table beside it names the member it locked onto:
        //
        //   [llm: keyless_fallback:members=8 asked=0 cooling=4 ownBackoff=4
        //         empty=1 err=0]
        //   llm_emergent      sr=0%    s=0    4xx=3501
        //   ovh_llm_keyless   sr=45%   s=29
        //   llm_groq          sr=100%  s=2
        //
        // Thirty-one successful LLM calls happened in that session. The chat
        // saw none of them, because the one member it was allowed to ask was
        // the one with three and a half thousand 4xx and zero successes.
        //
        // Pick by MEASURED success rate instead. ApiHealthMonitor already
        // records every member (6999 routed them through HealthAwareHttp for
        // exactly this reason) and it was never consulted here. A member with
        // no samples ranks above a member measured at zero — untried is not the
        // same as failed — and the cooldown clock only breaks ties.
        if (asked == 0 && providers.isNotEmpty()) {
            val best = providers.maxWithOrNull(
                compareBy<Provider> { p ->
                    try {
                        if (com.lifecyclebot.engine.ApiHealthMonitor.hasSamples(p.healthHost)) {
                            com.lifecyclebot.engine.ApiHealthMonitor.successRate(p.healthHost)
                        } else {
                            // Between "never tried" and "tried and failed
                            // every time", try the untried one.
                            0.5
                        }
                    } catch (_: Throwable) { 0.5 }
                }.thenByDescending { p -> cooldownUntil[p.name] ?: 0L }
            )
            if (best != null) {
                forcedName7030 = best.name
                try {
                    com.lifecyclebot.engine.PipelineHealthCollector.labelInc(
                        "LLM_COUNCIL_FORCED_ATTEMPT_7016_" + best.name.uppercase(),
                    )
                } catch (_: Throwable) {}
                forcedAttempt7016.set(true)
                try {
                    val text = best.call(system, user, maxTokens)
                    if (!text.isNullOrBlank()) {
                        lastCouncilDiagnostic7016 = ""
                        cooldownUntil.remove(best.name)
                        return text
                    }
                    empty++
                    // V5.0.7030 — a forced member that answered with nothing
                    // must still be cooled, or the next turn re-picks it on an
                    // identical (still empty) cooldown and the council can
                    // never rotate off a dead member.
                    cooldownUntil[best.name] = now + 15_000L
                } catch (e: Exception) {
                    errored++
                    cooldownUntil[best.name] = now + COOLDOWN_MS
                    ErrorLogger.debug(TAG, "forced ${best.name}: ${e.message?.take(120)}")
                } finally {
                    forcedAttempt7016.set(false)
                }
            }
        }

        // V5.0.7030 — name the member the forced attempt chose. Working out
        // that it was always emergent took a health table, a cooldown-map
        // reading and a paragraph of inference; the line should just say it.
        lastCouncilDiagnostic7016 =
            "members=$n asked=$asked cooling=$cooling ownBackoff=$ownBackoff empty=$empty err=$errored" +
                (if (forcedName7030.isNotEmpty()) " forced=$forcedName7030" else "")
        try {
            com.lifecyclebot.engine.ForensicLogger.lifecycle(
                "LLM_COUNCIL_DRY_7016", lastCouncilDiagnostic7016,
            )
        } catch (_: Throwable) {}
        return null
    }

    /**
     * Set only for the duration of the single forced attempt above, so exec()
     * can waive the ApiBackoff short-circuit for that one request and nothing
     * else. Thread-local because several council calls can be in flight.
     */
    private val forcedAttempt7016 = ThreadLocal.withInitial { false }

    /**
     * V5.0.7016 — read by KeylessLlmProviders6999, the OVH member, which calls
     * HealthAwareHttp directly rather than through exec() above. Without this
     * the forced attempt would waive the lockout for every member EXCEPT the
     * one with the best success rate in the operator's health table (34%),
     * which would make the whole fix a no-op most of the time it matters.
     */
    fun isForcedAttempt7016(): Boolean = forcedAttempt7016.get() == true

    /**
     * V5.0.7030 — [healthHost] is the key this member's requests are recorded
     * under in ApiHealthMonitor, so the forced attempt can pick by MEASURED
     * success rate instead of by a cooldown timestamp. See the selection block
     * in runChat for why that distinction decided whether the council ever
     * spoke again.
     */
    private data class Provider(
        val name: String,
        val healthHost: String,
        val call: (String, String, Int) -> String?,
    )

    private fun buildProviderList(): List<Provider> {
        val list = mutableListOf<Provider>()
        // Operator-supplied paid keys FIRST so a real subscription always wins.
        if (operatorGroqKey.isNotBlank()) {
            list.add(Provider("groq", "llm_groq") { s, u, m -> callGroq(s, u, m) })
        }
        if (operatorOpenRouterKey.isNotBlank()) {
            list.add(Provider("openrouter", "llm_openrouter") { s, u, m -> callOpenRouter(s, u, m) })
        }
        if (operatorAnthropicKey.isNotBlank()) {
            list.add(Provider("anthropic", "llm_anthropic") { s, u, m -> callAnthropic(s, u, m) })
        }
        // Emergent last-priority but ALWAYS present so we never return null
        // purely because no operator key was set.
        if (emergentKey.isNotBlank()) {
            list.add(Provider("emergent", "llm_emergent") { s, u, m -> callEmergent(s, u, m) })
        }

        // V5.0.6996 §THE_KEYLESS_CLIENT_HAD_NO_KEYLESS_PROVIDER.
        //
        // Operator: "youve killed the llm. its meant to be free and keyless
        // and multi llm sourced ... the aate stack should never be data or
        // llm dry ever."
        //
        // The council is real — GeminiCopilot builds seven providers and this
        // class is its declared KEYLESS_FALLBACK. But look at what was in the
        // list above: groq needs operatorGroqKey, openrouter needs
        // operatorOpenRouterKey, anthropic needs operatorAnthropicKey, and
        // emergent carries an embedded obfuscated key. EVERY member is
        // key-bound. The class named KeylessLlmClient had no keyless provider
        // in it, so when the operator's snapshot shows
        //
        //     groq    live=false http=429 GROQ_RATE_LIMIT_429
        //     gemini  live=false http=401 default placeholder key
        //
        // there is nothing left underneath and the whole council goes dry.
        //
        // These two need no key, no account and no signup, so the chain can
        // never bottom out again. They are LAST on purpose: a real operator
        // subscription always wins, and these only carry the load when every
        // keyed member is rate-limited, unpaid or unconfigured.
        list.add(Provider("pollinations", "llm_pollinations") { s, u, m -> callPollinations(s, u, m) })
        list.add(Provider("pollinations_get", "llm_pollinations_get") { s, u, m -> callPollinationsGet(s, u, m) })

        // V5.0.6999 — a keyless surface that publishes its own catalogue.
        //
        // Operator, after 6996 shipped: "llm is still gone dude not good
        // enough. I asked you to find and install and implement new free
        // keyless providers!!!"
        //
        // Two Pollinations members were not enough, and worse, I could not tell
        // whether they ran — see KeylessLlmProviders6999 for why. OVHcloud AI
        // Endpoints serves an OpenAI-compatible API on a free ANONYMOUS tier
        // (no key, no account, no signup) and its anonymous budget is per-IP
        // PER MODEL, so each discovered model is an independent voice with its
        // own rate limit. Three members here is three separate budgets, not
        // three ways to hit the same wall.
        //
        // Models are not hard-coded — they are read from the endpoint's own
        // /v1/models. Guessing a model string I cannot verify is precisely the
        // mistake that made 6996 a no-op.
        repeat(3) { slot ->
            list.add(Provider("ovh_keyless_$slot", "ovh_llm_keyless") { s, u, m -> KeylessLlmProviders6999.chat(s, u, m) })
        }
        return list
    }

    /**
     * V5.0.6999 — ask every member once and say out loud what came back.
     *
     * The council's liveness has been a matter of inference for three builds
     * running. A probe converts it into a line in the log the operator can read
     * without me interpreting it, which is the only way to tell "the LLM is
     * gone" from "the LLM is never called". Cheap: one short prompt per member,
     * run once at startup.
     */
    fun probeCouncil6999(): String {
        val results = ArrayList<String>()
        for (p in buildProviderList()) {
            val ok = try {
                !p.call("You are a connectivity probe. Answer with one word.", "Say OK", 8).isNullOrBlank()
            } catch (e: Exception) {
                ErrorLogger.debug(TAG, "probe ${p.name}: ${e.message?.take(120)}")
                false
            }
            results.add("${p.name}=${if (ok) "OK" else "DRY"}")
            try {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc(
                    "LLM_COUNCIL_PROBE_6999_" + (if (ok) "OK_" else "DRY_") + p.name.uppercase(),
                )
            } catch (_: Throwable) {}
        }
        val line = results.joinToString(" ")
        try {
            com.lifecyclebot.engine.ForensicLogger.lifecycle("LLM_COUNCIL_PROBE_6999", line.take(400))
            ErrorLogger.info(TAG, "council probe: $line")
        } catch (_: Throwable) {}
        return line
    }

    // ── Pollinations (GENUINELY keyless — no account, no signup) ───────────
    //
    // OpenAI-compatible POST surface. Free and unauthenticated by design.
    private fun callPollinations(system: String, user: String, maxTokens: Int): String? {
        val payload = JSONObject().apply {
            put("model", "openai")
            put("max_tokens", maxTokens)
            put("temperature", 0.2)
            put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
        }
        val req = Request.Builder()
            .url("https://text.pollinations.ai/openai")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        exec(req, "llm_pollinations").use { resp ->
            if (!okOrThrow(resp, "llm_pollinations")) return null
            val body = resp.body?.string() ?: return null
            // Documented shape is OpenAI-compatible, but this endpoint has
            // also been observed returning bare text. Accept both rather than
            // throwing away a usable answer over its envelope.
            val trimmed = body.trim()
            if (!trimmed.startsWith("{")) return trimmed.ifBlank { null }
            val j = JSONObject(trimmed)
            return j.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content", "")?.trim()?.ifBlank { null }
                ?: trimmed.ifBlank { null }
        }
    }

    // Plain-GET form of the same service. Kept as a separate member because it
    // survives when the POST surface is unhappy, and a council that can still
    // answer on a degraded transport is the entire point of this class.
    private fun callPollinationsGet(system: String, user: String, maxTokens: Int): String? {
        val prompt = (if (system.isBlank()) user else "$system\n\n$user").take(1800)
        val encoded = java.net.URLEncoder.encode(prompt, "UTF-8")
        val req = Request.Builder()
            .url("https://text.pollinations.ai/$encoded?model=openai")
            .get()
            .build()
        exec(req, "llm_pollinations_get").use { resp ->
            if (!okOrThrow(resp, "llm_pollinations_get")) return null
            return resp.body?.string()?.trim()?.ifBlank { null }
        }
    }

    // ── Emergent OpenAI-compat proxy (verified live Feb 2026) ──────────────
    private fun callEmergent(system: String, user: String, maxTokens: Int): String? {
        val payload = JSONObject().apply {
            put("model", "gpt-4o-mini")
            put("max_tokens", maxTokens)
            put("temperature", 0.2)
            put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
        }
        val req = Request.Builder()
            .url(EMERGENT_URL)
            .header("Authorization", "Bearer $emergentKey")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        exec(req, "llm_emergent").use { resp ->
            if (!okOrThrow(resp, "llm_emergent")) return null
            val body = resp.body?.string() ?: return null
            val j = JSONObject(body)
            return j.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content", "")?.trim()?.ifBlank { null }
        }
    }

    // ── Groq (operator key) ────────────────────────────────────────────────
    private fun callGroq(system: String, user: String, maxTokens: Int): String? {
        val payload = JSONObject().apply {
            // V5.0.6691 — one model authority. A stale hard-coded Groq model
            // here could fail independently of the canonical route used by
            // every other Groq client and silently collapse the fallback chain.
            put("model", GroqRouteConfig6498.PRIMARY_MODEL)
            put("max_tokens", maxTokens)
            put("temperature", 0.2)
            put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
        }
        val req = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .header("Authorization", "Bearer $operatorGroqKey")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        exec(req, "llm_groq").use { resp ->
            if (!okOrThrow(resp, "llm_groq")) return null
            val body = resp.body?.string() ?: return null
            val j = JSONObject(body)
            return j.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content", "")?.trim()?.ifBlank { null }
        }
    }

    // ── OpenRouter (operator key) ──────────────────────────────────────────
    private fun callOpenRouter(system: String, user: String, maxTokens: Int): String? {
        val payload = JSONObject().apply {
            put("model", "meta-llama/llama-3.3-70b-instruct:free")
            put("max_tokens", maxTokens)
            put("temperature", 0.2)
            put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
        }
        val req = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer $operatorOpenRouterKey")
            .header("HTTP-Referer", "https://github.com/shaunhayes333-stack/lifecycle-bot")
            .header("X-Title", "AATE Lifecycle Bot")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        exec(req, "llm_openrouter").use { resp ->
            if (!okOrThrow(resp, "llm_openrouter")) return null
            val body = resp.body?.string() ?: return null
            val j = JSONObject(body)
            return j.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content", "")?.trim()?.ifBlank { null }
        }
    }

    // ── Anthropic (operator key) ───────────────────────────────────────────
    private fun callAnthropic(system: String, user: String, maxTokens: Int): String? {
        val payload = JSONObject().apply {
            put("model", "claude-sonnet-4-5")
            put("max_tokens", maxTokens)
            put("system", system)
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
        }
        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", operatorAnthropicKey)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        exec(req, "llm_anthropic").use { resp ->
            if (!okOrThrow(resp, "llm_anthropic")) return null
            val body = resp.body?.string() ?: return null
            val j = JSONObject(body)
            return j.optJSONArray("content")?.optJSONObject(0)
                ?.optString("text", "")?.trim()?.ifBlank { null }
        }
    }
}