package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ApiBackoff
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
    private val skippedProviderLocked7345 = AtomicLong(0L)

    /** V5.0.7345 — leave the tail of a lockout alone so ApiBackoff's one real
     *  probe per lockout still reaches the provider through HealthAwareHttp. */
    private const val LOCKOUT_PROBE_WINDOW_MS_7345 = 10_000L
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
    internal enum class Outcome7116 {
        RESOLVED, NO_SUPPLY, MALFORMED, RATE_LIMITED, TRANSPORT, DECLINED,
        /** V5.0.7809 — the node cannot see the account yet (fresh launch); retried soon, not evidence. */
        NOT_FOUND_YET_7809,
    }

    /** V5.0.7809 — retry delay for an account the node cannot see yet. */
    private const val NOT_FOUND_RETRY_MS_7809 = 20_000L
    /** V5.0.7809 — rungs of the RPC ladder a supply read may try (Helius first). */
    private const val SUPPLY_RUNGS_7809 = 3
    private val notFoundYet7809 = AtomicLong(0L)

    private val http: OkHttpClient by lazy {
        // V5.0.7881 — on the shared client so its Helius reads are metered.
        com.lifecyclebot.network.SharedHttpClient.builder()
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
        // V5.0.8418 — the mint-account record (any chain read: getTokenSupply, getAccountInfo, DAS) is the supply.
        val verified8418 = try { MintSupply8418.wholeOrZero8418(mint) } catch (_: Throwable) { 0.0 }
        if (verified8418 > 0.0) return verified8418
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
        // V5.0.8458 — not a Solana public key: no SPL mint account to read, and one such key refused a whole 100-mint batch.
        if (!com.lifecyclebot.network.KeylessRpc8359.solanaKey8458(mint)) { notSolanaKey8458.incrementAndGet(); return }
        if (resolved.containsKey(mint)) return
        if (try { MintSupply8418.chainRead8418(mint) } catch (_: Throwable) { false }) return   // V5.0.8418 — the mint account was already read
        // V5.0.7345 §DO_NOT_BUILD_A_REQUEST_THE_BACKOFF_WILL_REFUSE.
        //
        // With Helius at max usage for a whole run: requested=2245, dec7116=2237
        // (refused locally by ApiBackoff via HealthAwareHttp's synthetic 503),
        // resolved=0. Every one of those was a pool handoff, a JSON payload, an
        // OkHttp request and a fake response, only to be declined without
        // touching the network. While the provider is locked out, a DECLINED
        // attempt writes nothing to resolved/failed/fetched, so skipping it
        // changes no supply, no verdict and no trade. lockoutRemainingMs is used
        // rather than isLockedOut because the latter consumes the one probe
        // per lockout; the final 10s of the lockout is left to that probe.
        // V5.0.7809 — declined only when EVERY rung of the supply ladder is
        // locked; a Helius lockout alone now falls through to the next rung.
        if (ApiBackoff.lockoutRemainingMs(hostLabel7116()) > LOCKOUT_PROBE_WINDOW_MS_7345) {
            if (supplyRungs7809().all { ApiBackoff.lockoutRemainingMs(rungLabel7809(it)) > LOCKOUT_PROBE_WINDOW_MS_7345 }) {
                skippedProviderLocked7345.incrementAndGet()
                return
            }
        }
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
        if (enqueueBatch8445(mint, gate)) return   // V5.0.8445 — 100 mint accounts per read
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

    // ── V5.0.8445 §ONE_READ_FOR_A_HUNDRED_SUPPLIES ──
    // 8441 (10 min): 1,300 supply asks, 52 resolved; 8439: 767 of 5,622. Every mint was one getTokenSupply, one at a time on
    // two threads, against keyless nodes allowing ~30 calls / 10 s for everything and a paced Helius budget. A mint account
    // holds the supply and decimals itself, and getMultipleAccounts reads 100 of them in one call (jsonParsed: the same record
    // MintSupply8418.fromAccountInfoResult8418 already accepts from OnChainMintAuthorityProof7248). A batch answer that is
    // not an initialised mint falls back to that mint's own getTokenSupply; no answer at all is a local retry, not evidence.
    const val BATCH_KEYS_8445 = 100
    const val BATCH_GATHER_MS_8449 = 2_000L
    private const val MAX_BATCH_QUEUED_8445 = 2_000
    private val batch8445 = java.util.concurrent.ConcurrentHashMap<String, AtomicBoolean>()
    private val batchRunning8445 = AtomicBoolean(false)
    private val batchCalls8445 = AtomicLong(0L)
    private val batchResolved8445 = AtomicLong(0L)
    private val batchUnanswered8445 = AtomicLong(0L)
    private val batchFallback8445 = AtomicLong(0L)
    private val notSolanaKey8458 = AtomicLong(0L)

    private fun enqueueBatch8445(mint: String, gate: AtomicBoolean): Boolean {
        if (batch8445.size >= MAX_BATCH_QUEUED_8445) return false
        batch8445[mint] = gate
        if (batchRunning8445.compareAndSet(false, true)) try { pool.execute { drainBatch8445() } } catch (_: Throwable) {
            batchRunning8445.set(false)
            batch8445.remove(mint)
            return false
        }
        return true
    }

    private fun drainBatch8445() {
        try {
            while (true) {
                // V5.0.8449 — gather before reading: 8446 sent 3,697 calls for 5,963 asks (1.6 mints a call) because the drain
                // read whatever one mint had queued. Up to [BATCH_GATHER_MS_8449] for a full batch; a full batch goes at once.
                val gatherUntil = System.currentTimeMillis() + BATCH_GATHER_MS_8449
                while (batch8445.size < BATCH_KEYS_8445 && System.currentTimeMillis() < gatherUntil) {
                    try { Thread.sleep(100L) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
                }
                val mints = batch8445.keys.take(BATCH_KEYS_8445)
                if (mints.isEmpty()) break
                readBatch8445(mints)
            }
        } finally {
            batchRunning8445.set(false)
            if (batch8445.isNotEmpty() && batchRunning8445.compareAndSet(false, true)) try { pool.execute { drainBatch8445() } } catch (_: Throwable) {
                batchRunning8445.set(false)
            }
        }
    }

    /** Pure (V5.0.8445): one getMultipleAccounts `value` entry as the single-account result MintSupply8418 parses. */
    fun accountResult8445(context: JSONObject?, value: Any?): JSONObject? =
        if (value == null || value == JSONObject.NULL || value !is JSONObject) null
        else JSONObject().put("context", context ?: JSONObject()).put("value", value)

    private fun readBatch8445(mints: List<String>) {
        val params = org.json.JSONArray().put(org.json.JSONArray(mints))
            .put(JSONObject().put("encoding", "jsonParsed").put("commitment", "confirmed"))
        batchCalls8445.incrementAndGet()
        val result = try { com.lifecyclebot.network.KeylessRpc8359.post8359(http, "getMultipleAccounts", params, "supply-batch-8445") } catch (_: Throwable) { null }
        val values = result?.optJSONArray("value")
        val context = result?.optJSONObject("context")
        val now = System.currentTimeMillis()
        for ((i, mint) in mints.withIndex()) {
            val gate = batch8445.remove(mint) ?: continue
            var handedOff = false
            try {
                if (values == null) {
                    batchUnanswered8445.incrementAndGet()
                    retryNotBefore[mint] = now + DECLINED_LOCAL_RETRY_MS_8272
                    continue
                }
                val one = accountResult8445(context, values.opt(i))
                if (one == null) {
                    notFoundYet7809.incrementAndGet()
                    retryNotBefore[mint] = now + NOT_FOUND_RETRY_MS_7809
                    continue
                }
                val s = MintSupply8418.fromAccountInfoResult8418(mint, one, now)
                if (s == null) {
                    // Not an initialised SPL / Token-2022 mint in this answer: getTokenSupply states why on its own.
                    batchFallback8445.incrementAndGet()
                    handedOff = try { pool.execute { resolveOnce7116(mint, gate) }; true } catch (_: Throwable) { false }
                    if (!handedOff) retryNotBefore[mint] = now + COOLDOWN_MS
                    continue
                }
                MintSupply8418.accept8418(s, notifyMetrics = false)
                val whole = MintSupply8418.wholeOrZero8418(mint).takeIf { it > 0.0 } ?: s.wholeTokens
                if (!whole.isFinite() || whole <= 0.0) { retryNotBefore[mint] = now + COOLDOWN_MS; continue }
                resolved[mint] = whole
                retryNotBefore.remove(mint)
                fetched.incrementAndGet()
                batchResolved8445.incrementAndGet()
                try {
                    PipelineHealthCollector.labelInc("ONCHAIN_SUPPLY_RESOLVED_7075")
                    if (TokenMetricsAuthority7069.acceptOnChainSupply7075(mint, whole)) {
                        corrections.incrementAndGet()
                        PipelineHealthCollector.labelInc("ONCHAIN_SUPPLY_CORRECTED_INFERENCE_7075")
                    }
                } catch (_: Throwable) {}
            } finally {
                if (!handedOff) gate.set(false)
            }
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
                    // V5.0.8418 — the strongest / latest-slot record wins; this read never displaces a better one.
                    val s = (try { MintSupply8418.wholeOrZero8418(mint) } catch (_: Throwable) { 0.0 }).takeIf { it > 0.0 } ?: supply ?: 0.0
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
                // V5.0.7809 — a launch the node cannot see yet. Not evidence about
                // the mint, so not `failed`; asked again in seconds, not hours
                // (Field Manual L153: launch data goes stale fastest).
                Outcome7116.NOT_FOUND_YET_7809 -> {
                    notFoundYet7809.incrementAndGet()
                    retryNotBefore[mint] = System.currentTimeMillis() + NOT_FOUND_RETRY_MS_7809
                    try { PipelineHealthCollector.labelInc("ONCHAIN_SUPPLY_NOT_VISIBLE_YET_7809") } catch (_: Throwable) {}
                }
                Outcome7116.RATE_LIMITED, Outcome7116.TRANSPORT, Outcome7116.DECLINED -> {
                    when (outcome) {
                        Outcome7116.RATE_LIMITED -> rateLimited.incrementAndGet()
                        Outcome7116.TRANSPORT -> transportError.incrementAndGet()
                        else -> declinedLocally.incrementAndGet()
                    }
                    retryNotBefore[mint] = System.currentTimeMillis() + retryDelayMs8272(outcome)   // V5.0.8272 — local decline: 10 s, not 90 s
                    try { PipelineHealthCollector.labelInc(if (outcome == Outcome7116.DECLINED) "ONCHAIN_SUPPLY_DECLINED_LOCALLY_8272" else "ONCHAIN_SUPPLY_SKIPPED_NOT_EVIDENCE_7116") } catch (_: Throwable) {}
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
        // V5.0.7809 §ONE_ENDPOINT_WAS_THE_WHOLE_SUPPLY_LADDER. Only the installed
        // RPC was ever asked, so a Helius lockout or one bad reply left the mint
        // unverifiable (METRICS_IDENTITY / TOKEN_METRICS_UNVERIFIABLE) until the
        // next cooldown. Walk the same preferred-first ladder the curve reader
        // uses (Helius leads), skipping rungs in backoff, and stop at the first
        // rung that answers about the mint. Field Manual L190 / L404.
        var last: Pair<Outcome7116, Double?> = Outcome7116.DECLINED to null
        // V5.0.8272 (R1) — eligible rungs FIRST, then the attempt budget: a locked rung is skipped before it is counted,
        // so a healthy fourth endpoint is reached when the first three are locked. Network attempts and elapsed time
        // stay bounded; a locally declined rung (synthetic circuit reply) does not consume a network attempt either.
        val started = System.currentTimeMillis()
        var attempts = 0
        for (rung in eligibleRungs8272(supplyRungs7809()) { label -> ApiBackoff.lockoutRemainingMs(label) > LOCKOUT_PROBE_WINDOW_MS_7345 }) {
            if (attempts >= SUPPLY_RUNGS_7809 || System.currentTimeMillis() - started > LADDER_BUDGET_MS_8272) break
            val label = rungLabel7809(rung)
            val got = try { fetchSupplyFrom7809(mint, rung, label) } catch (_: Throwable) { Outcome7116.TRANSPORT to null }
            when (got.first) {
                Outcome7116.RESOLVED, Outcome7116.NO_SUPPLY, Outcome7116.NOT_FOUND_YET_7809, Outcome7116.MALFORMED -> return got
                Outcome7116.RATE_LIMITED, Outcome7116.TRANSPORT -> { attempts++; last = got }
                Outcome7116.DECLINED -> { if (last.first == Outcome7116.DECLINED) last = got }
            }
        }
        return last
    }

    /** Pure (V5.0.8272): the ladder in preferred order with locked rungs removed — selection before the budget. */
    internal fun eligibleRungs8272(rungs: List<String>, locked: (String) -> Boolean): List<String> =
        rungs.filter { it.isNotBlank() && !locked(rungLabel7809(it)) }

    /** Pure (V5.0.8272): the per-mint retry a non-evidence outcome earns. A LOCAL decline is not a provider fact. */
    internal fun retryDelayMs8272(outcome: Outcome7116): Long = when (outcome) {
        Outcome7116.DECLINED -> DECLINED_LOCAL_RETRY_MS_8272
        else -> COOLDOWN_MS
    }
    private const val LADDER_BUDGET_MS_8272 = 6_000L
    private const val DECLINED_LOCAL_RETRY_MS_8272 = 10_000L

    /**
     * V5.0.7809 — pure: what a JSON-RPC error object says about the mint.
     * Every non-rate-limit error used to be NO_SUPPLY (six-hour negative cache),
     * so a node hiccup (-32603 internal, -32000..-32016 slot/node states) or a
     * not-yet-visible fresh account wrote "this mint has no supply" down as
     * evidence. Only -32602 naming a non-mint is evidence; an account the node
     * cannot find yet is retried soon; anything else is not an observation.
     */
    fun rpcErrorClass7809(code: Int, message: String): String {
        val m = message.lowercase()
        return when {
            code == -32429 || code == -32005 || code == -32097 || code == 429 -> "RATE_LIMITED"
            // V5.0.8441 — "-32602 Indexed requests require a personal token" (publicnode) is the node refusing the call, not
            // the mint lacking a supply: it was written down as NO_SUPPLY for six hours and stopped the ladder there.
            com.lifecyclebot.network.KeylessRpc8359.capabilityRefusal8441(code, message) -> "TRANSPORT"
            m.contains("could not find") || m.contains("not found") -> "NOT_FOUND_YET"
            code == -32602 -> "NO_SUPPLY"
            else -> "TRANSPORT"
        }
    }

    private fun classifyRpcError7809(code: Int, message: String): Pair<Outcome7116, Double?> =
        when (rpcErrorClass7809(code, message)) {
            "RATE_LIMITED" -> Outcome7116.RATE_LIMITED to null
            "NOT_FOUND_YET" -> Outcome7116.NOT_FOUND_YET_7809 to null
            "NO_SUPPLY" -> Outcome7116.NO_SUPPLY to null
            else -> Outcome7116.TRANSPORT to null
        }

    /** V5.0.7809 — the preferred-first RPC ladder for supply reads (Helius leads). */
    private fun supplyRungs7809(): List<String> {
        val url = rpcUrl
        if (url.isBlank()) return emptyList()
        return (try {
            // superseded by V5.0.8359 — supply is a market read: the whole ladder, keyless public nodes first (was rpcCandidates(url), Helius first)
            com.lifecyclebot.engine.RuntimeProviderAuthority6685.marketReadCandidates8359(url, "getTokenSupply")   // V5.0.8272 — whole ladder; the budget is applied after lockout selection
        } catch (_: Throwable) { listOf(url) }).ifEmpty { listOf(url) }
    }

    /** V5.0.7809 — one health label per RPC host, matching the curve reader's. */
    private fun rungLabel7809(url: String): String {
        if (url.contains("helius", ignoreCase = true)) return "helius"
        val host = try { java.net.URI(url).host?.lowercase().orEmpty() } catch (_: Throwable) { "" }
        return if (host.isBlank()) "solana_rpc" else "rpc_$host"
    }

    private fun fetchSupplyFrom7809(mint: String, url: String, label: String): Pair<Outcome7116, Double?> {
        if (url.isBlank()) return Outcome7116.DECLINED to null
        // V5.0.8359 — the shared per-mint supply answer (KeylessRpc8359, 10 min) is read before any rung is asked, and a
        // keyless rung whose host governor is closed is declined locally (nothing on the wire, not a provider fact).
        com.lifecyclebot.network.KeylessRpc8359.supplyCache8359.getAt8418(mint, System.currentTimeMillis())?.let { (cached8359, receivedAtMs8418) ->
            com.lifecyclebot.network.KeylessRpc8359.noteCacheHit8359()
            val parsedCached8418 = supplyFromValue8359(cached8359.optJSONObject("value"))
            if (parsedCached8418.first == Outcome7116.RESOLVED) record8418(mint, cached8359, receivedAtMs8418)
            return parsedCached8418
        }
        if (!com.lifecyclebot.network.KeylessRpc8359.beforeCall8359(url)) return Outcome7116.DECLINED to null
        // V5.0.7809 — `confirmed`, not the node default `finalized`: a launch
        // seen on the create tape is ~13 s from finality, and asking at the
        // default answered "could not find account" for it — which this class
        // then remembered as NO_SUPPLY for six hours.
        val payload = """{"jsonrpc":"2.0","id":1,"method":"getTokenSupply","params":["$mint",{"commitment":"confirmed"}]}"""
        val req = Request.Builder()
            .url(url)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        // V5.0.7116 — through HealthAwareHttp, not the raw client. This is what
        // puts the call into ApiHealthMonitor (helius read s=1 against 9,795
        // requests) and under ApiBackoff, so a sore RPC stops the storm at the
        // door instead of being hammered by it.
        val resp = HealthAwareHttp.execute(http, req, label)
        resp.use { r ->
            // Our own backoff refusal. Nothing went on the wire, so we learned
            // nothing about this mint — the §6982 rule.
            val synthetic = try {
                r.header(com.lifecyclebot.network.HostCircuitInterceptor.SYNTHETIC_HEADER_6969) != null
            } catch (_: Throwable) { false }
            if (synthetic) return Outcome7116.DECLINED to null
            if (!r.isSuccessful) {
                com.lifecyclebot.network.KeylessRpc8359.afterCall8359(url, r.code)   // V5.0.8359 — keyless governor (429 closes the host)
                return when {
                    r.code == 429 || r.code == 403 || r.code >= 500 -> Outcome7116.RATE_LIMITED to null
                    else -> Outcome7116.TRANSPORT to null
                }
            }
            val body = r.body?.string() ?: return Outcome7116.TRANSPORT to null
            // V5.0.7809 — a body that is not JSON (proxy/HTML error page) says
            // nothing about the mint; it was MALFORMED and cached for six hours.
            val json = try { JSONObject(body) } catch (_: Throwable) { return Outcome7116.TRANSPORT to null }
            // A JSON-RPC error object is the provider declining, not a statement
            // that the mint has no supply. -32429/-32005 are rate limits.
            json.optJSONObject("error")?.let { err ->
                com.lifecyclebot.network.KeylessRpc8359.noteRpcError8441(url, "getTokenSupply", err.optInt("code", 0), err.optString("message", ""))
                com.lifecyclebot.network.KeylessRpc8359.afterCall8359(url, 0, err.optInt("code", 0))
                return classifyRpcError7809(err.optInt("code", 0), err.optString("message", ""))
            }
            com.lifecyclebot.network.KeylessRpc8359.afterCall8359(url, r.code)
            // V5.0.8359 — a keyless node lagging far behind the newest slot seen is not the present supply: next rung.
            if (com.lifecyclebot.network.KeylessRpc8359.staleAnswer8359(url, json)) return Outcome7116.TRANSPORT to null
            // V5.0.7809 — a null value is an account the node cannot see yet.
            val value = json.optJSONObject("result")?.optJSONObject("value")
                ?: return Outcome7116.NOT_FOUND_YET_7809 to null
            val parsed8359 = supplyFromValue8359(value)
            if (parsed8359.first == Outcome7116.RESOLVED) json.optJSONObject("result")?.let {
                val at8418 = System.currentTimeMillis()
                com.lifecyclebot.network.KeylessRpc8359.supplyCache8359.put8359(mint, it, at8418)
                record8418(mint, it, at8418)
            }
            return parsed8359
        }
    }

    /**
     * V5.0.8418 — keep the whole answer: base-unit amount, decimals, `context.slot` and when it was received. The
     * metrics authority is told by [resolveOnce7116] (with the strongest record), not here.
     */
    private fun record8418(mint: String, result: JSONObject, observedAtMs: Long) {
        try { MintSupply8418.accept8418(MintSupply8418.fromTokenSupplyResult8418(mint, result, observedAtMs), notifyMetrics = false) } catch (_: Throwable) {}
    }

    /** V5.0.8359 — pure: a getTokenSupply `value` object (string amount + decimals) as whole tokens. */
    internal fun supplyFromValue8359(value: JSONObject?): Pair<Outcome7116, Double?> {
        if (value == null) return Outcome7116.NOT_FOUND_YET_7809 to null
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
            "sat7116=${skippedSaturated.get()} cd7116=${skippedCooldown.get()} locked7345=${skippedProviderLocked7345.get()} " +
            "negCached7116=${skippedNegative.get()} cooling7116=${retryNotBefore.size} " +
            "notVisibleYet7809=${notFoundYet7809.get()} " +
            "batch8445[calls=${batchCalls8445.get()} resolved=${batchResolved8445.get()} unanswered=${batchUnanswered8445.get()} " +
            "fallback=${batchFallback8445.get()} queued=${batch8445.size}] notSolanaKey8458=${notSolanaKey8458.get()}"

    /** V5.0.8441 — DiagnosisBrief8438 G INPUTS: [requested, resolved, allRungsLocked, declinedLocally, noSupply]. */
    fun counts8441(): LongArray = longArrayOf(requested.get(), fetched.get(), skippedProviderLocked7345.get(), declinedLocally.get(), noSupply.get())
}
