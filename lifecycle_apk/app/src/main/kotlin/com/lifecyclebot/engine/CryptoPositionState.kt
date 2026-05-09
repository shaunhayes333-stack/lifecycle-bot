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

    /**
     * V5.9.654 — Operator's 10-Point Triage #1 (lane segregation purity).
     *
     * Operator-reported drift bug (CryptoAlt diagnostic line):
     *     "positions=1 (live=0 paper=45 sim=0 watch=0)"
     *
     * Root cause: `record()` only ADDS to the bucket; nothing ever removed
     * a symbol when its position closed. After a few scan cycles, the
     * PAPER bucket accumulated every symbol ever held this session even
     * though the live `positions` map was nearly empty. The diagnostic
     * line then misled the operator (and the V5.9.653 agent) into
     * believing the position cap was binding.
     *
     * `replaceBucket()` rebuilds a bucket atomically from the caller's
     * current truth set. Callers (CryptoAltTrader.runScanCycle) pass
     * exactly the symbols backed by an open `AltPosition` — so once this
     * is wired, the diagnostic count can never exceed the real
     * `positions.size`.
     */
    fun replaceBucket(bucket: Bucket, symbols: Collection<String>) {
        val target = buckets[bucket] ?: return
        val incoming = symbols.map { it.uppercase() }.toSet()
        // Drop stale.
        target.retainAll(incoming)
        // Add any missing.
        target.addAll(incoming)
        // V5.9.654 — preserve the live-wins-over-paper invariant from
        // record(): if we just rewrote the LIVE bucket, anything in there
        // must not also be in PAPER/SIM.
        if (bucket == Bucket.LIVE) {
            buckets[Bucket.PAPER]?.removeAll(incoming)
            buckets[Bucket.SIMULATED]?.removeAll(incoming)
        }
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
