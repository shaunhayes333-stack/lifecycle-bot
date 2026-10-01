
package com.lifecyclebot.engine

import kotlin.math.abs
import kotlin.math.max

/**
 * V5.0.7635 - adversarial scenario critic for the Super Intelligence stack.
 *
 * Pure local red-team review over SuperWorldModel7634. It does not call
 * providers, execute, size, reserve capital or replace hard safety.
 */
object SuperAdversarialCritic7635 {
    enum class ScenarioKind { BULL, BASE, BEAR }

    data class Scenario(
        val kind: ScenarioKind,
        val utility: Double,
        val pWin: Double,
        val expectedPnlPct: Double,
        val failureRisk: Double,
    )

    data class Review(
        val scenarios: List<Scenario>,
        val preferred: ScenarioKind,
        val worstCaseUtility: Double,
        val thesisFragility: Double,
        val contradictionCount: Int,
        val convictionPenalty: Double,
        val criticConfidence: Double,
        val verdict: String,
    ) {
        fun contributionTag(): String {
            return String.format(
                java.util.Locale.US,
                "critic7635(pref=%s,worst=%+.1f,frag=%.2f,contra=%d,pen=%.1f,conf=%.2f,%s)",
                preferred.name,
                worstCaseUtility,
                thesisFragility,
                contradictionCount,
                convictionPenalty,
                criticConfidence,
                verdict,
            )
        }
    }

    fun review(world: SuperWorldModel7634.Snapshot): Review {
        val impulse = world.forHorizon(SuperWorldModel7634.Horizon.IMPULSE)
        val tactical = world.forHorizon(SuperWorldModel7634.Horizon.TACTICAL)
        val thesis = world.forHorizon(SuperWorldModel7634.Horizon.THESIS)

        val baseUtility = listOfNotNull(impulse, tactical, thesis)
            .map { it.utility }
            .average()
            .takeIf { it.isFinite() } ?: 0.0

        val baseP = listOfNotNull(impulse, tactical, thesis)
            .map { it.pWin }
            .average()
            .takeIf { it.isFinite() } ?: 0.50

        val baseE = listOfNotNull(impulse, tactical, thesis)
            .map { it.expectedPnlPct }
            .average()
            .takeIf { it.isFinite() } ?: 0.0

        val baseFail = listOfNotNull(impulse, tactical, thesis)
            .map { it.failureRisk }
            .average()
            .takeIf { it.isFinite() } ?: world.failureRisk

        val uncertainty = listOfNotNull(impulse, tactical, thesis)
            .map { it.uncertainty }
            .average()
            .takeIf { it.isFinite() } ?: 1.0

        val bull = Scenario(
            kind = ScenarioKind.BULL,
            utility = baseUtility + abs(baseE) * 0.25 + world.tailOpportunity * 10.0,
            pWin = (baseP + (1.0 - uncertainty) * 0.12).coerceIn(0.0, 1.0),
            expectedPnlPct = baseE + abs(baseE) * 0.35 + world.tailOpportunity * 20.0,
            failureRisk = (baseFail * 0.65).coerceIn(0.0, 1.0),
        )

        val base = Scenario(
            kind = ScenarioKind.BASE,
            utility = baseUtility,
            pWin = baseP,
            expectedPnlPct = baseE,
            failureRisk = baseFail.coerceIn(0.0, 1.0),
        )

        val bearShock = 12.0 + uncertainty * 18.0 + world.disagreement * 12.0
        val bear = Scenario(
            kind = ScenarioKind.BEAR,
            utility = baseUtility - bearShock,
            pWin = (baseP - 0.18 - world.disagreement * 0.10).coerceIn(0.0, 1.0),
            expectedPnlPct = baseE - bearShock * 1.8,
            failureRisk = (max(baseFail, 0.35) + uncertainty * 0.35).coerceIn(0.0, 1.0),
        )

        var contradictions = 0
        if (impulse != null && thesis != null) {
            if ((impulse.utility > 0.0) != (thesis.utility > 0.0)) contradictions++
            if ((impulse.expectedPnlPct > 0.0) != (thesis.expectedPnlPct > 0.0)) contradictions++
            if (abs(impulse.pWin - thesis.pWin) >= 0.20) contradictions++
        }
        if (world.latentState == SuperWorldModel7634.LatentState.UNCERTAIN) contradictions++
        if (world.latentState == SuperWorldModel7634.LatentState.DISTRIBUTING) contradictions++

        val spread = (bull.utility - bear.utility).coerceAtLeast(0.0)
        val fragility = (
            world.disagreement * 0.30 +
                uncertainty * 0.35 +
                (contradictions / 5.0).coerceIn(0.0, 1.0) * 0.20 +
                (spread / 60.0).coerceIn(0.0, 1.0) * 0.15
            ).coerceIn(0.0, 1.0)

        val penalty = (fragility * 14.0 + if (bear.utility < -20.0) 4.0 else 0.0)
            .coerceIn(0.0, 18.0)

        val preferred = when {
            bear.utility > 0.0 -> ScenarioKind.BULL
            base.utility > 0.0 -> ScenarioKind.BASE
            else -> ScenarioKind.BEAR
        }

        val confidence = (1.0 - fragility).coerceIn(0.0, 1.0)
        val verdict = when {
            world.failureRisk >= 0.72 -> "THESIS_FRAGILE"
            contradictions >= 3 -> "SELF_CONTRADICTORY"
            fragility >= 0.65 -> "HIGH_EPISTEMIC_RISK"
            base.utility > 0.0 && bear.utility > -10.0 -> "ROBUST"
            base.utility > 0.0 -> "POSITIVE_BUT_FRAGILE"
            else -> "NO_ROBUST_EDGE"
        }

        return Review(
            scenarios = listOf(bull, base, bear),
            preferred = preferred,
            worstCaseUtility = bear.utility,
            thesisFragility = fragility,
            contradictionCount = contradictions,
            convictionPenalty = penalty,
            criticConfidence = confidence,
            verdict = verdict,
        )
    }
}
