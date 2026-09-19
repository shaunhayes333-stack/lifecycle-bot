package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector

/**
 * V5.0.7032 §THE_LEARNERS_ARE_STILL_TRAINING_ON_IT.
 *
 * V5.0.7029 stopped the paper partial paths booking USD as SOL. It did not,
 * and could not, undo the rows already written — and those rows are not
 * merely a wrong number on a card. They are the training set.
 *
 * From the operator's 5.0.7027 capture:
 *
 *   FORENSIC_REALIZED_DELTA_6635  ledger=92.519955  journal=0.632539
 *   Tactic switcher:  PROJECT_SNIPER|S0-10 BREAKOUT   mu=+28406.8%
 *                     QUALITY|S0-10       MOMENTUM    mu=+33506.0%
 *                     CORE|S0-10          MOMENTUM    mu=+34148.5%
 *
 * The ledger says it realised 92.52 SOL; the journal, which records each
 * trade's own economics, says 0.63. A factor of 146 — the SOL/USD rate. And
 * the tactic switcher is rotating live strategy on mu values of +34,000%,
 * which is that same inflation reaching the thing that picks how the bot
 * trades. LaneExpectancyDamper, UnifiedPolicyHead, GrowthRewardShaper,
 * CapitalCreed and ForwardOutcomeModel all read the same bus.
 *
 * WHY THIS IS A QUARANTINE AND NOT A RESTATEMENT.
 *
 * I cannot reconstruct the true value of those rows, and I am not going to
 * pretend otherwise. EconomicEventSchema6464.Sell records grossProceedsSol,
 * the quantity and the basis — but NOT the mark that priced the sale, and NOT
 * the SOL/USD rate at the time. Without either, a corrupted partial and a
 * genuine 140x runner are literally the same row on disk.
 *
 * Dividing history by TODAY's SOL price would produce numbers that look like
 * a repair and are actually a fresh invention — the same defect class as
 * proceeds nothing can reconstruct, which is what caused this. The honest
 * operation on data whose provenance is unrecoverable is to mark it
 * unreliable and stop consuming it, which is what an auditor does with an
 * entry they cannot substantiate.
 *
 * 7032 adds exitPriceUsd + solUsdAtExit to the Sell event so this is
 * answerable forever after. The ABSENCE of solUsdAtExit is therefore an exact
 * marker of a pre-fix row — no date arithmetic, no build-number heuristic, no
 * guessing: the row either carries what priced it or it does not.
 *
 * SCOPE. A position that carries even one pre-fix paper PARTIAL is quarantined
 * whole, including its final close. That is deliberate and it is not
 * over-reach: a later full sell computes its realised P&L against a basis the
 * corrupted partial already reduced, so the terminal row inherits the error.
 * Half-excluding such a position would leave the learners a subtly wrong
 * number instead of an obviously missing one, and a subtly wrong number is
 * worse.
 *
 * Full sells on positions that never took a pre-fix partial are untouched —
 * that path never had the units defect, and it is the bulk of the history.
 *
 * NOT A LEDGER REWRITE. Nothing here changes cash, realised P&L or any
 * position. The account keeps its history and stays auditable; the LEARNERS
 * stop being fed it. Restating the simulated account is a separate action and
 * belongs to the operator, because voiding someone's balance is not a call to
 * make on their behalf.
 */
object ContaminatedPartialQuarantine7032 {

    /** positionIds whose economics cannot be substantiated. */
    private val contaminated = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    @Volatile private var scanned = false
    @Volatile private var partialsSeen = 0
    @Volatile private var lastSummary = "not scanned"

    /**
     * Scan the durable economic events once and collect every position
     * carrying an unreconstructible paper partial.
     *
     * Called after EconomicEventSchema6464 has loaded from disk. Idempotent;
     * a second call re-scans, which is harmless and picks up anything restored
     * late.
     */
    fun scan() {
        val events = try { EconomicEventSchema6464.snapshot() } catch (_: Throwable) { emptyList() }
        if (events.isEmpty()) return
        var partials = 0
        var unreconstructible = 0
        var dimensional7064 = 0
        val ids = HashSet<String>()
        // V5.0.7064 §9 — scale lookups are per position, not per event.
        val scaleCache7064 = HashMap<String, Int>()
        var firstCorrupt7064 = Long.MAX_VALUE
        for (e in events) {
            if (e !is EconomicEventSchema6464.Sell) continue
            if (!e.partial) continue
            if (!e.mode.equals("paper", true)) continue
            partials++
            // The marker. A partial written by 7032 or later carries the rate
            // its proceeds were converted with; one written before does not,
            // and nothing on disk can supply it after the fact.
            if (e.solUsdAtExit <= 0.0) {
                unreconstructible++
                if (e.positionId.isNotBlank()) ids.add(e.positionId)
                continue
            }
            // V5.0.7064 §9 — A PARTIAL THAT CARRIES ITS RATE CAN BE CHECKED.
            //
            // 7032 quarantined only the partials that could NOT be
            // reconstructed, and then stopped — a partial that recorded a rate
            // was treated as clean without anyone performing the
            // reconstruction it had just been handed the ingredients for. That
            // is the gap the operator's +71.8922 SOL sits in: those rows carry
            // an exit price and a rate, so 7032 waved them through, and their
            // proceeds are still a USD figure wearing a SOL label.
            //
            // Directive §2's arithmetic, applied retrospectively to the log.
            if (e.exitPriceUsd > 0.0 && e.solUsdAtExit >= 20.0 && e.positionId.isNotBlank()) {
                val scale = scaleCache7064.getOrPut(e.positionId) {
                    try {
                        CanonicalPositionAuthority6441.getPosition(e.positionId)?.quantityScale ?: -1
                    } catch (_: Throwable) { -1 }
                }
                if (scale in 0..18) {
                    val soldTokens = try {
                        java.math.BigDecimal(e.soldQty).movePointLeft(scale).toDouble()
                    } catch (_: Throwable) { 0.0 }
                    if (soldTokens.isFinite() && soldTokens > 0.0) {
                        val expected = (soldTokens * e.exitPriceUsd) / e.solUsdAtExit
                        val allowance = maxOf(1e-9, expected * 0.01)
                        if (expected.isFinite() && expected > 0.0 &&
                            kotlin.math.abs(e.grossProceedsSol - expected) > allowance
                        ) {
                            dimensional7064++
                            ids.add(e.positionId)
                            if (e.atMs in 1 until firstCorrupt7064) firstCorrupt7064 = e.atMs
                        }
                    }
                }
            }
        }
        // §9 — "quarantine every position/event DOWNSTREAM of the first corrupt
        // partial economic commit". A dimensionally broken commit does not
        // damage only its own row: it moves cash and realized P&L, and every
        // position sized, scored or rewarded after that point was decided
        // against a book that already held the error. Sweeping forward from the
        // earliest one is the only honest boundary.
        //
        // Only a DIMENSIONAL failure opens the epoch, never a missing rate. A
        // missing rate means "cannot be checked", and treating that as proof of
        // corruption would let one unpriced legacy row disable learning for the
        // whole book — the opposite of the operator's rule that nothing be
        // excluded for want of data.
        var downstream7064 = 0
        if (firstCorrupt7064 != Long.MAX_VALUE) {
            for (e in events) {
                if (!e.mode.equals("paper", true)) continue
                if (e.atMs < firstCorrupt7064) continue
                if (e.positionId.isBlank()) continue
                if (ids.add(e.positionId)) downstream7064++
            }
            firstCorruptAtMs7064 = firstCorrupt7064
        }
        contaminated.addAll(ids)
        partialsSeen = partials
        scanned = true
        lastSummary = "partials=$partials unreconstructible=$unreconstructible " +
            "dimensional=$dimensional7064 downstream=$downstream7064 positions=${ids.size}"
        try {
            PipelineHealthCollector.labelInc("CONTAMINATED_PARTIAL_QUARANTINE_7032")
            ForensicLogger.lifecycle(
                "CONTAMINATED_PARTIAL_QUARANTINE_7032",
                "$lastSummary reason=pre_7032_partial_carries_no_exit_rate " +
                    "action=exclude_position_from_learning_ledger_untouched",
            )
        } catch (_: Throwable) {}
    }

    /**
     * True when this position's economics cannot be substantiated and must not
     * reach a learner.
     *
     * Fails CLOSED on an unscanned store — if the scan has not run we do not
     * know which positions are clean, and feeding a learner on the assumption
     * that everything is fine is exactly how the contaminated rows got in.
     * scan() runs during bootstrap, well before the first close of a session.
     */
    fun isContaminated(positionId: String): Boolean {
        if (positionId.isBlank()) return false
        return contaminated.contains(positionId)
    }

    /**
     * V5.0.7064 §9 — wallclock of the earliest dimensionally corrupt paper
     * partial found in the log, or 0 when none. Everything the paper book did
     * at or after this instant was decided against an already-wrong balance.
     * Reset by §10's replay, which is what actually clears the epoch; this is
     * a measurement of the contamination, not a permanent state.
     */
    @Volatile
    var firstCorruptAtMs7064: Long = 0L
        private set

    fun statusLine(): String =
        "scanned=$scanned quarantinedPositions=${contaminated.size} partialsSeen=$partialsSeen " +
            "firstCorruptAtMs=$firstCorruptAtMs7064 [$lastSummary]"
}
