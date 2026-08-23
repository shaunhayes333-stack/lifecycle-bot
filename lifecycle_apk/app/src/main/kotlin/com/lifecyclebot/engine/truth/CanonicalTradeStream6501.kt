package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.TradeRecord
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6501 §1 — CANONICAL TRADE STREAM (source-level analytics filter).
 *
 * OPERATOR MANDATE (verbatim, 6500 evidence):
 *
 *   "Mathematical Edge Engine claims: BLUECHIP PnL = 6,267,242.8403 SOL.
 *    Source analytics contains similarly impossible multi-million-SOL
 *    profits.
 *
 *    Prevent Mathematics/strategy analytics from consuming legacy
 *    terminal rows; the 6.27-million-SOL output must become impossible.
 *
 *    Quarantine all historical rows with quantity/basis invariant
 *    failure from EVERY analytics consumer — not merely learning.
 *
 *    Fix ALWAYS AT SOURCE."
 *
 * DESIGN
 * ──────
 * Every analytics / MathEdge / strategy μ / reward / learner consumer
 * that reads `TradeRecord`s MUST route them through this stream's
 * `filter(...)` before any decisive-trade math. Non-terminal partials
 * AND positions with a broken quantity invariant AND mints in the
 * historical economic quarantine are dropped at source.
 *
 * There is exactly ONE truth predicate for "can this row feed
 * decisive analytics?":
 *
 *   TerminalCloseAuthority6499.isTerminalClose(record) &&
 *   !QuantityInvariantAuthority6500.isQuarantined(record.mint) &&
 *   !LearningQuarantineGate6470.isQuarantined(positionId=null,
 *                                             mint=record.mint)
 *
 * Analytics/MathEdge readers cannot construct their own combination;
 * they call `filter` or `isEligible`.
 */
object CanonicalTradeStream6501 {

    private val evaluated = AtomicLong(0L)
    private val eligible = AtomicLong(0L)
    private val droppedPartial = AtomicLong(0L)
    private val droppedInvariant = AtomicLong(0L)
    private val droppedHistorical = AtomicLong(0L)

    /**
     * Fast per-record predicate. True iff the record may feed
     * decisive analytics / MathEdge / strategy μ / rewards / learners.
     */
    fun isEligible(record: TradeRecord): Boolean {
        evaluated.incrementAndGet()
        // Terminal-only: partials are journal rows but never decisive.
        if (!TerminalCloseAuthority6499.isTerminalClose(record)) {
            droppedPartial.incrementAndGet()
            try { PipelineHealthCollector.labelInc("CANONICAL_TRADE_STREAM_DROP_PARTIAL_6501") } catch (_: Throwable) {}
            return false
        }
        // Quantity invariant break at source — the $754K / $6.27M class.
        if (record.mint.isNotBlank() &&
            QuantityInvariantAuthority6500.isQuarantined(record.mint)) {
            droppedInvariant.incrementAndGet()
            try { PipelineHealthCollector.labelInc("CANONICAL_TRADE_STREAM_DROP_INVARIANT_6501") } catch (_: Throwable) {}
            return false
        }
        // Historical qty-decimal / replay-divergent / oversold quarantine
        // (6496 §2). The same set that already gates learners now gates
        // ALL analytics consumers per operator's 6501 mandate.
        if (record.mint.isNotBlank() &&
            LearningQuarantineGate6470.isQuarantined(positionId = null, mint = record.mint)) {
            droppedHistorical.incrementAndGet()
            try { PipelineHealthCollector.labelInc("CANONICAL_TRADE_STREAM_DROP_HISTORICAL_6501") } catch (_: Throwable) {}
            return false
        }
        eligible.incrementAndGet()
        return true
    }

    /**
     * List-shaped convenience — the canonical iteration order for every
     * analytics consumer. Callers MUST route their input through this.
     */
    fun filter(records: Collection<TradeRecord>): List<TradeRecord> =
        records.filter { isEligible(it) }

    fun statusLine(): String =
        "evaluated=${evaluated.get()} eligible=${eligible.get()} " +
            "droppedPartial=${droppedPartial.get()} droppedInvariant=${droppedInvariant.get()} " +
            "droppedHistorical=${droppedHistorical.get()}"

    internal fun resetForTest() {
        evaluated.set(0L); eligible.set(0L)
        droppedPartial.set(0L); droppedInvariant.set(0L); droppedHistorical.set(0L)
    }
}
