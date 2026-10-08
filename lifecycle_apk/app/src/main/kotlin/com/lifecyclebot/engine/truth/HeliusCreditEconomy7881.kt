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
 *                     metered, never refused.
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
    }

    /** Default daily target. 5.0.7880 era: ~5M credits/day observed by the operator. */
    private const val DAILY_BUDGET_CREDITS = 1_500_000.0
    private const val ENRICHMENT_POOL_SHARE = 0.50
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
        "getTransactionsForAddress" -> 10.0
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
    @Volatile private var loaded = false
    @Volatile private var lastPersistMs = 0L

    private fun utcDay(nowMs: Long): Long = nowMs / 86_400_000L
    private fun dayFraction(nowMs: Long): Double = (nowMs % 86_400_000L) / 86_400_000.0

    private fun rollIfNewDay(nowMs: Long) {
        val d = utcDay(nowMs)
        if (d == day) return
        synchronized(this) {
            if (d == day) return
            if (!loaded) { loaded = true; restore(d) }
            if (d != day) {
                byMethod.clear(); byConsumer.clear(); byTier.clear(); wsBytes.clear()
                total = 0.0; enrichmentAdmitted = 0.0; day = d
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
    fun admit(consumer: Consumer, estCredits: Double, nowMs: Long = System.currentTimeMillis()): Boolean = try {
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

    private fun maybePersist(nowMs: Long) {
        if (nowMs - lastPersistMs < PERSIST_EVERY_MS) return
        lastPersistMs = nowMs
        try {
            val o = org.json.JSONObject()
            synchronized(this) {
                o.put("day", day); o.put("total", total); o.put("enrich", enrichmentAdmitted)
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
            o.optJSONObject("method")?.let { j -> for (k in j.keys()) byMethod[k] = parse(j.optString(k), 2) }
            o.optJSONObject("consumer")?.let { j -> for (k in j.keys()) byConsumer[k] = parse(j.optString(k), 3) }
            o.optJSONObject("tier")?.let { j -> for (k in j.keys()) try { byTier[Tier.valueOf(k)] = parse(j.optString(k), 2) } catch (_: Throwable) {} }
            o.optJSONObject("ws")?.let { j -> for (k in j.keys()) wsBytes[k] = AtomicLong(j.optLong(k)) }
            total = o.optDouble("total", 0.0); enrichmentAdmitted = o.optDouble("enrich", 0.0)
            day = today
        } catch (_: Throwable) {}
    }

    fun statusLine(nowMs: Long = System.currentTimeMillis()): String {
        rollIfNewDay(nowMs)
        val f = dayFraction(nowMs)
        val projected = if (f > 0.01) total / f else total
        fun k(v: Double) = if (v >= 1_000_000) "%.2fM".format(v / 1_000_000) else if (v >= 1_000) "%.1fk".format(v / 1_000) else "%.0f".format(v)
        return synchronized(this) {
            "  today=${k(total)} credits  pace→${k(projected)}/day  budget=${k(DAILY_BUDGET_CREDITS)}/day (${"%.0f".format(total / DAILY_BUDGET_CREDITS * 100)}%)\n" +
                "  tiers: " + Tier.values().joinToString(" · ") { t -> byTier[t]?.let { "${t.name}=${k(it[1])}(${it[0].toLong()} calls)" } ?: "${t.name}=0" } + "\n" +
                "  top methods: " + byMethod.entries.sortedByDescending { it.value[1] }.take(8)
                    .joinToString(" · ") { "${it.key}=${k(it.value[1])}(${it.value[0].toLong()})" }.ifBlank { "-" } + "\n" +
                "  enrichment consumers [admitted/creditsSpent/deferred value]: " + Consumer.values().joinToString(" · ") { c ->
                    val r = byConsumer[c.name]
                    "${c.name}=${r?.get(0)?.toLong() ?: 0}/${k(r?.get(1) ?: 0.0)}/${r?.get(2)?.toLong() ?: 0} v=${"%.2f".format(valueOf(c))}"
                } + "\n" +
                "  ws bytes: " + wsBytes.entries.joinToString(" · ") { "${it.key}=${"%.1f".format(it.value.get() / 1_048_576.0)}MB(${k(creditsForWsBytes(it.value.get()))})" }.ifBlank { "-" } + "\n" +
                "  read: EXECUTION and DECISION are never refused; ENRICHMENT is paced by value (deferred = served from cache or skipped)"
        }
    }
}
