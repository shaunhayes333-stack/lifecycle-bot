package com.lifecyclebot.engine.truth

/** One completed position is one outcome, including every partial fill and fee. */
internal object WholePositionEconomics7835 {
    data class Outcome(val grossSol: Double, val netSol: Double, val returnPct: Double, val feesSol: Double)
    fun from(position: CanonicalPositionAuthority6441.Position): Outcome {
        require(position.lifecycle == CanonicalPositionAuthority6441.Lifecycle.CLOSED)
        require(position.remainingQtyRaw.signum() == 0)
        require(position.entryCostSol.isFinite() && position.entryCostSol > 0.0)
        require(position.realizedProceedsSol.isFinite() && position.feesSol.isFinite())
        val gross = position.realizedProceedsSol - position.entryCostSol
        val net = gross - position.feesSol
        return Outcome(gross, net, net / position.entryCostSol * 100.0, position.feesSol)
    }
}
