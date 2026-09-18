package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.Position
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector

/**
 * V5.0.7017 §NO_POSITION_SHOULD_EVER_GO_UNPRICEABLE.
 *
 * Operator, in exactly those words, after seeing what V5.0.6895's refusal
 * actually costs.
 *
 * THE MISTAKE I ALMOST SHIPPED
 * ===========================
 * My first answer to "unpriceable isn't flat" was a graceful exit for
 * unpriceable positions — route them through StaleMarkRunnerProtection6829 and
 * scratch the losers out. That is treating the symptom. A position that cannot
 * be priced is a measurement failure, not a market condition, and the right
 * response is to take the measurement, not to design a dignified way of giving
 * up on it. This file takes the measurement.
 *
 * WHY A CROSS-SOURCE MARK IS REFUSED AT ALL
 * =========================================
 * Price-per-token is basis-dependent. A pump.fun bonding-curve quote and a
 * DexScreener AMM pair quote for the same mint are the same token measured two
 * ways, and V5.0.6895 refuses to compare them when they disagree by more than
 * 10x — correctly, because booking that difference as P&L is how the operator's
 * book once took a +15,532% phantom partial.
 *
 * But MARKET CAP IS NOT BASIS-DEPENDENT. It is the same quantity no matter who
 * reports it, because supply cancels out. So for any position whose entry
 * recorded both a price and a market cap, a tick from ANY source that reports a
 * market cap can be placed exactly on the entry's own basis:
 *
 *     comparablePrice = entryPrice x (currentMcap / entryMcap)
 *
 * No supply, no decimals, no knowledge of which DEX either side is quoting.
 * Exact, not approximate.
 *
 * THIS CONVERSION ALREADY EXISTED IN THIS CODEBASE
 * ================================================
 * CryptoAltActivity.uiComparableOpenPrice4479 has computed
 * `entryPrice * (currentMcap / entryMcapUsd)` since V5.0.4479, and its own
 * comment marks it "Display only". So the app has known how to price these
 * positions for hundreds of builds, and used that knowledge to paint a panel
 * while the engine beside it served entryPrice and evaluated 195,694 exits at
 * exactly 0.00%. The same shape as AateComponents6994 and as 6976's synthetic
 * tag: built, correct, and never wired to the thing that needed it.
 *
 * WORKED AGAINST THE OPERATOR'S 5.0.7012 SNAPSHOT
 * ===============================================
 *   POPINU  entry     = 4.4507569e-06 at mcap $4,239   (supply ~1e9 ✓)
 *           refused tick implies mcap $2,956
 *           reconciled = 4.4507569e-06 x (2956/4239) = 3.104e-06  → -30.2%
 *
 * That is a real, actionable mark. A stop-loss can act on -30.2%. Nothing can
 * act on the 0.00% the position was showing.
 *
 * Note what this means for V5.0.7016: market cap came straight off
 * `usd_market_cap` and was CORRECT even while the derived price was a
 * millionfold wrong, because only the supply divisor was broken. Reconciling
 * through market cap would have kept every one of those positions priceable
 * through the entire unit bug. That is the point of preferring the
 * basis-independent quantity — it survives the class of defect that breaks the
 * basis-dependent one.
 *
 * DOCTRINE
 * ========
 * - Runner capture is not negotiable (V5.9.1358). This function does NOT clamp,
 *   band or sanity-cap the result. A 1000x market cap move produces a 1000x
 *   reconciled price, because that is what happened. The 10x band that refuses
 *   raw cross-source ticks exists because those ticks are incomparable; a
 *   reconciled tick is comparable by construction, so applying a band to it
 *   would be re-introducing the very error it removes.
 * - Returns 0.0 — never a guess — when either market cap is missing. A mark
 *   this cannot produce is genuinely unknown, and saying so is the only honest
 *   answer left.
 */
object MarkBasisReconciler7017 {

    private val attempted = java.util.concurrent.atomic.AtomicLong(0)
    private val reconciled = java.util.concurrent.atomic.AtomicLong(0)
    private val noEntryMcap = java.util.concurrent.atomic.AtomicLong(0)
    private val noCurrentMcap = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * The entry's market cap. Position carries TWO fields for this one
     * quantity — `entryMcap` (V4.20) and `entryMcapUsd` (V5.9.744) — populated
     * by different paths, so either can be the only one set on a given
     * position. Reading both here keeps that split from silently halving the
     * number of positions this can rescue; it is not an endorsement of having
     * two fields.
     */
    fun entryMcapOf(pos: Position): Double {
        val a = pos.entryMcapUsd
        if (a.isFinite() && a > 0.0) return a
        val b = pos.entryMcap
        if (b.isFinite() && b > 0.0) return b
        // Last: recovered from the first on-basis tick after entry, for
        // positions whose open path recorded neither field. See
        // Executor.getActualPrice §7017 for why that observation is exact.
        val c = pos.entryMcapBackfilled7017
        if (c.isFinite() && c > 0.0) return c
        return 0.0
    }

    /**
     * Place [currentMcap] on the entry's price basis. Returns 0.0 when the
     * conversion is not available.
     */
    fun reconcile(entryPrice: Double, entryMcap: Double, currentMcap: Double): Double {
        attempted.incrementAndGet()
        if (!entryPrice.isFinite() || entryPrice <= 0.0) return 0.0
        if (!entryMcap.isFinite() || entryMcap <= 0.0) {
            noEntryMcap.incrementAndGet()
            return 0.0
        }
        if (!currentMcap.isFinite() || currentMcap <= 0.0) {
            noCurrentMcap.incrementAndGet()
            return 0.0
        }
        val px = entryPrice * (currentMcap / entryMcap)
        if (!px.isFinite() || px <= 0.0) return 0.0
        reconciled.incrementAndGet()
        return px
    }

    /**
     * Convenience for the one engine call site: reconcile a refused cross-source
     * tick for [pos] using the market cap that arrived with it.
     */
    fun reconcileForPosition(pos: Position, currentMcap: Double): Double =
        reconcile(pos.entryPrice, entryMcapOf(pos), currentMcap)

    fun note(
        mint: String,
        symbol: String,
        entryPrice: Double,
        entryMcap: Double,
        currentMcap: Double,
        rawTick: Double,
        reconciledPx: Double,
        refusals: Long,
    ) {
        try {
            PipelineHealthCollector.labelInc("MARK_BASIS_RECONCILED_7017")
        } catch (_: Throwable) {}
        // One line per 20 refusals: these fire per management pass per position
        // and a hundred held positions would otherwise drown the log, which is
        // what the refusal counter itself had to be throttled for.
        if (refusals % 20L == 1L) {
            try {
                val movePct = ((reconciledPx - entryPrice) / entryPrice) * 100.0
                ForensicLogger.lifecycle(
                    "MARK_BASIS_RECONCILED_7017",
                    "mint=${mint.take(10)} sym=$symbol entry=$entryPrice " +
                        "entryMcap=${entryMcap.toLong()} curMcap=${currentMcap.toLong()} " +
                        "rawTick=$rawTick reconciled=$reconciledPx " +
                        "move=${"%+.1f".format(movePct)}% refusals=$refusals " +
                        "action=priced_on_entry_basis_via_marketcap_not_refused",
                )
            } catch (_: Throwable) {}
        }
    }

    /** Diagnostic line for the pipeline report. */
    fun status(): String =
        "attempted=${attempted.get()} reconciled=${reconciled.get()} " +
            "noEntryMcap=${noEntryMcap.get()} noCurrentMcap=${noCurrentMcap.get()}"
}
