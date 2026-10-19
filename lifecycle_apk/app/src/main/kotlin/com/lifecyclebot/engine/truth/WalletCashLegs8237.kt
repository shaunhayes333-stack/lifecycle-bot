package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.Trade
import kotlin.math.abs

/** Cash movements keep their direction. A completed buy+sell cannot net away the sell's later wallet credit. */
object WalletCashLegs8237 {
    data class Leg8237(val signature: String, val side: String, val cashSol: Double)
    data class Evidence8237(val legs: List<Leg8237>, val unresolvedLegs: Int) {
        val grossInSol: Double get() = legs.filter { it.side == "SELL" }.sumOf { it.cashSol }
        val grossOutSol: Double get() = legs.filter { it.side == "BUY" }.sumOf { it.cashSol }
    }
    enum class Flow8237 { NO_FLOW, POSSIBLE_TRADE_SETTLEMENT, MIXED_TRADE_FLOW_UNRESOLVED, EXTERNAL_FLOW_REQUIRES_PROOF }

    /** Only finalized signed live rows supply amounts. Missing proof remains unresolved, never a zero cash movement. */
    fun evidence8237(trades: List<Trade>, fromMs: Long, toMs: Long): Evidence8237 {
        val relevant = trades.filter {
            it.mode.equals("live", true) && it.ts in fromMs..toMs &&
                (it.side.equals("BUY", true) || it.side.equals("SELL", true) || it.side.equals("PARTIAL_SELL", true))
        }
        val legs = ArrayList<Leg8237>()
        var unresolved = relevant.count { it.sig.isBlank() }
        relevant.filter { it.sig.isNotBlank() }.groupBy {
            it.sig to if (it.side.equals("BUY", true)) "BUY" else "SELL"
        }.forEach { (key, rows) ->
            val proved = rows.filter {
                it.proofState.equals("LIVE_FINALIZED", true) || it.proofState.equals("LIVE_RECONCILED", true)
            }
            val amounts = proved.mapNotNull { cashAmount8237(it) }
            // Contradictory duplicate projections are not independent cash legs.
            if (amounts.isEmpty() || amounts.size != proved.size || amounts.any { abs(it - amounts.first()) > 1e-9 }) unresolved++
            else legs += Leg8237(key.first, key.second, amounts.first())
        }
        return Evidence8237(legs, unresolved)
    }

    private fun cashAmount8237(t: Trade): Double? {
        val amount = when {
            t.side.equals("BUY", true) -> t.sol
            t.grossProceedsSol.isFinite() && t.grossProceedsSol > 0.0 -> t.grossProceedsSol
            // The live terminal SELL contract carries proceeds in sol. PARTIAL_SELL.sol carries slice COST.
            t.side.equals("SELL", true) -> t.sol
            else -> return null
        }
        return amount.takeIf { it.isFinite() && it > 0.0 }
    }

    /** This does not approve a deposit, move a peak or alter realized P&L. External proof belongs to the caller. */
    fun classify8237(flowSol: Double, evidence: Evidence8237, toleranceSol: Double = 0.002): Flow8237 {
        if (!flowSol.isFinite() || flowSol == 0.0) return Flow8237.NO_FLOW
        val sameDirection = if (flowSol > 0.0) evidence.grossInSol else evidence.grossOutSol
        if (sameDirection > 0.0 && abs(flowSol) <= sameDirection + toleranceSol.coerceAtLeast(0.0)) {
            return Flow8237.POSSIBLE_TRADE_SETTLEMENT
        }
        if (evidence.unresolvedLegs > 0 || sameDirection > 0.0) return Flow8237.MIXED_TRADE_FLOW_UNRESOLVED
        return Flow8237.EXTERNAL_FLOW_REQUIRES_PROOF
    }
}
