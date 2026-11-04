package com.lifecyclebot.perps.crypto.brain

/**
 * V5.0.8535 §C10 — one definition of a crypto close across every brain. superseded: `win = pnlPct > 0` (fluid wins /
 * losses, tactic rotation) beside `trainable = |pnl| >= 1` (canonical): a +0.1 % scratch was a win in one brain, a
 * -0.1 % scratch a tactic-changing loss, and both inconclusive in canonical stats.
 */
object CryptoOutcome8535 {
    enum class Kind { NET_WIN, NET_LOSS, SCRATCH, UNKNOWN }

    /** |pnl| under this (percent, net of the desk's own fee read) is a scratch: a terminal economic outcome, not direction evidence. */
    private const val SCRATCH_BAND_PCT = 1.0

    fun classify(pnlPct: Double): Kind = when {
        !pnlPct.isFinite() -> Kind.UNKNOWN
        pnlPct >= SCRATCH_BAND_PCT -> Kind.NET_WIN
        pnlPct <= -SCRATCH_BAND_PCT -> Kind.NET_LOSS
        else -> Kind.SCRATCH
    }

    fun trainable(kind: Kind): Boolean = kind == Kind.NET_WIN || kind == Kind.NET_LOSS
}
