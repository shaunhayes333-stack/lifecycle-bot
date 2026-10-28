package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.LearningPersistence
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7881 §SPEND_CREDITS_WHERE_THEY_EARN.
 *
 * Operator: "your also burning the fuck out of my helius credits. 5 million a
 * day on average ... it needs to be economic not limited. be smarter about how
 * we use our data calls."
 *
 * Nothing in the app knew what a Helius call cost. This object is the meter
 * and the purse:
 *
 *  METER   Every Helius REST call through SharedHttpClient is priced by method
 *          (batch-aware) by HeliusCreditMeterInterceptor7881, and both Helius
 *          websockets report the bytes they receive. Credits follow Helius's
 *          published table: standard RPC 1, DAS / getProgramAccounts 10,
 *          Enhanced Transactions (api.helius.xyz/v0) 100, websocket data 2 per
 *          0.1 MB, Sender 0.
 *
 *  PURSE   Three tiers.
 *          EXECUTION  sends, blockhash, balances, finality, fees — never refused.
 *          DECISION   mint/holder/supply/curve reads that safety and marks need —
 *                     metered; locally gated on budget or exhaustion.
 *          ENRICHMENT optional intelligence (insider scans, smart-money parsing
 *                     and streaming, bundle scans, swap-history candles, creator
 *                     history, market-sweep tx pulls) — admitted on a paced
 *                     daily budget, economically: each consumer has a base
 *                     share scaled by the measured value of its signal, and a
 *                     consumer whose signal is worth at least half its keep may
 *                     borrow from the unspent enrichment pool. A consumer whose
 *                     signal is measured to lose money is kept to a trickle so
 *                     it can still re-earn its share.
 *
 * Pacing spreads the day: by a fraction f of the UTC day, a consumer may have
 * spent its daily allowance x (f + 1/24) — no burst burns the day by noon.
 */
object HeliusCreditEconomy7881 {
    enum class Tier { EXECUTION, DECISION, ENRICHMENT }

    /** Enrichment consumers and their base share of the daily budget. */
    enum class Consumer(val share: Double) {
        INSIDER_SCAN(0.08),
        SMART_MONEY_DISCOVERY(0.05),
        SMART_MONEY_STREAM(0.08),
        BUNDLE_SCAN(0.10),
        SWAP_CANDLES(0.08),
        CREATOR_HISTORY(0.05),
        MARKET_SWEEP(0.06),
        DECISION_TAPE_8397(0.0),
    }

    /** Default daily target. 5.0.7880 era: ~5M credits/day observed by the operator. */
    // V5.0.8066 — owner, 2026-10-12: the month's 10M plan credits AND the $250 autoscaling cap were burnt with 13 days
    // left (59.7M used). The plan pays for ~333k a day; the target is 300k, and it is now enforced (overBudget8066).
    private const val DAILY_BUDGET_CREDITS = 300_000.0
    private const val ENRICHMENT_POOL_SHARE = 0.50
    internal const val DECISION_RESERVE_SHARE_8397 = 0.05
    private const val WS_CREDITS_PER_100KB = 2.0
    private const val PERSIST_EVERY_MS = 60_000L
    private const val PERSIST_KEY = "HELIUS_CREDIT_ECONOMY_7881"

    private val ENHANCED_DAS_METHODS = setOf(
        "getAsset", "getAssetBatch", "getAssetsByOwner", "getAssetsByCreator", "getAssetsByAuthority",
        "getAssetsByGroup", "searchAssets", "getAssetProof", "getAssetProofBatch", "getTokenAccounts",
        "getSignaturesForAsset", "getNftEditions", "getProgramAccounts",
    )
    private val EXECUTION_METHODS = setOf(
        "sendTransaction", "simulateTransaction", "getLatestBlockhash", "getSignatureStatuses", "getBalance",
        "getTokenAccountsByOwner", "getTokenAccountBalance", "getRecentPrioritizationFees", "getPriorityFeeEstimate",
        "getFeeForMessage", "getBlockHeight", "getHealth", "getSlot", "isBlockhashValid", "sendBundle",
    )
    private val ENRICHMENT_METHODS = setOf("getTransaction", "getSignaturesForAddress", "getTransactionsForAddress") + ENHANCED_DAS_METHODS

    // ── pure pricing ──

    fun isHelius(host: String): Boolean = host.contains("helius-rpc.com") || host.contains("helius.xyz")

    /** Pure: credits one JSON-RPC method costs. */
    private fun creditsForMethod(method: String): Double = when (method) {
        in ENHANCED_DAS_METHODS -> 10.0
        // Helius historical full-transaction RPC is 100 credits/call. Mispricing
        // it as 10 silently oversubscribes the paid enrichment budget.
        "getTransactionsForAddress" -> 100.0
        "getTransfersByAddress" -> 10.0
        "sendBundle" -> 10.0
        else -> 1.0
    }

    /** Pure: credits for a request to [host]/[path] carrying [methods] (empty for REST). */
    fun creditsFor(host: String, path: String, methods: List<String>): Double = when {
        host.contains("sender.helius-rpc.com") -> 0.0
        host.contains("api.helius.xyz") || path.startsWith("/v0/") -> 100.0
        methods.isEmpty() -> 1.0
        else -> methods.sumOf { creditsForMethod(it) }
    }

    /** Pure: the costliest tier any method in the request belongs to. */
    fun tierFor(host: String, path: String, methods: List<String>): Tier = when {
        host.contains("sender.helius-rpc.com") -> Tier.EXECUTION
        host.contains("api.helius.xyz") || path.startsWith("/v0/") -> Tier.ENRICHMENT
        methods.isNotEmpty() && methods.all { it in EXECUTION_METHODS } -> Tier.EXECUTION
        methods.any { it in ENRICHMENT_METHODS } -> Tier.ENRICHMENT
        else -> Tier.DECISION
    }

    /** Pure: websocket bytes to credits. */
    fun creditsForWsBytes(bytes: Long): Double = bytes.coerceAtLeast(0L) / 100_000.0 * WS_CREDITS_PER_100KB

    /**
     * Pure: may [consumer] spend [estCredits] more right now?
     * [value] is the consumer's measured signal value (0..1, see valueOf).
     */
    fun admits(
        budget: Double, dayFraction: Double, value: Double,
        consumerShare: Double, consumerSpent: Double,
        enrichmentSpent: Double, totalSpent: Double, estCredits: Double,
    ): Boolean {
        val pace = (dayFraction + 1.0 / 24.0).coerceIn(0.0, 1.0)
        val own = budget * consumerShare * value.coerceIn(0.0, 1.0) * pace
        if (consumerSpent + estCredits <= own) return true
        // Borrowing: only a consumer worth at least half its keep, only while the
        // whole day is on pace, and only from the enrichment pool's unspent part.
        if (value < 0.5 || totalSpent > budget * pace) return false
        return enrichmentSpent + estCredits <= budget * ENRICHMENT_POOL_SHARE * pace
    }

    // ── ledger ──

    private val byMethod = ConcurrentHashMap<String, DoubleArray>()      // [calls, credits]
    private val byConsumer = ConcurrentHashMap<String, DoubleArray>()    // [admitted, credits, denied]
    private val byTier = ConcurrentHashMap<Tier, DoubleArray>()          // [calls, credits]
    private val wsBytes = ConcurrentHashMap<String, AtomicLong>()
    @Volatile private var day: Long = -1L
    @Volatile private var total = 0.0
    @Volatile private var enrichmentAdmitted = 0.0
    // Charged at reservation, refunded only for unsent work. A restart conservatively keeps the charge.
    @Volatile private var decisionReserved8397 = 0.0
    @Volatile private var loaded = false
    @Volatile private var lastPersistMs = 0L

    private fun utcDay(nowMs: Long): Long = nowMs / 86_400_000L
    private fun dayFraction(nowMs: Long): Double = (nowMs % 86_400_000L) / 86_400_000.0

    private fun rollIfNewDay(nowMs: Long) {
        val d = utcDay(nowMs)
        // V5.0.8073 — the day's book is restored once the store is READY. It used to be tried on the very first metered
        // call (before LearningPersistence opened), found nothing, never retried, and the next save overwrote the day:
        // 5.0.8063 read today=738k at 16:15 UTC, 5.0.8064 read 39.9k at 17:19 UTC the same day — every install reset
        // the budget the hard gate (8066) enforces.
        if (!loaded && (try { LearningPersistence.ready() } catch (_: Throwable) { false })) synchronized(this) {
            if (!loaded) { loaded = true; restore(d) }
        }
        if (d == day) return
        synchronized(this) {
            if (d == day) return
            if (d != day) {
                byMethod.clear(); byConsumer.clear(); byTier.clear(); wsBytes.clear()
                total = 0.0; enrichmentAdmitted = 0.0; decisionReserved8397 = 0.0; day = d
            }
        }
    }

    /** V5.0.7888 — credits metered so far this UTC day (Cortex scoreboard: credits per decision). */
    fun creditsToday7888(nowMs: Long = System.currentTimeMillis()): Double {
        rollIfNewDay(nowMs)
        return synchronized(this) { total }
    }

    /** Metered call, from HeliusCreditMeterInterceptor7881. Never refuses. */
    fun meter(host: String, path: String, methods: List<String>, nowMs: Long = System.currentTimeMillis()) {
        try {
            rollIfNewDay(nowMs)
            val c = creditsFor(host, path, methods)
            val tier = tierFor(host, path, methods)
            val key = when {
                methods.isNotEmpty() -> methods.distinct().take(3).joinToString("+")
                path.startsWith("/v0/addresses") -> "v0/addresses/*/transactions"
                path.startsWith("/v0/") -> "v0" + path.removePrefix("/v0").take(24)
                else -> host.substringBefore('.').take(20)
            }
            synchronized(this) {
                byMethod.getOrPut(key) { DoubleArray(2) }.let { it[0] += 1.0; it[1] += c }
                byTier.getOrPut(tier) { DoubleArray(2) }.let { it[0] += 1.0; it[1] += c }
                total += c
            }
            maybePersist(nowMs)
        } catch (_: Throwable) {}
    }

    /** Websocket bytes received on [stream]; SMART_MONEY_STREAM also debits its consumer share. */
    fun meterWs(stream: String, bytes: Int, nowMs: Long = System.currentTimeMillis()) {
        if (bytes <= 0) return
        try {
            rollIfNewDay(nowMs)
            wsBytes.getOrPut(stream) { AtomicLong(0L) }.addAndGet(bytes.toLong())
            val c = creditsForWsBytes(bytes.toLong())
            synchronized(this) {
                total += c
                byTier.getOrPut(if (stream == Consumer.SMART_MONEY_STREAM.name) Tier.ENRICHMENT else Tier.DECISION) { DoubleArray(2) }
                    .let { it[1] += c }
                if (stream == Consumer.SMART_MONEY_STREAM.name) {
                    byConsumer.getOrPut(stream) { DoubleArray(3) }[1] += c
                    enrichmentAdmitted += c
                }
            }
            maybePersist(nowMs)
        } catch (_: Throwable) {}
    }

    /**
     * V5.0.8066 — HARD budget: true when today's metered credits (REST + websocket bytes) are past the day's pace
     * (budget x (fraction of the day + 1 h)). HeliusCreditMeterInterceptor7881 then answers every non-execution Helius
     * call locally with a 429, and the token websocket takes no new non-held subscription. Execution never stops.
     */
    // ── V5.0.8094 — provider-side exhaustion ─────────────────────────────────
    // Owner, 2026-10-14: "I ran out of helius credit." The plan's credits were spent, Helius answered every call with
    // an exhaustion error, and every consumer kept calling: HeliusSwapCandles7819 httpErr with 0 prints, token
    // subscriptions 0, setups DATA_INCOMPLETE. A provider exhaustion answer now latches for [EXHAUSTED_LATCH_MS_8094];
    // non-execution calls are answered locally (callers fall to their next source) with one probe per window.
    private const val EXHAUSTED_LATCH_MS_8094 = 15L * 60_000L
    // 8197: one independent success probe each minute can disprove an old
    // provider-exhaustion latch without reopening optional request floods.
    private const val RECOVERY_PROBE_MS_8197 = 60_000L
    fun recoveryProbeDue8197(nowMs: Long, lastProbeAtMs: Long): Boolean =
        nowMs - lastProbeAtMs >= RECOVERY_PROBE_MS_8197
    @Volatile private var exhaustedUntilMs8094 = 0L
    @Volatile private var lastProbeMs8094 = 0L
    private val exhaustedLatches8094 = AtomicLong(0)

    /** Pure: does a Helius error answer say the plan's credits/usage are exhausted (not a burst rate limit)? */
    fun isExhaustionAnswer8094(code: Int, body: String): Boolean {
        if (code !in setOf(402, 403, 429)) return false
        val b = body.lowercase()
        if (b.contains("aate_helius_budget") || b.contains("aate_helius_exhausted")) return false
        if (rateLimitWording8191(b)) return false   // V5.0.8191 — a per-second limit names itself; it is not spent credits
        return b.contains("credit") || b.contains("max usage") || b.contains("usage limit") ||
            b.contains("quota") || b.contains("upgrade your plan") || b.contains("exceeded your")
    }

    // ── V5.0.8191 §A_RATE_LIMIT_IS_NOT_EXHAUSTION ──
    // 5.0.8186: exhausted8094=LATCHED (55 latches) at 23% of the credit budget. Helius's per-second 429 answers carry
    // "exceeded your ... upgrade your plan" wording, which the exhaustion test read as spent credits, so every RPS burst
    // latched 15 minutes of local 429s for all non-execution Helius traffic. A body that names a rate limit is never
    // exhaustion now, and a 429 / in-body exhaustion answer latches only when [EXHAUST_CONFIRM_N_8191] of them arrive
    // within [EXHAUST_CONFIRM_MS_8191] (a 402 Payment Required latches at once).

    private const val EXHAUST_CONFIRM_N_8191 = 3
    const val EXHAUST_CONFIRM_MS_8191 = 120_000L
    private val exhaustAnswers8191 = java.util.ArrayDeque<Long>()
    private val exhaustUnconfirmed8191 = AtomicLong(0)

    /** Pure: the answer names a per-second / request-rate limit (not a spent allowance). */
    fun rateLimitWording8191(lowerBody: String): Boolean {
        val b = lowerBody.lowercase()
        return b.contains("rate limit") || b.contains("rate-limit") || b.contains("ratelimit") || b.contains("rate limited") ||
            b.contains("too many requests") || b.contains("per second") || b.contains("requests per") || b.contains(" rps")
    }

    /** Pure: do [answerTimes] (ms, oldest first) confirm exhaustion at [nowMs]? */
    fun exhaustionConfirmed8191(answerTimes: List<Long>, nowMs: Long, n: Int = EXHAUST_CONFIRM_N_8191, windowMs: Long = EXHAUST_CONFIRM_MS_8191): Boolean =
        answerTimes.count { nowMs - it in 0L..windowMs } >= n

    /**
     * One provider exhaustion answer with HTTP [code]: a 402 latches now; anything else latches once confirmed by
     * repeated answers (see above). Returns true when the latch was set.
     */
    fun noteExhaustionAnswer8191(code: Int, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (code == 402) { noteExhausted8094(nowMs); return true }
        val confirmed = synchronized(exhaustAnswers8191) {
            exhaustAnswers8191.addLast(nowMs)
            while (exhaustAnswers8191.isNotEmpty() && nowMs - exhaustAnswers8191.peekFirst() > EXHAUST_CONFIRM_MS_8191) exhaustAnswers8191.pollFirst()
            while (exhaustAnswers8191.size > 16) exhaustAnswers8191.pollFirst()
            exhaustionConfirmed8191(exhaustAnswers8191.toList(), nowMs)
        }
        if (confirmed) { noteExhausted8094(nowMs); return true }
        exhaustUnconfirmed8191.incrementAndGet()
        try { PipelineHealthCollector.labelInc("HELIUS_EXHAUSTION_ANSWER_UNCONFIRMED_8191") } catch (_: Throwable) {}
        return false
    }

    fun noteExhausted8094(nowMs: Long = System.currentTimeMillis()) {
        exhaustedUntilMs8094 = nowMs + EXHAUSTED_LATCH_MS_8094
        exhaustedLatches8094.incrementAndGet()
        try { PipelineHealthCollector.labelInc("HELIUS_PROVIDER_EXHAUSTED_8094") } catch (_: Throwable) {}
    }

    /**
     * 8196: a confirmed successful provider request disproves a stale
     * provider-exhaustion latch. Without this, even a successful recovery
     * probe left local Helius REST and WebSocket subscriptions disabled for
     * the remainder of the 15-minute latch (the wallet cannot afford that
     * missed launch coverage). The independent daily credit budget remains.
     */
    fun providerRecovered8196(code: Int, body: String): Boolean =
        code in 200..299 && !Regex("\\\"error\\\"\\s*:\\s*\\{", RegexOption.IGNORE_CASE).containsMatchIn(body)

    fun clearExhaustionOnProof8196(nowMs: Long = System.currentTimeMillis()) {
        if (exhaustedUntilMs8094 <= 0L) return
        exhaustedUntilMs8094 = 0L
        lastProbeMs8094 = nowMs
        synchronized(exhaustAnswers8191) { exhaustAnswers8191.clear() }
        try { PipelineHealthCollector.labelInc("HELIUS_EXHAUSTION_RECOVERED_ON_PROOF_8196") } catch (_: Throwable) {}
    }

    /** True while latched, except one probe per latch window (so recovery is noticed without a restart). */
    fun exhaustedGate8094(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (nowMs >= exhaustedUntilMs8094) return false
        // Atomic probe lease: concurrent scanner jobs must not all escape the
        // latch on the same heartbeat. Full execution and hard daily budget
        // remain under their existing independent authorities.
        return synchronized(exhaustAnswers8191) {
            if (nowMs >= exhaustedUntilMs8094) false
            else if (recoveryProbeDue8197(nowMs, lastProbeMs8094)) {
                lastProbeMs8094 = nowMs
                false
            } else true
        }
    }

    fun exhaustedLatched8094(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs < exhaustedUntilMs8094

    fun overBudget8066(nowMs: Long = System.currentTimeMillis()): Boolean = try {
        rollIfNewDay(nowMs)
        val over = synchronized(this) { total } >= ordinaryLimit8397(DAILY_BUDGET_CREDITS, dayFraction(nowMs))
        if (over) refused8066.incrementAndGet()
        over
    } catch (_: Throwable) { false }
    private val refused8066 = AtomicLong(0)

    /**
     * V5.0.8399 §TAPE_IS_DECISION_EVIDENCE — the absolute day cap, not the pro-rated ordinary pace. 5.0.8398 ran all day ON
     * the pace line (creditsToday 141,856 vs an ordinary limit of ~141.6k at 10:55 UTC, pace 311k/day vs 300k): overBudget8066
     * flipped minute by minute, and the first thing it cut was the near-decision parsed swap tape (tapeSub=NONE, tapeMints=0,
     * 8 resubscribes in 10 min) and the program curve tape — the very prints every lane then reported as tape8100:NO_PRINTS.
     * The tape's bytes are metered at [WS_CREDITS_PER_100KB] (cents of the bulk REST spend); it is decision evidence and is
     * paced by its own byte governor (PumpSwapTape8307.tapeWidth8307), so only the hard cap may switch it off.
     */
    fun overHardBudget8399(nowMs: Long = System.currentTimeMillis()): Boolean = try {
        rollIfNewDay(nowMs)
        val over = synchronized(this) { total } >= DAILY_BUDGET_CREDITS
        if (over) refusedHard8399.incrementAndGet()
        over
    } catch (_: Throwable) { false }
    private val refusedHard8399 = AtomicLong(0)

    /** Five percent of the existing daily allowance is protected from optional work, never added to it. */
    internal fun ordinaryLimit8397(budget: Double, fraction: Double): Double =
        budget * (1.0 - DECISION_RESERVE_SHARE_8397) * (fraction + 1.0 / 24.0).coerceIn(0.0, 1.0)

    internal fun reserveFits8397(budget: Double, spent: Double, reserved: Double, estimate: Double): Boolean =
        estimate.isFinite() && estimate > 0.0 && spent + estimate <= budget &&
            reserved + estimate <= budget * DECISION_RESERVE_SHARE_8397

    fun dailyRoom8397(nowMs: Long = System.currentTimeMillis()): Boolean {
        rollIfNewDay(nowMs)
        return total < DAILY_BUDGET_CREDITS
    }

    /** A bounded, explicit decision-evidence lease; optional scanners cannot obtain one through admit(). */
    fun reserveDecision8397(consumer: Consumer, estimate: Double, nowMs: Long): Reservation8386? = synchronized(this) {
        rollIfNewDay(nowMs)
        if (exhaustedLatched8094(nowMs) ||
            !reserveFits8397(DAILY_BUDGET_CREDITS, total, decisionReserved8397, estimate)) return@synchronized null
        decisionReserved8397 += estimate
        byConsumer.getOrPut(consumer.name) { DoubleArray(3) }.let { it[0]++; it[1] += estimate }
        enrichmentAdmitted += estimate
        maybePersist(nowMs, force8397 = true)
        Reservation8386(consumer, estimate, day.toString(), true)
    }

    /** The wire adapter uses the same lease as the producer. Meter even an uncertain network attempt exactly once. */
    fun authorizeWire8397(host: String, path: String, methods: List<String>, reservation: Reservation8386?,
                          nowMs: Long = System.currentTimeMillis()): Boolean = synchronized(this) {
        rollIfNewDay(nowMs)
        val cost = creditsFor(host, path, methods)
        val execution = tierFor(host, path, methods) == Tier.EXECUTION
        var reserved = reservation?.let {
            it.decision8397 && it.dayAt == day.toString() && it.remaining8397() + 1e-9 >= cost
        } == true
        // Optional work may consume ordinary headroom after a priority job was queued. Its original priority tag
        // can claim only this wire request's cost from the protected reserve, rather than being refused a second time.
        if (!execution && !reserved && total + cost > ordinaryLimit8397(DAILY_BUDGET_CREDITS, dayFraction(nowMs)) &&
            reservation?.priority8397 == true && reservation.dayAt == day.toString() && reservation.remaining8397() >= cost &&
            reserveFits8397(DAILY_BUDGET_CREDITS, total, decisionReserved8397, cost)) {
            decisionReserved8397 += cost; reserved = true
            maybePersist(nowMs, force8397 = true)
        }
        if (!execution && (total + cost > DAILY_BUDGET_CREDITS ||
                (!reserved && total + cost > ordinaryLimit8397(DAILY_BUDGET_CREDITS, dayFraction(nowMs))))) {
            refused8066.incrementAndGet(); return@synchronized false
        }
        reservation?.sent8386(cost)
        meter(host, path, methods, nowMs)
        true
    }

    /** Pure (V5.0.8066): is a request of this tier ever refused by the hard budget? Execution is never refused. */
    fun budgetGated8066(tier: Tier): Boolean = tier != Tier.EXECUTION

    /** Measured value of a consumer's signal, 0..1. */
    fun valueOf(consumer: Consumer): Double = when (consumer) {
        Consumer.INSIDER_SCAN, Consumer.SMART_MONEY_DISCOVERY, Consumer.SMART_MONEY_STREAM ->
            try { SignalSourceProof7291.economicValue7881(SignalSourceProof7291.Source.COPY) } catch (_: Throwable) { 0.5 }
        else -> 1.0
    }

    /**
     * Enrichment admission: true to spend [estCredits] on [consumer] now. A
     * refusal is the caller's cue to serve its cache or skip — never to retry.
     */
    fun admit(consumer: Consumer, estCredits: Double, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (exhaustedLatched8094(nowMs)) {
            try { PipelineHealthCollector.labelInc("HELIUS_EXHAUSTED_DEFERRED_8094_${consumer.name}") } catch (_: Throwable) {}
            return false
        }
        return admitWithinBudget8094(consumer, estCredits, nowMs)
    }

    /**
     * V5.0.8307 — pure: may a DECISION-priority job (a held or near-decision coin) spend [estCredits] beyond its consumer's
     * own share? Only while the whole day stays on pace (total + est within the paced budget): the credits the shared
     * wallet-read cache frees go to the coins being decided, never past the day's budget.
     */
    fun priorityAdmits8307(budget: Double, dayFraction: Double, totalSpent: Double, estCredits: Double): Boolean {
        val pace = (dayFraction + 1.0 / 24.0).coerceIn(0.0, 1.0)
        return totalSpent + estCredits <= budget * pace
    }

    private val priorityAdmittedN8307 = AtomicLong(0)

    /**
     * V5.0.8307 — [admit] for a held / near-decision job: its own share first; then any on-pace headroom of the day
     * (5.0.8306: swap candles budget-skipped 835 of 836 while getTokenAccountsByOwner duplicates spent the pace).
     */
    fun admitPriority8307(consumer: Consumer, estCredits: Double, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (admit(consumer, estCredits, nowMs)) return true
        if (exhaustedLatched8094(nowMs)) return false
        return try {
            rollIfNewDay(nowMs)
            val ok = synchronized(this) {
                val allowed = priorityAdmits8307(DAILY_BUDGET_CREDITS, dayFraction(nowMs), total, estCredits)
                if (allowed) {
                    val row = byConsumer.getOrPut(consumer.name) { DoubleArray(3) }
                    row[0] += 1.0; row[1] += estCredits; enrichmentAdmitted += estCredits
                }
                allowed
            }
            if (ok) {
                priorityAdmittedN8307.incrementAndGet()
                try { PipelineHealthCollector.labelInc("HELIUS_ECONOMY_PRIORITY_ADMIT_8307_${consumer.name}") } catch (_: Throwable) {}
            }
            ok
        } catch (_: Throwable) { false }
    }

    /** Reservations affect admission estimates only. The HTTP meter remains the sole actual-spend writer. */
    class Reservation8386 internal constructor(val consumer: Consumer, val estimate: Double, internal val dayAt: String,
                                               internal val decision8397: Boolean = false,
                                               internal val priority8397: Boolean = decision8397) {
        private var sent = 0.0
        private var closed = false
        @Synchronized fun sent8386(credits: Double) { if (!closed && credits.isFinite() && credits > 0) sent += credits }
        @Synchronized internal fun remaining8397(): Double = if (closed) 0.0 else (estimate - sent).coerceAtLeast(0.0)
        @Synchronized internal fun unused8386(): Double? {
            if (closed) return null
            closed = true
            // An in-flight websocket frame can cross a lease's byte boundary. Charge that overshoot too.
            return (estimate - sent).coerceAtMost(estimate)
        }
    }

    fun reserve8386(consumer: Consumer, estimate: Double, priority: Boolean, nowMs: Long): Reservation8386? = synchronized(this) {
        if (!(estimate > 0) || !estimate.isFinite()) return@synchronized null
        rollIfNewDay(nowMs)
        if (total + estimate > ordinaryLimit8397(DAILY_BUDGET_CREDITS, dayFraction(nowMs)))
            return@synchronized if (priority) reserveDecision8397(consumer, estimate, nowMs) else null
        val ok = if (priority) admitPriority8307(consumer, estimate, nowMs) else admit(consumer, estimate, nowMs)
        if (ok) Reservation8386(consumer, estimate, day.toString(), priority8397 = priority) else null
    }

    fun finish8386(reservation: Reservation8386) {
        val unused = reservation.unused8386() ?: return
        synchronized(this) {
            if (reservation.dayAt != day.toString()) return
            val row = byConsumer[reservation.consumer.name] ?: return
            row[1] = (row[1] - unused).coerceAtLeast(0.0)
            enrichmentAdmitted = (enrichmentAdmitted - unused).coerceAtLeast(0.0)
            if (reservation.decision8397) decisionReserved8397 = (decisionReserved8397 - unused).coerceAtLeast(0.0)
        }
    }

    private fun admitWithinBudget8094(consumer: Consumer, estCredits: Double, nowMs: Long): Boolean = try {
        rollIfNewDay(nowMs)
        val value = valueOf(consumer)
        val ok = synchronized(this) {
            val row = byConsumer.getOrPut(consumer.name) { DoubleArray(3) }
            val allowed = admits(DAILY_BUDGET_CREDITS, dayFraction(nowMs), value, consumer.share, row[1],
                enrichmentAdmitted, total, estCredits)
            if (allowed) { row[0] += 1.0; row[1] += estCredits; enrichmentAdmitted += estCredits } else row[2] += 1.0
            allowed
        }
        if (!ok) try { PipelineHealthCollector.labelInc("HELIUS_ECONOMY_DEFERRED_7881_${consumer.name}") } catch (_: Throwable) {}
        ok
    } catch (_: Throwable) { true }

    /**
     * The smart-money stream's affordable width: how many of [requested]
     * wallets to watch, given its value and how its spend is pacing.
     */
    fun streamWidth(requested: Int, nowMs: Long = System.currentTimeMillis()): Int {
        if (requested <= 0) return 0
        rollIfNewDay(nowMs)
        val value = valueOf(Consumer.SMART_MONEY_STREAM)
        val cap = (requested * value).toInt().coerceIn(minOf(requested, 3), requested)
        val pace = (dayFraction(nowMs) + 1.0 / 24.0).coerceIn(0.0, 1.0)
        val allowance = DAILY_BUDGET_CREDITS * Consumer.SMART_MONEY_STREAM.share * value * pace
        val spent = byConsumer[Consumer.SMART_MONEY_STREAM.name]?.get(1) ?: 0.0
        val ratio = if (allowance > 0.0) spent / allowance else 2.0
        return when {
            ratio >= 1.0 -> (cap / 2).coerceAtLeast(minOf(requested, 3))
            ratio >= 0.8 -> (cap * 3 / 4).coerceAtLeast(minOf(requested, 3))
            else -> cap
        }
    }

    // ── persistence ──

    private fun maybePersist(nowMs: Long, force8397: Boolean = false) {
        if (!force8397 && nowMs - lastPersistMs < PERSIST_EVERY_MS) return
        // V5.0.8073 — never save over the stored day before it has been read back.
        if (!loaded) return
        lastPersistMs = nowMs
        try {
            val o = org.json.JSONObject()
            synchronized(this) {
                o.put("day", day); o.put("total", total); o.put("enrich", enrichmentAdmitted)
                o.put("decisionReserved8397", decisionReserved8397)
                o.put("method", org.json.JSONObject().also { j -> byMethod.forEach { (k, v) -> j.put(k, "${v[0]},${v[1]}") } })
                o.put("consumer", org.json.JSONObject().also { j -> byConsumer.forEach { (k, v) -> j.put(k, "${v[0]},${v[1]},${v[2]}") } })
                o.put("tier", org.json.JSONObject().also { j -> byTier.forEach { (k, v) -> j.put(k.name, "${v[0]},${v[1]}") } })
                o.put("ws", org.json.JSONObject().also { j -> wsBytes.forEach { (k, v) -> j.put(k, v.get()) } })
            }
            LearningPersistence.save(PERSIST_KEY, o.toString())
        } catch (_: Throwable) {}
    }

    private fun restore(today: Long) {
        try {
            val o = org.json.JSONObject(LearningPersistence.load(PERSIST_KEY) ?: return)
            if (o.optLong("day", -1L) != today) return
            fun parse(s: String, n: Int): DoubleArray {
                val f = s.split(','); val a = DoubleArray(n)
                for (i in 0 until minOf(n, f.size)) a[i] = f[i].toDoubleOrNull() ?: 0.0
                return a
            }
            // V5.0.8073 — merged onto what this run already metered before the store opened (not replaced).
            fun add(a: DoubleArray?, b: DoubleArray): DoubleArray = if (a == null) b else DoubleArray(maxOf(a.size, b.size)) { i -> a.getOrElse(i) { 0.0 } + b.getOrElse(i) { 0.0 } }
            val sameDay = day == today || day == -1L || total == 0.0
            if (!sameDay) return
            o.optJSONObject("method")?.let { j -> for (k in j.keys()) byMethod[k] = add(byMethod[k], parse(j.optString(k), 2)) }
            o.optJSONObject("consumer")?.let { j -> for (k in j.keys()) byConsumer[k] = add(byConsumer[k], parse(j.optString(k), 3)) }
            o.optJSONObject("tier")?.let { j -> for (k in j.keys()) try { val t = Tier.valueOf(k); byTier[t] = add(byTier[t], parse(j.optString(k), 2)) } catch (_: Throwable) {} }
            o.optJSONObject("ws")?.let { j -> for (k in j.keys()) wsBytes.getOrPut(k) { AtomicLong(0L) }.addAndGet(j.optLong(k)) }
            total += o.optDouble("total", 0.0); enrichmentAdmitted += o.optDouble("enrich", 0.0)
            decisionReserved8397 += o.optDouble("decisionReserved8397", 0.0).coerceAtLeast(0.0)
            day = today
        } catch (_: Throwable) {}
    }

    fun statusLine(nowMs: Long = System.currentTimeMillis()): String {
        rollIfNewDay(nowMs)
        val f = dayFraction(nowMs)
        val projected = if (f > 0.01) total / f else total
        fun k(v: Double) = if (v >= 1_000_000) "%.2fM".format(v / 1_000_000) else if (v >= 1_000) "%.1fk".format(v / 1_000) else "%.0f".format(v)
        return synchronized(this) {
            "  exhausted8094=${if (exhaustedLatched8094(nowMs)) "LATCHED" else "no"}(latches=${exhaustedLatches8094.get()} unconfirmed8191=${exhaustUnconfirmed8191.get()})" +
            "  today=${k(total)} credits  pace→${k(projected)}/day  budget=${k(DAILY_BUDGET_CREDITS)}/day (${"%.0f".format(total / DAILY_BUDGET_CREDITS * 100)}%) hardGate8066[refused=${refused8066.get()}] priorityAdmit8307=${priorityAdmittedN8307.get()} decisionReserve8397=${k(decisionReserved8397)}/${k(DAILY_BUDGET_CREDITS * DECISION_RESERVE_SHARE_8397)}\n" +
                "  tiers: " + Tier.values().joinToString(" · ") { t -> byTier[t]?.let { "${t.name}=${k(it[1])}(${it[0].toLong()} calls)" } ?: "${t.name}=0" } + "\n" +
                "  top methods: " + byMethod.entries.sortedByDescending { it.value[1] }.take(8)
                    .joinToString(" · ") { "${it.key}=${k(it.value[1])}(${it.value[0].toLong()})" }.ifBlank { "-" } + "\n" +
                "  enrichment consumers [admitted/creditsSpent/deferred value]: " + Consumer.values().joinToString(" · ") { c ->
                    val r = byConsumer[c.name]
                    "${c.name}=${r?.get(0)?.toLong() ?: 0}/${k(r?.get(1) ?: 0.0)}/${r?.get(2)?.toLong() ?: 0} v=${"%.2f".format(valueOf(c))}"
                } + "\n" +
                "  free-first 8418: " + (try { HeliusCandleGate8418.statusLine8418() } catch (_: Throwable) { "candleGate8418 -" }) + " " +
                    (try { com.lifecyclebot.engine.BundleScanScope8418.statusLine8418() } catch (_: Throwable) { "bundleScope8418 -" }) + "\n" +
                "  ws bytes: " + wsBytes.entries.joinToString(" · ") { "${it.key}=${"%.1f".format(it.value.get() / 1_048_576.0)}MB(${k(creditsForWsBytes(it.value.get()))})" }.ifBlank { "-" } + "\n" +
                "  read: EXECUTION is not locally budget-gated; DECISION and ENRICHMENT can be locally refused by budget/exhaustion; enrichment is also value-paced"
        }
    }
}
