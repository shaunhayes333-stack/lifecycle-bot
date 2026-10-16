package com.lifecyclebot.engine

/** USD marks never determine the sign of a realized SOL return. Unknown basis stays unknown. */
object RealizedReturn8163 {
    fun percent(pnlSol: Double, sliceCostSol: Double, entryCostSol: Double, storedPct: Double): Double? {
        if (!pnlSol.isFinite()) return null
        if (sliceCostSol.isFinite() && sliceCostSol > 0.0) return pnlSol / sliceCostSol * 100.0
        if (!entryCostSol.isFinite() || entryCostSol <= 0.0) return null
        val wholePct = pnlSol / entryCostSol * 100.0
        // Legacy partials can lack slice cost: retain a plausible same-sign slice return.
        if (storedPct.isFinite() && pnlSol != 0.0 && (storedPct > 0.0) == (pnlSol > 0.0) &&
            kotlin.math.abs(storedPct) >= kotlin.math.abs(wholePct)) return storedPct
        return wholePct
    }
}
