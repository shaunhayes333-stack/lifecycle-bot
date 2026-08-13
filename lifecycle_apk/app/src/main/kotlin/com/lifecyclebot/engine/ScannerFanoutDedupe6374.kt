package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6374 — SCANNER FANOUT THROTTLE (per-mint dedupe upstream of INTAKE).
 *
 * Operator directive (verbatim):
 *   "Scanner Fanout Throttle: cycle 184 s → collapse the PUMP_PORTAL_WS = 788
 *    intake burst with a per-mint 60 s dedupe upstream of PHASE/INTAKE."
 *
 * The PumpPortal WS feed re-emits the same freshly-launched mint many times
 * per second (position update, price tick, trade tick etc). Every emission
 * used to call admitProtectedMemeIntake → GlobalTradeRegistry.addToWatchlist,
 * which internally hits duplicatesBlocked but still burns bot-loop cycles on
 * ScannerHardRejectStore + rejectedTokens + shouldDivertPumpToProbation +
 * PROBATION queue writes for EVERY re-emit. The operator snapshot showed
 * PUMP_PORTAL_WS = 788 intake events collapsing the loop to 184s cycles.
 *
 * FIX: A per-(source, mint) time-to-live (TTL) gate. First arrival admits,
 *      any re-arrival within [ttlMs] ms is silently dropped BEFORE
 *      admitProtectedMemeIntake even sees it. TTL defaults to 60s (per
 *      operator directive) and is fluid/tunable by the on-board learning
 *      layer at runtime via [setTtlMs].
 *
 * Doctrine: NEVER chokes the pipeline permanently. A mint is only muted for
 * [ttlMs] ms; downstream code (GlobalTradeRegistry duplicate refresh path,
 * probation promotion, source-balance) still sees the FIRST arrival every
 * minute unchanged.
 *
 * Cost: bounded ConcurrentHashMap with lazy pruning on read; upper bound
 * enforced by [MAX_ENTRIES] with FIFO-ish eviction of oldest last-seen
 * timestamps.
 */
object ScannerFanoutDedupe6374 {

    /** Default TTL per operator directive: 60 seconds. */
    private const val DEFAULT_TTL_MS: Long = 60_000L

    /** Upper bound on distinct (source|mint) entries; prevents unbounded growth. */
    private const val MAX_ENTRIES: Int = 4096

    /** Prune stride so we don't scan every call. */
    private const val PRUNE_EVERY_N_CALLS: Int = 200

    private val entries = ConcurrentHashMap<String, Long>()
    private val ttlMs = AtomicLong(DEFAULT_TTL_MS)
    private val admits = AtomicLong(0L)
    private val skips = AtomicLong(0L)
    private val callsSinceLastPrune = AtomicLong(0L)

    /**
     * Returns true iff this (source, mint) pair is fresh — either never seen
     * or the last sighting is older than [ttlMs]. Records the sighting.
     *
     * Call this in the WS/scanner callback BEFORE admitProtectedMemeIntake.
     */
    fun admit(source: String, mint: String): Boolean {
        if (mint.isBlank()) return true
        val key = keyOf(source, mint)
        val now = System.currentTimeMillis()
        val ttl = ttlMs.get()
        val last = entries[key]
        // Maybe prune first (cheap: bounded to MAX_ENTRIES scan on the 1-in-N tick).
        val calls = callsSinceLastPrune.incrementAndGet()
        if (calls % PRUNE_EVERY_N_CALLS == 0L || entries.size > MAX_ENTRIES) {
            pruneExpired(now, ttl)
        }
        if (last != null && (now - last) < ttl) {
            skips.incrementAndGet()
            try { PipelineHealthCollector.labelInc("SCANNER_FANOUT_DEDUPE_SKIP_6374|$source") } catch (_: Throwable) {}
            return false
        }
        entries[key] = now
        admits.incrementAndGet()
        // V5.0.6442 §4 SCANNER INTAKE GATE — consult the canonical
        // SameMintDedupAuthority6441 at the source. If the mint is
        // already OPEN (canonical) we BLOCK entry work entirely; the
        // scanner should route to exit/mark-update rather than emit a
        // new candidate. REENTRY_LOCKOUT respects the canonical
        // cooldown after a recent close.
        try {
            val decision = com.lifecyclebot.engine.truth.SameMintDedupAuthority6441
                .shouldCreateEntryCandidate(mint, source)
            if (decision == com.lifecyclebot.engine.truth.SameMintDedupAuthority6441.Decision.BLOCK ||
                decision == com.lifecyclebot.engine.truth.SameMintDedupAuthority6441.Decision.REENTRY_LOCKOUT) {
                skips.incrementAndGet()
                try { PipelineHealthCollector.labelInc("SCANNER_FANOUT_CANONICAL_$decision".take(60)) } catch (_: Throwable) {}
                return false
            }
        } catch (_: Throwable) {}
        return true
    }

    /** Fluid tuning hook (operator: "adjustments... in a fluid learnt state"). */
    fun setTtlMs(newTtl: Long) {
        val clamped = newTtl.coerceIn(5_000L, 600_000L)
        ttlMs.set(clamped)
    }

    fun currentTtlMs(): Long = ttlMs.get()
    fun admitCount(): Long = admits.get()
    fun skipCount(): Long = skips.get()

    fun snapshot(): Snapshot = Snapshot(
        ttlMs = ttlMs.get(),
        entryCount = entries.size,
        admitCount = admits.get(),
        skipCount = skips.get(),
    )

    data class Snapshot(
        val ttlMs: Long,
        val entryCount: Int,
        val admitCount: Long,
        val skipCount: Long,
    )

    /** Test-only reset. */
    internal fun resetForTest() {
        entries.clear()
        admits.set(0L)
        skips.set(0L)
        callsSinceLastPrune.set(0L)
        ttlMs.set(DEFAULT_TTL_MS)
    }

    private fun keyOf(source: String, mint: String): String =
        source.take(40).uppercase() + "|" + mint

    private fun pruneExpired(now: Long, ttl: Long) {
        try {
            val iter = entries.entries.iterator()
            var removed = 0
            while (iter.hasNext()) {
                val e = iter.next()
                if ((now - e.value) >= ttl) {
                    iter.remove()
                    removed++
                }
            }
            if (entries.size > MAX_ENTRIES) {
                // Hard cap safety: drop the oldest half if we somehow still exceed.
                val victims = entries.entries.sortedBy { it.value }.take(entries.size - (MAX_ENTRIES / 2))
                for (v in victims) entries.remove(v.key)
            }
            if (removed > 0) {
                try { PipelineHealthCollector.labelInc("SCANNER_FANOUT_DEDUPE_PRUNE_6374") } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }
}
