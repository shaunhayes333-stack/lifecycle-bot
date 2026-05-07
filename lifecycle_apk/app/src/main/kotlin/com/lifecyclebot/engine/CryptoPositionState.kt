package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.495z31 — Crypto position state segregation.
 *
 * Operator-reported issue: "CryptoAltTrader positions=51 |
 * balance=8.04 SOL" with `analyzed=58 hasPos=51 signals=26`. This
 * suggests live + paper + simulated state are blended — a paper
 * tracker entry blocks a real live route evaluation.
 *
 * Buckets:
 *   - LIVE       (real wallet-confirmed positions)
 *   - PAPER      (FluidLearning / paper engine positions)
 *   - SIMULATED  (LLM lab / replay positions)
 *   - WATCHLIST  (candidates only, NOT positions)
 *
 * Counters are pure deduped accounting — the actual position records
 * remain in their respective stores.
 */
object CryptoPositionState {

    enum class Bucket { LIVE, PAPER, SIMULATED, WATCHLIST }

    private val buckets: Map<Bucket, MutableSet<String>> = Bucket.values().associateWith {
        ConcurrentHashMap.newKeySet<String>()
    }

    fun record(symbol: String, bucket: Bucket) {
        // Dedupe: a symbol in LIVE must not also be in PAPER/SIM.
        val sym = symbol.uppercase()
        if (bucket == Bucket.LIVE) {
            buckets[Bucket.PAPER]?.remove(sym)
            buckets[Bucket.SIMULATED]?.remove(sym)
        }
        buckets[bucket]?.add(sym)
    }

    fun release(symbol: String, bucket: Bucket) {
        buckets[bucket]?.remove(symbol.uppercase())
    }

    fun count(bucket: Bucket): Int = buckets[bucket]?.size ?: 0

    fun snapshot(): Map<Bucket, Int> = Bucket.values().associateWith { count(it) }

    /** Pipeline-level helper. Live route evaluation must NOT be
     *  blocked by paper/sim entries. Returns the count of REAL live
     *  positions only. */
    fun liveOnlyCount(): Int = count(Bucket.LIVE)

    fun summaryLine(): String =
        "live=${count(Bucket.LIVE)} paper=${count(Bucket.PAPER)} sim=${count(Bucket.SIMULATED)} watch=${count(Bucket.WATCHLIST)}"
}
