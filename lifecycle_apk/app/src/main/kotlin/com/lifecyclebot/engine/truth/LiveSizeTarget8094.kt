package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8094 §ONE_CANDIDATE_SPECIFIC_LIVE_SIZE.
 *
 * 5.0.8093 sealed every live order at 0.029-0.033 SOL. Three things collapsed an approved size:
 *  1. one flat 18% wallet-share cap (BotConfig.maxWalletRiskPerTradePct) at FDG and again at the executor, whatever
 *     the evidence — the "25% ALPHA" tier of LiveSizingProfile could never come out;
 *  2. FDG asked for a proven-edge size, then took min(proposal, policy size), so a policy-approved compounding size
 *     above the original proposal was reverted to it;
 *  3. the order resolver re-applied the adaptive / streak / bridge multipliers to a size LiveRiskPolicy7807 had
 *     already shrunk for drawdown, governor and expectancy, pushing it under the route minimum, where it was
 *     "promoted" back to the minimum.
 *
 * Now: the candidate's proof tier sets ONE wallet-share ceiling (baseline 18%; up to 25% only for a PROVEN
 * positive-net-edge setup / cell / kind), recorded at the FDG seal and read back by the executor so both enforce the
 * same number. LiveRiskPolicy7807 sizes a proven candidate at its drawdown-damped quarter-Kelly share inside the lane
 * cap, pool-depth cap and hard loss at the stop; the ceiling here only bounds it. Unproven candidates are unchanged.
 */
object LiveSizeTarget8094 {
    /** The most a PROVEN positive-net-edge candidate may take of live wallet cash. */
    const val PROVEN_WALLET_SHARE_8094 = 0.25
    private const val CEILING_TTL_MS = 10L * 60_000L

    enum class ProofTier { PROVEN, UNPROVEN }

    /** Pure: the proof tier from the candidate's proven quarter-Kelly share (setup, cell or kind; 0 = none). */
    fun tierOf8094(edgeKellyFrac: Double): ProofTier =
        if (edgeKellyFrac.isFinite() && edgeKellyFrac > 0.0) ProofTier.PROVEN else ProofTier.UNPROVEN

    /** Pure: the wallet-share ceiling for [tier]; the configured baseline is never lowered and never raised for UNPROVEN. */
    fun walletShareCeiling8094(tier: ProofTier, configuredPct: Double): Double {
        val base = if (configuredPct.isFinite()) configuredPct.coerceIn(0.0, 1.0) else 0.0
        return if (tier == ProofTier.PROVEN) maxOf(base, PROVEN_WALLET_SHARE_8094) else base
    }

    /**
     * Pure: the ceiling LiveRiskPolicy7807 clamps to. An ordinary request ([compoundingCeilingSol] 0 or no Kelly size)
     * is clamped to the upstream proposal, as before. An approved proven target may exceed the proposal up to its own
     * Kelly size, never above the external ceiling (depth, curve, candidate wallet share).
     */
    fun upstreamCeiling8094(upstreamSol: Double, kellySol: Double, compoundingCeilingSol: Double): Double =
        if (compoundingCeilingSol > 0.0 && kellySol > 0.0) maxOf(upstreamSol, minOf(kellySol, compoundingCeilingSol)) else upstreamSol

    /**
     * Pure: the FDG's risk-sized amount after the policy decision. An approved compounding size is the decision (it
     * already sits under every policy cap); otherwise the decision can only lower the proposal (or lift it to a
     * risk-safe route minimum, 7841).
     */
    fun fdgRiskSized8094(proposalSol: Double, decisionSol: Double, decisionReason: String, compoundingApproved: Boolean): Double = when {
        compoundingApproved && decisionSol > 0.0 -> decisionSol
        decisionReason in setOf("OPEN_RISK_SAFE_MIN_PROMOTED_7840", "OPEN_RISK_SAFE_COST_FLOOR_PROMOTED_8269") &&
            decisionSol > proposalSol -> decisionSol
        else -> minOf(proposalSol, decisionSol)
    }

    /**
     * Pure: the FIRST constraint that sets a proven candidate's size — the smallest of: the undamped Kelly target
     * (KELLY = nothing clipped), the drawdown / daily-loss / governor damped target, the lane position cap, the
     * pool-depth cap, the hard loss at the stop, and the candidate wallet-share ceiling.
     */
    fun firstBindingCap8094(rawKellySol: Double, dampMult: Double, drawdownMult: Double, laneCapSol: Double,
                            liquidityCapSol: Double, hardLossCapSol: Double, walletCeilingSol: Double): String {
        val damped = rawKellySol * dampMult.coerceIn(0.0, 1.0)
        val dampName = if (drawdownMult < 1.0 - 1e-9) "DRAWDOWN" else "RISK"
        val caps = listOf(
            "KELLY" to rawKellySol,
            dampName to (if (dampMult < 1.0 - 1e-9) damped else Double.POSITIVE_INFINITY),
            "LANE" to laneCapSol,
            "LIQUIDITY" to liquidityCapSol,
            "RISK" to hardLossCapSol,
            "WALLET" to (if (walletCeilingSol > 0.0) walletCeilingSol else Double.POSITIVE_INFINITY),
        ).filter { it.second.isFinite() && it.second >= 0.0 }
        return caps.minByOrNull { it.second }?.first ?: "KELLY"
    }

    // ── the one ceiling, shared by FDG and the executor ──

    /** The ceiling a sealed ticket was approved under: bound to the exact sealed notional (lamports) of that intent. */
    private data class Sealed8094(val share: Double, val sealedLamports: Long, val atMs: Long)
    private val ceilings = ConcurrentHashMap<String, Sealed8094>()

    private fun lamports(sol: Double): Long = if (sol.isFinite() && sol > 0.0) kotlin.math.round(sol * 1_000_000_000.0).toLong() else 0L

    /** FDG: the ceiling this candidate's sealed ticket ([sealedSol]) was approved under. */
    fun recordCeiling8094(mint: String, walletSharePct: Double, sealedSol: Double, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank()) return
        ceilings[mint] = Sealed8094(walletSharePct, lamports(sealedSol), nowMs)
        if (ceilings.size > 2_000) ceilings.entries.removeIf { nowMs - it.value.atMs > CEILING_TTL_MS }
    }

    /**
     * Executor: the wallet share THIS sealed ticket was approved under — only when [ticketSol] is the exact notional
     * sealed for this mint (another candidate on the mint, or an unsealed size, gets the configured baseline).
     */
    fun walletShareFor8094(mint: String, ticketSol: Double, configuredPct: Double, nowMs: Long = System.currentTimeMillis()): Double {
        val c = ceilings[mint]?.takeIf { nowMs - it.atMs in 0L..CEILING_TTL_MS } ?: return configuredPct
        if (c.sealedLamports <= 0L || kotlin.math.abs(c.sealedLamports - lamports(ticketSol)) > 1L) return configuredPct
        return if (c.share.isFinite() && c.share > 0.0) maxOf(c.share, configuredPct) else configuredPct
    }

    // ── diagnostics: every FDG-authorised live candidate ──

    data class Row(
        val mint: String, val lane: String, val strategy: String, val walletCash: Double, val markedEquity: Double,
        val requestedSol: Double, val approvedKellySol: Double, val riskBudgetSol: Double, val laneCapSol: Double,
        val walletShareCapSol: Double, val liquidityCapSol: Double, val drawdownMultiplier: Double,
        val estimatedRoundTripCostSol: Double, val routableMinSol: Double, val finalSealedSol: Double,
        val bindingCap: String, val estimatedLossAtStopSol: Double, val proofTier: String, val atMs: Long,
    )

    private val rows = ConcurrentLinkedDeque<Row>()
    private val counters = ConcurrentHashMap<String, AtomicLong>()

    private fun inc(k: String) {
        counters.computeIfAbsent(k) { AtomicLong(0) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc(k) } catch (_: Throwable) {}
    }

    /** FDG, once per sealed live candidate: the full sizing row and the first binding cap. */
    fun recordSealed8094(r: Row) {
        rows.addLast(r)
        while (rows.size > 40) rows.pollFirst()
        if (r.proofTier == ProofTier.PROVEN.name) {
            if (r.finalSealedSol > r.routableMinSol * 1.05 + 1e-9) inc("PROVEN_EDGE_LARGER_SIZE_APPLIED")
            when (r.bindingCap) {
                "WALLET" -> inc("PROVEN_EDGE_SIZE_CLIPPED_WALLET")
                "LANE" -> inc("PROVEN_EDGE_SIZE_CLIPPED_LANE")
                "DRAWDOWN" -> inc("PROVEN_EDGE_SIZE_CLIPPED_DRAWDOWN")
                "LIQUIDITY" -> inc("PROVEN_EDGE_SIZE_CLIPPED_LIQUIDITY")
                "RISK" -> inc("PROVEN_EDGE_SIZE_CLIPPED_RISK")
            }
        }
        try {
            com.lifecyclebot.engine.ForensicLogger.lifecycle("LIVE_SIZE_SEALED_8094",
                "mint=${r.mint.take(10)} lane=${r.lane} strategy=${r.strategy} walletCash=${f(r.walletCash)} markedEquity=${f(r.markedEquity)} " +
                    "requestedSol=${f(r.requestedSol)} approvedKellySol=${f(r.approvedKellySol)} riskBudgetSol=${f(r.riskBudgetSol)} " +
                    "laneCapSol=${f(r.laneCapSol)} walletShareCapSol=${f(r.walletShareCapSol)} liquidityCapSol=${f(r.liquidityCapSol)} " +
                    "drawdownMultiplier=${"%.3f".format(r.drawdownMultiplier)} estimatedRoundTripCostSol=${f(r.estimatedRoundTripCostSol)} " +
                    "routableMinSol=${f(r.routableMinSol)} finalSealedSol=${f(r.finalSealedSol)} bindingCap=${r.bindingCap} " +
                    "estimatedLossAtStopSol=${f(r.estimatedLossAtStopSol)} proofTier=${r.proofTier}")
        } catch (_: Throwable) {}
    }

    /** Partial exits: a discretionary profit slice whose net is consumed by its own costs (PARTIAL_EXIT_NET_UNECONOMIC). */
    fun notePartialUneconomic8094() = inc("PARTIAL_EXIT_NET_UNECONOMIC")

    private fun f(v: Double) = if (v.isFinite()) "%.5f".format(v) else "-"

    fun statusLine(): String {
        val c = counters.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${it.value.get()}" }.ifBlank { "no sealed live sizes yet" }
        val last = rows.toList().takeLast(4).joinToString(" · ") {
            "${it.mint.take(6)}:${it.lane}:${it.proofTier}:final=${f(it.finalSealedSol)}/min=${f(it.routableMinSol)}:bind=${it.bindingCap}"
        }.ifBlank { "-" }
        return "LIVE_SIZE_TARGET_8094 provenShare=${(PROVEN_WALLET_SHARE_8094 * 100).toInt()}% $c recent=[$last]"
    }
}
