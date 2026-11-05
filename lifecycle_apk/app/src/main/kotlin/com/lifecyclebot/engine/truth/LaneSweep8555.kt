package com.lifecyclebot.engine.truth

/**
 * V5.0.8555 §LANE_SWEEP — the per-lane setup & strategy audit's shared rules (docs/DATA_MAP.md is the data side; the
 * register rows N-85.. are the lane side). What tightens applies in both modes; what loosens an entry or holds an exit
 * longer is PAPER-only until the lane / family clears the promotion bar (n >= 30 closes, mean - se > 0, PF >= 1.3,
 * MemePaperTrader8544.proofOf8546).
 */
object LaneSweep8555 {
    /**
     * The smallest gross profit a lane's discretionary "take it now" exit (momentum death, buy-pressure fade) may bank in
     * PAPER: the measured median round trip (2.8 % in the 559-decision export) + 1 % (FIELD_MANUAL §8.4: no exit that locks a
     * net loss except the stop). LIVE keeps its 0 until the paper record proves the change.
     */
    const val PAPER_PROFIT_EXIT_MIN_PCT = 4.0

    /** Pure: may a discretionary profit exit fire at [pnlPct] gross? */
    fun profitExitAllowed(pnlPct: Double, paper: Boolean): Boolean = if (paper) pnlPct > PAPER_PROFIT_EXIT_MIN_PCT else pnlPct > 0.0
}
