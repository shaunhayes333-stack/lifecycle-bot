package com.lifecyclebot.engine

import java.math.BigInteger

/** Price the tokens the order may actually deliver, not the headline quote's unadjusted output.
 * Buy pool/route fees and impact are already inside that price. Only the external buy charge and
 * the sell leg remain to be paid; charging the full round trip again double counts the buy.
 * These are deterministic execution economics, not a claim that a predicted target will occur.
 */
object ExecutableEntryFloor8521 {
    const val MAX_MARK_AGE_MS = 20_000L

    fun fresh8521(observedAtMs: Long, nowMs: Long): Boolean =
        observedAtMs > 0L && nowMs - observedAtMs in -5_000L..MAX_MARK_AGE_MS

    /** Exact-in output-floor arithmetic for a supplied output basis. A builder bps overrides quote
     * bps, but a dynamic response may also change output: live callers must then use dynamicBound8521.
     * Integer arithmetic prevents raw-supply overflow and rounding the calculated floor upward.
     */
    fun minimumOut8521(outRaw: Long, quotedMinimumRaw: Long?, quoteSlippageBps: Int?, builtSlippageBps: Int?): Long? {
        if (outRaw <= 0L) return null
        if (builtSlippageBps == null && quotedMinimumRaw != null)
            return quotedMinimumRaw.takeIf { it in 1L..outRaw }
        val bps = builtSlippageBps ?: quoteSlippageBps ?: return null
        if (bps !in 0..9_999) return null
        return bps.let {
            BigInteger.valueOf(outRaw).multiply(BigInteger.valueOf((10_000 - it).toLong()))
                .divide(BigInteger.valueOf(10_000L)).toLong()
        }.takeIf { it > 0L }
    }

    /** The dynamic-slippage response's otherAmount is a simulation amount, NOT a decoded minOut.
     * Until the binary instruction is decoded, never improve the original floor on a tighter bps
     * report alone. Retain the lower quote floor and a bound based on the worse observed output.
     * The caller names this CONSERVATIVE_BOUND rather than claiming a guaranteed minimum fill.
     */
    fun dynamicBound8521(outRaw: Long, quotedMinimumRaw: Long?, quoteSlippageBps: Int?,
                         builtSlippageBps: Int, simulatedOutRaw: Long?): Long? {
        if (simulatedOutRaw != null && simulatedOutRaw <= 0L) return null
        val quoted = minimumOut8521(outRaw, quotedMinimumRaw, quoteSlippageBps, null)
        val basis = minOf(outRaw, simulatedOutRaw ?: outRaw)
        val built = minimumOut8521(basis, null, null, builtSlippageBps) ?: return null
        return if (quoted == null) built else minOf(quoted, built)
    }

    /** Expected NET return on all SOL committed, at the actual executable buy price.
     * A quoted target at decisionPx*(1+expectedGross) stays anchored to the original decision.
     * Variable exit cost is charged on proceeds, fixed costs in SOL, once on each side.
     */
    fun netPct8521(decisionPx: Double, executablePx: Double, expectedGrossPct: Double, sizeSol: Double,
                   sellVariablePct: Double, sellFixedSol: Double, buyExternalPct: Double, buyFixedSol: Double): Double? {
        if (listOf(decisionPx, executablePx, expectedGrossPct, sizeSol, sellVariablePct, sellFixedSol, buyExternalPct, buyFixedSol)
                .any { !it.isFinite() } || decisionPx <= 0.0 || executablePx <= 0.0 || sizeSol <= 0.0 ||
            expectedGrossPct <= -100.0 || sellVariablePct !in 0.0..99.999 || sellFixedSol < 0.0 ||
            buyExternalPct < 0.0 || buyFixedSol < 0.0) return null
        val targetRatio = decisionPx / executablePx * (1.0 + expectedGrossPct / 100.0)
        val received = sizeSol * targetRatio * (1.0 - sellVariablePct / 100.0) - sellFixedSol
        val paid = sizeSol * (1.0 + buyExternalPct / 100.0) + buyFixedSol
        return ((received / paid - 1.0) * 100.0).takeIf { it.isFinite() }
    }

    /** Entry and protective-exit tips are asymmetric. Replacing the buy estimate with the built
     * transaction's charge must retain the whole estimated sell leg, not half the old round trip.
     */
    fun netWithFixedLegs8521(decisionPx: Double, executablePx: Double, expectedGrossPct: Double,
                            sizeSol: Double, sellVariablePct: Double, roundTripFixedSol: Double,
                            sellFixedSol: Double, buyExternalPct: Double, plannedBuyFixedSol: Double): Double? {
        if (listOf(roundTripFixedSol, sellFixedSol, plannedBuyFixedSol).any { !it.isFinite() || it < 0.0 } ||
            sellFixedSol > roundTripFixedSol) return null
        val modeledBuyFixed = roundTripFixedSol - sellFixedSol
        return netPct8521(decisionPx, executablePx, expectedGrossPct, sizeSol, sellVariablePct,
            sellFixedSol, buyExternalPct, maxOf(modeledBuyFixed, plannedBuyFixedSol))
    }

    /** Constant-product average buy price from OBSERVED virtual reserves, including venue/route
     * fees and the requested adverse-fill envelope. This is a reserve model, not a decoded transaction
     * minimum or a promised fill. Real SOL still belongs to the sellability gate.
     */
    fun curvePrice8521(virtualSol: Double, virtualTokens: Double, sizeSol: Double, solUsd: Double,
                      buyFeePct: Double, slippagePct: Double): Double? {
        if (listOf(virtualSol, virtualTokens, sizeSol, solUsd, buyFeePct, slippagePct).any { !it.isFinite() } ||
            virtualSol <= 0.0 || virtualTokens <= 0.0 || sizeSol <= 0.0 || solUsd <= 0.0 ||
            buyFeePct !in 0.0..99.999 || slippagePct !in 0.0..99.999) return null
        val poolIn = sizeSol * (1.0 - buyFeePct / 100.0)
        val output = virtualTokens * poolIn / (virtualSol + poolIn) * (1.0 - slippagePct / 100.0)
        return (sizeSol * solUsd / output).takeIf { it.isFinite() && it > 0.0 }
    }
}
