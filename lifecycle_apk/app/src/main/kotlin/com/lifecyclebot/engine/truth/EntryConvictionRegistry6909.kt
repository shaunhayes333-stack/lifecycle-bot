package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6909 — carries the EVIDENCE-only sizing conviction from the site that
 * computes it to the authority that needs it.
 *
 * Executor.doBuy builds the full sizing stack and knows, by component name,
 * which multipliers are learned belief and which are capacity. The sizing
 * authority that decides whether to promote a sub-minimum order to the
 * minimum executable notional is several bridges downstream
 * (CanonicalSizingBridge6532, TraderSizingBridge6444,
 * CanonicalAssetEntryContract6551, FinalDecisionGate, EdgeOptimizer) and sees
 * only a single scalar size. Threading a parameter through every one of those
 * signatures would touch five call chains for one fact.
 *
 * OrderSizeResolver6441.resolve already accepts `mint`, so it reads this
 * registry itself when a caller has not supplied conviction explicitly. One
 * writer, one reader, no bridge changes.
 *
 * Bounded by construction: TTL'd to the life of an entry decision and hard
 * capped, because an unbounded per-mint map is the exact failure this
 * codebase keeps rediscovering.
 */
object EntryConvictionRegistry6909 {

    /** Beyond this an entry decision is stale and conviction is unknown. */
    private const val TTL_MS = 60_000L
    private const val MAX_KEYS = 4_000
    private const val SWEEP_INTERVAL_MS = 30_000L

    private class Stamp(val conviction: Double, val atMs: Long)

    private val byMint = ConcurrentHashMap<String, Stamp>()
    private val lastSweepMs = AtomicLong(0L)
    private val stamps = AtomicLong(0L)
    private val reads = AtomicLong(0L)
    private val hits = AtomicLong(0L)

    private fun sweep(nowMs: Long) {
        if (byMint.size < MAX_KEYS && nowMs - lastSweepMs.get() < SWEEP_INTERVAL_MS) return
        lastSweepMs.set(nowMs)
        try {
            val it = byMint.entries.iterator()
            while (it.hasNext()) {
                if (nowMs - it.next().value.atMs > TTL_MS) it.remove()
            }
        } catch (_: Throwable) {}
    }

    /** Record the evidence-only conviction product for an in-flight entry. */
    fun stamp6909(mint: String, conviction: Double) {
        val key = mint.trim()
        if (key.isEmpty()) return
        if (!conviction.isFinite() || conviction < 0.0) return
        val now = System.currentTimeMillis()
        try { sweep(now) } catch (_: Throwable) {}
        byMint[key] = Stamp(conviction.coerceIn(0.0, 1.0), now)
        stamps.incrementAndGet()
    }

    /**
     * Conviction for a mint, or 1.0 when unknown or stale.
     *
     * 1.0 is deliberately the "unknown" value rather than 0.0: an absent
     * stamp must never be read as "no conviction", because that would turn a
     * missing signal into a refusal and choke every caller that does not
     * participate in this registry.
     */
    fun convictionFor6909(mint: String): Double {
        val key = mint.trim()
        if (key.isEmpty()) return 1.0
        reads.incrementAndGet()
        val s = byMint[key] ?: return 1.0
        if (System.currentTimeMillis() - s.atMs > TTL_MS) return 1.0
        hits.incrementAndGet()
        return s.conviction
    }

    fun statusLine(): String =
        "tracked=${byMint.size} stamps=${stamps.get()} reads=${reads.get()} hits=${hits.get()} ttlMs=$TTL_MS"

    internal fun clearForTest() {
        byMint.clear(); stamps.set(0L); reads.set(0L); hits.set(0L); lastSweepMs.set(0L)
    }
}
