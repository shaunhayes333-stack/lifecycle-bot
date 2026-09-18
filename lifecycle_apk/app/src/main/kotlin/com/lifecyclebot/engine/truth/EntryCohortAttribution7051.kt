package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.Trade
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7051 §THE_COHORT_LOST_ITS_WINNERS_TO_PROMOTION.
 *
 * THE OPERATOR'S INSTRUCTION, which is what found this: "remember tokens can
 * cross lanes, trading logic, holding logic, graduate to dex, many things mid
 * token hold. this must be examined and checked as well."
 *
 * THE DEFECT. A position entered by PROJECT_SNIPER that runs +100% is promoted
 * mid-hold and closes credited to whoever it was promoted INTO:
 *
 *   BotService:1615        ts.position.tradingMode = td.targetLane
 *   Executor:10739/10787/12060/15573   same, via holding-logic + treasury
 *   Executor:8691/8954/9706/11340      Trade.tradingMode = pos.tradingMode
 *   LosingPatternMemory:87 bucketKey(tradingMode = t.tradingMode, score)
 *
 * The losers never promote — they never run — so they stay. The cohort is
 * therefore biased toward losses BY THE EXACT MECHANISM THAT DEFINES A WINNER,
 * and this is the store that feeds danger-zone detection and size shaping.
 *
 * 5.0.7047 reports PROJECT_SNIPER|S11-25 losses=9 wins=2 and simultaneously
 * meanPnl=+20.26% with EV=+12.07%/trade. Shaping size down on a win count that
 * had its winners moved to another column is precisely how the trade-quality
 * directive's §6 ("do not kill runners early") gets violated by a change aimed
 * at §1.
 *
 * WHY NOT FIX IT AT THE SOURCE. Trade.tradingMode is written by the sell path,
 * and trade emission is on the operator's do-not-change list. Position
 * .modeHistory already records the whole chain ("PROJECT_SNIPER>MOONSHOT"), is
 * persisted (PositionPersistence:784), and is written in four places — and read
 * by nothing. V5.0.6855 recorded the provenance and never wired a consumer, so
 * the originating lane still never finds out its pick ran.
 *
 * WHAT THIS DOES. LaneAttributionLedger6427 already stamps the entry lane per
 * positionId at buy time (ExecutorCanonicalMirror6442:210) and is never
 * rewritten by a promotion, so it holds the ORIGIN. This resolves a closed
 * trade back to the lane that actually took the entry, and — more importantly
 * for the decision in front of us — MEASURES how often the two disagree, so we
 * can see the size of the leak before anything is shaped against it.
 *
 * READ-ONLY. No mutation of trades, positions, ledger or capital. It reads an
 * existing ledger and answers a question.
 */
object EntryCohortAttribution7051 {

    private val resolvedFromLedger = AtomicLong(0L)
    private val fellBackToFinalLane = AtomicLong(0L)
    private val promotionLeaks = AtomicLong(0L)
    private val leakWins = AtomicLong(0L)

    /**
     * The lane that ENTERED this trade.
     *
     * Falls back to the trade's own (final) lane when the ledger has no row —
     * it is in-memory, so positions restored from a previous process have none.
     * The fallback is counted rather than hidden: a high fallback share means
     * this attribution is only as good as the current session, and any cohort
     * read across a restart is still the old, leaky number.
     */
    fun entryLaneFor(t: Trade): String {
        val finalLane = t.tradingMode.ifBlank { "STANDARD" }
        val entry = try {
            LaneAttributionLedger6427.getEntryLane(t.positionId)?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) { null }
        if (entry == null) {
            fellBackToFinalLane.incrementAndGet()
            return finalLane
        }
        resolvedFromLedger.incrementAndGet()
        if (!entry.equals(finalLane, ignoreCase = true)) {
            promotionLeaks.incrementAndGet()
            // A leak that closed in profit is a WINNER the entering lane never
            // got credit for. That is the number that decides whether the
            // "losses=9 wins=2" picture can be trusted, so it is counted apart
            // from the leak total instead of being folded into it.
            if (t.pnlPct > 0.0) {
                leakWins.incrementAndGet()
                try {
                    PipelineHealthCollector.labelInc("ENTRY_COHORT_PROMOTION_LEAK_WIN_7051_${entry.uppercase()}_TO_${finalLane.uppercase()}")
                } catch (_: Throwable) {}
            }
            try { PipelineHealthCollector.labelInc("ENTRY_COHORT_PROMOTION_LEAK_7051") } catch (_: Throwable) {}
        }
        return entry
    }

    fun statusLine7051(): String =
        "resolved=${resolvedFromLedger.get()} fallbackFinalLane=${fellBackToFinalLane.get()} " +
            "promotionLeaks=${promotionLeaks.get()} leakWins=${leakWins.get()} " +
            "read=a_leak_win_is_a_runner_the_entering_lane_was_never_credited_with"

    internal fun resetForTest() {
        resolvedFromLedger.set(0L); fellBackToFinalLane.set(0L)
        promotionLeaks.set(0L); leakWins.set(0L)
    }
}
