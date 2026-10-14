package com.lifecyclebot.engine.sell

/**
 * V5.0.8094 §NO_MICROSCOPIC_PROFIT_SLICES.
 *
 * 5.0.8093 CfU5fA sold three live slices: partial_60pct (+0.0033 SOL), partial_69pct (+0.00042 SOL), then the rest
 * as a scratch. The small slice paid an exit transaction (priority fee, venue fee, slippage) to register a headline
 * profit that its own costs mostly consumed. Before a DISCRETIONARY live profit slice is sent, its expected
 * incremental net — proceeds after the exit leg's costs, minus the cost basis attributable to the slice — must be
 * positive and at least the costs it pays; otherwise the slice is skipped (the inventory stays for a larger rung,
 * the runner manager or the stop). Stops, catastrophic / rug / structural exits and full exits never wait on this.
 */
object PartialNetEconomics8094 {
    /** Pure: is [reason] at [fractionOfRemaining] a discretionary profit slice (not a full / protective / structural exit)? */
    fun discretionary8094(reason: String, fractionOfRemaining: Double): Boolean {
        if (!fractionOfRemaining.isFinite() || fractionOfRemaining <= 0.0 || fractionOfRemaining >= 0.999) return false
        // V5.0.8094 — the moonbag set-aside REPLACES a full exit (sell 75%, keep 25%): it costs no more than the exit it
        // replaces, so it is never skipped as uneconomic (8094: refused -> fell through to a full exit, setAside=0).
        if (reason.startsWith("TAIL_SET_ASIDE_8034")) return false
        val structural = try { com.lifecyclebot.engine.truth.NetEdge8030.structural8030(reason) } catch (_: Throwable) { true }
        val emergency = try { ProtectiveExitClass7807.isEmergency(reason) } catch (_: Throwable) { true }
        val rug = try { SellSafetyPolicy.isHardRug(reason) || SellSafetyPolicy.isEmergencyExit(reason) } catch (_: Throwable) { true }
        return !structural && !emergency && !rug
    }

    /** Pure: the slice's expected net SOL after the exit leg's costs ([exitCostPct] of proceeds) and its cost basis. */
    fun sliceNetSol8094(proceedsSol: Double, sliceCostBasisSol: Double, exitCostPct: Double): Double =
        proceedsSol * (1.0 - exitCostPct.coerceIn(0.0, 100.0) / 100.0) - sliceCostBasisSol

    /**
     * Pure: a PROFIT slice (gross proceeds above its cost basis) is uneconomic when it nets nothing, or nets less than
     * the costs it pays to be sold. A slice at a gross loss is a de-risking sale, not a profit slice: never held here.
     */
    fun uneconomic8094(proceedsSol: Double, sliceCostBasisSol: Double, exitCostPct: Double): Boolean {
        if (!proceedsSol.isFinite() || proceedsSol <= 0.0 || !sliceCostBasisSol.isFinite() || sliceCostBasisSol < 0.0) return false
        if (proceedsSol <= sliceCostBasisSol) return false
        val costs = proceedsSol * exitCostPct.coerceIn(0.0, 100.0) / 100.0
        val net = sliceNetSol8094(proceedsSol, sliceCostBasisSol, exitCostPct)
        return net <= 0.0 || net < costs
    }
}
