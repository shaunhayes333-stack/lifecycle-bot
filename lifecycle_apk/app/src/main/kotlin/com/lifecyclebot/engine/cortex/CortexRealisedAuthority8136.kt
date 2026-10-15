package com.lifecyclebot.engine.cortex

/**
 * V5.0.8136 §AUTHORITY_FROM_REALISED_CLOSES — the Cortex's STRONG-side authority answers to real money.
 *
 * Graduated authority (CortexScoreboard7885.fractionFor7955) is earned on forward LABELS (the 5-minute read). The
 * realised whole-position closes the Cortex files by its entry verdict (board.realized, OutcomeTruth) only ever
 * suspended a lane when STRONG trailed NEUTRAL/REFUSE (inversion). 8127: STRONG reads on positive 5-minute labels
 * whose realised closes LOST still held overrule / size-up power, because every bucket lost together and nothing
 * looked at STRONG's own realised sign.
 *
 * Now the label fraction is capped by the realised record of the lane's STRONG reads in the account mode:
 *   - fewer than [MIN_REALISED_N_8136] realised closes: at most partial (labels alone never grant full authority);
 *   - [MIN_REALISED_N_8136]+ closes with mean <= 0: DENIED (0) — negative realised closes earn no authority;
 *   - positive mean but not yet mean - SE > 0 on [FULL_REALISED_N_8136]+ closes: at most partial;
 *   - [FULL_REALISED_N_8136]+ closes with mean - SE > 0: the label fraction stands.
 * The cap only lowers authority. It re-reads every call, so authority returns when the realised record does.
 */
object CortexRealisedAuthority8136 {
    const val MIN_REALISED_N_8136 = 5.0
    const val FULL_REALISED_N_8136 = 20.0

    enum class Verdict8136 { NO_REALISED_PARTIAL, DENIED_REALISED_NEGATIVE, REALISED_UNPROVEN_PARTIAL, REALISED_PROVEN }

    /** Pure: the realised verdict for a STRONG record of [n] closes, mean [meanPct], standard error [sePct]. */
    fun verdict8136(n: Double, meanPct: Double, sePct: Double): Verdict8136 = when {
        !n.isFinite() || n < MIN_REALISED_N_8136 || !meanPct.isFinite() -> Verdict8136.NO_REALISED_PARTIAL
        meanPct <= 0.0 -> Verdict8136.DENIED_REALISED_NEGATIVE
        n >= FULL_REALISED_N_8136 && sePct.isFinite() && meanPct - sePct > 0.0 -> Verdict8136.REALISED_PROVEN
        else -> Verdict8136.REALISED_UNPROVEN_PARTIAL
    }

    /** Pure: [labelFraction] after the realised cap (never raised). */
    fun cappedFraction8136(labelFraction: Double, n: Double, meanPct: Double, sePct: Double): Double {
        if (!labelFraction.isFinite() || labelFraction <= 0.0) return 0.0
        return when (verdict8136(n, meanPct, sePct)) {
            Verdict8136.DENIED_REALISED_NEGATIVE -> 0.0
            Verdict8136.NO_REALISED_PARTIAL, Verdict8136.REALISED_UNPROVEN_PARTIAL ->
                minOf(labelFraction, CortexScoreboard7885.GRAD_PARTIAL_CAP_7955)
            Verdict8136.REALISED_PROVEN -> labelFraction
        }
    }
}
