package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade

/**
 * V5.0.6324 — LEARNING ELIGIBILITY CLASSIFIER (operator hotfix §12).
 *
 * The canonical governor may only train on trades that are live,
 * confirmed / finalised, reconciled, quantity-valid, cost-valid,
 * not duplicated, not accounting-quarantined, not pre-hotfix
 * corrupted, not broadcast-only, not synthetic paper, not unresolved
 * wallet recovery, not decimal-skewed, not marked ineligible.
 *
 * Every classifier decision carries a reason so the health snapshot
 * can enumerate exclusions.
 */
object LearningEligibility {

    enum class Eligibility {
        ELIGIBLE,
        PENDING_FINALITY,
        PENDING_RECONCILIATION,
        QUARANTINED_DECIMAL,
        QUARANTINED_DUPLICATE,
        QUARANTINED_ACCOUNTING,
        EXCLUDED_PRE_HOTFIX,
        EXCLUDED_BROADCAST_ONLY,
        EXCLUDED_EXTERNAL_UNRESOLVED,
    }

    data class Classification(val eligibility: Eligibility, val reason: String)

    /**
     * Classify a Trade row for canonical governor / learning intake.
     * The `windowStartMs` is the session-scoped WADDLE-era cutoff
     * (see LiveEntrySafetyHold.governorWindowStart). Rows before it
     * are permanently excluded unless a forensic migration validates
     * them.
     */
    fun classify(t: Trade, windowStartMs: Long): Classification {
        val proof = t.proofState
        val side = t.side.uppercase()
        if (!(side == "SELL" || side == "PARTIAL_SELL")) {
            return Classification(Eligibility.PENDING_FINALITY, "NOT_A_SELL_ROW")
        }
        if (!t.mode.equals("live", true)) {
            return Classification(Eligibility.EXCLUDED_EXTERNAL_UNRESOLVED, "PAPER_OR_SYNTHETIC")
        }
        if (proof.equals("LIVE_BROADCAST", true)) {
            return Classification(Eligibility.EXCLUDED_BROADCAST_ONLY, "PROOF=LIVE_BROADCAST")
        }
        if (!(proof.equals("LIVE_FINALIZED", true) || proof.equals("LIVE_RECONCILED", true))) {
            return Classification(Eligibility.PENDING_FINALITY, "PROOF=$proof")
        }
        if (t.ts in 1L..(windowStartMs - 1L)) {
            return Classification(Eligibility.EXCLUDED_PRE_HOTFIX, "TS_BEFORE_HOTFIX_WINDOW")
        }
        if (t.reason.startsWith("FALLBACK_AFTER_FAILED_PROFIT_EXIT_6312", true)) {
            return Classification(Eligibility.EXCLUDED_PRE_HOTFIX, "EXIT_REASON_INVARIANT_MARKER")
        }
        if (t.reason.contains("QUARANTINED_LEGACY", true)) {
            return Classification(Eligibility.QUARANTINED_ACCOUNTING, "QUARANTINED_LEGACY")
        }
        // Sold-quantity vs remaining-quantity sanity — signals a decimal skew.
        if (t.entryQtyToken > 0.0 && t.soldQtyToken > t.entryQtyToken * 1.01) {
            return Classification(Eligibility.QUARANTINED_DECIMAL, "SOLD_EXCEEDS_ENTRY_QTY")
        }
        return Classification(Eligibility.ELIGIBLE, "OK")
    }
}
