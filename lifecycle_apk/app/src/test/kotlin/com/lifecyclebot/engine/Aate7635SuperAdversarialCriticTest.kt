
package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7635SuperAdversarialCriticTest {
    private fun world(
        disagreement: Double,
        state: SuperWorldModel7634.LatentState,
        impulseU: Double,
        thesisU: Double,
    ): SuperWorldModel7634.Snapshot {
        fun h(
            horizon: SuperWorldModel7634.Horizon,
            utility: Double,
        ) = SuperWorldModel7634.HorizonForecast(
            horizon = horizon,
            pWin = if (utility > 0.0) 0.65 else 0.35,
            expectedPnlPct = utility,
            failureRisk = if (utility > 0.0) 0.25 else 0.65,
            rugRisk = 0.05,
            dispersionPct = 25.0,
            uncertainty = disagreement,
            utility = utility,
        )
        return SuperWorldModel7634.Snapshot(
            lane = "MOONSHOT",
            latentState = state,
            forecasts = listOf(
                h(SuperWorldModel7634.Horizon.IMPULSE, impulseU),
                h(SuperWorldModel7634.Horizon.TACTICAL, (impulseU + thesisU) / 2.0),
                h(SuperWorldModel7634.Horizon.THESIS, thesisU),
            ),
            trajectorySlopePct = thesisU - impulseU,
            tailOpportunity = 0.5,
            failureRisk = 0.4,
            disagreement = disagreement,
            modelBreadth = 3,
            source = "test",
        )
    }

    @Test fun conflictingHorizonsRaiseFragility() {
        val r = SuperAdversarialCritic7635.review(
            world(0.75, SuperWorldModel7634.LatentState.UNCERTAIN, 15.0, -18.0)
        )
        assertTrue(r.contradictionCount >= 3)
        assertTrue(r.thesisFragility > 0.60)
        assertTrue(r.convictionPenalty > 8.0)
    }

    @Test fun coherentTrajectoryHasLowerPenalty() {
        val r = SuperAdversarialCritic7635.review(
            world(0.05, SuperWorldModel7634.LatentState.TRENDING, 12.0, 28.0)
        )
        assertTrue(r.thesisFragility < 0.55)
        assertTrue(r.convictionPenalty < 10.0)
    }

    @Test fun plannerConsumesCriticPenalty() {
        val planner = File("src/main/kotlin/com/lifecyclebot/engine/SuperIntelligencePlanner7633.kt").readText()
        val oracle = File("src/main/kotlin/com/lifecyclebot/engine/truth/PredictiveEntryOracle6915.kt").readText()
        assertTrue(planner.contains("critic: SuperAdversarialCritic7635.Review? = null"))
        assertTrue(planner.contains("criticPenalty"))
        assertTrue(oracle.contains("SuperAdversarialCritic7635.review(world7634)"))
        assertTrue(oracle.contains("contributions += critic7635.contributionTag()"))
    }
}
