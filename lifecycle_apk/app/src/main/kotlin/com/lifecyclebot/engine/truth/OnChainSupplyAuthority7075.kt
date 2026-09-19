package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.HealthAwareHttp
import com.lifecyclebot.engine.PipelineHealthCollector
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7075 §SUPPLY IS AN ON-CHAIN FACT, NOT SOMETHING TO INFER.
 *
 * Operator: "I want proper fucking data every where. I been pretty clear."
 *
 * THE CIRCULARITY I SHIPPED IN V5.0.7069
 * ======================================
 * 7069 was right that marketCap = price x supply is the identity that makes
 * price and cap check each other. It was wrong about where supply comes from.
 * It INFERRED supply from the first observation carrying both numbers:
 *
 *     supply = mcap / price          <- from an UNVERIFIED price
 *
 * and then used that supply to "verify" every later price. If the first price
 * was wrong, the supply is wrong by the same factor, and the identity then
 * locks the error in and repairs correct prices TOWARDS the wrong one. A check
 * derived from the thing it is checking is not a check.
 *
 * The operator's 5.0.7072 device report shows both halves of that:
 *
 *   supplyCaptured=63           against 2518 live rows — 2.5% coverage, so
 *                               97% of marks were passing unverified
 *   identityBroken=1795         the check firing hard where it DID have supply
 *   8A6dzN partial_25pct        sol=2.0798 from cost=0.0144, a 144x, while
 *                               EXIT_TRIGGER_BASIS_REBASED_4481 reported
 *                               rawGain=14429.6% mcapGain=0.0% on the SAME mint
 *                               seconds later — the cap had not moved at all
 *
 * THE FIX IS TO GO AND GET THE REAL NUMBER.
 * `getTokenSupply` is a standard Solana RPC method. It returns the mint's
 * actual supply and decimals from chain state. It is not an estimate, it does
 * not depend on any price, and it cannot be corrupted by a bad tick. Helius is
 * live on this device at sr=100%, and TelegramScraper has been making the same
 * shape of RPC call for the holder-concentration check for many builds.
 *
 * PROVENANCE IS CARRIED, NOT ASSUMED. TokenMetricsAuthority7069 now knows
 * whether a supply is ONCHAIN or INFERRED, and only an ONCHAIN supply — or an
 * inferred one CORROBORATED by independent observations — is allowed to repair
 * a price. An uncorroborated inference marks the mark unverifiable and changes
 * nothing, which is the honest answer when the second opinion is really just
 * the first one wearing a hat.
 *
 * NOT ON THE HOT PATH. Resolution is async, once per mint, cached forever, and
 * fired from the metrics authority when it first sees a mint it cannot verify.
 * Nothing waits on it: the price returns immediately, unverified, and becomes
 * verifiable when chain state arrives. HOT_PATH_PROVIDER_CALL_SENTINEL_4295
 * exists for exactly the mistake this avoids.
 *
 * ── V5.0.7116 §ONCE_PER_MINT_WAS_ONLY_TRUE_ON_SUCCESS ────────────────────────
 *
 * "Once per mint, cached forever" above was true of a RESOLVE and false of a
 * FAILURE. A failure wrote nothing to [resolved] and then reset the in-flight
 * gate, so the guard at the top of [requestAsync7075] — `resolved.containsKey`
 * — short-circuited successes only. Every mint that could not be resolved was
 * asked again on the very next observation, forever, each attempt on a brand
 * new OS thread. TokenMetricsAuthority7069.storedSupplyOf calls this on EVERY
 * read that lacks a supply, and DataLegitimacyAuthority7077 calls it from both
 * discovery and qualification.
 *
 * The operator's 5.0.7115 snapshot, 323 seconds of uptime:
 *
 *     requested=9795  resolved=39  failed=9751  cached=39
 *     Token metrics: observed=24388 supplyCaptured=39 unverifiable=23270
 *     helius  sr=100%  s=1                      <- ONE sample recorded
 *
 * 9,751 failures in 323s is ~30 requests per second, sustained, against a
 * free-tier RPC, for the same handful of mints over and over. And the health
 * monitor recorded ONE helius call, because this object built its own private
 * OkHttpClient and never went through HealthAwareHttp — so ApiBackoff could not
 * throttle it and the operator's API health table showed helius green while
 * 9,795 requests a session went out unattributed. That is why nobody saw it.
 *
 * THREE THINGS WERE WRONG AND ALL THREE COMPOUND:
 *
 *  1. NO NEGATIVE CACHE, so a failure is retried immediately and forever. The
 *     storm is self-sustaining: enough requests to get rate-limited, and a
 *     rate-limit is a failure, which triggers another request.
 *
 *  2. NOT HEALTH-AWARE, so nothing throttled the storm and nothing measured it.
 *
 *  3. NO FAILURE REASON. [fetchSupply] returned a bare null for eight different
 *     reasons — unsuccessful response, no body, no result object, decimals out
 *     of range, blank amount, unparseable amount, non-finite, non-positive —
 *     and the caller collapsed all eight into one `failed` counter. 9,751
 *     failures and not one of them recorded WHY, so "the RPC is rate-limiting
 *     us" and "these mints genuinely have no supply" were indistinguishable.
 *
 * AND IT POISONED THE GATE THAT DEPENDS ON IT. DataLegitimacyAuthority7077 arms
 * itself on `resolved / (resolved + failed) >= 0.60`, computed over ATTEMPTS
 * rather than over distinct mints. A retry storm therefore dilutes the ratio
 * with thousands of repeat failures of the same few mints and holds the gate
 * permanently disarmed — 0.4% on this snapshot — which is indistinguishable
 * from the provider being down. The gate's own doctrine is that a refusal must
 * never mean "the resolver is not running"; the storm made the inverse true,
 * where "running fine" could not be told from "drowning in its own retries".
 *
 * WHAT COUNTS AS A FAILURE NOW. SolanaOhlcvFeed6916 §6982 already wrote the
 * rule this needed, for the same defect one layer over: "A local decline is the
 * absence of an observation ... write nothing down, and count it as a skip
 * rather than as an empty result." So:
 *
 *   · the provider answered and this mint has no usable supply  -> FAILED,
 *     negative-cached for [NEGATIVE_TTL_MS]. This is evidence.
 *   · rate-limited, 5xx, transport error, or our own ApiBackoff refusal ->
 *     SKIPPED, cooled down for [COOLDOWN_MS], and NOT counted as failed,
 *     because we learned nothing about the mint. This is what makes 7077's
 *     arming ratio mean what it says.
 *
 * Concurrency is bounded by a two-thread pool with a short queue that SHEDS
 * overflow rather than accumulating it — dropping a request is correct here,
 * since the mint will be asked again on its next observation.
 */
object OnChainSupplyAuthority7075 {

    private const val TAG = "OnChainSupply7075"

    /** One in-flight resolve per mint; a second request is dropped, not queued. */
    private val inFlight = ConcurrentHashMap<String, AtomicBoolean>()

    /** Resolved supplies. Absent means not yet known, never "zero supply". */
    private val resolved = ConcurrentHashMap<String, Double>()

    /**
     * V5.0.7116 — mint -> earliest ms at which it may be asked again.
     *
     * This is the entry that did not exist. Without it `resolved.containsKey`
     * was the only suppression, so it suppressed successes and nothing else.
     */
    private val retryNotBefore = ConcurrentHashMap<String, Long>()

    /** The provider answered and this mint has no usable supply. Real evidence. */
    private const val NEGATIVE_TTL_MS = 6L * 60L * 60L * 1000L

    /** We were declined or could not reach the wire. Not evidence — just wait. */
    private const val COOLDOWN_MS = 90_000L

    private val requested = AtomicLong(0L)
    private val fetched = AtomicLong(0L)
    private val failed = AtomicLong(0L)
    private val corrections = AtomicLong(0L)

    // V5.0.7116 — the reasons that were collapsed into one `failed` counter.
    private val skippedCooldown = AtomicLong(0L)
    private val skippedNegative = AtomicLong(0L)
    private val skippedSaturated = AtomicLong(0L)
    private val declinedLocally = AtomicLong(0L)
    private val rateLimited = AtomicLong(0L)
    private val transportError = AtomicLong(0L)
    private val noSupply = AtomicLong(0L)
    private val malformed = AtomicLong(0L)

    @Volatile
    private var rpcUrl: String = ""

    /**
     * V5.0.7116 — what a resolve attempt actually learned. The point of the enum
     * is that RESOLVED/NO_SUPPLY/MALFORMED are observations OF THE MINT, while
     * RATE_LIMITED/TRANSPORT/DECLINED are statements about us and the provider
     * and say nothing about the mint at all.
     */
    private enum class Outcome7116 {
        RESOLVED, NO_SUPPLY, MALFORMED, RATE_LIMITED, TRANSPORT, DECLINED,
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /**
     * V5.0.7116 — bounded, and REJECTS overflow instead of queuing it.
     *
     * This replaced `Thread { ... }.start()` per request, which created 9,751
     * threads in 323 seconds on the 7115 snapshot. Shedding the overflow is
     * correct rather than a lossy compromise: a dropped resolve costs nothing
     * because the mint is asked again on its next observation, whereas a queue
     * deep enough to hold the backlog would replay a burst minutes later
     * against a provider that is already refusing.
     *
     * AbortPolicy, deliberately, NOT DiscardPolicy. DiscardPolicy drops the
     * task silently, so [resolveOnce7116] would never run and its `finally`
     * would never release that mint's in-flight gate — the mint would be
     * wedged at inFlight=true forever and could never be resolved again.
     * AbortPolicy throws, the caller catches it and releases the gate itself.
     * CallerRunsPolicy is wrong for the same reason the pool exists: it would
     * run the RPC on whichever thread was observing a token.
     */
    private val pool: java.util.concurrent.ThreadPoolExecutor by lazy {
        java.util.concurrent.ThreadPoolExecutor(
            2, 2, 30L, TimeUnit.SECONDS,
            java.util.concurrent.ArrayBlockingQueue<Runnable>(64),
            { r -> Thread(r, "onchain-supply-7075").apply { isDaemon = true } },
            java.util.concurrent.ThreadPoolExecutor.AbortPolicy(),
        )
    }

    /** ApiBackoff / ApiHealthMonitor label, so the storm is visible and throttled. */
    private fun hostLabel7116(): String =
        if (rpcUrl.contains("helius", ignoreCase = true)) "helius" else "solana_rpc"

    /**
     * Supply the RPC endpoint once at bootstrap. Until this is set the
     * authority simply reports nothing known — it never guesses a URL and never
     * blocks a caller waiting for one.
     */
    fun installRpc7075(url: String) {
        if (url.isBlank()) return
        rpcUrl = url
        try { PipelineHealthCollector.labelInc("ONCHAIN_SUPPLY_RPC_INSTALLED_7075") } catch (_: Throwable) {}
    }

    /** Resolved on-chain supply in whole tokens, or 0.0 when not yet known. */
    fun supplyOf7075(mint: String): Double {
        val s = resolved[mint] ?: return 0.0
        return if (s.isFinite() && s > 0.0) s else 0.0
    }

    /**
     * Ask for [mint]'s supply. Returns immediately. The caller is never blocked
     * and never receives a value from this call — the answer lands in the cache
     * and in TokenMetricsAuthority7069 when chain state replies.
     */
    fun requestAsync7075(mint: String) {
        if (mint.isBlank() || rpcUrl.isBlank()) return
        if (resolved.containsKey(mint)) return
        // V5.0.7116 — the suppression that was missing. Without this the only
        // guard was `resolved.containsKey`, which by construction never holds
        // for a mint that has failed, so every failure was retried on the next
        // observation of that mint, forever.
        val now = System.currentTimeMillis()
        val notBefore = retryNotBefore[mint]
        if (notBefore != null) {
            if (now < notBefore) {
                skippedCooldown.incrementAndGet()
                return
            }
            retryNotBefore.remove(mint)
        }
        sweepCooldowns7116()
        val gate = inFlight.computeIfAbsent(mint) { AtomicBoolean(false) }
        if (!gate.compareAndSet(false, true)) return
        requested.incrementAndGet()
        val submitted = try {
            pool.execute { resolveOnce7116(mint, gate) }
            true
        } catch (_: Throwable) {
            false
        }
        if (!submitted) {
            // The pool shed this request. Nothing reached the wire, so it is not
            // evidence and must not look like a failure — but the gate MUST be
            // released here, because the task that would have released it in its
            // `finally` never ran. Brief cooldown so a saturated burst does not
            // immediately re-saturate.
            skippedSaturated.incrementAndGet()
            retryNotBefore[mint] = System.currentTimeMillis() + COOLDOWN_MS
            gate.set(false)
        }
    }

    /**
     * V5.0.7116 — keep [retryNotBefore] from growing without bound.
     *
     * One entry per mint the resolver could not answer, held for up to
     * NEGATIVE_TTL_MS. The token meta cache alone carried 2,507 rows on the
     * 7115 snapshot, so over a long session this map is the only thing here
     * that can grow, and an unbounded map is the shape of half the defects
     * this session has removed. Swept lazily, never on a hot path.
     */
    private fun sweepCooldowns7116() {
        if (retryNotBefore.size < 20_000) return
        val now = System.currentTimeMillis()
        try {
            retryNotBefore.entries.removeIf { it.value <= now }
        } catch (_: Throwable) {}
    }

    /**
     * V5.0.7116 — one attempt, with the outcome classified rather than collapsed.
     *
     * The `finally` releases the in-flight gate exactly as before; what changed
     * is that every non-RESOLVED path now writes a [retryNotBefore] entry, so
     * releasing the gate no longer means "ask again immediately".
     */
    private fun resolveOnce7116(mint: String, gate: AtomicBoolean) {
        try {
            val (outcome, supply) = fetchSupply7116(mint)
            when (outcome) {
                Outcome7116.RESOLVED -> {
                    val s = supply ?: 0.0
                    resolved[mint] = s
                    retryNotBefore.remove(mint)
                    fetched.incrementAndGet()
                    try {
                        PipelineHealthCollector.labelInc("ONCHAIN_SUPPLY_RESOLVED_7075")
                        val replaced = TokenMetricsAuthority7069.acceptOnChainSupply7075(mint, s)
                        if (replaced) {
                            corrections.incrementAndGet()
                            PipelineHealthCollector.labelInc("ONCHAIN_SUPPLY_CORRECTED_INFERENCE_7075")
                            ForensicLogger.lifecycle(
                                "ONCHAIN_SUPPLY_CORRECTED_INFERENCE_7075",
                                "mint=${mint.take(10)} onChainSupply=${s.toLong()} " +
                                    "action=chain_state_replaces_price_derived_guess",
                            )
                        }
                    } catch (_: Throwable) {}
                }
                // The provider answered about THIS MINT and it has no usable
                // supply. That is evidence, it counts as a failure, and it is
                // worth remembering for hours rather than seconds.
                Outcome7116.NO_SUPPLY, Outcome7116.MALFORMED -> {
                    failed.incrementAndGet()
                    if (outcome == Outcome7116.NO_SUPPLY) noSupply.incrementAndGet()
                    else malformed.incrementAndGet()
                    retryNotBefore[mint] = System.currentTimeMillis() + NEGATIVE_TTL_MS
                    skippedNegative.incrementAndGet()
                    try { PipelineHealthCollector.labelInc("ONCHAIN_SUPPLY_UNRESOLVED_7075") } catch (_: Throwable) {}
                }
                // We learned nothing about the mint — see the §6982 rule in the
                // class doc. Cool down, count the reason, and do NOT let it
                // touch `failed`, because DataLegitimacyAuthority7077 reads
                // `failed` to decide whether its own refusals mean anything.
                Outcome7116.RATE_LIMITED, Outcome7116.TRANSPORT, Outcome7116.DECLINED -> {
                    when (outcome) {
                        Outcome7116.RATE_LIMITED -> rateLimited.incrementAndGet()
                        Outcome7116.TRANSPORT -> transportError.incrementAndGet()
                        else -> declinedLocally.incrementAndGet()
                    }
                    retryNotBefore[mint] = System.currentTimeMillis() + COOLDOWN_MS
                    try { PipelineHealthCollector.labelInc("ONCHAIN_SUPPLY_SKIPPED_NOT_EVIDENCE_7116") } catch (_: Throwable) {}
                }
            }
        } catch (t: Throwable) {
            transportError.incrementAndGet()
            retryNotBefore[mint] = System.currentTimeMillis() + COOLDOWN_MS
            try { ErrorLogger.debug(TAG, "resolve fail ${mint.take(10)}: ${t.message?.take(60)}") } catch (_: Throwable) {}
        } finally {
            gate.set(false)
        }
    }

    /**
     * `getTokenSupply` returns amount as a base-unit STRING and the decimals
     * separately. Whole tokens is amount / 10^decimals, and the string is read
     * as a string precisely because a large base-unit amount does not survive a
     * Double round trip — the defect class this whole authority exists to stop.
     */
    private fun fetchSupply7116(mint: String): Pair<Outcome7116, Double?> {
        val url = rpcUrl
        if (url.isBlank()) return Outcome7116.DECLINED to null
        val payload = """{"jsonrpc":"2.0","id":1,"method":"getTokenSupply","params":["$mint"]}"""
        val req = Request.Builder()
            .url(url)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        // V5.0.7116 — through HealthAwareHttp, not the raw client. This is what
        // puts the call into ApiHealthMonitor (helius read s=1 against 9,795
        // requests) and under ApiBackoff, so a sore RPC stops the storm at the
        // door instead of being hammered by it.
        val resp = HealthAwareHttp.execute(http, req, hostLabel7116())
        resp.use { r ->
            // Our own backoff refusal. Nothing went on the wire, so we learned
            // nothing about this mint — the §6982 rule.
            val synthetic = try {
                r.header(com.lifecyclebot.network.HostCircuitInterceptor.SYNTHETIC_HEADER_6969) != null
            } catch (_: Throwable) { false }
            if (synthetic) return Outcome7116.DECLINED to null
            if (!r.isSuccessful) {
                return when {
                    r.code == 429 || r.code == 403 || r.code >= 500 -> Outcome7116.RATE_LIMITED to null
                    else -> Outcome7116.TRANSPORT to null
                }
            }
            val body = r.body?.string() ?: return Outcome7116.TRANSPORT to null
            val json = try { JSONObject(body) } catch (_: Throwable) { return Outcome7116.MALFORMED to null }
            // A JSON-RPC error object is the provider declining, not a statement
            // that the mint has no supply. -32429/-32005 are rate limits.
            json.optJSONObject("error")?.let { err ->
                val code = err.optInt("code", 0)
                return if (code == -32429 || code == -32005 || code == -32097) {
                    Outcome7116.RATE_LIMITED to null
                } else {
                    Outcome7116.NO_SUPPLY to null
                }
            }
            val value = json.optJSONObject("result")?.optJSONObject("value")
                ?: return Outcome7116.NO_SUPPLY to null
            val decimals = value.optInt("decimals", -1)
            if (decimals !in 0..18) return Outcome7116.MALFORMED to null
            val raw = value.optString("amount", "")
            if (raw.isBlank()) return Outcome7116.MALFORMED to null
            val big = try { java.math.BigDecimal(raw) } catch (_: Throwable) {
                return Outcome7116.MALFORMED to null
            }
            val whole = big.movePointLeft(decimals).toDouble()
            return if (whole.isFinite() && whole > 0.0) Outcome7116.RESOLVED to whole
            else Outcome7116.NO_SUPPLY to null
        }
    }

    /**
     * V5.0.7077 — the resolver's own evidence, readable as numbers.
     *
     * DataLegitimacyAuthority7077 refuses to trade on unqualified data, and a
     * refusal is only meaningful if it can be told apart from the resolver
     * simply not running. These three let it check that first. `status()` is a
     * string for the report; a gate must not parse a report line to decide
     * whether money moves.
     */
    fun rpcReady7075(): Boolean = rpcUrl.isNotBlank()

    fun resolvedCount7075(): Long = fetched.get()

    fun failedCount7075(): Long = failed.get()

    /**
     * Diagnostic line for the pipeline report.
     *
     * V5.0.7116 — `failed` now means only "the provider answered about this mint
     * and it has no usable supply". Everything that used to hide inside it is
     * named: rl (rate-limited or 5xx), tx (transport), dec (our own ApiBackoff
     * refusal), sat (pool saturated and the request was discarded), cd (asked
     * again inside its cooldown and skipped). The three `skip*` figures are NOT
     * failures and deliberately do not enter DataLegitimacyAuthority7077's
     * arming ratio — see the §6982 rule quoted in the class doc.
     */
    fun status(): String =
        "rpc=${if (rpcUrl.isBlank()) "unset" else "set"} requested=${requested.get()} " +
            "resolved=${fetched.get()} failed=${failed.get()} " +
            "correctedInference=${corrections.get()} cached=${resolved.size} " +
            "noSupply7116=${noSupply.get()} malformed7116=${malformed.get()} " +
            "rl7116=${rateLimited.get()} tx7116=${transportError.get()} dec7116=${declinedLocally.get()} " +
            "sat7116=${skippedSaturated.get()} cd7116=${skippedCooldown.get()} " +
            "negCached7116=${skippedNegative.get()} cooling7116=${retryNotBefore.size}"
}
