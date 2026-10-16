package com.lifecyclebot.engine.truth

/** V5.0.8170 — paper marks may never create spendable or reported equity from
 * zero/unfunded position cost. Historical unproven positions remain in inventory
 * for independent audit; this pure guard changes VALUATION, never records/holds.
 */
object PaperFundingGuard8170 {
    fun hasFundedBasis8170(ledgerOpenCostSol: Double, positionRemainingCostSol: Double): Boolean =
        ledgerOpenCostSol.isFinite() && ledgerOpenCostSol > 0.0 &&
            positionRemainingCostSol.isFinite() && positionRemainingCostSol > 0.0
}
