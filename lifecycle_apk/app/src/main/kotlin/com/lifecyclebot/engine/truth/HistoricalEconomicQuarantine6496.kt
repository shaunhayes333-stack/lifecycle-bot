package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6496 §2 — HISTORICAL ECONOMIC QUARANTINE.
 *
 * OPERATOR MANDATE (verbatim, 6495 evidence):
 *
 *   "The forensic reconciler reports BUY_SELL_QTY_SKEW over-sold
 *    mints=13, replay realizedΔ=0.9117 openCostΔ=0.9117,
 *    orphanOpenCost=0.006628 orphanLots=1. That tells me the new
 *    transactional path is probably fixed, but corrupted historical
 *    /replayed data remains in the corpus.
 *
 *    Do not allow those 13 skewed positions or replay-divergent rows
 *    into: tactic μ, win-rate calculations, Forward Outcome Model,
 *    Unified Policy Head, GrowthRewardShaper, losing-streak memory,
 *    position sizing. They need a permanent economic-integrity
 *    quarantine."
 *
 * DESIGN
 * ──────
 * Thin façade over the existing `LearningQuarantineGate6470`. Each
 * historical fault detector calls the appropriate `reportX(...)`
 * method here; we forward to `LearningQuarantineGate6470` with a
 * standardised reason tag so the operator can see WHICH historical
 * fault caused any given quarantine downstream.
 *
 * Every category is permanent for the mint/position within the run
 * — a corrupted historical outcome cannot be un-corrupted by a
 * later good row. Only fresh distinct mints continue to feed
 * learners.
 *
 * Feed points:
 *   • ForensicReconciler6377 BUY_SELL_QTY_SKEW  → reportBuySellSkew
 *   • CanonicalPaperReplay6464 orphan lots      → reportOrphanLot
 *   • EventStreamReplay6467 divergence          → reportEventStreamDivergence
 *   • PaperAccountReplay6461 divergence         → reportPaperReplayDivergence
 */
object HistoricalEconomicQuarantine6496 {

    private val skewCalls = AtomicLong(0L)
    private val orphanCalls = AtomicLong(0L)
    private val eventDivergenceCalls = AtomicLong(0L)
    private val paperDivergenceCalls = AtomicLong(0L)
    private val mintQuarantines = AtomicLong(0L)
    private val positionQuarantines = AtomicLong(0L)

    fun reportBuySellSkew(mints: Collection<String>) {
        skewCalls.incrementAndGet()
        var newly = 0
        for (mint in mints) {
            if (mint.isBlank()) continue
            val already = LearningQuarantineGate6470.isQuarantined(positionId = null, mint = mint)
            LearningQuarantineGate6470.quarantineMint(mint, "BUY_SELL_QTY_SKEW_6377")
            if (!already) {
                newly++
                mintQuarantines.incrementAndGet()
            }
        }
        if (newly > 0) {
            try {
                ForensicLogger.lifecycle(
                    "HISTORICAL_QUARANTINE_SKEW_6496",
                    "newMints=$newly totalNow=${LearningQuarantineGate6470.statusLine()}",
                )
                PipelineHealthCollector.labelInc("HISTORICAL_QUARANTINE_SKEW_6496")
            } catch (_: Throwable) {}
        }
    }

    fun reportOrphanLot(mint: String, orphanCostSol: Double) {
        if (mint.isBlank()) return
        orphanCalls.incrementAndGet()
        val already = LearningQuarantineGate6470.isQuarantined(positionId = null, mint = mint)
        LearningQuarantineGate6470.quarantineMint(
            mint,
            "OPEN_COST_WITHOUT_CANONICAL_LOT_6475(cost=${"%.6f".format(orphanCostSol)})",
        )
        if (!already) {
            mintQuarantines.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("HISTORICAL_QUARANTINE_ORPHAN_6496")
            } catch (_: Throwable) {}
        }
    }

    /**
     * Called when the event stream replay reports a first-divergent
     * event id. We quarantine BOTH the mint (if resolvable) AND the
     * eventId as a position tag — the ID field carries the
     * idempotency key which uniquely identifies the position.
     */
    fun reportEventStreamDivergence(firstDivergentEventId: String?, mint: String?) {
        eventDivergenceCalls.incrementAndGet()
        if (!firstDivergentEventId.isNullOrBlank()) {
            LearningQuarantineGate6470.quarantinePositionId(
                firstDivergentEventId,
                "EVENT_STREAM_REPLAY_DIVERGED_6467",
            )
            positionQuarantines.incrementAndGet()
        }
        if (!mint.isNullOrBlank()) {
            LearningQuarantineGate6470.quarantineMint(mint, "EVENT_STREAM_REPLAY_DIVERGED_6467")
            mintQuarantines.incrementAndGet()
        }
        try {
            PipelineHealthCollector.labelInc("HISTORICAL_QUARANTINE_EVENT_DIVERGENCE_6496")
        } catch (_: Throwable) {}
    }

    fun reportPaperReplayDivergence(mints: Collection<String>) {
        paperDivergenceCalls.incrementAndGet()
        for (mint in mints) {
            if (mint.isBlank()) continue
            LearningQuarantineGate6470.quarantineMint(mint, "PAPER_REPLAY_DIVERGENCE_6461")
        }
        try {
            PipelineHealthCollector.labelInc("HISTORICAL_QUARANTINE_PAPER_DIVERGENCE_6496")
        } catch (_: Throwable) {}
    }

    fun statusLine(): String =
        "skewCalls=${skewCalls.get()} orphanCalls=${orphanCalls.get()} " +
            "eventDivergences=${eventDivergenceCalls.get()} paperDivergences=${paperDivergenceCalls.get()} " +
            "mintQuarantines=${mintQuarantines.get()} positionQuarantines=${positionQuarantines.get()}"

    internal fun resetForTest() {
        skewCalls.set(0L); orphanCalls.set(0L)
        eventDivergenceCalls.set(0L); paperDivergenceCalls.set(0L)
        mintQuarantines.set(0L); positionQuarantines.set(0L)
    }
}
