package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 🔄 LANE TRANSITION MANAGER — V5.0.4599
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Universal promotion/hand-off pathway for all traders + lanes + layers.
 * Operator directive 2026-07-02: "layer and trader promotion is meant to
 * be there for all lanes, layers and traders."
 *
 * This is a SINGLE decision brain that every open position consults each
 * cycle. Given the position's current owner + live token metrics + PnL
 * trajectory, it decides whether to:
 *   • KEEP with current owner (position matches lane design intent)
 *   • PROMOTE to a higher-tier lane (mcap graduated, big-win reached, etc.)
 *   • ROTATE to a specialist (regime change matches specialist mission)
 *   • EXIT (nothing owns this token cleanly)
 *
 * Rules encoded from documented lane design intent (docstrings):
 *
 *   PROJECT_SNIPER graduation:
 *     mcap > $500K  → BLUECHIP (or MOONSHOT/MARS for high-momentum)
 *     mcap > $100K  → MOONSHOT/LUNAR
 *     mcap > $50K   → MOONSHOT/ORBITAL
 *     mcap collapse → EXIT
 *
 *   ANY LANE +100%+ realized/unrealized:
 *     → MOONSHOT (per MoonshotTraderAI docstring: "positions from Treasury/
 *       ShitCoin/BlueChip hit +100%+, PROMOTED to Moonshot to ride wider")
 *
 *   ANY LANE +25%+ established mcap:
 *     → CashGen/Treasury banks the win + restart (compounding)
 *
 *   Mcap crossing thresholds mid-hold:
 *     STANDARD → QUALITY  at mcap > $100K
 *     QUALITY  → BLUECHIP at mcap > $500K
 *     Any lane paused    → transition to nearest non-paused lane by design
 *
 * Doctrine: FLUID. No rigid transitions. AGI signals + docstring intent
 * govern. Every transition emits a LANE_TRANSITION_* forensic event for
 * learning.
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object LaneTransitionManager {

    const val VERSION = "V5.0.4599_LANE_TRANSITION_MANAGER"

    enum class Decision { KEEP, PROMOTE, ROTATE, EXIT }

    data class TransitionDecision(
        val decision: Decision,
        val targetLane: String?,
        val reason: String,
    )

    private const val PROMOTION_PNL_MOONSHOT = 100.0        // +100% → MOONSHOT
    private const val PROMOTION_PNL_TREASURY_BANK = 25.0    // +25% → CashGen banks + restart
    private const val MCAP_BLUECHIP_FLOOR = 500_000.0
    private const val MCAP_QUALITY_FLOOR = 100_000.0
    private const val MCAP_MOONSHOT_ORBITAL = 50_000.0
    private const val MCAP_COLLAPSE_FLOOR = 5_000.0

    /**
     * Evaluate whether a position should transition ownership.
     * Called by the hold-logic once per cycle per open position.
     */
    fun evaluate(currentLane: String, ts: TokenState, pnlPct: Double): TransitionDecision {
        val lane = currentLane.uppercase()
        val mcap = ts.lastMcap

        // 1) Emergency exit for mcap collapse
        if (mcap in 1.0..MCAP_COLLAPSE_FLOOR) {
            return TransitionDecision(Decision.EXIT, null, "mcap_collapse_below_${MCAP_COLLAPSE_FLOOR.toInt()}")
        }

        // 2) Owner lane is paused → rotate to nearest non-paused by mcap band
        if (isLanePaused(lane)) {
            val target = pickLaneByMcap(mcap, ts)
            if (target != null && target != lane) {
                return TransitionDecision(Decision.ROTATE, target, "owner_lane_paused_rotate_by_mcap")
            }
        }

        // 3) MOONSHOT promotion on +100% (per docstring)
        if (pnlPct >= PROMOTION_PNL_MOONSHOT && lane != "MOONSHOT") {
            return TransitionDecision(Decision.PROMOTE, "MOONSHOT", "pnl_ge_${PROMOTION_PNL_MOONSHOT.toInt()}_pct_moonshot_promotion")
        }

        // 4) CashGen bank on +25% established (per docstring: compounding)
        //    Only for non-CashGen non-MOONSHOT positions on established mcap
        if (pnlPct >= PROMOTION_PNL_TREASURY_BANK && mcap >= MCAP_QUALITY_FLOOR
            && lane !in setOf("CASHGEN", "TREASURY", "MOONSHOT")) {
            return TransitionDecision(Decision.PROMOTE, "CASHGEN", "pnl_ge_${PROMOTION_PNL_TREASURY_BANK.toInt()}_pct_treasury_bank_compound")
        }

        // 5) PROJECT_SNIPER graduation handoff
        if (lane == "PROJECT_SNIPER") {
            val target = pickLaneByMcap(mcap, ts)
            if (target != null && target != lane) {
                return TransitionDecision(Decision.PROMOTE, target, "project_sniper_graduation_handoff_by_mcap")
            }
        }

        // 6) Mcap-band rotation (STANDARD → QUALITY → BLUECHIP)
        val bandTarget = when {
            lane == "STANDARD" && mcap >= MCAP_QUALITY_FLOOR -> "QUALITY"
            lane == "QUALITY" && mcap >= MCAP_BLUECHIP_FLOOR -> "BLUECHIP"
            else -> null
        }
        if (bandTarget != null && !isLanePaused(bandTarget)) {
            return TransitionDecision(Decision.PROMOTE, bandTarget, "mcap_band_rotation_to_$bandTarget")
        }

        return TransitionDecision(Decision.KEEP, null, "no_transition_criteria_met")
    }

    private fun isLanePaused(lane: String): Boolean = try {
        LaneAutoPauseGuard.isPaused(lane)
    } catch (_: Throwable) { false }

    /**
     * Pick the best lane for a token given its mcap. Respects paused lanes
     * (skips them via next-best fallback).
     */
    private fun pickLaneByMcap(mcap: Double, ts: TokenState): String? {
        val ordered = when {
            mcap >= MCAP_BLUECHIP_FLOOR -> listOf("BLUECHIP", "QUALITY", "MOONSHOT")
            mcap >= MCAP_QUALITY_FLOOR -> listOf("QUALITY", "MOONSHOT", "STANDARD")
            mcap >= MCAP_MOONSHOT_ORBITAL -> listOf("MOONSHOT", "STANDARD")
            else -> listOf("STANDARD", "MOONSHOT")
        }
        return ordered.firstOrNull { !isLanePaused(it) }
    }

    /** Emit a forensic event for learning + audit trail. */
    fun logTransition(mint: String, symbol: String, from: String, decision: TransitionDecision) {
        try {
            ForensicLogger.lifecycle(
                "LANE_TRANSITION_${decision.decision.name}_4599",
                "mint=${mint.take(10)} sym=$symbol from=$from to=${decision.targetLane ?: "-"} reason=${decision.reason}",
            )
            PipelineHealthCollector.labelInc("LANE_TRANSITION_${decision.decision.name}_4599_FROM_${from.uppercase()}")
        } catch (_: Throwable) {}
    }

    fun statusLine(): String = "$VERSION active — universal promotion pathway for all lanes/traders"
}
