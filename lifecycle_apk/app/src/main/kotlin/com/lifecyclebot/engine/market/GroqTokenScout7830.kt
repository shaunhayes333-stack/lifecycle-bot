package com.lifecyclebot.engine.market

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7830 — GROQ WEB TOKEN SCOUT (operator-approved discovery source).
 *
 * Before this build no LLM in the app had web access: every Groq call was a
 * plain text completion, KeylessLlmClient deliberately excludes the
 * `compound` models from its text ladder, and GeminiCopilot only attaches
 * googleSearch for a direct AIza Gemini key. This worker is the one caller of
 * Groq's agentic web-search models (`groq/compound`, `groq/compound-mini`),
 * which search the web server-side on every call. It asks for currently
 * talked-about Solana memecoins (crypto Twitter, pump.fun launches /
 * migrations / fallouts, call groups, DexScreener trending) plus public
 * Telegram call channels.
 *
 * Authority: CANDIDATES ONLY. LLM output is untrusted text and may invent
 * contract addresses, so every address must pass the base58 mint shape check,
 * chain == solana, and a live DexScreener pair whose base token IS that mint
 * (real SPL mint with real market data) before it reaches the scanner. A
 * verified mint enters the ordinary intake (SolanaMarketScanner passesFilter
 * -> rugcheck -> watchlist -> processTokenCycle -> V3 / lanes / FDG / sizing)
 * as TokenSource.LLM_SCOUT with no score bonus, no gate bypass and no
 * special sizing. Field Manual L201 (social engagement is easy to
 * manufacture — verify market structure), L309 (a social post alone is not
 * a thesis), L400 (verify sources independently), L412 (discovery never
 * competes with held-position work: own coroutine, never on a hot path).
 *
 * Budget (free tier): one call per [CADENCE_MS_7830], at most
 * [MAX_CALLS_PER_DAY_7830] per UTC day, single-flight, [CALL_TIMEOUT_MS_7830]
 * hard ceiling, exponential backoff on 429 (Retry-After honoured).
 */
object GroqTokenScout7830 {

    const val PRIMARY_MODEL_7830 = "groq/compound"
    const val FALLBACK_MODEL_7830 = "groq/compound-mini"
    private const val GROQ_URL_7830 = "https://api.groq.com/openai/v1/chat/completions"
    /** ApiHealthMonitor / ApiBackoff key — separate from the "groq" text ladder's. */
    private const val HEALTH_KEY_7830 = "groq_compound"

    const val CADENCE_MS_7830 = 10L * 60_000L
    private const val FIRST_RUN_DELAY_MS_7830 = 90_000L
    const val MAX_CALLS_PER_DAY_7830 = 120
    const val CALL_TIMEOUT_MS_7830 = 45_000L
    private const val EMIT_BUDGET_MS_7830 = 60_000L
    private const val MAX_CANDIDATES_PER_RUN_7830 = 15
    private const val EMIT_DEDUPE_TTL_MS_7830 = 3L * 60 * 60 * 1000
    private const val CHANNEL_TTL_MS_7830 = 3L * 24 * 60 * 60 * 1000
    private const val MAX_CHANNELS_7830 = 60
    private const val MAX_BACKOFF_MS_7830 = 60L * 60_000L

    private val SOLANA_MINT_RX_7830 = Regex("^[1-9A-HJ-NP-Za-km-z]{32,44}$")
    private val TG_CHANNEL_RX_7830 = Regex("^[A-Za-z][A-Za-z0-9_]{4,31}$")
    private val QUOTE_SYMBOLS_7830 = setOf("SOL", "WSOL", "USDC", "USDT")

    /** One row of the model's JSON answer (untrusted). */
    data class Candidate7830(
        val symbol: String,
        val contractAddress: String,
        val chain: String,
        val narrative: String,
        val sourceUrl: String,
        val reason: String,
        val freshnessMinutes: Int,
    )

    /** Parsed model answer. */
    data class Parsed7830(val tokens: List<Candidate7830>, val telegramChannels: List<String>)

    /** A candidate whose mint has a live DexScreener pair; the only thing handed to intake. */
    data class Verified7830(
        val mint: String,
        val symbol: String,
        val narrative: String,
        val sourceUrl: String,
        val pair: com.lifecyclebot.network.PairInfo,
    )

    // Shares SharedHttpClient's dispatcher (so stopBot's cancelAllRequests also
    // cancels a scout call) but NOT its HostCircuitInterceptor: compound models
    // have their own Groq rate limits, and a compound 429 must not lock the
    // "groq" host out for the text ladder. Gzip falls back to OkHttp's own.
    private val http: OkHttpClient by lazy {
        val b = com.lifecyclebot.network.SharedHttpClient.builder()
        b.interceptors().clear()
        b.connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(CALL_TIMEOUT_MS_7830, TimeUnit.MILLISECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_MS_7830, TimeUnit.MILLISECONDS)
            .build()
    }
    private val dex by lazy { com.lifecyclebot.network.DexscreenerApi() }

    @Volatile private var job: Job? = null
    private val inFlight = AtomicBoolean(false)
    @Volatile private var model = PRIMARY_MODEL_7830
    @Volatile private var backoffUntilMs = 0L
    @Volatile private var rateLimitStreak = 0
    @Volatile private var dayKey = 0L
    @Volatile private var callsToday = 0
    @Volatile private var lastRunAtMs = 0L
    @Volatile private var lastError = ""
    @Volatile private var lastSample = ""

    private val runs = AtomicLong(0L)
    private val calls = AtomicLong(0L)
    private val okCalls = AtomicLong(0L)
    private val rateLimited = AtomicLong(0L)
    private val httpErrors = AtomicLong(0L)
    private val parseFailures = AtomicLong(0L)
    private val searchesUsed = AtomicLong(0L)
    private val candidatesReturned = AtomicLong(0L)
    private val verified = AtomicLong(0L)
    private val unverified = AtomicLong(0L)
    private val deduped = AtomicLong(0L)
    private val emitted = AtomicLong(0L)
    private val skippedBudget = AtomicLong(0L)
    private val skippedBackoff = AtomicLong(0L)
    private val skippedNoKey = AtomicLong(0L)

    private val recentlyEmitted = ConcurrentHashMap<String, Long>()
    private val channels = ConcurrentHashMap<String, Long>()

    /**
     * Start (or restart) on [scope]. [groqKey] is read every run so a key
     * typed into Settings applies without a restart. [emitter] hands verified
     * candidates to the scanner intake and returns how many it emitted.
     */
    fun start(
        scope: CoroutineScope,
        groqKey: () -> String,
        emitter: suspend (List<Verified7830>) -> Int,
    ) {
        synchronized(this) {
            job?.cancel()
            job = scope.launch(Dispatchers.IO + CoroutineName("groq-token-scout-7830")) {
                delay(FIRST_RUN_DELAY_MS_7830)
                while (isActive) {
                    runOnce(groqKey, emitter)
                    delay(CADENCE_MS_7830)
                }
            }
        }
        try { PipelineHealthCollector.labelInc("LLM_SCOUT_STARTED_7830") } catch (_: Throwable) {}
        ErrorLogger.info("GroqScout7830", "started cadence=${CADENCE_MS_7830}ms maxPerDay=$MAX_CALLS_PER_DAY_7830 model=$model")
    }

    fun stop(reason: String) {
        val j = synchronized(this) { val cur = job; job = null; cur }
        if (j != null) {
            j.cancel()
            try { PipelineHealthCollector.labelInc("LLM_SCOUT_STOPPED_7830") } catch (_: Throwable) {}
            ErrorLogger.info("GroqScout7830", "stopped reason=${reason.take(60)}")
        }
    }

    private fun isRunning(): Boolean = job?.isActive == true

    /**
     * Public Telegram channel usernames (no "@", no t.me/) the scout has seen
     * named as Solana call channels in the last few days, newest first.
     * Consumed by TelegramCallSweeper7830. Discovery hints only.
     */
    fun discoveredTelegramChannels(): List<String> {
        val now = System.currentTimeMillis()
        channels.entries.removeIf { now - it.value > CHANNEL_TTL_MS_7830 }
        return channels.entries.sortedByDescending { it.value }.map { it.key }
    }

    /** One scout pass. Single-flight; returns false when a pass was already running. */
    private suspend fun runOnce(groqKey: () -> String, emitter: suspend (List<Verified7830>) -> Int): Boolean {
        if (!inFlight.compareAndSet(false, true)) return false
        try {
            runs.incrementAndGet()
            lastRunAtMs = System.currentTimeMillis()
            val key = try { groqKey().trim() } catch (_: Throwable) { "" }
            if (key.isBlank()) {
                skippedNoKey.incrementAndGet()
                try { PipelineHealthCollector.labelInc("LLM_SCOUT_NO_GROQ_KEY_7830") } catch (_: Throwable) {}
                return true
            }
            val now = System.currentTimeMillis()
            if (now < backoffUntilMs || com.lifecyclebot.engine.ApiBackoff.isLockedOut(HEALTH_KEY_7830)) {
                skippedBackoff.incrementAndGet()
                try { PipelineHealthCollector.labelInc("LLM_SCOUT_SKIPPED_BACKOFF_7830") } catch (_: Throwable) {}
                return true
            }
            if (!takeDailyBudget7830(now)) {
                skippedBudget.incrementAndGet()
                try { PipelineHealthCollector.labelInc("LLM_SCOUT_SKIPPED_DAILY_BUDGET_7830") } catch (_: Throwable) {}
                return true
            }
            val content = callCompound7830(key) ?: return true
            val parsed = parseScoutResponse7830(content)
            if (parsed == null) {
                parseFailures.incrementAndGet()
                lastError = "parse_failed"
                try { PipelineHealthCollector.labelInc("LLM_SCOUT_PARSE_FAILED_7830") } catch (_: Throwable) {}
                return true
            }
            rememberChannels7830(parsed.telegramChannels, now)
            candidatesReturned.addAndGet(parsed.tokens.size.toLong())
            val ready = verifyAll7830(parsed.tokens.take(MAX_CANDIDATES_PER_RUN_7830))
            if (ready.isNotEmpty()) {
                val n = withTimeoutOrNull(EMIT_BUDGET_MS_7830) { emitter(ready) }
                if (n == null) {
                    try { PipelineHealthCollector.labelInc("LLM_SCOUT_EMIT_BUDGET_EXCEEDED_7830") } catch (_: Throwable) {}
                } else {
                    emitted.addAndGet(n.toLong())
                    val t = System.currentTimeMillis()
                    ready.forEach { recentlyEmitted[it.mint] = t }
                }
                lastSample = ready.take(3).joinToString(",") { "${it.symbol}:${it.narrative.take(24)}" }
            }
            ErrorLogger.info(
                "GroqScout7830",
                "pass model=$model returned=${parsed.tokens.size} ready=${ready.size} channels=${parsed.telegramChannels.size} " +
                    "sample=[${ready.take(5).joinToString(" ") { "${it.symbol}(${it.narrative.take(30)})" }}]",
            )
            return true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}:${t.message?.take(60)}"
            ErrorLogger.debug("GroqScout7830", "pass failed: $lastError")
            return true
        } finally {
            inFlight.set(false)
        }
    }

    @Synchronized
    private fun takeDailyBudget7830(now: Long): Boolean {
        val d = now / 86_400_000L
        if (d != dayKey) { dayKey = d; callsToday = 0 }
        if (callsToday >= MAX_CALLS_PER_DAY_7830) return false
        callsToday++
        return true
    }

    /** Pure: backoff for the [streak]th consecutive 429 (1-based); Retry-After wins when longer. */
    internal fun backoffMs7830(streak: Int, retryAfterSec: Long?): Long {
        val s = streak.coerceIn(1, 6)
        val exp = (CADENCE_MS_7830 shl (s - 1)).coerceAtMost(MAX_BACKOFF_MS_7830)
        val ra = (retryAfterSec ?: 0L).coerceIn(0L, MAX_BACKOFF_MS_7830 / 1000L) * 1000L
        return maxOf(exp, ra)
    }

    private fun systemPrompt7830(): String =
        "You are a Solana memecoin web scout with live web search. Search the web NOW. " +
            "Report only tokens you actually found in search results during this request. " +
            "Never guess or invent a contract address: if a source does not show the full Solana " +
            "mint address, omit that token. Output ONLY one JSON object, no prose, no code fences."

    private fun userPrompt7830(): String = """
Find Solana memecoins people are talking about RIGHT NOW (last few hours):
- trending on X / crypto Twitter (CT), including KOL mentions;
- pump.fun: fresh launches gaining traction, king-of-the-hill, recent migrations/graduations to Raydium/PumpSwap, and notable rugs or fallouts being discussed;
- Telegram / Discord call groups being talked about and what they are calling;
- DexScreener trending / boosted on Solana.
Also list public Telegram channel usernames (t.me/<name>) that regularly post Solana token calls.
Return exactly:
{"tokens":[{"symbol":"","contractAddress":"<full Solana mint>","chain":"solana","narrative":"","sourceUrl":"","reason":"","freshnessMinutes":0}],"telegramChannels":["username"]}
At most 15 tokens and 15 channels. For dumps/fallouts/suspected rugs start the reason with "FALLOUT:" (they are re-assessed by the bot's own safety checks).
""".trimIndent()

    /** One compound call. Returns the message content, or null (counters / backoff already updated). */
    private fun callCompound7830(key: String): String? {
        val useModel = model
        val payload = JSONObject()
            .put("model", useModel)
            .put("temperature", 0.2)
            .put("max_completion_tokens", 2048)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt7830()))
                    .put(JSONObject().put("role", "user").put("content", userPrompt7830())),
            )
        val req = Request.Builder()
            .url(GROQ_URL_7830)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .build()
        calls.incrementAndGet()
        val started = System.currentTimeMillis()
        return try {
            http.newCall(req).execute().use { resp ->
                val latency = System.currentTimeMillis() - started
                if (!resp.isSuccessful) {
                    val err = resp.body?.string().orEmpty().take(240)
                    try { com.lifecyclebot.engine.ApiHealthMonitor.record(HEALTH_KEY_7830, resp.code, latency, errorBody = err) } catch (_: Throwable) {}
                    try { com.lifecyclebot.engine.ApiBackoff.markFailure(HEALTH_KEY_7830, resp.code) } catch (_: Throwable) {}
                    onHttpFailure7830(resp.code, resp.header("Retry-After")?.trim()?.toLongOrNull(), err, useModel)
                    return@use null
                }
                try { com.lifecyclebot.engine.ApiHealthMonitor.record(HEALTH_KEY_7830, resp.code, latency) } catch (_: Throwable) {}
                try { com.lifecyclebot.engine.ApiBackoff.markSuccess(HEALTH_KEY_7830) } catch (_: Throwable) {}
                rateLimitStreak = 0
                okCalls.incrementAndGet()
                val body = resp.body?.string().orEmpty()
                val msg = JSONObject(body).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                val tools = msg?.optJSONArray("executed_tools")
                if (tools != null) searchesUsed.addAndGet(tools.length().toLong())
                val content = if (msg == null || msg.isNull("content")) "" else msg.optString("content", "").trim()
                if (content.isBlank()) {
                    lastError = "empty_content"
                    try { PipelineHealthCollector.labelInc("LLM_SCOUT_EMPTY_CONTENT_7830") } catch (_: Throwable) {}
                    null
                } else {
                    try { PipelineHealthCollector.labelInc("LLM_SCOUT_CALL_OK_7830") } catch (_: Throwable) {}
                    content
                }
            }
        } catch (e: Exception) {
            httpErrors.incrementAndGet()
            lastError = "transport:${e.javaClass.simpleName}"
            backoffUntilMs = System.currentTimeMillis() + CADENCE_MS_7830
            try { com.lifecyclebot.engine.ApiHealthMonitor.record(HEALTH_KEY_7830, 599, System.currentTimeMillis() - started, errorBody = e.message) } catch (_: Throwable) {}
            try { PipelineHealthCollector.labelInc("LLM_SCOUT_TRANSPORT_ERROR_7830") } catch (_: Throwable) {}
            null
        }
    }

    private fun onHttpFailure7830(code: Int, retryAfterSec: Long?, err: String, usedModel: String) {
        val now = System.currentTimeMillis()
        lastError = "http_$code:${err.replace(Regex("\\s+"), " ").take(80)}"
        val other = if (usedModel == PRIMARY_MODEL_7830) FALLBACK_MODEL_7830 else PRIMARY_MODEL_7830
        when (code) {
            429 -> {
                rateLimited.incrementAndGet()
                rateLimitStreak += 1
                backoffUntilMs = now + backoffMs7830(rateLimitStreak, retryAfterSec)
                // Compound and compound-mini carry separate per-model limits.
                model = other
                try { PipelineHealthCollector.labelInc("LLM_SCOUT_RATE_LIMITED_7830") } catch (_: Throwable) {}
            }
            401, 403 -> {
                httpErrors.incrementAndGet()
                backoffUntilMs = now + MAX_BACKOFF_MS_7830
                try { PipelineHealthCollector.labelInc("LLM_SCOUT_AUTH_ERROR_7830") } catch (_: Throwable) {}
            }
            400, 404 -> {
                httpErrors.incrementAndGet()
                model = other
                backoffUntilMs = now + CADENCE_MS_7830
                try { PipelineHealthCollector.labelInc("LLM_SCOUT_HTTP_${code}_7830") } catch (_: Throwable) {}
            }
            else -> {
                httpErrors.incrementAndGet()
                backoffUntilMs = now + 2 * CADENCE_MS_7830
                try { PipelineHealthCollector.labelInc("LLM_SCOUT_HTTP_ERROR_7830") } catch (_: Throwable) {}
            }
        }
    }

    /** Pure: base58, 32..44 chars — the shape of a Solana public key. */
    internal fun isSolanaMintShape7830(s: String): Boolean = SOLANA_MINT_RX_7830.matches(s)

    /** Pure: "@name", "t.me/name", "https://t.me/s/name" -> "name"; null when not a public channel username. */
    internal fun normalizeTelegramChannel7830(raw: String): String? {
        var s = raw.trim().removePrefix("@")
        val i = s.indexOf("t.me/", ignoreCase = true)
        if (i >= 0) s = s.substring(i + 5)
        s = s.removePrefix("s/").substringBefore('/').substringBefore('?').removePrefix("@").trim()
        if (s.startsWith("+") || s.equals("joinchat", ignoreCase = true)) return null
        return if (TG_CHANNEL_RX_7830.matches(s)) s else null
    }

    /**
     * Pure: parse the model's answer defensively (code fences, prose around
     * the JSON, a bare array of tokens). Null when nothing parses.
     */
    internal fun parseScoutResponse7830(content: String): Parsed7830? {
        val text = content.replace("```json", "").replace("```", "")
        val objStart = text.indexOf('{')
        val objEnd = text.lastIndexOf('}')
        val arrStart = text.indexOf('[')
        var tokensArr: JSONArray? = null
        var chanArr: JSONArray? = null
        if (objStart >= 0 && objEnd > objStart && (arrStart < 0 || objStart < arrStart)) {
            val obj = try { JSONObject(text.substring(objStart, objEnd + 1)) } catch (_: Throwable) { null }
            if (obj != null) {
                tokensArr = obj.optJSONArray("tokens") ?: obj.optJSONArray("coins")
                chanArr = obj.optJSONArray("telegramChannels") ?: obj.optJSONArray("telegram_channels")
            }
        }
        if (tokensArr == null && chanArr == null && arrStart >= 0) {
            val arrEnd = text.lastIndexOf(']')
            if (arrEnd > arrStart) tokensArr = try { JSONArray(text.substring(arrStart, arrEnd + 1)) } catch (_: Throwable) { null }
        }
        if (tokensArr == null && chanArr == null) return null
        val tokens = ArrayList<Candidate7830>()
        if (tokensArr != null) {
            for (k in 0 until tokensArr.length()) {
                val o = tokensArr.optJSONObject(k) ?: continue
                val ca = o.optString("contractAddress", o.optString("mint", "")).trim()
                if (ca.isBlank() || ca == "null") continue
                tokens += Candidate7830(
                    symbol = o.optString("symbol", "").trim().removePrefix("$").take(16),
                    contractAddress = ca,
                    chain = o.optString("chain", "").trim().lowercase(),
                    narrative = o.optString("narrative", "").replace(Regex("\\s+"), " ").take(80),
                    sourceUrl = o.optString("sourceUrl", "").trim().take(200),
                    reason = o.optString("reason", "").replace(Regex("\\s+"), " ").take(160),
                    freshnessMinutes = o.optInt("freshnessMinutes", -1),
                )
            }
        }
        val chans = ArrayList<String>()
        if (chanArr != null) {
            for (k in 0 until chanArr.length()) {
                val c = normalizeTelegramChannel7830(chanArr.optString(k, "")) ?: continue
                if (c !in chans) chans += c
            }
        }
        return Parsed7830(tokens, chans)
    }

    /** Pure: the cheap untrusted-text checks that run before any network lookup. */
    internal fun passesShape7830(c: Candidate7830): Boolean =
        (c.chain == "solana" || c.chain == "sol") && isSolanaMintShape7830(c.contractAddress)

    private fun rememberChannels7830(list: List<String>, now: Long) {
        for (c in list) channels[c] = now
        if (channels.size > MAX_CHANNELS_7830) {
            channels.entries.sortedBy { it.value }.take(channels.size - MAX_CHANNELS_7830).forEach { channels.remove(it.key) }
        }
        if (list.isNotEmpty()) try { PipelineHealthCollector.labelInc("LLM_SCOUT_CHANNELS_SEEN_7830") } catch (_: Throwable) {}
    }

    /**
     * Shape check, dedupe against recent emissions, then a live DexScreener
     * pair for the exact mint (base token address must equal it, price and
     * liquidity > 0). Anything that fails is counted LLM_SCOUT_UNVERIFIED_7830.
     */
    private suspend fun verifyAll7830(cands: List<Candidate7830>): List<Verified7830> {
        val now = System.currentTimeMillis()
        recentlyEmitted.entries.removeIf { now - it.value > EMIT_DEDUPE_TTL_MS_7830 }
        val out = ArrayList<Verified7830>()
        val seenThisRun = HashSet<String>()
        for (c in cands) {
            if (!passesShape7830(c)) { markUnverified7830("shape"); continue }
            val mint = c.contractAddress
            if (!seenThisRun.add(mint) || recentlyEmitted.containsKey(mint)) {
                deduped.incrementAndGet()
                continue
            }
            if (c.reason.startsWith("FALLOUT:", ignoreCase = true)) {
                // Operator asked for pump.fun fallouts too. Labelled, not favoured:
                // the ordinary rug/safety intake decides (Field Manual L201).
                try { PipelineHealthCollector.labelInc("LLM_SCOUT_FALLOUT_CANDIDATE_7830") } catch (_: Throwable) {}
            }
            val pair = try { dex.getBestPair(mint) } catch (_: Throwable) { null }
            val baseOk = pair != null && (pair.tokenAddress.isBlank() || pair.tokenAddress == mint)
            if (pair == null || !baseOk || pair.candle.priceUsd <= 0.0 || pair.liquidity <= 0.0 ||
                pair.baseSymbol.uppercase() in QUOTE_SYMBOLS_7830
            ) {
                markUnverified7830("no_live_pair")
                continue
            }
            verified.incrementAndGet()
            out += Verified7830(
                mint = mint,
                symbol = pair.baseSymbol.ifBlank { c.symbol.ifBlank { mint.take(6) } },
                narrative = c.narrative,
                sourceUrl = c.sourceUrl,
                pair = pair,
            )
            delay(250L)
        }
        return out
    }

    private fun markUnverified7830(why: String) {
        unverified.incrementAndGet()
        try { PipelineHealthCollector.labelInc("LLM_SCOUT_UNVERIFIED_7830") } catch (_: Throwable) {}
        try { PipelineHealthCollector.labelInc("LLM_SCOUT_UNVERIFIED_7830_$why") } catch (_: Throwable) {}
    }

    fun statusLine(): String {
        val now = System.currentTimeMillis()
        val age = if (lastRunAtMs > 0L) "${(now - lastRunAtMs) / 1000}s" else "never"
        val bo = if (backoffUntilMs > now) "${(backoffUntilMs - now) / 1000}s" else "0"
        return "LLM_SCOUT_7830 running=${isRunning()} model=$model runs=${runs.get()} calls=${calls.get()} " +
            "today=$callsToday/$MAX_CALLS_PER_DAY_7830 okCalls=${okCalls.get()} rateLimited=${rateLimited.get()} " +
            "httpErr=${httpErrors.get()} parseFail=${parseFailures.get()} searches=${searchesUsed.get()} " +
            "candidatesReturned=${candidatesReturned.get()} verified=${verified.get()} unverified=${unverified.get()} " +
            "deduped=${deduped.get()} emitted=${emitted.get()} channelsDiscovered=${channels.size} " +
            "skip[noKey=${skippedNoKey.get()} backoff=${skippedBackoff.get()} budget=${skippedBudget.get()}] " +
            "backoff=$bo lastRun=$age" +
            (if (lastSample.isNotBlank()) " sample=[$lastSample]" else "") +
            (if (lastError.isNotBlank()) " lastError=$lastError" else "") + " authority=candidates_only"
    }
}
