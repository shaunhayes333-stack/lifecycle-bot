package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.Position
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector

/**
 * V5.0.7059 §NOTHING_GOES_STALE_UNPRICED_LOST_OR_EXCLUDED — NO EXCEPTIONS.
 *
 * Operator, in exactly those words, after seeing CYCLIC hold MLKRS for 30m22s
 * on "px=pricing wait | PnL: basis wait | PRICE_STALE_OR_TS_UNKN" — while the
 * market cap needed to price it sat on the same TokenState the whole time.
 *
 * THE TWO FAILURES ARE ONE FAILURE
 * ================================
 * A mark is judged, and when the judgement fails the answer is "no answer".
 * That single shape produces both of the operator's defects, in opposite
 * directions:
 *
 *   EXCLUDED  CyclicTradeEngine.resolveCyclicPrice returns a dead verdict on
 *             feed age alone — before it has even asked for a price — and the
 *             held branch `return`s on it. A position with a stop-loss, a
 *             take-profit and 30 minutes of market movement is evaluated
 *             exactly zero times. Nothing was measured, so nothing was risked
 *             in the only sense that matters: nothing was protected either.
 *
 *   INFLATED  Executor.getActualPrice runs its entire 6895/7017 apparatus
 *             INSIDE `if (pos.entryPriceSource != ts.lastPriceSource)`. When
 *             the source matches, the tick is cached and served with NO
 *             cross-check of any kind. Same-source ticks are the ones nobody
 *             checks, so a same-source tick that contradicts the market cap
 *             reported on the same call goes straight to the ledger. That is
 *             the F9CBDp partials: four rungs, market cap flat at $3,146
 *             throughout, ~79 SOL of proceeds booked against ~0.089 SOL of
 *             basis. A 946x on a token that did not move.
 *
 * Both are the same missing step. 7017 already proved the step exists and is
 * exact — it just gated it behind a source mismatch and behind a 10x band.
 *
 * WHY MARKET CAP IS THE ARBITER
 * =============================
 * Price-per-token is basis-dependent; market cap is not, because supply
 * cancels. So for any position that recorded an entry price and an entry
 * market cap, ANY tick carrying a market cap can be placed exactly on that
 * position's own basis:
 *
 *     mcapImplied = entryPrice x (currentMcap / entryMcap)
 *
 * When the raw tick and mcapImplied agree, they corroborate each other and the
 * raw tick is served — it is the direct measurement. When they disagree
 * materially, ONE of them is wrong, and it is not the basis-independent one.
 *
 * THIS IS NOT A CLAMP AND IT DOES NOT TOUCH RUNNERS
 * =================================================
 * Runner capture is not negotiable (V5.9.1358). A genuine 1000x moves price
 * AND market cap together, so the two agree to within a rounding error and
 * this object is silent — it serves the raw tick, all 1000x of it. Divergence
 * between price and market cap is not a big move; it is the two numbers
 * disagreeing about the SAME move, which means a supply divisor, a decimals
 * shift, or a unit crossing. [MCAP_DIVERGENCE_MAX] is deliberately loose at 3x
 * so that real supply events (burns, mints) pass untouched; the defect class
 * this catches is orders of magnitude, not percentages.
 *
 * AND IT NEVER RETURNS NOTHING
 * ============================
 * [resolve] always yields a usable mark for a position with any history at
 * all, degrading through an explicit, named ladder rather than refusing:
 *
 *     TICK_MCAP_AGREED  both present and corroborating       — best
 *     MCAP_RECONCILED   they disagree; the invariant one wins
 *     TICK_ONLY         no market cap to check against
 *     MCAP_ONLY         no tick, but the cap prices it exactly
 *     CARRIED_ROUTE     last on-basis price, at any age
 *     ENTRY_FLAT        nothing since entry; 0% is the honest answer
 *     NONE              no entry price either — not a position
 *
 * The provenance travels with the number so a caller that must not TRADE on a
 * weak mark can still SEE one. Refusing to act is a decision; refusing to look
 * is a defect. Only NONE means genuinely unknown, and only a position with no
 * entry price can reach it.
 */
object CanonicalMarkResolution7059 {

    /**
     * How far the raw tick may sit from the market-cap-implied price before the
     * market cap wins. Loose on purpose — see the class note. A real supply
     * event moves this by tens of percent; the defects it catches move it by
     * 100x or more.
     */
    private const val MCAP_DIVERGENCE_MAX = 3.0

    enum class Provenance {
        TICK_MCAP_AGREED,
        MCAP_RECONCILED,
        TICK_ONLY,
        MCAP_ONLY,
        CARRIED_ROUTE,
        ENTRY_FLAT,
        NONE,
    }

    data class Mark(
        val price: Double,
        val provenance: Provenance,
        val rawTick: Double,
        val mcapImplied: Double,
    ) {
        val usable: Boolean get() = price.isFinite() && price > 0.0

        /**
         * True when the mark rests on an observation of the market taken since
         * entry. ENTRY_FLAT does not: it is a truthful 0%, not a measurement,
         * so a caller deciding whether to OPEN risk should require this while a
         * caller deciding whether to CLOSE it must not.
         */
        val observed: Boolean get() = when (provenance) {
            Provenance.TICK_MCAP_AGREED, Provenance.MCAP_RECONCILED,
            Provenance.TICK_ONLY, Provenance.MCAP_ONLY, Provenance.CARRIED_ROUTE -> true
            Provenance.ENTRY_FLAT, Provenance.NONE -> false
        }
    }

    private val counts = java.util.concurrent.ConcurrentHashMap<Provenance, java.util.concurrent.atomic.AtomicLong>()
    private val corrected = java.util.concurrent.atomic.AtomicLong(0)
    private val worstDivergence = java.util.concurrent.atomic.AtomicLong(0)

    private fun bump(p: Provenance) {
        try {
            counts.computeIfAbsent(p) { java.util.concurrent.atomic.AtomicLong(0) }.incrementAndGet()
        } catch (_: Throwable) {}
    }

    /**
     * Resolve the one mark for [pos]. [rawTick] is the price the feed reported
     * (0.0 or non-finite when there is none); [currentMcap] is the market cap
     * that arrived with it (likewise).
     *
     * Never throws and never returns a non-finite price.
     */
    fun resolve(pos: Position, rawTick: Double, currentMcap: Double): Mark {
        val tick = if (rawTick.isFinite() && rawTick > 0.0) rawTick else 0.0
        val implied = try {
            MarkBasisReconciler7017.reconcileForPosition(pos, currentMcap)
        } catch (_: Throwable) { 0.0 }
        val mcapImplied = if (implied.isFinite() && implied > 0.0) implied else 0.0

        if (tick > 0.0 && mcapImplied > 0.0) {
            val ratio = tick / mcapImplied
            val diverged = !ratio.isFinite() ||
                ratio > MCAP_DIVERGENCE_MAX ||
                ratio < (1.0 / MCAP_DIVERGENCE_MAX)
            return if (diverged) {
                corrected.incrementAndGet()
                val magnitude = if (ratio.isFinite() && ratio > 0.0) {
                    (if (ratio >= 1.0) ratio else 1.0 / ratio)
                } else Double.MAX_VALUE
                if (magnitude.isFinite()) {
                    val asLong = magnitude.toLong()
                    while (true) {
                        val prev = worstDivergence.get()
                        if (asLong <= prev || worstDivergence.compareAndSet(prev, asLong)) break
                    }
                }
                bump(Provenance.MCAP_RECONCILED)
                Mark(mcapImplied, Provenance.MCAP_RECONCILED, tick, mcapImplied)
            } else {
                bump(Provenance.TICK_MCAP_AGREED)
                Mark(tick, Provenance.TICK_MCAP_AGREED, tick, mcapImplied)
            }
        }
        if (tick > 0.0) {
            bump(Provenance.TICK_ONLY)
            return Mark(tick, Provenance.TICK_ONLY, tick, 0.0)
        }
        if (mcapImplied > 0.0) {
            bump(Provenance.MCAP_ONLY)
            return Mark(mcapImplied, Provenance.MCAP_ONLY, 0.0, mcapImplied)
        }
        val carried = pos.lastRoutePrice
        if (carried.isFinite() && carried > 0.0) {
            bump(Provenance.CARRIED_ROUTE)
            return Mark(carried, Provenance.CARRIED_ROUTE, 0.0, 0.0)
        }
        val entry = pos.entryPrice
        if (entry.isFinite() && entry > 0.0) {
            bump(Provenance.ENTRY_FLAT)
            return Mark(entry, Provenance.ENTRY_FLAT, 0.0, 0.0)
        }
        bump(Provenance.NONE)
        return Mark(0.0, Provenance.NONE, 0.0, 0.0)
    }

    /**
     * Log a correction. Throttled the same way 7017's is — these fire per
     * management pass per position and a hundred held positions would drown the
     * log otherwise.
     */
    fun noteCorrection(mint: String, symbol: String, mark: Mark, context: String) {
        if (mark.provenance != Provenance.MCAP_RECONCILED) return
        try {
            PipelineHealthCollector.labelInc("MARK_MCAP_DIVERGENCE_CORRECTED_7059")
        } catch (_: Throwable) {}
        if (corrected.get() % 20L == 1L) {
            try {
                val ratio = if (mark.mcapImplied > 0.0) mark.rawTick / mark.mcapImplied else -1.0
                ForensicLogger.lifecycle(
                    "MARK_MCAP_DIVERGENCE_CORRECTED_7059",
                    "ctx=$context mint=${mint.take(10)} sym=$symbol " +
                        "rawTick=${mark.rawTick} mcapImplied=${mark.mcapImplied} " +
                        "ratio=${"%.4g".format(ratio)} band=$MCAP_DIVERGENCE_MAX " +
                        "served=${mark.price} " +
                        "action=market_cap_is_basis_independent_so_it_wins",
                )
            } catch (_: Throwable) {}
        }
    }

    /** Diagnostic line for the pipeline report. */
    fun status(): String {
        val parts = Provenance.values().joinToString(" ") { p ->
            "${p.name.lowercase()}=${counts[p]?.get() ?: 0L}"
        }
        return "$parts corrected=${corrected.get()} worstDivergence=${worstDivergence.get()}x"
    }
}
