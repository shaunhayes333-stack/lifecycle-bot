package com.lifecyclebot.engine

/** Accounting arithmetic only. A milestone is achieved equity, never a guaranteed banked floor. */
internal object CyclicCompound9061 {
    const val DEFAULT_SEED_USD = 5.0
    data class Milestone(val reachedUsd: Double, val tier: Int, val nextUsd: Double?)

    fun validBalances(sol: Double, usd: Double): Boolean =
        sol.isFinite() && usd.isFinite() && sol >= 0.0 && usd >= 0.0

    fun fitsAllocation(size:Double,allocation:Double):Boolean = size.isFinite() && allocation.isFinite() &&
        size>0.0 && allocation>0.0 && size<=allocation

    /** Recover the original seed from the old doubling ledger without changing its balance. */
    fun legacySeed(floor: Double, tier: Int): Double? {
        if (!floor.isFinite() || floor <= 0.0 || tier !in 0..1023) return null
        return Math.scalb(floor, -tier).takeIf { it.isFinite() && it > 0.0 }
    }

    fun milestones(seed: Double, priorFloor: Double, priorTier: Int, equity: Double): Milestone? {
        if (!seed.isFinite() || seed <= 0.0 || !equity.isFinite() || equity < 0.0 ||
            !priorFloor.isFinite() || priorFloor < seed || priorTier !in 0..1023) return null
        val expected = Math.scalb(seed, priorTier)
        if (!expected.isFinite() || kotlin.math.abs(expected - priorFloor) > expected * 1e-6) return null
        var floor = priorFloor
        var tier = priorTier
        while (tier < 1023 && floor <= Double.MAX_VALUE / 2.0 && equity >= floor * 2.0) {
            floor *= 2.0
            tier++
        }
        return Milestone(floor, tier, (floor * 2.0).takeIf { it.isFinite() })
    }

    /** Input must be the canonical whole-position net receipt, including partials and every fee. */
    fun afterSettlement(balanceSol: Double, netSol: Double): Double? {
        if (!balanceSol.isFinite() || balanceSol < 0.0 || !netSol.isFinite()) return null
        return (balanceSol + netSol).takeIf { it.isFinite() }
    }
}
