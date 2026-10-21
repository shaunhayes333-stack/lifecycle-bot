package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.CurveShapeFeed8236
import com.lifecyclebot.engine.truth.TradeTape8100
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.8236 — a default is not a reading (every lane), and a fresh curve coin's shape comes from its curve. */
class Aate8236InputTruthTest {
    private val none = LaneInputTruth8236.Inputs8236(mcap = false, momentum = false, buyPressure = false, priceHistory = false, v3 = false)
    private val all = LaneInputTruth8236.Inputs8236(mcap = true, momentum = true, buyPressure = true, priceHistory = true, v3 = true)

    @Test fun anUnhydratedMcapDefersEveryLanesTooLowRule() {
        assertTrue(LaneInputTruth8236.deferFor8236("QUALITY", "MCAP too low: $0 < $500000", none)!!.startsWith(LaneInputTruth8236.MCAP_DEFER))
        assertTrue(LaneInputTruth8236.deferFor8236("BLUECHIP", "MCAP_TOO_LOW: \$0K < \$1M", none)!!.startsWith(LaneInputTruth8236.MCAP_DEFER))
        assertTrue(LaneInputTruth8236.deferFor8236("MOONSHOT", "mcap_too_low_0K_min_1K", none)!!.startsWith(LaneInputTruth8236.MCAP_DEFER))
        assertTrue(LaneInputTruth8236.deferFor8236("DIP_HUNTER", "MCAP_TOO_LOW: \$0K", none)!!.startsWith(LaneInputTruth8236.MCAP_DEFER))
        // a real reading below the bar still refuses; too-high is never an unhydrated cap
        assertNull(LaneInputTruth8236.deferFor8236("QUALITY", "MCAP too low: $4000 < $500000", all))
        assertNull(LaneInputTruth8236.deferFor8236("QUALITY", "MCAP_TOO_HIGH: $1788699", none))
    }

    @Test fun unobservedMomentumAndPressureDeferNotRefuse() {
        assertTrue(LaneInputTruth8236.deferFor8236("EXPRESS", "NO_CONTINUATION: 0.0% < 1.0% (learning=0%)", none)!!.startsWith(LaneInputTruth8236.MOMENTUM_DEFER))
        // observed buy pressure can stand in for momentum (Express synthesises it): the refusal is then a judgement
        assertNull(LaneInputTruth8236.deferFor8236("EXPRESS", "NO_CONTINUATION: 0.0% < 1.0%", none.copy(buyPressure = true)))
        assertTrue(LaneInputTruth8236.deferFor8236("TREASURY", "neutral_momentum;balanced_book_50;steady_velocity", none)!!.startsWith(LaneInputTruth8236.V3_UNSCORED_DEFER))
        assertNull(LaneInputTruth8236.deferFor8236("TREASURY", "weak_momentum_32;positive_pressure_58", all))
        assertTrue(LaneInputTruth8236.deferFor8236("DIP_HUNTER", "NOT_A_DIP: 0.0% < 15%", none)!!.startsWith(LaneInputTruth8236.PRICE_HISTORY_DEFER))
        assertNull(LaneInputTruth8236.deferFor8236("DIP_HUNTER", "NOT_A_DIP: 4.0% < 15%", all))
        assertTrue(LaneInputTruth8236.deferFor8236("CASHGEN", "SELL_PRESSURE: 50%", none)!!.contains("BUY_PRESSURE_UNOBSERVED_DEFER_8236"))
    }

    @Test fun safetyLiquidityAndExistingDefersAreNeverRemapped() {
        assertNull(LaneInputTruth8236.deferFor8236("MOONSHOT", "rugcheck_0_below_min_15", none))
        assertNull(LaneInputTruth8236.deferFor8236("SHITCOIN", "LIQ_TOO_LOW: $400", none))
        assertNull(LaneInputTruth8236.deferFor8236("MANIPULATED", "MCAP_UNHYDRATED_DEFER_8190", none))
        assertNull(LaneInputTruth8236.deferFor8236("CYCLIC", "SELLABILITY_REJECT", none))
    }

    @Test fun observationComesFromMetadataNotValues() {
        val ts = TokenState(mint = "m8236")
        val i = LaneInputTruth8236.observed8236(ts, historyPoints = 1)
        assertFalse(i.mcap); assertFalse(i.momentum); assertFalse(i.buyPressure); assertFalse(i.priceHistory); assertFalse(i.v3)
        ts.lastMcap = 25_000.0; ts.buyPressureObservedAtMs8084 = 1L; ts.priceChangeObservedAtMs8084 = 1L; ts.lastV3Score = 40
        val j = LaneInputTruth8236.observed8236(ts, historyPoints = 3)
        assertTrue(j.mcap && j.momentum && j.buyPressure && j.priceHistory && j.v3)
    }

    @Test fun cryptoDefaultsAreUnobservedOnlyWhenNothingWasStated() {
        assertTrue(LaneInputTruth8236.cryptoInputsUnobserved8236(0, 0, 0.0))
        assertFalse(LaneInputTruth8236.cryptoInputsUnobserved8236(0, 0, 1.7))
        assertFalse(LaneInputTruth8236.cryptoInputsUnobserved8236(12, 9, 0.0))
    }

    @Test fun curveSubscriptionsFollowHeldThenFreshestCandidates() {
        val now = 1_000_000L
        val a = TokenState(mint = "a").also { it.addedToWatchlistAt = now - 60_000L }
        val b = TokenState(mint = "b").also { it.addedToWatchlistAt = now - 10_000L }
        val old = TokenState(mint = "old").also { it.addedToWatchlistAt = now - 3L * 60L * 60_000L }
        val grad = TokenState(mint = "grad").also { it.addedToWatchlistAt = now - 5_000L }
        val set = CurveShapeFeed8236.subscriptionSet8236(setOf("held"), listOf(a, old, grad, b), now,
            hasCurveKey = { true }, graduated = { it == "grad" }, maxCandidates = 2)
        assertEquals(listOf("held", "b", "a"), set.toList())
    }

    @Test fun aCurveOnlyYoungShapeIsStatedFromOneSource() {
        val t0 = 5_000_000L
        val curve = listOf(0L, 16_000L, 32_000L, 48_000L).map { TradeTape8100.Trade(t0 + it, 1e-6 * (1.0 + it / 100_000.0), 0.0, false, priceOnly = true) }
        val s = TradeTape8100.youngShapeOf8236(curve, curve = true)
        assertNotNull(s)
        assertTrue(s!!.runupPct > 0.0)
        assertNull(TradeTape8100.youngShapeOf8236(curve.take(2), curve = true))
    }

    @Test fun bothPromotionPathsReadTheDoctrineBudget() {
        val risk = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/LiveRiskPolicy7807.kt").readText()
        // 8269 uses the reservation-adjusted same-candidate wallet and SOL quote rather
        // than reading a potentially stale global status as a second budget.
        assertTrue(risk.contains("MinPromotion8188.doctrineBudgetSol8236(size, tradeable8153, i.solUsd),"))
        val promo = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/MinPromotion8188.kt").readText()
        assertTrue(promo.contains("val budgetSol8234 = doctrineBudgetSol8236(requestedSol)"))
        val bridge = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SpecialistBrainBridge7542.kt").readText()
        assertTrue(bridge.contains("LaneInputTruth8236.deferFor8236(lane, o.reason, inputs8236)"))
    }
}
