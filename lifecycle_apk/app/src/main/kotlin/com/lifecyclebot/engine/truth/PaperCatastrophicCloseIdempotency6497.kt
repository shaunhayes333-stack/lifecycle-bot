package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6497 §4 — PAPER CATASTROPHIC CLOSE IDEMPOTENCY.
 *
 * OPERATOR MANDATE (verbatim, 6496 evidence):
 *
 *   "Second serious fault — paper exits are stuck. MER is producing
 *    a ridiculous retry storm:
 *      ZOMBIE_CATASTROPHE_PENDING_RETRY = 554
 *      SELL_RECONCILER_URGENT_TICK_REQUESTED = 554
 *      TICK_CATASTROPHIC... = 260
 *      PAPER_CLOSE_FAILED = 16
 *      TERMINAL_SELL_ABANDONED = 32
 *      PAPER_CLOSE_STUCK_TTL_RETRY = 28
 *
 *    The recent events confirm the same MER position is repeatedly
 *    being detected at about -99.9% and repeatedly requesting an
 *    urgent sell without achieving a terminal close.
 *
 *    For PAPER mode, once a catastrophic position has a valid
 *    canonical lot, there should be no reason to hammer the sell
 *    reconciler hundreds of times. It should atomically:
 *      claim terminal → calculate simulated close → journal →
 *      mutate canonical lot CLOSED → release occupancy
 *    or quarantine exactly once if the economic record is corrupt.
 *
 *    P0: Make paper catastrophic closes terminal/idempotent so MER
 *    cannot generate hundreds of sell retries."
 *
 * DESIGN
 * ──────
 * Per-mint one-shot latch. `tryClaim(mint)` returns true only the
 * FIRST time a paper catastrophic close is attempted for a mint.
 * All subsequent calls return false → the caller short-circuits
 * without re-requesting a sell / bumping ZOMBIE_CATASTROPHE_PENDING_RETRY.
 *
 * On successful close the latch stays claimed (so no retries fire).
 * On CORRUPT_ECONOMIC_RECORD outcome, the caller invokes
 * `quarantineOnce(mint)` which routes to `HistoricalEconomicQuarantine6496`
 * exactly once — never twice for the same mint.
 *
 * `release(mint)` exists for the rare case a legitimate later
 * recovery path wants to attempt one more close (e.g. after
 * economic corruption is corrected). Not called from the zombie
 * retry hot path.
 */
object PaperCatastrophicCloseIdempotency6497 {

    enum class Outcome {
        CLAIMED_FIRST,    // caller MAY attempt the close
        ALREADY_CLAIMED,  // caller MUST short-circuit
    }

    private data class Latch(
        val claimedAtMs: Long,
        val symbol: String,
    )

    private val latches = ConcurrentHashMap<String, Latch>()
    private val quarantined = ConcurrentHashMap<String, Boolean>()
    private val firstClaims = AtomicLong(0L)
    private val shortCircuits = AtomicLong(0L)
    private val quarantineOnces = AtomicLong(0L)

    fun tryClaim(mint: String, symbol: String): Outcome {
        if (mint.isBlank()) return Outcome.CLAIMED_FIRST
        val prior = latches.putIfAbsent(mint, Latch(System.currentTimeMillis(), symbol))
        return if (prior == null) {
            firstClaims.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "PAPER_CATASTROPHE_CLOSE_CLAIMED_6497",
                    "mint=${mint.take(10)} symbol=$symbol action=first_attempt",
                )
                PipelineHealthCollector.labelInc("PAPER_CATASTROPHE_CLOSE_CLAIMED_6497")
            } catch (_: Throwable) {}
            Outcome.CLAIMED_FIRST
        } else {
            shortCircuits.incrementAndGet()
            try { PipelineHealthCollector.labelInc("PAPER_CATASTROPHE_CLOSE_SHORTCIRCUIT_6497") } catch (_: Throwable) {}
            Outcome.ALREADY_CLAIMED
        }
    }

    /**
     * Route a corrupt economic record to HistoricalEconomicQuarantine6496
     * exactly once per mint. Subsequent calls are no-ops.
     */
    fun quarantineOnce(mint: String, reason: String) {
        if (mint.isBlank()) return
        if (quarantined.putIfAbsent(mint, true) != null) return
        quarantineOnces.incrementAndGet()
        try {
            HistoricalEconomicQuarantine6496.reportOrphanLot(mint, 0.0)
            ForensicLogger.lifecycle(
                "PAPER_CATASTROPHE_QUARANTINE_ONCE_6497",
                "mint=${mint.take(10)} reason=$reason",
            )
            PipelineHealthCollector.labelInc("PAPER_CATASTROPHE_QUARANTINE_ONCE_6497")
        } catch (_: Throwable) {}
    }

    /** Rare: allow a corrected mint to attempt one more close. */
    fun release(mint: String) {
        latches.remove(mint)
        quarantined.remove(mint)
    }

    fun isClaimed(mint: String): Boolean = latches.containsKey(mint)

    fun statusLine(): String =
        "firstClaims=${firstClaims.get()} shortCircuits=${shortCircuits.get()} " +
            "quarantineOnces=${quarantineOnces.get()} live=${latches.size}"

    internal fun resetForTest() {
        latches.clear(); quarantined.clear()
        firstClaims.set(0L); shortCircuits.set(0L); quarantineOnces.set(0L)
    }
}
