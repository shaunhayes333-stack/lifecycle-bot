package com.lifecyclebot.engine.truth

object LaneSweep8555 {
    const val PAPER_PROFIT_EXIT_MIN_PCT = 4.0

    /** Common gross threshold for discretionary fade exits. Protective exits are evaluated separately. */
    fun profitExitAllowed(pnlPct: Double): Boolean = pnlPct > PAPER_PROFIT_EXIT_MIN_PCT
}
