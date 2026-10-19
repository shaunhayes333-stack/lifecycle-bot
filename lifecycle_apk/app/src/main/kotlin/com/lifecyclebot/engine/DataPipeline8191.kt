package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8191 §SPEND_WHERE_A_DECISION_IS_NEAR — the P1 data pipeline's shared bookkeeping.
 *
 * 5.0.8186: Birdeye 150000/150000 CU spent (BIRDEYE_SEED_SKIPPED_BUDGET=1639), GeckoTerminal 0 bars, setup core
 * coverage 33%. Birdeye was spent per watchlist tick on every tradable coin (security, trade data, price stats, whale
 * feed: four premium calls per coin per cache window) and on 1m / 5m / 15m / 4H candle seeds for every new token, most
 * of which never reached a decision, while Codex (keyed, 56/56 chart calls) and DexScreener (free, 98%) were healthy.
 *
 *  - [noteNearDecision8191] / [nearDecision8191]: a coin whose setup asked for its missing inputs (the playbook is
 *    classifying it now) is near a decision for [NEAR_DECISION_MS_8191]; held positions always are.
 *  - [premiumAllowed8191]: Birdeye premium enrichment only for a held or near-decision coin, and not when a cheaper
 *    fresh source (a DexScreener pair answer) already states the same flow and the coin is not held.
 *  - Candle seeds (DataOrchestrator.seedCandleHistory): priority (held / near decision) asks Codex getBars first,
 *    then the keyless feed, then Birdeye for a timeframe still empty. An ordinary new token: keyless, then Codex while
 *    [CODEX_ORDINARY_PER_MIN_8191] lasts ([claimCodexOrdinary8191]), never Birdeye.
 *  - [pairFetchDue8191]: a setup input whose DexScreener pair is absent, stale or missing that input triggers one fetch per
 *    [PAIR_FETCH_GAP_MS_8191] per mint (the answer lands in PairFlow8169; nothing is inferred).
 *  - Birdeye spend by endpoint class ([noteBirdeye8191]) so the biggest consumer is named in the report.
 */
object DataPipeline8191 {
    const val NEAR_DECISION_MS_8191 = 10L * 60_000L
    const val PAIR_FETCH_GAP_MS_8191 = 60_000L
    const val CODEX_ORDINARY_PER_MIN_8191 = 6
    private const val CAP_8191 = 4_000

    const val SRC_CODEX = "codex"
    const val SRC_KEYLESS = "keyless"
    const val SRC_BIRDEYE = "birdeye"

    private val nearDecisionAt = ConcurrentHashMap<String, Long>()
    private val pairFetchAt = ConcurrentHashMap<String, Long>()
    private val codexOrdinary = java.util.ArrayDeque<Long>()
    private val birdeyeByEndpoint = ConcurrentHashMap<String, AtomicLong>()
    private val premiumAllowed = AtomicLong(0L)
    private val premiumSkipped = AtomicLong(0L)
    private val premiumSkippedCovered = AtomicLong(0L)
    private val pairFetches = AtomicLong(0L)
    private val seedBySource = ConcurrentHashMap<String, AtomicLong>()
    private val PAIR_REPAIR_INPUTS_8237 = setOf("bp", "chg", "liq", "mcap", "opp", "vol",
        "route", "token_map", "quote", "sellability")

    private fun trim(m: ConcurrentHashMap<String, Long>, nowMs: Long, keepMs: Long) {
        if (m.size > CAP_8191) m.entries.removeIf { nowMs - it.value > keepMs }
        if (m.size > CAP_8191) m.clear()
    }

    fun noteNearDecision8191(mint: String, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank()) return
        nearDecisionAt[mint] = nowMs
        trim(nearDecisionAt, nowMs, NEAR_DECISION_MS_8191)
    }

    /** Pure: is a coin noted at [notedAtMs] still near a decision at [nowMs]? */
    fun nearDecisionAt8191(notedAtMs: Long?, nowMs: Long): Boolean =
        notedAtMs != null && nowMs - notedAtMs in 0L..NEAR_DECISION_MS_8191

    fun nearDecision8191(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean = nearDecisionAt8191(nearDecisionAt[mint], nowMs)

    private fun held8191(mint: String): Boolean = try { HeldPositionSupervisor7246.isHeld(mint) } catch (_: Throwable) { false }

    /** Pure: may Birdeye premium enrichment run for a coin? */
    fun premiumAllowedPure8191(held: Boolean, nearDecision: Boolean, cheaperFresh: Boolean): Boolean =
        held || (nearDecision && !cheaperFresh)

    /** BotService's premium prefetch: held, or near a decision without a fresh DexScreener flow for the same coin. */
    fun premiumAllowed8191(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val held = held8191(mint)
        val near = nearDecision8191(mint, nowMs)
        val cheaper = try {
            com.lifecyclebot.engine.truth.PairFlow8169.peek8169(mint)?.let { nowMs - it.atMs in 0L..(5L * 60_000L) } == true
        } catch (_: Throwable) { false }
        val ok = premiumAllowedPure8191(held, near, cheaper)
        if (ok) premiumAllowed.incrementAndGet() else {
            premiumSkipped.incrementAndGet()
            if (near && cheaper) premiumSkippedCovered.incrementAndGet()
            try { PipelineHealthCollector.labelInc("BIRDEYE_PREMIUM_SKIPPED_NOT_NEAR_DECISION_8191") } catch (_: Throwable) {}
        }
        return ok
    }

    /** BotService's premium prefetch (one-line call site): tradable (liq >= $2K, mcap >= $5K) and [premiumAllowed8191]. */
    fun premium8191(ts: com.lifecyclebot.data.TokenState): Boolean =
        ts.lastLiquidityUsd >= 2_000.0 && ts.lastMcap >= 5_000.0 && premiumAllowed8191(ts.mint)

    /** Is this seed a priority one (held or near a decision)? */
    fun seedPriority8191(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean = held8191(mint) || nearDecision8191(mint, nowMs)

    /** Claim one of the ordinary seeds' Codex calls this minute (priority seeds never ask). */
    fun claimCodexOrdinary8191(nowMs: Long = System.currentTimeMillis()): Boolean = synchronized(codexOrdinary) {
        while (codexOrdinary.isNotEmpty() && nowMs - codexOrdinary.peekFirst() >= 60_000L) codexOrdinary.pollFirst()
        if (codexOrdinary.size >= CODEX_ORDINARY_PER_MIN_8191) false else { codexOrdinary.addLast(nowMs); true }
    }

    fun noteSeed8191(source: String, outcome: String) {
        seedBySource.computeIfAbsent("$source:$outcome") { AtomicLong(0L) }.incrementAndGet()
    }

    /** Keep the missing route/proof key as well as its depth request. Fresh liquidity alone is not route evidence. */
    internal fun pairRepairInputs8237(keys: Collection<String>): Set<String> =
        keys.map { it.substringBefore(':').trim().lowercase() }.filterTo(LinkedHashSet()) { it in PAIR_REPAIR_INPUTS_8237 }

    /** A pair answer covers a repair only when it states every requested input at the resolver's own freshness.
     * A fresh price/liquidity answer can still lack the buy/sell split or price changes. PairFlow does not carry
     * mcap, volume, opportunity context or route proof, so its timestamp alone cannot declare those repairs satisfied.
     */
    internal fun pairFlowSatisfies8191(
        flow: com.lifecyclebot.engine.truth.PairFlow8169.Flow8169?, requiredInputs: Set<String>,
        nowMs: Long, ageMin: Double,
    ): Boolean {
        if (flow == null || requiredInputs.isEmpty()) return false
        val resolver = com.lifecyclebot.engine.truth.SetupFeatureResolver8169
        val maxAgeMs = resolver.maxEvidenceAge8170(ageMin)
        return requiredInputs.all { input ->
            when (input.substringBefore(':').trim().lowercase()) {
                "bp" -> resolver.pairBp8169(flow, nowMs, maxAgeMs).value != null
                "chg" -> resolver.pairChg8169(flow, nowMs, maxAgeMs).value != null
                "liq" -> resolver.pairLiq8169(flow, nowMs, maxAgeMs).value != null
                else -> false
            }
        }
    }

    /** Pure: a stale or incomplete answer is retried, still within the per-mint provider request budget. */
    fun pairFetchDue8191(
        flowAtMs: Long?, askedAtMs: Long?, nowMs: Long, freshMs: Long = 15L * 60_000L,
        requiredInputsObserved: Boolean = true,
    ): Boolean {
        val fresh = requiredInputsObserved && flowAtMs != null && nowMs - flowAtMs in -60_000L..freshMs
        if (fresh) return false
        return askedAtMs == null || nowMs - askedAtMs !in 0L until PAIR_FETCH_GAP_MS_8191
    }

    /** Claim one shared fetch across all lanes. Launch repairs and their reader both use the 60-second window;
     * mature inputs retain the normal window. The claim is atomic, so simultaneous specialist requests coalesce.
     */
    fun claimPairFetch8191(
        mint: String, nowMs: Long = System.currentTimeMillis(),
        requiredInputs: Set<String> = setOf("bp", "chg", "liq"), ageMin: Double = Double.NaN,
    ): Boolean {
        if (mint.isBlank()) return false
        val flow = try { com.lifecyclebot.engine.truth.PairFlow8169.peek8169(mint) } catch (_: Throwable) { null }
        val maxAgeMs = com.lifecyclebot.engine.truth.SetupFeatureResolver8169.maxEvidenceAge8170(ageMin)
        val observed = pairFlowSatisfies8191(flow, requiredInputs, nowMs, ageMin)
        var claimed = false
        pairFetchAt.compute(mint) { _, askedAt ->
            if (pairFetchDue8191(flow?.atMs, askedAt, nowMs, maxAgeMs, observed)) {
                claimed = true
                nowMs
            } else askedAt
        }
        if (!claimed) return false
        trim(pairFetchAt, nowMs, PAIR_FETCH_GAP_MS_8191)
        pairFetches.incrementAndGet()
        return true
    }

    /** Pure: the endpoint class of a Birdeye URL ("defi/ohlcv", "defi/v3/token/trade-data"). */
    fun birdeyeEndpoint8191(url: String): String {
        val path = url.substringAfter("birdeye.so", url).substringBefore('?').trim('/')
        return path.split('/').filter { it.isNotBlank() }.take(4).joinToString("/").ifBlank { "other" }
    }

    fun noteBirdeye8191(url: String) {
        val k = birdeyeEndpoint8191(url)
        if (birdeyeByEndpoint.size >= 64 && !birdeyeByEndpoint.containsKey(k)) { birdeyeByEndpoint.computeIfAbsent("other") { AtomicLong(0L) }.incrementAndGet(); return }
        birdeyeByEndpoint.computeIfAbsent(k) { AtomicLong(0L) }.incrementAndGet()
    }

    fun statusLine8191(): String =
        "pipeline8191[birdeyeByEndpoint{" + birdeyeByEndpoint.entries.sortedByDescending { it.value.get() }.take(8)
            .joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" } + "} premium[allowed=${premiumAllowed.get()} " +
            "skipped=${premiumSkipped.get()} coveredByDex=${premiumSkippedCovered.get()}] nearDecision=${nearDecisionAt.size} " +
            "pairFetches=${pairFetches.get()} seed{" + seedBySource.entries.sortedByDescending { it.value.get() }
            .joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" } + "}]"

    internal fun resetForTests8191() {
        nearDecisionAt.clear(); pairFetchAt.clear(); synchronized(codexOrdinary) { codexOrdinary.clear() }
        birdeyeByEndpoint.clear(); seedBySource.clear()
        premiumAllowed.set(0L); premiumSkipped.set(0L); premiumSkippedCovered.set(0L); pairFetches.set(0L)
    }
}
