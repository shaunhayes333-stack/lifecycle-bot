package com.lifecyclebot.engine

/**
 * V5.9.495z31 — RugCheck deterministic policy.
 *
 * Operator-reported inconsistency: same token can be both
 * "RC PENDING — allowing for evaluation" AND "RC PENDING — +15 penalty".
 *
 * Make this a single deterministic policy. In live mode RC pending is
 * blocked by default unless an explicit high-score override fires. In
 * paper mode RC pending is allowed for learning.
 */
object RugCheckPolicy {

    enum class State {
        RC_CONFIRMED_SAFE,
        RC_CONFIRMED_RISKY,
        RC_PENDING_BLOCKED,
        RC_PENDING_ALLOWED_PAPER,
        RC_PENDING_ALLOWED_LIVE_OVERRIDE,
    }

    /** Threshold above which a high-score override allows live entry
     *  on a token that hasn't completed RugCheck yet.
     *
     *  V5.9.736 — lowered 75 → 70. Operator live-mode log showed strong
     *  fresh-launch candidates (e.g. BULLISH conf=72, sources=2) being
     *  hard-blocked by RC_PENDING with no override, despite the upstream
     *  scanner already passing them on multi-source confidence. 75 was
     *  picked for paper mode where we have no cost to rejecting; in live
     *  the operator can't afford to reject 70-74 conf tokens at age=0min
     *  because by the time RC scores them (10-30s), entry alpha is gone.
     *  The override still applies the +15 entry penalty (downsizes the
     *  trade) and the FDG still has final veto — this only widens the
     *  band of "trust upstream confidence" by 5 points. */
    const val LIVE_OVERRIDE_SCORE_MIN: Int = 70

    fun evaluate(
        rcConfirmedSafe: Boolean,
        rcConfirmedRisky: Boolean,
        rcPending: Boolean,
        isPaperMode: Boolean,
        score: Int,
    ): State {
        if (rcConfirmedRisky) return State.RC_CONFIRMED_RISKY
        if (rcConfirmedSafe)  return State.RC_CONFIRMED_SAFE
        if (!rcPending)       return State.RC_CONFIRMED_SAFE     // nothing pending = treat as ok

        if (isPaperMode) return State.RC_PENDING_ALLOWED_PAPER
        if (score >= LIVE_OVERRIDE_SCORE_MIN) return State.RC_PENDING_ALLOWED_LIVE_OVERRIDE
        return State.RC_PENDING_BLOCKED
    }

    /** Convenience: penalty to apply (or null when entry should be
     *  blocked outright). Live override path applies a small penalty
     *  to size; paper allow path applies none (learning). */
    fun penaltyOrBlock(state: State): Int? = when (state) {
        State.RC_CONFIRMED_RISKY               -> null   // block
        State.RC_PENDING_BLOCKED               -> null   // block
        State.RC_CONFIRMED_SAFE                -> 0
        State.RC_PENDING_ALLOWED_PAPER         -> 5
        State.RC_PENDING_ALLOWED_LIVE_OVERRIDE -> 15
    }
}
