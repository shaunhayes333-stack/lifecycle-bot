package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector

/**
 * V5.0.6732 — §LANE_SCOPED_CAPITAL_FAIRNESS.
 *
 * Operator diagnostic from 6731: `ExitThroughputAuthority6727` is
 * globally blocking 300 admissions with `CASH_STARVED_EXIT_THROUGHPUT_6727`
 * and 313 with `INVENTORY_VELOCITY_OPM_6730`, together 94% of all EXEC
 * blocks. Simultaneously, every specialist lane reports `capitalStarved=false`.
 * The mismatch is real: the global guard evaluates portfolio-wide
 * cashRatio + total open count, while the specialist views only count
 * a lane starved if its own pending intents can't be funded from
 * shared cash (`pending > 0L && sharedCash <= 0.0`).
 *
 * A lane that owns 0 positions and has 3% target allocation is being
 * blocked by the same portfolio-saturation gate as a lane already
 * carrying 80% of open equity. This authority owns the single answer
 * for "does THIS specific lane still have budget headroom?" so the
 * global throughput guard can defer to per-lane fairness before
 * hard-blocking.
 *
 * Contract:
 *  - Returns `hasHeadroom = true` when the lane's own used allocation
 *    plus pending intents is under LANE_HEADROOM_RATIO of its target
 *    allocation.
 *  - Returns `hasHeadroom = false` when the lane has consumed at least
 *    LANE_HEADROOM_RATIO of its target — global throttling is fair.
 *
 * Never mutates state. Non-meme lanes fail open (return `hasHeadroom=true`)
 * so cross-asset parity is preserved.
 */
object LaneCapitalFairness6732 {

    /** Configured meme desk names — must match ToolkitSignalSheet.configuredMemeDesks6599. */
    private val MEME_LANES = setOf(
        "QUALITY", "BLUECHIP", "SHITCOIN", "CYCLIC", "EXPRESS", "CORE",
        "MOONSHOT", "PROJECT_SNIPER", "DIP_HUNTER", "MANIPULATED", "TREASURY", "CASHGEN",
    )
    /** Fraction of a lane's target allocation below which the lane is
     * considered to have headroom. 0.90 → a lane using <90% of its
     * target share is protected from portfolio-wide throughput blocks.
     * Above that, the global gate is fair. */
    private const val LANE_HEADROOM_RATIO = 0.90

    data class Headroom(
        val hasHeadroom: Boolean,
        val lane: String,
        val usedSol: Double,
        val targetSol: Double,
        val utilization: Double,
    )

    private fun normLane(raw: String): String =
        raw.trim().uppercase().replace('-', '_').replace(' ', '_').let {
            when (it) { "BLUE_CHIP" -> "BLUECHIP"; "PRESALE_SNIPE" -> "PROJECT_SNIPER"; else -> it }
        }

    /**
     * Compute per-lane headroom. Non-meme lanes fail open (headroom always true).
     * Any exception path fails open — this authority never blocks by accident.
     */
    fun headroomFor(mode: String, lane: String): Headroom {
        val nl = normLane(lane)
        if (nl.isBlank() || nl !in MEME_LANES) {
            return Headroom(true, nl, 0.0, 0.0, 0.0)
        }
        return try {
            val paperMode = mode.trim().equals("PAPER", true)
            val (sharedCash, sharedEquity) = if (paperMode) {
                val cap = PaperCapitalAuthority6577.snapshot()
                cap.availableCashSol to cap.totalEquitySol
            } else {
                val cap = CanonicalCapitalAuthority6450.snapshot()
                val eq = cap.cashSol + cap.openMarketValueSol
                cap.cashSol to eq
            }
            val positions = CanonicalPositionAuthority6441.openPositions()
            val laneOwned = positions.filter {
                it.mode.equals(mode, true) && (
                    it.lane.equals(nl, true) || (nl == "BLUECHIP" && it.lane.equals("BLUE_CHIP", true))
                )
            }
            val used = laneOwned.sumOf { (it.entryCostSol - it.soldCostBasisSol).coerceAtLeast(0.0) }
            val targetSol = laneTargetSol(nl, sharedEquity)
            val util = if (targetSol > 0.0) used / targetSol else 0.0
            val ok = util < LANE_HEADROOM_RATIO
            if (ok) {
                try { PipelineHealthCollector.labelInc("LANE_HEADROOM_OK_6732_${nl}") } catch (_: Throwable) {}
            } else {
                try { PipelineHealthCollector.labelInc("LANE_HEADROOM_SATURATED_6732_${nl}") } catch (_: Throwable) {}
            }
            Headroom(ok, nl, used, targetSol, util)
        } catch (_: Throwable) {
            Headroom(true, nl, 0.0, 0.0, 0.0)
        }
    }

    /** Convenience: does this lane still have room to open a new position? */
    fun hasHeadroom(mode: String, lane: String): Boolean = headroomFor(mode, lane).hasHeadroom

    /** V5.0.6732 — compute the target SOL allocation for a lane from its
     * expectancy + opportunity weight (same formula ToolkitSignalSheet uses).
     * Kept intentionally simple; the specialist report is the observability
     * surface, this authority is the enforcement surface. */
    private fun laneTargetSol(lane: String, sharedEquity: Double): Double {
        if (sharedEquity <= 0.0) return 0.0
        val weights = MEME_LANES.associateWith { l ->
            val expectancy = try { com.lifecyclebot.engine.LaneExpectancyDamper.sizeMultiplier(l) } catch (_: Throwable) { 1.0 }
            // 6732: opportunity weight is deliberately flat here (1.0). Using
            // qualified counts would tie fairness to scanner traffic, which
            // conflates "this lane has candidates to admit" with "this lane
            // has budget". Budget fairness must be independent of traffic.
            expectancy.coerceIn(0.25, 1.50)
        }
        val weightSum = weights.values.sum().coerceAtLeast(0.01)
        val laneWeight = weights[lane] ?: 1.0
        return sharedEquity * (laneWeight / weightSum)
    }
}
