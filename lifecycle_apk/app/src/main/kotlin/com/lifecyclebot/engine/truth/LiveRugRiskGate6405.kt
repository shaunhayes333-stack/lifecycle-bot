package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector

/**
 * V5.0.6405 §17 — LIVE RUG RISK HARD GATE.
 *
 * OPERATOR DIRECTIVE (07 Feb 2026)
 * ────────────────────────────────
 * "paper is capable of finding quality trades, runners and has run as
 *  high as 90% winrate at times. live trading needs to be just as
 *  effective". Snapshot showed WR=36.7%/PF=0.87/exp=-0.0002SOL on 30
 *  canonical trades with 10× TICK_CATASTROPHIC_CONFIRMED_-91PCT and
 *  30× RAPID_CATASTROPHE_STOP — bot is buying rugs the paper lane
 *  never has to pay for.
 *
 * PROBLEM
 * ───────
 * ImmediateCollapseGuard was being called with hard-coded optimistic
 * defaults for every real signal (lpBurned=true, lpLocked=true,
 * rapidSellAcceleration=false, recentLiquidityChangePct=0.0 etc), so
 * the only hard-block path was mintOrFreezeAuthorityLive. Meanwhile
 * the brain was screaming BRAIN_RUGCHECK_FLOOR (172 events in 27min)
 * and PROVIDER_PROOF_HOLDER_CASCADE_BLIND (149 events) but those were
 * treated as soft-shapes (size × 0.65) not vetoes. Live kept firing
 * on obvious rugs while paper (which pays no rug tax) achieved 90% WR.
 *
 * FIX (root-cause, no bandaid)
 * ────────────────────────────
 * New hard gate applied to LIVE buys ONLY (paper keeps its exploratory
 * behaviour so the tactic switcher can learn). Multi-signal red-flag
 * scoring; ≥ RED_FLAG_HARD_BLOCK_SCORE points → BLOCK.
 *
 * Red flag                            | points
 * ------------------------------------|-------
 * BRAIN_RUGCHECK_FLOOR advisor label  |   2
 * PROVIDER_PROOF_HOLDER_CASCADE_BLIND |   2
 * topHolderConcentrationPct >= 50     |   2
 * liquidityUsd < 3 000                |   2
 * lastSellPressurePct >= 65           |   1
 * MOMENTUM_AVOID advisor label        |   1
 * mint OR freeze authority live       |   3 (single-source hard block)
 *
 * Any single-source-hard-block flag OR total >= 3 blocks the buy.
 * Emits `LIVE_RUG_RISK_HARD_BLOCK_6405` forensic with the exact score
 * and triggered flags so operators can grep the reason.
 */
object LiveRugRiskGate6405 {

    private const val RED_FLAG_HARD_BLOCK_SCORE = 3

    data class Signals(
        val advisorLabels: Collection<String>,
        val topHolderConcentrationPct: Double?,
        val liquidityUsd: Double?,
        val lastSellPressurePct: Double,
        val mintAuthorityLive: Boolean,
        val freezeAuthorityLive: Boolean,
        val isPaper: Boolean,
    )

    data class Verdict(
        val block: Boolean,
        val score: Int,
        val flags: List<String>,
    )

    fun evaluate(mint: String, symbol: String, lane: String, s: Signals): Verdict {
        // Paper never blocks — it needs exposure to learn.
        if (s.isPaper) return Verdict(false, 0, emptyList())

        val flags = mutableListOf<String>()
        var score = 0

        if (s.mintAuthorityLive || s.freezeAuthorityLive) {
            score += 3
            flags += "MINT_OR_FREEZE_AUTHORITY_LIVE"
        }

        val labels = s.advisorLabels.map { it.uppercase() }
        if (labels.any { it.contains("BRAIN_RUGCHECK_FLOOR") }) {
            score += 2
            flags += "BRAIN_RUGCHECK_FLOOR"
        }
        if (labels.any { it.contains("PROVIDER_PROOF_HOLDER_CASCADE_BLIND") }) {
            score += 2
            flags += "PROVIDER_PROOF_HOLDER_CASCADE_BLIND"
        }
        if (labels.any { it.contains("MOMENTUM_AVOID") }) {
            score += 1
            flags += "MOMENTUM_AVOID"
        }

        val topHolder = s.topHolderConcentrationPct ?: 0.0
        if (topHolder >= 50.0) {
            score += 2
            flags += "TOP_HOLDER_${topHolder.toInt()}PCT"
        } else if (topHolder >= 40.0) {
            score += 1
            flags += "TOP_HOLDER_${topHolder.toInt()}PCT"
        }

        val liq = s.liquidityUsd ?: 0.0
        if (liq in 0.01..2_999.99) {
            score += 2
            flags += "LIQUIDITY_$${liq.toInt()}"
        } else if (liq in 3_000.0..5_999.99) {
            score += 1
            flags += "LIQUIDITY_$${liq.toInt()}"
        }

        if (s.lastSellPressurePct >= 65.0) {
            score += 1
            flags += "SELL_PRESSURE_${s.lastSellPressurePct.toInt()}PCT"
        }

        val block = score >= RED_FLAG_HARD_BLOCK_SCORE
        if (block) {
            try {
                ForensicLogger.lifecycle(
                    "LIVE_RUG_RISK_HARD_BLOCK_6405",
                    "mint=${mint.take(10)} sym=$symbol lane=$lane score=$score " +
                        "threshold=$RED_FLAG_HARD_BLOCK_SCORE flags=${flags.joinToString(",")}",
                )
                PipelineHealthCollector.labelInc("LIVE_RUG_RISK_HARD_BLOCK_6405")
            } catch (_: Throwable) {}
        }
        return Verdict(block, score, flags)
    }
}
