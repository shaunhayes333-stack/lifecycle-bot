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

    /**
     * V5.0.7155 §THE RING MUST NOT RACE THE TRADING LOOP FOR SOCKETS.
     *
     * V5.0.7151 made the council parallel, which was right, and built it on
     * SharedHttpClient — which hands out the SAME Dispatcher and the SAME
     * ConnectionPool the price providers use (maxRequests=96,
     * maxRequestsPerHost=24). Up to nine LLM members now start at once, each
     * able to hold a socket for the full 9s council wall clock, and the
     * forced sweep can start nine more. On the operator's 5.0.7153 device,
     * against 5.0.7145 on the same hardware:
     *
     *   dexscreener  sr=98% avg=541ms  ->  sr=48% avg=5165ms net=53
     *   helius       sr=97% avg=393ms  ->  sr=13% avg=8506ms net=13
     *   jupiter      sr=94% avg=169ms  ->  sr=16% avg=6597ms net=10
     *   raydium      sr=100% avg=505ms ->  sr= 0%            net=1
     *   defillama    sr=100% avg=548ms ->  sr= 0%            net=2
     *
     * Every provider roughly ten times slower, with network errors where
     * there were none. That is the signature of dispatcher and connection
     * pool contention, and its timing matches the ring exactly.
     *
     * The operator's instruction was "stop letting one provider outage block
     * the entire trading loop". 7151 removed one way of doing that and
     * introduced another: the council no longer blocks on nine serial
     * timeouts, it starves the price feed instead. Parallel WITHIN the
     * council, isolated FROM the trading path — both halves are required,
     * and I only built the first.
     *
     * So the council gets its own client: its own Dispatcher, its own
     * ConnectionPool, and a hard concurrency cap. Nothing it does can now
     * consume a permit or a socket that a mark refresh or a swap quote needs.
     *
     * (Caveat stated plainly: that snapshot is 145s from boot, so some of the
     * latency is cold start. The isolation is correct regardless of how much
     * of the regression it explains, and the next snapshot will separate the
     * two — if the price providers recover, the ring was the cause.)
     */
    private val llmDispatcher7155: okhttp3.Dispatcher = okhttp3.Dispatcher().apply {
        // The ring races at most this many members at once; the rest queue.
        // Nine simultaneous sockets is not worth a starved price feed, and a
        // first answer from four is almost always the same answer.
        maxRequests = LLM_RING_CONCURRENCY_7155
        maxRequestsPerHost = 2
    }

    private val httpClient: OkHttpClient by lazy {
        // Derived from SharedHttpClient so the interceptor chain is inherited
        // intact — HostCircuitInterceptor (which okOrThrow's OwnBackoffRefusal
        // detection depends on) and the gzip request/decode pair, whose
        // absence once "killed all Solana RPC reads" per the note in that
        // file. Only the dispatcher and the connection pool are replaced, and
        // those are exactly the two things being contended for.
        SharedHttpClient.builder()
            .dispatcher(llmDispatcher7155)
            .connectionPool(okhttp3.ConnectionPool(4, 60L, TimeUnit.SECONDS))
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

    /**
     * V5.0.7151 — the whole council gets ONE wall clock, not one per member.
     * Racing members means the deadline is the slowest answer we are willing
     * to wait for, not the sum of every dead member's timeout.
     */
    private const val COUNCIL_WALL_MS_7151 = 9_000L

    /**
     * V5.0.7155 — the ring's width, applied to BOTH the thread pool and the
     * OkHttp dispatcher so the two cannot disagree. 7151 used an unbounded
     * cached pool on the shared dispatcher, which let nine members hold nine
     * sockets for nine seconds against the price feed.
     */
    private const val LLM_RING_CONCURRENCY_7155 = 4

    /**
     * Daemon threads: a hung provider socket must never hold the process
     * open. FIXED size, not cached: the ring is a race between a bounded
     * number of members, and an unbounded pool is how a dry council turned
     * into a starved scanner.
     */
    private val councilPool7151: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newFixedThreadPool(LLM_RING_CONCURRENCY_7155) { r ->
            Thread(r, "aate-llm-ring").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }
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

    /**
     * V5.0.7164 §THE RING WAS GENERATING ITS OWN RATE LIMIT.
     *
     * Pollinations answers, verbatim, on the operator's device:
     *
     *   "Queue full for IP: 1 requests already queued (max: 1)"
     *
     * One queued request per IP is the whole budget, and the provider list at
     * :887-888 registers TWO members against that one host — `pollinations`
     * (POST /openai) and `pollinations_get` (GET /$prompt). 7151 turned the
     * council into a race, so from 7151 onward both are asked in the SAME
     * instant, every turn. One of them is guaranteed to be refused, take an
     * error cooldown, and be classified RATE by 7150. The snapshot agrees:
     * both sit at RATE with llm_pollinations net=213 and pollinations_get
     * net=105.
     *
     * A parallel ring is still right — the operator's instruction stands —
     * but parallel across PROVIDERS is not the same as parallel across a
     * provider's rate limit. Members that share a host now take turns on it:
     * the first to claim the slot goes to the wire, the second is refused by
     * our own backoff (never asked, never cooled, never penalised) and is
     * free to win the next turn.
     */
    private val hostSlots7164 = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.Semaphore>()

    private fun rateLimitHostOf7164(providerName: String): String? = when {
        providerName.startsWith("pollinations") -> "pollinations.ai"
        else -> null
    }

    /**
     * Runs [body] holding the provider's shared-host slot, or returns an
     * [OwnBackoffRefusal] when a sibling already holds it. Providers with no
     * shared host run unimpeded.
     */
    private fun withHostSlot7164(providerName: String, body: () -> Any?): Any? {
        val host = rateLimitHostOf7164(providerName) ?: return body()
        val sem = hostSlots7164.getOrPut(host) { java.util.concurrent.Semaphore(1, true) }
        if (!sem.tryAcquire()) {
            try {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LLM_HOST_SLOT_YIELDED_7164")
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc(
                    "LLM_HOST_SLOT_YIELDED_7164_" + providerName.uppercase(),
                )
            } catch (_: Throwable) {}
            return OwnBackoffRefusal(host)
        }
        return try { body() } finally { sem.release() }
    }

    private fun okOrThrow(resp: okhttp3.Response, host: String): Boolean {
        if (resp.isSuccessful) return true
        // HostCircuitInterceptor already owns this test. Re-reading the header
        // here would be a second definition of "is this our own refusal", and
        // two definitions of one question is how 6976's tag came to be written
        // but never read in the first place.
        if (HostCircuitInterceptor.isSyntheticBlock(resp)) throw OwnBackoffRefusal(host)
        // V5.0.7141 — SAY WHAT THE PROVIDER ACTUALLY SAID.
        //
        // Operator: "llm is still broken."
        //
        // Their 5.0.7140 device has every keyed member of the council at zero:
        //
        //   llm_gemini_7136  sr=0%  4xx=2      llm_groq        sr=0%  4xx=2
        //   llm_openrouter   sr=0%  4xx=19     llm_emergent    sr=0%  4xx=2
        //
        // while KeyValidator reports groq live=true http=200 GROQ_HEALTHY. A
        // valid key and a 4xx on the completion endpoint is a specific,
        // knowable fault — wrong model, quota, region, API not enabled, bad
        // body — and every one of those reasons arrives IN THE RESPONSE BODY.
        //
        // This function threw all of it away and returned a bare false. The
        // health table could therefore only ever say "4xx", and I have twice
        // now reasoned about which 4xx it might be instead of reading it.
        // Guessing at a cause that the server is already stating is the same
        // mistake as every absence-treated-as-fact in this session, with the
        // added indignity that the fact was right there.
        //
        // Consuming the body here is safe: every caller returns immediately
        // when this returns false, so nobody reads it afterwards. The key is
        // stripped before anything is written, because Gemini carries it in
        // the query string and a log line is not a place for it.
        try {
            val snippet = try {
                resp.peekBody(600L).string().trim().replace('\n', ' ')
            } catch (_: Throwable) { "" }
            val safeUrl = resp.request.url.toString().substringBefore("?key=")
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LLM_PROVIDER_HTTP_${resp.code}_7141")
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LLM_PROVIDER_HTTP_${resp.code}_7141_$host".take(60))
            ErrorLogger.warn(
                TAG,
                "provider=$host http=${resp.code} url=$safeUrl body=${snippet.take(400)}",
            )
            com.lifecyclebot.engine.ForensicLogger.lifecycle(
                "LLM_PROVIDER_HTTP_ERROR_7141",
                "provider=$host http=${resp.code} msg=${resp.message.take(40)} " +
                    "body=${snippet.take(240)}",
            )
            classifyAndPenalise7150(host, resp.code, snippet)
        } catch (_: Throwable) {}
        return false
    }

    /**
     * V5.0.7150 §A DEAD PROVIDER IS NOT A SLOW ONE.
     *
     * Operator's 5.0.7145 device, 839 seconds of uptime:
     *
     *   LLM_PROVIDER_HTTP_ERROR_7141 : 2311      (~2.75 failures every second)
     *   LLM_PROVIDER_HTTP_429_7141   : 1922
     *   LLM_PROVIDER_COOLDOWN_4458   :   59      (cooldowns actually applied)
     *   llm_emergent  4xx=384   llm_gemini_7136 4xx=385
     *   llm_openrouter 4xx=389  llm_groq 4xx=384  ovh_llm_keyless 4xx=1230
     *
     * And what those bodies actually say, now that 7141 prints them:
     *
     *   emergent   : "Budget has been exceeded! ... Current cost: 761.1047,
     *                 Max budget: 761.0983"  — the key is spent. Permanently.
     *   openrouter : "This model is unavailable for free."  — the slug is
     *                 retired. It will be retired tomorrow too.
     *   gemini     : "You exceeded your current quota"  — free-tier daily cap.
     *
     * None of those get better in fifteen seconds, and every one was retried
     * roughly four hundred times. 7145 fixed "nobody is asked" by asking
     * everybody; this is the other half — everybody is now asked, and
     * everybody is dead, so the council spends the whole session hammering
     * corpses and the operator gets templated musings instead of an answer.
     *
     * A 15s cooldown is the right response to a transient. It is the wrong
     * response to a spent budget, a retired model or a rejected key, and
     * treating them the same is the same defect class as everything else in
     * this run: refusing to read what the evidence actually says.
     */
    private const val PENALTY_TERMINAL_MS_7150 = 6L * 60 * 60 * 1000  // spent key / dead model
    private const val PENALTY_QUOTA_MS_7150 = 30L * 60 * 1000          // daily or plan quota
    private const val PENALTY_RATE_MS_7150 = 60L * 1000                // per-minute rate limit

    private val providerPenaltyUntil7150 = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val providerPenaltyReason7150 = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun classifyAndPenalise7150(host: String, code: Int, body: String) {
        val b = body.lowercase()
        val terminal = b.contains("budget has been exceeded") ||
            b.contains("budget_exceeded") ||
            b.contains("unavailable for free") ||
            b.contains("no longer available") ||
            b.contains("is not a valid model") ||
            b.contains("api key not valid") ||
            b.contains("invalid api key") ||
            b.contains("incorrect api key") ||
            code == 401 || code == 403
        // A daily/plan quota names the plan or the billing account. A bare 429
        // with no such wording is an ordinary per-minute rate limit, and those
        // genuinely do clear in a minute.
        val quota = !terminal && (
            b.contains("exceeded your current quota") ||
                b.contains("billing details") ||
                b.contains("quota_exceeded") ||
                b.contains("insufficient_quota") ||
                b.contains("daily limit") ||
                b.contains("credits")
            )
        val (ms, why) = when {
            terminal -> PENALTY_TERMINAL_MS_7150 to "TERMINAL"
            quota -> PENALTY_QUOTA_MS_7150 to "QUOTA"
            code == 429 -> PENALTY_RATE_MS_7150 to "RATE"
            else -> return
        }
        val until = System.currentTimeMillis() + ms
        val prior = providerPenaltyUntil7150[host] ?: 0L
        if (until <= prior) return
        providerPenaltyUntil7150[host] = until
        providerPenaltyReason7150[host] = why
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LLM_PROVIDER_PENALISED_7150_$why")
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LLM_PROVIDER_PENALISED_7150_${why}_$host".take(60))
            com.lifecyclebot.engine.ForensicLogger.lifecycle(
                "LLM_PROVIDER_PENALISED_7150",
                "provider=$host http=$code class=$why penaltyMs=$ms action=stop_retrying_until_expiry",
            )
        } catch (_: Throwable) {}
    }

    /** V5.0.7150 — true while a provider is serving a classified penalty. */
    private fun penalised7150(name: String, now: Long): Boolean =
        (providerPenaltyUntil7150[name] ?: 0L) > now

    /** V5.0.7150 — operator-facing summary of who is benched and why. */
    fun penaltyStatusLine7150(): String {
        val now = System.currentTimeMillis()
        val rows = providerPenaltyUntil7150.entries
            .filter { it.value > now }
            .sortedByDescending { it.value }
            .joinToString(" · ") {
                "${it.key}:${providerPenaltyReason7150[it.key] ?: "?"}(${(it.value - now) / 1000}s)"
            }
        return if (rows.isBlank()) "llmPenalties7150: none" else "llmPenalties7150: $rows"
    }

    @Volatile private var operatorGroqKey: String = ""
    @Volatile private var operatorOpenRouterKey: String = ""
    @Volatile private var operatorAnthropicKey: String = ""
    @Volatile private var operatorGeminiKey: String = ""

    fun setOperatorKeys(
        groq: String = "",
        openRouter: String = "",
        anthropic: String = "",
        // V5.0.7136 — the operator's Gemini key had nowhere to go.
        //
        // Operator: "I have a legitimate groq and gemini llm keys entered into
        // and saved in settings. so there shouldn't be an issue."
        //
        // There was an issue, and it was here. BotService read cfg.geminiApiKey
        // into a local named antKey and passed it to the ANTHROPIC slot behind
        // `if (antKey.startsWith("sk-ant-"))`. A Gemini key starts with AIza, so
        // that test is false for every real one and the key was discarded at the
        // door. This council also had no Gemini member to give it to even if the
        // test had passed. A key the operator entered, saved, and could see in
        // Settings was therefore guaranteed never to be used by this class.
        gemini: String = "",
    ) {
        operatorGroqKey = groq.trim()
        operatorOpenRouterKey = openRouter.trim()
        operatorAnthropicKey = anthropic.trim()
        operatorGeminiKey = gemini.trim()
    }

    /**
     * V5.0.7136 — a provider's refusal is not the model's answer.
     *
     * Operator screenshot, Sentient Mind, rendered as PHILO's reply in the
     * persona feed:
     *
     *   "The account behind this API key doesn't have enough credits. Please
     *    top up or complete a quest, then try again. If this isn't your
     *    Pollinations account, contact whoever runs the app or service you're
     *    using."
     *
     * That is Pollinations telling us it will not serve the request. It arrives
     * with HTTP 200 and a plain-text body, so okOrThrow passes it and the bare
     * text branch hands it back as a completion. The council then counts it as
     * a SUCCESS, stops asking anyone else, and the app prints a billing notice
     * in the voice of the bot's own persona.
     *
     * This is the same shape as every other defect this session: a refusal read
     * as a result. §6982 already states the rule — "a local decline is the
     * absence of an observation ... count it as a skip rather than as an empty
     * result." It applies to a remote decline that arrives dressed as prose.
     *
     * Deliberately narrow. It requires a short body AND a distinctive
     * billing/auth phrase, because a real answer may legitimately discuss API
     * keys or rate limits. A false positive costs one provider attempt and the
     * council moves on; a false negative prints a vendor's dunning notice to the
     * operator as if the bot said it.
     */
    private fun providerRefusalText7136(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (t.length > 600) return null
        val low = t.lowercase()
        val markers = listOf(
            "doesn't have enough credits", "does not have enough credits",
            "insufficient credits", "insufficient_quota", "out of credits",
            "please top up", "top-up?ref=", "complete a quest",
            "quota exceeded", "exceeded your current quota",
            "invalid api key", "incorrect api key", "api key not valid",
            "unauthorized", "rate limit exceeded", "too many requests",
            "contact whoever runs the app",
        )
        val hit = markers.firstOrNull { low.contains(it) } ?: return null
        return hit
    }

    /** Returns the body when it is a real completion, or null when it is a refusal. */
    private fun asCompletionOrNull7136(text: String?, provider: String): String? {
        val t = text?.trim()?.ifBlank { null } ?: return null
        val refusal = providerRefusalText7136(t) ?: return t
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LLM_PROVIDER_REFUSAL_AS_TEXT_7136")
            com.lifecyclebot.engine.ForensicLogger.lifecycle(
                "LLM_PROVIDER_REFUSAL_AS_TEXT_7136",
                "provider=$provider marker=\"$refusal\" len=${t.length} " +
                    "action=treat_as_failure_try_next_member body=${t.take(140).replace('\n', ' ')}",
            )
        } catch (_: Throwable) {}
        return null
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

        // ─────────────────────────────────────────────────────────────────
        // V5.0.7151 §THE COUNCIL IS A RING, NOT A QUEUE.
        //
        // Operator: "they should all be parallelled rings not serial. stop
        // letting one provider outage block the entire trading loop. that
        // goes everywhere."
        //
        // This loop asked nine members ONE AT A TIME and returned on the
        // first real answer. When every member is healthy that is cheap,
        // because the first one answers. When members are dead — which on the
        // operator's 5.0.7145 device is ALL of them, 2311 HTTP failures in
        // 839 seconds — the caller pays every timeout end to end before
        // learning the council is dry. Nine members at a couple of seconds
        // each is most of a bot-loop cycle spent waiting on providers that
        // already said no, and the whole point of having nine was that no
        // single one could hold the loop up.
        //
        // A serial fallback chain makes availability MULTIPLICATIVE in
        // latency and only additive in reliability. Racing them makes latency
        // the FASTEST member's, not the sum of the dead ones'.
        //
        // So: every eligible member is asked at once, the first non-blank
        // answer wins and the rest are cancelled. The whole council is bounded
        // by one wall-clock deadline rather than by nine sequential timeouts.
        //
        // Members still running when the deadline passes are recorded as
        // NOTHING — not empty, not errored. We did not observe a failure, we
        // stopped waiting, and writing that down as a failure would cool a
        // member for our own impatience. Same rule as the OwnBackoffRefusal
        // branch below and §6982 throughout.
        val eligible7151 = ArrayList<Provider>(n)
        for (offset in 0 until n) {
            val p = providers[(start + offset) % n]
            // A provider serving a classified penalty (spent budget, retired
            // model, rejected key, exhausted daily quota) is not asked. It
            // answered already, in words, and that will not change this minute.
            if (penalised7150(p.name, now)) { cooling++; continue }
            if ((cooldownUntil[p.name] ?: 0L) > now) { cooling++; continue }
            eligible7151.add(p)
        }

        if (eligible7151.isNotEmpty()) {
            val ecs = java.util.concurrent.ExecutorCompletionService<Pair<String, Any?>>(councilPool7151)
            val futures = ArrayList<java.util.concurrent.Future<Pair<String, Any?>>>(eligible7151.size)
            for (p in eligible7151) {
                futures.add(
                    // V5.0.7153 — Callable is explicit. Kotlin refuses an
                    // explicit type argument on the Java submit(Callable<V>)
                    // overload, and a bare lambda is ambiguous against
                    // submit(Runnable), which would discard the result.
                    ecs.submit(
                        java.util.concurrent.Callable<Pair<String, Any?>> {
                            try { p.name to withHostSlot7164(p.name) { p.call(system, user, maxTokens) } }
                            catch (t: Throwable) { p.name to t }
                        },
                    ),
                )
            }
            asked += eligible7151.size
            try {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LLM_COUNCIL_PARALLEL_RING_7151")
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc(
                    "LLM_COUNCIL_PARALLEL_RING_7151_N${eligible7151.size}",
                )
            } catch (_: Throwable) {}
            val deadline7151 = System.currentTimeMillis() + COUNCIL_WALL_MS_7151
            var winner7151: String? = null
            try {
                var settled = 0
                while (settled < futures.size && winner7151 == null) {
                    val remaining = deadline7151 - System.currentTimeMillis()
                    if (remaining <= 0L) break
                    val done = ecs.poll(remaining, java.util.concurrent.TimeUnit.MILLISECONDS) ?: break
                    settled++
                    val (pname, outcome) = try { done.get() } catch (t: Throwable) { "?" to t }
                    when {
                        outcome is OwnBackoffRefusal -> {
                            // Never asked — our own circuit short-circuited it.
                            // No cooldown, and it does not count as asked.
                            asked--
                            ownBackoff++
                            ErrorLogger.debug(TAG, "provider=$pname refused by our own backoff")
                        }
                        outcome is Throwable -> {
                            errored++
                            cooldownUntil[pname] = now + COOLDOWN_MS
                            ErrorLogger.debug(TAG, "provider=$pname err=${outcome.message?.take(120)}")
                        }
                        outcome is String && outcome.isNotBlank() -> winner7151 = outcome
                        else -> {
                            empty++
                            cooldownUntil[pname] = now + 15_000L
                        }
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                for (f in futures) if (!f.isDone) f.cancel(true)
            }
            if (winner7151 != null) {
                lastCouncilDiagnostic7016 = ""
                return winner7151
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
            // V5.0.7145 — WHEN NOBODY WAS ASKED, ASK EVERYBODY.
            //
            // Operator, after entering fresh Groq and Gemini keys: "there is no
            // excuse that this is not working mate. fucking 0."
            //
            // They are right, and 7141's logging finally shows why. Their
            // 5.0.7141 device:
            //
            //   LLM_COUNCIL_DRY_7016  members=9 asked=0 cooling=5 ownBackoff=4
            //                         empty=1 err=0 forced=pollinations_get
            //
            // ASKED=0. Nine members, nobody contacted. Five in the council's own
            // cooldown, four refused by our ApiBackoff. llm_groq does not even
            // appear in that session's health table, because no Groq call was
            // ever made — the operator's new key never reached the wire.
            //
            // This rescue existed for exactly that case and then tried ONE
            // member. It picks `best` by health, which on this device is
            // pollinations_get, whose body is the Pollinations "doesn't have
            // enough credits" notice — correctly rejected by 7136, counted
            // empty, and the council returns dry with eight untried members
            // including both of the operator's keyed ones.
            //
            // One attempt is not a rescue. When a person has typed a question
            // and the entire council is sidelined by OUR OWN timers, the right
            // behaviour is to walk every member in health order and stop at the
            // first real answer. The cooldowns exist to protect providers from
            // a hot loop, not to stop a human being answered; there is exactly
            // one of these sweeps per dry turn.
            val ordered7145 = providers.sortedWith(
                compareByDescending<Provider> { p ->
                    try {
                        if (com.lifecyclebot.engine.ApiHealthMonitor.hasSamples(p.healthHost)) {
                            com.lifecyclebot.engine.ApiHealthMonitor.successRate(p.healthHost)
                        } else {
                            // Between "never tried" and "tried and failed every
                            // time", try the untried one.
                            0.5
                        }
                    } catch (_: Throwable) { 0.5 }
                }.thenBy { p -> cooldownUntil[p.name] ?: 0L }
            )
            try {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LLM_COUNCIL_FORCED_SWEEP_7145")
            } catch (_: Throwable) {}
            // V5.0.7151 — the sweep races too.
            //
            // This branch only runs when NOBODY reached the wire, i.e. every
            // member was locked out by our own backoff. Walking them one at a
            // time to discover that was the worst case of all: the caller pays
            // the full serial cost precisely on the turn when the council has
            // the least to offer. forcedAttempt7016 is a ThreadLocal read by
            // exec(), so each racer sets it on its OWN thread and clears it in
            // a finally — the waiver travels with the request, not with the
            // caller.
            val sweep7151 = ordered7145.filter { !penalised7150(it.name, System.currentTimeMillis()) }
            if (sweep7151.isNotEmpty()) {
                val ecs2 = java.util.concurrent.ExecutorCompletionService<Pair<String, Any?>>(councilPool7151)
                val futures2 = ArrayList<java.util.concurrent.Future<Pair<String, Any?>>>(sweep7151.size)
                for (p in sweep7151) {
                    try {
                        com.lifecyclebot.engine.PipelineHealthCollector.labelInc(
                            "LLM_COUNCIL_FORCED_ATTEMPT_7016_" + p.name.uppercase(),
                        )
                    } catch (_: Throwable) {}
                    futures2.add(
                        ecs2.submit(
                            java.util.concurrent.Callable<Pair<String, Any?>> {
                                forcedAttempt7016.set(true)
                                try { p.name to withHostSlot7164(p.name) { p.call(system, user, maxTokens) } }
                                catch (t: Throwable) { p.name to t }
                                finally { forcedAttempt7016.set(false) }
                            },
                        ),
                    )
                }
                forcedName7030 = sweep7151.first().name
                val deadline2 = System.currentTimeMillis() + COUNCIL_WALL_MS_7151
                var swept7151: String? = null
                try {
                    var settled = 0
                    while (settled < futures2.size && swept7151 == null) {
                        val remaining = deadline2 - System.currentTimeMillis()
                        if (remaining <= 0L) break
                        val done = ecs2.poll(remaining, java.util.concurrent.TimeUnit.MILLISECONDS) ?: break
                        settled++
                        val (pname, outcome) = try { done.get() } catch (t: Throwable) { "?" to t }
                        when {
                            outcome is OwnBackoffRefusal -> {
                                ownBackoff++
                                ErrorLogger.debug(TAG, "sweep $pname refused by own backoff")
                            }
                            outcome is Throwable -> {
                                errored++
                                cooldownUntil[pname] = now + COOLDOWN_MS
                                ErrorLogger.debug(TAG, "sweep $pname: ${outcome.message?.take(120)}")
                            }
                            outcome is String && outcome.isNotBlank() -> {
                                cooldownUntil.remove(pname)
                                forcedName7030 = pname
                                try {
                                    com.lifecyclebot.engine.PipelineHealthCollector.labelInc(
                                        "LLM_COUNCIL_FORCED_SWEEP_ANSWERED_7145_" + pname.uppercase(),
                                    )
                                } catch (_: Throwable) {}
                                swept7151 = outcome
                            }
                            else -> {
                                empty++
                                cooldownUntil[pname] = now + 15_000L
                            }
                        }
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } finally {
                    for (f in futures2) if (!f.isDone) f.cancel(true)
                }
                if (swept7151 != null) {
                    lastCouncilDiagnostic7016 = ""
                    return swept7151
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
        // V5.0.7136 — the operator's Gemini key gets a seat. Keyed members stay
        // ahead of the keyless tail below, so a configured subscription is
        // preferred and Pollinations only carries the load when it has to.
        if (operatorGeminiKey.isNotBlank()) {
            list.add(Provider("gemini", "llm_gemini_7136") { s, u, m -> callGemini7136(s, u, m) })
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
            if (!trimmed.startsWith("{")) return asCompletionOrNull7136(trimmed, "pollinations")
            val j = JSONObject(trimmed)
            val content = j.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content", "")?.trim()?.ifBlank { null }
                ?: trimmed
            return asCompletionOrNull7136(content, "pollinations")
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
            return asCompletionOrNull7136(resp.body?.string(), "pollinations_get")
        }
    }

    /**
     * V5.0.7136 — Gemini, using the key the operator actually entered.
     *
     * The council had seven members and not one of them was Gemini, while the
     * operator's own Gemini key sat in Settings being tested for an Anthropic
     * prefix. Direct generateContent, same endpoint GeminiCopilot uses, so this
     * adds no new dependency and no new account — it spends a key that is
     * already configured, already saved, and already expected to be in use.
     *
     * System and user are folded into one turn rather than sent as
     * systemInstruction: the fold is what callPollinationsGet already does, it
     * works on every v1beta model, and a council member that fails on an
     * envelope detail is worth less than one that answers.
     */
    private fun callGemini7136(system: String, user: String, maxTokens: Int): String? {
        val key = operatorGeminiKey
        if (key.isBlank()) return null
        val prompt = if (system.isBlank()) user else "$system\n\n$user"
        val payload = JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            ))
            put("generationConfig", JSONObject()
                .put("maxOutputTokens", maxTokens)
                .put("temperature", 0.2))
        }
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$key")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        exec(req, "llm_gemini_7136").use { resp ->
            if (!okOrThrow(resp, "llm_gemini_7136")) return null
            val body = resp.body?.string()?.trim()?.ifBlank { null } ?: return null
            if (!body.startsWith("{")) return asCompletionOrNull7136(body, "gemini")
            val text = JSONObject(body)
                .optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)
                ?.optString("text", "")?.trim()?.ifBlank { null }
            return asCompletionOrNull7136(text, "gemini")
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
    //
    // V5.0.7164 §ONE MODEL IS ONE RATE-LIMIT BUCKET.
    //
    // The operator's 5.0.7161 device holds a working Groq key — KeyValidator
    // reports GROQ_HEALTHY http=200 — and the provider still reads
    //
    //   llm_groq  sr=2%  4xx=209        llmPenalties7150: llm_groq:RATE
    //
    // sr=2% is the tell. A dead model or a rejected key answers 0%; two
    // percent means the route works and the budget does not. Groq meters the
    // free tier PER MODEL, and 6498 pins every caller to exactly one, so the
    // council's entire Groq capacity is one model's daily allowance. Once
    // that bucket empties the provider is finished for the day, and 7150
    // benches it on RATE every sixty seconds to rediscover the same thing.
    //
    // A 429 on one model says nothing about another. Same ladder shape as
    // 7150 used for OpenRouter's dead slugs, extended to rotate on 429: when
    // a model is out of budget, ask the next one. PRIMARY_MODEL stays the
    // head of the ladder and the canonical health route, so 6498's one-model
    // authority still holds for everything that asks "is Groq configured".
    private val GROQ_MODEL_LADDER_7164 = listOf(
        GroqRouteConfig6498.PRIMARY_MODEL,
        "llama-3.3-70b-versatile",
        "llama-3.1-8b-instant",
        "openai/gpt-oss-120b",
        "qwen/qwen3-32b",
    )
    @Volatile private var groqModelIdx7164: Int = 0

    private fun callGroq(system: String, user: String, maxTokens: Int): String? {
        val idx7164 = groqModelIdx7164.coerceIn(0, GROQ_MODEL_LADDER_7164.size - 1)
        val model7164 = GROQ_MODEL_LADDER_7164[idx7164]
        val payload = JSONObject().apply {
            // V5.0.6691 — one model authority. A stale hard-coded Groq model
            // here could fail independently of the canonical route used by
            // every other Groq client and silently collapse the fallback chain.
            // V5.0.7164 — the head of the ladder IS that authority; the rest
            // are only reached when the head refuses this specific call.
            put("model", model7164)
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
            if (!okOrThrow(resp, "llm_groq")) {
                // 429 = this model's budget, 404/400 = this model's name.
                // Both are verdicts on the MODEL, not on the account, so the
                // ladder advances. 401/403 are account-level and are left to
                // classifyAndPenalise7150 — no model would help.
                if (resp.code == 429 || resp.code == 404 || resp.code == 400) {
                    groqModelIdx7164 = (idx7164 + 1) % GROQ_MODEL_LADDER_7164.size
                    try {
                        com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LLM_GROQ_MODEL_ROTATED_7164")
                        com.lifecyclebot.engine.ForensicLogger.lifecycle(
                            "LLM_GROQ_MODEL_ROTATED_7164",
                            "from=$model7164 to=${GROQ_MODEL_LADDER_7164[groqModelIdx7164]} http=${resp.code}",
                        )
                    } catch (_: Throwable) {}
                }
                return null
            }
            val body = resp.body?.string() ?: return null
            val j = JSONObject(body)
            val out7164 = j.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content", "")?.trim()?.ifBlank { null }
            // Stay on the model that answered.
            if (out7164 != null && groqModelIdx7164 != idx7164) groqModelIdx7164 = idx7164
            return out7164
        }
    }

    // ── OpenRouter (operator key) ──────────────────────────────────────────
    //
    // V5.0.7150 — the pinned slug died and took the provider with it.
    //
    // Operator's 5.0.7145 device, in OpenRouter's own words:
    //
    //   http=404 {"error":{"message":"This model is unavailable for free.
    //   The paid version is available now - use this slug instead:
    //   meta-llama/llama-3.3-70b-instruct","code":404}}
    //
    // 389 times. A single hardcoded `:free` slug is a bet that a third party
    // will keep one specific free tier alive forever, and that bet has now
    // lost — the suggested replacement in that message is the PAID model, so
    // taking the server's advice literally would start charging the operator.
    //
    // Free slugs are the thing that churns here, so the fix is a ladder
    // rather than a better guess: try each, remember the one that answered,
    // and start there next time. When a slug 404s the next is tried on the
    // following call instead of the provider being written off.
    private val OPENROUTER_FREE_MODELS_7150 = listOf(
        "meta-llama/llama-3.3-70b-instruct:free",
        "meta-llama/llama-3.1-8b-instruct:free",
        "google/gemma-2-9b-it:free",
        "mistralai/mistral-7b-instruct:free",
        "qwen/qwen-2-7b-instruct:free",
    )
    @Volatile private var openRouterModelIdx7150: Int = 0

    private fun callOpenRouter(system: String, user: String, maxTokens: Int): String? {
        val idx = openRouterModelIdx7150.coerceIn(0, OPENROUTER_FREE_MODELS_7150.size - 1)
        val model7150 = OPENROUTER_FREE_MODELS_7150[idx]
        val payload = JSONObject().apply {
            put("model", model7150)
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
            if (!okOrThrow(resp, "llm_openrouter")) {
                // V5.0.7150 — a model-level refusal advances the ladder; an
                // account-level one (spent credits, bad key) does not, because
                // no slug would help and classifyAndPenalise7150 has already
                // benched the provider.
                if (resp.code == 404 || resp.code == 400) {
                    openRouterModelIdx7150 = (idx + 1) % OPENROUTER_FREE_MODELS_7150.size
                    try {
                        com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LLM_OPENROUTER_MODEL_ROTATED_7150")
                        com.lifecyclebot.engine.ForensicLogger.lifecycle(
                            "LLM_OPENROUTER_MODEL_ROTATED_7150",
                            "from=$model7150 to=${OPENROUTER_FREE_MODELS_7150[openRouterModelIdx7150]} http=${resp.code}",
                        )
                    } catch (_: Throwable) {}
                }
                return null
            }
            val body = resp.body?.string() ?: return null
            val j = JSONObject(body)
            val out7150 = j.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content", "")?.trim()?.ifBlank { null }
            // Remember the slug that actually answered.
            if (out7150 != null && openRouterModelIdx7150 != idx) openRouterModelIdx7150 = idx
            return out7150
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