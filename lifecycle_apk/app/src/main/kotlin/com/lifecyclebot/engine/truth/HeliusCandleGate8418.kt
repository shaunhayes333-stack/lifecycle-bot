package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8418 §FREE_BARS_FIRST_HELIUS_LAST.
 *
 * 5.0.8416: 188.7k Helius credits by 00:53, on pace for 304k against the 300k day; `v0/addresses/{acct}/transactions`
 * was 124.6k of it — SWAP_CANDLES 93.9k (707 jobs, up to two 100-credit pages each) and BUNDLE_SCAN 29.5k. The candle
 * request asked Helius FIRST: DataOrchestrator.seedCandleHistory fired HeliusSwapCandles7819 before the keyless
 * GeckoTerminal seed, and request7819 reserved credits before looking at the mint's own local tape, its observed
 * price bars or the keyless pump.fun curve candles — the free sources ran only on the refusal paths.
 *
 * The order now (decision only; nothing here fetches):
 *  1. the free sources run first on every request (local tape, observed-price bars, cached pump.fun candles), and a
 *     mint whose 1 m coverage they complete never reaches Helius;
 *  2. a bulk coin (not held, not near a decision, no open hand-off) never spends Helius credits — keyless only;
 *  3. a near-decision / hand-off coin first gets [FREE_GRACE_MS_8418] for the asynchronous keyless sources (the
 *     GeckoTerminal seed, the pump.fun fetch scheduled in step 1) to land, then Helius is the fallback;
 *  4. a held position's bars are never made to wait: Helius right after the free pass when still short.
 * Per mint, the 5 / 15 minute retry stamps of HeliusSwapCandles7819 still deduplicate the paid read. Execution reads and
 * sends never pass here (they stay on Helius).
 */
object HeliusCandleGate8418 {
    const val FREE_GRACE_MS_8418 = 20_000L
    private const val FIRST_ASK_TTL_MS_8418 = 15L * 60_000L
    private const val FIRST_ASK_CAP_8418 = 4_000

    enum class Decision8418 { COVERED_BY_FREE, BULK_KEYLESS_ONLY, FREE_GRACE, HELIUS_FALLBACK }

    /**
     * Pure. [priority] is HeliusSwapCandles7819.priority8284 (3 held, 2 near-decision, 1 hand-off, 0 bulk);
     * [coveredByFree] whether 1 m coverage is complete after the free pass; [firstFreeAskAtMs] when this mint's bars were
     * first asked of the free sources.
     */
    fun decide8418(priority: Int, coveredByFree: Boolean, firstFreeAskAtMs: Long, nowMs: Long): Decision8418 = when {
        coveredByFree -> Decision8418.COVERED_BY_FREE
        priority <= 0 -> Decision8418.BULK_KEYLESS_ONLY
        priority >= HeliusSwapCandles7819.PRIORITY_HELD_8284 -> Decision8418.HELIUS_FALLBACK
        nowMs - firstFreeAskAtMs < FREE_GRACE_MS_8418 -> Decision8418.FREE_GRACE
        else -> Decision8418.HELIUS_FALLBACK
    }

    private val firstAskAt = ConcurrentHashMap<String, Long>()
    private val byDecision = ConcurrentHashMap<String, AtomicLong>()

    /** When [mint]'s bars were first asked of the free sources (this call's [nowMs] if never). Bounded. */
    fun firstAsk8418(mint: String, nowMs: Long): Long {
        val prior = firstAskAt[mint]
        if (prior != null && nowMs - prior <= FIRST_ASK_TTL_MS_8418) return prior
        firstAskAt[mint] = nowMs
        if (firstAskAt.size > FIRST_ASK_CAP_8418) MemoryBounds8289.capByAge8289(firstAskAt, FIRST_ASK_CAP_8418, FIRST_ASK_TTL_MS_8418, nowMs) { it }
        return nowMs
    }

    fun note8418(d: Decision8418) { byDecision.computeIfAbsent(d.name) { AtomicLong(0L) }.incrementAndGet() }

    fun count8418(d: Decision8418): Long = byDecision[d.name]?.get() ?: 0L

    internal fun trackedCount8418(): Int = firstAskAt.size

    internal fun resetForTest8418() { firstAskAt.clear(); byDecision.clear() }

    fun statusLine8418(): String =
        "candleGate8418{" + Decision8418.values().joinToString(",") { "${it.name}=${count8418(it)}" } + " tracked=${firstAskAt.size}}"
}
